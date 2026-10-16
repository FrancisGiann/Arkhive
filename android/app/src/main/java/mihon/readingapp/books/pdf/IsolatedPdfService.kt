package mihon.readingapp.books.pdf

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import java.util.concurrent.atomic.AtomicLong

/** Native PDF parsing/rendering runs under a distinct, permissionless isolated UID. */
class IsolatedPdfService : Service() {
    private val lock = Any()
    private val ids = AtomicLong()
    private val documents = mutableMapOf<Long, PdfRenderer>()
    private val binder = object : IPdfEngine.Stub() {
        override fun openDocument(document: ParcelFileDescriptor): Long = synchronized(lock) {
            check(documents.size < 2) { "Close an existing PDF session first." }
            val renderer = try { PdfRenderer(document) } catch (error: Exception) { document.close(); throw error }
            if (renderer.pageCount !in 1..20_000) { renderer.close(); error("Unsupported PDF page count.") }
            ids.incrementAndGet().also { documents[it] = renderer }
        }
        override fun pageCount(session: Long): Int = synchronized(lock) { requireNotNull(documents[session]).pageCount }
        override fun rendererUid(): Int = Process.myUid()
        override fun closeDocument(session: Long) { synchronized(lock) { documents.remove(session)?.close() } }
        override fun render(session: Long, pageIndex: Int, width: Int, output: ParcelFileDescriptor) {
            synchronized(lock) {
                val renderer = requireNotNull(documents[session])
                require(pageIndex in 0 until renderer.pageCount)
                ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream ->
                    renderer.openPage(pageIndex).use { page ->
                        val requestedWidth = width.coerceIn(256, 2048)
                        val scale = minOf(requestedWidth.toDouble() / page.width,
                            kotlin.math.sqrt(4_000_000.0 / (page.width.toDouble() * page.height)))
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                        } finally { bitmap.recycle() }
                    }
                }
            }
        }
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onUnbind(intent: Intent): Boolean { release(); return false }
    override fun onDestroy() { release(); super.onDestroy() }
    private fun release() { synchronized(lock) { documents.values.forEach { it.close() }; documents.clear() } }
}
