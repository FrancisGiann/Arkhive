package eu.kanade.tachiyomi.ui.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ChapterListPagingTest {
    @Test
    fun `ascending page boundary uses the previous global chapter once`() {
        // Ordered 2, 4 across two single-item pages: chapter 3 is missing at the boundary.
        assertEquals(1, missingChapterCountBefore(null, 2.0, descending = false, firstPage = true))
        assertNull(missingChapterCountBefore(2.0, null, descending = false, firstPage = true, hasMoreAfter = true))
        assertEquals(1, missingChapterCountBefore(2.0, 4.0, descending = false, firstPage = false))
        assertNull(missingChapterCountBefore(4.0, null, descending = false, firstPage = false))
    }

    @Test
    fun `descending page boundary uses the previous global chapter and suppresses page tails`() {
        // Ordered 10, 8, 7. The 10→8 gap appears before 8; page 1's tail must not claim
        // chapters 1 through 9 are missing, and page 2 must not repeat the gap.
        assertNull(missingChapterCountBefore(null, 10.0, descending = true, firstPage = true))
        assertNull(missingChapterCountBefore(10.0, null, descending = true, firstPage = true, hasMoreAfter = true))
        assertEquals(1, missingChapterCountBefore(10.0, 8.0, descending = true, firstPage = false))
        assertNull(missingChapterCountBefore(8.0, 7.0, descending = true, firstPage = false))
    }

    @Test
    fun `descending final page keeps global missing chapter tail`() {
        // With no chapter 1–3 entries, the old global list displayed that gap after chapter 4.
        assertEquals(3, missingChapterCountBefore(4.0, null, descending = true, firstPage = false))
        assertNull(missingChapterCountBefore(4.0, null, descending = true, firstPage = false, hasMoreAfter = true))
    }
}
