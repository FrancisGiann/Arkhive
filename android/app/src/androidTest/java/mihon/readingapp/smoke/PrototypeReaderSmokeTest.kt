package mihon.readingapp.smoke

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiDevice
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerPageHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.app.di.appGraph
import mihon.data.database.DatabaseRepositoryImpl
import mihon.readingapp.fixtures.OriginalDemoCatalog
import mihon.readingapp.fixtures.OriginalDemoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.Preference
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

/** Real activity, gestures and production SQLDelight persistence; only original offline images are used. */
@RunWith(AndroidJUnit4::class)
class PrototypeReaderSmokeTest {
    @Test
    fun originalDemoPageSwipePersistsAndRestoresProgress() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check("readingapp" in context.packageName.lowercase()) { "Run only against the private prototype" }
        val graph = context.appGraph
        val device = UiDevice.getInstance(instrumentation)
        val restorePreferences = mutableListOf<() -> Unit>()
        var driver: SqlDriver? = null
        var insertedMangaId: Long? = null
        var reader: ActivityScenario<ReaderActivity>? = null

        try {
            temporarilySet(graph.basePreferences.incognitoMode, false, restorePreferences)
            temporarilySet(graph.basePreferences.downloadedOnly, false, restorePreferences)
            temporarilySet(graph.readerPreferences.pageTransitions, false, restorePreferences)
            temporarilySet(graph.readerPreferences.showNavigationOverlayNewUser, false, restorePreferences)
            temporarilySet(graph.readerPreferences.showNavigationOverlayOnStart, false, restorePreferences)
            temporarilySet(graph.readerPreferences.dualPageSplitPaged, false, restorePreferences)
            temporarilySet(graph.readerPreferences.cropBorders, false, restorePreferences)
            temporarilySet(graph.readerPreferences.imageScaleType, 1, restorePreferences)

            // AppGraph intentionally exposes no database/repository accessor. Use the same production
            // bindings and on-disk database, with a second connection and UUID-isolated rows.
            lateinit var productionDriver: SqlDriver
            val migrationState = DatabaseRepositoryImpl(lazy { productionDriver })
            productionDriver = DatabaseBindings.providesSqlDriver(context, migrationState)
            driver = productionDriver
            val database = DatabaseBindings.providesDatabase(productionDriver)
            val mangaRepository = MangaRepositoryImpl(database)
            val chapterRepository = ChapterRepositoryImpl(database)
            val getChapter = GetChapter(chapterRepository)

            val source = graph.sourceManager.get(OriginalDemoSource.MANGA_ID)
            assertTrue("The app must register its original offline source", source is OriginalDemoSource)
            source as OriginalDemoSource
            val remoteManga = source.getPopularManga(1).mangas.first()
            val remoteUpdate = source.getMangaUpdate(remoteManga, emptyList(), true, true)
            val uniqueUrl = "${remoteManga.url}/reader-smoke-${UUID.randomUUID()}"
            val manga = NetworkToLocalManga(mangaRepository)(
                Manga.create().copy(
                    source = source.id,
                    url = uniqueUrl,
                    title = "${remoteManga.title} · isolated reader smoke",
                    favoriteAt = Clock.System.now(),
                    initialized = true,
                    thumbnailUrl = remoteManga.thumbnail_url,
                    viewerFlags = (ReadingMode.LEFT_TO_RIGHT.flagValue or ReaderOrientation.LOCKED_PORTRAIT.flagValue)
                        .toLong(),
                    chapterFlags = Manga.CHAPTER_SORTING_NUMBER,
                ),
            )
            insertedMangaId = manga.id
            val chapters = chapterRepository.updateFromRemote(
                removedIds = emptyList(),
                added = remoteUpdate.chapters.mapIndexed { index, chapter ->
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = chapter.url,
                        name = chapter.name,
                        chapterNumber = chapter.chapter_number.toDouble(),
                        scanlator = chapter.scanlator,
                        sourceOrder = index.toLong(),
                        dateFetch = Clock.System.now(),
                        dateUpload = Instant.fromEpochMilliseconds(chapter.date_upload),
                    )
                },
                updated = emptyList(),
            )
            assertEquals(OriginalDemoCatalog.CHAPTER_COUNT, chapters.size)
            val chapter = chapters.single { it.chapterNumber == 1.0 }
            assertTrue(mangaRepository.getLibraryManga().any { it.manga.id == manga.id })

            reader = ActivityScenario.launch(ReaderActivity.newIntent(context, manga.id, chapter.id))
            acknowledgeFullscreenTutorial(device)
            awaitDecodedVisiblePage(reader, chapter.id, pageIndex = 0)
            // Wait until input is idle, then use a decisive page-turn gesture. A 60% drag
            // loses touch slop and can fall below the distance threshold when the emulator
            // injects it too slowly to qualify as a fling.
            device.waitForIdle(1_000)
            val swipeBounds = Rect()
            reader.onActivity { activity ->
                val viewer = requireNotNull(activity.viewModel.state.value.viewer)
                assertTrue(viewer.getView().getGlobalVisibleRect(swipeBounds))
            }
            assertTrue(
                "A real leftward gesture must be injected into the left-to-right reader",
                device.swipe(
                    swipeBounds.left + swipeBounds.width() * 9 / 10,
                    swipeBounds.centerY(),
                    swipeBounds.left + swipeBounds.width() / 10,
                    swipeBounds.centerY(),
                    10,
                ),
            )
            awaitDecodedVisiblePage(reader, chapter.id, pageIndex = 1)
            reader.close()
            reader = null

