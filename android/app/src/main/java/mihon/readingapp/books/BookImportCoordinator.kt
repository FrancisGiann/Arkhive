package mihon.readingapp.books

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mihon.core.metro.AppCoroutineScope
import mihon.readingapp.library.LibraryShelfMembership

sealed interface BookImportState {
    data object Idle : BookImportState
    data class Running(
        val fileName: String,
        val fileIndex: Int,
        val fileCount: Int,
        val stage: String,
        val bytesCopied: Long? = null,
        val totalBytes: Long? = null,
    ) : BookImportState
    data class Complete(val books: List<ImportedBookResult>, val failures: List<String>) : BookImportState
}

data class ImportedBookResult(val id: String, val title: String, val alreadyImported: Boolean)

/** Process-lifetime import owner so progress remains visible while the user navigates around the app. */
@Inject
@SingleIn(AppScope::class)
class BookImportCoordinator(
    private val context: Context,
    private val importer: LocalBookImporter,
    private val membership: LibraryShelfMembership,
    @AppCoroutineScope private val appScope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<BookImportState>(BookImportState.Idle)
    val state = mutableState.asStateFlow()
    private var activeJob: Job? = null
    private var retryUris: List<Uri> = emptyList()

    @Synchronized fun start(uris: List<Uri>): Boolean {
        if (uris.isEmpty() || activeJob?.isActive == true) return false
        retryUris = uris.toList()
        mutableState.value = BookImportState.Running(
            fileName = uris.first().lastPathSegment?.substringAfterLast('/')?.take(120) ?: "Selected file",
            fileIndex = 1,
            fileCount = uris.size,
            stage = "Waiting to start",
        )
        activeJob = appScope.launch {
            val results = mutableListOf<ImportedBookResult>()
            val failures = mutableListOf<String>()
            retryUris.forEachIndexed { index, uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                importer.import(uri) { progress ->
                    mutableState.value = BookImportState.Running(
                        progress.fileName,
                        index + 1,
                        retryUris.size,
                        progress.stage,
                        progress.bytesCopied,
                        progress.totalBytes,
                    )
                }.fold(
                    onSuccess = { imported ->
                        membership.restoreBook(imported.book.id)
                        results += ImportedBookResult(imported.book.id, imported.book.title, imported.alreadyImported)
                    },
                    onFailure = { error ->
                        val name = uri.lastPathSegment?.substringAfterLast('/')?.take(120) ?: "Selected file"
                        failures += "$name: ${error.message ?: "Import failed"}"
                    },
                )
            }
            mutableState.value = BookImportState.Complete(results, failures)
        }
        return true
    }

    @Synchronized fun retry(): Boolean = start(retryUris)
    fun dismiss() {
        if (activeJob?.isActive != true) mutableState.value = BookImportState.Idle
    }
}
