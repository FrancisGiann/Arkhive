package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import eu.kanade.tachiyomi.data.coil.ImageDecoder
import kotlinx.coroutines.*
import mihon.readingapp.readers.LongPagePolicy
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Width-fitted regions retain source geometry. Only visible tiles plus one neighbor are decoded. */
class WebtoonStripImageView(context: Context) : View(context) {
    private data class Key(val sample: Int, val width: Int, val index: Int)
    private data class Tile(val bitmap: Bitmap, val top: Int, val bottom: Int)
    private val tiles = java.util.LinkedHashMap<Key, Tile>(8, .75f, true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var image: ImageDecoder.RegionImage? = null
    private var imageWidth = 0
    private var imageHeight = 0
    private var generation = 0L
    private var requestSerial = 0L
    private var pending: Job? = null
    private var needed = emptyList<Key>()
    private var wanted = emptyList<Key>()
    private var currentRows = 1
    private var released = false
    /** Readiness is tied to the visible tile range, which changes as a long page scrolls. */
    private var visibleRangeReady = false
    private val brush = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clip = Rect()
    private val autoReadRect = Rect()
    private val destination = Rect()
    var onReload: (() -> Unit)? = null
    var onReady: (() -> Unit)? = null
    var onRegionDecoded: (() -> Unit)? = null
    var onFailure: ((Throwable) -> Unit)? = null
    val cachedBitmapBytes: Long get() = tiles.values.sumOf { it.bitmap.allocationByteCount.toLong() }
    val cachedTileCount: Int get() = tiles.size
    fun autoScrollDiagnosticState(): String {
        val local = Rect()
        val visible = getLocalVisibleRect(local) && !local.isEmpty
        val scale = if (imageWidth > 0) width.toDouble() / imageWidth else 0.0
        val target = if (imageWidth > 0) (width * zoom()).roundToInt().coerceIn(1, 4096).coerceAtMost(imageWidth) else 1
        val sample = if (imageWidth > 0) LongPagePolicy.widthSample(imageWidth, target) else 1
        val rows = if (imageWidth > 0) LongPagePolicy.regionRows(imageWidth, sample) else 1
        val count = if (rows > 0) ceil(imageHeight.toDouble() / rows).toInt().coerceAtLeast(1) else 1
        val first = if (visible && scale > 0) (local.top / scale / rows).toInt().coerceIn(0, count - 1) else -1
        val last = if (visible && scale > 0) ((local.bottom - 1).coerceAtLeast(0) / scale / rows).toInt().coerceIn(first, count - 1) else -1
        val visibleKeys = if (first >= 0) (first..last).map { Key(sample, target, it) } else emptyList()
        return "visible=$visible/local=${local.flattenToString()}/image=${imageWidth}x$imageHeight/" +
            "tiles=${tiles.size}/visibleTiles=${visibleKeys.count(tiles::containsKey)}/${visibleKeys.size}/" +
            "rangeReady=${visibleKeys.isNotEmpty() && visibleKeys.all(tiles::containsKey)}/pending=${pending?.isActive == true}"
    }
    val hasDecodedImage: Boolean get() {
        if(image == null || width <= 0 || imageWidth <= 0 || imageHeight <= 0) return false
        val actual = autoReadRect
        if(!getLocalVisibleRect(actual) || actual.isEmpty) return false
        val target = (width * zoom()).roundToInt().coerceIn(1,4096).coerceAtMost(imageWidth)
        val sample = LongPagePolicy.widthSample(imageWidth,target)
        val rows = LongPagePolicy.regionRows(imageWidth,sample)
        val scale = width.toDouble()/imageWidth
        val count = ceil(imageHeight.toDouble()/rows).toInt()
        val first = (actual.top/scale/rows).toInt().coerceIn(0,count-1)
        val last = ((actual.bottom-1).coerceAtLeast(0)/scale/rows).toInt().coerceIn(first,count-1)
        return (first..last).all { tiles.containsKey(Key(sample,target,it)) }
    }

    /** Returns the contiguous decoded edge reachable from the viewport, or tile zero offscreen. */
    fun autoScrollDecodedExtent(): Long? {
        if (image == null || width <= 0 || height <= 0 || imageWidth <= 0 || imageHeight <= 0) return null
        val target = (width * zoom()).roundToInt().coerceIn(1, 4096).coerceAtMost(imageWidth)
        val sample = LongPagePolicy.widthSample(imageWidth, target)
        val rows = LongPagePolicy.regionRows(imageWidth, sample)
        val scale = width.toDouble() / imageWidth
        val count = ceil(imageHeight.toDouble() / rows).toInt()
        val visibleRect = autoReadRect
        val isVisible = getLocalVisibleRect(visibleRect) && !visibleRect.isEmpty
        val first = if (isVisible) (visibleRect.top / scale / rows).toInt().coerceIn(0, count - 1) else 0
        val last = if (isVisible) ((visibleRect.bottom - 1).coerceAtLeast(0) / scale / rows).toInt().coerceIn(first, count - 1) else 0
        if ((first..last).any { !tiles.containsKey(Key(sample, target, it)) }) return null
        var edge = last
        while (edge + 1 < count && tiles.containsKey(Key(sample, target, edge + 1))) edge++
        val decoded = tiles[Key(sample, target, edge)] ?: return null
        val bottom = (decoded.bottom * scale).roundToInt().coerceIn(0, height)
        return (bottom.toLong() shl 1) or (if (decoded.bottom >= imageHeight) 1L else 0L)
    }

    /** Decode one leading region for the single adjacent page beyond the visible frontier. */
    fun requestAutoScrollFirstRegion() {
        val selected = image ?: return
        if (released || width <= 0 || imageWidth <= 0 || imageHeight <= 0) return
        val target = (width * zoom()).roundToInt().coerceIn(1, 4096).coerceAtMost(imageWidth)
        val sample = LongPagePolicy.widthSample(imageWidth, target)
        val rows = LongPagePolicy.regionRows(imageWidth, sample)
        val key = Key(sample, target, 0)
        if (tiles.containsKey(key) || pending?.isActive == true) return
        val token = generation
        val request = ++requestSerial
        wanted = listOf(key)
        needed = listOf(key)
        pending = scope.launch {
            try {
                ensureActive()
                val bottom = minOf(rows.toLong(), imageHeight.toLong()).toInt()
                val bitmap = selected.region(0, bottom, sample, target) ?: return@launch
                if (!isActive || token != generation || image !== selected) { bitmap.recycle(); return@launch }
                if (bitmap.allocationByteCount > 8 * 1024 * 1024) {
                    bitmap.recycle(); error("Image tile exceeded the decoding bound.")
                }
                tiles[key] = Tile(bitmap, 0, bottom)
                trim()
                onRegionDecoded?.invoke()
                invalidate()
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Throwable) { if (token == generation) { val failure = onFailure; release(); failure?.invoke(error) } }
            finally { if (token == generation && request == requestSerial) pending = null }
        }
    }

    fun setImage(value: ImageDecoder.RegionImage) {
        release(); image = value; imageWidth = value.width; imageHeight = value.height
        released = false; visibleRangeReady = false
        requestLayout(); invalidate()
    }
    fun recycle() {
        release(); released = false; imageWidth = 0; imageHeight = 0
        visibleRangeReady = false
        onReload = null; onReady = null; onRegionDecoded = null; onFailure = null; requestLayout()
    }
    private fun release() {
        generation++; requestSerial++; pending?.cancel(); pending = null
        val old = image; image = null
        if (old != null) scope.launch(Dispatchers.Default) { old.close() }
        tiles.values.forEach { it.bitmap.recycle() }; tiles.clear()
        needed = emptyList(); wanted = emptyList()
    }
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (image != null) { release(); released = true }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (released) { released = false; onReload?.invoke() }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (imageWidth > 0 && w > 0) LongPagePolicy.displayedHeight(imageWidth, imageHeight, w) else 0
        setMeasuredDimension(w, h)
    }
    private fun zoom(): Float {
        var ancestor = parent
        while (ancestor is View) {
            if (ancestor is WebtoonRecyclerView) return ancestor.scaleX.coerceIn(.5f, 5f)
            ancestor = ancestor.parent
        }
        return 1f
    }
    override fun onDraw(canvas: Canvas) {
        val selected = image ?: return
        if (width <= 0 || !getLocalVisibleRect(clip)) return
        val scale = width.toDouble() / imageWidth
        val target = (width * zoom()).roundToInt().coerceIn(1, 4096).coerceAtMost(imageWidth)
        val sample = LongPagePolicy.widthSample(imageWidth, target)
        val rows = LongPagePolicy.regionRows(imageWidth, sample)
        val count = ceil(imageHeight.toDouble() / rows).toInt()
        val first = (clip.top / scale / rows).toInt().coerceIn(0, count - 1)
        val last = ((clip.bottom - 1).coerceAtLeast(0) / scale / rows).toInt().coerceIn(first, count - 1)
        val visible = (first..last).map { Key(sample, target, it) }
        val requests = visible + listOf(first - 1, last + 1).filter { it in 0 until count }.map { Key(sample, target, it) }
        needed = visible
        val currentlyReady = visible.all(tiles::containsKey)
        if (currentlyReady && !visibleRangeReady) onReady?.invoke()
        visibleRangeReady = currentlyReady
        // Draw older resolution as a fallback while zoom-specific regions are loading.
        if (!visible.all(tiles::containsKey)) {
            for ((key, tile) in tiles) if (key !in visible) draw(canvas, tile, scale)
        }
        visible.forEach { key -> tiles[key]?.let { draw(canvas, it, scale) } }
        if (wanted != requests || currentRows != rows || pending == null) {
            pending?.cancel(); wanted = requests; currentRows = rows
            val token = generation
            val request = ++requestSerial
            val missing = requests.filterNot(tiles::containsKey)
            if (missing.isNotEmpty()) pending = scope.launch {
                try {
                    for (key in missing) {
                        ensureActive()
                        var top = key.index * rows
                        var bottom = minOf(top.toLong() + rows, imageHeight.toLong()).toInt()
                        if (bottom - top < sample) top = (bottom - sample).coerceAtLeast(0)
                        val bitmap = selected.region(top, bottom, sample, target) ?: return@launch
                        if (!isActive || token != generation || image !== selected) { bitmap.recycle(); return@launch }
                        if (bitmap.allocationByteCount > 8 * 1024 * 1024) {
                            bitmap.recycle(); error("Image tile exceeded the decoding bound.")
                        }
                        tiles[key] = Tile(bitmap, top, bottom)
                        trim()
                        onRegionDecoded?.invoke()
                        invalidate()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (error: Throwable) { if (token == generation) { val failure = onFailure; release(); failure?.invoke(error) } }
                finally { if (token == generation && request == requestSerial) pending = null }
            }
        }
    }
    private fun draw(canvas: Canvas, tile: Tile, scale: Double) {
        if (tile.bitmap.isRecycled) return
        val top = (tile.top * scale).roundToInt(); val bottom = (tile.bottom * scale).roundToInt()
        if (bottom <= clip.top || top >= clip.bottom) return
        destination.set(0, top, width, bottom)
        canvas.drawBitmap(tile.bitmap, null, destination, brush)
    }
    private fun trim() {
        val iterator = tiles.entries.iterator()
        while (iterator.hasNext() && cachedBitmapBytes > MAX_CACHE_BYTES) {
            val (key, tile) = iterator.next()
            if (key !in needed) { iterator.remove(); tile.bitmap.recycle() }
        }
        check(cachedBitmapBytes <= MAX_CACHE_BYTES) { "Visible image regions exceed the supported viewport budget." }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean = false
    companion object { const val MAX_CACHE_BYTES = 64L * 1024 * 1024 }
}
