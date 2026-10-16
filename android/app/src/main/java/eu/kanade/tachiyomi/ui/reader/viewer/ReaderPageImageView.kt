// Modified for the private ReadingApp prototype; see READINGAPP-PROTOTYPE.md.
package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.annotation.CallSuper
import androidx.annotation.StyleRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.os.postDelayed
import androidx.core.view.isVisible
import coil3.asDrawable
import coil3.dispose
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.ViewSizeResolver
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_IN_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
import com.github.chrisbanes.photoview.PhotoView
import eu.kanade.tachiyomi.data.coil.ImageDecoder
import eu.kanade.tachiyomi.data.coil.cropBorders
import eu.kanade.tachiyomi.data.coil.webtoonStrips
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonStripImageView
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutoStepScroller
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.view.isVisibleOnScreen
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import mihon.readingapp.readers.LongPagePolicy

/**
 * A wrapper view for showing page image.
 *
 * Animated image will be drawn by [PhotoView] while [SubsamplingScaleImageView] will take non-animated image.
 *
 * @param isWebtoon if true, [WebtoonStripImageView] will be used instead of [SubsamplingScaleImageView]
 * and [AppCompatImageView] will be used instead of [PhotoView]
 */
open class ReaderPageImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttrs: Int = 0,
    @StyleRes defStyleRes: Int = 0,
    private val isWebtoon: Boolean = false,
) : FrameLayout(context, attrs, defStyleAttrs, defStyleRes) {

    private var pageView: View? = null

    private var stripRequest: Disposable? = null

    private var config: Config? = null
    private var readableLongPage = false
    private var firstLongSelection = true
    data class LongPagePosition(val progression: Float, val relativeZoom: Float)
    var onLongPagePositionChanged: ((LongPagePosition) -> Unit)? = null

    val isReadableLongPage: Boolean get() = readableLongPage

    var onImageLoaded: (() -> Unit)? = null
    /** Geometry is available for layout before a lazy long-page tile has decoded. */
    var onImageGeometryReady: (() -> Unit)? = null
    var onImageLoadError: ((Throwable?) -> Unit)? = null
    /** Signals a newly decoded visible long-page region without replaying initial load effects. */
    var onAutoScrollViewportReady: (() -> Unit)? = null
    var onScaleChanged: ((newScale: Float) -> Unit)? = null
    var onViewClicked: (() -> Unit)? = null

    /**
     * For automatic background. Will be set as background color when [onImageLoaded] is called.
     */
    var pageBackground: Drawable? = null

    /** Download readiness and decoded image readiness are separate; automation needs both. */
    private var imageLoadedForAutoScroll = false
    private var firstStripDecodePending = true

    val isImageReadyForAutoScroll: Boolean
        get() = imageLoadedForAutoScroll && when (val view = pageView) {
            is WebtoonStripImageView -> view.hasDecodedImage
            is SubsamplingScaleImageView -> view.isReady
            is AppCompatImageView -> view.drawable != null
            else -> false
        }

    @CallSuper
    open fun onImageLoaded() {
        imageLoadedForAutoScroll = true
        onImageLoaded?.invoke()
        background = pageBackground
    }

    @CallSuper
    open fun onImageLoadError(error: Throwable?) {
        imageLoadedForAutoScroll = false
        onImageLoadError?.invoke(error)
    }

    @CallSuper
    open fun onScaleChanged(newScale: Float) {
        onScaleChanged?.invoke(newScale)
    }

    @CallSuper
    open fun onViewClicked() {
        onViewClicked?.invoke()
    }

    open fun onPageSelected(forward: Boolean) {
        with(pageView as? SubsamplingScaleImageView) {
            if (this == null) return
            if (isReady) {
                if (readableLongPage) {
                    if (firstLongSelection && config?.savedLongPosition != null) restoreLongPosition(config!!.savedLongPosition!!)
                    else goToLongPageEdge(forward)
                    firstLongSelection = false
                } else landscapeZoom(forward)
            } else {
                setOnImageEventListener(
                    object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onReady() {
                            configureLongPage(config)
                            setupZoom(config)
                            if (readableLongPage) {
                                if (firstLongSelection && config?.savedLongPosition != null) restoreLongPosition(config!!.savedLongPosition!!)
                                else goToLongPageEdge(forward)
                                firstLongSelection = false
                            } else landscapeZoom(forward)
                            this@ReaderPageImageView.onImageLoaded()
                        }

                        override fun onImageLoadError(e: Exception) {
                            onImageLoadError(e)
                        }
                    },
                )
            }
        }
    }

    private fun SubsamplingScaleImageView.landscapeZoom(forward: Boolean) {
        if (
            config != null &&
            config!!.landscapeZoom &&
            config!!.minimumScaleType == SCALE_TYPE_CENTER_INSIDE &&
            sWidth > sHeight &&
            scale == minScale
        ) {
            handler?.postDelayed(500) {
                val point = when (config!!.zoomStartPosition) {
                    ZoomStartPosition.LEFT -> if (forward) PointF(0F, 0F) else PointF(sWidth.toFloat(), 0F)
                    ZoomStartPosition.RIGHT -> if (forward) PointF(sWidth.toFloat(), 0F) else PointF(0F, 0F)
                    ZoomStartPosition.CENTER -> center
                }

                val targetScale = height.toFloat() / sHeight.toFloat()
                animateScaleAndCenter(targetScale, point)!!
                    .withDuration(500)
                    .withEasing(EASE_IN_OUT_QUAD)
                    .withInterruptible(true)
                    .start()
            }
        }
    }

    fun setImage(drawable: Drawable, config: Config) {
        imageLoadedForAutoScroll = false
        readableLongPage = false
        firstLongSelection = true
        this.config = config
        if (drawable is Animatable) {
            prepareAnimatedImageView()
            setAnimatedImage(drawable, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(drawable, config)
        }
    }

    fun setImage(source: BufferedSource, isAnimated: Boolean, config: Config) {
        imageLoadedForAutoScroll = false
        readableLongPage = false
        firstLongSelection = true
        this.config = config
        if (isAnimated) {
            prepareAnimatedImageView()
            setAnimatedImage(source, config)
        } else if (isWebtoon) {
            prepareStripImageView()
            setStripImage(source, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(source, config)
        }
    }

    fun invalidateImageViewport() { (pageView as? WebtoonStripImageView)?.invalidate() }

    fun requestAutoScrollFirstRegion() { (pageView as? WebtoonStripImageView)?.requestAutoScrollFirstRegion() }

    /** Packs decoded bottom pixels and a fully-decoded flag into one allocation-free value. */
    fun autoScrollDecodedExtent(): Long? {
        val view = pageView
        if (view is WebtoonStripImageView) {
            return view.autoScrollDecodedExtent()
        }
        if (!imageLoadedForAutoScroll) return null
        val ready = when (view) {
            is SubsamplingScaleImageView -> view.isReady
            is AppCompatImageView -> view.drawable != null
            else -> false
        }
        return if (ready) (height.toLong() shl 1) or 1L else null
    }

    fun autoScrollDecodeDiagnostic(): String = when (val view = pageView) {
        is WebtoonStripImageView -> {
            val extent = view.autoScrollDecodedExtent()
            "${view.autoScrollDiagnosticState()}/extent=${extent?.ushr(1)}/${extent?.and(1L) == 1L}"
        }
        is SubsamplingScaleImageView -> "subsamplingReady=${view.isReady}"
        else -> "view=${view?.javaClass?.simpleName}"
    }

    fun recycle() {
        imageLoadedForAutoScroll = false
        stripRequest?.dispose()
        stripRequest = null
        pageView?.let {
            when (it) {
                is SubsamplingScaleImageView -> it.recycle()
                is AppCompatImageView -> it.dispose()
                is WebtoonStripImageView -> it.recycle()
            }
            it.isVisible = false
        }
    }

    /**
     * Check if the image can be panned to the left
     */
    fun canPanLeft(): Boolean = canPan { it.left }

    /**
     * Check if the image can be panned to the right
     */
    fun canPanRight(): Boolean = canPan { it.right }

    /**
     * Check whether the image can be panned.
     * @param fn a function that returns the direction to check for
     */
    private fun canPan(fn: (RectF) -> Float): Boolean {
        (pageView as? SubsamplingScaleImageView)?.let { view ->
            RectF().let {
                view.getPanRemaining(it)
                return fn(it) > 1
            }
        }
        return false
    }

    /**
     * Pans the image to the left by a screen's width worth.
     */
    fun panLeft() {
        pan { center, view -> center.also { it.x -= view.width / view.scale } }
    }

    /**
     * Pans the image to the right by a screen's width worth.
     */
    fun panRight() {
        pan { center, view -> center.also { it.x += view.width / view.scale } }
    }

    /**
     * Pans the image.
     * @param fn a function that computes the new center of the image
     */
    private fun pan(fn: (PointF, SubsamplingScaleImageView) -> PointF) {
        (pageView as? SubsamplingScaleImageView)?.let { view ->

            val target = fn(view.center ?: return, view)
            view.animateCenter(target)!!
                .withEasing(EASE_OUT_QUAD)
                .withDuration(250)
                .withInterruptible(true)
                .start()
        }
    }

    /** A manual/timed reading step stays within a width-fitted long image first. */
    fun stepWithinLongPage(forward: Boolean, animate: Boolean = true): Boolean {
        val view = pageView as? SubsamplingScaleImageView ?: return false
        if (!readableLongPage || !view.isReady) return false
        val remaining = RectF().also(view::getPanRemaining)
        if ((if (forward) remaining.bottom else remaining.top) <= 1f) return false
        val center = view.center ?: return false
        val target = PointF(center.x, center.y + (if (forward) 1 else -1) * view.height * .75f / view.scale)
        if (animate && ReaderAutoStepScroller.animationsAllowed(context)) {
            view.animateCenter(target)!!
                .withEasing(EASE_OUT_QUAD)
                .withDuration((250 * context.animatorDurationScale).toLong().coerceAtLeast(1L))
                .withInterruptible(true)
                .start()
        } else {
            view.setScaleAndCenter(view.scale, target)
        }
        return true
    }

    fun cancelAutoReadStepAnimation() {
        val view = pageView as? SubsamplingScaleImageView ?: return
        val center = view.center ?: return
        view.setScaleAndCenter(view.scale, center)
    }

    override fun canScrollVertically(direction: Int): Boolean {
        val view = pageView as? SubsamplingScaleImageView
        if (readableLongPage && view?.isReady == true) {
            val remaining = RectF().also(view::getPanRemaining)
            return (if (direction < 0) remaining.top else remaining.bottom) > 1f
        }
        return super.canScrollVertically(direction)
    }

    private fun SubsamplingScaleImageView.configureLongPage(config: Config?) {
        val requested = config?.minimumScaleType ?: SCALE_TYPE_CENTER_INSIDE
        val smart = config?.smartLongPageFit == true && requested == SCALE_TYPE_CENTER_INSIDE &&
            LongPagePolicy.needsWidthFit(sWidth, sHeight, width, height)
        readableLongPage = LongPagePolicy.isLong(sWidth, sHeight) &&
            (smart || requested == SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH)
        if (smart) setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH)
        if (readableLongPage) restoreLongPosition(config?.savedLongPosition ?: LongPagePosition(0f, 1f))
    }

    private fun SubsamplingScaleImageView.restoreLongPosition(position: LongPagePosition) {
        val base = width.toFloat() / sWidth
        val targetScale = base * position.relativeZoom.coerceIn(1f, MAX_ZOOM_SCALE)
        val half = height / (2f * targetScale)
        val extent = (sHeight - 2 * half).coerceAtLeast(0f)
        setScaleAndCenter(targetScale, PointF(sWidth / 2f, half + extent * position.progression.coerceIn(0f, 1f)))
    }

    private fun SubsamplingScaleImageView.goToLongPageEdge(forward: Boolean) {
        val base = width.toFloat() / sWidth
        val half = height / (2f * base)
        setScaleAndCenter(base, PointF(sWidth / 2f, if (forward) half else sHeight - half))
    }

    private fun prepareNonAnimatedImageView() {
        if (pageView is SubsamplingScaleImageView) return
        removeView(pageView)

        pageView = SubsamplingScaleImageView(context).apply {
            setMaxTileSize(ImageUtil.hardwareBitmapThreshold)
            setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
            setMinimumTileDpi(180)
            setOnStateChangedListener(
                object : SubsamplingScaleImageView.OnStateChangedListener {
                    override fun onScaleChanged(newScale: Float, origin: Int) {
                        this@ReaderPageImageView.onScaleChanged(newScale)
                    }

                    override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                        if (readableLongPage && newCenter != null && isReady && scale > 0f) {
                            val half = height / (2f * scale)
                            val extent = (sHeight - 2 * half).coerceAtLeast(1f)
                            onLongPagePositionChanged?.invoke(LongPagePosition(
                                ((newCenter.y - half) / extent).coerceIn(0f, 1f), scale / (width.toFloat() / sWidth)))
                        }
                    }
                },
            )
            setOnClickListener { this@ReaderPageImageView.onViewClicked() }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun SubsamplingScaleImageView.setupZoom(config: Config?) {
        // 5x zoom
        val base = if (readableLongPage) width.toFloat() / sWidth else scale
        maxScale = base * MAX_ZOOM_SCALE
        setDoubleTapZoomScale(base * 2)

        when (config?.zoomStartPosition) {
            ZoomStartPosition.LEFT -> setScaleAndCenter(scale, PointF(0F, 0F))
            ZoomStartPosition.RIGHT -> setScaleAndCenter(scale, PointF(sWidth.toFloat(), 0F))
            ZoomStartPosition.CENTER -> setScaleAndCenter(scale, center)
            null -> {}
        }
    }

    private fun setNonAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? SubsamplingScaleImageView)?.apply {
        setDoubleTapZoomDuration(config.zoomDuration.getSystemScaledDuration())
        setMinimumScaleType(config.minimumScaleType)
        setMinimumDpi(1) // Just so that very small image will be fit for initial load
        setCropBorders(config.cropBorders)
        setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    configureLongPage(config)
                    setupZoom(config)
                    if (isVisibleOnScreen() && !readableLongPage) landscapeZoom(true)
                    this@ReaderPageImageView.onImageLoaded()
                }

                override fun onImageLoadError(e: Exception) {
                    this@ReaderPageImageView.onImageLoadError(e)
                }
            },
        )

        when (data) {
            is BitmapDrawable -> {
                setImage(ImageSource.bitmap(data.bitmap))
                isVisible = true
            }
            is BufferedSource -> {
                setHardwareConfig(ImageUtil.canUseHardwareBitmap(data))
                setImage(ImageSource.inputStream(data.inputStream()))
                isVisible = true
            }
            else -> {
                throw IllegalArgumentException("Not implemented for class ${data::class.simpleName}")
            }
        }
    }

    private fun prepareStripImageView() {
        if (pageView is WebtoonStripImageView) return
        removeView(pageView)

        pageView = WebtoonStripImageView(context)
        addView(pageView, MATCH_PARENT, WRAP_CONTENT)
    }

    private fun setStripImage(data: BufferedSource, config: Config) {
        val view = pageView as? WebtoonStripImageView ?: return
        firstStripDecodePending = true
        view.onReload = {
            firstStripDecodePending = true
            imageLoadedForAutoScroll = false
            requestStrips(view, data, config, isReload = true)
        }
        requestStrips(view, data, config, isReload = false)
    }

    private fun requestStrips(view: WebtoonStripImageView, data: BufferedSource, config: Config, isReload: Boolean) {
        // A recycled view is reused for another page, where a request still running for the old one must not land
        stripRequest?.dispose()
        stripRequest = ImageRequest.Builder(context)
            // Peeking leaves the data whole for decoding again after the strips were freed off screen
            .data(data.peek())
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(
                onSuccess = { result ->
                    view.onReady = {
                        if (firstStripDecodePending) {
                            firstStripDecodePending = false
                            onImageLoaded()
                        } else {
                            onAutoScrollViewportReady?.invoke()
                        }
                    }
                    view.onRegionDecoded = { onAutoScrollViewportReady?.invoke() }
                    view.onFailure = { onImageLoadError(it) }
                    view.setImage(result as ImageDecoder.RegionImage)
                    view.isVisible = true
                    // The native recycler waits for header geometry before it can lay out/draw.
                    // Pixel readiness remains false until the visible regions have arrived.
                    if (!isReload) onImageGeometryReady?.invoke()
                },
            )
            .listener(
                onError = { _, result ->
                    onImageLoadError(result.throwable)
                },
            )
            .size(ViewSizeResolver(this))
            .precision(Precision.INEXACT)
            .cropBorders(config.cropBorders)
            .webtoonStrips(true)
            .crossfade(false)
            .build()
            .let(context.imageLoader::enqueue)
    }

    private fun prepareAnimatedImageView() {
        if (pageView is AppCompatImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            AppCompatImageView(context)
        } else {
            PhotoView(context)
        }.apply {
            adjustViewBounds = true

            if (this is PhotoView) {
                setScaleLevels(1F, 2F, MAX_ZOOM_SCALE)
                // Force 2 scale levels on double tap
                setOnDoubleTapListener(
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (scale > 1F) {
                                setScale(1F, e.x, e.y, true)
                            } else {
                                setScale(2F, e.x, e.y, true)
                            }
                            return true
                        }

                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            this@ReaderPageImageView.onViewClicked()
                            return super.onSingleTapConfirmed(e)
                        }
                    },
                )
                setOnScaleChangeListener { _, _, _ ->
                    this@ReaderPageImageView.onScaleChanged(scale)
                }
            }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun setAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? AppCompatImageView)?.apply {
        if (this is PhotoView) {
            setZoomTransitionDuration(config.zoomDuration.getSystemScaledDuration())
        }

        val request = ImageRequest.Builder(context)
            .data(data)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(
                onSuccess = { result ->
                    val drawable = result.asDrawable(context.resources)
                    setImageDrawable(drawable)
                    (drawable as? Animatable)?.start()
                    isVisible = true
                    this@ReaderPageImageView.onImageLoaded()
                },
            )
            .listener(
                onError = { _, result ->
                    onImageLoadError(result.throwable)
                },
            )
            .crossfade(false)
            .build()
        context.imageLoader.enqueue(request)
    }

    private fun Int.getSystemScaledDuration(): Int {
        return (this * context.animatorDurationScale).toInt().coerceAtLeast(1)
    }

    /**
     * All of the config except [zoomDuration] will only be used for non-animated image.
     */
    data class Config(
        val zoomDuration: Int,
        val minimumScaleType: Int = SCALE_TYPE_CENTER_INSIDE,
        val cropBorders: Boolean = false,
        val zoomStartPosition: ZoomStartPosition = ZoomStartPosition.CENTER,
        val landscapeZoom: Boolean = false,
        val smartLongPageFit: Boolean = true,
        val savedLongPosition: LongPagePosition? = null,
    )

    enum class ZoomStartPosition {
        LEFT,
        CENTER,
        RIGHT,
    }
}

private const val MAX_ZOOM_SCALE = 5F
