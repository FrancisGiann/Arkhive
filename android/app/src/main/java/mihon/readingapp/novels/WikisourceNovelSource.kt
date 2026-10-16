package mihon.readingapp.novels

import android.os.SystemClock
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import mihon.readingapp.novels.lnreader.LnReaderExtensionManager
import mihon.readingapp.novels.lnreader.LnReaderInstalledPlugin

@Inject @SingleIn(AppScope::class)
class NovelSources(network: NetworkHelper, val lnReaderExtensions: LnReaderExtensionManager) {
    private val client by lazy { network.client.newBuilder().apply {
        interceptors().removeAll { it is CloudflareInterceptor }
    }.cookieJar(CookieJar.NO_COOKIES).cache(null).followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build() }
    private val bundled: List<NovelSource> by lazy { listOf(WikisourceNovelSource(client)) }
    private val browseScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val registryScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val browsers=java.util.LinkedHashMap<String,NovelCatalogPager>()
    private val adapters = java.util.concurrent.ConcurrentHashMap<String, LnReaderNovelSource>()
    private val availableMutable=MutableStateFlow<List<NovelSource>>(listOf(WikisourceNovelSource(client)))
    val availableState:StateFlow<List<NovelSource>> = availableMutable.asStateFlow()
    // Controlled compiled fixtures may be registered by instrumentation; no executable bundle import.
    internal val fixtures = java.util.concurrent.ConcurrentHashMap<String, NovelSource>()
    private var sourceTrust = emptyMap<String, LnReaderInstalledPlugin>()
    init {
        registryScope.launch {
            // Loading persisted manager state here means downloads can resolve an installed
            // adapter after process restart without first visiting the management screen.
            lnReaderExtensions.snapshot()
            lnReaderExtensions.snapshots.collect { snapshot ->
                val installed = snapshot.installedPlugins.associateBy { it.sourceId }
                val nextTrust = installed
                val changed = (sourceTrust.keys + nextTrust.keys).filter { sourceTrust[it] != nextTrust[it] }.toSet()
                synchronized(browsers) { changed.forEach { browsers[it]?.invalidateForSourceChange() } }
                sourceTrust = nextTrust
                installed.values.forEach { record -> adapters.computeIfAbsent(record.sourceId) { LnReaderNovelSource(lnReaderExtensions, record) } }
                availableMutable.value = buildList {
                    addAll(bundled)
                    installed.values.filter { it.enabled }.sortedBy { it.metadata.name.lowercase() }
                        .forEach { record -> add(adapters.computeIfAbsent(record.sourceId) { LnReaderNovelSource(lnReaderExtensions, record) }) }
                    addAll(fixtures.values.sortedBy { it.name })
                }.distinctBy { it.id }
            }
        }
    }
    val available: List<NovelSource> get() = availableMutable.value

    /** Refresh test-fixture registrations when the user opens the source list. Installed source
     * changes continue to arrive through the manager's snapshots StateFlow collector. */
    internal fun refreshAvailableSources() {
        val installed = lnReaderExtensions.snapshots.value.installedPlugins
        availableMutable.value = buildList {
            addAll(bundled)
            installed.filter { it.enabled }.sortedBy { it.metadata.name.lowercase() }
                .forEach { record -> add(adapters.computeIfAbsent(record.sourceId) { LnReaderNovelSource(lnReaderExtensions, record) }) }
            addAll(fixtures.values.sortedBy { it.name })
        }.distinctBy { it.id }
    }

    fun pager(id:String):NovelCatalogPager = synchronized(browsers) {
        browsers.getOrPut(id) { NovelCatalogPager(browseScope, get(id)) }
    }
    fun get(id: String): NovelSource {
        availableMutable.value.firstOrNull { it.id == id }?.let { return it }
        fixtures[id]?.let { return it }
        if (id.matches(Regex("lnreader\\.[a-f0-9]{32}"))) {
            adapters[id]?.let { return it }
            val persisted = lnReaderExtensions.currentInstalled(id)
            if (persisted != null) return adapters.computeIfAbsent(id) { LnReaderNovelSource(lnReaderExtensions, persisted) }
            // Return a stable proxy during cold startup; each method revalidates persisted trust
            // on IO before any extension code runs. This also preserves queued-job resolution.
            val placeholder = LnReaderInstalledPlugin(id, "", mihon.readingapp.novels.lnreader.LnReaderPluginMetadata(
                id = id, name = "LNReader extension", site = "https://invalid.example/", language = "", version = "",
                bundleUrl = "https://invalid.example/plugin.js"), "0".repeat(64), emptySet(), false, 0)
            return adapters.computeIfAbsent(id) { LnReaderNovelSource(lnReaderExtensions, placeholder) }
        }
        error("This novel source is unavailable. Saved chapters can still be read offline.")
    }

