package mihon.readingapp.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterPageWindowTest {
    @Test
    fun `page windows retain every item across first middle and last page`() {
        val chapters = (0 until 10_001).toList()
        val windows = (0 until chapterPageWindow(chapters.size, 0).pageCount)
            .map { chapterPageWindow(chapters.size, it) }

        assertEquals(101, windows.size)
        assertEquals(0, windows.first().startIndex)
        assertEquals(100, windows.first().endIndexExclusive)
        assertEquals(5_000, windows[50].startIndex)
        assertEquals(5_100, windows[50].endIndexExclusive)
        assertEquals(10_000, windows.last().startIndex)
        assertEquals(10_001, windows.last().endIndexExclusive)
        assertEquals(chapters, windows.flatMap { chapters.subList(it.startIndex, it.endIndexExclusive) })
    }

    @Test
    fun `empty results and page changes clamp safely`() {
        assertEquals(ChapterPageWindow(0, 0, 0, 0), chapterPageWindow(0, 9))
        assertEquals(ChapterPageWindow(2, 3, 200, 201), chapterPageWindow(201, 9))
        assertEquals(ChapterPageWindow(0, 3, 0, 100), chapterPageWindow(201, -9))
    }

    @Test
    fun `invalid chapter page dimensions are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { chapterPageWindow(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { chapterPageWindow(1, 0, 0) }
    }

    @Test
    fun `page bounds do not overflow for maximum supported index values`() {
        assertEquals(
            ChapterPageWindow(Int.MAX_VALUE - 1, Int.MAX_VALUE, Int.MAX_VALUE - 1, Int.MAX_VALUE),
            chapterPageWindow(Int.MAX_VALUE, Int.MAX_VALUE, 1),
        )
        assertEquals(
            ChapterPageWindow(0, 1, 0, Int.MAX_VALUE),
            chapterPageWindow(Int.MAX_VALUE, 9, Int.MAX_VALUE),
        )
    }

    @Test
    fun `chapter search preserves title and numeric matching`() {
        assertEquals(true, chapterMatchesSearch("The Lantern Keeper", 42.5, "lantern"))
        assertEquals(true, chapterMatchesSearch("A quiet chapter", 42.5, "42.5"))
        assertEquals(true, chapterMatchesSearch("A quiet chapter", null, "  "))
        assertEquals(false, chapterMatchesSearch("A quiet chapter", null, "43"))
    }

    @Test
    fun `page slice carries the true previous row and only the final page owns its trailing boundary`() {
        val rows = (0 until 251).toList()
        val first = chapterPageSlice(rows, chapterPageWindow(rows.size, 0))
        val middle = chapterPageSlice(rows, chapterPageWindow(rows.size, 1))
        val last = chapterPageSlice(rows, chapterPageWindow(rows.size, 2))

        assertEquals(null, first.previousItem)
        assertTrue(first.hasMoreAfter)
        assertEquals(99, middle.previousItem)
        assertEquals(100, middle.items.first())
        assertTrue(middle.hasMoreAfter)
        assertEquals(199, last.previousItem)
        assertEquals(200, last.items.first())
        assertEquals(false, last.hasMoreAfter)
    }

    @Test
    fun `search job identity changes for a replaced chapter list even when its contents compare equal`() {
        val oldRows = listOf("Chapter 1", "Chapter 2")
        val replacementRows = oldRows.toList()
        val oldKey = ChapterListIdentity(oldRows)
        val replacementKey = ChapterListIdentity(replacementRows)

        assertEquals(oldRows, replacementRows)
        assertEquals(false, oldKey == replacementKey)
        assertEquals(true, replacementKey.matches(replacementRows))
        assertEquals(false, replacementKey.matches(oldRows))
    }
}
