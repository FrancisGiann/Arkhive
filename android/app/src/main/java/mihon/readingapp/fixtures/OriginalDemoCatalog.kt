package mihon.readingapp.fixtures

/** Original synthetic content. No external provider, copyrighted pages, or network request is used. */
object OriginalDemoCatalog {
    const val HOST = "fixtures.readingapp.invalid"
    const val BASE_URL = "https://$HOST"
    const val CHAPTER_COUNT = 3
    const val PAGES_PER_CHAPTER = 6

    enum class Format(val label: String) { MANGA("Manga · RTL"), WEBTOON("Vertical webtoon") }

    data class Title(
        val slug: String,
        val name: String,
        val format: Format,
        val genre: String,
        val accent: Int,
    )

    val titles = listOf(
        Title("neon-courier", "Neon Courier", Format.MANGA, "Action", 0xFFB4A0FF.toInt()),
        Title("moonlit-archive", "Moonlit Archive", Format.MANGA, "Mystery", 0xFFFFB56B.toInt()),
        Title("rainlight-district", "Rainlight District", Format.WEBTOON, "Slice of life", 0xFF64D9C8.toInt()),
        Title("lantern-circuit", "Lantern Circuit", Format.WEBTOON, "Adventure", 0xFFFF7F98.toInt()),
    )

    fun search(format: Format, query: String): List<Title> = titles.filter {
        it.format == format && (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true))
    }

    fun title(slug: String): Title = titles.firstOrNull { it.slug == slug }
        ?: error("Unknown original demo title: $slug")

    fun titleSlug(path: String): String = path.trim('/').split('/').first().also { title(it) }

    fun chapterPaths(slug: String): List<String> {
        title(slug)
        return (CHAPTER_COUNT downTo 1).map { "/$slug/chapter/$it" }
    }

    fun pageUrls(chapterPath: String): List<String> {
        val parts = chapterPath.trim('/').split('/')
        require(parts.size == 3 && parts[1] == "chapter") { "Invalid demo chapter" }
        title(parts[0])
        val chapter = parts[2].toInt()
        require(chapter in 1..CHAPTER_COUNT)
        return (1..PAGES_PER_CHAPTER).map { "$BASE_URL/pages/${parts[0]}/$chapter/$it.png?art=storyboard2" }
    }
}
