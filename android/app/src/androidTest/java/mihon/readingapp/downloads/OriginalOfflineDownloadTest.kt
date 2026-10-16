package mihon.readingapp.downloads

import android.graphics.Rect
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerPageHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.app.di.appGraph
import mihon.data.database.DatabaseRepositoryImpl
import mihon.readingapp.fixtures.OriginalDemoSource
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.util.UUID
import kotlin.time.Clock

/** Explicitly staged by the guarded script; a real process stop separates each method. */
@RunWith(AndroidJUnit4::class)
class OriginalOfflineDownloadTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = context.appGraph
    private val marker get() = File(context.cacheDir, "original-offline-download-test.json")
    private val nonce get() = requireNotNull(InstrumentationRegistry.getArguments().getString("arkhive_fixture_nonce"))
        .also { UUID.fromString(it); check("readingapp" in context.packageName) }
    private fun state(): JSONObject = JSONObject(marker.readText()).also { check(it.getString("nonce") == nonce) }
    private fun repositories(): Repositories {
        lateinit var driver: SqlDriver
        val migration = DatabaseRepositoryImpl(lazy { driver })
        driver = DatabaseBindings.providesSqlDriver(context, migration)
        val database = DatabaseBindings.providesDatabase(driver)
        return Repositories(driver, MangaRepositoryImpl(database), ChapterRepositoryImpl(database))
    }
    private data class Repositories(val driver: SqlDriver, val mangas: MangaRepositoryImpl,
        val chapters: ChapterRepositoryImpl) : java.io.Closeable { override fun close() = driver.close() }

    @Test fun queueOriginalChapterWithoutStarting() = runBlocking<Unit> {
        check(!marker.exists()) { "Preserve the previous fixture until it is explicitly cleaned up." }
        val tree = graph.storagePreferences.baseStorageDirectory.get()
        val selected = android.net.Uri.parse(tree)
        val treeId = android.provider.DocumentsContract.getTreeDocumentId(selected)
        assertTrue("Choose an actual test folder using Android's UI", context.contentResolver.persistedUriPermissions.any {
            it.uri.authority == selected.authority &&
                android.provider.DocumentsContract.getTreeDocumentId(it.uri) == treeId &&
                it.isReadPermission && it.isWritePermission
        })
        assertTrue("Do not modify a pre-existing queue", graph.downloadManager.queueState.value.isEmpty())
        repositories().use { repo ->
            val source = graph.sourceManager.get(OriginalDemoSource.MANGA_ID) as OriginalDemoSource
            val remote = source.getPopularManga(1).mangas.first()
            val update = source.getMangaUpdate(remote, emptyList(), true, true)
            val manga = NetworkToLocalManga(repo.mangas)(Manga.create().copy(source = source.id,
                url = "${remote.url}/offline-$nonce", title = "Arkhive original offline test $nonce",
                initialized = true, favoriteAt = Clock.System.now(), thumbnailUrl = remote.thumbnail_url,
                viewerFlags = (ReadingMode.LEFT_TO_RIGHT.flagValue or ReaderOrientation.LOCKED_PORTRAIT.flagValue).toLong(),
                chapterFlags = Manga.CHAPTER_SORTING_NUMBER))
            val chapters = repo.chapters.updateFromRemote(emptyList(), update.chapters.mapIndexed { index, c ->
                Chapter.create().copy(mangaId = manga.id, url = c.url, name = c.name,
                    chapterNumber = c.chapter_number.toDouble(), scanlator = c.scanlator,
                    sourceOrder = index.toLong(), dateFetch = Clock.System.now())
            }, emptyList())
            val chapter = chapters.single { it.chapterNumber == 1.0 }
            marker.writeText(JSONObject().put("nonce", nonce).put("manga", manga.id).put("chapter", chapter.id).toString())
            graph.downloadManager.downloadChapters(manga, listOf(chapter), autoStart = false)
            assertEquals(chapter.id, graph.downloadManager.queueState.value.single().chapter.id)
            assertNotNull(context.getSharedPreferences("active_downloads", 0).getString(chapter.id.toString(), null))
            val diskStore = File(context.applicationInfo.dataDir, "shared_prefs/active_downloads.xml")
            withTimeout(5_000) {
                while (!runCatching { diskStore.readText().contains("name=\"${chapter.id}\"") }.getOrDefault(false)) delay(100)
            }
            assertFalse(graph.downloadManager.isChapterDownloadedOnDisk(chapter.name, chapter.scanlator, chapter.url, manga.title, source))
        }
    }
    @Test fun restoredQueueDownloadsOriginalImages() = runBlocking<Unit> {
        val state = state(); val chapterId = state.getLong("chapter")
        withTimeout(15_000) { while (graph.downloadManager.getQueuedDownloadOrNull(chapterId) == null) delay(100) }
        repositories().use { repo ->
            val manga = requireNotNull(repo.mangas.getMangaById(state.getLong("manga")))
            val chapter = requireNotNull(repo.chapters.getChapterById(chapterId))
            check(manga.title.endsWith(nonce))
            val source = graph.sourceManager.get(manga.source) as OriginalDemoSource
            graph.downloadManager.startDownloads()
            withTimeout(60_000) {
                while (!graph.downloadManager.isChapterDownloadedOnDisk(chapter.name, chapter.scanlator, chapter.url, manga.title, source)) delay(200)
            }
            withTimeout(10_000) { while (graph.downloadManager.getQueuedDownloadOrNull(chapterId) != null) delay(100) }
            assertFalse(context.getSharedPreferences("active_downloads", 0).contains(chapterId.toString()))
        }
    }
    @Test fun downloadedChapterDecodesAfterRestartInAirplaneMode() = runBlocking<Unit> {
        assertEquals(1, Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0))
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        withTimeout(5_000) { while (connectivity.activeNetwork != null) delay(100) }
        val state = state(); val id = state.getLong("chapter")
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.newIntent(context, state.getLong("manga"), id)).use { reader ->
            withTimeout(20_000) {
                while (true) {
                    var ready = false
                    reader.onActivity { activity ->
                        val s = activity.viewModel.state.value
                        s.initError?.let { throw AssertionError("Downloaded reader failed", it) }
                        if (s.currentChapter?.chapter?.id == id && s.currentChapter?.pageLoader is DownloadPageLoader) {
                            assertEquals(6, s.totalPages)
                            ready = s.viewer?.getView()?.holders()?.any { holder ->
                                val rect = Rect()
                                holder.page.index == 0 && holder.isImageReadyForAutoScroll && holder.isShown &&
                                    holder.getGlobalVisibleRect(rect) && rect.width() >= holder.width * 9 / 10
                            } == true
                        }
                    }
                    if (ready) break
                    delay(100)
                }
            }
        }
    }
    @Test fun removeOnlyThisOriginalFixture() = runBlocking<Unit> {
        val state = state()
        repositories().use { repo ->
            val manga = requireNotNull(repo.mangas.getMangaById(state.getLong("manga")))
            check(manga.title.endsWith(nonce) && manga.source == OriginalDemoSource.MANGA_ID)
            graph.downloadManager.deleteManga(manga, requireNotNull(graph.sourceManager.get(manga.source)))
            repo.driver.execute(null, "DELETE FROM manga WHERE id = ?", 1) { bindLong(0, manga.id) }.await()
            marker.delete()
        }
    }
    private fun View.holders(): Sequence<PagerPageHolder> = sequence {
        if (this@holders is PagerPageHolder) yield(this@holders)
        if (this@holders is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).holders())
    }
}
