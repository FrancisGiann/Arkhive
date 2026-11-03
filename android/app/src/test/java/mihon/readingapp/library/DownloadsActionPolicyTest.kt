package mihon.readingapp.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadsActionPolicyTest {
    @Test fun trueEmptyStateHasNoQueueActionsAndNoPausedMangaClaim() {
        val state = DownloadsActionPolicy.resolve(0, false, 0, false, false, false, false)
        assertFalse(state.showPause)
        assertFalse(state.showResume)
        assertEquals("Manga idle", state.mangaStatus)
        assertEquals("Novels idle", state.novelStatus)
        assertEquals("Smart off", state.smartStatus)
    }

    @Test fun activeStoppedAndSmartStatesExposeOnlyActionsThatApply() {
        val mangaRunning = DownloadsActionPolicy.resolve(2, true, 0, false, false, false, false)
        assertTrue(mangaRunning.showPause)
        assertFalse(mangaRunning.showResume)
        assertEquals("Manga active", mangaRunning.mangaStatus)

        val mangaWaiting = DownloadsActionPolicy.resolve(2, false, 0, false, false, false, false)
        assertFalse(mangaWaiting.showPause)
        assertTrue(mangaWaiting.showResume)
        assertEquals("Manga waiting", mangaWaiting.mangaStatus)

        val smartWaiting = DownloadsActionPolicy.resolve(0, false, 0, false, true, false, false)
        assertTrue(smartWaiting.showPause)
        assertFalse(smartWaiting.showResume)
        assertEquals("Smart enabled · waiting", smartWaiting.smartStatus)

        val smartRunning = DownloadsActionPolicy.resolve(0, false, 0, false, true, false, true)
        assertTrue(smartRunning.showPause)
        assertEquals("Smart running", smartRunning.smartStatus)

        val runningRulesRecentlyDisabled = DownloadsActionPolicy.resolve(0, false, 0, false, false, false, true)
        assertTrue(runningRulesRecentlyDisabled.showPause)
        assertEquals("Smart running", runningRulesRecentlyDisabled.smartStatus)

        val smartPaused = DownloadsActionPolicy.resolve(0, false, 0, false, true, true, false)
        assertFalse(smartPaused.showPause)
        assertTrue(smartPaused.showResume)
        assertEquals("Smart paused", smartPaused.smartStatus)
    }

    @Test fun mixedAndHiddenOnlyQueuesKeepGlobalControlsWithoutHiddenCounts() {
        // These raw counts include hidden jobs. Visible counts and titles are not accepted by this
        // policy, so global controls remain available without disclosing hidden rows.
        val hiddenMangaActive = DownloadsActionPolicy.resolve(1, true, 0, false, false, false, false)
        assertTrue(hiddenMangaActive.showPause)
        assertEquals("Manga active", hiddenMangaActive.mangaStatus)

        val mixed = DownloadsActionPolicy.resolve(
            mangaQueueCount = 1,
            mangaRunning = false,
            novelQueueCount = 2,
            novelsPaused = false,
            smartRulesEnabled = true,
            smartPaused = true,
            smartRunning = false,
        )
        assertTrue(mixed.showPause)
        assertTrue(mixed.showResume)
        assertEquals("Manga waiting", mixed.mangaStatus)
        assertEquals("Novels ready", mixed.novelStatus)
        assertEquals("Smart paused", mixed.smartStatus)
    }

    @Test fun emptyPersistentNovelPauseGetsAnHonestGlobalResumeAction() {
        val pausedFutureQueue = DownloadsActionPolicy.resolve(0, false, 0, true, false, false, false)
        assertFalse(pausedFutureQueue.showPause)
        assertTrue(pausedFutureQueue.showResume)
        assertEquals("Resume downloads", pausedFutureQueue.resumeLabel)
        assertEquals("Novel downloads paused", pausedFutureQueue.novelStatus)
    }

    @Test fun emptyVisibleQueuesCanHaveSmartPauseAndNovelResumeTargets() {
        val mixedEmpty = DownloadsActionPolicy.resolve(
            mangaQueueCount = 0,
            mangaRunning = false,
            novelQueueCount = 0,
            novelsPaused = true,
            smartRulesEnabled = true,
            smartPaused = false,
            smartRunning = false,
        )
        assertTrue(mixedEmpty.showPause)
        assertTrue(mixedEmpty.showResume)
        assertEquals("Pause all · Smart", mixedEmpty.pauseLabel)
        assertEquals("Resume all · novels", mixedEmpty.resumeLabel)
        assertEquals("Manga idle", mixedEmpty.mangaStatus)
        assertEquals("Novel downloads paused", mixedEmpty.novelStatus)
        assertEquals("Smart enabled · waiting", mixedEmpty.smartStatus)
    }
}
