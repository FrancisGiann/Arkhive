package readingapp.reader

import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationAction
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationMode
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationPause
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationSession
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationSettings
import eu.kanade.tachiyomi.ui.reader.automation.boundedScrollPixels
import eu.kanade.tachiyomi.ui.reader.automation.nextPageInChapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderAutomationTest {
    private val chapterId = 42L
    private val timed = ReaderAutomationSettings(intervalSeconds = 8)
    private val continuous = ReaderAutomationSettings(mode = ReaderAutomationMode.CONTINUOUS, speedDpPerSecond = 60)

    @Test
    fun `opening a clock does not autoplay`() {
        val session = ReaderAutomationSession()
        assertFalse(session.isRunning)
        assertEquals(ReaderAutomationAction.Idle, session.next(60_000_000_000, chapterId, true, 1f))
    }

    @Test
    fun `timed steps wait the selected interval`() {
        val session = ReaderAutomationSession()
        assertTrue(session.start(chapterId, timed, 0, false))
        assertEquals(ReaderAutomationAction.Idle, session.next(7_999_999_999, chapterId, false, 1f))
        assertEquals(1L, session.nextStepDelayMillis(7_999_999_999))
        assertEquals(ReaderAutomationAction.Step, session.next(8_000_000_000, chapterId, false, 1f))
        assertEquals(ReaderAutomationAction.Idle, session.next(8_000_000_001, chapterId, false, 1f))
    }

    @Test
    fun `late callbacks never fast forward through a backlog`() {
        val session = ReaderAutomationSession()
        session.start(chapterId, timed, 0, true)
        assertEquals(ReaderAutomationAction.Step, session.next(80_000_000_000, chapterId, true, 1f))
        assertEquals(ReaderAutomationAction.Idle, session.next(80_000_000_001, chapterId, true, 1f))
        assertEquals(8_000L, session.nextStepDelayMillis(80_000_000_001))
    }

    @Test
    fun `120Hz motion retains fractional pixels instead of freezing at low speed`() {
        val session = ReaderAutomationSession()
        session.start(chapterId, continuous, 0, true)
        var distance = 0
        for (frame in 1..120) {
            val action = session.next(frame * 8_333_333L, chapterId, true, 1f)
            distance += (action as? ReaderAutomationAction.Scroll)?.pixels ?: 0
        }
        assertTrue(distance in 59..60)
    }

    @Test
    fun `scroll speed accounts for display density`() {
        val session = ReaderAutomationSession()
        session.start(chapterId, continuous, 0, true)
        var distance = 0
        for (frame in 1..120) {
            val action = session.next(frame * 8_333_333L, chapterId, true, 2f)
            distance += (action as? ReaderAutomationAction.Scroll)?.pixels ?: 0
        }
        assertTrue(distance in 119..120)
    }

    @Test
    fun `a stalled frame cannot jump whole screens`() {
        val session = ReaderAutomationSession()
        session.start(chapterId, continuous.copy(speedDpPerSecond = 240), 0, true)
        val action = session.next(10_000_000_000, chapterId, true, 1f) as ReaderAutomationAction.Scroll
        assertTrue(action.pixels in 1..16)
    }

    @Test
    fun `temporary readiness wait resets both step and motion clocks without catch up`() {
        val scroll = ReaderAutomationSession()
        scroll.start(chapterId, continuous.copy(speedDpPerSecond = 240), 0, true)
        assertTrue(scroll.next(64_000_000, chapterId, true, 1f) is ReaderAutomationAction.Scroll)
        scroll.resetAfterReadiness(50_000_000_000)
        val firstFrame = scroll.next(50_064_000_000, chapterId, true, 1f) as ReaderAutomationAction.Scroll
        assertTrue(firstFrame.pixels in 1..16, "Continuous motion resumes with one frame of distance")

        val timedSession = ReaderAutomationSession()
        timedSession.start(chapterId, timed, 0, true)
        timedSession.resetAfterReadiness(20_000_000_000)
        assertEquals(ReaderAutomationAction.Idle, timedSession.next(27_999_999_999, chapterId, true, 1f))
        assertEquals(ReaderAutomationAction.Step, timedSession.next(28_000_000_000, chapterId, true, 1f))
    }

    @Test
    fun `chapter change pauses before movement`() {
        val session = ReaderAutomationSession()
        session.start(chapterId, timed, 0, true)
        assertEquals(
            ReaderAutomationAction.Paused(ReaderAutomationPause.CHAPTER_END),
            session.next(8_000_000_000, chapterId + 1, true, 1f),
        )
        assertFalse(session.isRunning)
    }

    @Test
    fun `lost chapter pauses rather than continuing blind`() {
        val session = ReaderAutomationSession()
        session.start(chapterId, continuous, 0, true)
        assertEquals(
            ReaderAutomationAction.Paused(ReaderAutomationPause.CHAPTER_END),
            session.next(16_000_000, null, true, 1f),
        )
    }

    @Test
    fun `system reduced motion stops continuous playback`() {
        val session = ReaderAutomationSession()
        session.start(chapterId, continuous, 0, true)
        assertEquals(
            ReaderAutomationAction.Paused(ReaderAutomationPause.REDUCED_MOTION),
            session.next(16_000_000, chapterId, false, 1f),
        )
        assertFalse(session.isRunning)
        assertFalse(session.start(chapterId, continuous, 20_000_000, false))
    }

    @Test
    fun `manual pause and fresh start never resume an old countdown`() {
        val session = ReaderAutomationSession()
        session.start(chapterId, timed, 0, true)
        session.pause()
        assertEquals(ReaderAutomationAction.Idle, session.next(80_000_000_000, chapterId, true, 1f))
        session.start(chapterId, timed, 100_000_000_000, true)
        assertEquals(ReaderAutomationAction.Idle, session.next(107_000_000_000, chapterId, true, 1f))
        assertEquals(ReaderAutomationAction.Step, session.next(108_000_000_000, chapterId, true, 1f))
    }

    @Test
    fun `persisted out of range settings are bounded`() {
        assertEquals(16, continuous.copy(speedDpPerSecond = -1).normalized().speedDpPerSecond)
        assertEquals(240, continuous.copy(speedDpPerSecond = Int.MAX_VALUE).normalized().speedDpPerSecond)
        assertEquals(2, timed.copy(intervalSeconds = -1).normalized().intervalSeconds)
        assertEquals(60, timed.copy(intervalSeconds = Int.MAX_VALUE).normalized().intervalSeconds)
    }

    @Test
    fun `RTL advances left and never enters a transition`() {
        val items = listOf(43L, null, chapterId, chapterId, null, 41L)
        assertEquals(2, nextPageInChapter(3, -1, chapterId, items))
        assertNull(nextPageInChapter(2, -1, chapterId, items))
    }

    @Test
    fun `loaded adjacent chapter remains a boundary when transitions are hidden`() {
        val items = listOf(41L, chapterId, chapterId, 43L)
        assertEquals(2, nextPageInChapter(1, 1, chapterId, items))
        assertNull(nextPageInChapter(2, 1, chapterId, items))
        assertNull(nextPageInChapter(2, 0, chapterId, items))
        assertNull(nextPageInChapter(0, 1, chapterId, items))
    }

    @Test
    fun `webtoon delta stops at visible decoded chapter end`() {
        assertEquals(60, boundedScrollPixels(600, readableBottom = 1060, viewportBottom = 1000))
        assertEquals(8, boundedScrollPixels(8, readableBottom = 1060, viewportBottom = 1000))
        assertEquals(0, boundedScrollPixels(600, readableBottom = 990, viewportBottom = 1000))
        assertEquals(0, boundedScrollPixels(-1, readableBottom = 1060, viewportBottom = 1000))
    }
}
