// Modified for the private ReadingApp prototype; see READINGAPP-PROTOTYPE.md.
package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.graphics.PointF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.core.app.ActivityCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.WebtoonLayoutManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutoAdvanceResult
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutoStepScroller
import eu.kanade.tachiyomi.ui.reader.automation.boundedScrollPixels
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import kotlin.math.max
import kotlin.math.min

/**
 * Implementation of a [Viewer] to display pages with a [RecyclerView].
 */
class WebtoonViewer(val activity: ReaderActivity, val isContinuous: Boolean = true) : Viewer {

    val graph by lazy { activity.appGraph }
    val downloadManager by lazy { graph.downloadManager }
    val readerPreferences by lazy { graph.readerPreferences }

    private val scope = MainScope()

    /**
     * Recycler view used by this viewer.
     */
    val recycler = WebtoonRecyclerView(activity)
    private val autoStepScroller = ReaderAutoStepScroller(activity)

    /**
     * Frame containing the recycler view.
     */
    private val frame = WebtoonFrame(activity)

    /**
     * Distance to scroll when the user taps on one side of the recycler view.
     */
    private val scrollDistance = activity.resources.displayMetrics.heightPixels * 3 / 4

    /**
     * Layout manager of the recycler view.
     */
    private val layoutManager = WebtoonLayoutManager(activity, scrollDistance)

    /**
     * Configuration used by this viewer, like allow taps, or crop image borders.
     */
    val config = WebtoonConfig(scope, readerPreferences)

    /**
     * Adapter of the recycler view.
     */
    private val adapter = WebtoonAdapter(this)

    /** Event source for bounded Auto-read waits at the decoded image frontier. */
    private val autoReadLayoutChanges = MutableStateFlow(0)
    private val autoReadPageChanges = MutableStateFlow(0)
    private var autoReadReadableBottom = 0
    private var autoReadReachedChapterEnd = false
    private var autoReadPermanentFailure = false

    /**
     * Currently active item. It can be a chapter page or a chapter transition.
     */
    private var currentPage: Any? = null

    private val threshold: Int by lazy { readerPreferences.readerHideThreshold.get().threshold }

