package mihon.readingapp.library

/** Presentation and action availability for the global Downloads controls. */
data class DownloadsActionState(
    val showPause: Boolean,
    val showResume: Boolean,
    val pauseLabel: String,
    val resumeLabel: String,
    val mangaStatus: String,
    val novelStatus: String,
    val smartStatus: String,
)

object DownloadsActionPolicy {
    fun resolve(
        mangaQueueCount: Int,
        mangaRunning: Boolean,
        novelQueueCount: Int,
        novelsPaused: Boolean,
        smartRulesEnabled: Boolean,
        smartPaused: Boolean,
        smartRunning: Boolean,
    ): DownloadsActionState {
        require(mangaQueueCount >= 0 && novelQueueCount >= 0)

        val mangaCanPause = mangaQueueCount > 0 && mangaRunning
        val mangaCanResume = mangaQueueCount > 0 && !mangaRunning
        val novelsCanPause = novelQueueCount > 0 && !novelsPaused
        val novelsCanResume = novelsPaused
        val smartCanPause = smartRunning || smartRulesEnabled && !smartPaused
        val smartCanResume = smartRulesEnabled && smartPaused

        val pauseTargets = mangaCanPause || novelsCanPause || smartCanPause
        val resumeTargets = mangaCanResume || novelsCanResume || smartCanResume
        val resumeOnlyNovelPause = novelsPaused && novelQueueCount == 0 &&
            mangaQueueCount == 0 && !smartRulesEnabled && !smartPaused
        val emptyMixedTargets = mangaQueueCount == 0 && novelQueueCount == 0 && pauseTargets && resumeTargets
        val pauseLabel = when {
            emptyMixedTargets && smartCanPause -> "Pause all · Smart"
            else -> "Pause all"
        }
        val resumeLabel = when {
            emptyMixedTargets && novelsCanResume -> "Resume all · novels"
            emptyMixedTargets && smartCanResume -> "Resume all · Smart"
            resumeOnlyNovelPause -> "Resume downloads"
            else -> "Resume all"
        }

        return DownloadsActionState(
            showPause = pauseTargets,
            showResume = resumeTargets,
            pauseLabel = pauseLabel,
            resumeLabel = resumeLabel,
            mangaStatus = when {
                mangaQueueCount == 0 -> "Manga idle"
                mangaRunning -> "Manga active"
                else -> "Manga waiting"
            },
            novelStatus = when {
                novelQueueCount > 0 && novelsPaused -> "Novels paused"
                novelQueueCount > 0 -> "Novels ready"
                novelsPaused -> "Novel downloads paused"
                else -> "Novels idle"
            },
            smartStatus = when {
                smartRunning -> "Smart running"
                smartRulesEnabled && smartPaused -> "Smart paused"
                !smartRulesEnabled -> "Smart off"
                else -> "Smart enabled · waiting"
            },
        )
    }
}