            // Do not rely on Flow invalidation across two drivers: poll a fresh production query.
            withTimeout(10_000) {
                while (getChapter.await(chapter.id)?.lastPageRead != 1) delay(100)
            }
            val storedChapter = getChapter.await(chapter.id)
            assertNotNull(storedChapter)
            assertEquals(1, storedChapter!!.lastPageRead)
            assertFalse("One swipe must not finish a six-page chapter", storedChapter.read)

            // A fresh ActivityScenario has no old activity SavedState; resume must come from disk.
            reader = ActivityScenario.launch(ReaderActivity.newIntent(context, manga.id, chapter.id))
            acknowledgeFullscreenTutorial(device)
            awaitDecodedVisiblePage(reader, chapter.id, pageIndex = 1)
            assertEquals(1, getChapter.await(chapter.id)!!.lastPageRead)
        } finally {
            try {
                reader?.close()
                instrumentation.waitForIdleSync()
                val mangaId = insertedMangaId
                if (mangaId != null) {
                    withContext(Dispatchers.IO) {
                        // Only this test's row; production foreign keys cascade its chapters/history.
                        driver!!.execute(null, "DELETE FROM manga WHERE id = ?", 1) {
                            bindLong(0, mangaId)
                        }.await()
                    }
                }
            } finally {
                restorePreferences.asReversed().forEach { it() }
                driver?.close()
            }
        }
    }

    private fun acknowledgeFullscreenTutorial(device: UiDevice) {
        // Fresh full AOSP images show a real system tutorial which owns window focus.
        val title = device.wait(Until.findObject(By.text("Viewing full screen")), 2_000) ?: return
        check(title.applicationPackage in setOf("android", "com.android.systemui"))
        requireNotNull(device.findObject(By.text("Got it"))).click()
    }

    private suspend fun awaitDecodedVisiblePage(
        reader: ActivityScenario<ReaderActivity>,
        chapterId: Long,
        pageIndex: Int,
    ) {
        var observed = "Reader state not observed"
        try {
            withTimeout(20_000) {
                while (true) {
                    var ready = false
                    reader.onActivity { activity ->
                        val state = activity.viewModel.state.value
                        state.initError?.let { throw AssertionError("The real reader failed to load", it) }
                        val viewer = state.viewer
                        val holders = viewer?.getView()?.pageHolders()?.toList().orEmpty()
                        observed = "focus=${activity.hasWindowFocus()}, lifecycle=${activity.lifecycle.currentState}, dialog=${state.dialog}, " +
                            "chapter=${state.currentChapter?.chapter?.id}, page=${state.currentPage}, " +
                            "total=${state.totalPages}, viewer=${viewer?.javaClass?.simpleName}, " +
                            "holders=" + holders.joinToString {
                                val rect = Rect()
                                "${it.page.index}:ready=${it.isImageReadyForAutoScroll},shown=${it.isShown}," +
                                    "size=${it.width}x${it.height},visible=${it.getGlobalVisibleRect(rect)}:$rect"
                            }
                        if (activity.hasWindowFocus() &&
                            state.currentChapter?.chapter?.id == chapterId && state.currentPage == pageIndex + 1
                        ) {
                            assertEquals(OriginalDemoCatalog.PAGES_PER_CHAPTER, state.totalPages)
                            ready = viewer?.getView()?.pageHolders()?.any { holder ->
                                val visible = Rect()
                                holder.page.chapter.chapter.id == chapterId && holder.page.index == pageIndex &&
                                    holder.isShown && holder.isImageReadyForAutoScroll &&
                                    holder.getGlobalVisibleRect(visible) &&
                                    visible.width() >= holder.width * 9 / 10 && visible.height() > 0
                            } == true
                        }
                    }
                    if (ready) return@withTimeout
                    delay(100)
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw AssertionError("Decoded page $pageIndex did not appear: $observed", error)
        }
    }

    private fun View.pageHolders(): Sequence<PagerPageHolder> = sequence {
        if (this@pageHolders is PagerPageHolder) yield(this@pageHolders)
        if (this@pageHolders is ViewGroup) {
            for (index in 0 until childCount) yieldAll(getChildAt(index).pageHolders())
        }
    }

    private fun <T> temporarilySet(
        preference: Preference<T>,
        value: T,
        restorations: MutableList<() -> Unit>,
    ) {
        val wasSet = preference.isSet()
        val previous = preference.get()
        restorations += { if (wasSet) preference.set(previous) else preference.delete() }
        preference.set(value)
    }
}
