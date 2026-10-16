package mihon.readingapp.fixtures

import android.content.Context
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Original geometric illustrations generated on demand; bounded PNG cache, no DNS/network fallback. */
internal object OriginalDemoImages {
    private val controlled = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    internal fun controlledImage(url: String, bytes: ByteArray?) {
        check(eu.kanade.tachiyomi.BuildConfig.DEBUG)
        require(url.startsWith(OriginalDemoCatalog.BASE_URL + "/pages/"))
        if (bytes == null) controlled.remove(url) else { require(bytes.size <= 2_000_000 && controlled.size < 4); controlled[url] = bytes }
    }
    private var artworkAssets: AssetManager? = null
    fun configure(context: Context) { artworkAssets = context.applicationContext.assets }
    private const val CACHE_BYTES = 4 * 1024 * 1024
    private val cache = object : LruCache<String, ByteArray>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    val client = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val request = chain.request()
            if (request.url.host != OriginalDemoCatalog.HOST) throw IOException("Offline fixture rejects outside hosts")
            if (request.method != "GET") throw IOException("Offline fixture only supports GET")
            val bytes = controlled[request.url.toString()] ?: synchronized(cache) {
                cache[request.url.encodedPath] ?: render(request.url.encodedPath).also {
                    cache.put(request.url.encodedPath, it)
                }
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("Original offline demo")
                .header("Content-Type", "image/png")
                .header("Cache-Control", "public, max-age=31536000, immutable")
                .body(bytes.toResponseBody("image/png".toMediaType()))
                .build()
        })
        .build()

    private fun render(path: String): ByteArray {
        val parts = path.trim('/').split('/')
        val cover = parts.size == 2 && parts[0] == "covers" && parts[1].endsWith(".png")
        val page = parts.size == 4 && parts[0] == "pages" && parts[3].endsWith(".png")
        require(cover || page) { "Invalid original image path" }
        val title = OriginalDemoCatalog.title(if (cover) parts[1].removeSuffix(".png") else parts[1])
        val pageNumber = if (cover) 0 else parts[3].removeSuffix(".png").toInt()
        val chapter = if (cover) 0 else parts[2].toInt()
        if (!cover) {
            require(chapter in 1..OriginalDemoCatalog.CHAPTER_COUNT)
            require(pageNumber in 1..OriginalDemoCatalog.PAGES_PER_CHAPTER)
        }
        val width = if (cover) 480 else 720
        val height = when {
            cover -> 720
            title.format == OriginalDemoCatalog.Format.WEBTOON -> 2160
            else -> 1080
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val artwork = artworkAssets?.let { assets -> runCatching {
                val name = if (cover) title.slug else if (title.format == OriginalDemoCatalog.Format.WEBTOON) "webtoon-page" else "manga-page"
                assets.open("arkhive-art/$name.png").use(BitmapFactory::decodeStream)
            }.getOrNull() }
            if (artwork != null) {
                try {
                    canvas.drawColor(0xFFF4F5F3.toInt())
                    val brush=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    if(!cover && title.format==OriginalDemoCatalog.Format.WEBTOON) {
                        canvas.drawColor(0xFFEBE5F0.toInt())
                        val scaled=artwork.height.toFloat()*width/artwork.width
                        var top=0f
                        while(top<height-36f) { canvas.drawBitmap(artwork,null,RectF(0f,top,width.toFloat(),top+scaled),brush);top+=scaled }
                    } else canvas.drawBitmap(artwork,null,RectF(0f,0f,width.toFloat(),height.toFloat()-if(cover) 0f else 36f),brush)
                    if (!cover) canvas.drawText("${title.name} · Ch $chapter · Page $pageNumber · ORIGINAL", 20f, height-12f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xFF1D2330.toInt();textSize=18f })
                } finally { artwork.recycle() }
            } else if (cover) drawCover(canvas, title) else drawPage(canvas, title, chapter, pageNumber, height)
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawCover(canvas: Canvas, title: OriginalDemoCatalog.Title) {
        canvas.drawColor(0xFF10101E.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = title.accent
        canvas.drawCircle(350f, 220f, 195f, paint)
        paint.color = 0xFF292440.toInt()
        val slash = Path().apply {
            moveTo(0f, 420f)
            lineTo(480f, 110f)
            lineTo(480f, 340f)
            lineTo(0f, 650f)
            close()
        }
        canvas.drawPath(slash, paint)
        drawSilhouette(canvas, 220f, 300f, title.accent, 1.35f)
        paint.color = Color.WHITE
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        paint.textSize = 46f
        title.name.split(' ').forEachIndexed { i, word -> canvas.drawText(word.uppercase(), 30f, 518f + i * 53, paint) }
        paint.textSize = 19f
        canvas.drawText(title.format.label.uppercase(), 32f, 660f, paint)
        paint.textSize = 16f
        canvas.drawText("ORIGINAL OFFLINE DEMO", 32f, 692f, paint)
    }

    private fun drawPage(canvas: Canvas, title: OriginalDemoCatalog.Title, chapter: Int, page: Int, height: Int) {
        canvas.drawColor(0xFFF4F1ED.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val vertical = title.format == OriginalDemoCatalog.Format.WEBTOON
        val panels = if (vertical) 3 else 2
        val gap = if (vertical) 100f else 34f
        val panelHeight = (height - 120f - gap * (panels - 1)) / panels
        repeat(panels) { i ->
            val top = 36f + i * (panelHeight + gap)
            paint.color = if ((i + page) % 2 == 0) 0xFF191725.toInt() else 0xFF242038.toInt()
            canvas.drawRect(28f, top, 692f, top + panelHeight, paint)
            paint.color = title.accent
            canvas.drawCircle(530f - i * 85f, top + 118f, 100f, paint)
            // Original angular skyline, motion lines, and a simple geometric courier silhouette.
            paint.color = 0xFF454055.toInt()
            repeat(8) { building ->
                val x = 35f + building * 88f
                val rise = 80f + (building * 31 + page * 17 + i * 23) % 145
                canvas.drawRect(x, top + panelHeight - rise, x + 65f, top + panelHeight, paint)
            }
            drawSilhouette(canvas, 235f + i * 55f, top + panelHeight * 0.44f, title.accent, 1f)
            paint.color = Color.WHITE
            canvas.drawRoundRect(RectF(380f, top + 215f, 665f, top + 305f), 22f, 22f, paint)
            paint.color = 0xFF11111A.toInt()
            paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            paint.textSize = 21f
            canvas.drawText(if (i == 0) "A new route awaits." else "One panel at a time.", 395f, top + 255f, paint)
            paint.textSize = 17f
            canvas.drawText("Original synthetic illustration", 395f, top + 283f, paint)
        }
        paint.color = 0xFF191725.toInt()
        paint.textSize = 20f
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        canvas.drawText("${title.name} · Ch $chapter · Page $page · ORIGINAL DEMO", 30f, height - 23f, paint)
    }

    private fun drawSilhouette(canvas: Canvas, x: Float, y: Float, accent: Int, scale: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFF0B0B14.toInt()
        canvas.drawCircle(0f, 0f, 34f, paint)
        val body = Path().apply {
            moveTo(-32f, 29f)
            lineTo(35f, 32f)
            lineTo(65f, 176f)
            lineTo(-60f, 178f)
            close()
        }
        canvas.drawPath(body, paint)
        canvas.drawRect(-35f, 168f, -11f, 244f, paint)
        canvas.drawRect(12f, 168f, 37f, 244f, paint)
        paint.color = accent
        canvas.drawRect(-29f, 45f, 38f, 59f, paint)
        canvas.restore()
    }
}
