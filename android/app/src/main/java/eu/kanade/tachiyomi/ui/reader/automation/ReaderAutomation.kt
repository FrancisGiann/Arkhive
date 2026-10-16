package eu.kanade.tachiyomi.ui.reader.automation

import kotlin.math.min

enum class ReaderAutomationMode {
    CONTINUOUS,
    TIMED_STEP,
}

data class ReaderAutomationSettings(
    val mode: ReaderAutomationMode = ReaderAutomationMode.TIMED_STEP,
    val speedDpPerSecond: Int = 60,
    val intervalSeconds: Int = 8,
) {
    fun normalized() = copy(
        speedDpPerSecond = speedDpPerSecond.coerceIn(16, 240),
        intervalSeconds = intervalSeconds.coerceIn(2, 60),
    )
}

enum class ReaderAutomationPause {
    USER,
    BACKGROUND,
    CHAPTER_END,
    NOT_READY,
    REDUCED_MOTION,
    VIEWER_CHANGED,
}

sealed interface ReaderAutomationAction {
    data object Idle : ReaderAutomationAction
    data object Step : ReaderAutomationAction
    data class Scroll(val pixels: Int) : ReaderAutomationAction
    data class Paused(val reason: ReaderAutomationPause) : ReaderAutomationAction
}

/**
 * A monotonic, single-chapter playback clock. There is no automatic restart or catch-up.
 * Kept independent of Android so boundary and pacing behavior can be tested without a device.
 */
class ReaderAutomationSession {
    var isRunning = false
        private set

    private var chapterId: Long? = null
    private var settings = ReaderAutomationSettings()
    private var lastFrameNanos = 0L
    private var nextStepNanos = 0L
    private var fractionalPixels = 0.0

    fun start(
        chapterId: Long,
        settings: ReaderAutomationSettings,
        nowNanos: Long,
        continuousAllowed: Boolean,
    ): Boolean {
        pause()
        if (settings.mode == ReaderAutomationMode.CONTINUOUS && !continuousAllowed) return false
        this.chapterId = chapterId
        this.settings = settings.normalized()
        lastFrameNanos = nowNanos
        nextStepNanos = nowNanos + this.settings.intervalSeconds * NANOS_PER_SECOND
        isRunning = true
        return true
    }

    fun pause() {
        isRunning = false
        fractionalPixels = 0.0
    }

    fun nextStepDelayMillis(nowNanos: Long): Long =
        ((nextStepNanos - nowNanos).coerceAtLeast(0L) + 999_999L) / 1_000_000L

    fun next(
        nowNanos: Long,
        currentChapterId: Long?,
        continuousAllowed: Boolean,
        density: Float,
    ): ReaderAutomationAction {
        if (!isRunning) return ReaderAutomationAction.Idle
        if (chapterId != currentChapterId) return paused(ReaderAutomationPause.CHAPTER_END)
        if (settings.mode == ReaderAutomationMode.TIMED_STEP) {
            if (nowNanos < nextStepNanos) return ReaderAutomationAction.Idle
            // A delayed UI thread gets one step, never a burst of skipped pages.
            nextStepNanos = nowNanos + settings.intervalSeconds * NANOS_PER_SECOND
            return ReaderAutomationAction.Step
        }
        if (!continuousAllowed) return paused(ReaderAutomationPause.REDUCED_MOTION)

        val elapsed = (nowNanos - lastFrameNanos).coerceIn(0, MAX_FRAME_NANOS)
        lastFrameNanos = nowNanos
        fractionalPixels += settings.speedDpPerSecond * density.coerceAtLeast(0f) * elapsed / 1_000_000_000.0
        val pixels = fractionalPixels.toInt()
        fractionalPixels -= pixels
        return if (pixels > 0) ReaderAutomationAction.Scroll(pixels) else ReaderAutomationAction.Idle
    }

    /** Restart motion timing after a temporary page/layout wait without catching up. */
    fun resetAfterReadiness(nowNanos: Long) {
        if (!isRunning) return
        lastFrameNanos = nowNanos
        fractionalPixels = 0.0
        if (settings.mode == ReaderAutomationMode.TIMED_STEP) {
            nextStepNanos = nowNanos + settings.intervalSeconds * NANOS_PER_SECOND
        }
    }

    private fun paused(reason: ReaderAutomationPause): ReaderAutomationAction.Paused {
        pause()
        return ReaderAutomationAction.Paused(reason)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val MAX_FRAME_NANOS = 64_000_000L
    }
}

/** Next adapter position in reading order; transitions and preloaded chapters are hard boundaries. */
fun nextPageInChapter(current: Int, direction: Int, chapterId: Long, chapterIds: List<Long?>): Int? {
    if (direction != 1 && direction != -1) return null
    if (chapterIds.getOrNull(current) != chapterId) return null
    val next = current + direction
    return next.takeIf { chapterIds.getOrNull(it) == chapterId }
}

/** Never moves the viewport beyond the last laid-out, readable image of the captured chapter. */
fun boundedScrollPixels(requested: Int, readableBottom: Int, viewportBottom: Int): Int =
    min(requested.coerceAtLeast(0), (readableBottom - viewportBottom).coerceAtLeast(0))

enum class ReaderAutoAdvanceResult {
    ADVANCED,
    WAITING,
    CHAPTER_END,
    NOT_READY,
    UNSUPPORTED,
}
