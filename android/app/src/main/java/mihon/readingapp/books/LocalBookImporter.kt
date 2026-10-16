package mihon.readingapp.books

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mihon.domain.book.LocalBook
import mihon.domain.book.LocalBookFormat
import mihon.domain.book.LocalBookRepository
import mihon.readingapp.books.pdf.IsolatedPdfClient
import org.readium.r2.shared.util.getOrElse
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class BookImportResult(val book: LocalBook, val alreadyImported: Boolean)
data class BookImportProgress(
    val fileName: String,
    val stage: String,
    val bytesCopied: Long? = null,
    val totalBytes: Long? = null,
)

@Inject
@SingleIn(AppScope::class)
class LocalBookImporter(private val context: Context, private val repository: LocalBookRepository) {
    private val mutex = Mutex()
    val directory: File get() = File(context.filesDir, "arkhive-books").apply { mkdirs() }
    fun assetFile(book: LocalBook): File = ownedFile(book.assetFile)
    fun coverFile(book: LocalBook): File? = book.coverFile?.let(::ownedFile)?.takeIf { it.isFile }
    suspend fun ensureCurrentAsset(book: LocalBook): File = withContext(Dispatchers.IO) {
        mutex.withLock { ensureCurrentAssetLocked(book) }
    }
    suspend fun removePrivateCopy(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val book = repository.get(id) ?: return@withLock
            repository.remove(id)
            setOf(book.assetFile, book.originalFile, book.coverFile).filterNotNull()
                .forEach { ownedFile(it).delete() }
        }
    }
    suspend fun deletePrivateAssets(id: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val book = repository.get(id) ?: return@withLock false
            setOf(book.assetFile, book.originalFile, book.coverFile).filterNotNull()
                .map(::ownedFile).fold(false) { deleted, file -> file.delete() || deleted }
        }
    }
    private fun ownedFile(name: String): File {
        require(Regex("[a-f0-9]{64}\\.(read\\.)?(epub|pdf|txt|cover\\.png)").matches(name)) { "Invalid private book asset." }
        return File(directory, name)
    }

    suspend fun import(uri: Uri, progress: (BookImportProgress) -> Unit = {}): Result<BookImportResult> = try {
        withContext(Dispatchers.IO) { mutex.withLock { Result.success(importFile(uri, progress)) } }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) { Result.failure(error) }

    private suspend fun importFile(uri: Uri, progress: (BookImportProgress) -> Unit): BookImportResult {
        require(uri.scheme == "content" || uri.scheme == "file") { "Choose a local EPUB, PDF or text file." }
        var reportedSize: Long? = null
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                reportedSize = sizeIndex.takeIf { index -> index >= 0 && !it.isNull(index) }?.let(it::getLong)?.takeIf { size -> size > 0 }
                it.getString(0)
            } else null
        }?.take(500) ?: uri.lastPathSegment?.substringAfterLast('/')?.take(500) ?: "Imported book"
        progress(BookImportProgress(name, "Copying into Arkhive", 0, reportedSize))
        val stage = File.createTempFile("book-import-", ".part", directory)
        var original: File? = null
        var prepared: File? = null
        var cover: File? = null
        var committed = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(stage).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var count = 0L
                    var nextProgress = 256L * 1024
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        require(count <= BookPackagePolicy.MAX_FILE_BYTES) { "This file exceeds the 256 MiB import limit." }
                        output.write(buffer, 0, read); digest.update(buffer, 0, read)
                        if (count >= nextProgress || count == reportedSize) {
                            progress(BookImportProgress(name, "Copying into Arkhive", count, reportedSize))
                            nextProgress = count + 256L * 1024
                        }
                    }
                    output.fd.sync()
                }
            } ?: error("This document could not be opened. Select it again in Android's file picker.")
            require(stage.length() > 0) { "This document is empty." }
            progress(BookImportProgress(name, "Checking for an existing copy"))
            val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
            repository.getByFingerprint(fingerprint)?.let { existing ->
                val existingOriginal = ownedFile(existing.originalFile)
                if (!existingOriginal.isFile) {
                    move(stage, existingOriginal)
                    original = existingOriginal
                }
                ensureCurrentAssetLocked(existing)
                progress(BookImportProgress(name, "Opening the existing library item"))
                committed = true
                return BookImportResult(existing, true)
            }
            val prefix = stage.inputStream().use { input -> ByteArray(16).also { input.read(it) } }
            val format = when {
                prefix.take(5).toByteArray().toString(Charsets.US_ASCII) == "%PDF-" -> LocalBookFormat.PDF
                prefix[0] == 'P'.code.toByte() && prefix[1] == 'K'.code.toByte() -> LocalBookFormat.EPUB
                name.endsWith(".txt", true) || context.contentResolver.getType(uri) == "text/plain" -> LocalBookFormat.TEXT
                else -> error("Supported imports are EPUB, PDF and UTF-8/UTF-16 text. Comic archives use the manga Local source.")
            }
            val extension = when (format) { LocalBookFormat.EPUB -> "epub"; LocalBookFormat.PDF -> "pdf"; LocalBookFormat.TEXT -> "txt" }
            original = ownedFile("$fingerprint.$extension")
            move(stage, original)
            progress(BookImportProgress(name, if (format == LocalBookFormat.PDF) "Preparing PDF preview" else "Preparing safe offline pages"))
            val now = System.currentTimeMillis()
            var title = name.substringBeforeLast('.', name).trim().ifEmpty { "Imported book" }.take(500)
            var author: String? = null
            var asset = original
            cover = ownedFile("$fingerprint.cover.png")
            if (format == LocalBookFormat.PDF) {
                val pdf = IsolatedPdfClient.open(context, original)
                try {
                    require(pdf.pageCount > 0)
                    val page = pdf.render(0, 360)
                    try { page.copyTo(cover, overwrite = true) } finally { page.delete() }
                } finally { pdf.close() }
            } else {
                val temp = File.createTempFile("epub-ready-", ".part", directory)
                try {
                    if (format == LocalBookFormat.TEXT) {
                        require(original.length() <= BookPackagePolicy.MAX_TEXT_BYTES) { "Text files are limited to 16 MiB." }
                        BookPackagePolicy.textToEpub(BookPackagePolicy.decodeText(original.readBytes()), title, fingerprint, temp)
                    } else BookPackagePolicy.prepareEpub(original, temp)
                    prepared = ownedFile("$fingerprint.read.epub")
                    move(temp, prepared)
                    asset = prepared
                    progress(BookImportProgress(name, "Reading title and cover"))
                    val publication = LocalEpubEngine(context).open(prepared)
                    try {
                        title = publication.metadata.title?.trim()?.takeIf { it.isNotEmpty() }?.take(500) ?: title
                        author = publication.metadata.authors.joinToString { it.name }.trim().takeIf { it.isNotEmpty() }?.take(500)
                        val link = publication.linksWithRel("cover").firstOrNull()
                        val bytes = link?.let { publication.get(it)?.read()?.getOrElse { null } }
                        if (bytes != null && bytes.size <= BookPackagePolicy.MAX_ENTRY_BYTES) {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                                var sample = 1
                                while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                                bitmap?.let { b -> try { cover.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { b.recycle() } }
                            }
                        }
                    } finally { publication.close() }
                } finally { temp.delete() }
            }
            coroutineContext.ensureActive()
            val book = LocalBook(fingerprint, title, author, format, asset.name, original.name, name, fingerprint,
                original.length(), cover.name.takeIf { cover.isFile }, now, now, null, 0.0)
            progress(BookImportProgress(name, "Adding to Library"))
            withContext(NonCancellable) {
                repository.insert(book)
                committed = true
            }
            return BookImportResult(book, false)
        } finally {
            stage.delete()
            if (!committed) { original?.delete(); prepared?.delete(); cover?.delete() }
        }
    }
    private fun move(source: File, destination: File) {
        try { Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun ensureCurrentAssetLocked(book: LocalBook): File {
        val asset = ownedFile(book.assetFile)
        if (book.format == LocalBookFormat.PDF) {
            require(asset.isFile && asset.length() in 1..BookPackagePolicy.MAX_FILE_BYTES) {
                "The private PDF copy is missing. Select the original file again to restore it."
            }
            return asset
        }
        if (asset.isFile && BookPackagePolicy.isCurrentPrepared(asset)) return asset
        val original = ownedFile(book.originalFile)
        require(original.isFile && original.length() in 1..BookPackagePolicy.MAX_FILE_BYTES) {
            "The private book copy is missing. Select the original file again to restore it."
        }
        val temp = File.createTempFile("book-repair-", ".part", directory)
        try {
            when (book.format) {
                LocalBookFormat.EPUB -> BookPackagePolicy.prepareEpub(original, temp)
                LocalBookFormat.TEXT -> {
                    require(original.length() <= BookPackagePolicy.MAX_TEXT_BYTES) { "Text files are limited to 16 MiB." }
                    BookPackagePolicy.textToEpub(BookPackagePolicy.decodeText(original.readBytes()), book.title, book.id, temp)
                }
                LocalBookFormat.PDF -> error("PDF repair should use the retained private copy directly.")
            }
            move(temp, asset)
        } finally {
            temp.delete()
        }
        return asset
    }
}
