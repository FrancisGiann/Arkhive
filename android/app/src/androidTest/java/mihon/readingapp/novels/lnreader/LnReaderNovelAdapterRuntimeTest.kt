package mihon.readingapp.novels.lnreader

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import mihon.app.di.appGraph
import mihon.readingapp.novels.LnReaderNovelSource
import mihon.readingapp.novels.NovelRepository
import mihon.readingapp.novels.NovelSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Executes only the in-test CommonJS fixture; no community bundle is fetched or run. */
@RunWith(AndroidJUnit4::class)
class LnReaderNovelAdapterRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun reviewedCommonJsSourceFlowsThroughLibraryAndSavedChaptersSurviveRevocation() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val isolatedFiles = File(context.cacheDir, "lnreader-adapter-$suffix")
        val isolatedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = isolatedFiles
        }
        val databaseName = "ln-adapter-$suffix.db"
        val fixture = AdapterTransport()
        val manager = LnReaderExtensionManager(isolatedContext, fixture, LnReaderSandboxRuntime(context, fixture))
        var repository: NovelRepository? = null
        var restartedRepository: NovelRepository? = null
        var sourcesUnderTest: NovelSources? = null
        var restartedSources: NovelSources? = null
        try {
            val repositoryUrl = "https://fixture.invalid/plugins.json?adapter=$suffix"
            manager.addRepository(repositoryUrl)
            val listed = manager.refreshRepository(repositoryUrl)
            assertEquals(1, listed.metadata.size)
            assertEquals("fixture-adapter", listed.metadata.single().id)
            assertEquals("Metadata listing never evaluates plugin code.", 0, fixture.pluginRequests.get())

            val staged = manager.stagePlugin(repositoryUrl, "fixture-adapter")
            assertTrue(staged.sha256.matches(Regex("[a-f0-9]{64}")))
            assertEquals("Reviewing bytes still does not execute them.", 0, fixture.pluginRequests.get())
            val installed = manager.installReviewed(staged.reviewToken, staged.sha256, staged.requiredOrigins)
            assertTrue(installed.enabled)

            val sources = NovelSources(context.appGraph.networkHelper, manager)
            sourcesUnderTest = sources
            withTimeout(10_000) {
                sources.availableState.first { available -> available.any { it.id == installed.sourceId } }
            }
            val source = sources.get(installed.sourceId) as LnReaderNovelSource
            val descriptor = source.describe()
            assertEquals("Status", descriptor.filters.single { it.key == "status" }.label)
            assertEquals("ongoing", descriptor.filters.single { it.key == "status" }.defaultValue.toString().trim('"'))
            assertEquals(
                "{\"include\":[\"fantasy\"]}",
                descriptor.filters.single { it.key == "requiredGenres" }.defaultValue.toString(),
            )
            assertEquals("{}", descriptor.filters.single { it.key == "optionalGenres" }.defaultValue.toString())

            val catalog = source.catalog(1, "")
            assertEquals("Catalog ongoing", catalog.items.single().name)
            assertEquals("Protocol-relative covers are stored as safe absolute HTTPS URLs.",
                "https://fixture.invalid/cover.png", catalog.items.single().cover)
            val searched = source.catalog(1, "lantern")
            assertEquals("Search lantern", searched.items.single().name)

            val novelRepository = NovelRepository(context, sources, databaseName, false)
            repository = novelRepository
            val novel = novelRepository.load(installed.sourceId, "authored-novel")
            assertEquals("Authored Novel", novel.detail.name)
            assertEquals("The parsed novel keeps a cacheable absolute cover URL.",
                "https://fixture.invalid/cover.png", novel.detail.cover)
            val persistedChapters = novelRepository.chapters(novel.id)
            assertEquals("Parsed chapters are persisted as the novel repository's chapter rows.", 2, persistedChapters.size)
            assertEquals(1.0, persistedChapters[0].chapterNumber!!, 0.0001)
            assertEquals(2.0, persistedChapters[1].chapterNumber!!, 0.0001)
            novelRepository.favorite(novel.id, true)
            val firstChapter = novelRepository.chapters(novel.id).first()
            novelRepository.bookmark(firstChapter.id, true)
            novelRepository.saveProgress(novel.id, firstChapter.id, 0.42).join()

            val liveText = novelRepository.text(novel.id, firstChapter.id)
            assertTrue(liveText.contains("lantern keeper", ignoreCase = true))
            // Remove the temporary read cache so the following queued save must call the
            // extension's parseChapter again and commit the sanitized result as offline content.
            novelRepository.removeSaved(firstChapter.id)
            assertEquals(1, novelRepository.queue(novel.id, listOf(firstChapter.id)))
            val queued = requireNotNull(novelRepository.claim())
            novelRepository.runDownload(queued)
            val downloaded = novelRepository.chapters(novel.id).single { it.id == firstChapter.id }
            assertTrue(downloaded.downloaded)
            assertTrue(downloaded.bookmark)
            assertEquals(0.42, downloaded.progression, 0.0001)
            assertTrue(fixture.pluginRequests.get() >= 4)

            val coverUrl = requireNotNull(novel.detail.cover)
            val ownedCover = requireNotNull(source.coverFile(coverUrl))
            assertTrue(ownedCover.isFile)
            val decodedCover = requireNotNull(BitmapFactory.decodeFile(ownedCover.absolutePath))
            assertEquals("Broker returned the authored raster bytes, not an untrusted URL model.", 3, decodedCover.width)
            decodedCover.recycle()

            // Installing the same reviewed bytes with a changed trust set must invalidate a
            // retained catalog pager and reject late rows from its prior authorization.
            val pager = sources.pager(installed.sourceId)
            withContext(Dispatchers.Main.immediate) { pager.select("", null, force = true) }
            val loadedCatalog = withTimeout(10_000) { pager.state.first { !it.busy && it.items.isNotEmpty() } }
            val reviewedAgain = manager.stagePlugin(repositoryUrl, "fixture-adapter")
            assertEquals(installed.sha256, reviewedAgain.sha256)
            manager.installReviewed(
                reviewedAgain.reviewToken,
                reviewedAgain.sha256,
                reviewedAgain.requiredOrigins + "https://cdn.fixture.invalid:443",
            )
            val clearedCatalog = withTimeout(10_000) {
                pager.state.first { it.revision > loadedCatalog.revision && it.items.isEmpty() && !it.busy }
            }
            assertTrue(clearedCatalog.error.orEmpty().contains("changed or was disabled"))
            withContext(Dispatchers.Main.immediate) { pager.pause() }

            // A new manager/source/repository object exercises persisted trust and library
            // startup. The actual process-stop/offline invocation can target the saved DB
            // produced by the separate staged offline test.
            novelRepository.closeForTest()
            repository = null
            val restoredManager = LnReaderExtensionManager(isolatedContext, fixture, LnReaderSandboxRuntime(context, fixture))
            val restoredRecord = restoredManager.snapshot().installedPlugins.single()
            assertEquals(installed.sourceId, restoredRecord.sourceId)
            assertTrue(restoredRecord.enabled)
            val restoredSources = NovelSources(context.appGraph.networkHelper, restoredManager)
            restartedSources = restoredSources
            withTimeout(10_000) {
                restoredSources.availableState.first { available -> available.any { it.id == installed.sourceId } }
            }
            val restoredRepository = NovelRepository(context, restoredSources, databaseName, false)
            restartedRepository = restoredRepository
            restoredManager.setEnabled(installed.sourceId, false)
            withTimeout(10_000) { restoredSources.availableState.first { available -> available.none { it.id == installed.sourceId } } }
            restoredManager.uninstall(installed.sourceId)

            val requestCountBeforeOfflineRead = fixture.pluginRequests.get()
            val offlineText = restoredRepository.text(novel.id, firstChapter.id)
            assertEquals(liveText, offlineText)
            assertEquals("Uninstall must leave saved chapter bytes readable without source execution.", requestCountBeforeOfflineRead, fixture.pluginRequests.get())
            val offlineState = restoredRepository.chapters(novel.id).single { it.id == firstChapter.id }
            assertTrue(offlineState.downloaded)
            assertTrue(offlineState.bookmark)
            assertEquals(0.42, offlineState.progression, 0.0001)
            assertNotNull("A validated owned cover remains usable from cache after uninstall.", restoredManager.coverFile(installed.sourceId, coverUrl))
            assertFalse(restoredManager.snapshot().installedPlugins.any { it.sourceId == installed.sourceId })
        } finally {
            runCatching { restartedRepository?.closeForTest() }
            runCatching { repository?.closeForTest() }
            restartedSources?.closeForTest()
            sourcesUnderTest?.closeForTest()
            context.deleteDatabase(databaseName)
            val novelFolder = "arkhive-novel-test-${NovelRepository.id(databaseName).take(16)}"
            File(context.filesDir, novelFolder).deleteRecursively()
            File(context.cacheDir, novelFolder).deleteRecursively()
            isolatedFiles.deleteRecursively()
        }
    }

    private class AdapterTransport : LnReaderHostFetch {
        val pluginRequests = AtomicInteger()
        private val manifest = """[
          {"id":"fixture-adapter","name":"Authored adapter","site":"https://fixture.invalid/","lang":"en","version":"1.0","url":"https://fixture.invalid/plugin.js"}
        ]""".trimIndent().toByteArray()
        private val bundle = COMMON_JS.toByteArray()
        private val cover = run {
            val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
            val output = ByteArrayOutputStream()
            try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
            finally { bitmap.recycle() }
        }

        override suspend fun fetch(request: LnReaderHttpRequest, allowedOrigins: Set<String>, maxBytes: Int): LnReaderHttpResponse {
            assertTrue("The fixture only serves the reviewed test origin.", "https://fixture.invalid:443" in allowedOrigins)
            assertFalse(request.headers.keys.any { it.equals("cookie", true) || it.equals("authorization", true) })
            if (request.url.endsWith("/cover.png")) {
                assertEquals("POST", request.method)
                assertEquals("cover=1", request.body?.toString(Charsets.UTF_8))
                assertEquals("The official imageRequestInit Referer is retained through the broker.",
                    "https://fixture.invalid/reader", request.headers["referer"])
            }
            val body = when {
                request.url.contains("plugins.json") -> manifest
                request.url.endsWith("/plugin.js") -> bundle
                request.url.endsWith("/catalog") -> "<main><article data-path='authored-novel'><h2>Fixture novel</h2></article></main>".toByteArray()
                request.url.endsWith("/search") -> "<main><h2>Search result</h2></main>".toByteArray()
                request.url.endsWith("/detail") || request.url.endsWith("/page") -> "{}".toByteArray()
                request.url.endsWith("/chapter") -> "authored chapter response".toByteArray()
                request.url.endsWith("/cover.png") -> cover
                else -> error("Unexpected authored fixture request: ${request.url}")
            }
            assertTrue("Fixture responses honor the host byte bound.", body.size <= maxBytes)
            if (!request.url.contains("plugins.json") && !request.url.endsWith("/plugin.js") && !request.url.endsWith("/cover.png")) {
                pluginRequests.incrementAndGet()
            }
            val contentType = when {
                request.url.endsWith("/cover.png") -> "image/png"
                request.url.endsWith("/detail") || request.url.endsWith("/page") -> "application/json"
                else -> "text/plain"
            }
            return LnReaderHttpResponse(request.url, 200, "OK", mapOf("content-type" to contentType), body)
        }
    }

    private companion object {
        val COMMON_JS = """
            const { load } = require('cheerio');
            const { fetchApi } = require('@libs/fetch');
            const { storage } = require('@libs/storage');
            module.exports.default = {
              id: 'fixture-adapter', name: 'Authored adapter', site: 'https://fixture.invalid/', version: '1.0',
              imageRequestInit: { method: 'POST', headers: { Referer: 'https://fixture.invalid/reader' }, body: 'cover=1' },
              filters: {
                status: { label: 'Status', type: 'Picker', value: 'ongoing', options: [
                  { label: 'Ongoing', value: 'ongoing' }, { label: 'Complete', value: 'complete' }
                ] },
                requiredGenres: { label: 'Required genres', type: 'XCheckbox', value: { include: ['fantasy'] }, options: [
                  { label: 'Fantasy', value: 'fantasy' }, { label: 'Mystery', value: 'mystery' }
                ] },
                optionalGenres: { label: 'Optional genres', type: 'XCheckbox', value: {}, options: [
                  { label: 'Fantasy', value: 'fantasy' }, { label: 'Mystery', value: 'mystery' }
                ] },
              },
              async popularNovels(pageNo, options) {
                const response = await fetchApi('https://fixture.invalid/catalog');
                const $ = load(await response.text());
                storage.set('catalogFilter', options.filters.status.value);
                return $('article').map((_, node) => ({ name: 'Catalog ' + options.filters.status.value,
                  path: $(node).attr('data-path'), cover: '//fixture.invalid/cover.png' })).get();
              },
              async searchNovels(term, pageNo) {
                const response = await fetchApi('https://fixture.invalid/search');
                const $ = load(await response.text());
                return [{ name: 'Search ' + term, path: 'authored-novel', cover: '//fixture.invalid/cover.png' }];
              },
              async parseNovel(path) {
                const response = await fetchApi('https://fixture.invalid/detail');
                await response.json();
                return { name: 'Authored Novel', path, author: 'Fixture author', cover: '//fixture.invalid/cover.png',
                  genres: 'fantasy', summary: 'A wholly authored compatibility fixture.', status: 'Ongoing',
                  chapters: [{ name: 'Chapter One', path: 'chapter-one', chapterNumber: 1, page: '1' }], totalPages: 2 };
              },
              async parsePage(path, page) {
                const response = await fetchApi('https://fixture.invalid/page');
                await response.json();
                return { chapters: [{ name: 'Chapter Two', path: 'chapter-two', chapterNumber: 2, page: '2' }] };
              },
              resolveUrl(path, isNovel) { return 'https://fixture.invalid/' + path; },
              async parseChapter(path) {
                const response = await fetchApi('https://fixture.invalid/chapter');
                await response.text();
                return '<article><h2>' + path + '</h2><p>The lantern keeper crossed the quiet northern harbor at dawn, carrying a careful map and a bright brass key.</p></article>';
              },
            };
        """.trimIndent()
    }
}
