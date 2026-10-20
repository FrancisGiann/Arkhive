package mihon.readingapp.novels.lnreader

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Only self-authored source bytes and fixture.invalid transport responses are executed here. */
@RunWith(AndroidJUnit4::class)
class LnReaderSandboxRuntimeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun commonJsHelpersPromiseFetchStorageAndFreshIsolationWork() = runBlocking {
        val fixtureFetch = FixtureFetch()
        val runtime = LnReaderSandboxRuntime(context, fixtureFetch)
        val support = runtime.supportStatus()
        assertTrue("Isolated JS support unavailable: ${support.reason}; missing=${support.missingFeatures}", support.supported)

        val first = runtime.execute(
            pluginId = "fixture-source", pluginName = "Authored fixture", pluginSite = "https://fixture.invalid/",
            pluginVersion = "1.0", bundle = COMMON_JS.toByteArray(), operation = "searchNovels",
            arguments = listOf(JsonPrimitive("spring"), JsonPrimitive(2)),
            approvedOrigins = setOf("https://fixture.invalid:443"),
        )
        val value = requireNotNull(first.value).jsonObject
        assertEquals("spring", value["term"]?.jsonPrimitive?.content)
        assertEquals("Authored Novel", value["title"]?.jsonPrimitive?.content)
        assertEquals("x-fixture-ok", value["header"]?.jsonPrimitive?.content)
        assertEquals("fixture body", value["body"]?.jsonPrimitive?.content)
        assertEquals("中国", value["gbk"]?.jsonPrimitive?.content)
        assertEquals("parser", value["parsed"]?.jsonPrimitive?.content)
        assertEquals("2024-01-02", value["date"]?.jsonPrimitive?.content)
        assertEquals("Completed", value["status"]?.jsonPrimitive?.content)
        assertEquals("XCheckbox", value["filter"]?.jsonPrimitive?.content)
        assertEquals("true", value["absolute"]?.jsonPrimitive?.content)
        assertTrue(value["encoded"]?.jsonPrimitive?.content.orEmpty().contains("%E4"))
        assertEquals("undefined/undefined/undefined", value["ambient"]?.jsonPrimitive?.content)
        assertEquals("true", value["protocolRelative"]?.jsonPrimitive?.content)
        assertEquals(2, first.requests)
        assertEquals("1", first.storage["calls"]?.jsonObject?.get("value")?.jsonPrimitive?.content)
        assertTrue(first.storage["calls"]?.jsonObject?.get("created")?.jsonPrimitive?.content.orEmpty().contains("T"))
        assertEquals(2, fixtureFetch.requestCount)

