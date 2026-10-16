package mihon.readingapp.novels

/** Small in-process source fixture for repository upgrade, migration, and inbox tests. */
internal class ControlledNovelSource(
    override val id: String,
    val workPath: String,
    val workName: String,
    chapters: List<NovelChapter>,
) : NovelSource {
    override val name: String = "Controlled fixture $id"
    override val site: String = "https://fixture.invalid"
    @Volatile var currentChapters: List<NovelChapter> = chapters

    override suspend fun catalog(page: Int, search: String, genre: String?) =
        NovelCatalogPage(emptyList(), null, "self-authored fixture")

    override suspend fun detail(path: String): SourceNovel {
        require(path == workPath)
        return SourceNovel(
            path = workPath,
            name = workName,
            chapters = currentChapters.toList(),
            provenanceUrl = "$site/$workPath",
            rights = "Self-authored test fixture",
        )
    }

    override suspend fun chapter(path: String) = "<p>Self-authored test chapter.</p>"
}
