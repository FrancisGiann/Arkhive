package mihon.readingapp.discovery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import mihon.app.di.appGraph
import mihon.domain.discovery.CatalogQuery
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Controlled HTTP fixtures exercise real Android cache/preferences; they are not live provider evidence. */
@RunWith(AndroidJUnit4::class)
class PublicDiscoveryRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val preferences get() = context.appGraph.basePreferences
    private var previousIncognito = false
    private val ownFiles = mutableListOf<File>()
    private val capture get() = instrumentation.context.assets.open("discovery/openlibrary-manga.json").bufferedReader().use { it.readText() }
    @Before fun savePreferences() { previousIncognito = preferences.incognitoMode.get(); preferences.incognitoMode.set(false) }
    @After fun restoreOwnState() {
        ownFiles.forEach(File::delete)
        preferences.incognitoMode.set(previousIncognito)
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit().commit()
    }
    private fun fixtureClient(code: Int = 200, body: String = capture, request: (okhttp3.Request) -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            request(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Controlled fixture")
                .header("Date", "Tue, 13 Oct 2026 20:10:00 GMT").body(body.toResponseBody()).build()
        }.build()
    private fun offlineClient() = OkHttpClient.Builder().addInterceptor { throw IOException("Controlled offline transport") }.build()
    private fun repository(client: OkHttpClient) = PublicDiscoveryRepository(context, preferences, client)
    private fun ownQuery() = CatalogQuery(search = "original-discovery-runtime-${UUID.randomUUID()}")
    private fun cacheFile(repo: PublicDiscoveryRepository, query: CatalogQuery): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(repo.catalogUrl(query).toString().toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "arkhive-public-metadata"), "$hash.json").also(ownFiles::add)
    }
    @Test fun cachedProviderFeedSurvivesFreshRepositoryAndOfflineRefreshWithoutChangingTimestamp() = runBlocking {
        var calls = 0
        val repo = repository(fixtureClient { calls++; assertNull(it.header("Authorization")); assertNull(it.header("Cookie")) })
        val query = ownQuery(); val file = cacheFile(repo, query)
        val live = repo.catalog(query)
        assertNotNull(live.feed); assertFalse(live.cached); assertTrue(file.isFile)
        assertEquals("鬼滅の刃 1", live.feed!!.titles.first().title)
        val offline = repository(offlineClient())
        val fresh = offline.catalog(query)
        assertTrue(fresh.cached); assertFalse(fresh.stale)
        assertEquals(live.feed!!.provenance.fetchedAt, fresh.feed!!.provenance.fetchedAt)
        val stale = offline.catalog(query, force = true)
        assertTrue(stale.cached); assertTrue(stale.stale); assertTrue(stale.message!!.contains("Showing saved"))
        assertEquals(live.feed!!.titles, stale.feed!!.titles)
        assertEquals(live.feed!!.provenance.fetchedAt, stale.feed!!.provenance.fetchedAt)
        assertEquals(1, calls)
    }
    @Test fun incognitoSkipsBothExistingPersistentCacheAndNewCacheWrites() = runBlocking {
        val repo = repository(fixtureClient()); val query = ownQuery(); val file = cacheFile(repo, query)
        assertNotNull(repo.catalog(query).feed); val saved = file.readBytes()
        preferences.incognitoMode.set(true)
        val result = repository(offlineClient()).catalog(query)
        assertNull(result.feed); assertFalse(result.cached)
        assertArrayEquals(saved, file.readBytes())
        val other = ownQuery(); val freshFile = cacheFile(repo, other)
        assertNotNull(repo.catalog(other).feed)
        assertFalse(freshFile.exists())
    }
    @Test fun rateLimitedUnavailableAndGenuinelyEmptyFeedsStayHonest() = runBlocking {
        var calls = 0
        val throttled = repository(fixtureClient(code = 429) { calls++ })
        val query = ownQuery(); cacheFile(throttled, query)
        val first = throttled.catalog(query)
        val second = throttled.catalog(query, force = true)
        assertNull(first.feed); assertNull(second.feed); assertTrue(first.message!!.contains("rate limited")); assertEquals(1, calls)
        val empty = repository(fixtureClient(body = """{"docs":[],"numFound":0}"""))
        val emptyQuery = ownQuery(); cacheFile(empty, emptyQuery)
        val result = empty.catalog(emptyQuery)
        assertNotNull(result.feed); assertTrue(result.feed!!.titles.isEmpty()); assertEquals(0L, result.feed!!.total)
    }
}