        val second = runtime.execute(
            pluginId = "fixture-source", pluginName = "Authored fixture", pluginSite = "https://fixture.invalid/",
            pluginVersion = "1.0", bundle = COMMON_JS.toByteArray(), operation = "parseChapter",
            arguments = listOf(JsonPrimitive("chapter")), approvedOrigins = setOf("https://fixture.invalid:443"),
            storage = first.storage,
        )
        assertEquals("undefined", second.value?.jsonPrimitive?.content)
        assertEquals("2", second.storage["calls"]?.jsonObject?.get("value")?.jsonPrimitive?.content)
    }

    @Test
    fun cancelingAnUndispatchedSupportCheckDoesNotPoisonTheNextProviderConnection() = runBlocking {
        val runtime = LnReaderSandboxRuntime(context, FixtureFetch())
        val leavingScreen = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            runtime.supportStatus()
        }
        assertFalse("The test must cancel while support is still waiting for the provider bind.", leavingScreen.isCompleted)
        leavingScreen.cancelAndJoin()

        repeat(2) { index ->
            val support = withTimeout(20_000) { runtime.supportStatus() }
            assertTrue("Provider reconnect ${index + 1} failed after screen cancellation: ${support.reason}", support.supported)
        }
        val operation = runtime.execute(
            "fixture-source", "Authored fixture", "https://fixture.invalid/", "1.0", COMMON_JS.toByteArray(),
            "parseChapter", listOf(JsonPrimitive("after canceled support")), setOf("https://fixture.invalid:443"),
        )
        assertEquals("undefined", operation.value?.jsonPrimitive?.content)
    }

    @Test
    fun repeatedSupportChecksCloseAndReacquireBeforeFreshPluginOperations() = runBlocking {
        val runtime = LnReaderSandboxRuntime(context, FixtureFetch())
        repeat(3) { index ->
            val support = withTimeout(20_000) { runtime.supportStatus() }
            assertTrue("Support check ${index + 1} failed: ${support.reason}", support.supported)
        }

        val first = runtime.execute(
            "fixture-source", "Authored fixture", "https://fixture.invalid/", "1.0", COMMON_JS.toByteArray(),
            "parseChapter", listOf(JsonPrimitive("first")), setOf("https://fixture.invalid:443"),
        )
        assertEquals("undefined", first.value?.jsonPrimitive?.content)
        val supportAfterExecution = runtime.supportStatus()
        assertTrue("The provider must remain reconnectable after repeated checks and execution: ${supportAfterExecution.reason}", supportAfterExecution.supported)
        val second = runtime.execute(
            "fixture-source", "Authored fixture", "https://fixture.invalid/", "1.0", COMMON_JS.toByteArray(),
            "parseChapter", listOf(JsonPrimitive("second")), setOf("https://fixture.invalid:443"),
        )
        assertEquals("undefined", second.value?.jsonPrimitive?.content)
    }

    @Test
    fun unknownRequireAndCpuTimeoutFailClosedAndNextOperationRecovers() = runBlocking {
        val runtime = LnReaderSandboxRuntime(context, FixtureFetch())
        val unknown = runCatching {
            runtime.execute("fixture-source", "Authored fixture", "https://fixture.invalid/", "1.0",
                UNKNOWN_MODULE.toByteArray(), "searchNovels", emptyList(), setOf("https://fixture.invalid:443"))
        }.exceptionOrNull()
        assertTrue("An unlisted CommonJS package must fail explicitly: $unknown", unknown?.message.orEmpty().contains("Unsupported module"))

        val timedOut = runCatching {
            runtime.execute("fixture-source", "Authored fixture", "https://fixture.invalid/", "1.0",
                CPU_LOOP.toByteArray(), "searchNovels", emptyList(), setOf("https://fixture.invalid:443"))
        }.exceptionOrNull()
        assertTrue("A CPU loop must hit the hard operation deadline: $timedOut", timedOut is TimeoutCancellationException)

        val recovered = runtime.execute(
            "fixture-source", "Authored fixture", "https://fixture.invalid/", "1.0", COMMON_JS.toByteArray(),
            "parseChapter", listOf(JsonPrimitive("after timeout")), setOf("https://fixture.invalid:443"),
        )
        assertEquals("undefined", recovered.value?.jsonPrimitive?.content)
    }

    @Test
    fun currentOfficialManifestListsUnsupportedEntriesWithoutFetchingBundles() = runBlocking {
        val isolatedFiles = File(context.cacheDir, "lnreader-official-manifest-test-${System.currentTimeMillis()}")
        val isolatedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = isolatedFiles
        }
        try {
            val officialBytes = InstrumentationRegistry.getInstrumentation().context.assets
                .open("official-plugins-v3-manifest.json").use { it.readBytes() }
            val fixtureFetch = FixtureFetch().apply { manifestBytes = officialBytes }
            val manager = LnReaderExtensionManager(isolatedContext, fixtureFetch, LnReaderSandboxRuntime(context, fixtureFetch))
            val repo = "https://fixture.invalid/official-plugins.json"
            manager.addRepository(repo)
            val refreshed = manager.refreshRepository(repo)
            assertEquals("The pinned upstream v3 manifest should remain discoverable as metadata.", 301, refreshed.metadata.size)
            assertTrue(refreshed.metadata.single { it.id == "reinowuxia" }.compatibilityError.orEmpty().contains("public HTTPS"))
            assertTrue(refreshed.metadata.single { it.id == "komga" }.compatibilityError.orEmpty().contains("public HTTPS"))
            assertEquals(2, refreshed.metadata.count { it.customCss && it.compatibilityError != null })
            assertEquals(0, fixtureFetch.bundleFetches)
            assertEquals(0, fixtureFetch.pluginMethodExecutions)
            val persisted = LnReaderExtensionManager(isolatedContext, fixtureFetch, LnReaderSandboxRuntime(context, fixtureFetch)).snapshot()
            assertEquals(301, persisted.repositories.single { it.url == repo }.metadata.size)
            assertEquals("url", persisted.repositories.single { it.url == repo }.metadata.single { it.id == "komga" }.site)
        } finally {
            isolatedFiles.deleteRecursively()
        }
    }

    @Test
    fun repositoryReviewInstallDisableAndUninstallNeverRunOnListing() = runBlocking {
        val fixtureFetch = FixtureFetch()
        val runtime = LnReaderSandboxRuntime(context, fixtureFetch)
        val isolatedFiles = File(context.cacheDir, "lnreader-manager-test-${System.currentTimeMillis()}")
        val isolatedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = isolatedFiles
        }
        try {
        val manager = LnReaderExtensionManager(isolatedContext, fixtureFetch, runtime)
        val repo = "https://fixture.invalid/plugins.json?run=${System.currentTimeMillis()}"
        manager.addRepository(repo)
        val refreshed = manager.refreshRepository(repo)
        assertEquals(3, refreshed.metadata.size)
        val insecureHttp = refreshed.metadata.single { it.id == "reinowuxia" }
        val invalidSite = refreshed.metadata.single { it.id == "komga" }
        assertTrue(insecureHttp.compatibilityError.orEmpty().contains("public HTTPS"))
        assertTrue(invalidSite.compatibilityError.orEmpty().contains("public HTTPS"))
        assertTrue("Unsafe entries remain visible but cannot be staged.",
            runCatching { manager.stagePlugin(repo, "reinowuxia") }.exceptionOrNull()?.message.orEmpty().contains("public HTTPS"))
        assertEquals(0, fixtureFetch.bundleFetches)
        val reopened = LnReaderExtensionManager(isolatedContext, fixtureFetch, runtime).snapshot()
        assertEquals("url", reopened.repositories.single { it.url == repo }.metadata.single { it.id == "komga" }.site)

        val review = manager.stagePlugin(repo, "fixture-source")
        assertEquals("fixture-source", review.metadata.id)
        assertTrue(manager.discardReview(review.reviewToken))
        assertFalse(manager.discardReview(review.reviewToken))
        val approvedReview = manager.stagePlugin(repo, "fixture-source")
        assertEquals(2, fixtureFetch.bundleFetches)
        assertEquals(0, fixtureFetch.pluginMethodExecutions)
        val installed = manager.installReviewed(approvedReview.reviewToken, approvedReview.sha256, approvedReview.requiredOrigins)
        assertTrue(installed.enabled)
        val catalogCall = manager.execute(installed.sourceId, "searchNovels", listOf(JsonPrimitive("spring"), JsonPrimitive(1)))
        assertEquals("Authored Novel", catalogCall.value?.jsonObject?.get("title")?.jsonPrimitive?.content)

        // A same-version bundle hash change must invalidate the old trust confirmation.
        fixtureFetch.currentBundle = COMMON_JS.replace("Authored Novel", "Updated Novel").toByteArray()
        manager.refreshRepository(repo)
        val changed = manager.stagePlugin(repo, "fixture-source")
        assertEquals(installed.sha256, changed.existingSha256)
        assertTrue(changed.sha256 != installed.sha256)
        val staleTrust = runCatching {
            manager.installReviewed(changed.reviewToken, installed.sha256, changed.requiredOrigins)
        }.exceptionOrNull()
        assertTrue(staleTrust?.message.orEmpty().contains("hash does not match"))
        val reviewedAgain = manager.stagePlugin(repo, "fixture-source")
        manager.installReviewed(reviewedAgain.reviewToken, reviewedAgain.sha256, reviewedAgain.requiredOrigins)
        val updatedCatalog = manager.execute(installed.sourceId, "searchNovels", listOf(JsonPrimitive("spring"), JsonPrimitive(1)))
        assertEquals("Updated Novel", updatedCatalog.value?.jsonObject?.get("title")?.jsonPrimitive?.content)

        manager.setEnabled(installed.sourceId, false)
        val disabled = runCatching { manager.execute(installed.sourceId, "searchNovels", emptyList()) }.exceptionOrNull()
        assertTrue(disabled?.message.orEmpty().contains("disabled"))
        manager.uninstall(installed.sourceId)
        assertTrue(manager.snapshot().installedPlugins.none { it.sourceId == installed.sourceId })
        } finally {
            isolatedFiles.deleteRecursively()
        }
    }

    @Test
    fun trustUpdatesAndDisablingCancelTheirActiveBrokeredOperations() = runBlocking {
        val isolatedFiles = File(context.cacheDir, "lnreader-revocation-test-${System.currentTimeMillis()}")
        val isolatedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = isolatedFiles
        }
        try {
            val fixtureFetch = FixtureFetch()
            val manager = LnReaderExtensionManager(isolatedContext, fixtureFetch, LnReaderSandboxRuntime(context, fixtureFetch))
            val repo = "https://fixture.invalid/plugins.json?revocation=${System.currentTimeMillis()}"
            manager.addRepository(repo)
            manager.refreshRepository(repo)
            val staged = manager.stagePlugin(repo, "fixture-source")
            val installed = manager.installReviewed(staged.reviewToken, staged.sha256, staged.requiredOrigins)
            val sameBundleReview = manager.stagePlugin(repo, "fixture-source")
            assertEquals(installed.sha256, sameBundleReview.sha256)

            val updateStarted = CompletableDeferred<Unit>()
            val updateGate = CompletableDeferred<Unit>()
            fixtureFetch.searchStarted = updateStarted
            fixtureFetch.searchGate = updateGate
            val updateOperation = async {
                runCatching {
                    manager.execute(installed.sourceId, "searchNovels", listOf(JsonPrimitive("spring"), JsonPrimitive(1)))
                }.exceptionOrNull()
            }
            try {
                withTimeout(10_000) { updateStarted.await() }
                val requestsAtUpdate = fixtureFetch.requestCount
                manager.installReviewed(
                    sameBundleReview.reviewToken,
                    sameBundleReview.sha256,
                    sameBundleReview.requiredOrigins + "https://cdn.fixture.invalid:443",
                )
                val updateOutcome = runCatching { updateOperation.await() }
                val updateFailure = updateOutcome.exceptionOrNull() ?: updateOutcome.getOrNull()
                assertTrue("A same-hash trust change must cancel the old operation.", updateFailure is CancellationException)
                assertEquals("No later broker request should run after the trust change returns.", requestsAtUpdate, fixtureFetch.requestCount)
            } finally {
                updateGate.complete(Unit)
                updateOperation.cancelAndJoin()
            }

            val disableStarted = CompletableDeferred<Unit>()
            val disableGate = CompletableDeferred<Unit>()
            fixtureFetch.searchStarted = disableStarted
            fixtureFetch.searchGate = disableGate
            val disableOperation = async {
                runCatching {
                    manager.execute(installed.sourceId, "searchNovels", listOf(JsonPrimitive("spring"), JsonPrimitive(1)))
                }.exceptionOrNull()
            }
            try {
                withTimeout(10_000) { disableStarted.await() }
                val requestsAtDisable = fixtureFetch.requestCount
                manager.setEnabled(installed.sourceId, false)
                val disableOutcome = runCatching { disableOperation.await() }
                val disableFailure = disableOutcome.exceptionOrNull() ?: disableOutcome.getOrNull()
                assertTrue("Disabling must cancel the active operation, not merely hide it.", disableFailure is CancellationException)
                assertEquals("No later broker request should run after disable returns.", requestsAtDisable, fixtureFetch.requestCount)
            } finally {
                disableGate.complete(Unit)
                disableOperation.cancelAndJoin()
                fixtureFetch.searchStarted = null
                fixtureFetch.searchGate = null
            }
            manager.uninstall(installed.sourceId)
        } finally {
            isolatedFiles.deleteRecursively()
        }
    }

    @Test
    fun strictHttpBrokerRejectsUnsafeTargetsAndSessionHeadersBeforeDnsOrSockets() = runBlocking {
        val broker = LnReaderHttpBroker()
        val reviewedFixture = setOf("https://fixture.invalid:443")
        suspend fun rejected(url: String, origins: Set<String>, headers: Map<String, String> = emptyMap()): Throwable {
            val failure = runCatching {
                broker.fetch(LnReaderHttpRequest("fixture", url, "GET", headers, null), origins, 1024)
            }.exceptionOrNull()
            return failure ?: error("Unsafe request unexpectedly reached a response: $url")
        }

        assertTrue(rejected("http://fixture.invalid/", reviewedFixture).message.orEmpty().contains("public HTTPS"))
        assertTrue(rejected("https://user:pass@fixture.invalid/", reviewedFixture).message.orEmpty().contains("without credentials"))
        assertTrue(rejected("https://10.0.0.1/", reviewedFixture).message.orEmpty().contains("IP-literal"))
        assertTrue(rejected("https://[fd00::1]/", reviewedFixture).message.orEmpty().contains("IP-literal"))
        assertTrue(rejected("https://localhost/", setOf("https://localhost:443")).message.orEmpty().contains("Local and private"))
        assertTrue(rejected("https://unreviewed.invalid/", reviewedFixture).message.orEmpty().contains("approved source origins"))
        assertTrue(rejected("https://fixture.invalid/", reviewedFixture, mapOf("Cookie" to "session=x"))
            .message.orEmpty().contains("Credentials and browser session headers are unsupported"))
        assertTrue(rejected("https://fixture.invalid/", reviewedFixture, mapOf("Authorization" to "Bearer x"))
            .message.orEmpty().contains("Credentials and browser session headers are unsupported"))
    }

    private class FixtureFetch : LnReaderHostFetch {
        var requestCount = 0
        var bundleFetches = 0
        var pluginMethodExecutions = 0
        var currentBundle: ByteArray = COMMON_JS.toByteArray()
        var manifestBytes: ByteArray = MANIFEST
        @Volatile var searchStarted: CompletableDeferred<Unit>? = null
        @Volatile var searchGate: CompletableDeferred<Unit>? = null

        override suspend fun fetch(request: LnReaderHttpRequest, allowedOrigins: Set<String>, maxBytes: Int): LnReaderHttpResponse {
            requestCount++
            assertTrue("The plugin origin remains explicitly approved.", "https://fixture.invalid:443" in allowedOrigins)
            assertFalse(request.headers.keys.any { it.equals("cookie", true) || it.equals("authorization", true) })
            val url = request.url
            val body = when {
                url.contains("plugins.json") || url.contains("official-plugins.json") -> manifestBytes
                url.endsWith("/plugin.js") -> { bundleFetches++; currentBundle }
                url.endsWith("/search") -> {
                    searchStarted?.complete(Unit)
                    searchGate?.await()
                    "fixture body".toByteArray()
                }
                url.endsWith("/gbk") -> byteArrayOf(0xD6.toByte(), 0xD0.toByte(), 0xB9.toByte(), 0xFA.toByte())
                else -> error("Unexpected fixture request: $url")
            }
            if (url.endsWith("/search")) pluginMethodExecutions++
            assertTrue(body.size <= maxBytes)
            return LnReaderHttpResponse(url, 200, "OK", mapOf("x-fixture" to "x-fixture-ok"), body)
        }
    }

    private companion object {
        // The latter two rows mirror current official manifest metadata URL edge cases. They are
        // listed with a compatibility reason; neither HTTP nor malformed origins are upgraded.
        val MANIFEST = """[
          {"id":"fixture-source","name":"Authored fixture","site":"https://fixture.invalid/","lang":"en","version":"1.0","url":"https://fixture.invalid/plugin.js"},
          {"id":"reinowuxia","name":"Metadata-only HTTP example","site":"http://www.reinowuxia.com/","lang":"zh","version":"1.0","url":"https://fixture.invalid/plugin.js"},
          {"id":"komga","name":"Metadata-only invalid URL example","site":"url","lang":"en","version":"1.0","url":"https://fixture.invalid/plugin.js"}
        ]""".toByteArray()

        val COMMON_JS = """
            const { load } = require('cheerio');
            const Parser = require('htmlparser2').Parser;
            const dayjs = require('dayjs');
            const { encode } = require('urlencode');
            const { fetchApi, fetchText } = require('@libs/fetch');
            const { storage } = require('@libs/storage');
            const { NovelStatus } = require('@libs/novelStatus');
            const { FilterTypes } = require('@libs/filterInputs');
            const { isUrlAbsolute } = require('@libs/isAbsoluteUrl');
            module.exports.default = {
              id: 'fixture-source', name: 'Authored fixture', site: 'https://fixture.invalid/', version: '1.0',
              async searchNovels(term, page) {
                globalThis.marker = 'set only in this operation';
                const $ = load('<main><h2>Authored Novel</h2></main>');
                let parsed = '';
                const parser = new Parser({ ontext: text => { parsed += text; } });
                parser.write('<p>parser</p>'); parser.end();
                const response = await fetchApi('https://fixture.invalid/search');
                const text = await response.text();
                const gbk = await fetchText('https://fixture.invalid/gbk', undefined, 'gbk');
                storage.set('calls', (storage.get('calls') || 0) + 1, Date.now() + 60000);
                return { term, page, title: $('h2').text(), parsed, date: dayjs('2024-01-02').format('YYYY-MM-DD'),
                  header: response.headers.get('X-Fixture'), body: text, gbk, status: NovelStatus.Completed,
                  filter: FilterTypes.ExcludableCheckboxGroup, encoded: encode('你好'),
                  absolute: isUrlAbsolute('https://fixture.invalid/'),
                  protocolRelative: isUrlAbsolute('//cdn.fixture.invalid/image'),
                  ambient: typeof process + '/' + typeof fetch + '/' + typeof globalThis.android };
              },
              async parseChapter() {
                storage.set('calls', (storage.get('calls') || 0) + 1, Date.now() + 60000);
                return typeof globalThis.marker;
              },
            };
        """.trimIndent()

        val UNKNOWN_MODULE = """const fs = require('fs'); module.exports = { id:'fixture-source', name:'Authored fixture', site:'https://fixture.invalid/', version:'1.0', searchNovels(){ return fs; } };"""
        val CPU_LOOP = """module.exports = { id:'fixture-source', name:'Authored fixture', site:'https://fixture.invalid/', version:'1.0', searchNovels(){ while(true){} } };"""
    }
}
