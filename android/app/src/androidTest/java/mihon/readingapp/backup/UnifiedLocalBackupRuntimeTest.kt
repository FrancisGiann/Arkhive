package mihon.readingapp.backup

import android.net.Uri
import android.util.Base64
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.data.database.DatabaseRepositoryImpl
import mihon.domain.book.BookBookmark
import mihon.domain.book.LocalBook
import mihon.domain.book.LocalBookRepository
import mihon.readingapp.fixtures.OriginalDemoSource
import mihon.readingapp.books.BookReaderActivity
import mihon.readingapp.library.ReadingProfile
import mihon.readingapp.library.ReadingRoom
import mihon.readingapp.library.ShelfAssignment
import mihon.readingapp.library.ShelfStatus
import mihon.readingapp.novels.ControlledNovelSource
import mihon.readingapp.novels.NovelChapter
import mihon.readingapp.novels.NovelRepository
import mihon.readingapp.novels.SourceNovel
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.junit.runner.RunWith
import tachiyomi.data.DatabaseBindings
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

/** Runs replacement against test-scoped manga/books and an isolated novel store. */
@RunWith(AndroidJUnit4::class)
class UnifiedLocalBackupRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.appGraph
    private fun contentUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.bookfixtures", file)

    private fun restoredReaderDiagnostic(activity:BookReaderActivity):String {
        val navigator=activity.supportFragmentManager.findFragmentByTag("arkhive_epub")
            as? org.readium.r2.navigator.epub.EpubNavigatorFragment
        val locator=navigator?.currentLocator?.value
        val href=locator?.href?.toString()?.substringBefore('?')?.substringBefore('#')
            ?.substringAfterLast('/')?.filter { it.isLetterOrDigit() || it in "._-" }?.take(80) ?: "unavailable"
        val webViews=mutableListOf<WebView>()
        fun collect(view:View) {
            if(view is WebView) webViews+=view
            if(view is ViewGroup) for(index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        collect(activity.window.decorView)
        val panes=webViews.mapIndexed { index,web ->
            val bounds=Rect()
            "w$index#${System.identityHashCode(web)}(shown=${web.isShown},visible=${web.getGlobalVisibleRect(bounds)},"+
                "focus=${web.hasFocus()},scroll=${web.scrollY},content=${web.contentHeight},bounds=${bounds.flattenToString()})"
        }.joinToString(";")
        return "focus=${activity.hasWindowFocus()},lifecycle=${activity.lifecycle.currentState},"+
            "navigatorView=${navigator?.view?.isShown},locator=$href,progress=${locator?.locations?.progression},webViews=[$panes]"
    }

    @Test fun localRestoreValidatesBeforeMutationAndRemapsMetadataWithoutExportingAssets(): Unit = runBlocking {
        val nonce = UUID.randomUUID().toString()
        val app = graph
        val fixtureSourceId = "qa.backup.$nonce"
        val fixtureNovelPath = "/original-backup-$nonce"
        val novelSource = ControlledNovelSource(
            fixtureSourceId,
            fixtureNovelPath,
            "Original Backup Story $nonce",
            listOf(NovelChapter("chapter-one", "Original chapter one", 1.0)),
        )
        app.novelRepository.sources.fixtures[fixtureSourceId] = novelSource
        val isolatedNovelRepo = NovelRepository(context, app.novelRepository.sources, "backup-qa-$nonce.db", false)

        lateinit var databaseDriver: SqlDriver
        val migration = DatabaseRepositoryImpl(lazy { databaseDriver })
        databaseDriver = DatabaseBindings.providesSqlDriver(context, migration)

        val mangaUrl = "/original-backup-qa-$nonce"
        val manga = NetworkToLocalManga(app.mangaRepository)(Manga.create().copy(
            source = OriginalDemoSource.MANGA_ID,
            url = mangaUrl,
            title = "Original backup QA $nonce",
            initialized = true,
            favoriteAt = Clock.System.now(),
        ))
        val otherManga = NetworkToLocalManga(app.mangaRepository)(Manga.create().copy(
            source = OriginalDemoSource.WEBTOON_ID,
            url = "/original-backup-outside-$nonce",
            title = "Original outside backup fixture $nonce",
            initialized = true,
        ))
        val nativeChapter = app.chapterRepository.updateFromRemote(emptyList(), listOf(
            Chapter.create().copy(mangaId = manga.id, url = "/backup-chapter-$nonce", name = "Original backup chapter", chapterNumber = 1.0, sourceOrder = 0),
        ), emptyList()).single()

        val fixtureDir = File(context.cacheDir, "original-book-fixtures").apply { mkdirs() }
        val bookFile = File(fixtureDir, "original-backup-$nonce.txt").apply {
            writeText("Original self-authored offline book $nonce.\n" + "This is fixture prose only.\n".repeat(30))
        }
        val fixtureFingerprint = digest(bookFile.readBytes())
        var importedBookId: String? = null
        var importedFingerprint: String? = null
        var payloadFile: File? = null
        val toolsBefore = app.libraryToolsStore.state.value
        val hiddenBefore = app.libraryVisibility.hiddenSeries.get()
        val removedBooksBefore = app.libraryShelfMembership.removedBookIds.value
        val smartBefore = app.smartOfflineStore.state.value
        val sourceId = OriginalDemoSource.MANGA_ID
        val fakeOldMangaKey = "manga:9223372036854775000"
        val targetMangaKey = "manga:${manga.id}"
        val novelKey = "novel:${NovelRepository.novelId(fixtureSourceId, fixtureNovelPath)}"
        var oldFavoriteState = false
        var otherFavoriteState = false

        try {
            // Resolve only after cleanup protection begins; the debug provider deliberately
            // exposes just original-book-fixtures/ through ContentResolver.
            val bookUri = contentUri(bookFile)
            app.chapterRepository.update(ChapterUpdate(nativeChapter.id) {
                read = true
                bookmark = true
                lastPageRead = 7
            })
            val imported = app.localBookImporter.import(bookUri).getOrThrow()
            importedBookId = imported.book.id
            importedFingerprint = imported.book.fingerprint
            assertEquals("The test's exact authored file must define the cleanup fingerprint", fixtureFingerprint, imported.book.fingerprint)
            val bookLocator = """{"href":"chapter-0.xhtml","type":"application/xhtml+xml","locations":{"progression":0.44}}"""
            app.localBookRepository.saveProgress(imported.book.id, bookLocator, 0.44, System.currentTimeMillis())
            val bookmarkId = "backup-$nonce"
            app.localBookRepository.addBookmark(BookBookmark(bookmarkId, imported.book.id, bookLocator, "Original fixture marker", 10L))

            val savedNovel = isolatedNovelRepo.load(fixtureSourceId, fixtureNovelPath)
            isolatedNovelRepo.favorite(savedNovel.id, true)
            val novelChapter = isolatedNovelRepo.chapters(savedNovel.id).single()
            isolatedNovelRepo.bookmark(novelChapter.id, true)
            isolatedNovelRepo.saveProgress(savedNovel.id, novelChapter.id, 0.999).join()
            val novelEntry = isolatedNovelRepo.backupEntries().single()

            val mangaEntries = app.mangaBackupCreator(listOf(manga), BackupOptions(
                tracking = false,
                appSettings = false,
                extensionStores = false,
                sourceSettings = false,
                privateSettings = false,
            ))
            val shelf = "Original QA shelf $nonce"
            val bookFingerprint = imported.book.fingerprint
            val portableManga = "manga:$sourceId:${encode(mangaUrl)}"
            val portableNovel = "novel:$fixtureSourceId:${encode(fixtureNovelPath)}"
            val portableBook = "book:$bookFingerprint"
            val testPayload = UnifiedLocalBackupPayload(
                native = Backup(
                    backupManga = mangaEntries,
                    backupCategories = emptyList(),
                    backupSources = listOf(BackupSource("Arkhive Originals QA", sourceId)),
                ),
                novels = listOf(novelEntry),
                books = listOf(UnifiedBookBackup(
                    fingerprint = bookFingerprint,
                    title = imported.book.title,
                    author = imported.book.author,
                    format = imported.book.format.name,
                    originalName = imported.book.originalName,
                    fileBytes = imported.book.fileBytes,
                    addedAt = imported.book.addedAt,
                    updatedAt = imported.book.updatedAt,
                    locator = bookLocator,
                    progression = 0.44,
                    bookmarks = listOf(UnifiedBookBookmarkBackup(bookmarkId, bookLocator, "Original fixture marker", 10L)),
                )),
                // This deliberately differs from the database row ID. Restore uses source+URL.
                seriesReferences = mapOf(fakeOldMangaKey to portableManga, novelKey to portableNovel, "book:$bookFingerprint" to portableBook),
                library = UnifiedLibraryBackupSettings(
                    shelves = listOf(shelf),
                    assignments = mapOf(fakeOldMangaKey to ShelfAssignment(ShelfStatus.ON_HOLD, setOf(shelf))),
                    profiles = mapOf(fakeOldMangaKey to ReadingProfile("vertical", 63, 91, ReadingRoom.WARM_PAPER)),
                    room = ReadingRoom.MIDNIGHT_VIOLET,
                    hiddenSeries = setOf(fakeOldMangaKey),
                ),
            )
            val bytes = UnifiedLocalBackupCodec.encode(testPayload)
            assertFalse("Archive metadata must not include fixture prose", bytes.toString(Charsets.UTF_8).contains("self-authored offline book"))
            assertFalse("Archive metadata must not include Arkhive-private asset paths", bytes.toString(Charsets.UTF_8).contains("assetFile"))
            payloadFile = File(fixtureDir, "original-backup-payload-$nonce.json").apply { writeBytes(bytes) }

            val scopedManga = FixtureMangaRepository(app.mangaRepository, setOf(manga.id, otherManga.id))
            val scopedBooks = FixtureBookRepository(app.localBookRepository, setOf(bookFingerprint))
            val service = UnifiedLocalBackupService(
                context = context,
                mangaRepository = scopedManga,
                sourceManager = app.sourceManager,
                mangaBackupCreator = app.mangaBackupCreator,
                categoriesBackupCreator = app.categoriesBackupCreator,
                mangaRestorer = app.mangaRestorer,
                categoriesRestorer = app.categoriesRestorer,
                novels = isolatedNovelRepo,
                books = scopedBooks,
                tools = app.libraryToolsStore,
                visibility = app.libraryVisibility,
                membership = app.libraryShelfMembership,
                smartStore = app.smartOfflineStore,
            )

            oldFavoriteState = app.mangaRepository.getMangaById(manga.id).favorite
            otherFavoriteState = app.mangaRepository.getMangaById(otherManga.id).favorite
            app.libraryToolsStore.update { it.copy(smartOffline = mapOf(targetMangaKey to mihon.readingapp.library.SmartOfflineRule(true, 5))) }
            app.libraryVisibility.setHidden(setOf(targetMangaKey), false)

            // Exercise the actual Create path using only fixture-scoped metadata and local SAF-style files.
            val exportFile = File(fixtureDir, "original-backup-created-$nonce.json")
            try {
                val privateLocator = """{"href":"chapter-0.xhtml","type":"application/xhtml+xml","title":"private title","text":{"highlight":"private snippet"},"locations":{"progression":0.44}}"""
                app.localBookRepository.saveProgress(imported.book.id, privateLocator, 0.44, System.currentTimeMillis())
                app.localBookRepository.removeBookmark(bookmarkId)
                app.localBookRepository.addBookmark(BookBookmark(bookmarkId, imported.book.id, privateLocator, "Original fixture marker", 10L))
                app.libraryToolsStore.update { state -> state.copy(
                    shelves = listOf(shelf),
                    assignments = mapOf(targetMangaKey to ShelfAssignment(ShelfStatus.ON_HOLD, setOf(shelf))),
                    profiles = mapOf(
                        targetMangaKey to ReadingProfile("vertical", 63, 91, ReadingRoom.WARM_PAPER),
                        novelKey to ReadingProfile(autoSpeed = 88),
                        "book:$bookFingerprint" to ReadingProfile(layout = "scroll"),
                    ),
                    smartOffline = emptyMap(),
                    stats = emptyMap(),
                    observations = emptyMap(),
                ) }
                app.libraryVisibility.hiddenSeries.set(setOf(targetMangaKey))
                val exportUri = contentUri(exportFile)
                val creation = service.create(exportUri)
                assertTrue(creation.token.isNotBlank())
                val createdBytes = exportFile.readBytes()
                val createdPayload = UnifiedLocalBackupCodec.decode(createdBytes).payload
                assertEquals("The selected fixture manga is in the native backup", mangaUrl, createdPayload.native.backupManga.single().url)
                assertEquals("The isolated novel progress is included", 0.999, createdPayload.novels.single().chapters.single().progression, 0.0001)
                assertEquals("The imported book exports metadata only", bookFingerprint, createdPayload.books.single().fingerprint)
                val portableLocator = JSONObject(requireNotNull(createdPayload.books.single().locator))
                assertEquals("chapter-0.xhtml", portableLocator.getString("href"))
                assertEquals("application/xhtml+xml", portableLocator.getString("type"))
                assertEquals(0.44, portableLocator.getJSONObject("locations").getDouble("progression"), 0.0001)
                assertFalse(portableLocator.has("title"))
                assertEquals(createdPayload.books.single().locator, createdPayload.books.single().bookmarks.single().locator)
                assertEquals(setOf(targetMangaKey, novelKey, "book:$bookFingerprint"), createdPayload.seriesReferences.keys)
                assertTrue(createdPayload.seriesReferences.getValue(targetMangaKey).startsWith("manga:$sourceId:"))
                val createdText = createdBytes.toString(Charsets.UTF_8)
                assertFalse("The full text body is never included", createdText.contains("self-authored offline book"))
                assertFalse("Private locator titles and snippets are never included", createdText.contains("private title") || createdText.contains("private snippet"))
                assertFalse("Private on-device asset paths are never included", createdText.contains("assetFile") || createdText.contains("coverFile"))
                assertTrue("The backup contains only an allowlisted reader configuration", createdPayload.native.backupPreferences.isEmpty())
            } finally { exportFile.delete() }

            val payloadUri = contentUri(payloadFile)
            val preview = service.preview(payloadUri)
            assertEquals(1, preview.manga); assertEquals(1, preview.novels); assertEquals(1, preview.books)
            val targetRow = app.mangaRepository.getMangaByUrlAndSourceId(mangaUrl, sourceId)
            assertNotNull(targetRow)
            assertNotEquals("Portable mapping is tested independently of the old numeric row ID", fakeOldMangaKey, "manga:${targetRow!!.id}")

            // A changed file and invalid envelopes must be rejected before touching membership/settings.
            // Creating a portable archive intentionally clears Smart Offline rules; restore the
            // explicit local opt-in here so invalid-restore checks can prove it remains untouched.
            app.libraryToolsStore.update { it.copy(
                smartOffline = mapOf(targetMangaKey to mihon.readingapp.library.SmartOfflineRule(true, 5)),
            ) }
            payloadFile.writeText("changed after preview")
            assertTrue(runCatching { service.restore(payloadUri, preview.token, UnifiedBackupRestoreMode.MERGE) }.isFailure)
            assertEquals(oldFavoriteState, app.mangaRepository.getMangaById(manga.id).favorite)
            assertEquals(otherFavoriteState, app.mangaRepository.getMangaById(otherManga.id).favorite)
            assertTrue(targetMangaKey in app.libraryToolsStore.state.value.smartOffline)

            val schemaBytes = bytes.toString(Charsets.UTF_8).replaceFirst("\"schema\":1", "\"schema\":2").toByteArray(Charsets.UTF_8)
            payloadFile.writeBytes(schemaBytes)
            assertTrue(runCatching { service.restore(payloadUri, digest(schemaBytes), UnifiedBackupRestoreMode.REPLACE_MEMBERSHIP) }.isFailure)
            val tamperedBytes = bytes.toString(Charsets.UTF_8).replaceFirst("Original backup QA", "Altered backup QA").toByteArray(Charsets.UTF_8)
            assertNotEquals(bytes.toString(Charsets.UTF_8), tamperedBytes.toString(Charsets.UTF_8))
            payloadFile.writeBytes(tamperedBytes)
            assertTrue(runCatching { service.restore(payloadUri, digest(tamperedBytes), UnifiedBackupRestoreMode.REPLACE_MEMBERSHIP) }.isFailure)
            assertEquals(oldFavoriteState, app.mangaRepository.getMangaById(manga.id).favorite)
            assertEquals(otherFavoriteState, app.mangaRepository.getMangaById(otherManga.id).favorite)

            // Exercise Merge preservation against a separate later fixture favorite.
            payloadFile.writeBytes(bytes)
            val validPreview = service.preview(payloadUri)
            app.mangaRepository.update(MangaUpdate(otherManga.id) { favoriteAt = Clock.System.now() })
            otherFavoriteState = true
            app.localBookRepository.saveProgress(imported.book.id, bookLocator, 0.1, System.currentTimeMillis())
            app.localBookRepository.removeBookmark(bookmarkId)
            isolatedNovelRepo.favorite(savedNovel.id, false)
            isolatedNovelRepo.bookmark(novelChapter.id, false)
            isolatedNovelRepo.saveProgress(savedNovel.id, novelChapter.id, 0.2).join()
            setOf(imported.book.assetFile, imported.book.originalFile, imported.book.coverFile)
                .filterNotNull().forEach { File(app.localBookImporter.directory, it).delete() }
            // Simulate restore onto a device without the original import. The archive must create
            // a metadata placeholder, after which selecting the user's original repairs the bytes.
            app.localBookRepository.remove(imported.book.id)
            app.libraryToolsStore.update { it.copy(smartOffline = it.smartOffline - targetMangaKey) }

            val scopedService = service
            scopedService.restore(payloadUri, validPreview.token, UnifiedBackupRestoreMode.MERGE)
            assertTrue("Merge keeps a later favorite outside the archived membership", app.mangaRepository.getMangaById(otherManga.id).favorite)
            assertTrue(app.libraryVisibility.isHidden(targetMangaKey))
            assertEquals(ShelfStatus.ON_HOLD, app.libraryToolsStore.state.value.assignments[targetMangaKey]?.status)
            assertEquals(ReadingProfile("vertical", 63, 91, ReadingRoom.WARM_PAPER), app.libraryToolsStore.profile(targetMangaKey))
            assertFalse("Backup restore must not enable background downloading", app.libraryToolsStore.state.value.smartOffline.containsKey(targetMangaKey))
            val restoredChapter = app.chapterRepository.getChapterByMangaId(manga.id).single()
            assertTrue(restoredChapter.read); assertTrue(restoredChapter.bookmark); assertEquals(7, restoredChapter.lastPageRead)
            val restoredNovel = requireNotNull(isolatedNovelRepo.get(savedNovel.id))
            val restoredNovelChapter = isolatedNovelRepo.chapters(savedNovel.id).single()
            assertTrue(restoredNovel.favorite); assertTrue(restoredNovelChapter.bookmark)
            assertEquals(0.999, restoredNovelChapter.progression, 0.0001)
            val missingAssetRow = requireNotNull(app.localBookRepository.get(bookFingerprint))
            assertEquals(0.44, missingAssetRow.progression, 0.0001)
            assertNotNull("The restored saved position must decode as a Readium locator", Locator.fromJSON(JSONObject(requireNotNull(missingAssetRow.locator))))
            assertEquals("Original fixture marker", app.localBookRepository.bookmarks(bookFingerprint).single().label)
            assertNotNull("The restored bookmark must decode as a Readium locator", Locator.fromJSON(JSONObject(app.localBookRepository.bookmarks(bookFingerprint).single().locator)))
            assertFalse(File(app.localBookImporter.directory, missingAssetRow.originalFile).exists())
            assertFalse(File(app.localBookImporter.directory, missingAssetRow.assetFile).exists())

            val reimported = app.localBookImporter.import(bookUri).getOrThrow()
            assertTrue("A missing metadata-only import can be restored from the user's original file", reimported.alreadyImported)
            assertEquals(0.44, reimported.book.progression, 0.0001)
            val restoredBook = requireNotNull(app.localBookRepository.get(bookFingerprint))
            assertNotNull("The reimported reading position remains a valid Readium locator",
                Locator.fromJSON(JSONObject(requireNotNull(restoredBook.locator))))
            val restoredBookmark = app.localBookRepository.bookmarks(bookFingerprint).single()
            assertEquals("Original fixture marker", restoredBookmark.label)
            assertNotNull("The reimported bookmark remains a valid Readium locator",
                Locator.fromJSON(JSONObject(restoredBookmark.locator)))
            val readerIntent = android.content.Intent(context, BookReaderActivity::class.java)
                .putExtra(BookReaderActivity.BOOK_ID, restoredBook.id)
            ActivityScenario.launch<BookReaderActivity>(readerIntent).use { reader ->
                val restoreStage="open the reimported original at its saved Readium position"
                try {
                    withTimeout(20_000) {
                        while (true) {
                            var reopenedAtRestoredPosition = false
                            reader.onActivity { activity ->
                                val navigator = activity.supportFragmentManager.findFragmentByTag("arkhive_epub")
                                    as? org.readium.r2.navigator.epub.EpubNavigatorFragment
                                val locator = navigator?.currentLocator?.value
                                reopenedAtRestoredPosition = navigator?.view != null && locator != null &&
                                    locator.href.toString().substringBefore('#') == "chapter-0.xhtml" &&
                                    (locator.locations.progression ?: 0.0) >= 0.43
                            }
                            if (reopenedAtRestoredPosition) break
                            delay(100)
                        }
                    }
                } catch (error:TimeoutCancellationException) {
                    var diagnostic="reader activity unavailable"
                    reader.onActivity { activity -> diagnostic=restoredReaderDiagnostic(activity) }
                    throw AssertionError("Timed out while trying to $restoreStage: $diagnostic",error)
                }
            }

            app.libraryToolsStore.update { it.copy(smartOffline = mapOf(targetMangaKey to mihon.readingapp.library.SmartOfflineRule(true, 5))) }
            val replacePreview = service.preview(payloadUri)
            service.restore(payloadUri, replacePreview.token, UnifiedBackupRestoreMode.REPLACE_MEMBERSHIP)
            assertFalse("Replace removes only the later scoped fixture membership", app.mangaRepository.getMangaById(otherManga.id).favorite)
            assertTrue(app.mangaRepository.getMangaById(manga.id).favorite)
            assertFalse(app.libraryToolsStore.state.value.cleanupReadSmartCopies)
            assertTrue(app.libraryToolsStore.state.value.smartOffline.isEmpty())
            assertFalse(app.smartOfflineStore.state.value.running)
            assertEquals(0.999, isolatedNovelRepo.chapters(savedNovel.id).single().progression, 0.0001)
        } finally {
            payloadFile?.delete()
            bookFile.delete()
            app.localBookRepository.removeBookmark("backup-$nonce")
            val fixtureBooks = linkedMapOf<String, mihon.domain.book.LocalBook>()
            for (key in setOfNotNull(importedBookId, importedFingerprint, fixtureFingerprint)) {
                app.localBookRepository.get(key)?.let { fixtureBooks[it.id] = it }
                app.localBookRepository.getByFingerprint(key)?.let { fixtureBooks[it.id] = it }
            }
            for (book in fixtureBooks.values) {
                for (bookmark in app.localBookRepository.bookmarks(book.id)) {
                    app.localBookRepository.removeBookmark(bookmark.id)
                }
                app.localBookImporter.removePrivateCopy(book.id)
            }
            app.libraryToolsStore.update { toolsBefore }
            app.libraryVisibility.hiddenSeries.set(hiddenBefore)
            val removedNow = app.libraryShelfMembership.removedBookIds.value
            (removedNow - removedBooksBefore).forEach(app.libraryShelfMembership::restoreBook)
            (removedBooksBefore - removedNow).forEach(app.libraryShelfMembership::removeBook)
            app.smartOfflineStore.update { smartBefore.copy(running = false, currentSeries = null, currentChapter = null) }
            app.mangaRepository.update(MangaUpdate(manga.id) { favoriteAt = null })
            app.mangaRepository.update(MangaUpdate(otherManga.id) { favoriteAt = null })
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                databaseDriver.execute(null, "DELETE FROM manga WHERE id IN (?, ?)", 2) {
                    bindLong(0, manga.id); bindLong(1, otherManga.id)
                }.await()
            }
            databaseDriver.close()
            runCatching { isolatedNovelRepo.closeForTest() }
            app.novelRepository.sources.fixtures.remove(fixtureSourceId)
            context.deleteDatabase("backup-qa-$nonce.db")
            val folder = "arkhive-novel-test-" + NovelRepository.id("backup-qa-$nonce.db").take(16)
            File(context.filesDir, folder).deleteRecursively()
            File(context.cacheDir, folder).deleteRecursively()
            val remainingBooks = app.localBookRepository.observeBooks().first()
            assertFalse(
                "The exact self-authored backup row must be absent after fixture cleanup",
                remainingBooks.any { it.fingerprint == fixtureFingerprint || it.originalName == "original-backup-$nonce.txt" },
            )
        }
    }

    private class FixtureMangaRepository(
        private val delegate: MangaRepository,
        private val allowedIds: Set<Long>,
    ) : MangaRepository by delegate {
        override suspend fun getFavorites() = delegate.getFavorites().filter { it.id in allowedIds }
    }

    private class FixtureBookRepository(
        private val delegate: LocalBookRepository,
        private val allowedFingerprints: Set<String>,
    ) : LocalBookRepository by delegate {
        override fun observeBooks() = delegate.observeBooks().map { rows -> rows.filter { it.fingerprint in allowedFingerprints } }
        override suspend fun getByFingerprint(fingerprint: String): LocalBook? =
            if (fingerprint in allowedFingerprints) delegate.getByFingerprint(fingerprint) else null
    }

    private fun encode(value: String): String = Base64.encodeToString(
        value.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
    )

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
