package mihon.readingapp.library

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.download.SmartOfflineBlockedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.data.database.DatabaseRepositoryImpl
import mihon.readingapp.fixtures.OriginalDemoCatalog
import mihon.readingapp.fixtures.OriginalDemoSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import java.util.UUID
import kotlin.time.Clock

/** A Smart-selected synthetic chapter exercises the native page/archive engine without queue mutation. */
@RunWith(AndroidJUnit4::class)
class SmartOfflineEngineRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun nativeSmartSaveIsBoundedAndNeverJoinsTheManualQueue() = runBlocking {
        val graph = context.appGraph
        val nonce = UUID.randomUUID().toString()
        val tree = android.net.Uri.parse(graph.storagePreferences.baseStorageDirectory.get())
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        assertTrue("The QA app must have its explicit writable test folder", context.contentResolver.persistedUriPermissions.any {
            it.uri.authority == tree.authority && DocumentsContract.getTreeDocumentId(it.uri) == treeId &&
                it.isReadPermission && it.isWritePermission
        })

        lateinit var driver: SqlDriver
        val migration = DatabaseRepositoryImpl(lazy { driver })
        driver = DatabaseBindings.providesSqlDriver(context, migration)
        val database = DatabaseBindings.providesDatabase(driver)
        val mangas = MangaRepositoryImpl(database)
        val chapters = ChapterRepositoryImpl(database)
        val source = graph.sourceManager.get(OriginalDemoSource.MANGA_ID) as OriginalDemoSource
        val chapterPath = "/neon-courier/chapter/1?smart-qa=$nonce"
        val pageUrls = OriginalDemoCatalog.pageUrls("/neon-courier/chapter/1").map { "$it&smart-qa=$nonce" }
        var manga: Manga? = null
        var chapter: Chapter? = null
        var secondChapter: Chapter? = null
        val smartBefore = graph.smartOfflineStore.state.value
        val toolsBefore = graph.libraryToolsStore.state.value
        try {
            assertTrue(graph.downloadManager.queueState.value.isEmpty())
            OriginalDemoSource.controlledPageLists[chapterPath] = pageUrls
            manga = NetworkToLocalManga(mangas)(Manga.create().copy(
                source = source.id,
                url = "/neon-courier/smart-qa-$nonce",
                title = "Arkhive Smart engine QA $nonce",
                initialized = true,
                favoriteAt = Clock.System.now(),
                chapterFlags = Manga.CHAPTER_SORTING_NUMBER,
            ))
            chapter = chapters.updateFromRemote(emptyList(), listOf(Chapter.create().copy(
                mangaId = manga!!.id,
                url = chapterPath,
                name = "Smart synthetic chapter",
                chapterNumber = 1.0,
                sourceOrder = 0,
                dateFetch = Clock.System.now(),
            )), emptyList()).single()
            assertNull(graph.downloadManager.getQueuedDownloadOrNull(chapter!!.id))

            val gate = SmartOfflineBudget({ 64L * 1024 * 1024 }, 0, { true }, { true })
            val job = Download(source, manga!!, chapter!!)
            val storedBytes = graph.downloader.downloadSmartChapter(job, gate)
            assertNotNull("The existing page/archive engine must save the selected chapter", storedBytes)
            assertTrue(requireNotNull(storedBytes) > 0)
            assertEquals(Download.State.DOWNLOADED, job.status)
            assertTrue(graph.downloadManager.isChapterDownloadedOnDisk(
                chapter!!.name, chapter!!.scanlator, chapter!!.url, manga!!.title, source,
            ))
            assertNull("Smart's native-engine path must not create a manual queue entry", graph.downloadManager.getQueuedDownloadOrNull(chapter!!.id))

            val seriesKey = "manga:${manga!!.id}"
            val ownedFile = requireNotNull(graph.downloadProvider.findChapterDir(
                chapter!!.name, chapter!!.scanlator, chapter!!.url, manga!!.title, source,
            ))
            val completedMtime = ownedFile.lastModified()
            graph.chapterRepository.update(ChapterUpdate(chapter!!.id) { read = true })
            graph.libraryToolsStore.update { it.copy(cleanupReadSmartCopies = false) }
            graph.smartOfflineStore.recordOwned(SmartOwnedCopy(
                seriesKey, chapter!!.id.toString(), "manga", requireNotNull(storedBytes), System.currentTimeMillis(), completedMtime,
            ))
            assertEquals(0, graph.smartOfflineDispatcher.cleanupReadOwned())
            assertNotNull("Cleanup remains disabled until the user opts in", graph.downloadProvider.findChapterDir(
                chapter!!.name, chapter!!.scanlator, chapter!!.url, manga!!.title, source,
            ))
            graph.libraryToolsStore.update { it.copy(cleanupReadSmartCopies = true) }
            assertEquals(1, graph.smartOfflineDispatcher.cleanupReadOwned())
            assertNull(graph.downloadProvider.findChapterDir(chapter!!.name, chapter!!.scanlator, chapter!!.url, manga!!.title, source))
            assertFalse(graph.smartOfflineStore.state.value.ownedCopies.containsKey("manga:${chapter!!.id}"))

            val secondPath = "/neon-courier/chapter/2?smart-qa=$nonce"
            OriginalDemoSource.controlledPageLists[secondPath] = OriginalDemoCatalog.pageUrls("/neon-courier/chapter/2").map { "$it&smart-qa=$nonce" }
            secondChapter = chapters.updateFromRemote(emptyList(), listOf(Chapter.create().copy(
                mangaId = manga!!.id, url = secondPath, name = "Smart budget/manual fixture", chapterNumber = 2.0,
                sourceOrder = 1, dateFetch = Clock.System.now(),
            )), emptyList()).single()
            val secondJob = Download(source, manga!!, secondChapter!!)
            assertThrows(SmartOfflineBlockedException::class.java) {
                runBlocking { graph.downloader.downloadSmartChapter(secondJob, SmartOfflineBudget({ 0L }, 0, { true }, { true })) }
            }
            assertFalse(graph.downloadManager.isChapterDownloadedOnDisk(
                secondChapter!!.name, secondChapter!!.scanlator, secondChapter!!.url, manga!!.title, source,
            ))

            graph.downloadManager.downloadChapters(manga!!, listOf(secondChapter!!), autoStart = false)
            val queued = requireNotNull(graph.downloadManager.getQueuedDownloadOrNull(secondChapter!!.id))
            assertNull("Smart must leave an existing manual queue item alone", graph.downloader.downloadSmartChapter(
                secondJob, SmartOfflineBudget({ 64L * 1024 * 1024 }, 0, { true }, { true }),
            ))
            assertSame(queued, graph.downloadManager.getQueuedDownloadOrNull(secondChapter!!.id))
            graph.downloadManager.cancelQueuedDownloads(listOf(queued))
            assertNull(graph.downloadManager.getQueuedDownloadOrNull(secondChapter!!.id))
        } finally {
            OriginalDemoSource.controlledPageLists.remove(chapterPath)
            OriginalDemoSource.controlledPageLists.remove("/neon-courier/chapter/2?smart-qa=$nonce")
            secondChapter?.let { graph.downloadManager.getQueuedDownloadOrNull(it.id)?.let { queued -> runCatching { graph.downloadManager.cancelQueuedDownloads(listOf(queued)) } } }
            val selectedManga = manga
            val selectedChapter = chapter
            if (selectedManga != null && selectedChapter != null) {
                runCatching { graph.downloadManager.deleteSmartOwnedChapter(selectedChapter, selectedManga, source) }
                withContext(Dispatchers.IO) {
                    runCatching {
                        driver.execute(null, "DELETE FROM manga WHERE id = ?", 1) { bindLong(0, selectedManga.id) }.await()
                    }
                }
            }
            driver.close()
            graph.smartOfflineStore.update { smartBefore }
            graph.libraryToolsStore.update { toolsBefore }
        }
    }
}
