package mihon.readingapp.readers

import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.cash.sqldelight.db.SqlDriver
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerPageHolder
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonStripImageView
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import kotlinx.coroutines.*
import mihon.app.di.appGraph
import mihon.data.database.DatabaseRepositoryImpl
import mihon.readingapp.fixtures.OriginalDemoCatalog
import mihon.readingapp.fixtures.OriginalDemoImages
import mihon.readingapp.fixtures.OriginalDemoSource
import mihon.readingapp.library.ReadingProfile
import mihon.readingapp.library.ReadingRoom
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

/** Own extreme strips run through the real source, page loader, native activity and renderer. */
@RunWith(AndroidJUnit4::class)
class LongPageRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.appGraph
    private suspend fun fixture(
        mode: ReadingMode,
        sourceId: Long = OriginalDemoSource.MANGA_ID,
        sidePadding: Int = 0,
        disableZoomOut: Boolean = true,
        cropPaged: Boolean = false,
        cropWebtoon: Boolean = false,
        wideFixture: Boolean = false,
        body: suspend (ActivityScenario<ReaderActivity>, Long) -> Unit,
    ) {
        val nonce = UUID.randomUUID().toString()
        val slug = if(sourceId==OriginalDemoSource.WEBTOON_ID) "rainlight-district" else "neon-courier"
        val chapterPath = "/$slug/chapter/1"
        val path = "$chapterPath?qa=$nonce"
        val urls = OriginalDemoCatalog.pageUrls(chapterPath).map { "$it&qa=$nonce" }
        val data = instrumentation.context.assets.open(
            if (wideFixture) "reader/wide-extreme-tall.png" else "reader/extreme-tall.png",
        ).use { it.readBytes() }
        val prefs = graph.readerPreferences
        val restores = mutableListOf<() -> Unit>()
        fun <T> temporarily(preference: tachiyomi.core.common.preference.Preference<T>, value: T) {
            val before = preference.get(); val existed = preference.isSet(); restores += { if(existed) preference.set(before) else preference.delete() }; preference.set(value)
        }
        temporarily(prefs.imageScaleType, SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
        temporarily(prefs.smartLongPageFit, true); temporarily(prefs.webtoonSidePadding, sidePadding); temporarily(prefs.webtoonDisableZoomOut,disableZoomOut)
        temporarily(prefs.cropBordersWebtoon, cropWebtoon); temporarily(prefs.cropBorders, cropPaged)
        temporarily(prefs.dualPageSplitPaged, false); temporarily(prefs.dualPageSplitWebtoon, false)
        var driver: SqlDriver? = null; var inserted: Long? = null
        try {
            OriginalDemoSource.controlledPageLists[path] = urls
            OriginalDemoImages.controlledImage(urls.first(), data)
            lateinit var production: SqlDriver
            val migration = DatabaseRepositoryImpl(lazy { production })
            production = DatabaseBindings.providesSqlDriver(context, migration); driver = production
            val database = DatabaseBindings.providesDatabase(production)
            val mangas = MangaRepositoryImpl(database); val chapters = ChapterRepositoryImpl(database)
            val manga = NetworkToLocalManga(mangas)(Manga.create().copy(source = sourceId,
                url = "/$slug/long-qa-$nonce", title = "Arkhive original long-page QA $nonce", initialized = true,
                favoriteAt = Clock.System.now(), viewerFlags = mode.flagValue.toLong(), chapterFlags = Manga.CHAPTER_SORTING_NUMBER))
            inserted = manga.id
            val chapter = chapters.updateFromRemote(emptyList(), listOf(Chapter.create().copy(mangaId = manga.id,
                url = path, name = "Original extreme strip + normal pages", chapterNumber = 1.0, sourceOrder = 0, dateFetch = Clock.System.now())), emptyList()).single()
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.newIntent(context, manga.id, chapter.id)).use { body(it, chapter.id) }
        } finally {
            OriginalDemoImages.controlledImage(urls.first(), null); OriginalDemoSource.controlledPageLists.remove(path)
            urls.forEach { runCatching { graph.chapterCache.getImageFile(it).delete() } }
            inserted?.let { id -> withContext(Dispatchers.IO) { driver?.execute(null, "DELETE FROM manga WHERE id=?", 1) { bindLong(0, id) }?.await() } }
            restores.asReversed().forEach { it() }; driver?.close()
        }
    }
    private fun assertReaderUsesFullContainerWidth(activity: ReaderActivity) {
        val window=activity.window.decorView
        val reader=activity.findViewById<View>(R.id.reader_container)
        val viewer=activity.findViewById<View>(R.id.viewer_container)
        assertTrue("Window must be laid out",window.width>0)
        assertTrue("Reader container must be laid out",reader.width>0)
        assertTrue("Viewer must be laid out",viewer.width>0)
        assertEquals("Reader must reach both horizontal window edges",window.width,reader.width)
        assertEquals("No app-owned horizontal inset may be added around the native viewer",reader.width,viewer.width)
        val windowPosition=IntArray(2);window.getLocationInWindow(windowPosition)
        val readerPosition=IntArray(2);reader.getLocationInWindow(readerPosition)
        val viewerPosition=IntArray(2);viewer.getLocationInWindow(viewerPosition)
        assertEquals(windowPosition[0],readerPosition[0])
        assertEquals(windowPosition[0]+window.width,readerPosition[0]+reader.width)
        assertEquals("Viewer starts at the reader's left edge",readerPosition[0],viewerPosition[0])
        assertEquals("Viewer reaches the reader's right edge",readerPosition[0]+reader.width,viewerPosition[0]+viewer.width)
    }

    private fun dispatchNativePinch(
        activity: ReaderActivity,
        zoomIn: Boolean,
        terminalSpanFraction: Float? = null,
    ) {
        val viewport = (activity.viewModel.state.value.viewer as WebtoonViewer).getView()
        dispatchNativePinch(activity, viewport, zoomIn, terminalSpanFraction)
    }

    /** Send two fingers through the native Activity dispatch path for either vertical viewer. */
    private fun dispatchNativePinch(
        activity: ReaderActivity,
        viewport: View,
        zoomIn: Boolean,
        terminalSpanFraction: Float? = null,
    ) {
        val frame = viewport
        val location = IntArray(2)
        frame.getLocationInWindow(location)
        val centerX = location[0] + frame.width / 2f
        val centerY = location[1] + frame.height * 0.55f
        val minScaleSpan = ViewConfiguration.get(activity).scaledMinimumScalingSpan.toFloat()
        val fitSpan = frame.width * 0.48f
        val zoomSpan = frame.width * 0.90f
        require(terminalSpanFraction == null || !zoomIn && terminalSpanFraction in 0.50f..0.90f)
        check(fitSpan > minScaleSpan) {
            "Frame-derived pinch span $fitSpan must exceed platform minimum scaling span $minScaleSpan (frame=${frame.width})"
        }
        Log.i("ArkhiveZoomQA", "PINCH_GEOMETRY frame=${frame.width}x${frame.height},minSpan=$minScaleSpan,fitSpan=$fitSpan,zoomSpan=$zoomSpan")
        val downTime = android.os.SystemClock.uptimeMillis()
        var elapsed = 0L

        fun send(action: Int, span: Float, pointerCount: Int) {
            val properties = Array(pointerCount) { index ->
                MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER }
            }
            val coordinates = Array(pointerCount) { index ->
                MotionEvent.PointerCoords().apply {
                    x = centerX + if (index == 0) -span / 2f else span / 2f
                    y = centerY
                    pressure = 1f
                    size = 1f
                }
            }
            val event = MotionEvent.obtain(
                downTime, downTime + elapsed, action, pointerCount, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
            elapsed += 16
        }

        val startSpan = if (zoomIn) fitSpan else zoomSpan
        val spans = when {
            zoomIn -> listOf(frame.width * 0.62f, frame.width * 0.76f, zoomSpan)
            terminalSpanFraction != null -> listOf(
                frame.width * 0.82f,
                frame.width * 0.76f,
                frame.width * terminalSpanFraction,
            )
            else -> listOf(frame.width * 0.76f, frame.width * 0.62f, fitSpan)
        }
        send(MotionEvent.ACTION_DOWN, startSpan, 1)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), startSpan, 2)
        spans.forEach { send(MotionEvent.ACTION_MOVE, it, 2) }
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), spans.last(), 2)
        send(MotionEvent.ACTION_UP, spans.last(), 1)
    }

    private fun dispatchNativeDrag(activity: ReaderActivity) {
        val viewport = (activity.viewModel.state.value.viewer as WebtoonViewer).getView()
        dispatchNativeDrag(activity, viewport)
    }

    /** A fresh one-finger scroll/pan after the pinch stream has ended. */
    private fun dispatchNativeDrag(activity: ReaderActivity, viewport: View, moveUp: Boolean = true) {
        val location = IntArray(2)
        viewport.getLocationInWindow(location)
        val x = location[0] + viewport.width / 2f
        val startY = location[1] + viewport.height * (if (moveUp) 0.68f else 0.43f)
        val endY = location[1] + viewport.height * (if (moveUp) 0.43f else 0.68f)
        val downTime = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, y: Float, elapsed: Long) {
            val event = MotionEvent.obtain(downTime, downTime + elapsed, action, x, y, 0)
            try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, startY, 0)
        send(MotionEvent.ACTION_MOVE, startY + (endY - startY) * 0.4f, 100)
        send(MotionEvent.ACTION_MOVE, startY + (endY - startY) * 0.8f, 200)
        send(MotionEvent.ACTION_UP, endY, 300)
    }

    private data class InjectedTouchTarget(
        val centerX: Float,
        val centerY: Float,
        val width: Int,
        val height: Int,
        val minimumScaleSpan: Float,
    )

    private fun injectedTouchTarget(activity: ReaderActivity, view: View): InjectedTouchTarget {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return InjectedTouchTarget(
            centerX = location[0] + view.width / 2f,
            centerY = location[1] + view.height / 2f,
            width = view.width,
            height = view.height,
            minimumScaleSpan = ViewConfiguration.get(activity).scaledMinimumScalingSpan.toFloat(),
        )
    }

    /**
     * Inject through Android's input dispatcher. Calling Activity.dispatchTouchEvent directly
     * bypasses the native window/input path that a real reader gesture uses.
     */
    private fun dispatchInjectedPinch(
        target: InjectedTouchTarget,
        zoomIn: Boolean,
        terminalSpanFraction: Float? = null,
    ) {
        val width = target.width.toFloat()
        val startSpan = if (zoomIn) width * 0.48f else width * 0.90f
        require(terminalSpanFraction == null || !zoomIn && terminalSpanFraction in 0.50f..0.90f)
        val spans = when {
            zoomIn -> listOf(width * 0.62f, width * 0.76f, width * 0.90f)
            terminalSpanFraction != null -> listOf(
                width * 0.82f,
                width * 0.76f,
                width * terminalSpanFraction,
            )
            else -> listOf(width * 0.76f, width * 0.62f, width * 0.48f)
        }
        check(startSpan > target.minimumScaleSpan) {
            "Pinch span " + startSpan + " must exceed Android minimum " + target.minimumScaleSpan
        }
        val downTime = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, span: Float, pointerCount: Int) {
            val properties = Array(pointerCount) { index ->
                MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER }
            }
            val coordinates = Array(pointerCount) { index ->
                MotionEvent.PointerCoords().apply {
                    x = target.centerX + if (index == 0) -span / 2f else span / 2f
                    y = target.centerY
                    pressure = 1f
                    size = 1f
                }
            }
            val event = MotionEvent.obtain(
                downTime,
                android.os.SystemClock.uptimeMillis(),
                action,
                pointerCount,
                properties,
                coordinates,
                0,
                0,
                1f,
                1f,
                0,
                0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
            try {
                instrumentation.sendPointerSync(event)
            } finally {
                event.recycle()
            }
            android.os.SystemClock.sleep(24)
        }

        send(MotionEvent.ACTION_DOWN, startSpan, 1)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), startSpan, 2)
        spans.forEach { send(MotionEvent.ACTION_MOVE, it, 2) }
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), spans.last(), 2)
        send(MotionEvent.ACTION_UP, spans.last(), 1)
    }

    private fun dispatchInjectedDrag(target: InjectedTouchTarget, moveUp: Boolean) {
        val offset = target.height * 0.19f
        val startY = target.centerY + if (moveUp) offset else -offset
        val endY = target.centerY - if (moveUp) offset else -offset
        val downTime = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(
                downTime,
                android.os.SystemClock.uptimeMillis(),
                action,
                target.centerX,
                y,
                0,
            ).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            try {
                instrumentation.sendPointerSync(event)
            } finally {
                event.recycle()
            }
            android.os.SystemClock.sleep(40)
        }
        send(MotionEvent.ACTION_DOWN, startY)
        repeat(9) { step ->
            send(MotionEvent.ACTION_MOVE, startY + (endY - startY) * (step + 1) / 9f)
        }
        send(MotionEvent.ACTION_UP, endY)
    }

    private fun verticalPagerImage(activity: ReaderActivity): SubsamplingScaleImageView? =
        activity.findViewById<View>(android.R.id.content).all<PagerPageHolder>()
            .firstOrNull { it.page.index == 0 && it.isShown }
            ?.all<SubsamplingScaleImageView>()?.firstOrNull()

    private fun verticalPagerDiagnostic(activity: ReaderActivity): String {
        val state = activity.viewModel.state.value
        val image = verticalPagerImage(activity)
        val bounds = Rect()
        val visible = image?.getGlobalVisibleRect(bounds) == true
        val center = image?.center
        return "viewer=" + (state.viewer?.javaClass?.simpleName ?: "none") +
            ",page=" + state.currentPage +
            ",focused=" + activity.window.decorView.hasWindowFocus() +
            ",image=" + image?.width + "x" + image?.height +
            ",ready=" + image?.isReady +
            ",source=" + image?.sWidth + "x" + image?.sHeight +
            ",scale=" + image?.scale + ",min=" + image?.minScale + ",max=" + image?.maxScale +
            ",center=" + center?.x + "," + center?.y +
            ",visible=" + visible + ":" + bounds.toShortString()
    }

    private suspend fun captureVerticalPagerStage(reader: ActivityScenario<ReaderActivity>, name: String) {
        reader.onActivity { activity ->
            Log.i("ArkhiveZoomQA", "CAPTURE:" + name + " " + verticalPagerDiagnostic(activity))
        }
        delay(2_000)
    }

    private suspend fun captureZoomStage(reader: ActivityScenario<ReaderActivity>, name: String) {
        reader.onActivity { activity ->
            val viewer = activity.viewModel.state.value.viewer
            val recycler = (viewer as? WebtoonViewer)?.recycler
            val strip = recycler?.all<WebtoonStripImageView>()?.firstOrNull { it.isShown && it.height > 50_000 }
            val frame = activity.findViewById<View>(R.id.reader_container)
            val frameBounds = Rect()
            val frameVisible = frame?.getGlobalVisibleRect(frameBounds) == true
            val recyclerBounds = Rect()
            val recyclerOrigin = Point()
            val recyclerVisible = recycler?.getGlobalVisibleRect(recyclerBounds, recyclerOrigin) == true
            val stripBounds = Rect()
            val stripOrigin = Point()
            val stripVisible = strip?.getGlobalVisibleRect(stripBounds, stripOrigin) == true
            val rawLocal = Rect()
            val hasRawLocal = strip?.getLocalVisibleRect(rawLocal) == true
            Log.i(
                "ArkhiveZoomQA",
                "CAPTURE:$name viewer=${viewer?.javaClass?.simpleName ?: "none"},page=${activity.viewModel.state.value.currentPage}," +
                    "frame=${frameVisible}:${frameBounds.toShortString()}," +
                    "recycler=${recyclerVisible}:${recyclerBounds.toShortString()}/origin=${recyclerOrigin}," +
                    "size=${recycler?.width}x${recycler?.height},layoutHeight=${recycler?.layoutParams?.height}," +
                    "scale=${recycler?.scaleX},translation=${recycler?.x},${recycler?.y}," +
                    "strip=${strip?.width}x${strip?.height},visible=${stripVisible}:${stripBounds.toShortString()}/origin=${stripOrigin}," +
                    "rawLocal=${hasRawLocal}:${rawLocal.toShortString()},tiles=${strip?.autoScrollDiagnosticState()}",
            )
        }
        delay(2_000)
    }
    @Test fun pagedVerticalStartsAtWidthAndStepsInsideImageBeforeNormalPage() = runBlocking {
        fixture(ReadingMode.VERTICAL) { reader, chapter ->
            await { var okay = false; reader.onActivity { a -> okay = a.findViewById<View>(android.R.id.content).all<PagerPageHolder>().any { it.page.index == 0 && it.isImageReadyForAutoScroll && it.isReadableLongPage } }; okay }
            var before = 0f
            reader.onActivity { a ->
                assertReaderUsesFullContainerWidth(a)
                val holder = a.findViewById<View>(android.R.id.content).all<PagerPageHolder>().first { it.page.index == 0 && it.isShown }
                val image = holder.all<SubsamplingScaleImageView>().first()
                assertEquals("Tall paged image view spans the full pager viewport",(a.viewModel.state.value.viewer as PagerViewer).pager.width,image.width)
                assertEquals(image.width.toFloat() / image.sWidth, image.scale, .05f)
                before = image.center!!.y
                (a.viewModel.state.value.viewer as PagerViewer).moveToNext()
                assertEquals(1, a.viewModel.state.value.currentPage)
            }
            await { var moved = false; reader.onActivity { a -> moved = a.findViewById<View>(android.R.id.content).all<PagerPageHolder>().first { it.page.index == 0 && it.isShown }.all<SubsamplingScaleImageView>().first().center!!.y > before + 100 }; moved }
            delay(1500)
            screenshot("Arkhive-tall-paged-width")
            reader.onActivity { a -> val image = a.findViewById<View>(android.R.id.content).all<PagerPageHolder>().first { it.page.index == 0 && it.isShown }.all<SubsamplingScaleImageView>().first(); image.setScaleAndCenter(image.width.toFloat()/image.sWidth, PointF(image.sWidth/2f, image.sHeight.toFloat())) }
            delay(300)
            reader.onActivity { a -> (a.viewModel.state.value.viewer as PagerViewer).moveToNext() }
            await { var normal = false; reader.onActivity { a -> normal = a.viewModel.state.value.currentPage == 2 && a.findViewById<View>(android.R.id.content).all<PagerPageHolder>().any { it.page.index == 1 && it.isShown && it.isImageReadyForAutoScroll && !it.isReadableLongPage } }; normal }
        }
    }
    @Test fun continuousLongImageUsesWidthAndBoundedRegionsWhileScrolling() = runBlocking {
        fixture(ReadingMode.WEBTOON, sourceId=OriginalDemoSource.WEBTOON_ID, cropWebtoon=true) { reader, _ ->
            await { var ready = false; reader.onActivity { a -> ready = a.findViewById<View>(android.R.id.content).all<WebtoonStripImageView>().any { it.isShown && it.hasDecodedImage && it.height > 50000 } }; ready }
            reader.onActivity { a ->
                val viewer = a.viewModel.state.value.viewer as WebtoonViewer
                assertReaderUsesFullContainerWidth(a)
                val tile = viewer.getView().all<WebtoonStripImageView>().first { it.isShown && it.height > 50000 }
                assertEquals(viewer.recycler.width, tile.width)
                assertTrue("The live webtoon renderer keeps the selected crop preference",viewer.config.imageCropBorders)
                assertTrue(tile.cachedTileCount < 12); assertTrue(tile.cachedBitmapBytes <= WebtoonStripImageView.MAX_CACHE_BYTES)
                viewer.recycler.scrollBy(0, 5200)
            }
            await { var ready = false; reader.onActivity { a -> ready = a.findViewById<View>(android.R.id.content).all<WebtoonStripImageView>().any { it.isShown && it.hasDecodedImage && it.height > 50000 } }; ready }
            delay(350)
            screenshot("Arkhive-tall-continuous-regions", checkCoverage=true)
            reader.onActivity { a -> val tile = a.findViewById<View>(android.R.id.content).all<WebtoonStripImageView>().first { it.isShown && it.height > 50000 }; assertTrue(tile.cachedBitmapBytes <= WebtoonStripImageView.MAX_CACHE_BYTES); assertTrue(tile.cachedTileCount < 16) }
        }
    }

    @Test fun continuousTallImageSurvivesNativePinchReversalThenScroll() = runBlocking {
        fixture(
            mode = ReadingMode.WEBTOON,
            sourceId = OriginalDemoSource.WEBTOON_ID,
        disableZoomOut = true,
            cropWebtoon = true,
            wideFixture = true,
        ) { reader, chapter ->
            var expectedPage = 0
            var lastDiagnostic = "not sampled"
            var fitBeforePinchVisibleTop = 0

            fun diagnostic(activity: ReaderActivity): String {
                val state = activity.viewModel.state.value
                val viewer = state.viewer as? WebtoonViewer
                val strip = viewer?.recycler?.all<WebtoonStripImageView>()
                    ?.firstOrNull { it.isShown && it.height > 50_000 }
                val visible = Rect()
                val hasVisible = strip?.getLocalVisibleRect(visible) == true
                return "chapter=${state.currentChapter?.chapter?.id},page=${state.currentPage}," +
                    "scale=${viewer?.recycler?.scaleX},offset=${viewer?.recycler?.computeVerticalScrollOffset()}," +
                    "image=${strip?.width}x${strip?.height},clip=$hasVisible:${visible.toShortString()}," +
                    "decode=${strip?.autoScrollDiagnosticState()}"
            }

            suspend fun awaitStage(stage: String, condition: () -> Boolean) {
                try {
                    await(stage, condition = condition)
                } catch (failure: AssertionError) {
                    reader.onActivity { activity ->
                        lastDiagnostic = diagnostic(activity)
                        Log.e("ArkhiveZoomQA", "FAIL:" + stage.replace(" ", "-") + " $lastDiagnostic", failure)
                    }
                    // Leave the live reader up briefly so the host can capture the failed frame.
                    delay(2_000)
                    throw failure
                }
            }

            suspend fun assertStage(stage: String, passed: Boolean, message: String) {
                if (!passed) {
                    reader.onActivity { activity ->
                        lastDiagnostic = diagnostic(activity)
                        Log.e("ArkhiveZoomQA", "FAIL:" + stage.replace(" ", "-") + " $lastDiagnostic :: " + message)
                    }
                    // Leave the live failure frame available for a host-side adb capture.
                    delay(2_000)
                    throw AssertionError("$message; $lastDiagnostic")
                }
            }

            suspend fun awaitDecoded(stage: String) {
                awaitStage(stage) {
                    var ready = false
                    reader.onActivity { activity ->
                        val state = activity.viewModel.state.value
                        val viewer = state.viewer as? WebtoonViewer
                        if (expectedPage == 0 && state.currentChapter?.chapter?.id == chapter && state.currentPage > 0) {
                            expectedPage = state.currentPage
                        }
                        ready = viewer != null &&
                            state.currentChapter?.chapter?.id == chapter &&
                            expectedPage > 0 && state.currentPage == expectedPage &&
                            viewer.recycler.all<WebtoonStripImageView>().any {
                                it.isShown && it.height > 50_000 && it.hasDecodedImage
                            }
                        lastDiagnostic = diagnostic(activity)
                    }
                    ready
                }
            }

            suspend fun captureStage(stage: String) {
                reader.onActivity { activity ->
                    val viewer = activity.viewModel.state.value.viewer as WebtoonViewer
                    val strip = viewer.recycler.all<WebtoonStripImageView>()
                        .first { it.isShown && it.height > 50_000 }
                    val localVisible = Rect()
                    val hasLocalVisible = strip.getLocalVisibleRect(localVisible)
                    lastDiagnostic = diagnostic(activity)
                    Log.i(
                        "ArkhiveZoomQA",
                        "CAPTURE:" + stage + " " + lastDiagnostic +
                            " clip=" + hasLocalVisible + ":" + localVisible.toShortString(),
                    )
                }
                // The host QA runner captures the actual device frame from adb during this window.
                delay(2_000)
            }

            awaitDecoded("initial tall strip is visible and decoded")
            reader.onActivity { activity ->
                activity.hideMenu()
                val viewer = activity.viewModel.state.value.viewer as WebtoonViewer
                val strip = viewer.recycler.all<WebtoonStripImageView>().first { it.isShown && it.height > 50_000 }
                assertTrue("Reader reports a 1-based page after loading the chapter", expectedPage > 0)
                assertEquals("Initial fit uses the full available width", viewer.recycler.width, strip.width)
                assertEquals(1f, viewer.recycler.scaleX, 0.001f)
                val visible = Rect()
                assertTrue("Initial fit has a visible strip viewport", strip.getLocalVisibleRect(visible))
                fitBeforePinchVisibleTop = visible.top
                lastDiagnostic = diagnostic(activity)
            }
            captureStage("fit-before")

            // A real pinch goes through Activity.dispatchTouchEvent and the production scale detector.
            reader.onActivity { activity -> dispatchNativePinch(activity, zoomIn = true) }
            var zoomedScale = 1f
            reader.onActivity { activity ->
                zoomedScale = (activity.viewModel.state.value.viewer as WebtoonViewer).recycler.scaleX
            }
            reader.onActivity { activity -> lastDiagnostic = diagnostic(activity) }
            assertStage(
                "pinch-in-recognition",
                zoomedScale > 1.2f,
                "Two-pointer pinch-in should reach the live Webtoon scale detector; scale=$zoomedScale",
            )
            delay(80)
            reader.onActivity { activity -> dispatchNativePinch(activity, zoomIn = false) }
            awaitStage("rapid reversal returns to fit width") {
                var scale = 0f
                reader.onActivity { activity ->
                    scale = (activity.viewModel.state.value.viewer as WebtoonViewer).recycler.scaleX
                    lastDiagnostic = diagnostic(activity)
                }
                kotlin.math.abs(scale - 1f) < 0.03f
            }
            awaitDecoded("rapid reversal has decoded the visible tall strip at fit width")
            captureStage("rapid-reversal-fit")

            var fitAfterPinchVisibleTop = 0
            reader.onActivity { activity ->
                val strip = (activity.viewModel.state.value.viewer as WebtoonViewer).recycler
                    .all<WebtoonStripImageView>().first { it.isShown && it.height > 50_000 }
                val visible = Rect()
                assertTrue("The strip remains visible after the centered pinch roundtrip", strip.getLocalVisibleRect(visible))
                fitAfterPinchVisibleTop = visible.top
            }
            assertStage(
                "centered pinch roundtrip preserves the image anchor",
                kotlin.math.abs(fitAfterPinchVisibleTop - fitBeforePinchVisibleTop) <= 2,
                "The unchanged centered pinch should not pan the image: before=$fitBeforePinchVisibleTop after=$fitAfterPinchVisibleTop",
            )

            var beforeZoomPanTop = 0
            reader.onActivity { activity -> dispatchNativePinch(activity, zoomIn = true) }
            awaitStage("second pinch-in reaches a magnified state") {
                var scale = 0f
                reader.onActivity { activity ->
                    scale = (activity.viewModel.state.value.viewer as WebtoonViewer).recycler.scaleX
                    lastDiagnostic = diagnostic(activity)
                }
                scale > 1.2f
            }
            reader.onActivity { activity ->
                val strip = (activity.viewModel.state.value.viewer as WebtoonViewer).recycler
                    .all<WebtoonStripImageView>().first { it.isShown && it.height > 50_000 }
                val visible = Rect()
                assertTrue(strip.getLocalVisibleRect(visible))
                beforeZoomPanTop = visible.top
                dispatchNativeDrag(activity)
            }
            awaitStage("pan while magnified advances the strip viewport") {
                var moved = false
                reader.onActivity { activity ->
                    val viewer = activity.viewModel.state.value.viewer as? WebtoonViewer
                    val strip = viewer?.recycler?.all<WebtoonStripImageView>()
                        ?.firstOrNull { it.isShown && it.height > 50_000 }
                    val visible = Rect()
                    val hasVisible = strip?.getLocalVisibleRect(visible) == true
                    moved = viewer != null && viewer.recycler.scaleX > 1.2f &&
                        hasVisible && visible.top > beforeZoomPanTop + 40
                    lastDiagnostic = diagnostic(activity)
                }
                moved
            }
            awaitDecoded("magnified pan has decoded the newly visible strip tiles")
            captureStage("zoomed-pan")

            var beforeVisibleTop = 0
            reader.onActivity { activity ->
                val viewer = activity.viewModel.state.value.viewer as WebtoonViewer
                val strip = viewer.recycler.all<WebtoonStripImageView>()
                    .first { it.isShown && it.height > 50_000 }
                val visible = Rect()
                assertTrue("The tall strip has a visible viewport before drag", strip.getLocalVisibleRect(visible))
                beforeVisibleTop = visible.top
                lastDiagnostic = diagnostic(activity)
                dispatchNativeDrag(activity)
            }
            awaitStage("real scroll after rapid reversal advances the long image") {
                var moved = false
                reader.onActivity { activity ->
                    val state = activity.viewModel.state.value
                    val viewer = state.viewer as? WebtoonViewer
                    val strip = viewer?.recycler?.all<WebtoonStripImageView>()
                        ?.firstOrNull { it.isShown && it.height > 50_000 }
                    val visible = Rect()
                    val hasVisible = strip?.getLocalVisibleRect(visible) == true
                    moved = viewer != null &&
                        state.currentChapter?.chapter?.id == chapter &&
                        state.currentPage == expectedPage &&
                        hasVisible && visible.top > beforeVisibleTop + 100
                    lastDiagnostic = diagnostic(activity)
                }
                moved
            }
            awaitDecoded("scrolled long-image viewport is decoded")
            captureStage("scroll-after-rapid-reversal")

            // The prior single pinch-in must be reversed once to return to fit width.
            reader.onActivity { activity -> dispatchNativePinch(activity, zoomIn = false) }
            awaitStage("return-to-normal gesture restores width fit") {
                var scale = 0f
                reader.onActivity { activity ->
                    scale = (activity.viewModel.state.value.viewer as WebtoonViewer).recycler.scaleX
                    lastDiagnostic = diagnostic(activity)
                }
                kotlin.math.abs(scale - 1f) < 0.03f
            }
            awaitDecoded("returned fit-width viewport has decoded visible regions")
            reader.onActivity { activity ->
                val viewer = activity.viewModel.state.value.viewer as WebtoonViewer
                val strip = viewer.recycler.all<WebtoonStripImageView>().first { it.isShown && it.height > 50_000 }
                assertEquals("Return to normal restores the image's fit-width geometry", viewer.recycler.width, strip.width)
                assertEquals(chapter, activity.viewModel.state.value.currentChapter?.chapter?.id)
                assertEquals(expectedPage, activity.viewModel.state.value.currentPage)
                lastDiagnostic = diagnostic(activity)
            }
            captureStage("return-to-fit")

        }
    }

    @Test fun webtoonLongStripModesSurviveZoomedAndBelowFitScroll() = runBlocking {
        listOf(ReadingMode.WEBTOON, ReadingMode.CONTINUOUS_VERTICAL).forEach { mode ->
            verifyLongStripModeSurvivesZoomedAndBelowFitScroll(mode)
        }
    }

    private suspend fun verifyLongStripModeSurvivesZoomedAndBelowFitScroll(mode: ReadingMode) {
        fixture(
            mode = mode,
            sourceId = OriginalDemoSource.WEBTOON_ID,
            disableZoomOut = false,
            wideFixture = true,
        ) { reader, chapter ->
            val modeName = mode.name.lowercase().replace('_', '-')
            var expectedPage = 0
            fun sample(): Triple<WebtoonViewer?, WebtoonStripImageView?, Rect> {
                var result = Triple<WebtoonViewer?, WebtoonStripImageView?, Rect>(null, null, Rect())
                reader.onActivity { activity ->
                    val viewer = activity.viewModel.state.value.viewer as? WebtoonViewer
                    val strip = viewer?.recycler?.all<WebtoonStripImageView>()?.firstOrNull { it.isShown && it.height > 50_000 }
                    val visible = Rect()
                    if (strip?.getLocalVisibleRect(visible) != true) visible.setEmpty()
                    result = Triple(viewer, strip, visible)
                }
                return result
            }
            fun chapterMatches(): Boolean {
                var matches = false
                reader.onActivity { activity ->
                    val state = activity.viewModel.state.value
                    matches = expectedPage > 0 && state.currentChapter?.chapter?.id == chapter &&
                        state.currentPage == expectedPage
                }
                return matches
            }

            await("$modeName wide long strip is decoded") {
                val (viewer, strip, _) = sample()
                strip?.hasDecodedImage == true &&
                    viewer != null && strip.width == viewer.recycler.width &&
                    strip.height > 50_000 &&
                    strip.autoScrollDiagnosticState().contains("image=1400x80000")
            }
            await("$modeName first logical page is ready") {
                var ready = false
                reader.onActivity { activity ->
                    val state = activity.viewModel.state.value
                    if (expectedPage == 0 && state.currentChapter?.chapter?.id == chapter && state.currentPage > 0) {
                        expectedPage = state.currentPage
                    }
                    ready = expectedPage > 0 && state.currentPage == expectedPage
                }
                ready
            }
            reader.onActivity { activity -> activity.hideMenu() }
            captureZoomStage(reader, "$modeName-fit")

            reader.onActivity { activity -> dispatchNativePinch(activity, zoomIn = true) }
            await("$modeName strip zooms in") {
                val (viewer) = sample()
                (viewer?.recycler?.scaleX ?: 0f) > 1.2f
            }
            val beforeZoomPan = sample().third.top
            reader.onActivity { activity -> dispatchNativeDrag(activity) }
            await("$modeName pan advances the magnified image") {
                val (viewer, strip, visible) = sample()
                viewer != null && viewer.recycler.scaleX > 1.2f && strip?.hasDecodedImage == true && visible.top > beforeZoomPan + 40
            }
            captureZoomStage(reader, "$modeName-zoomed-pan")

            val scaleBeforePartialZoomOut = sample().first?.recycler?.scaleX ?: error("$modeName viewer disappeared")
            reader.onActivity { activity ->
                dispatchNativePinch(activity, zoomIn = false, terminalSpanFraction = 0.72f)
            }
            await("$modeName partial zoom-out remains enlarged") {
                val scale = sample().first?.recycler?.scaleX ?: 0f
                scale > 1.2f && scale < scaleBeforePartialZoomOut - 0.05f
            }
            captureZoomStage(reader, "$modeName-partial-zoom-out")

            val beforePartialOutForward = sample().third.top
            reader.onActivity { activity -> dispatchNativeDrag(activity) }
            await("$modeName scroll after partial zoom-out advances while still enlarged") {
                val (viewer, strip, visible) = sample()
                viewer != null && viewer.recycler.scaleX > 1.2f &&
                    strip?.hasDecodedImage == true && visible.top > beforePartialOutForward + 40 && chapterMatches()
            }
            captureZoomStage(reader, "$modeName-partial-zoom-out-forward-scroll")

            val beforePartialOutReverse = sample().third.top
            reader.onActivity { activity ->
                val viewport = (activity.viewModel.state.value.viewer as WebtoonViewer).getView()
                dispatchNativeDrag(activity, viewport, moveUp = false)
            }
            await("$modeName reverse scroll after partial zoom-out stays enlarged") {
                val (viewer, strip, visible) = sample()
                viewer != null && viewer.recycler.scaleX > 1.2f &&
                    strip?.hasDecodedImage == true && visible.top < beforePartialOutReverse - 40 && chapterMatches()
            }
            captureZoomStage(reader, "$modeName-partial-zoom-out-reverse-scroll")

            reader.onActivity { activity -> dispatchNativePinch(activity, zoomIn = false) }
            await("$modeName reverse pinch reaches fit or below") {
                val scale = sample().first?.recycler?.scaleX ?: 0f
                scale in 0.5f..1.03f
            }
            reader.onActivity { activity -> dispatchNativePinch(activity, zoomIn = false) }
            await("$modeName permits zoom below fit") {
                val (viewer) = sample()
                val scale = viewer?.recycler?.scaleX ?: 1f
                scale in 0.5f..0.97f
            }
            await("$modeName below-fit layout settles") {
                var settled = false
                reader.onActivity { activity ->
                    val viewer = activity.viewModel.state.value.viewer as? WebtoonViewer
                    settled = viewer != null && viewer.recycler.layoutParams.height > viewer.getView().height
                }
                settled
            }
            var beforeBelowFit = 0
            reader.onActivity { activity ->
                beforeBelowFit = (activity.viewModel.state.value.viewer as WebtoonViewer).recycler.computeVerticalScrollOffset()
                dispatchNativeDrag(activity)
            }
            await("$modeName below-fit scroll still moves and decodes") {
                var moved = false
                reader.onActivity { activity ->
                    val state = activity.viewModel.state.value
                    val viewer = state.viewer as? WebtoonViewer
                    val strip = viewer?.recycler?.all<WebtoonStripImageView>()?.firstOrNull { it.isShown && it.height > 50_000 }
                    moved = state.currentChapter?.chapter?.id == chapter && viewer != null &&
                        viewer.recycler.scaleX < 0.98f &&
                        kotlin.math.abs(viewer.recycler.computeVerticalScrollOffset() - beforeBelowFit) > 80 &&
                        strip?.hasDecodedImage == true
                }
                moved
            }
            captureZoomStage(reader, "$modeName-below-fit-scroll")
        }
    }

    @Test fun verticalPagerLongImageSurvivesZoomedPanAndReturnToFit() = runBlocking {
        fixture(
            mode = ReadingMode.VERTICAL,
            disableZoomOut = false,
            cropPaged = true,
            wideFixture = true,
        ) { reader, chapter ->
            fun currentImageTarget(): InjectedTouchTarget {
                var target: InjectedTouchTarget? = null
                reader.onActivity { activity ->
                    val image = verticalPagerImage(activity)
                        ?: throw AssertionError("Visible vertical pager image is missing: " + verticalPagerDiagnostic(activity))
                    target = injectedTouchTarget(activity, image)
                    Log.i("ArkhiveZoomQA", "TOUCH_TARGET " + verticalPagerDiagnostic(activity))
                }
                return checkNotNull(target)
            }

            suspend fun awaitVerticalStage(stage: String, condition: (ReaderActivity) -> Boolean) {
                try {
                    await(stage) {
                        var matched = false
                        reader.onActivity { activity -> matched = condition(activity) }
                        matched
                    }
                } catch (error: AssertionError) {
                    reader.onActivity { activity ->
                        Log.e("ArkhiveZoomQA", "FAIL:vertical-pager-" + stage.replace(' ', '-') + " " + verticalPagerDiagnostic(activity))
                    }
                    delay(2_000)
                    throw error
                }
            }

            await("vertical pager wide long image is ready") {
                var ready = false
                reader.onActivity { activity ->
                    val firstHolder = activity.findViewById<View>(android.R.id.content).all<PagerPageHolder>()
                        .firstOrNull { it.page.index == 0 && it.isShown }
                    val image = firstHolder?.all<SubsamplingScaleImageView>()?.firstOrNull()
                    ready = image != null && image.isReady && image.sWidth == 1400 && image.sHeight > 70_000 &&
                        firstHolder?.page?.index == 0 && activity.viewModel.state.value.currentPage == 1
                }
                ready
            }
            reader.onActivity { activity -> activity.hideMenu() }
            captureVerticalPagerStage(reader, "vertical-pager-fit")

            dispatchInjectedPinch(currentImageTarget(), zoomIn = true)
            awaitVerticalStage("vertical pager long image zooms beyond its fit scale") { activity ->
                val image = verticalPagerImage(activity)
                image != null && image.isReady && image.scale > image.minScale * 1.2f &&
                    activity.viewModel.state.value.currentPage == 1
            }
            captureVerticalPagerStage(reader, "vertical-pager-zoomed")

            var lastForwardCenter = 0f
            reader.onActivity { activity ->
                lastForwardCenter = verticalPagerImage(activity)?.center?.y ?: 0f
            }
            repeat(3) { index ->
                dispatchInjectedDrag(currentImageTarget(), moveUp = true)
                awaitVerticalStage("vertical pager forward drag " + index) { activity ->
                    val image = verticalPagerImage(activity)
                    image != null && image.isReady && image.scale > image.minScale * 1.2f &&
                        (image.center?.y ?: lastForwardCenter) > lastForwardCenter + 20f &&
                        activity.viewModel.state.value.currentChapter?.chapter?.id == chapter &&
                        activity.viewModel.state.value.currentPage == 1
                }
                reader.onActivity { activity ->
                    lastForwardCenter = verticalPagerImage(activity)?.center?.y ?: lastForwardCenter
                }
                delay(350)
            }
            captureVerticalPagerStage(reader, "vertical-pager-zoomed-forward-pan")

            var beforeReverse = lastForwardCenter
            reader.onActivity { activity ->
                beforeReverse = verticalPagerImage(activity)?.center?.y ?: beforeReverse
            }
            dispatchInjectedDrag(currentImageTarget(), moveUp = false)
            awaitVerticalStage("vertical pager reverse drag") { activity ->
                val image = verticalPagerImage(activity)
                image != null && image.isReady && image.scale > image.minScale * 1.2f &&
                    (image.center?.y ?: beforeReverse) < beforeReverse - 20f &&
                    activity.viewModel.state.value.currentPage == 1
            }
            captureVerticalPagerStage(reader, "vertical-pager-zoomed-reverse-pan")

            var scaleBeforePartialOut = 0f
            reader.onActivity { activity ->
                scaleBeforePartialOut = verticalPagerImage(activity)?.scale ?: 0f
            }
            dispatchInjectedPinch(currentImageTarget(), zoomIn = false, terminalSpanFraction = 0.72f)
            awaitVerticalStage("vertical pager partial zoom-out remains above fit") { activity ->
                val image = verticalPagerImage(activity)
                image != null && image.isReady && image.scale > image.minScale * 1.2f &&
                    image.scale < scaleBeforePartialOut - 0.02f && activity.viewModel.state.value.currentPage == 1
            }
            captureVerticalPagerStage(reader, "vertical-pager-partial-zoom-out")

            var partialOutForwardCenter = 0f
            reader.onActivity { activity ->
                partialOutForwardCenter = verticalPagerImage(activity)?.center?.y ?: 0f
            }
            dispatchInjectedDrag(currentImageTarget(), moveUp = true)
            awaitVerticalStage("vertical pager scrolls forward after partial zoom-out") { activity ->
                val image = verticalPagerImage(activity)
                image != null && image.isReady && image.scale > image.minScale * 1.2f &&
                    (image.center?.y ?: partialOutForwardCenter) > partialOutForwardCenter + 20f &&
                    activity.viewModel.state.value.currentChapter?.chapter?.id == chapter &&
                    activity.viewModel.state.value.currentPage == 1
            }
            captureVerticalPagerStage(reader, "vertical-pager-partial-zoom-out-forward-scroll")

            var partialOutReverseCenter = 0f
            reader.onActivity { activity ->
                partialOutReverseCenter = verticalPagerImage(activity)?.center?.y ?: 0f
            }
            dispatchInjectedDrag(currentImageTarget(), moveUp = false)
            awaitVerticalStage("vertical pager reverses scroll while still zoomed after zoom-out") { activity ->
                val image = verticalPagerImage(activity)
                image != null && image.isReady && image.scale > image.minScale * 1.2f &&
                    (image.center?.y ?: partialOutReverseCenter) < partialOutReverseCenter - 20f &&
                    activity.viewModel.state.value.currentChapter?.chapter?.id == chapter &&
                    activity.viewModel.state.value.currentPage == 1
            }
            captureVerticalPagerStage(reader, "vertical-pager-partial-zoom-out-reverse-scroll")

            dispatchInjectedPinch(currentImageTarget(), zoomIn = false)
            awaitVerticalStage("vertical pager returns to its enforced fit-width minimum") { activity ->
                val image = verticalPagerImage(activity)
                image != null && image.isReady && image.scale <= image.minScale * 1.1f &&
                    activity.viewModel.state.value.currentPage == 1
            }
            captureVerticalPagerStage(reader, "vertical-pager-return-fit")

            var fitCenter = 0f
            reader.onActivity { activity -> fitCenter = verticalPagerImage(activity)?.center?.y ?: 0f }
            dispatchInjectedDrag(currentImageTarget(), moveUp = true)
            awaitVerticalStage("vertical pager still scrolls at fit width") { activity ->
                val image = verticalPagerImage(activity)
                image != null && image.isReady && image.scale <= image.minScale * 1.1f &&
                    (image.center?.y ?: fitCenter) > fitCenter + 20f &&
                    activity.viewModel.state.value.currentChapter?.chapter?.id == chapter &&
                    activity.viewModel.state.value.currentPage == 1
            }
            captureVerticalPagerStage(reader, "vertical-pager-fit-scroll")
        }
    }

    @Test fun visibleFullWidthActionRemovesInsetsAndSurvivesReaderRecreation() = runBlocking {
        fixture(
            mode=ReadingMode.WEBTOON,
            sourceId=OriginalDemoSource.WEBTOON_ID,
            sidePadding=12,
            disableZoomOut=false,
            cropWebtoon=true,
        ) { reader, chapter ->
            val device=UiDevice.getInstance(instrumentation)
            val toolsBefore=graph.libraryToolsStore.state.value
            var profileKey:String?=null
            var oldProfile:ReadingProfile?=null
            try {
            var initialTileDiagnostic="not observed"
            try { await("initial webtoon tile decoded") {
                var ready=false
                reader.onActivity { activity ->
                    val viewer=activity.viewModel.state.value.viewer as? WebtoonViewer
                    val tiles=viewer?.recycler?.all<WebtoonStripImageView>().orEmpty()
                    val tile=tiles.firstOrNull { it.isShown && it.height>50000 }
                    initialTileDiagnostic="chapter=${activity.viewModel.state.value.currentChapter?.chapter?.id},"+
                        "page=${activity.viewModel.state.value.currentPage},viewer=${viewer?.recycler?.width}x${viewer?.recycler?.height},"+
                        "tiles=${tiles.size},visible=${tile?.isShown},decoded=${tile?.hasDecodedImage},tile=${tile?.width}x${tile?.height},"+
                        "offset=${viewer?.recycler?.computeVerticalScrollOffset()},crop=${viewer?.config?.imageCropBorders}"
                    ready=tiles.any { child ->
                            child.isShown && child.hasDecodedImage && child.height>50000
                    }
                }
                ready
            } } catch(failure:AssertionError) {
                throw AssertionError("${failure.message}; $initialTileDiagnostic",failure)
            }
            reader.onActivity { activity ->
                assertReaderUsesFullContainerWidth(activity)
                val viewer=activity.viewModel.state.value.viewer as WebtoonViewer
                assertTrue("Fixture starts with measurable internal side padding",viewer.recycler.sidePaddingPixels>0)
                assertTrue("Crop remains a distinct user choice",viewer.config.imageCropBorders)
                val tile=viewer.getView().all<WebtoonStripImageView>().first { it.isShown && it.height>50000 }
                assertTrue("Configured internal padding narrows the image before choosing full width",tile.width<viewer.recycler.width)
                // Simulate a user zoom-in before opening the reader controls. The width action
                // must not reset that zoom when it only raises the minimum fit scale.
                viewer.recycler.onScale(1.5f)
                activity.showMenu()
            }
            openReaderSettings(device,reader)
            revealFullWidthAction(device)
            requireNotNull(device.wait(Until.findObject(By.text("Use full available width")),10_000)).click()
            await("selected full-width setting reaches the active Webtoon viewer") {
                var applied=false
                reader.onActivity { activity ->
                    val viewer=activity.viewModel.state.value.viewer as? WebtoonViewer
                    val tile=viewer?.getView()?.all<WebtoonStripImageView>()?.firstOrNull { it.isShown && it.height>50000 }
                    applied=viewer!=null && viewer.recycler.sidePaddingPixels==0 &&
                        activity.appGraph.readerPreferences.webtoonSidePadding.get()==0 &&
                        activity.appGraph.readerPreferences.webtoonDisableZoomOut.get() &&
                        viewer.config.imageCropBorders && tile?.width==viewer.recycler.width &&
                        kotlin.math.abs((viewer.recycler.scaleX)-1.5f)<0.05f
                }
                applied
            }
            device.pressBack()
            assertTrue(device.wait(Until.gone(By.text("Use full available width")),5_000))
            reader.onActivity { activity ->
                activity.hideMenu()
                assertReaderUsesFullContainerWidth(activity)
                val viewer=activity.viewModel.state.value.viewer as WebtoonViewer
                val tile=viewer.getView().all<WebtoonStripImageView>().first { it.isShown && it.height>50000 }
                assertEquals("Full-width image uses the whole live RecyclerView width",viewer.recycler.width,tile.width)
                assertTrue("Full width does not clear the user's crop choice",viewer.config.imageCropBorders)
                assertEquals("Opening the reader settings must preserve the user's zoom",1.5f,viewer.recycler.scaleX,0.05f)
                assertEquals(chapter,activity.viewModel.state.value.currentChapter?.chapter?.id)
                val mangaId=requireNotNull(activity.viewModel.manga?.id)
                profileKey="manga:$mangaId"
                oldProfile=toolsBefore.profiles[profileKey!!]
                val profile=oldProfile ?: activity.appGraph.libraryToolsStore.profile(profileKey!!)
                activity.appGraph.libraryToolsStore.setProfile(profileKey!!,profile.copy(room=ReadingRoom.WARM_PAPER))
            }

            // A fresh Activity must read the persisted width-fit choice and restore actual image geometry.
            reader.recreate()
            await("recreated Webtoon viewer restores the selected full-width geometry") {
                var restored=false
                reader.onActivity { activity ->
                    val viewer=activity.viewModel.state.value.viewer as? WebtoonViewer
                    val tile=viewer?.getView()?.all<WebtoonStripImageView>()?.firstOrNull { it.isShown && it.hasDecodedImage && it.height>50000 }
                    restored=viewer!=null && activity.viewModel.state.value.currentChapter?.chapter?.id==chapter &&
                        activity.appGraph.readerPreferences.webtoonSidePadding.get()==0 &&
                        activity.appGraph.readerPreferences.webtoonDisableZoomOut.get() &&
                        viewer.recycler.width==activity.findViewById<View>(R.id.reader_container).width &&
                        viewer.config.imageCropBorders && tile?.width==viewer.recycler.width
                }
                restored
            }
            reader.onActivity { activity ->
                assertReaderUsesFullContainerWidth(activity)
                val viewer=activity.viewModel.state.value.viewer as WebtoonViewer
                assertEquals("Reader profile application does not alter persisted full width",0,viewer.recycler.sidePaddingPixels)
                assertTrue(viewer.config.imageCropBorders)
                viewer.recycler.onScale(1.5f)
                activity.showMenu()
            }
            openReaderSettings(device,reader)
            revealFullWidthAction(device)
            requireNotNull(device.wait(Until.findObject(By.text("Use full available width")),10_000))
            device.pressBack()
            reader.onActivity { activity ->
                val viewer=activity.viewModel.state.value.viewer as WebtoonViewer
                activity.hideMenu()
                assertReaderUsesFullContainerWidth(activity)
                assertEquals("Opening controls after applying the series profile must preserve zoom",1.5f,viewer.recycler.scaleX,0.05f)
                assertTrue("Applying the series profile preserves the selected crop state",viewer.config.imageCropBorders)
            }
            } finally {
                profileKey?.let { key -> graph.libraryToolsStore.update { state ->
                    if(oldProfile==null) state.copy(profiles=state.profiles-key)
                    else state.copy(profiles=state.profiles+(key to oldProfile!!))
                } }
            }
        }
    }

    @Test fun previouslySavedFullWidthPreferencesRemainEdgeToEdgeAfterUpgradeAndRecreation() = runBlocking {
        // In 0.3.2 these persisted settings were the user's full-width choice. The old
        // unconditional 20dp Activity margin kept the page inset despite those settings.
        fixture(
            mode=ReadingMode.WEBTOON,
            sourceId=OriginalDemoSource.WEBTOON_ID,
            sidePadding=0,
            disableZoomOut=true,
            cropWebtoon=true,
        ) { reader, chapter ->
            await("legacy full-width webtoon image decoded") {
                var ready=false
                reader.onActivity { activity ->
                    val viewer=activity.viewModel.state.value.viewer as? WebtoonViewer
                    ready=viewer?.getView()?.all<WebtoonStripImageView>()?.any { it.isShown && it.hasDecodedImage && it.height>50000 }==true
                }
                ready
            }
            reader.onActivity { activity ->
                val viewer=activity.viewModel.state.value.viewer as WebtoonViewer
                val prefs=activity.appGraph.readerPreferences
                assertTrue("The historical full-width preference is present",prefs.webtoonSidePadding.isSet())
                assertTrue("The historical no-zoom-out preference is present",prefs.webtoonDisableZoomOut.isSet())
                assertEquals(0,prefs.webtoonSidePadding.get())
                assertTrue(prefs.webtoonDisableZoomOut.get())
                assertReaderUsesFullContainerWidth(activity)
                val tile=viewer.getView().all<WebtoonStripImageView>().first { it.isShown && it.height>50000 }
                assertEquals("Previously saved full-width preference fills the native viewport",viewer.recycler.width,tile.width)
                assertTrue("Saved image-crop choice remains independent",viewer.config.imageCropBorders)
                assertEquals(chapter,activity.viewModel.state.value.currentChapter?.chapter?.id)
                viewer.recycler.scrollBy(0,1800)
            }
            await("legacy preference fixture advances within the same chapter") {
                var advanced=false
                reader.onActivity { activity ->
                    val viewer=activity.viewModel.state.value.viewer as? WebtoonViewer
                    advanced=viewer!=null && viewer.recycler.computeVerticalScrollOffset()>0 &&
                        activity.viewModel.state.value.currentChapter?.chapter?.id==chapter
                }
                advanced
            }
            var savedOffset=0
            var savedPage=0
            reader.onActivity { activity ->
                val state=activity.viewModel.state.value
                savedOffset=(state.viewer as WebtoonViewer).recycler.computeVerticalScrollOffset()
                savedPage=state.currentPage
            }
            reader.recreate()
            // The existing WebtoonViewer restores the chapter/logical page through moveToPage,
            // which starts a tall page at its top; intra-page pixel offset is not persisted across
            // destroying the Activity. The fit preference is persisted independently.
            var lastRestoreDiagnostic="not observed"
            try { await("recreated reader restores historical full-width preferences and logical page") {
                var restored=false
                reader.onActivity { activity ->
                    val viewer=activity.viewModel.state.value.viewer as? WebtoonViewer
                    val tile=viewer?.getView()?.all<WebtoonStripImageView>()?.firstOrNull { it.isShown && it.hasDecodedImage && it.height>50000 }
                    lastRestoreDiagnostic="chapter=${activity.viewModel.state.value.currentChapter?.chapter?.id},page=${activity.viewModel.state.value.currentPage},"+
                        "viewer=${viewer?.recycler?.width}x${viewer?.recycler?.height},reader=${activity.findViewById<View>(R.id.reader_container).width},"+
                        "sidePadding=${viewer?.recycler?.sidePaddingPixels},tile=${tile?.width}x${tile?.height},"+
                        "offset=${viewer?.recycler?.computeVerticalScrollOffset()},savedOffset=$savedOffset,"+
                        "crop=${viewer?.config?.imageCropBorders},prefs=${activity.appGraph.readerPreferences.webtoonSidePadding.get()}/"+
                        "${activity.appGraph.readerPreferences.webtoonDisableZoomOut.get()}"
                    restored=viewer!=null &&
                        activity.viewModel.state.value.currentChapter?.chapter?.id==chapter &&
                        activity.viewModel.state.value.currentPage==savedPage &&
                        activity.appGraph.readerPreferences.webtoonSidePadding.get()==0 &&
                        activity.appGraph.readerPreferences.webtoonDisableZoomOut.get() &&
                        viewer.config.imageCropBorders && tile?.width==viewer.recycler.width
                }
                restored
            } } catch(failure:AssertionError) {
                throw AssertionError("${failure.message}; $lastRestoreDiagnostic",failure)
            }
            reader.onActivity { activity ->
                assertReaderUsesFullContainerWidth(activity)
                assertEquals("Upgrade compatibility must retain the selected internal padding",0,
                    (activity.viewModel.state.value.viewer as WebtoonViewer).recycler.sidePaddingPixels)
                assertEquals("Upgrade compatibility must not rewrite the legacy preference",0,
                    activity.appGraph.readerPreferences.webtoonSidePadding.get())
                assertEquals("Upgrade compatibility retains the active logical page",savedPage,activity.viewModel.state.value.currentPage)
            }
        }
    }

    private fun screenshot(name: String, checkCoverage: Boolean=false) {
        val dir = File(context.getExternalFilesDir(null), "arkhive-qa").apply { mkdirs() }
        val file=File(dir,"$name.png");assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(file))
        if(checkCoverage) android.graphics.BitmapFactory.decodeFile(file.path).let { bitmap ->
            try { val samples=(120 until bitmap.height-150 step 150).map {bitmap.getPixel(bitmap.width/2,it) and 0x00ffffff}
                assertTrue("Visible long-page pixels must cover the viewport after decoding, not just old tile readiness",samples.count {it!=0}.toDouble()/samples.size>.8)
            } finally {bitmap.recycle()}
        }
    }
    private fun revealFullWidthAction(device:UiDevice) {
        // Settings opens on the reader's Reading mode tab, but explicitly select it so this
        // remains stable if the dialog preserves its last-used tab across future versions.
        device.wait(Until.findObject(By.text("Reading mode")),5_000)?.click()
        val action=By.text("Use full available width")
        // At large text sizes a swipe can carry the action through the viewport between
        // accessibility snapshots. Scroll the actual vertical body in smaller steps and
        // wait for each result instead of immediately sending another fling.
        fun body()=device.findObjects(By.scrollable(true))
            .lastOrNull { it.visibleBounds.height()>device.displayHeight/3 }
        for(direction in listOf(Direction.DOWN,Direction.UP)) {
            var previous=""
            var unchanged=0
            for(attempt in 0 until 20) {
                if(device.wait(Until.findObject(action),800)!=null) return
                val scroll=body() ?: break
                val signature=scroll.findObjects(By.clazz("android.widget.TextView"))
                    .take(4).joinToString { "${it.text}@${it.visibleBounds}" }
                unchanged=if(signature==previous) unchanged+1 else 0
                if(unchanged>=2) break
                previous=signature
                // Compose's virtual ScrollView can return false despite a successful move.
                // Observe the settled visible rows rather than treating that event result as
                // an end-of-list signal. Slow short moves avoid flinging past the action.
                scroll.scroll(direction,.2f,600)
            }
        }
        if(device.findObject(By.text("Use full available width"))==null) {
            val hierarchy=File(context.cacheDir,"full-width-settings-hierarchy.xml")
            device.dumpWindowHierarchy(hierarchy)
            val visibleState=runCatching { hierarchy.readText().take(8000) }.getOrDefault("hierarchy unavailable")
            throw AssertionError("Full-width action is not reachable from the Reading mode settings tab: $visibleState")
        }
    }
    private suspend fun openReaderSettings(device:UiDevice,scenario:ActivityScenario<ReaderActivity>) {
        // The storyboard reader menu intentionally groups the legacy settings bar under
        // Reader tools. Use that visible route instead of searching for a hidden Settings icon.
        val settings=findStableClickable(device,listOf(By.desc("Settings"),By.descContains("Settings")),1_500)
        if(settings!=null) {
            settings.first.click()
            return
        }
        requireStableClickable(device,By.text("⋮"),"reader menu",scenario).click()
        requireStableClickable(device,By.text("Reader tools"),"Reader tools menu item",scenario).click()
        requireStableClickable(device,By.desc("Settings"),"Settings",scenario).click()
    }
    private suspend fun requireStableClickable(
        device:UiDevice,
        selector:androidx.test.uiautomator.BySelector,
        label:String,
        scenario:ActivityScenario<ReaderActivity>,
    ):androidx.test.uiautomator.UiObject2 = findStableClickable(device,listOf(selector))?.first
        ?: run {
            val hierarchy=File(context.cacheDir,"full-width-reader-$label-hierarchy.xml")
            val screenshot=File(context.cacheDir,"full-width-reader-$label-missing.png")
            device.dumpWindowHierarchy(hierarchy)
            device.takeScreenshot(screenshot)
            var readerState="activity unavailable"
            runCatching { scenario.onActivity { activity ->
                val state=activity.viewModel.state.value
                val viewer=state.viewer as? WebtoonViewer
                readerState="menu=${state.menuVisible},dialog=${state.dialog},chapter=${state.currentChapter?.chapter?.id},"+
                    "page=${state.currentPage},viewer=${state.viewer?.javaClass?.simpleName},"+
                    "width=${viewer?.recycler?.width},height=${viewer?.recycler?.height},"+
                    "offset=${viewer?.recycler?.computeVerticalScrollOffset()},crop=${viewer?.config?.imageCropBorders},"+
                    "focus=${activity.window.decorView.hasWindowFocus()}"
            } }
            val visible=runCatching { hierarchy.readText().take(8000) }.getOrDefault("hierarchy unavailable")
            throw AssertionError("Visible $label button stayed absent/unstable; $readerState; " +
                "hierarchy=$visible; screenshot=${screenshot.absolutePath}")
        }
    private suspend fun findStableClickable(
        device:UiDevice,
        selectors:List<androidx.test.uiautomator.BySelector>,
        timeoutMs:Long=8_000,
    ):Pair<androidx.test.uiautomator.UiObject2,String>? {
        var lastBounds:String?=null
        var stableObservations=0
        var diagnostic="label not found"
        var result:Pair<androidx.test.uiautomator.UiObject2,String>?=null
        withTimeoutOrNull(timeoutMs) {
            while(result==null) {
                for(selector in selectors) {
                    val labelNode=device.findObject(selector)
                    val row=runCatching { labelNode?.let(::clickableReaderAncestor) }.getOrNull()
                    diagnostic="label=${automationNodeDiagnostic(labelNode)},row=${automationNodeDiagnostic(row)}"
                    val bounds=runCatching {
                        row?.takeIf { it.isEnabled && it.isClickable }?.visibleBounds
                            ?.takeIf { it.width()>0 }?.flattenToString()
                    }.getOrNull()
                    if(row!=null && bounds!=null) {
                        if(bounds==lastBounds) stableObservations++ else stableObservations=0
                        lastBounds=bounds
                        if(stableObservations>=2) result=row to diagnostic
                    } else {
                        lastBounds=null
                        stableObservations=0
                    }
                    if(result!=null) break
                }
                if(result==null) delay(100)
            }
        }
        return result
    }
    private fun clickableReaderAncestor(label:androidx.test.uiautomator.UiObject2):androidx.test.uiautomator.UiObject2? {
        var node:androidx.test.uiautomator.UiObject2?=label
        repeat(8) {
            val current=node ?: return null
            if(current.isClickable) return current
            node=current.parent
        }
        return null
    }
    private fun automationNodeDiagnostic(node:androidx.test.uiautomator.UiObject2?):String=runCatching {
        node?.let { "enabled=${it.isEnabled},clickable=${it.isClickable},bounds=${it.visibleBounds.flattenToString()}" } ?: "missing"
    }.getOrDefault("stale")
    private suspend fun await(condition: () -> Boolean) = withTimeout(25000) { while (!condition()) delay(100) }
    private suspend fun await(stage:String, timeoutMs:Long=25_000, condition:() -> Boolean) {
        try { withTimeout(timeoutMs) { while(!condition()) delay(100) } }
        catch(error:TimeoutCancellationException) { throw AssertionError("Timed out at $stage",error) }
    }
    private inline fun <reified T : View> View.all(): List<T> { val out = mutableListOf<T>(); val pending = java.util.ArrayDeque<View>(); pending.add(this); while(pending.isNotEmpty()) { val v = pending.removeFirst(); if(v is T) out += v; if(v is ViewGroup) for(i in 0 until v.childCount) pending.add(v.getChildAt(i)) }; return out }
}