    internal fun closeForTest() {
        browseScope.cancel()
        registryScope.cancel()
        synchronized(browsers) { browsers.clear() }
    }
}

/** Arkhive-authored adapter to the official, unprotected MediaWiki API. No login/cookies/scripts. */
class WikisourceNovelSource internal constructor(private val client: OkHttpClient,
    override val id: String = "wikisource.en", override val name: String = "Wikisource · English novels",
    private val requestIntervalMillis: Long = 1100) : NovelSource {
    override val site = "https://en.wikisource.org"
    private val requests = Mutex()
    private var nextRequest = 0L
    private var blockedUntil = 0L
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun catalog(page: Int, search: String, genre: String?): NovelCatalogPage {
        require(page in 1..40)
        val terms = search.trim().take(120).replace("\"", "\\\"")
        val category = when (genre) { "Fantasy" -> "Fantasy novels"; "Science fiction" -> "Science fiction novels"; else -> "Novels" }
        val query = listOf(terms.takeIf { it.isNotBlank() }?.let { "\"$it\"" }, "incategory:\"$category\"").filterNotNull().joinToString(" ")
        val root = api(mapOf("action" to "query", "list" to "search", "srsearch" to query,
            "srnamespace" to "0", "srlimit" to "24", "sroffset" to ((page - 1) * 24).toString(), "srprop" to ""))
        return WikisourceParser.catalog(root, page, category)
    }

    override suspend fun detail(path: String): SourceNovel = withContext(Dispatchers.Default) {
        val parsed = parsed(path, "text|categories")
        WikisourceParser.detail(path, parsed, site)
    }

    override suspend fun chapter(path: String): String = withContext(Dispatchers.Default) {
        val parsed = parsed(path, "text")
        val resolvedTitle = parsed["title"]?.jsonPrimitive?.contentOrNull
            ?: error("Wikisource did not identify the resolved chapter.")
        require(NovelHtmlPolicy.validTitle(resolvedTitle)) { "Wikisource resolved this chapter to an unsupported page." }
        val text = parsed["text"]?.jsonPrimitive?.contentOrNull ?: error("The source returned no chapter text.")
        NovelHtmlPolicy.sanitize(NovelHtmlPolicy.wikiBody(text))
    }
    private suspend fun parsed(path: String, prop: String): JsonObject {
        require(NovelHtmlPolicy.validTitle(path)) { "Unsupported Wikisource title path." }
        // MediaWiki resolves only its own page redirects here. The HTTP client still rejects
        // transport redirects, and the returned title/body are validated before they are read.
        return api(mapOf("action" to "parse", "page" to path, "prop" to prop, "redirects" to "1"))["parse"]?.jsonObject
            ?: error("This source page is unavailable.")
    }
    fun url(title: String): String = WikisourceParser.url(site, title)
    private suspend fun api(params: Map<String, String>): JsonObject = withContext(Dispatchers.IO) {
        val url = "$site/w/api.php".toHttpUrl().newBuilder().apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
            addQueryParameter("format", "json"); addQueryParameter("formatversion", "2"); addQueryParameter("maxlag", "5")
        }.build()
        requests.withLock {
            check(SystemClock.elapsedRealtime() >= blockedUntil) { "Wikisource is rate limited. Retry after its cooldown." }
            delay((nextRequest - SystemClock.elapsedRealtime()).coerceAtLeast(0)); nextRequest = SystemClock.elapsedRealtime() + requestIntervalMillis
            client.newCall(Request.Builder().url(url).header("Accept", "application/json")
                .header("User-Agent", "Arkhive/0.4 (on-demand novel reader; https://github.com/FrancisGiann/Arkhive)").build()).await().use { response ->
                if (response.code == 429) {
                    blockedUntil = SystemClock.elapsedRealtime() + (response.header("Retry-After")?.toLongOrNull()?.coerceIn(30, 3600) ?: 300) * 1000
                    error("Wikisource is rate limited (HTTP429). Retry after its cooldown.")
                }
                check(response.isSuccessful) { "Wikisource is unavailable (HTTP${response.code})." }
                check(response.body.contentLength() <= 2_000_000) { "This source response exceeds the chapter limit." }
                val bytes = ByteArrayOutputStream()
                response.body.byteStream().use { stream ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        coroutineContext.ensureActive(); val n = stream.read(buffer); if (n < 0) break
                        check(bytes.size() + n <= 2_000_000) { "This source response exceeds the chapter limit." }; bytes.write(buffer, 0, n)
                    }
                }
                val root = json.parseToJsonElement(bytes.toString("UTF-8")).jsonObject
                root["error"]?.jsonObject?.let { problem -> error(problem["info"]?.jsonPrimitive?.content ?: "Wikisource could not supply this page.") }
                root
            }
        }
    }
}

