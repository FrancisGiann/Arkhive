package mihon.readingapp.books

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import mihon.core.metro.AppCoroutineScope
import mihon.domain.book.LocalBookRepository
import mihon.readingapp.books.pdf.IsolatedPdfClient
import org.readium.r2.shared.publication.Publication

/** FIFO writes owned by the app, so closing an activity cannot lose its final locator. */
@Inject
@SingleIn(AppScope::class)
class BookProgressWriter(@AppCoroutineScope private val scope: CoroutineScope,
    private val repository: LocalBookRepository) {
    private data class Update(val id: String, val locator: String, val progress: Double, val time: Long)
    private val updates = Channel<Update>(Channel.UNLIMITED)
    init {
        scope.launch(Dispatchers.IO) {
            for (update in updates) {
                try {
                    repository.saveProgress(update.id, update.locator, update.progress, update.time)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep the writer alive for subsequent saves; the asset is untouched. */ }
            }
        }
    }
    fun save(id: String, locator: String, progress: Double) {
        updates.trySend(Update(id, locator, progress, System.currentTimeMillis()))
    }
    fun release(pdf: IsolatedPdfClient?, epub: Publication?) {
        scope.launch(Dispatchers.IO) { runCatching { pdf?.close() }; runCatching { epub?.close() } }
    }
}
