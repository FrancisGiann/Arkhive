package eu.kanade.tachiyomi.data.coil

import android.graphics.Bitmap
import android.graphics.Rect
import mihon.readingapp.readers.LongPagePolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import coil3.Canvas
import coil3.Image
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.bitmapConfig
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.decoder.ImageDecoder
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A [Decoder] that uses built-in [ImageDecoder] to decode images that is not supported by the system.
 */
class ImageDecoder(private val resources: ImageSource, private val options: Options) : Decoder {

    /** Encoded decoder ownership, not a full-height bitmap. The view requests only visible regions. */
    class RegionImage(private val decoder: tachiyomi.decoder.ImageDecoder,
        override val width: Int, override val height: Int) : Image {
        private val lock = Any()
        private var closed = false
        override val size: Long get() = 0 // Never enter Coil's shared bitmap cache.
        override val shareable: Boolean get() = false
        override fun draw(canvas: Canvas) {}
        suspend fun region(top: Int, bottom: Int, sample: Int, targetWidth: Int): Bitmap? {
            val callerJob = currentCoroutineContext()[Job]
            val ownedBitmap = AtomicReference<Bitmap?>(null)
            try {
                val result = stripDecodes.withPermit {
                    synchronized(lock) {
                        if (closed) return@synchronized null
                        callerJob?.ensureActive()
                        val bitmap = decoder.decode(Rect(0, top, width, bottom), sample)
                            ?: error("The image region could not be decoded.")
                        ownedBitmap.set(bitmap)
                        // Native decode itself cannot be interrupted. Recycle its result as soon
                        // as it returns if the viewport request went stale while JNI was running.
                        callerJob?.ensureActive()
                        if (bitmap.width <= targetWidth) bitmap else {
                            val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth,
                                (bitmap.height.toDouble() * targetWidth / bitmap.width).roundToInt().coerceAtLeast(1), true)
                            bitmap.recycle()
                            ownedBitmap.set(scaled)
                            scaled
                        }
                    }
                }
                callerJob?.ensureActive()
                ownedBitmap.set(null)
                return result
            } catch (error: Throwable) {
                ownedBitmap.getAndSet(null)?.takeUnless(Bitmap::isRecycled)?.recycle()
                throw error
            }
        }
        fun close() = synchronized(lock) { if (!closed) { closed = true; decoder.recycle() } }
    }

    override suspend fun decode(): DecodeResult {
        if (options.webtoonStrips) return decodeStrips()

        val decoder = resources.sourceOrNull()?.use {
            ImageDecoder.newInstance(it.inputStream(), options.cropBorders, displayProfile)
        }

        check(decoder != null && decoder.width > 0 && decoder.height > 0) { "Failed to initialize decoder" }

        val srcWidth = decoder.width
        val srcHeight = decoder.height

        val dstWidth = options.size.widthPx(options.scale) { srcWidth }
        val dstHeight = options.size.heightPx(options.scale) { srcHeight }

        val sampleSize = DecodeUtils.calculateInSampleSize(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            dstWidth = dstWidth,
            dstHeight = dstHeight,
            scale = options.scale,
        )

        var bitmap = decoder.decode(sampleSize = sampleSize)
        decoder.recycle()

        check(bitmap != null) { "Failed to decode image" }

        if (options.bitmapConfig == Bitmap.Config.HARDWARE && ImageUtil.canUseHardwareBitmap(bitmap)) {
            val hwBitmap = bitmap.copy(Bitmap.Config.HARDWARE, false)
            if (hwBitmap != null) {
                bitmap.recycle()
                bitmap = hwBitmap
            }
        }

        return DecodeResult(
            image = bitmap.asImage(),
            isSampled = sampleSize > 1,
        )
    }

    /** Parse dimensions once; viewport height never reduces a long page's readable width. */
    private suspend fun decodeStrips(): DecodeResult = stripDecodes.withPermit {
        val decoder = resources.sourceOrNull()?.use {
            ImageDecoder.newInstance(it.inputStream(), options.cropBorders, displayProfile)
        }
        check(decoder != null && decoder.width > 0 && decoder.height > 0) { "Failed to initialize decoder" }
        try {
            val width = decoder.width; val height = decoder.height
            val viewWidth = options.size.widthPx(options.scale) { width }.coerceIn(1, 4096)
            LongPagePolicy.displayedHeight(width, height, viewWidth)
            DecodeResult(image = RegionImage(decoder, width, height), isSampled = false)
        } catch (error: Throwable) { decoder.recycle(); throw error }
    }

    class Factory : Decoder.Factory {

        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            return if (options.webtoonStrips || isApplicable(result.source.source())) {
                ImageDecoder(result.source, options)
            } else {
                null
            }
        }

        private fun isApplicable(source: BufferedSource): Boolean {
            val type = source.peek().inputStream().use {
                ImageUtil.findImageType(it)
            }
            return when (type) {
                ImageUtil.ImageType.AVIF, ImageUtil.ImageType.JXL, ImageUtil.ImageType.HEIF -> true
                else -> false
            }
        }

        override fun equals(other: Any?) = other is Factory

        override fun hashCode() = javaClass.hashCode()
    }

    companion object {
        var displayProfile: ByteArray? = null
    }
}

// One native region at a time; no parallel full-page allocations or uploads.
private val stripDecodes = RegionDecodeGate()

/** Keeps waits cancellable while one native region decode finishes on a worker thread. */
internal class RegionDecodeGate(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val semaphore = Semaphore(1)

    suspend fun <T> withPermit(block: suspend () -> T): T {
        semaphore.acquire()
        try {
            return withContext(NonCancellable + dispatcher) { block() }
        } finally {
            semaphore.release()
        }
    }
}
