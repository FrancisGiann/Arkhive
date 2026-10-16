package mihon.readingapp.novels

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import mihon.app.di.appGraph

/** Network-constrained durable queue; bounded batches stay within Android's background limits. */
class NovelDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repository = applicationContext.appGraph.novelRepository
        repeat(10) {
            currentCoroutineContext().ensureActive()
            if (repository.readingActive) return Result.retry()
            val next = repository.claim() ?: return if (repository.state.value.downloads.any { it.state == "retry" }) Result.retry() else Result.success()
            repository.runDownload(next)
            if (repository.state.value.downloads.any { it.id == next.id && it.state == "retry" }) return Result.retry()
        }
        if(repository.state.value.downloads.any {it.state in setOf("queued","retry","downloading")}) repository.continueBatch()
        return Result.success()
    }
}
