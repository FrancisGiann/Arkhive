package mihon.readingapp.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mihon.readingapp.library.ReadingProfile
import mihon.readingapp.library.ReadingRoom
import mihon.readingapp.library.ShelfAssignment
import mihon.readingapp.novels.NovelBackupEntry
import java.security.MessageDigest
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** A metadata-only record for an imported book. It deliberately contains no asset paths or bytes. */
@Serializable
data class UnifiedBookBackup(
    val fingerprint: String,
    val title: String,
    val author: String? = null,
    val format: String,
    val originalName: String,
    val fileBytes: Long,
    val addedAt: Long,
    val updatedAt: Long,
    val locator: String? = null,
    val progression: Double = 0.0,
    val bookmarks: List<UnifiedBookBookmarkBackup> = emptyList(),
    val removedFromLibrary: Boolean = false,
)

@Serializable
data class UnifiedBookBookmarkBackup(
    val id: String,
    val locator: String,
    val label: String,
    val createdAt: Long,
)

/** Settings are keyed by portable identities such as source+URL, never by a database row ID. */
@Serializable
data class UnifiedLibraryBackupSettings(
    val shelves: List<String> = emptyList(),
    val assignments: Map<String, ShelfAssignment> = emptyMap(),
    val profiles: Map<String, ReadingProfile> = emptyMap(),
    val room: ReadingRoom? = null,
    val smartStorageMiB: Int = 256,
    val statsEnabled: Boolean = false,
    val hiddenSeries: Set<String> = emptySet(),
    val removedBookFingerprints: Set<String> = emptySet(),
)

@Serializable
data class UnifiedLocalBackupPayload(
    val native: Backup,
    val novels: List<NovelBackupEntry>,
    val books: List<UnifiedBookBackup>,
    val seriesReferences: Map<String, String>,
    val library: UnifiedLibraryBackupSettings,
)

@Serializable
data class UnifiedLocalBackupEnvelope(
    val schema: Int = SCHEMA,
    val createdAt: Long,
    val payload: UnifiedLocalBackupPayload,
    val sha256: String,
) {
    companion object { const val SCHEMA = 1 }
}

data class UnifiedBackupPreview(
    val token: String,
    val manga: Int,
    val novels: Int,
    val books: Int,
    val shelves: Int,
    val hiddenSeries: Int,
)

enum class UnifiedBackupRestoreMode { MERGE, REPLACE_MEMBERSHIP }

/** MIME types emitted by the supported local EPUB navigator for reflowable resources. */
internal val SAFE_BOOK_LOCATOR_MEDIA_TYPES = setOf("application/xhtml+xml", "text/html")

/** Strict codec: schema, bounded metadata shape and payload digest are checked before restore. */
object UnifiedLocalBackupCodec {
    const val MAX_BYTES = 64 * 1024 * 1024
    private const val MAX_MANGA = 20_000
    private const val MAX_NOVELS = 10_000
    private const val MAX_BOOKS = 10_000
    private const val MAX_CHAPTERS = 200_000
    private const val MAX_BOOKMARKS = 100_000

