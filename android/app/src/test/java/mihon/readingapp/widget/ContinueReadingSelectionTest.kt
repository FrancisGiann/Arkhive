package mihon.readingapp.widget

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ContinueReadingSelectionTest {
    private fun story(key: String, time: Long) = ContinueStory(key, "Private title", "", "", null, 0.5, "", time)
    @Test fun hiddenLatestFallsBackAcrossFormats() {
        val comic = story("manga:1", 10)
        val book = story("book:2", 30)
        val novel = story("novel:3", 20)
        assertEquals(novel, selectContinueStory(listOf(comic, book, novel), setOf(book.key)))
    }
    @Test fun entirelyHiddenLibraryHasNoLauncherContent() {
        val items = listOf(story("book:1", 40), story("novel:2", 50))
        assertNull(selectContinueStory(items, items.map { it.key }.toSet()))
    }
    @Test fun unreadAndEmptyLibrariesHaveNoResumeTarget() {
        assertNull(selectContinueStory(emptyList(), emptySet()))
        assertNull(selectContinueStory(listOf(story("manga:1", 0)), emptySet()))
    }
    @Test fun latestReadingTimeWinsRegardlessOfFormatOrOrder() {
        val newest = story("book:1", 60)
        assertEquals(newest, selectContinueStory(listOf(newest, story("novel:2", 50), story("manga:3", 40)), emptySet()))
    }

    private fun chapter(id: Long, read: Boolean = false, page: Int = 0) =
        tachiyomi.domain.chapter.model.Chapter.create().copy(id = id, read = read, lastPageRead = page)

    @Test fun partialHistoryChapterRetainsItsSavedPageDespiteEarlierUnreadChapters() {
        val current = chapter(3, page = 17)
        assertEquals(current, selectContinueChapter(listOf(chapter(1), chapter(2), current), 3))
    }
    @Test fun finishedHistoryChapterAdvancesForwardAndAllFinishedReturnsToDetail() {
        val next = chapter(3)
        assertEquals(next, selectContinueChapter(listOf(chapter(1), chapter(2, read = true), next), 2))
        assertNull(selectContinueChapter(listOf(chapter(1, read = true), chapter(2, read = true)), 2))
    }
    @Test fun deletedHistoryChapterFallsBackToAnExistingUnreadChapter() {
        val next = chapter(2)
        assertEquals(next, selectContinueChapter(listOf(chapter(1, read = true), next), 99))
    }
}
