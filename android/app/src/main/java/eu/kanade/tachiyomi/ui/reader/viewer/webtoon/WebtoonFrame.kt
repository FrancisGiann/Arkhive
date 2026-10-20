package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.content.Context
import android.graphics.Rect
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout

/**
 * Frame layout which contains a [WebtoonRecyclerView]. It's needed to handle touch events,
 * because the recyclerview is scaled and its touch events are translated, which breaks the
 * detectors.
 *
 * TODO consider integrating this class into [WebtoonViewer].
 */
class WebtoonFrame(context: Context) : FrameLayout(context) {

    /**
     * Scale detector, either with pinch or quick scale.
     */
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())

    /**
     * Fling detector.
     */
    private val flingDetector = GestureDetector(context, FlingListener())

    /** A transformed child must not inherit the coordinate stream that changed its transform. */
    private var suppressChildTouchUntilUp = false
    private var childTouchActive = false

    var doubleTapZoom = true
        set(value) {
            field = value
            recycler?.doubleTapZoom = value
            scaleDetector.isQuickScaleEnabled = value
        }

    var zoomOutDisabled = false
        set(value) {
            field = value
            recycler?.zoomOutDisabled = value
        }

    /**
     * Recycler view added in this frame.
     */
    private val recycler: WebtoonRecyclerView?
        get() = getChildAt(0) as? WebtoonRecyclerView

    /**
     * Dispatches a touch event to the detectors.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            suppressChildTouchUntilUp = false
            childTouchActive = false
        }

        scaleDetector.onTouchEvent(ev)

        // A scale gesture changes the RecyclerView's matrix while Android is still mapping the
        // same MotionEvent stream into that child. Cancel that stream once, then let the next
        // independent one-finger gesture begin against the settled matrix.
        val startsOrContinuesScale = ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN || scaleDetector.isInProgress
        if (startsOrContinuesScale && !suppressChildTouchUntilUp) {
            suppressChildTouchUntilUp = true
            val cancel = MotionEvent.obtain(ev)
            cancel.action = MotionEvent.ACTION_CANCEL
            try {
                flingDetector.onTouchEvent(cancel)
            } finally {
                cancel.recycle()
            }
        } else if (!suppressChildTouchUntilUp) {
            flingDetector.onTouchEvent(ev)
        }

        // Get the bounding box of the recyclerview and translate any motion events to be within it.
        // Used to allow scrolling outside the recyclerview.
        val recyclerRect = Rect()
        recycler?.getHitRect(recyclerRect) ?: return super.dispatchTouchEvent(ev)
        // Shrink the box to account for any rounding issues.
        recyclerRect.inset(1, 1)

        if (recyclerRect.right < recyclerRect.left || recyclerRect.bottom < recyclerRect.top) {
            return super.dispatchTouchEvent(ev)
        }

        ev.setLocation(
            ev.x.coerceIn(recyclerRect.left.toFloat(), recyclerRect.right.toFloat()),
            ev.y.coerceIn(recyclerRect.top.toFloat(), recyclerRect.bottom.toFloat()),
        )

        if (suppressChildTouchUntilUp) {
            if (childTouchActive) {
                val cancel = MotionEvent.obtain(ev)
                cancel.action = MotionEvent.ACTION_CANCEL
                try {
                    super.dispatchTouchEvent(cancel)
                } finally {
                    cancel.recycle()
                }
                childTouchActive = false
            }
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                suppressChildTouchUntilUp = false
            }
            return true
        }

        val handled = super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> childTouchActive = handled
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> childTouchActive = false
        }
        return handled
    }

    /**
     * Scale listener used to delegate events to the recycler view.
     */
    inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            recycler?.onScaleBegin()
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            recycler?.onScale(detector.scaleFactor)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            recycler?.onScaleEnd()
        }
    }

    /**
     * Fling listener used to delegate events to the recycler view.
     */
    inner class FlingListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            return recycler?.zoomFling(velocityX.toInt(), velocityY.toInt()) ?: false
        }
    }
}