    val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun encode(payload: UnifiedLocalBackupPayload, createdAt: Long = System.currentTimeMillis()): ByteArray {
        validate(payload)
        val payloadJson = json.encodeToString(UnifiedLocalBackupPayload.serializer(), payload)
        val digest = sha256(payloadJson.toByteArray(Charsets.UTF_8))
        return json.encodeToString(
            UnifiedLocalBackupEnvelope.serializer(),
            UnifiedLocalBackupEnvelope(createdAt = createdAt, payload = payload, sha256 = digest),
        ).toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) { "Backup metadata exceeds the 64 MiB limit." } }
    }

    fun decode(bytes: ByteArray): UnifiedLocalBackupEnvelope {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Backup file is empty or exceeds the 64 MiB limit." }
        val envelope = json.decodeFromString(UnifiedLocalBackupEnvelope.serializer(), bytes.toString(Charsets.UTF_8))
        require(envelope.schema == UnifiedLocalBackupEnvelope.SCHEMA) { "This backup uses an unsupported schema." }
        require(envelope.createdAt in 1..System.currentTimeMillis() + 24L * 60 * 60 * 1000) { "Backup timestamp is invalid." }
        validate(envelope.payload)
        val payloadJson = json.encodeToString(UnifiedLocalBackupPayload.serializer(), envelope.payload)
        require(constantTimeEquals(envelope.sha256, sha256(payloadJson.toByteArray(Charsets.UTF_8)))) {
            "Backup integrity check failed. Nothing was restored."
        }
        return envelope
    }

    fun validate(payload: UnifiedLocalBackupPayload) {
        val native = payload.native
        require(native.backupManga.size <= MAX_MANGA && native.backupCategories.size <= 500 && native.backupSources.size <= 500) {
            "Backup contains too many manga entries, categories or sources."
        }
        require(payload.novels.size <= MAX_NOVELS && payload.books.size <= MAX_BOOKS && payload.seriesReferences.size <= 50_000) {
            "Backup contains too many local library entries."
        }
        var chapterCount = 0L
        native.backupManga.forEach { item ->
            require(item.source != 0L) { "A manga entry refers to the excluded Local source." }
            require(item.url.length in 1..4096 && item.title.length in 1..1000)
            val thumbnailUrl = item.thumbnailUrl
            require(isSafeReference(item.url) && (thumbnailUrl == null || isSafeReference(thumbnailUrl))) {
                "A manga URL contains credentials or an unsupported destination."
            }
            require(item.notes.isEmpty() && item.tracking.isEmpty()) { "Private notes or tracking data are not allowed in this backup." }
            require(item.memo.contentEquals(JSON_EMPTY)) { "Opaque manga metadata is not allowed in this backup." }
            require(item.chapters.all { chapter ->
                chapter.url.length in 1..4096 && isSafeReference(chapter.url) && chapter.name.length <= 1000 && chapter.memo.contentEquals(JSON_EMPTY)
            }) { "A chapter contains unsupported private metadata." }
            require(item.history.all { history -> history.url.length in 1..4096 && isSafeReference(history.url) && history.lastRead >= 0 && history.readDuration >= 0 }) {
                "Reading history contains an unsafe URL or invalid duration."
            }
            chapterCount += item.chapters.size
        }
        require(native.backupCategories.map { it.name }.distinct().size == native.backupCategories.size)
        require(native.backupCategories.all { it.name.isNotBlank() && it.name.length <= 100 })
        require(native.backupManga.all { it.tracking.isEmpty() } && native.backupSourcePreferences.isEmpty() &&
            native.backupPreferences.isEmpty() && native.backupExtensionStores.isEmpty()) {
            "This backup contains account, source or extension settings that Arkhive will not restore."
        }

        payload.novels.forEach { entry ->
            require(entry.sourceId.matches(Regex("[a-z0-9._-]{1,100}")))
            require(entry.detail.path.length in 1..1000 && entry.detail.name.length in 1..500)
            require(isSafeReference(entry.detail.path) && (entry.detail.cover == null || isSafeReference(entry.detail.cover)) &&
                (entry.detail.provenanceUrl.isBlank() || isSafeReference(entry.detail.provenanceUrl))) {
                "A novel URL contains credentials or an unsupported destination."
            }
            require(entry.detail.chapters.isEmpty()) { "Novel backup metadata must not contain chapter text." }
            // NovelRepository.restoreEntries accepts at most 5,000 chapter rows. Reject larger
            // payloads during decode/preflight, before native membership or settings can change.
            require(entry.chapters.size <= 5_000 && entry.chapters.all {
                it.path.length in 1..1000 && isSafeReference(it.path) && it.name.length <= 500 && it.position >= 0 &&
                    it.progression.isFinite() && it.progression in 0.0..1.0 &&
                    (it.chapterNumber == null || it.chapterNumber.isFinite()) &&
                    (it.releaseTime == null || it.releaseTime.length <= 200) && it.arrivedAt >= 0
            }) { "Novel chapter metadata is invalid." }
            require(entry.chapters.map { it.path }.distinct().size == entry.chapters.size)
            require(entry.lastChapterPath == null ||
                (entry.lastChapterPath.length in 1..1000 && isSafeReference(entry.lastChapterPath) &&
                    entry.chapters.any { it.path == entry.lastChapterPath })) {
                "Novel reading progress refers to a missing or unsafe chapter."
            }
            chapterCount += entry.chapters.size
        }

        var bookmarkCount = 0L
        payload.books.forEach { book ->
            require(book.fingerprint.matches(Regex("[a-f0-9]{64}")))
            require(book.title.isNotBlank() && book.title.length <= 500 && book.author.orEmpty().length <= 500)
            require(book.format in setOf("EPUB", "PDF", "TEXT"))
            require(book.originalName.isNotBlank() && book.originalName.length <= 500 && book.fileBytes in 1..268_435_456)
            require(book.progression.isFinite() && book.progression in 0.0..1.0 && book.locator.orEmpty().length <= 20_000)
            require(book.locator == null || isSafeBookLocator(book.locator)) { "An imported-book position contains unsupported data." }
            require(book.bookmarks.size <= 10_000 && book.bookmarks.all {
                it.id.length in 1..128 && it.locator.isNotBlank() && it.locator.length <= 20_000 &&
                    it.label.length <= 500 && isSafeBookLocator(it.locator)
            }) { "Imported-book bookmark metadata is invalid." }
            bookmarkCount += book.bookmarks.size
        }
        require(chapterCount <= MAX_CHAPTERS && bookmarkCount <= MAX_BOOKMARKS) { "Backup contains too many chapters or bookmarks." }

        val settings = payload.library
        require(settings.shelves.size <= 100 && settings.shelves.distinct().size == settings.shelves.size &&
            settings.shelves.all { it.isNotBlank() && it == it.trim() && it.length <= 80 })
        require(settings.smartStorageMiB in 32..8192)
        require(settings.assignments.size <= 50_000 && settings.profiles.size <= 50_000 &&
            settings.hiddenSeries.size <= 50_000 && settings.removedBookFingerprints.all { it.matches(Regex("[a-f0-9]{64}")) })
        require(settings.assignments.values.all { it.shelves.all(settings.shelves::contains) })
        require(settings.profiles.values.all { profile ->
            (profile.layout == null || profile.layout in setOf("scroll", "pages", "rtl", "ltr", "webtoon", "vertical")) &&
                (profile.brightnessPercent == null || profile.brightnessPercent in 1..100) &&
                (profile.autoSpeed == null || profile.autoSpeed in 16..240)
        })
        require(payload.seriesReferences.size <= 50_000 && payload.seriesReferences.keys.all { it.length <= 256 } &&
            payload.seriesReferences.values.all { it.length in 1..4096 })
        require(payload.seriesReferences.keys.all(mihon.readingapp.library.LibraryToolsPolicy::validKey)) {
            "A library identity is invalid."
        }
        require((settings.assignments.keys + settings.profiles.keys + settings.hiddenSeries).all(payload.seriesReferences::containsKey)) {
            "A library setting refers to a missing series identity."
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(left: String, right: String): Boolean {
        if (left.length != 64 || right.length != 64) return false
        var difference = 0
        for (index in left.indices) difference = difference or (left[index].code xor right[index].code)
        return difference == 0
    }

    private fun isSafeReference(value: String): Boolean {
        if (value.isBlank() || value.any(Char::isISOControl)) return false
        val uri = runCatching { URI(value.replace(" ", "%20")) }.getOrNull() ?: return false
        if (uri.scheme != null && uri.scheme !in setOf("http", "https")) return false
        if (uri.scheme != null && uri.host.isNullOrBlank()) return false
        if (uri.rawUserInfo != null) return false
        val privateKey = Regex("(?i)(token|api.?key|auth|session|secret|pass(word)?|credential|cookie|signature|access.?id)")
        val query = uri.rawQuery ?: return true
        val querySafe = query.split('&').all { part ->
            val key = part.substringBefore('=')
            val decoded = runCatching { URLDecoder.decode(key, StandardCharsets.UTF_8.name()) }.getOrDefault(key)
            !privateKey.containsMatchIn(decoded)
        }
        return querySafe && uri.fragment?.let { !privateKey.containsMatchIn(it) } != false
    }

    private fun isSafeBookLocator(value: String): Boolean = runCatching {
        val root = json.parseToJsonElement(value).jsonObject
        if ("page" in root) {
            require(root.keys.all { it in setOf("page", "zoom") })
            require(root.getValue("page").jsonPrimitive.longOrNull?.let { it in 0L..1_000_000L } == true)
            root["zoom"]?.jsonPrimitive?.doubleOrNull?.let { require(it.isFinite() && it in 0.1..10.0) }
            return@runCatching true
        }
        require(root.keys.all { it in setOf("href", "type", "locations") })
        val href = root.getValue("href").jsonPrimitive.content
        val uri = URI(href.replace(" ", "%20"))
        require(uri.scheme == null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawAuthority == null)
        require(root.getValue("type").jsonPrimitive.content in SAFE_BOOK_LOCATOR_MEDIA_TYPES)
        root["locations"]?.jsonObject?.let { locations ->
            require(locations.keys.all { it in setOf("fragments", "progression", "totalProgression", "position") })
            locations["progression"]?.jsonPrimitive?.doubleOrNull?.let { require(it.isFinite() && it in 0.0..1.0) }
            locations["totalProgression"]?.jsonPrimitive?.doubleOrNull?.let { require(it.isFinite() && it in 0.0..1.0) }
            locations["position"]?.jsonPrimitive?.longOrNull?.let { require(it in 0L..1_000_000L) }
            locations["fragments"]?.jsonArray?.let { fragments ->
                require(fragments.size <= 32 && fragments.all { it.jsonPrimitive.content.length in 1..4096 && it.jsonPrimitive.content.none(Char::isISOControl) })
            }
        }
        true
    }.getOrDefault(false)

    private val JSON_EMPTY = "{}".toByteArray(Charsets.UTF_8)
}
