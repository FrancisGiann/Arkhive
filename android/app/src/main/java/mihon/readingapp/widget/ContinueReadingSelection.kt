package mihon.readingapp.widget

internal fun selectContinueStory(stories: List<ContinueStory>, hidden: Set<String>): ContinueStory? =
    stories.asSequence().filter { it.lastRead > 0 && it.key !in hidden }.maxByOrNull { it.lastRead }

/** Keep a partially read history chapter even when earlier chapters remain unread. */
internal fun selectContinueChapter(
    chapters: List<tachiyomi.domain.chapter.model.Chapter>,
    latestChapterId: Long?,
): tachiyomi.domain.chapter.model.Chapter? {
    val current = chapters.indexOfFirst { it.id == latestChapterId }
    if (current < 0) return chapters.firstOrNull { !it.read }
    if (!chapters[current].read) return chapters[current]
    return chapters.drop(current + 1).firstOrNull { !it.read }
}
