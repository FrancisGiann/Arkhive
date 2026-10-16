package mihon.readingapp.fixtures

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.OkHttpClient

/** Uses the normal cover, chapter, image-reader and optional-download paths entirely offline. */
class OriginalDemoSource(private val format: OriginalDemoCatalog.Format) : HttpSource() {
    override val id = when (format) {
        OriginalDemoCatalog.Format.MANGA -> MANGA_ID
        OriginalDemoCatalog.Format.WEBTOON -> WEBTOON_ID
    }
    override val name = "Arkhive Originals · ${format.label} (offline demo)"
    override val lang = "en"
    override val supportsLatest = false
    override val baseUrl = OriginalDemoCatalog.BASE_URL
    override val client: OkHttpClient = OriginalDemoImages.client

    // Avoid initializing a real provider's network stack or user-agent for synthetic local content.
    override fun headersBuilder(): Headers.Builder = Headers.Builder().add("User-Agent", "ReadingApp-Original-Demo")

    override suspend fun getPopularManga(page: Int): MangasPage = catalog(page, "")

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = catalog(page, query)

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException(
        "The original offline demo has no provider latest-updates or ranking feed",
    )

    private fun catalog(page: Int, query: String) = MangasPage(
        if (page == 1) OriginalDemoCatalog.search(format, query).map(::manga) else emptyList(),
        hasNextPage = false,
    )

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val title = OriginalDemoCatalog.title(OriginalDemoCatalog.titleSlug(manga.url))
        require(title.format == format)
        val newChapters = if (fetchChapters) {
            OriginalDemoCatalog.chapterPaths(title.slug).map { path ->
                SChapter.create().apply {
                    url = path
                    chapter_number = path.substringAfterLast('/').toFloat()
                    name = "Chapter ${chapter_number.toInt()} · Original demo"
                    scanlator = "Arkhive original synthetic fixture"
                    // Fixed fixture dates are not a claim of live publishing activity.
                    date_upload = 1_760_400_000_000L + chapter_number.toLong() * 86_400_000L
                }
            }
        } else {
            chapters
        }
        return SMangaUpdate(if (fetchDetails) manga(title) else manga, newChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val title = OriginalDemoCatalog.title(OriginalDemoCatalog.titleSlug(chapter.url))
        require(title.format == format)
        val urls = controlledPageLists[chapter.url] ?: OriginalDemoCatalog.pageUrls(chapter.url)
        return urls.mapIndexed { index, url -> Page(index, url, url) }
    }

    override suspend fun getImageUrl(page: Page): String = requireNotNull(page.imageUrl)

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    private fun manga(title: OriginalDemoCatalog.Title): SManga = SManga.create().apply {
        url = "/${title.slug}"
        this.title = "${title.name} · Original demo"
        thumbnail_url = "$baseUrl/covers/${title.slug}.png?art=storyboard1"
        author = "Arkhive original synthetic fixture"
        artist = author
        genre = "${title.genre}, Original demo, ${title.format.label}"
        description = "Original offline layout fixture. Three synthetic chapters with six generated pages each. " +
            "No real publisher or scanlation source is included. " +
            if (format == OriginalDemoCatalog.Format.MANGA) {
                "Select Right to left in the reader for the intended paged manga layout."
            } else {
                "Select Webtoon in the reader for the intended continuous vertical layout."
            }
        status = SManga.COMPLETED
        initialized = true
    }

    companion object {
        internal val controlledPageLists = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
        const val MANGA_ID = 0x524541444D414E47L
        const val WEBTOON_ID = 0x5245414457454254L

        fun createAll(): List<OriginalDemoSource> = OriginalDemoCatalog.Format.entries.map(::OriginalDemoSource)
    }
}
