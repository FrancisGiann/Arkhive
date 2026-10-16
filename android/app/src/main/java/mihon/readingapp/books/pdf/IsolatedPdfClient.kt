package mihon.readingapp.books.pdf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.Closeable

/** The app only supplies explicit descriptors; the renderer cannot browse app files or use network. */
class IsolatedPdfClient private constructor(
    private val context: Context,
    private val connection: ServiceConnection,
    private val engine: IPdfEngine,
    private val session: Long,
    val pageCount: Int,
    val rendererUid: Int,
) : Closeable {
    private val temporaryFiles = java.util.concurrent.ConcurrentHashMap.newKeySet<File>()
    suspend fun render(page: Int, width: Int): File = withContext(Dispatchers.IO) {
        require(page in 0 until pageCount)
        val dir = File(context.cacheDir, "arkhive-pdf").apply { mkdirs() }
        val file = File.createTempFile("page-$page-", ".png", dir)
        temporaryFiles.add(file)
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE).use {
                engine.render(session, page, width, it)
            }
            require(file.length() in 1..32_000_000) { "The PDF page could not be rendered." }
            file
        } catch (error: Exception) { file.delete(); throw error }
    }
    override fun close() {
        runCatching { engine.closeDocument(session) }
        runCatching { context.unbindService(connection) }
        temporaryFiles.forEach { it.delete() }; temporaryFiles.clear()
    }
    companion object {
        suspend fun open(context: Context, file: File): IsolatedPdfClient {
            val app = context.applicationContext
            val ready = CompletableDeferred<IPdfEngine>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    ready.complete(IPdfEngine.Stub.asInterface(binder))
                }
                override fun onServiceDisconnected(name: ComponentName) {
                    if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException("PDF renderer stopped."))
                }
                override fun onNullBinding(name: ComponentName) {
                    ready.completeExceptionally(IllegalStateException("PDF renderer unavailable."))
                }
                override fun onBindingDied(name: ComponentName) {
                    if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException("PDF renderer stopped."))
                }
            }
            var bound = false
            try {
                withContext(Dispatchers.Main) {
                    bound = app.bindService(Intent(app, IsolatedPdfService::class.java), connection, Context.BIND_AUTO_CREATE)
                    check(bound) { "PDF renderer unavailable." }
                }
                val engine = withTimeout(15_000) { ready.await() }
                return withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                        val session = engine.openDocument(it)
                        IsolatedPdfClient(app, connection, engine, session, engine.pageCount(session), engine.rendererUid())
                    }
                }
            } catch (error: Exception) {
                if (bound) runCatching { app.unbindService(connection) }
                throw error
            }
        }
    }
}
