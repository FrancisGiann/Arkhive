package mihon.readingapp.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import mihon.readingapp.novels.NovelBackupEntry
import mihon.readingapp.novels.NovelChapterBackup
import mihon.readingapp.novels.SourceNovel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnifiedLocalBackupCodecTest {
    @Test fun roundTripKeepsOnlyValidatedPortableMetadata() {
        val payload = payload()
        val decoded = UnifiedLocalBackupCodec.decode(UnifiedLocalBackupCodec.encode(payload))
        assertEquals(payload.novels.single().sourceId, decoded.payload.novels.single().sourceId)
        assertEquals(payload.novels.single().chapters, decoded.payload.novels.single().chapters)
        assertEquals(payload.library, decoded.payload.library)
        assertTrue(decoded.payload.native.backupManga.isEmpty())
    }

    @Test fun rejectsUnsupportedSchemaAndTamperedPayloadBeforeRestore() {
        val encoded = UnifiedLocalBackupCodec.encode(payload()).toString(Charsets.UTF_8)
        val unsupported = encoded.replaceFirst("\"schema\":1", "\"schema\":2")
        assertTrue(unsupported != encoded)
        assertThrows(IllegalArgumentException::class.java) {
            UnifiedLocalBackupCodec.decode(unsupported.toByteArray(Charsets.UTF_8))
        }

        val changed = encoded.replaceFirst("Fixture novel", "Altered novel")
        assertTrue(changed != encoded)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            UnifiedLocalBackupCodec.decode(changed.toByteArray(Charsets.UTF_8))
        }
        assertTrue(failure.message.orEmpty().contains("integrity"))
    }

    @Test fun rejectsNovelChapterCountBeyondRepositoryRestoreBoundDuringPreflight() {
        val manyChapters = (0..5_000).map { index ->
            NovelChapterBackup(
                path = "/fixture/chapter-$index",
                name = "Chapter $index",
                position = index,
                progression = 0.0,
                bookmark = false,
            )
        }
        val oversized = payload().copy(novels = listOf(novel().copy(chapters = manyChapters)))
        val failure = assertThrows(IllegalArgumentException::class.java) {
            UnifiedLocalBackupCodec.encode(oversized)
        }
        assertTrue(failure.message.orEmpty().contains("chapter metadata"))
    }

    @Test fun rejectsCredentialBearingSourceReferencesAndUnsafeBookLocators() {
        val manga = eu.kanade.tachiyomi.data.backup.models.BackupManga(
            source = 41,
            url = "https://reader.example/story?api_key=secret",
            title = "Credential fixture",
        )
        assertThrows(IllegalArgumentException::class.java) {
            UnifiedLocalBackupCodec.encode(payload().copy(native = Backup(listOf(manga))))
        }

        val book = UnifiedBookBackup(
            fingerprint = "a".repeat(64),
            title = "Fixture book",
            format = "EPUB",
            originalName = "fixture.epub",
            fileBytes = 100,
            addedAt = 1,
            updatedAt = 1,
            locator = "{\"href\":\"https://private.example/chapter.xhtml\"}",
        )
        assertThrows(IllegalArgumentException::class.java) {
            UnifiedLocalBackupCodec.encode(payload().copy(books = listOf(book)))
        }
    }

    @Test fun preservesOnlyAllowlistedReadiumLocatorMediaTypes() {
        val valid = UnifiedBookBackup(
            fingerprint = "a".repeat(64), title = "Fixture book", format = "EPUB",
            originalName = "fixture.epub", fileBytes = 100, addedAt = 1, updatedAt = 1,
            locator = """{"href":"chapter.xhtml","type":"application/xhtml+xml","locations":{"progression":0.4}}""",
        )
        val encoded = UnifiedLocalBackupCodec.encode(payload().copy(books = listOf(valid)))
        val decoded = UnifiedLocalBackupCodec.decode(encoded).payload.books.single()
        assertEquals(valid.locator, decoded.locator)

        val missingType = valid.copy(locator = """{"href":"chapter.xhtml","locations":{"progression":0.4}}""")
        assertThrows(IllegalArgumentException::class.java) {
            UnifiedLocalBackupCodec.encode(payload().copy(books = listOf(missingType)))
        }
        val unsafeType = valid.copy(locator = """{"href":"chapter.xhtml","type":"text/html<script>"}""")
        assertThrows(IllegalArgumentException::class.java) {
            UnifiedLocalBackupCodec.encode(payload().copy(books = listOf(unsafeType)))
        }
        val unknownFields = valid.copy(locator = """{"href":"chapter.xhtml","type":"application/xhtml+xml","title":"private title"}""")
        assertThrows(IllegalArgumentException::class.java) {
            UnifiedLocalBackupCodec.encode(payload().copy(books = listOf(unknownFields)))
        }
    }

    @Test fun acceptsSignedNonzeroNativeSourceIdsAndRejectsTheExcludedLocalSource() {
        val manga = eu.kanade.tachiyomi.data.backup.models.BackupManga(
            source = -73,
            url = "/signed-source-fixture",
            title = "Signed source fixture",
        )
        assertEquals(-73L, UnifiedLocalBackupCodec.decode(
            UnifiedLocalBackupCodec.encode(payload().copy(native = Backup(listOf(manga)))),
        ).payload.native.backupManga.single().source)

        val local = manga.apply { source = 0L }
        val failure = assertThrows(IllegalArgumentException::class.java) {
            UnifiedLocalBackupCodec.encode(payload().copy(native = Backup(listOf(local))))
        }
        assertTrue(failure.message.orEmpty().contains("Local source"))
    }

    private fun payload() = UnifiedLocalBackupPayload(
        native = Backup(backupManga = emptyList()),
        novels = listOf(novel()),
        books = emptyList(),
        seriesReferences = emptyMap(),
        library = UnifiedLibraryBackupSettings(),
    )

    private fun novel() = NovelBackupEntry(
        sourceId = "wikisource.en",
        detail = SourceNovel(
            path = "/wiki/Fixture_novel",
            name = "Fixture novel",
            chapters = emptyList(),
            provenanceUrl = "https://en.wikisource.org/wiki/Fixture_novel",
            rights = "Public domain",
        ),
        favorite = true,
        lastChapterPath = "/wiki/Fixture_novel/Chapter_1",
        lastRead = 7,
        chapters = listOf(
            NovelChapterBackup("/wiki/Fixture_novel/Chapter_1", "Chapter 1", 0, .4, true),
        ),
    )
}
