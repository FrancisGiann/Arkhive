package mihon.readingapp.backup

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import mihon.core.common.extensions.JsonObjectEmptyBytes
import mihon.domain.book.BookBookmark
import mihon.domain.book.LocalBook
import mihon.domain.book.LocalBookFormat
import mihon.domain.book.LocalBookRepository
import mihon.readingapp.library.LibraryShelfMembership
import mihon.readingapp.library.LibraryToolsPolicy
import mihon.readingapp.library.LibraryToolsStore
import mihon.readingapp.library.SmartOfflineStore
import mihon.readingapp.novels.NovelBackupEntry
import mihon.readingapp.novels.NovelRepository
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.library.service.LibraryVisibility
import tachiyomi.domain.source.service.SourceManager
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.security.MessageDigest

data class UnifiedBackupRestoreResult(
    val restoredManga: Int,
    val restoredNovels: Int,
    val restoredBooks: Int,
    val unmatchedSettings: Int,
)

/** User-selected local SAF backup; only library metadata and reading state are serialized. */
@Inject
@SingleIn(AppScope::class)
class UnifiedLocalBackupService(
    private val context: Context,
    private val mangaRepository: MangaRepository,
    private val sourceManager: SourceManager,
    private val mangaBackupCreator: MangaBackupCreator,
    private val categoriesBackupCreator: CategoriesBackupCreator,
    private val mangaRestorer: MangaRestorer,
    private val categoriesRestorer: CategoriesRestorer,
    private val novels: NovelRepository,
    private val books: LocalBookRepository,
    private val tools: LibraryToolsStore,
    private val visibility: LibraryVisibility,
    private val membership: LibraryShelfMembership,
    private val smartStore: SmartOfflineStore,
) {
    suspend fun create(uri: Uri) = withContext(Dispatchers.IO) {
        requireLocalPickerUri(uri)
        val favourites = mangaRepository.getFavorites()
        val settingsKeys = (tools.state.value.assignments.keys + tools.state.value.profiles.keys + visibility.hiddenSeries.get())
            .filter(LibraryToolsPolicy::validKey).toSet()
        val referencedManga = mutableListOf<tachiyomi.domain.manga.model.Manga>()
        for (key in settingsKeys) {
            if (!key.startsWith("manga:")) continue
            val id = key.substringAfter(':').toLongOrNull() ?: continue
            runCatching { mangaRepository.getMangaById(id) }.getOrNull()?.let(referencedManga::add)
        }
        // Mihon's Local source points at user files and paths. Imported books have a separate
        // metadata-only representation below, so keep those paths out of this archive.
        val nativeSeries = (favourites.asSequence() + referencedManga)
            .filter { it.source != LOCAL_SOURCE_ID }
            .distinctBy { it.id }
            .toList()
        require(nativeSeries.size <= 20_000) { "Library exceeds the 20,000-series backup limit." }
        val options = BackupOptions(
            libraryEntries = true,
            categories = true,
            chapters = true,
            tracking = false,
            history = true,
            readEntries = false,
            appSettings = false,
            extensionStores = false,
            sourceSettings = false,
            privateSettings = false,
        )
        val safeManga = mangaBackupCreator(nativeSeries, options).mapNotNull { sanitizeNativeManga(it) }
        val safeSourceIds = safeManga.map { it.source }.distinct()
        require(safeSourceIds.size <= 500) { "Backup references more than 500 sources." }
        val sourceNames = safeSourceIds.map { id ->
            val name = runCatching { sourceManager.getOrStub(id).name }.getOrDefault("Source $id").take(200)
            BackupSource(name = name, sourceId = id)
        }
        val categories = categoriesBackupCreator()
        require(categories.size <= 500) { "Backup contains more than 500 categories." }
        val native = Backup(
            backupManga = safeManga,
            backupCategories = categories,
            backupSources = sourceNames,
        )

        val allNovelEntries = novels.backupEntries()
        require(allNovelEntries.size <= 10_000) { "Novel library exceeds the 10,000-series backup limit." }
        val novelEntries = allNovelEntries.mapNotNull(::sanitizeNovel)
        val currentBooks = books.observeBooks().first()
        require(currentBooks.size <= 10_000) { "Imported book library exceeds the 10,000-book backup limit." }
        val bookEntries = currentBooks.mapNotNull { book ->
            val fingerprint = book.fingerprint.takeIf { it.matches(SHA256_REGEX) } ?: return@mapNotNull null
            val bookmarks = books.bookmarks(book.id).take(10_000).mapNotNull { bookmark ->
                val locator = safeBookLocator(bookmark.locator) ?: return@mapNotNull null
                bookmark.copy(locator = locator, label = bookmark.label.take(500))
                    .takeIf { it.id.length in 1..128 }
                    ?.let { UnifiedBookBookmarkBackup(it.id, it.locator, it.label, it.createdAt) }
            }
            UnifiedBookBackup(
                fingerprint = fingerprint,
                title = book.title.take(500),
                author = book.author?.take(500),
                format = book.format.name,
                originalName = safeFileName(book.originalName),
                fileBytes = book.fileBytes,
                addedAt = book.addedAt,
                updatedAt = book.updatedAt,
                locator = book.locator?.let(::safeBookLocator),
                progression = book.progression,
                bookmarks = bookmarks,
                removedFromLibrary = book.id in membership.removedBookIds.value,
            )
        }

        val (references, sharedSettings) = captureSharedSettings(settingsKeys, currentBooks)
        val payload = UnifiedLocalBackupPayload(native, novelEntries, bookEntries, references, sharedSettings)
        val bytes = UnifiedLocalBackupCodec.encode(payload)
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("Android could not open the selected backup destination.")
        output.use { stream ->
            stream.write(bytes)
            stream.flush()
            (stream as? FileOutputStream)?.fd?.sync()
        }
        UnifiedBackupPreview(
            token = sha256(bytes), manga = safeManga.size, novels = novelEntries.size,
            books = bookEntries.size, shelves = sharedSettings.shelves.size,
            hiddenSeries = sharedSettings.hiddenSeries.size,
        )
    }

    suspend fun preview(uri: Uri): UnifiedBackupPreview = withContext(Dispatchers.IO) {
        val bytes = readBounded(uri)
        val envelope = decodeAndValidate(bytes)
        envelope.payload.let { payload ->
            UnifiedBackupPreview(
                token = sha256(bytes), manga = payload.native.backupManga.size,
                novels = payload.novels.size, books = payload.books.size,
                shelves = payload.library.shelves.size, hiddenSeries = payload.library.hiddenSeries.size,
            )
        }
    }

    /** Re-reads the confirmed file and requires its whole-file token to still match the preview. */
    suspend fun restore(uri: Uri, previewToken: String, mode: UnifiedBackupRestoreMode): UnifiedBackupRestoreResult =
        withContext(Dispatchers.IO) {
            val bytes = readBounded(uri)
            require(sha256(bytes) == previewToken) { "The selected file changed after preview. Review it again before restoring." }
            val payload = decodeAndValidate(bytes).payload
            val oldFavorites = mangaRepository.getFavorites()
            val oldBooks = books.observeBooks().first()
            val incomingManga = payload.native.backupManga.mapNotNull { item -> mangaIdentity(item.source, item.url) }.toSet()

            if (mode == UnifiedBackupRestoreMode.REPLACE_MEMBERSHIP) {
                oldFavorites.forEach { old ->
                    mangaRepository.setMangaCategories(old.id, emptyList())
                    if (mangaIdentity(old.source, old.url) !in incomingManga) {
                        mangaRepository.update(MangaUpdate(old.id) { favoriteAt = null })
                    }
                }
            }
            categoriesRestorer(payload.native.backupCategories)
            mangaRestorer.restore(payload.native.backupManga, payload.native.backupCategories)
            novels.restoreEntries(payload.novels, replace = mode == UnifiedBackupRestoreMode.REPLACE_MEMBERSHIP)
            restoreBooks(payload.books, oldBooks, payload.library.removedBookFingerprints, mode)

            val targetByReference = resolveRestoredReferences(payload)
            val unmapped = applySharedSettings(payload, targetByReference, mode)
            UnifiedBackupRestoreResult(
                restoredManga = payload.native.backupManga.size,
                restoredNovels = payload.novels.size,
                restoredBooks = payload.books.size,
                unmatchedSettings = unmapped,
            )
        }

    private suspend fun restoreBooks(
        entries: List<UnifiedBookBackup>,
        oldBooks: List<LocalBook>,
        removedFingerprints: Set<String>,
        mode: UnifiedBackupRestoreMode,
    ) {
        val incomingIds = entries.mapTo(HashSet()) { it.fingerprint }
        if (mode == UnifiedBackupRestoreMode.REPLACE_MEMBERSHIP) {
            oldBooks.filterNot { it.fingerprint in incomingIds }.forEach { membership.removeBook(it.id) }
        }
        entries.forEach { entry ->
            val fingerprint = entry.fingerprint
            val existing = books.getByFingerprint(fingerprint)
            val format = LocalBookFormat.valueOf(entry.format)
            val extension = when (format) {
                LocalBookFormat.EPUB -> "epub"
                LocalBookFormat.PDF -> "pdf"
                LocalBookFormat.TEXT -> "txt"
            }
            val placeholder = existing ?: LocalBook(
                id = fingerprint,
                title = entry.title,
                author = entry.author,
                format = format,
                assetFile = if (format == LocalBookFormat.PDF) "$fingerprint.$extension" else "$fingerprint.read.epub",
                originalFile = "$fingerprint.$extension",
                originalName = entry.originalName,
                fingerprint = fingerprint,
                fileBytes = entry.fileBytes,
                coverFile = "$fingerprint.cover.png",
                addedAt = entry.addedAt,
                updatedAt = entry.updatedAt,
                locator = entry.locator,
                progression = entry.progression,
            )
            if (existing == null) {
                books.insert(placeholder)
            } else {
                val useIncoming = mode == UnifiedBackupRestoreMode.REPLACE_MEMBERSHIP || entry.progression > existing.progression
                books.restoreMetadata(
                    existing.copy(
                        title = if (mode == UnifiedBackupRestoreMode.REPLACE_MEMBERSHIP) entry.title else existing.title,
                        author = if (mode == UnifiedBackupRestoreMode.REPLACE_MEMBERSHIP) entry.author else existing.author,
                        originalName = if (mode == UnifiedBackupRestoreMode.REPLACE_MEMBERSHIP) entry.originalName else existing.originalName,
                        fileBytes = existing.fileBytes,
                        addedAt = if (mode == UnifiedBackupRestoreMode.REPLACE_MEMBERSHIP) entry.addedAt else minOf(existing.addedAt, entry.addedAt),
                        updatedAt = maxOf(existing.updatedAt, entry.updatedAt),
                        locator = if (useIncoming) entry.locator else existing.locator,
                        progression = if (useIncoming) entry.progression else existing.progression,
                    ),
                )
            }
            val currentBookmarks = books.bookmarks(fingerprint)
            val incomingBookmarkIds = entry.bookmarks.mapTo(HashSet()) { it.id }
            if (mode == UnifiedBackupRestoreMode.REPLACE_MEMBERSHIP) {
                currentBookmarks.filterNot { it.id in incomingBookmarkIds }.forEach { books.removeBookmark(it.id) }
            }
            entry.bookmarks.forEach { bookmark ->
                books.addBookmark(BookBookmark(bookmark.id, fingerprint, bookmark.locator, bookmark.label, bookmark.createdAt))
            }
            if (entry.removedFromLibrary || fingerprint in removedFingerprints) membership.removeBook(fingerprint)
            else membership.restoreBook(fingerprint)
        }
    }

    private suspend fun resolveRestoredReferences(payload: UnifiedLocalBackupPayload): Map<String, String> {
        val result = mutableMapOf<String, String>()
        payload.native.backupManga.forEach { item ->
            mangaRepository.getMangaByUrlAndSourceId(item.url, item.source)?.let { result[mangaIdentity(item.source, item.url)] = "manga:${it.id}" }
        }
        payload.novels.forEach { item ->
            result[novelIdentity(item.sourceId, item.detail.path)] = "novel:${NovelRepository.novelId(item.sourceId, item.detail.path)}"
        }
        payload.books.forEach { item -> result[bookIdentity(item.fingerprint)] = "book:${item.fingerprint}" }
        return result
    }

    /** Validates encoded source identities before a preview or any restore mutation. */
    private fun decodeAndValidate(bytes: ByteArray): UnifiedLocalBackupEnvelope {
        val envelope = UnifiedLocalBackupCodec.decode(bytes)
        val payload = envelope.payload
        val manga = payload.native.backupManga.associateBy { mangaIdentity(it.source, it.url) }
        val novelsByIdentity = payload.novels.associateBy { novelIdentity(it.sourceId, it.detail.path) }
        val booksByIdentity = payload.books.associateBy { bookIdentity(it.fingerprint) }
        require(payload.seriesReferences.keys.all(LibraryToolsPolicy::validKey)) {
            "The backup contains an invalid saved library identity."
        }
        payload.seriesReferences.forEach { (localKey, portable) ->
            when {
                localKey.startsWith("manga:") -> {
                    val parts = portable.split(':', limit = 3)
                    require(parts.size == 3 && parts[0] == "manga") { "The backup contains an invalid manga reference." }
                    val sourceId = parts[1].toLongOrNull()?.takeIf { it != LOCAL_SOURCE_ID }
                        ?: error("The backup contains an invalid manga source reference.")
                    val url = runCatching {
                        String(Base64.decode(parts[2], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
                    }.getOrNull()?.takeIf { safeReference(it) == it }
                        ?: error("The backup contains an unsafe manga reference.")
                    require(mangaIdentity(sourceId, url) in manga) {
                        "The backup contains a manga reference without matching reading data."
                    }
                }
                localKey.startsWith("novel:") -> {
                    val parts = portable.split(':', limit = 3)
                    require(parts.size == 3 && parts[0] == "novel" && parts[1].matches(Regex("[a-z0-9._-]{1,100}"))) {
                        "The backup contains an invalid novel reference."
                    }
                    val path = runCatching {
                        String(Base64.decode(parts[2], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
                    }.getOrNull()?.takeIf { safeReference(it) == it }
                        ?: error("The backup contains an unsafe novel reference.")
                    require(novelIdentity(parts[1], path) in novelsByIdentity) {
                        "The backup contains a novel reference without matching reading data."
                    }
                }
                localKey.startsWith("book:") -> {
                    require(portable.startsWith("book:") && portable.substringAfter(':').matches(SHA256_REGEX) &&
                        portable in booksByIdentity) { "The backup contains a book reference without matching metadata." }
                }
                else -> error("The backup contains an unsupported library identity.")
            }
        }
        return envelope
    }

    private suspend fun applySharedSettings(
        payload: UnifiedLocalBackupPayload,
        targetByReference: Map<String, String>,
        mode: UnifiedBackupRestoreMode,
    ): Int {
        val settings = payload.library
        val remappedAssignments = settings.assignments.mapNotNull { (reference, value) ->
            payload.seriesReferences[reference]?.let(targetByReference::get)?.let { it to value }
        }.toMap()
        val remappedProfiles = settings.profiles.mapNotNull { (reference, value) ->
            payload.seriesReferences[reference]?.let(targetByReference::get)?.let { it to value }
        }.toMap()
        val remappedHidden = settings.hiddenSeries.mapNotNullTo(LinkedHashSet()) { reference ->
            payload.seriesReferences[reference]?.let(targetByReference::get)
        }
        val requested = settings.assignments.size + settings.profiles.size + settings.hiddenSeries.size
        val matched = remappedAssignments.size + remappedProfiles.size + remappedHidden.size
        val replace = mode == UnifiedBackupRestoreMode.REPLACE_MEMBERSHIP
        val replacementShelves = settings.shelves.takeIf { replace }

        tools.update { current ->
            val restoredShelves = replacementShelves ?: (current.shelves + settings.shelves).distinct().take(100)
            val next = current.copy(
                shelves = restoredShelves,
                assignments = if (replace) remappedAssignments else current.assignments + remappedAssignments,
                profiles = if (replace) remappedProfiles else current.profiles + remappedProfiles,
                room = if (replace) settings.room else settings.room ?: current.room,
                smartStorageMiB = if (replace) settings.smartStorageMiB else current.smartStorageMiB,
                // Smart rules, active ownership, health observations and per-chapter stats are
                // intentionally not in the archive. Replace disables cleanup; merge preserves
                // only rules the current device already had.
                smartOffline = if (replace) emptyMap() else current.smartOffline,
                cleanupReadSmartCopies = if (replace) false else current.cleanupReadSmartCopies,
                statsEnabled = if (replace) settings.statsEnabled else current.statsEnabled,
                stats = if (replace) emptyMap() else current.stats,
                observations = if (replace) emptyMap() else current.observations,
                dashboardCollection = current.dashboardCollection.takeIf { selected ->
                    selected.isEmpty() || selected in setOf("Reading", "On Hold", "Finished") || selected in restoredShelves
                }.orEmpty(),
            )
            LibraryToolsPolicy.validate(next)
        }

        val oldHidden = visibility.hiddenSeries.get()
        visibility.hiddenSeries.set(if (replace) remappedHidden else oldHidden + remappedHidden)
        if (replace) {
            smartStore.setCurrent(null, null, "Smart rules were not restored from backup.")
        }
        return (requested - matched).coerceAtLeast(0)
    }

    private suspend fun captureSharedSettings(
        keys: Set<String>,
        currentBooks: List<LocalBook>,
    ): Pair<Map<String, String>, UnifiedLibraryBackupSettings> {
        val current = tools.state.value
        val refs = keys.mapNotNull { key -> portableReference(key)?.let { key to it } }.toMap()
        val assignmentRefs = current.assignments.mapNotNull { (key, value) -> refs[key]?.let { key to value } }.toMap()
        val profileRefs = current.profiles.mapNotNull { (key, value) -> refs[key]?.let { key to value } }.toMap()
        val hiddenRefs = visibility.hiddenSeries.get().filterTo(LinkedHashSet()) { it in refs }
        return refs to UnifiedLibraryBackupSettings(
            shelves = current.shelves,
            assignments = assignmentRefs,
            profiles = profileRefs,
            room = current.room,
            smartStorageMiB = current.smartStorageMiB,
            statsEnabled = current.statsEnabled,
            hiddenSeries = hiddenRefs,
            removedBookFingerprints = membership.removedBookIds.value.intersect(currentBooks.map { it.fingerprint }.toSet()),
        )
    }

    private suspend fun portableReference(key: String): String? = when {
        key.startsWith("manga:") -> {
            val id = key.substringAfter(':').toLongOrNull() ?: return null
            val item = runCatching { mangaRepository.getMangaById(id) }.getOrNull() ?: return null
            mangaIdentity(item.source, safeReference(item.url) ?: return null)
        }
        key.startsWith("novel:") -> {
            val item = novels.get(key.substringAfter(':')) ?: return null
            novelIdentity(item.sourceId, safeReference(item.detail.path) ?: return null)
        }
        key.startsWith("book:") -> {
            val item = books.get(key.substringAfter(':')) ?: return null
            bookIdentity(item.fingerprint)
        }
        else -> null
    }

    private suspend fun sanitizeNativeManga(item: BackupManga): BackupManga? {
        if (item.source == LOCAL_SOURCE_ID) return null
        val safeMangaUrl = safeReference(item.url) ?: return null
        item.url = safeMangaUrl
        item.thumbnailUrl = item.thumbnailUrl?.let(::safeReference)
        item.notes = ""
        item.memo = JsonObjectEmptyBytes
        item.tracking = emptyList()
        item.chapters = item.chapters.mapNotNull { chapter ->
            safeReference(chapter.url)?.let { safeUrl ->
                chapter.url = safeUrl
                chapter.memo = JsonObjectEmptyBytes
                chapter.name = chapter.name.take(1000)
                chapter
            }
        }
        item.history = item.history.mapNotNull { history ->
            safeReference(history.url)?.let { safeUrl -> history.apply { url = safeUrl } }
        }
        return item
    }

    private fun sanitizeNovel(entry: NovelBackupEntry): NovelBackupEntry? {
        val path = safeReference(entry.detail.path) ?: return null
        val detail = entry.detail.copy(
            path = path,
            name = entry.detail.name.take(500),
            cover = entry.detail.cover?.let(::safeReference),
            genres = entry.detail.genres.take(100).map { it.take(100) },
            summary = entry.detail.summary?.take(20_000),
            chapters = emptyList(),
            provenanceUrl = entry.detail.provenanceUrl.let(::safeReference).orEmpty(),
            rights = entry.detail.rights.take(500),
        )
        val chapters = entry.chapters.mapNotNull { chapter ->
            safeReference(chapter.path)?.let { safePath ->
                chapter.copy(path = safePath, name = chapter.name.take(500))
            }
        }
        return entry.copy(sourceId = entry.sourceId.take(100), detail = detail, chapters = chapters)
    }

    private fun mangaIdentity(sourceId: Long, url: String): String = "manga:$sourceId:${encodePart(url)}"
    private fun novelIdentity(sourceId: String, path: String): String = "novel:$sourceId:${encodePart(path)}"
    private fun bookIdentity(fingerprint: String): String = "book:$fingerprint"

    private fun encodePart(value: String): String = Base64.encodeToString(
        value.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
    )

    private fun safeReference(raw: String?): String? {
        if (raw.isNullOrBlank() || raw.length > 4096 || raw.any { it.isISOControl() }) return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        if (uri.scheme != null && uri.scheme !in setOf("http", "https")) return null
        if (uri.scheme != null && uri.host.isNullOrBlank()) return null
        if (uri.encodedAuthority?.contains('@') == true) return null
        // Reject credential-bearing identities instead of stripping query parameters and changing
        // the source's stable URL. This avoids silently creating a second library row on restore.
        if (uri.queryParameterNames.any(PRIVATE_QUERY_KEY::containsMatchIn)) return null
        return raw
    }

    /** Preserve reading position, but omit snippets/titles and reject external locator URLs. */
    private fun safeBookLocator(raw: String?): String? {
        if (raw.isNullOrBlank() || raw.length > 20_000 || raw.any { it.isISOControl() }) return null
        val source = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val safe = JSONObject()
        if (source.has("page")) {
            val page = source.optInt("page", -1)
            if (page !in 0..1_000_000) return null
            safe.put("page", page)
            source.optDouble("zoom", 1.0).takeIf { it.isFinite() && it in 0.1..10.0 }?.let { safe.put("zoom", it) }
            return safe.toString()
        }
        val href = source.optString("href").takeIf { it.isNotBlank() && it.length <= 4096 } ?: return null
        if (href.any { it.isISOControl() } || href.startsWith("//") || href.contains('\\')) return null
        val parsedHref = runCatching { Uri.parse(href) }.getOrNull() ?: return null
        if (parsedHref.scheme != null || parsedHref.encodedAuthority != null) return null
        val type = source.optString("type").takeIf { it in SAFE_BOOK_LOCATOR_MEDIA_TYPES } ?: return null
        safe.put("href", href)
        safe.put("type", type)
        source.optJSONObject("locations")?.let { locations ->
            val safeLocations = JSONObject()
            locations.optDouble("progression", Double.NaN).takeIf { it.isFinite() && it in 0.0..1.0 }
                ?.let { safeLocations.put("progression", it) }
            locations.optDouble("totalProgression", Double.NaN).takeIf { it.isFinite() && it in 0.0..1.0 }
                ?.let { safeLocations.put("totalProgression", it) }
            locations.optInt("position", -1).takeIf { it in 0..1_000_000 }
                ?.let { safeLocations.put("position", it) }
            val fragments = locations.optJSONArray("fragments")
            if (fragments != null && fragments.length() <= 32) {
                val safeFragments = org.json.JSONArray()
                for (index in 0 until fragments.length()) {
                    val fragment = fragments.optString(index)
                    if (fragment.length in 1..4096 && fragment.none(Char::isISOControl)) safeFragments.put(fragment)
                }
                if (safeFragments.length() > 0) safeLocations.put("fragments", safeFragments)
            }
            if (safeLocations.length() > 0) safe.put("locations", safeLocations)
        }
        return safe.toString().takeIf { it.length <= 20_000 }
    }

    private fun safeFileName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')
        .filterNot(Char::isISOControl).take(500).ifBlank { "Imported book" }

    private fun readBounded(uri: Uri): ByteArray {
        requireLocalPickerUri(uri)
        val input = context.contentResolver.openInputStream(uri) ?: error("Android could not open the selected backup file.")
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= UnifiedLocalBackupCodec.MAX_BYTES) { "Backup file exceeds the 64 MiB limit." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun requireLocalPickerUri(uri: Uri) {
        require(uri.scheme == "content" || uri.scheme == "file") { "Choose a local file using Android's document picker." }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val LOCAL_SOURCE_ID = 0L
        val SHA256_REGEX = Regex("[a-f0-9]{64}")
        val PRIVATE_QUERY_KEY = Regex("(?i)(token|api.?key|auth|session|secret|pass(word)?|credential|cookie|signature|access.?id)")
    }
}
