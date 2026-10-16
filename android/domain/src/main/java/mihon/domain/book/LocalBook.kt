package mihon.domain.book

import kotlinx.coroutines.flow.Flow

enum class LocalBookFormat { EPUB, PDF, TEXT }

data class LocalBook(
    val id: String,
    val title: String,
    val author: String?,
    val format: LocalBookFormat,
    val assetFile: String,
    val originalFile: String,
    val originalName: String,
    val fingerprint: String,
    val fileBytes: Long,
    val coverFile: String?,
    val addedAt: Long,
    val updatedAt: Long,
    val locator: String?,
    val progression: Double,
)

data class BookBookmark(val id: String, val bookId: String, val locator: String, val label: String, val createdAt: Long)

interface LocalBookRepository {
    fun observeBooks(): Flow<List<LocalBook>>
    suspend fun get(id: String): LocalBook?
    suspend fun getByFingerprint(fingerprint: String): LocalBook?
    suspend fun insert(book: LocalBook)
    suspend fun saveProgress(id: String, locator: String, progression: Double, updatedAt: Long)
    /** Restore metadata without changing Arkhive-owned asset paths or deleting local files. */
    suspend fun restoreMetadata(book: LocalBook)
    suspend fun bookmarks(bookId: String): List<BookBookmark>
    suspend fun addBookmark(bookmark: BookBookmark)
    suspend fun removeBookmark(id: String)
    suspend fun rename(id: String, title: String)
    suspend fun remove(id: String)
}