/** Reader markup contains no scripts, event handlers, external resources, forms or source CSS. */
object NovelHtmlPolicy {
    fun validTitle(title: String) = title.isNotBlank() && title.length <= 500 && title.none { it.code < 32 } &&
        !Regex("(?i)^(Author|File|Page|Index|Template|Category|Special|MediaWiki|Talk|User|Wikisource):").containsMatchIn(title)
    fun workName(title: String) = title.substringBefore('/').replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim().lowercase()
    fun wikiBody(html: String): String {
        val doc = Jsoup.parse(html)
        val output = doc.select(".prp-pages-output")
        val body = if (output.isNotEmpty()) output.joinToString("\n") { it.outerHtml() } else doc.select(".mw-parser-output").first()?.outerHtml() ?: doc.body().html()
        val clean = Jsoup.parseBodyFragment(body)
        clean.select("script,style,form,iframe,object,embed,.ws-header,.wst-header,.ws-noexport,.noprint,.licenseContainer,.licenseBanner,.dynlayout-exempt,.pagenum,.pagenum-anchor,.mw-editsection,.wst-footer").remove()
        return clean.body().html()
    }
    fun isRedirectStub(html: String): Boolean {
        val document = Jsoup.parseBodyFragment(html)
        val paragraphs = document.select("p")
        val text = document.body().text().replace('\u00a0', ' ').trim()
        if (text.length > 512) return false
        if (paragraphs.size == 1 && document.select("ul,ol,li").isEmpty()) {
            val paragraphText = paragraphs.single().text().replace('\u00a0', ' ').trim()
            return text == paragraphText && Regex("(?i)^redirect\\s+to\\s*:\\s*\\S.{0,500}$").matches(text)
        }

        // MediaWiki's cached redirect notice is rendered as a label paragraph followed by
        // one target list item. Keep this deliberately narrow so chapter prose stays valid.
        val lists = document.select("ul,ol")
        val items = document.select("li")
        if (paragraphs.size != 1 || lists.size != 1 || lists.single().tagName() != "ul" || items.size != 1) return false
        val label = paragraphs.single().text().replace('\u00a0', ' ').trim()
        val target = items.single().text().replace('\u00a0', ' ').trim()
        return label.equals("Redirect to:", ignoreCase = true) && target.isNotBlank() &&
            text == "$label $target" && document.body().children().size == 1 &&
            document.body().children().single().let { wrapper ->
                wrapper.select("p").size == 1 && wrapper.select("ul,ol,li").size == 2 &&
                    wrapper.text().replace('\u00a0', ' ').trim() == text
            }
    }
    fun sanitize(html: String): String {
        require(html.toByteArray().size <= 2_000_000) { "Chapter text exceeds the supported limit." }
        require(!isRedirectStub(html)) { "Wikisource returned a redirect stub instead of chapter text. Reopen the chapter online to repair its saved copy." }
        val document = Jsoup.parseBodyFragment(html)
        document.select("script,style,iframe,object,embed,form,input,button,video,audio,img,svg,canvas").remove()
        document.select("a").unwrap()
        val safe = Jsoup.clean(document.body().html(), Safelist.none().addTags("p", "br", "h1", "h2", "h3", "h4", "blockquote", "em", "strong", "b", "i", "u", "s", "ul", "ol", "li", "pre", "code", "hr", "div", "section", "sup", "sub"))
        require(Jsoup.parse(safe).text().length >= 32) { "The source returned no readable chapter text." }
        return safe
    }
}