    init {
        recycler.sidePaddingPercent = config.sidePadding
        recycler.setItemViewCacheSize(RECYCLER_VIEW_CACHE_SIZE)
        recycler.isVisible = false // Don't let the recycler layout yet
        recycler.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        recycler.isFocusable = false
        recycler.itemAnimator = null
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    signalAutoReadLayoutChange()
                    if (newState != RecyclerView.SCROLL_STATE_IDLE) activity.pauseReaderAutomation()
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    signalAutoReadLayoutChange()
                    onScrolled()

                    if ((dy > threshold || dy < -threshold) && activity.viewModel.state.value.menuVisible) {
                        activity.hideMenu()
                    }

                    if (dy < 0) {
                        val firstIndex = layoutManager.findFirstVisibleItemPosition()
                        val firstItem = adapter.items.getOrNull(firstIndex)
                        if (firstItem is ChapterTransition.Prev && firstItem.to != null) {
                            activity.requestPreloadChapter(firstItem.to)
                        }
                    }

                    val lastIndex = layoutManager.findLastEndVisibleItemPosition()
                    val lastItem = adapter.items.getOrNull(lastIndex)
                    if (lastItem is ChapterTransition.Next && lastItem.to == null) {
                        activity.showMenu()
                    }
                }
            },
        )
        recycler.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) = signalAutoReadLayoutChange()
            override fun onChildViewDetachedFromWindow(view: View) = signalAutoReadLayoutChange()
        })
        recycler.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> signalAutoReadLayoutChange() }
        recycler.tapListener = { event ->
            val viewPosition = IntArray(2)
            recycler.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            recycler.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = PointF(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / recycler.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / recycler.originalHeight,
            )
            when (config.navigator.getAction(pos)) {
                NavigationRegion.MENU -> activity.toggleMenu()
                NavigationRegion.NEXT, NavigationRegion.RIGHT -> scrollDown()
                NavigationRegion.PREV, NavigationRegion.LEFT -> scrollUp()
            }
        }
        recycler.longTapListener = f@{ event ->
            if (activity.viewModel.state.value.menuVisible || config.longTapEnabled) {
                val child = recycler.findChildViewUnder(event.x, event.y)
                if (child != null) {
                    val position = recycler.getChildAdapterPosition(child)
                    val item = adapter.items.getOrNull(position)
                    if (item is ReaderPage) {
                        activity.onPageLongTap(item)
                        return@f true
                    }
                }
            }
            false
        }

        config.imagePropertyChangedListener = {
            recycler.sidePaddingPercent = config.sidePadding
            refreshAdapter()
        }

        config.themeChangedListener = {
            ActivityCompat.recreate(activity)
        }

        config.doubleTapZoomChangedListener = {
            frame.doubleTapZoom = it
        }

        config.zoomPropertyChangedListener = {
            frame.zoomOutDisabled = it
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }

        frame.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        frame.addView(recycler)
    }

    private fun checkAllowPreload(page: ReaderPage?): Boolean {
        // Page is transition page - preload allowed
        page ?: return true

        // Initial opening - preload allowed
        currentPage ?: return true

        val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
        val nextChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as? ReaderPage)?.chapter

        // Allow preload for
        // 1. Going between pages of same chapter
        // 2. Next chapter page
        return when (page.chapter) {
            (currentPage as? ReaderPage)?.chapter -> true
            nextChapter -> true
            else -> false
        }
    }

    /**
     * Returns the view this viewer uses.
     */
    override fun getView(): View {
        return frame
    }

    /**
     * Destroys this viewer. Called when leaving the reader or swapping viewers.
     */
    override fun destroy() {
        super.destroy()
        scope.cancel()
    }

    /**
     * Called from the RecyclerView listener when a [page] is marked as active. It notifies the
     * activity of the change and requests the preload of the next chapter if this is the last page.
     */
    private fun onPageSelected(page: ReaderPage, allowPreload: Boolean) {
        val pages = page.chapter.pages ?: return
        logcat { "onPageSelected: ${page.number}/${pages.size}" }
        activity.onPageSelected(page)

        // Preload next chapter once we're within the last 5 pages of the current chapter
        val inPreloadRange = pages.size - page.number < 5
        if (inPreloadRange && allowPreload && page.chapter == adapter.currentChapter) {
            logcat { "Request preload next chapter because we're at page ${page.number} of ${pages.size}" }
            val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
            val transitionChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as?ReaderPage)?.chapter
            if (transitionChapter != null) {
                logcat { "Requesting to preload chapter ${transitionChapter.chapter.chapterNumber}" }
                activity.requestPreloadChapter(transitionChapter)
            }
        }
    }

    /**
     * Called from the RecyclerView listener when a [transition] is marked as active. It request the
     * preload of the destination chapter of the transition.
     */
    private fun onTransitionSelected(transition: ChapterTransition) {
        logcat { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        if (toChapter != null) {
            logcat { "Request preload destination chapter because we're on the transition" }
            activity.requestPreloadChapter(toChapter)
        }
    }

    /**
     * Tells this viewer to set the given [chapters] as active.
     */
    override fun setChapters(chapters: ViewerChapters) {
        val forceTransition = config.alwaysShowChapterTransition || currentPage is ChapterTransition
        adapter.setChapters(chapters, forceTransition)

        if (recycler.isGone) {
            logcat { "Recycler first layout" }
            val pages = chapters.currChapter.pages ?: return
            moveToPage(pages[min(chapters.currChapter.requestedPage, pages.lastIndex)])
            recycler.isVisible = true
        }
    }

    /**
     * Tells this viewer to move to the given [page].
     */
    override fun moveToPage(page: ReaderPage) {
        val position = adapter.items.indexOf(page)
        if (position != -1) {
            layoutManager.scrollToPositionWithOffset(position, 0)
            if (layoutManager.findLastEndVisibleItemPosition() == -1) {
                onScrolled(pos = position)
            }
        } else {
            logcat { "Page $page not found in adapter" }
        }
    }

    override val supportsContinuousAutoScroll = true

    override fun autoAdvanceWithinChapter(chapterId: Long, pixels: Int?): ReaderAutoAdvanceResult {
        if (recycler.scaleY != 1f) return ReaderAutoAdvanceResult.NOT_READY
        if (recycler.isComputingLayout || recycler.height == 0) return ReaderAutoAdvanceResult.WAITING
        updateAutoReadWindow(chapterId)
        if (autoReadPermanentFailure) return ReaderAutoAdvanceResult.NOT_READY
        val viewportBottom = recycler.height - recycler.paddingBottom
        val stepDistance = (viewportBottom - recycler.paddingTop) * 3 / 4
        val distance = boundedScrollPixels(pixels ?: stepDistance, autoReadReadableBottom, viewportBottom)
        if (distance == 0) {
            return if (autoReadReachedChapterEnd) ReaderAutoAdvanceResult.CHAPTER_END else ReaderAutoAdvanceResult.WAITING
        }
        if (pixels == null && config.usePageTransitions && ReaderAutoStepScroller.animationsAllowed(activity)) {
            autoStepScroller.scrollBy(recycler, 0, distance)
        } else {
            recycler.scrollBy(0, distance)
        }
        return ReaderAutoAdvanceResult.ADVANCED
    }

    override suspend fun awaitAutoAdvanceReady(chapterId: Long): Boolean {
        if (autoReadWindowState(chapterId).first) return true
        requestAutoReadFrontier(chapterId)
        merge(autoReadLayoutChanges.map { Unit }, autoReadPageChanges.map { Unit }).first {
            val state = autoReadWindowState(chapterId)
            if (state.first || state.second) true else {
                requestAutoReadFrontier(chapterId)
                false
            }
        }
        return autoReadWindowState(chapterId).first
    }

    /** Called by attached holders on bounded transport/decode transitions, never per frame. */
    fun signalAutoReadPageChange() {
        autoReadPageChanges.value = autoReadPageChanges.value + 1
    }

    /** Stage details are used only when the authored reader test times out. */
    fun autoReadDiagnosticState(chapterId: Long): String {
        val rect = android.graphics.Rect()
        val children = (0 until recycler.childCount).mapNotNull { index ->
            val child = recycler.getChildAt(index)
            val position = layoutManager.getPosition(child)
            val page = adapter.items.getOrNull(position) as? ReaderPage ?: return@mapNotNull null
            if (page.chapter.chapter.id != chapterId) return@mapNotNull null
            val image = child as? ReaderPageImageView
            child.getGlobalVisibleRect(rect)
            "${page.index}:${page.status}/${page.renderState}/image=${image?.isImageReadyForAutoScroll}/" +
                "shown=${child.isShown}/bounds=${rect.flattenToString()}/decode=${image?.autoScrollDecodeDiagnostic()}"
        }
        return "scaleY=${recycler.scaleY},computing=${recycler.isComputingLayout},height=${recycler.height}," +
            "offset=${recycler.computeVerticalScrollOffset()},children=${children.joinToString(prefix="[", postfix="]")}"
    }

    /** Pair is (readable movement or chapter end, permanent decode error at the frontier). */
    private fun autoReadWindowState(chapterId: Long): Pair<Boolean, Boolean> {
        if (recycler.isComputingLayout || recycler.height == 0 || recycler.scaleY != 1f) return false to false
        updateAutoReadWindow(chapterId)
        val viewportBottom = recycler.height - recycler.paddingBottom
        val distance = boundedScrollPixels(Int.MAX_VALUE, autoReadReadableBottom, viewportBottom)
        return (distance > 0 || autoReadReachedChapterEnd) to autoReadPermanentFailure
    }

    /** Movement never crosses the contiguous decoded edge, even for a fully downloaded long page. */
    private fun updateAutoReadWindow(chapterId: Long) {
        autoReadReadableBottom = recycler.paddingTop
        autoReadReachedChapterEnd = false
        autoReadPermanentFailure = false
        val viewportTop = recycler.paddingTop
        val viewportBottom = recycler.height - recycler.paddingBottom
        var foundChapter = false
        for (index in 0 until layoutManager.childCount) {
            val child = layoutManager.getChildAt(index) ?: continue
            val childTop = layoutManager.getDecoratedTop(child)
            val childBottom = layoutManager.getDecoratedBottom(child)
            if (childBottom <= viewportTop) continue
            val page = adapter.items.getOrNull(layoutManager.getPosition(child)) as? ReaderPage ?: continue
            if (page.chapter.chapter.id != chapterId) {
                if (foundChapter) break
                continue
            }
            foundChapter = true
            if (page.status is Page.State.Error || page.renderState == ReaderPage.RenderState.ERROR) {
                autoReadPermanentFailure = true
                return
            }
            if (page.status != Page.State.Ready) return
            val image = child as? ReaderPageImageView ?: return
            val extent = image.autoScrollDecodedExtent() ?: return
            val decodedBottom = (extent ushr 1).toInt()
            val fullyDecoded = (extent and 1L) != 0L
            autoReadReadableBottom = max(autoReadReadableBottom, if (fullyDecoded) childBottom else childTop + decodedBottom)
            autoReadReachedChapterEnd = fullyDecoded && page == page.chapter.pages?.lastOrNull()
            if (!fullyDecoded) return
        }
    }

    /** Request visible tiles, or predecode one adjacent page's first tile when it starts offscreen. */
    private fun requestAutoReadFrontier(chapterId: Long) {
        val viewportTop = recycler.paddingTop
        val viewportBottom = recycler.height - recycler.paddingBottom
        var foundChapter = false
        for (index in 0 until layoutManager.childCount) {
            val child = layoutManager.getChildAt(index) ?: continue
            val childTop = layoutManager.getDecoratedTop(child)
            val childBottom = layoutManager.getDecoratedBottom(child)
            if (childBottom <= viewportTop) continue
            val page = adapter.items.getOrNull(layoutManager.getPosition(child)) as? ReaderPage ?: continue
            if (page.chapter.chapter.id != chapterId) {
                if (foundChapter) return
                continue
            }
            foundChapter = true
            if (page.status is Page.State.Error || page.renderState == ReaderPage.RenderState.ERROR || page.status != Page.State.Ready) return
            val image = child as? ReaderPageImageView ?: return
            val extent = image.autoScrollDecodedExtent()
            if (extent == null) {
                if (childTop < viewportBottom && childBottom > viewportTop) image.invalidateImageViewport()
                else if (childTop >= viewportBottom) image.requestAutoScrollFirstRegion()
                return
            }
            if ((extent and 1L) != 0L) continue
            if (childTop < viewportBottom && childBottom > viewportTop) {
                image.invalidateImageViewport()
            } else if (childTop >= viewportBottom) {
                image.requestAutoScrollFirstRegion()
            }
            return
        }
    }

    private fun signalAutoReadLayoutChange() {
        autoReadLayoutChanges.value = autoReadLayoutChanges.value + 1
    }

    override fun cancelAutoReadStepAnimation() {
        autoStepScroller.cancel()
    }

    override fun prepareAutoReadStart() {
        recycler.stopScroll()
    }

    fun onScrolled(pos: Int? = null) {
        val position = pos ?: layoutManager.findLastEndVisibleItemPosition()
        val item = adapter.items.getOrNull(position)
        val allowPreload = checkAllowPreload(item as? ReaderPage)
        if (item != null && currentPage != item) {
            currentPage = item
            when (item) {
                is ReaderPage -> onPageSelected(item, allowPreload)
                is ChapterTransition -> onTransitionSelected(item)
            }
        }
    }

    /**
     * Scrolls up by [scrollDistance].
     */
    private fun scrollUp() {
        if (config.usePageTransitions && ReaderAutoStepScroller.animationsAllowed(activity)) {
            recycler.smoothScrollBy(0, -scrollDistance)
        } else {
            recycler.scrollBy(0, -scrollDistance)
        }
    }

    /**
     * Scrolls down by [scrollDistance].
     */
    private fun scrollDown() {
        if (config.usePageTransitions && ReaderAutoStepScroller.animationsAllowed(activity)) {
            recycler.smoothScrollBy(0, scrollDistance)
        } else {
            recycler.scrollBy(0, scrollDistance)
        }
    }

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) scrollDown() else scrollUp()
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) scrollUp() else scrollDown()
                }
            }
            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            -> if (isUp) scrollUp()

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            -> if (isUp) scrollDown()
            else -> return false
        }
        return true
    }

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        return false
    }

    /**
     * Notifies adapter of changes around the current page to trigger a relayout in the recycler.
     * Used when an image configuration is changed.
     */
    private fun refreshAdapter() {
        val position = layoutManager.findLastEndVisibleItemPosition()
        adapter.refresh()
        adapter.notifyItemRangeChanged(
            max(0, position - 3),
            min(position + 3, adapter.itemCount - 1),
        )
    }
}

// Double the cache size to reduce rebinds/recycles incurred by the extra layout space on scroll direction changes
private const val RECYCLER_VIEW_CACHE_SIZE = 4
