@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package mihon.readingapp.compat.fixture

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import rx.Observable

/** Self-authored legacy-shaped fixture compiled against the pinned host, not a historical third-party APK. */
class LegacyFixtureFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(LegacyFixtureSource())
}

class ModernFixtureFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(ModernFixtureSource())
}

class LegacyFixtureSource : CatalogueSource {
    override val id = 0x5241434F4E543134L
    override val name = "Controlled legacy 1.4 fixture"
    override val lang = "en"
    override val supportsLatest = false

    // Intentionally implements only the deprecated Rx contract; the retained host supplies suspend bridges.
    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.just(catalog(page, ""))
    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.error(UnsupportedOperationException())
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        Observable.just(catalog(page, query))
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.just(title())
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.just(chapters())
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.just(pages())
}

class ModernFixtureSource : Source {
    override val id = 0x5241434F4E543136L
    override val name = "Controlled suspend 1.6 fixture"
    override val lang = "en"
    override val supportsLatest = false

    override suspend fun getPopularManga(page: Int): MangasPage = catalog(page, "")
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = catalog(page, query)
    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()
    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = SMangaUpdate(if (fetchDetails) title() else manga, if (fetchChapters) chapters() else chapters)
    override suspend fun getPageList(chapter: SChapter): List<Page> = pages()
}

private fun catalog(page: Int, query: String): MangasPage = MangasPage(
    if (page == 1 && (query.isBlank() || "Original fixture".contains(query, true))) listOf(title()) else emptyList(),
    false,
)

private fun title(): SManga = SManga.create().apply {
    url = "/original-fixture"
    title = "Original fixture"
    description = "Self-authored, offline runtime contract fixture. No provider content."
    status = SManga.COMPLETED
    initialized = true
}

private fun chapters(): List<SChapter> = listOf(SChapter.create().apply {
    url = "/original-fixture/chapter/1"
    name = "Original chapter"
    chapter_number = 1f
})

private fun pages(): List<Page> = listOf(
    Page(7, imageUrl = "https://fixtures.readingapp.invalid/controlled/page-one.png"),
    Page(2, imageUrl = "https://fixtures.readingapp.invalid/controlled/page-two.png"),
)
