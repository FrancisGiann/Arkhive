package mihon.readingapp.widget

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompactBookplateLayoutTest {
    @Test
    fun approved180DpGeometryKeepsTheCoverAndActionProminent() {
        val metrics = compactBookplateMetrics(180, 180)

        assertEquals(78f, metrics.coverWidth, .05f)
        assertEquals(131f, metrics.coverHeight)
        assertEquals(83f, metrics.copyWidth, .05f)
        assertEquals(33f, metrics.buttonHeight)
        assertEquals(4, metrics.maxTitleLines)
        assertTrue(metrics.titleMaxHeight > 3 * 14f * 1.1f)
    }

    @Test
    fun approved160DpGeometryScalesTheBookplateWithoutHidingProgress() {
        val metrics = compactBookplateMetrics(160, 160)

        assertEquals(160f / 180f, metrics.scale, 0.0001f)
        assertEquals(69.773f, metrics.coverWidth, 0.02f)
        assertEquals(117f, metrics.coverHeight, 0.02f)
        assertEquals(74.227f, metrics.copyWidth, 0.02f)
        assertEquals(29f, metrics.buttonHeight, 0.02f)
        assertTrue(metrics.maxTitleLines >= 3)
        assertEquals(28f, metrics.ringDiameter)
    }

    @Test
    fun onlyTightOrLargeFontLayoutsKeepTheLegacyCompactTreatment() {
        assertTrue(isTightCompactWidget(110, 110, 1f))
        assertTrue(isTightCompactWidget(160, 130, 1f))
        assertTrue(isTightCompactWidget(160, 160, 1.31f))
        assertFalse(isTightCompactWidget(160, 160, 1f))
        assertFalse(isTightCompactWidget(180, 180, 1.3f))
        assertTrue(usesCompactBookplate(160, 160, 1f))
        assertTrue(usesCompactBookplate(180, 180, 1f))
        assertFalse(usesCompactBookplate(280, 180, 1f))
        assertFalse(usesCompactBookplate(180, 280, 1f))
        assertFalse(usesCompactBookplate(240, 240, 1f))
        assertFalse(usesCompactBookplate(160, 160, 1.31f))
    }

    @Test
    fun storyCopyKeepsCountRingAndResumeDescriptionTogether() {
        val copy = compactBookplateCopy(
            story(
                key = "manga:42",
                progress = .1727,
                status = "19 / 110 chapters read",
            ),
            locked = false,
        )

        assertEquals("Childhood Friend of the Zenith", copy.title)
        assertEquals("19 / 110", copy.count)
        assertEquals(.1727f, copy.progress!!, 0.0001f)
        assertEquals("Resume reading", copy.action)
        assertEquals("Resume reading Childhood Friend of the Zenith. 19 / 110 read", copy.contentDescription)
    }

    @Test
    fun emptyAndUnknownProgressStatesDoNotInventAProgressRing() {
        val empty = compactBookplateCopy(story = null, locked = false)
        assertEquals("Your next chapter", empty.title)
        assertEquals("Open library", empty.action)
        assertNull(empty.count)
        assertNull(empty.progress)
        assertFalse(empty.hasProgress)

        val unknown = compactBookplateCopy(
            story("novel:7", Double.NaN, "19 / 110 read"),
            locked = false,
        )
        assertNull(unknown.count)
        assertNull(unknown.progress)
        assertFalse(unknown.hasProgress)
    }

    @Test
    fun booksUseTheirNumericProgressAndLockedFallbackKeepsUnlockAction() {
        val book = compactBookplateCopy(
            story("book:book-id", .496, "49% · Saved offline"),
            locked = false,
        )
        assertEquals("50%", book.count)
        assertEquals(.496f, book.progress!!, 0.0001f)

        val locked = compactBookplateCopy(story = null, locked = true)
        assertEquals("Your library is locked", locked.title)
        assertEquals("Unlock Arkhive", locked.action)
        assertEquals("Unlock Arkhive", locked.contentDescription)
    }

    private fun story(
        key: String,
        progress: Double,
        status: String,
    ) = ContinueStory(
        key = key,
        title = "Childhood Friend of the Zenith",
        author = "",
        kind = "",
        cover = null,
        progress = progress,
        status = status,
        lastRead = 1,
    )
}
