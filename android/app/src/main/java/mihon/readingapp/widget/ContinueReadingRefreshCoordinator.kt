package mihon.readingapp.widget

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes full widget updates and discards selections changed while their cover was loading. */
internal class ContinueReadingRefreshCoordinator<Selection, Cover> {
    private val mutex = Mutex()

    suspend fun refresh(
        select: suspend () -> Selection,
        loadCover: suspend (Selection) -> Cover?,
        publish: suspend (Selection, Cover?) -> Unit,
        sameSelection: (Selection, Selection) -> Boolean = { first, second -> first == second },
    ) = mutex.withLock {
        // A burst of reading updates can change the selection while Coil is suspended.
        // The observer will request another refresh if it keeps changing.
        repeat(3) {
            val selection = select()
            val cover = try {
                loadCover(selection)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (sameSelection(selection, select())) {
                publish(selection, cover)
                return@withLock
            }
        }
    }
}
