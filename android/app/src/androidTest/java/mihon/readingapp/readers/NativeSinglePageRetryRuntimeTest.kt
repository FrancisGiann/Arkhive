package mihon.readingapp.readers

import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutoAdvanceResult
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationController
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationMode
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerPageHolder
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import mihon.app.di.appGraph
import mihon.data.database.DatabaseRepositoryImpl
import mihon.readingapp.fixtures.OriginalDemoCatalog
import mihon.readingapp.fixtures.OriginalDemoImages
import mihon.readingapp.fixtures.OriginalDemoSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import java.util.UUID
import kotlin.time.Clock

/** Retries one failed native manga image through the existing page loader and button. */
@RunWith(AndroidJUnit4::class)
class NativeSinglePageRetryRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)

    private fun assertReaderUsesFullContainerWidth(activity: ReaderActivity) {
        val window=activity.window.decorView
        val reader=activity.findViewById<View>(R.id.reader_container)
        val viewer=activity.findViewById<View>(R.id.viewer_container)
        assertTrue("Window must be laid out",window.width>0)
        assertTrue("Reader container must be laid out",reader.width>0)
        assertTrue("Viewer must be laid out",viewer.width>0)
        assertEquals("Reader must reach both horizontal window edges",window.width,reader.width)
        assertEquals("Native reader must not add an outer horizontal inset",reader.width,viewer.width)
        val windowPosition=IntArray(2);window.getLocationInWindow(windowPosition)
        val readerPosition=IntArray(2);reader.getLocationInWindow(readerPosition)
        val viewerPosition=IntArray(2);viewer.getLocationInWindow(viewerPosition)
        assertEquals(windowPosition[0],readerPosition[0])
        assertEquals(windowPosition[0]+window.width,readerPosition[0]+reader.width)
        assertEquals(readerPosition[0],viewerPosition[0])
        assertEquals(readerPosition[0]+reader.width,viewerPosition[0]+viewer.width)
    }

    private fun assertPagerImageFillsViewport(activity:ReaderActivity,viewer:PagerViewer) {
        val holder=activity.findViewById<View>(android.R.id.content).all<PagerPageHolder>()
            .first { it.isShown && it.page.index==activity.viewModel.state.value.currentPage-1 }
        assertEquals("Pager page layout fills the page viewer",viewer.pager.width,holder.width)
        assertTrue("Pager page width is measured",holder.width>0)
    }

    @Test fun continuousOptionSwitchesPagedMangaToVerticalAndRetainsThePosition(): Unit = runBlocking {
        val graph = context.appGraph
        val nonce = UUID.randomUUID().toString()
        val path = "/neon-courier/chapter/1?continuous-qa=$nonce"
        val urls = OriginalDemoCatalog.pageUrls("/neon-courier/chapter/1").map { "$it&continuous-qa=$nonce" }
        OriginalDemoSource.controlledPageLists[path] = urls
        val oldReducedMotion = graph.uiPreferences.arkhiveReducedMotion.get()
        val readerPreferences=graph.readerPreferences
        val restoredReaderPreferences=mutableListOf<()->Unit>()
        fun <T> temporarily(preference:tachiyomi.core.common.preference.Preference<T>,value:T) {
            val previous=preference.get();val wasSet=preference.isSet()
            restoredReaderPreferences += { if(wasSet) preference.set(previous) else preference.delete() }
            preference.set(value)
        }
        val toolsBefore = graph.libraryToolsStore.state.value
        val automationPreferences = context.getSharedPreferences("readingapp_reader_automation", 0)
        val automationKeys = setOf(
            "mode_${ReadingMode.WEBTOON.flagValue}",
            "speed_${ReadingMode.WEBTOON.flagValue}",
            "interval_${ReadingMode.WEBTOON.flagValue}",
        )
        val priorAutomationSettings = automationPreferences.all.filterKeys { it in automationKeys }
        val priorIdleTimeout = Configurator.getInstance().waitForIdleTimeout
        var driver: SqlDriver? = null
        var mangaId: Long? = null
        try {
            temporarily(readerPreferences.webtoonSidePadding,0)
            temporarily(readerPreferences.webtoonDisableZoomOut,true)
            temporarily(readerPreferences.cropBorders,true)
            temporarily(readerPreferences.cropBordersWebtoon,true)
            // Continuous playback keeps frames moving; UIAutomator's default long idle wait can
            // otherwise block until an entire chapter reaches its boundary.
            Configurator.getInstance().waitForIdleTimeout = 100L
            graph.uiPreferences.arkhiveReducedMotion.set(false)
            val activeDriver = DatabaseBindings.providesSqlDriver(context, DatabaseRepositoryImpl(lazy { requireNotNull(driver) }))
            driver = activeDriver
            val database = DatabaseBindings.providesDatabase(activeDriver)
            val mangas = MangaRepositoryImpl(database)
            val chapters = ChapterRepositoryImpl(database)
            val source = graph.sourceManager.get(OriginalDemoSource.MANGA_ID) as OriginalDemoSource
            val manga = NetworkToLocalManga(mangas)(Manga.create().copy(
                source = source.id,
                url = "/neon-courier/continuous-qa-$nonce",
                title = "Arkhive continuous mode QA $nonce",
                initialized = true,
                favoriteAt = Clock.System.now(),
                viewerFlags = ReadingMode.RIGHT_TO_LEFT.flagValue.toLong(),
                chapterFlags = Manga.CHAPTER_SORTING_NUMBER,
            ))
            mangaId = manga.id
            val seriesKey = "manga:${manga.id}"
            graph.libraryToolsStore.setProfile(seriesKey, graph.libraryToolsStore.profile(seriesKey).copy(autoSpeed = 240))
            val chapter = chapters.updateFromRemote(emptyList(), listOf(Chapter.create().copy(
                mangaId = manga.id,
                url = path,
                name = "Continuous mode QA",
                chapterNumber = 1.0,
                sourceOrder = 0,
                dateFetch = Clock.System.now(),
            )), emptyList()).single()

            ActivityScenario.launch<ReaderActivity>(ReaderActivity.newIntent(context, manga.id, chapter.id)).use { scenario ->
                await("initial page is decoded", scenario) {
                    var decoded = false
                    scenario.onActivity { activity ->
                        val page = activity.viewModel.state.value.currentChapter?.pages?.firstOrNull()
                        decoded = page?.status == eu.kanade.tachiyomi.source.model.Page.State.Ready &&
                            activity.viewModel.state.value.viewer is PagerViewer
                    }
                    decoded
                }
                scenario.onActivity { activity ->
                    assertReaderUsesFullContainerWidth(activity)
                    val state = activity.viewModel.state.value
                    val viewer = state.viewer as PagerViewer
                    assertTrue("Paged crop preference reaches the active renderer",viewer.config.imageCropBorders)
                    assertPagerImageFillsViewport(activity,viewer)
                    viewer.moveToPage(state.currentChapter!!.pages!![1])
                }
                await("a nonzero page becomes active", scenario) {
                    var selected = false
                    scenario.onActivity { selected = it.viewModel.state.value.currentPage == 2 }
                    selected
                }
                scenario.onActivity { it.viewModel.showMenus(true) }
                requireNotNull(device.wait(Until.findObject(By.text("RTL")), 10_000)).click()
                requireNotNull(device.wait(Until.findObject(By.text("Auto read")), 10_000)).click()
                val continuous = requireNotNull(device.wait(Until.findObject(By.text("Continuous scroll")), 10_000))
                assertTrue("Paged manga must offer an explicit switch to vertical scrolling", continuous.isEnabled)
                continuous.click()

                await("vertical mode and the current position survive rebinding", scenario) {
                    var switched = false
                    scenario.onActivity { activity ->
                        val state = activity.viewModel.state.value
                        switched = state.viewer is WebtoonViewer &&
                            state.currentChapter?.chapter?.id == chapter.id && state.currentPage == 2 &&
                            activity.viewModel.getMangaReadingMode() == ReadingMode.WEBTOON.flagValue
                    }
                    switched
                }
                assertNotNull("Continuous must remain selected after the viewer is recreated",
                    device.wait(Until.findObject(By.textContains("Scroll speed ·")), 10_000))
                scenario.onActivity { activity ->
                    assertReaderUsesFullContainerWidth(activity)
                    val viewer=activity.viewModel.state.value.viewer as WebtoonViewer
                    assertEquals("The full-width choice remains enabled through pager-to-Continuous rebind",0,viewer.recycler.sidePaddingPixels)
                    assertTrue("Webtoon crop preference remains an independent user choice",viewer.config.imageCropBorders)
                    assertTrue(readerPreferences.webtoonDisableZoomOut.get())
                    assertTrue(readerPreferences.cropBorders.get() && readerPreferences.cropBordersWebtoon.get())
                }
                await("Continuous mode remains bound after viewer recreation", scenario, 8_000) {
                    val automation = scenarioAutomationState(scenario)
                    automation?.settings?.mode == ReaderAutomationMode.CONTINUOUS && automation.continuousAvailable
                }

                await("the rebound vertical viewport has a decoded visible page", scenario, 12_000) {
                    var ready = false
                    scenario.onActivity { activity ->
                        val state = activity.viewModel.state.value
                        val viewer = state.viewer as? WebtoonViewer
                        ready = state.currentChapter?.chapter?.id == chapter.id && viewer != null &&
                            viewer.recycler.width==activity.findViewById<View>(R.id.reader_container).width &&
                            viewer.recycler.height > 0 &&
                            (0 until viewer.recycler.childCount).any { index ->
                                val child = viewer.recycler.getChildAt(index)
                                child.isShown && child.width==viewer.recycler.width &&
                                    (child as? ReaderPageImageView)?.isImageReadyForAutoScroll == true
                            }
                    }
                    ready
                }

                // This fixture starts on the previously selected page. Run fast enough to
                // cross the following page boundary and exercise readiness resumption there.
                val speedSlider = requireNotNull(device.wait(Until.findObject(By.desc("Continuous scroll speed")), 10_000))
                val sliderBounds = speedSlider.visibleBounds
                device.click(sliderBounds.right - 8, sliderBounds.centerY())
                val speedLabel = requireNotNull(device.wait(Until.findObject(By.textContains("Scroll speed ·")), 10_000))
                val selectedSpeed = Regex("Scroll speed · (\\d+)").find(speedLabel.text)?.groupValues?.get(1)?.toInt()
                    ?: error("Could not read Continuous speed from ${speedLabel.text}")
                assertTrue("The frontier fixture needs a high visible speed", selectedSpeed >= 200)

                var startingOffset = 0
                scenario.onActivity { activity ->
                    startingOffset = (activity.viewModel.state.value.viewer as WebtoonViewer).recycler.computeVerticalScrollOffset()
                }
                assertNotNull("The visible Start control should begin playback",
                    device.wait(Until.findObject(By.text("Start auto read")), 10_000))
                device.findObject(By.text("Start auto read")).click()
                await("Start leaves the Continuous controller running and exposes Pause", scenario, 8_000) {
                    var running = false
                    scenario.onActivity { activity ->
                        val automation = readerAutomationState(activity)
                        running = automation?.running == true && automation.settings.mode == ReaderAutomationMode.CONTINUOUS
                    }
                    running && device.findObject(By.desc("Pause auto read")) != null
                }
                await("native vertical reader crosses a decoded page frontier under Continuous auto read", scenario, 18_000) {
                    var moved = false
                    scenario.onActivity { activity ->
                        val offset = (activity.viewModel.state.value.viewer as? WebtoonViewer)
                            ?.recycler?.computeVerticalScrollOffset()
                        moved = offset != null && offset > startingOffset + 1_500
                    }
                    moved
                }
                await("Continuous remains active after crossing the decoded page frontier", scenario, 8_000) {
                    val automation = scenarioAutomationState(scenario)
                    automation?.running == true && automation.settings.mode == ReaderAutomationMode.CONTINUOUS &&
                        device.findObject(By.desc("Pause auto read")) != null
                }
                device.findObject(By.desc("Pause auto read")).click()
                assertTrue("The compact footer should expose Resume after pausing",
                    device.wait(Until.findObject(By.desc("Resume auto read")), 10_000) != null)
                assertTrue("The compact footer should no longer expose Pause",
                    device.wait(Until.gone(By.desc("Pause auto read")), 10_000))
                var pausedOffset = 0
                scenario.onActivity { activity ->
                    pausedOffset = (activity.viewModel.state.value.viewer as WebtoonViewer).recycler.computeVerticalScrollOffset()
                }
                delay(650)
                scenario.onActivity { activity ->
                    assertEquals("The visible pause action must cancel further vertical movement", pausedOffset,
                        (activity.viewModel.state.value.viewer as WebtoonViewer).recycler.computeVerticalScrollOffset())
                }
            }
        } finally {
            Configurator.getInstance().waitForIdleTimeout = priorIdleTimeout
            driver?.let { activeDriver ->
                mangaId?.let { id -> withContext(Dispatchers.IO) {
                    runCatching { activeDriver.execute(null, "DELETE FROM manga WHERE id = ?", 1) { bindLong(0, id) }.await() }
                } }
                activeDriver.close()
            }
            OriginalDemoSource.controlledPageLists.remove(path)
            urls.forEach { runCatching { graph.chapterCache.getImageFile(it).delete() } }
            graph.libraryToolsStore.update { toolsBefore }
            graph.uiPreferences.arkhiveReducedMotion.set(oldReducedMotion)
            restoredReaderPreferences.asReversed().forEach { it() }
            automationPreferences.edit().apply {
                automationKeys.forEach(::remove)
                priorAutomationSettings.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Float -> putFloat(key, value)
                        else -> Unit
                    }
                }
            }.commit()
        }
    }

    @Test fun cancellingAnimatedFinalPageTurnDoesNotCommitReadProgress(): Unit = runBlocking {
        val graph = context.appGraph
        val nonce = UUID.randomUUID().toString()
        val path = "/neon-courier/chapter/1?cancel-turn-qa=$nonce"
        val urls = OriginalDemoCatalog.pageUrls("/neon-courier/chapter/1").map { "$it&cancel-turn-qa=$nonce" }
        OriginalDemoSource.controlledPageLists[path] = urls
        val oldReducedMotion = graph.uiPreferences.arkhiveReducedMotion.get()
        val oldPageTransitions = graph.readerPreferences.pageTransitions.get()
        val toolsBefore = graph.libraryToolsStore.state.value
        var driver: SqlDriver? = null
        var mangaId: Long? = null
        try {
            graph.uiPreferences.arkhiveReducedMotion.set(false)
            graph.readerPreferences.pageTransitions.set(true)
            val activeDriver = DatabaseBindings.providesSqlDriver(context, DatabaseRepositoryImpl(lazy { requireNotNull(driver) }))
            driver = activeDriver
            val database = DatabaseBindings.providesDatabase(activeDriver)
            val mangas = MangaRepositoryImpl(database)
            val chapters = ChapterRepositoryImpl(database)
            val source = graph.sourceManager.get(OriginalDemoSource.MANGA_ID) as OriginalDemoSource
            val manga = NetworkToLocalManga(mangas)(Manga.create().copy(
                source = source.id,
                url = "/neon-courier/cancel-turn-qa-$nonce",
                title = "Arkhive cancelled page turn QA $nonce",
                initialized = true,
                favoriteAt = Clock.System.now(),
                viewerFlags = ReadingMode.LEFT_TO_RIGHT.flagValue.toLong(),
                chapterFlags = Manga.CHAPTER_SORTING_NUMBER,
            ))
            mangaId = manga.id
            val chapter = chapters.updateFromRemote(emptyList(), listOf(Chapter.create().copy(
                mangaId = manga.id,
                url = path,
                name = "Cancelled final page turn QA",
                chapterNumber = 1.0,
                sourceOrder = 0,
                dateFetch = Clock.System.now(),
            )), emptyList()).single()
            val seriesKey = "manga:${manga.id}"
            graph.libraryToolsStore.update { it.copy(statsEnabled = true, stats = it.stats - seriesKey) }

            ActivityScenario.launch<ReaderActivity>(ReaderActivity.newIntent(context, manga.id, chapter.id)).use { scenario ->
                var originalPagePosition = -1
                var originChapterPageIndex = -1
                var originReadPage = -1
                await("reader loads the authored fixture chapter", scenario) {
                    var opened = false
                    scenario.onActivity { activity ->
                        val state = activity.viewModel.state.value
                        opened = state.currentChapter?.chapter?.id == chapter.id &&
                            state.currentChapter?.pages.orEmpty().size == OriginalDemoCatalog.PAGES_PER_CHAPTER &&
                            state.viewer is PagerViewer
                    }
                    opened
                }
                scenario.onActivity { activity ->
                    val state = activity.viewModel.state.value
                    val viewer = state.viewer as PagerViewer
                    val targetIndex = state.currentChapter!!.pages!!.lastIndex
                    val beforeTarget = state.currentChapter!!.pages!![targetIndex - 1]
                    originChapterPageIndex = targetIndex - 1
                    viewer.moveToPage(beforeTarget)
                }
                await("penultimate page is active before the animated turn", scenario) {
                    var active = false
                    scenario.onActivity { activity ->
                        val state = activity.viewModel.state.value
                        val pages = state.currentChapter?.pages.orEmpty()
                        active = state.currentChapter?.chapter?.id == chapter.id &&
                            pages.size > 1 && state.currentPage == pages[pages.lastIndex - 1].number
                    }
                    active
                }
                scenario.onActivity { activity ->
                    val state = activity.viewModel.state.value
                    originalPagePosition = (state.viewer as PagerViewer).pager.currentItem
                    originReadPage = state.currentPage
                }
                await("penultimate and final page are both decoded", scenario) {
                    var ready = false
                    scenario.onActivity { activity ->
                        val state = activity.viewModel.state.value
                        val viewer = state.viewer as? PagerViewer
                        val pages = state.currentChapter?.pages.orEmpty()
                        val holders = activity.findViewById<View>(android.R.id.content).all<PagerPageHolder>()
                        val target = pages.lastOrNull()
                        val current = pages.getOrNull(originChapterPageIndex)
                        ready = viewer != null && target != null && current != null &&
                            pagerIdle(viewer) &&
                            target.status == eu.kanade.tachiyomi.source.model.Page.State.Ready &&
                            current.status == eu.kanade.tachiyomi.source.model.Page.State.Ready &&
                            holders.firstOrNull { it.page === target }?.isImageReadyForAutoScroll == true &&
                            holders.firstOrNull { it.page === current }?.isImageReadyForAutoScroll == true
                    }
                    ready
                }

                scenario.onActivity { activity ->
                    val state = activity.viewModel.state.value
                    val viewer = state.viewer as PagerViewer
                    assertEquals(ReaderAutoAdvanceResult.ADVANCED, viewer.autoAdvanceWithinChapter(chapter.id, null))
                    assertTrue("The final-page turn should be in flight before cancellation", pendingAutoTurn(viewer))
                    assertEquals("A settling target must not persist as the active reading position", originReadPage,
                        activity.viewModel.state.value.currentPage)
                    assertEquals(originalPagePosition + 1, viewer.pager.currentItem)
                    activity.pauseReaderAutomation()
                    assertEquals("Cancellation returns the pager to the original page", originalPagePosition, viewer.pager.currentItem)
                    assertEquals("Cancellation keeps the recorded position at the origin", originReadPage,
                        activity.viewModel.state.value.currentPage)
                    assertFalse(graph.libraryToolsStore.state.value.stats[seriesKey]?.completedChapters?.contains(chapter.id.toString()) == true)
                    assertFalse(activity.viewModel.state.value.currentChapter!!.chapter.read)
                }
                delay(700)
                scenario.onActivity { activity ->
                    assertEquals("A cancelled final-page animation never completes the chapter", originalPagePosition,
                        (activity.viewModel.state.value.viewer as PagerViewer).pager.currentItem)
                    assertFalse(graph.libraryToolsStore.state.value.stats[seriesKey]?.completedChapters?.contains(chapter.id.toString()) == true)
                    assertFalse(activity.viewModel.state.value.currentChapter!!.chapter.read)
                }
            }
        } finally {
            driver?.let { activeDriver ->
                mangaId?.let { id -> withContext(Dispatchers.IO) {
                    runCatching { activeDriver.execute(null, "DELETE FROM manga WHERE id = ?", 1) { bindLong(0, id) }.await() }
                } }
                activeDriver.close()
            }
            OriginalDemoSource.controlledPageLists.remove(path)
            urls.forEach { runCatching { graph.chapterCache.getImageFile(it).delete() } }
            graph.libraryToolsStore.update { toolsBefore }
            graph.uiPreferences.arkhiveReducedMotion.set(oldReducedMotion)
            graph.readerPreferences.pageTransitions.set(oldPageTransitions)
        }
    }

    @Test fun retryingBrokenFinalPageCountsCompletionOnlyAfterItBecomesReady() = runBlocking {
        retryScenario(pageCount = 1, expectCompletion = true, decodeInBackground = true)
    }

    @Test fun retryOneBrokenImageKeepsTheViewportAndOtherReadyPages() = runBlocking {
        retryScenario(pageCount = OriginalDemoCatalog.PAGES_PER_CHAPTER, expectCompletion = false)
    }

    private suspend fun retryScenario(pageCount: Int, expectCompletion: Boolean, decodeInBackground: Boolean = false) {
        val graph = context.appGraph
        val nonce = UUID.randomUUID().toString()
        val path = "/neon-courier/chapter/1?retry-qa=$nonce"
        val urls = OriginalDemoCatalog.pageUrls("/neon-courier/chapter/1").take(pageCount).map { "$it&retry-qa=$nonce" }
        val badUrl = urls.first()
        OriginalDemoSource.controlledPageLists[path] = urls
        OriginalDemoImages.controlledImage(badUrl, byteArrayOf(1, 2, 3))

        lateinit var driver: SqlDriver
        val migration = DatabaseRepositoryImpl(lazy { driver })
        driver = DatabaseBindings.providesSqlDriver(context, migration)
        val database = DatabaseBindings.providesDatabase(driver)
        val mangas = MangaRepositoryImpl(database)
        val chapters = ChapterRepositoryImpl(database)
        val source = graph.sourceManager.get(OriginalDemoSource.MANGA_ID) as OriginalDemoSource
        var mangaId: Long? = null
        val toolsBefore = graph.libraryToolsStore.state.value
        try {
            val manga = NetworkToLocalManga(mangas)(Manga.create().copy(
                source = source.id,
                url = "/neon-courier/retry-qa-$nonce",
                title = "Arkhive one page retry QA $nonce",
                initialized = true,
                favoriteAt = Clock.System.now(),
                viewerFlags = ReadingMode.LEFT_TO_RIGHT.flagValue.toLong(),
                chapterFlags = Manga.CHAPTER_SORTING_NUMBER,
            ))
            mangaId = manga.id
            val chapter = chapters.updateFromRemote(emptyList(), listOf(Chapter.create().copy(
                mangaId = manga.id,
                url = path,
                name = "Retry QA with $pageCount page(s)",
                chapterNumber = 1.0,
                sourceOrder = 0,
                dateFetch = Clock.System.now(),
            )), emptyList()).single()
            val seriesKey = "manga:${manga.id}"
            graph.libraryToolsStore.update { it.copy(statsEnabled = true, stats = it.stats - seriesKey) }

            ActivityScenario.launch<ReaderActivity>(ReaderActivity.newIntent(context, manga.id, chapter.id)).use { scenario ->
                await("initial visible decode error", scenario) {
                    var failedWithRetry = false
                    scenario.onActivity { activity ->
                        val state = activity.viewModel.state.value
                        val page = state.currentChapter?.pages?.firstOrNull()
                        val holder = activity.findViewById<View>(android.R.id.content).all<PagerPageHolder>()
                            .firstOrNull { it.page.index == 0 && it.isShown }
                        val retry = holder?.findViewById<View>(R.id.action_retry)
                        val otherReady = state.currentChapter?.pages.orEmpty().any {
                            it.index != 0 && it.status == eu.kanade.tachiyomi.source.model.Page.State.Ready
                        }
                        failedWithRetry = state.currentChapter?.chapter?.id == chapter.id &&
                            state.viewer is PagerViewer && page?.status is eu.kanade.tachiyomi.source.model.Page.State.Error &&
                            page.renderState == eu.kanade.tachiyomi.ui.reader.model.ReaderPage.RenderState.ERROR &&
                            retry?.isShown == true && (pageCount == 1 || otherReady)
                    }
                    failedWithRetry
                }

                var chapterId = 0L
                var activePage = 0
                var pagerItem = 0
                var readyOtherPages = emptyMap<Int, eu.kanade.tachiyomi.source.model.Page.State>()
                scenario.onActivity { activity ->
                    val state = activity.viewModel.state.value
                    chapterId = requireNotNull(state.currentChapter).chapter.id
                    activePage = state.currentPage
                    val viewer = state.viewer as PagerViewer
                    pagerItem = viewer.pager.currentItem
                    readyOtherPages = state.currentChapter!!.pages.orEmpty().filter { it.index != 0 && it.status == eu.kanade.tachiyomi.source.model.Page.State.Ready }
                        .associate { it.index to it.status }
                    if (pageCount > 1) assertTrue("A neighboring page should remain Ready during retry", readyOtherPages.isNotEmpty())
                    assertFalse(graph.libraryToolsStore.state.value.stats["manga:${manga.id}"]?.completedChapters?.contains(chapter.id.toString()) == true)
                    val holder = activity.findViewById<View>(android.R.id.content).all<PagerPageHolder>()
                        .first { it.page.index == 0 && it.isShown }
                    val retry = requireNotNull(holder.findViewById<View>(R.id.action_retry))
                    OriginalDemoImages.controlledImage(badUrl, null)
                    if (!decodeInBackground) {
                        assertTrue("The visible retry action should invoke the existing loader", retry.performClick())
                    }
                }

                if (decodeInBackground) {
                    // Exercise a successful decode while the Activity is not resumed. The
                    // completion observer must remain armed and finish only after foreground
                    // return, rather than consuming Ready and silently exiting in the background.
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
                    scenario.onActivity { activity ->
                        val page = requireNotNull(activity.viewModel.state.value.currentChapter?.pages?.firstOrNull())
                        page.chapter.pageLoader?.retryPage(page)
                    }
                }

                await(if (decodeInBackground) "successful retry decode while backgrounded" else "successful retry decode", scenario) {
                    var repaired = false
                    scenario.onActivity { activity ->
                        val state = activity.viewModel.state.value
                        val page = state.currentChapter?.pages?.firstOrNull()
                        val holder = activity.findViewById<View>(android.R.id.content).all<PagerPageHolder>()
                            .firstOrNull { it.page.index == 0 && it.isShown }
                        repaired = page?.status == eu.kanade.tachiyomi.source.model.Page.State.Ready &&
                            page.renderState == eu.kanade.tachiyomi.ui.reader.model.ReaderPage.RenderState.READY &&
                            holder?.isImageReadyForAutoScroll == true
                    }
                    repaired
                }
                if (decodeInBackground) {
                    assertFalse("A final page decoded in background must not complete the chapter",
                        graph.libraryToolsStore.state.value.stats["manga:${manga.id}"]?.completedChapters?.contains(chapter.id.toString()) == true)
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                }
                if (expectCompletion) {
                    await("foreground final-page completion", scenario) {
                        graph.libraryToolsStore.state.value.stats["manga:${manga.id}"]?.completedChapters?.contains(chapter.id.toString()) == true
                    }
                }
                scenario.onActivity { activity ->
                    val state = activity.viewModel.state.value
                    assertEquals(chapterId, state.currentChapter?.chapter?.id)
                    assertEquals(activePage, state.currentPage)
                    assertEquals(pagerItem, (state.viewer as PagerViewer).pager.currentItem)
                    readyOtherPages.forEach { (index, status) ->
                        assertEquals(status, state.currentChapter?.pages?.get(index)?.status)
                    }
                    assertEquals("Only the failed page should have been retried.",
                        eu.kanade.tachiyomi.source.model.Page.State.Ready,
                        state.currentChapter?.pages?.first()?.status)
                    if (!expectCompletion) {
                        assertFalse("Retrying a non-final page must not complete the chapter",
                            graph.libraryToolsStore.state.value.stats["manga:${manga.id}"]?.completedChapters?.contains(chapter.id.toString()) == true)
                    }
                }
            }
        } finally {
            OriginalDemoImages.controlledImage(badUrl, null)
            OriginalDemoSource.controlledPageLists.remove(path)
            urls.forEach { runCatching { graph.chapterCache.getImageFile(it).delete() } }
            mangaId?.let { id -> withContext(Dispatchers.IO) {
                runCatching { driver.execute(null, "DELETE FROM manga WHERE id = ?", 1) { bindLong(0, id) }.await() }
            } }
            driver.close()
            graph.libraryToolsStore.update { toolsBefore }
        }
    }

    private suspend fun await(
        stage: String,
        scenario: ActivityScenario<ReaderActivity>,
        timeoutMs: Long = 25_000,
        condition: () -> Boolean,
    ) {
        try {
            withTimeout(timeoutMs) {
                while (!condition()) delay(100)
            }
        } catch (error: TimeoutCancellationException) {
            var details = "activity unavailable"
            scenario.onActivity { activity ->
                val state = activity.viewModel.state.value
                val pages = state.currentChapter?.pages.orEmpty()
                val page = pages.firstOrNull()
                val holders = activity.findViewById<View>(android.R.id.content).all<PagerPageHolder>()
                val holder = holders.firstOrNull { it.page.index == 0 && it.isShown }
                val retry = holder?.findViewById<View>(R.id.action_retry)
                val retryError = retry?.parent as? View
                val readyPages = pages.filter { it.status == eu.kanade.tachiyomi.source.model.Page.State.Ready }.map { it.index }
                val automation = readerAutomationState(activity)
                val vertical = state.viewer as? WebtoonViewer
                val visibleVerticalChildren = vertical?.let { viewer ->
                    (0 until viewer.recycler.childCount).mapNotNull { index ->
                        val child = viewer.recycler.getChildAt(index)
                        val image = child as? ReaderPageImageView
                        if (!child.isShown) null else "${child.javaClass.simpleName}:ready=${image?.isImageReadyForAutoScroll}:size=${child.width}x${child.height}"
                    }
                }.orEmpty()
                details = "chapter=${state.currentChapter?.chapter?.id},currentPage=${state.currentPage}," +
                    "pageCount=${pages.size},pageStatus=${page?.status},render=${page?.renderState}," +
                    "streamPresent=${page?.stream != null},imageUrl=${page?.imageUrl}," +
                    "visibleHolders=${holders.filter { it.isShown }.map { it.page.index }}," +
                    "visibleReady=${holder?.isImageReadyForAutoScroll},retryShown=${retry?.isShown}," +
                    "errorContainerShown=${retryError?.isShown},readyPages=$readyPages," +
                    "automationMode=${automation?.settings?.mode},automationRunning=${automation?.running}," +
                    "continuousAvailable=${automation?.continuousAvailable},automationMessage=${automation?.message}," +
                    "viewer=${state.viewer?.javaClass?.simpleName},verticalOffset=${vertical?.recycler?.computeVerticalScrollOffset()}," +
                    "visibleVerticalChildren=$visibleVerticalChildren," +
                    "autoReadLayout=${vertical?.autoReadDiagnosticState(state.currentChapter?.chapter?.id ?: Long.MIN_VALUE)}," +
                    "windowFocus=${activity.hasWindowFocus()},lifecycle=${activity.lifecycle.currentState}"
            }
            throw AssertionError("Timed out at $stage: $details", error)
        }
    }

    private fun scenarioAutomationState(scenario: ActivityScenario<ReaderActivity>): eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationUiState? {
        var value: eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationUiState? = null
        scenario.onActivity { value = readerAutomationState(it) }
        return value
    }

    private fun readerAutomationState(activity: ReaderActivity): eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationUiState? = runCatching {
        val field = activity.javaClass.declaredFields.first { it.name == "readerAutomation\$delegate" }
        field.isAccessible = true
        val controller = (field.get(activity) as Lazy<*>).value as ReaderAutomationController
        controller.uiState
    }.getOrNull()

    private fun pendingAutoTurn(viewer: PagerViewer): Boolean = runCatching {
        val field = viewer.javaClass.superclass.getDeclaredField("automaticPageOrigin")
        field.isAccessible = true
        field.get(viewer) is Int
    }.getOrDefault(false)

    private fun pagerIdle(viewer: PagerViewer): Boolean = runCatching {
        val field = viewer.javaClass.superclass.getDeclaredField("isIdle")
        field.isAccessible = true
        field.getBoolean(viewer)
    }.getOrDefault(false)

    private inline fun <reified T : View> View.all(): List<T> {
        val out = mutableListOf<T>()
        val pending = ArrayDeque<View>()
        pending.add(this)
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            if (view is T) out += view
            if (view is ViewGroup) for (index in 0 until view.childCount) pending.add(view.getChildAt(index))
        }
        return out
    }
}
