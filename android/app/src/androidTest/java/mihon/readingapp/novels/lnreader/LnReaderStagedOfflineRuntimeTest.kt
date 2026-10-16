package mihon.readingapp.novels.lnreader

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mihon.app.di.appGraph
import mihon.readingapp.novels.NovelRepository
import mihon.readingapp.novels.NovelSources
import mihon.readingapp.novels.LnReaderNovelSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * A separate invocation can run test01, force-stop the target app, then run test02 with
 * `-e lnreaderColdRestart true`; test03 removes the authored persistent fixture afterward.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LnReaderStagedOfflineRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "lnreader-staged-offline.db"
    private val handoffFile get() = File(context.filesDir, HANDOFF_FILE)
    private val managerFiles get() = File(context.filesDir, MANAGER_DIRECTORY)
    private val novelFiles get() = File(context.filesDir, "arkhive-novel-test-${NovelRepository.id(databaseName).take(16)}")
    private val novelCache get() = File(context.cacheDir, "arkhive-novel-test-${NovelRepository.id(databaseName).take(16)}")

    @Test
    fun test01SetupInstalledSourceAndSavedChapterBeforeForceStop() = runBlocking {
        clearFixture()
        val transport = RestartFixtureTransport()
        val manager = manager(transport)
        var sources: NovelSources? = null
        var repository: NovelRepository? = null
        try {
            manager.addRepository(REPOSITORY_URL)
            manager.refreshRepository(REPOSITORY_URL)
            val review = manager.stagePlugin(REPOSITORY_URL, "restart-fixture")
            val installed = manager.installReviewed(review.reviewToken, review.sha256, review.requiredOrigins)
            val registry = NovelSources(context.appGraph.networkHelper, manager)
            sources = registry
            withTimeout(10_000) { registry.availableState.first { values -> values.any { it.id == installed.sourceId } } }
            val repo = NovelRepository(context, registry, databaseName, false)
            repository = repo
            val novel = repo.load(installed.sourceId, "restart-novel")
            assertEquals("Relative plugin covers become absolute metadata before persistence.",
                "https://fixture.invalid/restart-cover.png", novel.detail.cover)
            repo.favorite(novel.id, true)
            val chapter = repo.chapters(novel.id).first()
            repo.bookmark(chapter.id, true)
            repo.saveProgress(novel.id, chapter.id, 0.37).join()
            val liveText = repo.text(novel.id, chapter.id)
            repo.removeSaved(chapter.id)
            assertEquals(1, repo.queue(novel.id, listOf(chapter.id)))
            repo.runDownload(requireNotNull(repo.claim()))
            assertTrue(repo.chapters(novel.id).single { it.id == chapter.id }.downloaded)

            val coverUrl = requireNotNull(novel.detail.cover)
            val coverFile = requireNotNull(manager.coverFile(installed.sourceId, coverUrl))
            assertTrue(coverFile.isFile)
            val decoded = requireNotNull(BitmapFactory.decodeFile(coverFile.absolutePath))
            assertEquals(4, decoded.height)
            decoded.recycle()

            // Remove executable permission and bytes before process restart; offline pages and
            // the already owned raster must remain available solely from app-managed storage.
            manager.setEnabled(installed.sourceId, false)
            manager.uninstall(installed.sourceId)
            val offlineText = repo.text(novel.id, chapter.id)
            assertEquals(liveText, offlineText)
            val row = repo.chapters(novel.id).single { it.id == chapter.id }
            assertTrue(row.bookmark)
            assertEquals(0.37, row.progression, 0.0001)
            val marker = buildJsonObject {
                put("pid", JsonPrimitive(Process.myPid()))
                put("sourceId", installed.sourceId)
                put("novelId", novel.id)
                put("chapterId", chapter.id)
                put("coverUrl", coverUrl)
                put("expectedText", liveText)
                put("transportRequests", transport.requests.get())
            }
            handoffFile.writeText(marker.toString())
        } finally {
            runCatching { repository?.closeForTest() }
            sources?.closeForTest()
        }
    }

    @Test
    fun test02VerifyOfflineStateAfterOptionalForceStop() = runBlocking {
        assertTrue("Run test01SetupInstalledSourceAndSavedChapterBeforeForceStop first.", handoffFile.isFile)
        val marker = Json.parseToJsonElement(handoffFile.readText()).jsonObject
        val priorPid = marker.getValue("pid").jsonPrimitive.content.toInt()
        val coldRestartRequested = InstrumentationRegistry.getArguments().getString("lnreaderColdRestart") == "true"
        if (coldRestartRequested) assertTrue("The test process must be new after force-stop.", priorPid != Process.myPid())

        val offlineOnly = RestartFixtureTransport(rejectNetwork = true)
        val manager = manager(offlineOnly)
        assertTrue(manager.snapshot().installedPlugins.isEmpty())
        val sources = NovelSources(context.appGraph.networkHelper, manager)
        var repository: NovelRepository? = null
        try {
            withTimeout(10_000) {
                sources.availableState.first { available -> available.none { it.id == marker.getValue("sourceId").jsonPrimitive.content } }
            }
            val repo = NovelRepository(context, sources, databaseName, false)
            repository = repo
            val novelId = marker.getValue("novelId").jsonPrimitive.content
            val chapterId = marker.getValue("chapterId").jsonPrimitive.content
            val novel = requireNotNull(repo.get(novelId))
            assertTrue(novel.favorite)
            val chapter = repo.chapters(novelId).single { it.id == chapterId }
            assertTrue(chapter.downloaded)
            assertTrue(chapter.bookmark)
            assertEquals(0.37, chapter.progression, 0.0001)
            val text = repo.text(novelId, chapterId)
            assertEquals(marker.getValue("expectedText").jsonPrimitive.content, text)
            val sourceProxy = sources.get(marker.getValue("sourceId").jsonPrimitive.content) as LnReaderNovelSource
            val cachedCover = sourceProxy.coverFile(marker.getValue("coverUrl").jsonPrimitive.content)
            assertNotNull("The broker-owned cover should still be available offline after uninstall.", cachedCover)
            assertEquals("No transport call is allowed after uninstall.", 0, offlineOnly.requests.get())
        } finally {
            runCatching { repository?.closeForTest() }
            sources.closeForTest()
        }
    }

    @Test
    fun test03CleanupStagedOfflineFixture() {
        clearFixture()
    }

    private fun manager(transport: RestartFixtureTransport) = LnReaderExtensionManager(
        object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = managerFiles
        },
        transport,
        LnReaderSandboxRuntime(context, transport),
    )

    private fun clearFixture() {
        handoffFile.delete()
        managerFiles.deleteRecursively()
        context.deleteDatabase(databaseName)
        novelFiles.deleteRecursively()
        novelCache.deleteRecursively()
    }

    private class RestartFixtureTransport(private val rejectNetwork: Boolean = false) : LnReaderHostFetch {
        val requests = AtomicInteger()
        private val png = run {
            val bitmap = Bitmap.createBitmap(3, 4, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream()
            try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes); bytes.toByteArray() }
            finally { bitmap.recycle() }
        }

        override suspend fun fetch(request: LnReaderHttpRequest, allowedOrigins: Set<String>, maxBytes: Int): LnReaderHttpResponse {
            if (rejectNetwork) error("Offline restart attempted a source network request.")
            requests.incrementAndGet()
            assertTrue("Only the reviewed synthetic fixture host is served.", "https://fixture.invalid:443" in allowedOrigins)
            val body = when {
                request.url.contains("restart-plugins.json") -> MANIFEST
                request.url.endsWith("/restart-plugin.js") -> PLUGIN.toByteArray()
                request.url.endsWith("/restart-cover.png") -> png
                request.url.endsWith("/chapter") -> "chapter".toByteArray()
                else -> error("Unexpected staged offline fixture URL: ${request.url}")
            }
            require(body.size <= maxBytes)
            return LnReaderHttpResponse(request.url, 200, "OK", emptyMap(), body)
        }
    }

    private companion object {
        const val REPOSITORY_URL = "https://fixture.invalid/restart-plugins.json"
        const val MANAGER_DIRECTORY = "lnreader-staged-offline-manager"
        const val HANDOFF_FILE = "lnreader-staged-offline-handoff.json"
        val MANIFEST = """[
          {"id":"restart-fixture","name":"Authored restart fixture","site":"https://fixture.invalid/","lang":"en","version":"1.0","url":"https://fixture.invalid/restart-plugin.js"}
        ]""".toByteArray()
        val PLUGIN = """
            module.exports.default = {
              id: 'restart-fixture', name: 'Authored restart fixture', site: 'https://fixture.invalid/', version: '1.0',
              async parseNovel(path) {
                return { name: 'Restarted Novel', path, cover: '/restart-cover.png',
                  chapters: [{ name: 'Saved Chapter', path: 'saved-chapter', chapterNumber: 1 }], totalPages: 1 };
              },
              resolveUrl(path) { return 'https://fixture.invalid/' + path; },
              async parseChapter(path) {
                await (await require('@libs/fetch').fetchApi('https://fixture.invalid/chapter')).text();
                return '<p>The carefully saved lantern keeper chapter remains available after the source is removed.</p>';
              },
            };
        """.trimIndent()
    }
}
