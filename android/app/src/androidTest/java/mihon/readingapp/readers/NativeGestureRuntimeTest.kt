package mihon.readingapp.readers

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.animation.AnimatorSet
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.SqlDriver
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationController
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationMode
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationSettings
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.app.di.appGraph
import mihon.data.database.DatabaseRepositoryImpl
import mihon.readingapp.fixtures.OriginalDemoCatalog
import mihon.readingapp.fixtures.OriginalDemoSource
import eu.kanade.tachiyomi.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import java.util.UUID
import kotlin.time.Clock

/** Exercises native Webtoon touch dispatch while continuous auto-read is active. */
@RunWith(AndroidJUnit4::class)
class NativeGestureRuntimeTest {
    @Volatile private var lastNativeTapTrace:String = "none"
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.appGraph
    private val device get() = UiDevice.getInstance(instrumentation)

    @Test fun continuousAutoReadPausesButAlternatingManualDragsKeepMoving() = runBlocking {
        val nonce = UUID.randomUUID().toString()
        val chapterPath = "/rainlight-district/chapter/1?gesture-qa=$nonce"
        val urls = OriginalDemoCatalog.pageUrls("/rainlight-district/chapter/1").map { "$it&gesture-qa=$nonce" }
        val sourceId = OriginalDemoSource.WEBTOON_ID
        val automationPreferences = context.getSharedPreferences("readingapp_reader_automation", 0)
        val automationKeys = setOf(
            "mode_${ReadingMode.WEBTOON.flagValue}",
            "speed_${ReadingMode.WEBTOON.flagValue}",
            "interval_${ReadingMode.WEBTOON.flagValue}",
        )
        val priorAutomationSettings = automationPreferences.all.filterKeys { it in automationKeys }
        val priorIdleTimeout = Configurator.getInstance().waitForIdleTimeout
        val toolsBefore = graph.libraryToolsStore.state.value
        val oldReducedMotion = graph.uiPreferences.arkhiveReducedMotion.get()
        var driver: SqlDriver? = null
        var mangaId: Long? = null
        OriginalDemoSource.controlledPageLists[chapterPath] = urls
        try {
            // The authored playback case exercises the supported motion path; preserve the
            // user's reduced-motion setting exactly before restoring it after the fixture.
            graph.uiPreferences.arkhiveReducedMotion.set(false)
            check(!graph.uiPreferences.arkhiveReducedMotion.get()) { "Continuous playback fixture requires motion enabled" }
            Configurator.getInstance().waitForIdleTimeout = 100L
            automationPreferences.edit()
                .putString("mode_${ReadingMode.WEBTOON.flagValue}", ReaderAutomationMode.CONTINUOUS.name)
                .putInt("speed_${ReadingMode.WEBTOON.flagValue}", 240)
                .putInt("interval_${ReadingMode.WEBTOON.flagValue}", 1)
                .commit()

            lateinit var production: SqlDriver
            val migration = DatabaseRepositoryImpl(lazy { production })
            production = DatabaseBindings.providesSqlDriver(context, migration)
            driver = production
            val database = DatabaseBindings.providesDatabase(production)
            val mangas = MangaRepositoryImpl(database)
            val chapters = ChapterRepositoryImpl(database)
            val manga = NetworkToLocalManga(mangas)(Manga.create().copy(
                source = sourceId,
                url = "/rainlight-district/gesture-qa-$nonce",
                title = "Arkhive native gesture QA $nonce",
                initialized = true,
                favoriteAt = Clock.System.now(),
                viewerFlags = ReadingMode.WEBTOON.flagValue.toLong(),
                chapterFlags = Manga.CHAPTER_SORTING_NUMBER,
            ))
            mangaId = manga.id
            val chapter = chapters.updateFromRemote(emptyList(), listOf(Chapter.create().copy(
                mangaId = manga.id,
                url = chapterPath,
                name = "Native gesture QA",
                chapterNumber = 1.0,
                sourceOrder = 0,
                dateFetch = Clock.System.now(),
            )), emptyList()).single()

            ActivityScenario.launch<ReaderActivity>(ReaderActivity.newIntent(context, manga.id, chapter.id)).use { scenario ->
                try {
                await("decoded first Webtoon page") {
                    var ready = false
                    scenario.onActivity { activity ->
                        val viewer = activity.viewModel.state.value.viewer as? WebtoonViewer
                        ready = viewer != null && (0 until viewer.recycler.childCount).any { index ->
                            val child = viewer.recycler.getChildAt(index)
                            child.isShown && (child as? eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView)
                                ?.isImageReadyForAutoScroll == true
                        }
                    }
                    ready
                }

                scenario.onActivity { activity ->
                    activity.hideMenu()
                }
                lateinit var readerActivity: ReaderActivity
                scenario.onActivity { readerActivity = it }
                fun nativeViewport(): Pair<Int, Int> {
                    var result = 0 to 0
                    instrumentation.runOnMainSync {
                        val viewer = readerActivity.findViewById<View>(R.id.viewer_container)
                        result = viewer.height to (viewer.layoutParams as android.widget.FrameLayout.LayoutParams).bottomMargin
                    }
                    return result
                }
                fun liveOffsetAndRunning(): Pair<Int, Boolean> {
                    var result = 0 to false
                    instrumentation.runOnMainSync {
                        val viewer = readerActivity.viewModel.state.value.viewer as? WebtoonViewer
                        result = (viewer?.recycler?.computeVerticalScrollOffset() ?: 0) to
                            (readerAutomationState(readerActivity)?.running == true)
                    }
                    return result
                }
                fun tapCenterWhileLive() = dispatchCenterTap(readerActivity)

                assertTrue("Hidden native reader has no automatic playback button",
                    device.wait(Until.gone(By.desc("Resume auto read")), 2_000))
                assertTrue("Hidden native reader has no pause button",
                    device.wait(Until.gone(By.desc("Pause auto read")), 2_000))
                val hiddenOffViewport = nativeViewport()
                assertEquals("Hidden fullscreen reserves no footer height", 0, hiddenOffViewport.second)
                assertTrue("The quiet reader expands to the available viewport", hiddenOffViewport.first > 0)
                assertTrue("Capture hidden native fullscreen with auto-read off",
                    device.takeScreenshot(File(context.cacheDir, "qa-0.4.2-native-hidden-off.png")))

                tapCenterWhileLive()
                val resumeObserved=device.wait(Until.findObject(By.desc("Resume auto read")),5_000)
                val resume = requireNotNull(resumeObserved) {
                    "A center tap should reveal an accessible Resume action"
                }
                val resumeBounds = resume.visibleBounds
                val density = context.resources.displayMetrics.density
                assertTrue("Revealed play/pause target is at least 48dp wide", resumeBounds.width() / density >= 48f)
                assertTrue("Revealed play/pause target stays at most 56dp wide", resumeBounds.width() / density <= 56f)
                assertTrue("Revealed play/pause target is at least 48dp tall", resumeBounds.height() / density >= 48f)
                val revealedOffViewport = nativeViewport()
                assertTrue("Revealed controls reserve their visible footer", revealedOffViewport.second > 0)
                assertTrue("Capture revealed native controls with auto-read off",
                    device.takeScreenshot(File(context.cacheDir, "qa-0.4.2-native-revealed-off.png")))
                requireNotNull(device.wait(Until.findObject(By.text("⋮")), 3_000)) {
                    "Native webtoon toolbar should expose its reader options menu"
                }.click()
                requireNotNull(device.wait(Until.findObject(By.text("Auto read")), 3_000)) {
                    "Native Auto read settings are available from the webtoon options menu"
                }.click()
                assertNotNull("Revealed Auto-read options include Continuous scroll", device.wait(Until.findObject(By.text("Continuous scroll")), 3_000))
                assertNotNull("Revealed Auto-read options include timed page/screen mode", device.wait(Until.findObject(By.text("Timed page / screen steps")), 3_000))
                assertTrue("Capture revealed reader automation modes and speed controls",
                    device.takeScreenshot(File(context.cacheDir, "qa-0.4.2-native-auto-options.png")))
                assertTrue("Auto-read options can be dismissed without starting playback", device.pressBack())
                requireNotNull(device.wait(Until.findObject(By.desc("Resume auto read")), 3_000)).click()

                val initialOffset = liveOffsetAndRunning().first
                withTimeout(8_000) {
                    while (true) {
                        val (offset, running) = liveOffsetAndRunning()
                        if (running && offset > initialOffset + 100) break
                        delay(50)
                    }
                }
                assertTrue("The revealed Resume action starts playback and returns to quiet fullscreen",
                    device.wait(Until.gone(By.desc("Pause auto read")), 3_000) && device.wait(Until.gone(By.desc("Resume auto read")), 3_000))
                val hiddenRunningViewport = nativeViewport()
                assertEquals("Running fullscreen reserves no footer height", 0, hiddenRunningViewport.second)
                assertTrue("Hiding the controls expands the native viewport", hiddenRunningViewport.first > revealedOffViewport.first)
                delay(250)
                assertTrue("Viewport expansion does not pause the newly started continuous run", liveOffsetAndRunning().second)
                assertTrue("Capture running native fullscreen without the playback strip",
                    device.takeScreenshot(File(context.cacheDir, "qa-0.4.2-native-hidden-running.png")))

                tapCenterWhileLive()
                assertNotNull("A center tap pauses playback and reveals Resume", device.wait(Until.findObject(By.desc("Resume auto read")), 5_000))
                assertTrue("Center tap leaves no persistent running strip", device.wait(Until.gone(By.desc("Pause auto read")), 3_000))
                assertTrue("Capture the revealed paused native controls",
                    device.takeScreenshot(File(context.cacheDir, "qa-0.4.2-native-revealed-paused.png")))
                scenario.onActivity { it.hideMenu() }
                val hiddenPausedViewport = nativeViewport()
                assertEquals("Paused fullscreen reserves no footer height", 0, hiddenPausedViewport.second)
                assertTrue("Paused fullscreen does not leave a Resume strip visible",
                    device.wait(Until.gone(By.desc("Resume auto read")), 3_000))
                assertTrue("Capture hidden native fullscreen with auto-read paused",
                    device.takeScreenshot(File(context.cacheDir, "qa-0.4.2-native-hidden-paused.png")))
                tapCenterWhileLive()
                assertNotNull("A center tap restores paused controls", device.wait(Until.findObject(By.desc("Resume auto read")), 3_000))
                scenario.onActivity { activity -> automationController(activity).start() }
                val visiblePause = requireNotNull(device.wait(Until.findObject(By.desc("Pause auto read")), 5_000))
                assertTrue("A running reader exposes its accessible Pause action when controls are visible", visiblePause.isEnabled)
                assertTrue("Capture revealed native controls while auto-read is running",
                    device.takeScreenshot(File(context.cacheDir, "qa-0.4.2-native-revealed-running.png")))
                visiblePause.click()
                assertNotNull("Pause action returns to Resume while controls remain visible", device.wait(Until.findObject(By.desc("Resume auto read")), 3_000))
                requireNotNull(device.wait(Until.findObject(By.desc("Resume auto read")), 3_000)).click()
                withTimeout(8_000) {
                    while (true) {
                        val (offset, running) = liveOffsetAndRunning()
                        if (running && offset > initialOffset + 200) break
                        delay(50)
                    }
                }
                val resumedQuietViewport = nativeViewport()
                assertEquals("Resumed hidden fullscreen has no reserved strip", 0, resumedQuietViewport.second)
                assertTrue("Resuming from the shown button expands the native viewport", resumedQuietViewport.first > revealedOffViewport.first)
                delay(250)
                assertTrue("The actual shown Resume button remains running after fullscreen reflow", liveOffsetAndRunning().second)

                var widths = Triple(0, 0, 0)
                instrumentation.runOnMainSync {
                    val root = readerActivity.findViewById<View>(R.id.reader_container)
                    val viewer = readerActivity.findViewById<View>(R.id.viewer_container)
                    val decor = readerActivity.window.decorView
                    val rootLocation = IntArray(2).also(root::getLocationOnScreen)
                    val viewerLocation = IntArray(2).also(viewer::getLocationOnScreen)
                    val windowLocation = IntArray(2).also(decor::getLocationOnScreen)
                    assertEquals("Reader container keeps full window width", decor.width, root.width)
                    assertEquals("Viewer adds no horizontal outer margin", root.width, viewer.width)
                    assertEquals(windowLocation[0], rootLocation[0])
                    assertEquals(rootLocation[0], viewerLocation[0])
                    assertEquals(rootLocation[0] + root.width, viewerLocation[0] + viewer.width)
                    widths = Triple(root.width, viewer.width, viewerLocation[0])
                }
                assertEquals("Quiet native viewport reaches the full reader container width", widths.first, widths.second)

                val scrollStates = mutableListOf<Int>()
                instrumentation.runOnMainSync {
                    val viewer = readerActivity.viewModel.state.value.viewer as WebtoonViewer
                    viewer.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                            scrollStates += newState
                        }
                    })
                }

                // Activity.dispatchTouchEvent reaches the real frame/recycler listeners. A cancel
                // ends each drag without starting an uncontrolled fling, so alternating direction
                // deltas are measurable and the test also exercises ACTION_CANCEL cleanup.
                repeat(8) { gesture ->
                    val fingerMovesUp = gesture % 2 == 0
                    val before = liveOffsetAndRunning().first
                    dispatchDrag(readerActivity, fingerMovesUp = fingerMovesUp, cancel = true)
                    if (gesture == 0) {
                        assertFalse("Touch pauses auto-read during the real Activity dispatch",
                            liveOffsetAndRunning().second)
                    }
                    await("alternating manual drag $gesture changes the RecyclerView offset in its direction") {
                        val after = liveOffsetAndRunning().first
                        if (fingerMovesUp) after > before + 50 else after < before - 50
                    }
                }

                assertTrue("A genuine drag state reached RecyclerView", scrollStates.contains(RecyclerView.SCROLL_STATE_DRAGGING))
                assertFalse("Manual gestures do not restart auto-read", liveOffsetAndRunning().second)

                // A fast ACTION_UP starts RecyclerView settling. The next opposite gesture must
                // take over that motion even though the scroll callback pauses auto-read.
                dispatchDrag(scenario, fingerMovesUp = true, cancel = false)
                await("fast ACTION_UP enters RecyclerView settling") {
                    var settling = false
                    scenario.onActivity { settling = scrollStates.contains(RecyclerView.SCROLL_STATE_SETTLING) }
                    settling
                }
                val beforeFlingInterrupt = scenarioOffset(scenario)
                dispatchDrag(scenario, fingerMovesUp = false, cancel = true)
                await("reversed ACTION_CANCEL gesture interrupts settling and moves the viewport") {
                    scenarioOffset(scenario) < beforeFlingInterrupt - 50
                }

                // Exercise the real zoom-fling animator, then verify the first fresh tap after it
                // ends reaches the tap listener rather than inheriting stale manual-scroll state.
                scenario.onActivity { activity ->
                    activity.hideMenu()
                }
                var beforeZoomX = 0f
                var zoomFlingStarted = false
                scenario.onActivity { activity ->
                    val recycler = (activity.viewModel.state.value.viewer as WebtoonViewer).recycler
                    beforeZoomX = recycler.x
                    recycler.onScale(2f)
                    zoomFlingStarted = recycler.zoomFling(2_400, 0)
                }
                assertTrue("A horizontal zoom fling starts while native content is zoomed", zoomFlingStarted)
                await("zoom fling visibly moves the scaled reader", timeoutMs = 1_000) {
                    var moved = false
                    scenario.onActivity { activity ->
                        moved = (activity.viewModel.state.value.viewer as WebtoonViewer).recycler.x > beforeZoomX + 5f
                    }
                    moved
                }
                await("tracked zoom-fling animator completes", timeoutMs = 1_000) {
                    var running = false
                    scenario.onActivity { activity ->
                        running = zoomFlingRunning((activity.viewModel.state.value.viewer as WebtoonViewer).recycler)
                    }
                    !running
                }
                var settledZoomX = 0f
                scenario.onActivity { activity ->
                    settledZoomX = (activity.viewModel.state.value.viewer as WebtoonViewer).recycler.x
                }
                delay(100)
                scenario.onActivity { activity ->
                    assertEquals("Completed zoom fling leaves a stable image position", settledZoomX,
                        (activity.viewModel.state.value.viewer as WebtoonViewer).recycler.x, 0.5f)
                }
                var tapCount = 0
                var originalTapListener: ((MotionEvent) -> Unit)? = null
                scenario.onActivity { activity ->
                    val recycler = (activity.viewModel.state.value.viewer as WebtoonViewer).recycler
                    originalTapListener = recycler.tapListener
                    recycler.tapListener = { event ->
                        tapCount++
                        originalTapListener?.invoke(event)
                    }
                }
                dispatchCenterTap(scenario)
                await("first center tap after a nonmoving edge fling reaches the tap listener") {
                    var count = 0
                    scenario.onActivity { count = tapCount }
                    count == 1
                }
                scenario.onActivity { activity ->
                    val recycler = (activity.viewModel.state.value.viewer as WebtoonViewer).recycler
                    recycler.tapListener = originalTapListener
                    recycler.onScale(0.5f)
                    activity.hideMenu()
                }
                scenario.onActivity { activity ->
                    val recycler = (activity.viewModel.state.value.viewer as WebtoonViewer).recycler
                    assertEquals("Zoom restoration returns to the native reader scale", 1f, recycler.scaleX, 0.001f)
                }
                scenario.onActivity { activity ->
                    automationController(activity).updateSettings(
                        ReaderAutomationSettings(mode = ReaderAutomationMode.TIMED_STEP, intervalSeconds = 2),
                    )
                    automationController(activity).start()
                }
                val beforeTimedStep = scenarioOffset(scenario)
                await("timed-step auto-read begins its animated movement", timeoutMs = 5_000) {
                    scenarioOffset(scenario) > beforeTimedStep + 20
                }
                dispatchTapThenCancel(scenario)
                assertFalse("Touch pauses the timed-step controller", scenarioAutomationState(scenario)?.running ?: true)
                val afterTimedPause = scenarioOffset(scenario)
                delay(400)
                assertTrue("Touch cancels the owned timed-step animation", scenarioOffset(scenario) <= afterTimedPause + 2)
                val beforePostTimedGesture = scenarioOffset(scenario)
                dispatchDrag(scenario, fingerMovesUp = false, cancel = true)
                await("manual drag remains responsive after timed-step cancellation") {
                    scenarioOffset(scenario) < beforePostTimedGesture - 50
                }
                scenario.onActivity { activity ->
                    val state = activity.viewModel.state.value
                    assertEquals("Touch keeps the same chapter selected", chapter.id, state.currentChapter?.chapter?.id)
                    assertTrue("Current page remains inside the chapter", state.currentPage in 1..urls.size)
                    val viewer = state.viewer as WebtoonViewer
                    assertTrue("Reader progress stays within the chapter viewport", viewer.recycler.computeVerticalScrollOffset() >= 0)
                }
                delay(1_700)
                val savedChapter = chapters.getChapterById(chapter.id)
                assertTrue("Persisted chapter progress remains valid",
                    savedChapter != null && savedChapter.lastPageRead in -1 until urls.size)
                } catch(error:Throwable) {
                    runCatching {
                        var failedActivity:ReaderActivity?=null
                        scenario.onActivity { activity -> failedActivity=activity }
                        failedActivity?.let {captureNativeFailureEvidence(it,manga.id,chapter.id,chapterPath)}
                    }
                    throw error
                }
            }
        } finally {
            val motionRestoreAtEntry = runCatching {
                graph.uiPreferences.arkhiveReducedMotion.set(oldReducedMotion)
                graph.uiPreferences.arkhiveReducedMotion.get()
            }
            try {
                driver?.let { activeDriver ->
                    mangaId?.let { id -> withContext(Dispatchers.IO) {
                        runCatching { activeDriver.execute(null, "DELETE FROM manga WHERE id = ?", 1) { bindLong(0, id) }.await() }
                    } }
                    activeDriver.close()
                }
                OriginalDemoSource.controlledPageLists.remove(chapterPath)
                urls.forEach { runCatching { graph.chapterCache.getImageFile(it).delete() } }
                graph.libraryToolsStore.update { toolsBefore }
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
                Configurator.getInstance().waitForIdleTimeout = priorIdleTimeout
            } finally {
                val motionRestoreReadback = runCatching { graph.uiPreferences.arkhiveReducedMotion.get() }
                runCatching {
                    File(context.cacheDir, "qa-0.4.3-failure-evidence/native-motion-restore.txt")
                        .apply { parentFile?.mkdirs() }
                        .writeText("before=$oldReducedMotion atFinally=${motionRestoreAtEntry.getOrNull()} final=${motionRestoreReadback.getOrNull()}")
                }
                check(motionRestoreAtEntry.getOrNull() == oldReducedMotion && motionRestoreReadback.getOrNull() == oldReducedMotion) {
                    "Reduced-motion preference was not restored: before=$oldReducedMotion atFinally=${motionRestoreAtEntry.getOrNull()} final=${motionRestoreReadback.getOrNull()}"
                }
            }
        }
    }

    private suspend fun await(stage: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!condition()) delay(40) }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for $stage", error)
        }
    }

    private fun dispatchTapThenCancel(scenario: ActivityScenario<ReaderActivity>) {
        scenario.onActivity { activity ->
            val viewer = activity.viewModel.state.value.viewer as WebtoonViewer
            val location = IntArray(2)
            viewer.recycler.getLocationInWindow(location)
            val x = location[0] + viewer.recycler.width / 2f
            val y = location[1] + viewer.recycler.height / 2f
            val downTime = SystemClock.uptimeMillis()
            for ((action, elapsedMs) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_CANCEL to 20L)) {
                val event = MotionEvent.obtain(downTime, downTime + elapsedMs, action, x, y, 0)
                try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
    }

    private fun dispatchCenterTap(scenario: ActivityScenario<ReaderActivity>) {
        scenario.onActivity { activity ->
            val viewer = activity.viewModel.state.value.viewer as WebtoonViewer
            val location = IntArray(2)
            viewer.recycler.getLocationInWindow(location)
            val x = location[0] + viewer.recycler.width / 2f
            val y = location[1] + viewer.recycler.height / 2f
            val downTime = SystemClock.uptimeMillis()
            for ((action, elapsedMs) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 60L)) {
                val event = MotionEvent.obtain(downTime, downTime + elapsedMs, action, x, y, 0)
                try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
    }

    /** Dispatch on the captured activity so live auto-scroll probes do not wait on scenario idle. */
    private fun dispatchCenterTap(activity: ReaderActivity) {
        val dispatches=mutableListOf<String>()
        instrumentation.runOnMainSync {
            val viewer = activity.viewModel.state.value.viewer as WebtoonViewer
            val location = IntArray(2)
            viewer.recycler.getLocationInWindow(location)
            val x = location[0] + viewer.recycler.width / 2f
            val y = location[1] + viewer.recycler.height / 2f
            val downTime = SystemClock.uptimeMillis()
            for ((action, elapsedMs) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 60L)) {
                val event = MotionEvent.obtain(downTime, downTime + elapsedMs, action, x, y, 0)
                try { dispatches += "action=$action handled=${activity.dispatchTouchEvent(event)}" } finally { event.recycle() }
            }
            lastNativeTapTrace="screen=${x.toInt()},${y.toInt()},recyclerWindow=${location[0]},${location[1]},size=${viewer.recycler.width}x${viewer.recycler.height},time=$downTime,events=$dispatches"
        }
    }

    private fun captureNativeFailureEvidence(activity:ReaderActivity,mangaId:Long,chapterId:Long,chapterPath:String) {
        val currentPackage=device.currentPackageName
        var runtime="unavailable"
        instrumentation.runOnMainSync {
            val state=activity.viewModel.state.value
            val viewer=state.viewer as? WebtoonViewer
            val recycler=viewer?.recycler
            val location=IntArray(2)
            recycler?.getLocationOnScreen(location)
            val automation=readerAutomationState(activity)
            val root=activity.window.decorView
            runtime="package=$currentPackage,activity=${activity.javaClass.name},lifecycle=${activity.lifecycle.currentState},windowFocus=${activity.hasWindowFocus()},"+
                "mangaId=$mangaId,stateManga=${state.manga?.id},chapterFixture=$chapterId,chapterPath=$chapterPath,currentChapter=${state.currentChapter?.chapter?.id},"+
                "menuVisible=${state.menuVisible},dialog=${state.dialog},viewer=${state.viewer?.javaClass?.name},autoState=$automation,"+
                "viewport=${recycler?.width}x${recycler?.height}@${location[0]},${location[1]},shown=${recycler?.isShown},focus=${recycler?.hasFocus()},"+
                "children=${recycler?.childCount},scrollOffset=${recycler?.computeVerticalScrollOffset()},root=${root.width}x${root.height},rootShown=${root.isShown},rootFocus=${root.hasFocus()},tap=$lastNativeTapTrace"
        }
        val dir=File(context.cacheDir,"qa-0.4.3-failure-evidence").apply {mkdirs()}
        val stem="native-center-tap-${SystemClock.uptimeMillis()}"
        File(dir,"$stem.txt").writeText(runtime)
        runCatching {device.dumpWindowHierarchy(File(dir,"$stem-hierarchy.xml"))}
        runCatching {device.takeScreenshot(File(dir,"$stem.png"))}
    }

    private fun scenarioOffset(scenario: ActivityScenario<ReaderActivity>): Int {
        var offset = 0
        scenario.onActivity { activity ->
            offset = (activity.viewModel.state.value.viewer as WebtoonViewer).recycler.computeVerticalScrollOffset()
        }
        return offset
    }

    private fun scenarioAutomationState(scenario: ActivityScenario<ReaderActivity>) = runCatching {
        var state: eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationUiState? = null
        scenario.onActivity { state = readerAutomationState(it) }
        state
    }.getOrNull()

    private fun zoomFlingRunning(recycler: eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonRecyclerView): Boolean {
        val field = recycler.javaClass.getDeclaredField("zoomFlingAnimator")
        field.isAccessible = true
        return (field.get(recycler) as? AnimatorSet)?.isRunning == true
    }

    private fun dispatchDrag(scenario: ActivityScenario<ReaderActivity>, fingerMovesUp: Boolean, cancel: Boolean) {
        scenario.onActivity { activity ->
            dispatchDragOnMain(activity, fingerMovesUp, cancel)
        }
    }

    private fun dispatchDrag(activity: ReaderActivity, fingerMovesUp: Boolean, cancel: Boolean) {
        instrumentation.runOnMainSync {
            dispatchDragOnMain(activity, fingerMovesUp, cancel)
        }
    }

    private fun dispatchDragOnMain(activity: ReaderActivity, fingerMovesUp: Boolean, cancel: Boolean) {
        run {
            val viewer = activity.viewModel.state.value.viewer as WebtoonViewer
            val location = IntArray(2)
            viewer.recycler.getLocationInWindow(location)
            val x = location[0] + viewer.recycler.width / 2f
            val topY = location[1] + viewer.recycler.height * 0.28f
            val bottomY = location[1] + viewer.recycler.height * 0.78f
            val startY = if (fingerMovesUp) bottomY else topY
            val endY = if (fingerMovesUp) topY else bottomY
            val downTime = SystemClock.uptimeMillis()
            fun send(action: Int, y: Float, elapsedMs: Long) {
                val eventTime = downTime + elapsedMs
                val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
                try {
                    activity.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
            send(MotionEvent.ACTION_DOWN, startY, 0)
            send(MotionEvent.ACTION_MOVE, startY + (endY - startY) * 0.55f, 24)
            send(MotionEvent.ACTION_MOVE, endY, 48)
            send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, endY, 72)
        }
    }

    private fun automationController(activity: ReaderActivity): ReaderAutomationController = runCatching {
        val field = activity.javaClass.declaredFields.first { it.name == "readerAutomation\$delegate" }
        field.isAccessible = true
        (field.get(activity) as Lazy<*>).value as ReaderAutomationController
    }.getOrThrow()

    private fun readerAutomationState(activity: ReaderActivity): eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationUiState? = runCatching {
        automationController(activity).uiState
    }.getOrNull()
}
