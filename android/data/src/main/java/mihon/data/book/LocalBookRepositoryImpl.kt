package mihon.data.book

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import mihon.domain.book.BookBookmark
import mihon.domain.book.LocalBook
import mihon.domain.book.LocalBookFormat
import mihon.domain.book.LocalBookRepository
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class LocalBookRepositoryImpl(private val database: Database) : LocalBookRepository {
    override fun observeBooks(): Flow<List<LocalBook>> = database.local_bookQueries.books(::mapBook).subscribeToList(Dispatchers.IO)
    override suspend fun get(id: String): LocalBook? = database.local_bookQueries.book(id, ::mapBook).awaitAsOneOrNull()
    override suspend fun getByFingerprint(fingerprint: String): LocalBook? =
        database.local_bookQueries.byFingerprint(fingerprint, ::mapBook).awaitAsOneOrNull()
    override suspend fun insert(book: LocalBook) {
        with(book) {
            database.local_bookQueries.insertBook(id, title, author, format.name, assetFile, originalFile,
                originalName, fingerprint, fileBytes, coverFile, addedAt, updatedAt, locator, progression)
        }
    }
    override suspend fun saveProgress(id: String, locator: String, progression: Double, updatedAt: Long) {
        require(progression.isFinite())
        database.local_bookQueries.saveProgress(locator, progression.coerceIn(0.0, 1.0), updatedAt, id)
    }
    override suspend fun restoreMetadata(book: LocalBook) {
        require(book.progression.isFinite() && book.progression in 0.0..1.0)
        database.local_bookQueries.restoreMetadata(
            book.title.take(500), book.author?.take(500), book.originalName.take(500),
            book.fileBytes.coerceIn(1, 268_435_456), book.addedAt, book.updatedAt,
            book.locator?.take(20_000), book.progression, book.id,
        )
    }
    override suspend fun bookmarks(bookId: String): List<BookBookmark> =
        database.book_bookmarkQueries.bookmarks(bookId, ::BookBookmark).awaitAsList()
    override suspend fun addBookmark(bookmark: BookBookmark) {
        with(bookmark) { database.book_bookmarkQueries.addBookmark(id, bookId, locator, label, createdAt) }
    }
    override suspend fun removeBookmark(id: String) { database.book_bookmarkQueries.removeBookmark(id) }
    override suspend fun rename(id: String, title: String) {
        require(title.isNotBlank())
        database.local_bookQueries.renameBook(title.trim().take(500), id)
    }
    override suspend fun remove(id: String) { database.local_bookQueries.removeBook(id) }
    private fun mapBook(id: String, title: String, author: String?, format: String, asset_file: String,
        original_file: String, original_name: String, fingerprint: String, file_bytes: Long,
        cover_file: String?, added_at: Long, updated_at: Long, locator: String?, progression: Double) =
        LocalBook(id, title, author, LocalBookFormat.valueOf(format), asset_file, original_file, original_name,
            fingerprint, file_bytes, cover_file, added_at, updated_at, locator, progression)
}
