package mihon.readingapp.discovery

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import mihon.domain.discovery.*
import okhttp3.OkHttpClient
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Human-triggered public metadata only. No accounts, HTML scraping, books or executable downloads. */
@SingleIn(AppScope::class)
class PublicDiscoveryRepository internal constructor(
    private val context: Context,
    private val preferences: BasePreferences,
    private val client: OkHttpClient,
) {
    @Inject constructor(context: Context, network: NetworkHelper, preferences: BasePreferences) : this(
        context,
        preferences,
        network.client.newBuilder().apply {
            interceptors().removeAll { it is CloudflareInterceptor }
        }.cookieJar(CookieJar.NO_COOKIES).cache(null).followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build(),
    )
    private val requests = Mutex()
    // Memory-only browse session survives opening a detail/source and returning to this tab.
    val browsing by lazy { CatalogPager(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) {
        query, page, force -> catalog(query, force, page)
    } }
    private var nextStart = 0L
    private val blockedUntil = mutableMapOf<String, Long>()
    private val directory get() = File(context.cacheDir, "arkhive-public-metadata").apply { mkdirs() }
    private data class Payload(val text: String, val fetchedAt: Long, val date: String?, val cached: Boolean,
        val stale: Boolean, val message: String?)
    fun catalogUrl(query: CatalogQuery, page: Int = 1): HttpUrl {
        require(page in 1..CatalogPager.MAX_PAGES)
        if (query.window != CatalogWindow.SUBJECT)
            return "https://openlibrary.org/trending/${query.window.path}.json".toHttpUrl().newBuilder()
                .addQueryParameter("limit", "24").apply { if (page > 1) addQueryParameter("page", page.toString()) }.build()
        val terms = listOfNotNull(query.focus.subject?.let { "subject:$it" }, query.genre.subject?.let { "subject:$it" },
            query.search.trim().take(120).takeIf(String::isNotBlank)?.let { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" })
        return "https://openlibrary.org/search.json".toHttpUrl().newBuilder()
            .addQueryParameter("q", terms.joinToString(" AND ").ifBlank { "subject:fiction" })
            .addQueryParameter("limit", "24")
            .apply { if (page > 1) addQueryParameter("offset", ((page - 1) * 24).toString()) }
            .addQueryParameter("fields", "key,title,author_name,cover_i,subject,first_publish_year,edition_key")
            .build()
    }
    suspend fun catalog(query: CatalogQuery, force: Boolean = false, page: Int = 1): CatalogLoad = try {
        val url = catalogUrl(query, page)
        val data = payload(url, force)
        val feed = withContext(Dispatchers.Default) { DiscoveryDataPolicy.parseCatalog(data.text, query, url.toString(), data.fetchedAt, data.date, page) }
        CatalogLoad(feed, data.cached, data.stale, data.message)
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) { CatalogLoad(null, message = error.message ?: "Catalog information is unavailable. Try again later.") }

    suspend fun editions(title: CatalogTitle): Result<List<CatalogEdition>> = try {
        require(Regex("/works/OL[0-9]+W").matches(title.key))
        val url = "https://openlibrary.org${title.key}/editions.json".toHttpUrl().newBuilder().addQueryParameter("limit", "10").build()
        val data = payload(url, false)
        Result.success(withContext(Dispatchers.Default) { DiscoveryDataPolicy.parseEditions(data.text) })
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) { Result.failure(error) }

    suspend fun store(isbn: String, country: String): StoreLookup {
        val checked = System.currentTimeMillis()
        val valid = DiscoveryDataPolicy.validIsbn(isbn) ?: return StoreLookup(emptyList(),
            "Enter a valid ISBN10 or ISBN13 for the edition.", checked, "", country, state = StoreLookupState.INVALID_INPUT)
        // Open Library supplies edition metadata only. It is never presented as a store offer.
        val catalogUrl = "https://openlibrary.org/api/books".toHttpUrl().newBuilder()
            .addQueryParameter("bibkeys", "ISBN:$valid").addQueryParameter("format", "json").addQueryParameter("jscmd", "data").build()
        var catalog: Pair<CatalogEdition, String>? = null
        var catalogError: String? = null
        try {
            val data = payload(catalogUrl, false)
            catalog = withContext(Dispatchers.Default) { DiscoveryDataPolicy.parseIsbnCatalogue(data.text, valid) }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (error: Exception) { catalogError = error.message ?: "Catalogue unavailable." }
        if (country !in java.util.Locale.getISOCountries()) return StoreLookup(emptyList(),
            "Choose a valid store region to check price information.", checked, catalogUrl.toString(), country,
            catalogue = catalog?.first, catalogueUrl = catalog?.second, state = StoreLookupState.INVALID_INPUT)
        val url = "https://www.googleapis.com/books/v1/volumes".toHttpUrl().newBuilder()
            .addQueryParameter("q", "isbn:$valid").addQueryParameter("country", country).addQueryParameter("maxResults", "10").build()
        return try {
            val data = payload(url, false, ttl = 60 * 60 * 1000L)
            val result = withContext(Dispatchers.Default) { DiscoveryDataPolicy.parseOffers(data.text, valid, country, url.toString(), data.fetchedAt) }
            result.copy(catalogue = catalog?.first, catalogueUrl = catalog?.second,
                state = if (result.offers.isNotEmpty() || catalog != null) StoreLookupState.MATCHES else if (catalogError != null) StoreLookupState.PROVIDER_UNAVAILABLE else StoreLookupState.EMPTY,
                providerFailed = catalogError != null,
                message = when {
                    data.stale -> data.message ?: "Saved price information may have changed."
                    result.offers.isEmpty() && catalog != null -> "Edition found. Google Books returned no store information for it; price and stock are unavailable."
                    catalogError != null -> "Catalogue lookup unavailable. $catalogError"
                    else -> result.message
                })
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (error: Exception) { StoreLookup(emptyList(),
              "Price provider unavailable. ${error.message ?: "Try again later."}" + if (catalog != null) " The catalogue edition is shown below without a price." else "",
              checked, url.toString(), country, catalog?.first, catalog?.second, true, StoreLookupState.PROVIDER_UNAVAILABLE) }
    }

    private suspend fun payload(url: HttpUrl, force: Boolean, ttl: Long = 6 * 60 * 60 * 1000L): Payload = withContext(Dispatchers.IO) {
        require(url.isHttps && url.host in setOf("openlibrary.org", "www.googleapis.com"))
        val keepCache = !preferences.incognitoMode.get()
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(directory, "$hash.json")
        val old = if (keepCache) readCache(file, url.toString()) else null
        val now = System.currentTimeMillis()
        if (!force && old != null && now - old.fetchedAt in 0..ttl) return@withContext old
        try {
            requests.withLock {
                val blocked = blockedUntil[url.host] ?: 0
                check(System.currentTimeMillis() >= blocked) { "${provider(url)} is rate limited. Try again after the provider's cooldown." }
                // Open Library's default maximum is one request per second; metadata is loaded on demand.
                delay((nextStart - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
                nextStart = android.os.SystemClock.elapsedRealtime() + 1_100
                val request = Request.Builder().url(url).header("User-Agent", "ArkhivePrivateReader/0.3 (on-demand book metadata)")
                    .header("Accept", "application/json").build()
                client.newCall(request).await().use { response ->
                    if (response.code == 429) {
                        val seconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(60, 3600) ?: 300
                        blockedUntil[url.host] = System.currentTimeMillis() + seconds * 1000
                        error("${provider(url)} is rate limited (HTTP429). Store/catalog data is unavailable until its cooldown ends.")
                    }
                    check(response.isSuccessful) { "${provider(url)} is unavailable (HTTP${response.code}). No replacement data was generated." }
                    val body = response.body
                    check(body.contentLength() <= 2_000_000) { "The provider response is too large." }
                    val out = ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer); if (count < 0) break
                            check(out.size() + count <= 2_000_000) { "The provider response is too large." }
                            out.write(buffer, 0, count)
                        }
                    }
                    val text = out.toByteArray().toString(Charsets.UTF_8)
                    DiscoveryDataPolicy.root(text) // Never cache an HTML error page as a successful feed.
                    val payload = Payload(text, System.currentTimeMillis(), response.header("Date"), false, false, null)
                    if (keepCache) runCatching { writeCache(file, url.toString(), payload) }
                    payload
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (error: Exception) {
            old?.copy(stale = true, message = "Showing saved ${provider(url)} data from an earlier check. ${error.message ?: "Provider unavailable."}")
                ?: throw error
        }
    }
    private fun provider(url: HttpUrl) = if (url.host == "openlibrary.org") "Open Library" else "Google Books"
    private fun readCache(file: File, endpoint: String): Payload? = runCatching {
        if (!file.isFile || file.length() > 2_100_000) return@runCatching null
        val obj = DiscoveryDataPolicy.root(file.readText())
        if (obj["endpoint"]?.jsonPrimitive?.content != endpoint) return@runCatching null
        val time = obj["fetchedAt"]!!.jsonPrimitive.long
        val text = obj["body"]!!.jsonPrimitive.content
        Payload(text, time, obj["date"]?.jsonPrimitive?.contentOrNull, true, false, null)
    }.getOrNull()
    private fun writeCache(file: File, endpoint: String, data: Payload) {
        val text = buildJsonObject {
            put("endpoint", endpoint); put("fetchedAt", data.fetchedAt); put("body", data.text)
            data.date?.let { put("date", it) }
        }.toString()
        val temp = File(file.parentFile, file.name + ".part")
        temp.writeText(text)
        if (!temp.renameTo(file)) temp.delete()
        directory.listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending(File::lastModified)
            .drop(24).forEach(File::delete)
    }
}
