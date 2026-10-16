@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package mihon.readingapp.compat

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rx.Observable

class LegacySourceBridgeTest {
    @Test
    fun `retained suspend bridge dispatches legacy Rx catalog search and page methods`() = runBlocking {
        val source = LegacySource()
        assertEquals("Self-authored title", source.getPopularManga(1).mangas.single().title)
        assertTrue(source.getSearchManga(1, "no-match", FilterList()).mangas.isEmpty())
        assertEquals(listOf(7, 2), source.getPageList(chapter()).map { it.index })
    }

    @Test
    fun `partial updates preserve existing values and skip unrequested legacy calls`() = runBlocking {
        val source = LegacySource()
        val existingManga = title("Existing title")
        val existingChapters = listOf(chapter())
        val unchanged = source.getMangaUpdate(existingManga, existingChapters, false, false)
        assertSame(existingManga, unchanged.manga)
        assertSame(existingChapters, unchanged.chapters)
        assertEquals(0, source.detailCalls)
        assertEquals(0, source.chapterCalls)

        val detailsOnly = source.getMangaUpdate(existingManga, existingChapters, true, false)
        assertEquals("Self-authored title", detailsOnly.manga.title)
        assertSame(existingChapters, detailsOnly.chapters)
        assertEquals(1, source.detailCalls)
        assertEquals(0, source.chapterCalls)

        val chaptersOnly = source.getMangaUpdate(existingManga, emptyList(), false, true)
        assertSame(existingManga, chaptersOnly.manga)
        assertEquals(1, chaptersOnly.chapters.size)
        assertEquals(1, source.detailCalls)
        assertEquals(1, source.chapterCalls)
    }

    @Test
    fun `legacy Rx parser errors reach caller rather than returning a fake empty chapter`() {
        val source = LegacySource()
        source.failPages = true
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { source.getPageList(chapter()) }
        }
        assertEquals("Controlled parser failure", error.message)
    }

    private class LegacySource : CatalogueSource {
        override val id = 141414L
        override val name = "Self-authored bridge test"
        override val lang = "en"
        override val supportsLatest = false
        var detailCalls = 0
        var chapterCalls = 0
        var failPages = false
        override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.just(MangasPage(listOf(title()), false))
        override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
            Observable.just(MangasPage(if (query == "no-match") emptyList() else listOf(title()), false))
        override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
            detailCalls++
            return Observable.just(title())
        }
        override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
            chapterCalls++
            return Observable.just(listOf(chapter()))
        }
        override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = if (failPages) {
            Observable.error(IllegalStateException("Controlled parser failure"))
        } else {
            Observable.just(listOf(Page(7), Page(2)))
        }
    }

    companion object {
        private fun title(name: String = "Self-authored title") = SManga.create().apply {
            url = "/original"
            title = name
        }

        private fun chapter() = SChapter.create().apply {
            url = "/original/1"
            name = "Original chapter"
            chapter_number = 1f
        }
    }
}
