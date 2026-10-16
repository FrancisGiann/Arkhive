package eu.kanade.tachiyomi.ui.reader.automation

import android.animation.ValueAnimator
import mihon.app.di.appGraph
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer

data class ReaderAutomationUiState(
    val settings: ReaderAutomationSettings = ReaderAutomationSettings(),
    val running: Boolean = false,
    val continuousAvailable: Boolean = false,
    val message: String = "Starts only when you choose Start. Stops at the end of this chapter.",
)

/**
 * Activity-owned playback driver. Only continuous motion schedules display-frame callbacks;
 * timed steps use one delayed task, and a paused/static page schedules nothing.
 */
class ReaderAutomationController(
    context: Context,
    private val viewer: () -> Viewer?,
    private val chapterId: () -> Long?,
    private val readerActive: () -> Boolean,
) : Choreographer.FrameCallback {
    private val reducedMotion = context.appGraph.uiPreferences.arkhiveReducedMotion
    private val preferences = context.getSharedPreferences("readingapp_reader_automation", Context.MODE_PRIVATE)
    private val accessibility = context.getSystemService<AccessibilityManager>()
    private val density = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    private val session = ReaderAutomationSession()
    private val readinessScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var readinessJob: Job? = null
    private var readinessGeneration = 0L
    private var modeKey = 0
    private var activeChapterId: Long? = null
    private var touchInterruptedPlayback = false
    private val contextGraph=context.appGraph
    private var seriesKey: String? = null
    fun bindSeries(key: String) {seriesKey=key}

    var uiState by mutableStateOf(ReaderAutomationUiState())
        private set

    private val step = Runnable { advance(System.nanoTime()) }

    fun bindReadingMode(readingMode: Int) {
        pause(ReaderAutomationPause.VIEWER_CHANGED)
        modeKey = readingMode
        val storedMode = preferences.getString("mode_$modeKey", null)
        val settings = ReaderAutomationSettings(
            mode = ReaderAutomationMode.entries.firstOrNull { it.name == storedMode }
                ?: ReaderAutomationMode.TIMED_STEP,
            speedDpPerSecond = preferences.getInt("speed_$modeKey", 60),
            intervalSeconds = preferences.getInt("interval_$modeKey", 8),
        ).normalized()
        val speed=seriesKey?.let {contextGraph.libraryToolsStore.profile(it).autoSpeed}
        uiState = ReaderAutomationUiState(
            settings = if(speed==null) settings else settings.copy(speedDpPerSecond=speed),
            continuousAvailable = isContinuousAllowed(),
        )
    }

    /** Store a choice under the reader mode that will be bound after an explicit layout switch. */
    fun prepareSettingsForReadingMode(readingMode: Int, settings: ReaderAutomationSettings) {
        val normalized = settings.normalized()
        val selected = seriesKey
        val hasSeriesSpeed = selected != null && contextGraph.libraryToolsStore.profile(selected).autoSpeed != null
        val edit = preferences.edit()
            .putString("mode_$readingMode", normalized.mode.name)
            .putInt("interval_$readingMode", normalized.intervalSeconds)
        if (!hasSeriesSpeed) edit.putInt("speed_$readingMode", normalized.speedDpPerSecond)
        edit.apply()
    }

    fun systemAllowsContinuousMotion(): Boolean =
        ValueAnimator.areAnimatorsEnabled() && !reducedMotion.get() &&
            accessibility?.isTouchExplorationEnabled != true

    fun updateSettings(settings: ReaderAutomationSettings) {
        previewSettings(settings)
        val normalized = uiState.settings
        val selected=seriesKey
        val hasSeriesSpeed=selected!=null && contextGraph.libraryToolsStore.profile(selected).autoSpeed!=null
        if(hasSeriesSpeed) contextGraph.libraryToolsStore.setProfile(selected!!,contextGraph.libraryToolsStore.profile(selected).copy(autoSpeed=normalized.speedDpPerSecond))
        val edit=preferences.edit()
            .putString("mode_$modeKey", normalized.mode.name)
            .putInt("interval_$modeKey", normalized.intervalSeconds)
        if(!hasSeriesSpeed) edit.putInt("speed_$modeKey", normalized.speedDpPerSecond)
        edit.apply()
    }

    /** Slider changes stay in memory until the gesture finishes, avoiding per-frame disk writes. */
    fun previewSettings(settings: ReaderAutomationSettings) {
        pause()
        val normalized = settings.normalized()
        uiState = uiState.copy(settings = normalized, continuousAvailable = isContinuousAllowed())
    }

    fun start() {
        // A tap that interrupted playback must never turn into a Start click on ACTION_UP.
        if (touchInterruptedPlayback || session.isRunning || !readerActive()) return
        val currentChapter = chapterId() ?: return
        val currentViewer = viewer() ?: return
        val allowed = isContinuousAllowed()
        if (!session.start(currentChapter, uiState.settings, System.nanoTime(), allowed)) {
            uiState = uiState.copy(
                continuousAvailable = allowed,
                message = "Continuous motion is unavailable in this reader or disabled by system settings. " +
                    "Choose timed steps.",
            )
            return
        }
        activeChapterId = currentChapter
        currentViewer.prepareAutoReadStart()
        uiState = uiState.copy(running = true, continuousAvailable = allowed, message = "Touch anywhere to pause.")
        schedule()
    }

    fun pause(reason: ReaderAutomationPause = ReaderAutomationPause.USER) {
        val wasRunning = session.isRunning || uiState.running
        readinessGeneration++
        readinessJob?.cancel()
        readinessJob = null
        session.pause()
        handler.removeCallbacks(step)
        choreographer.removeFrameCallback(this)
        viewer()?.cancelAutoReadStepAnimation()
        activeChapterId = null
        if (reason == ReaderAutomationPause.BACKGROUND || reason == ReaderAutomationPause.VIEWER_CHANGED) {
            touchInterruptedPlayback = false
        }
        if (wasRunning) {
            uiState = uiState.copy(running = false, message = pauseMessage(reason))
        }
    }

    fun destroy() {
        pause(ReaderAutomationPause.BACKGROUND)
        readinessScope.cancel()
    }

    fun onTouchStarted() {
        touchInterruptedPlayback = touchInterruptedPlayback || session.isRunning
        pause()
    }

    fun onTouchEnded() {
        touchInterruptedPlayback = false
    }

    /** The activity also invokes this on selections so a changed chapter pauses immediately. */
    fun onChapterSelected(selectedChapterId: Long) {
        if (session.isRunning && selectedChapterId != activeChapterId) pause(ReaderAutomationPause.CHAPTER_END)
    }

    override fun doFrame(frameTimeNanos: Long) {
        advance(frameTimeNanos)
    }

    private fun advance(nowNanos: Long) {
        if (!readerActive()) {
            pause(ReaderAutomationPause.BACKGROUND)
            return
        }
        when (val action = session.next(nowNanos, chapterId(), isContinuousAllowed(), density)) {
            is ReaderAutomationAction.Paused -> pause(action.reason)
            is ReaderAutomationAction.Scroll -> move(action.pixels)
            ReaderAutomationAction.Step -> move(null)
            ReaderAutomationAction.Idle -> Unit
        }
        if (session.isRunning && readinessJob == null) schedule()
    }

    /** Returns true when this attempt is suspended on a temporary readiness event. */
    private fun move(pixels: Int?): Boolean {
        val currentChapter = activeChapterId ?: return false
        val currentViewer = viewer() ?: run {
            pause(ReaderAutomationPause.NOT_READY)
            return false
        }
        when (currentViewer.autoAdvanceWithinChapter(currentChapter, pixels)) {
            ReaderAutoAdvanceResult.ADVANCED -> return false
            ReaderAutoAdvanceResult.CHAPTER_END -> pause(ReaderAutomationPause.CHAPTER_END)
            ReaderAutoAdvanceResult.WAITING -> waitForReadiness(
                viewer = currentViewer,
                chapterId = currentChapter,
                pendingTimedStep = pixels == null && uiState.settings.mode == ReaderAutomationMode.TIMED_STEP,
            )
            else -> pause(ReaderAutomationPause.NOT_READY)
        }
        return readinessJob != null
    }

    private fun waitForReadiness(viewer: Viewer, chapterId: Long, pendingTimedStep: Boolean) {
        if (readinessJob?.isActive == true) return
        val generation = ++readinessGeneration
        uiState = uiState.copy(message = "Waiting for the next page to finish loading.")
        val job = readinessScope.launch(start = CoroutineStart.LAZY) {
            val ready = try {
                withTimeoutOrNull(READINESS_TIMEOUT_MS) {
                    viewer.awaitAutoAdvanceReady(chapterId)
                } == true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (generation != readinessGeneration) return@launch
            readinessJob = null
            if (!session.isRunning) return@launch
            if (!readerActive() || chapterId() != chapterId || this@ReaderAutomationController.viewer() !== viewer) {
                pause(ReaderAutomationPause.BACKGROUND)
                return@launch
            }
            if (!ready) {
                pause(ReaderAutomationPause.NOT_READY)
                return@launch
            }
            session.resetAfterReadiness(System.nanoTime())
            uiState = uiState.copy(message = "Touch anywhere to pause.")
            if (pendingTimedStep) {
                if (!move(null) && session.isRunning) schedule()
            } else {
                schedule()
            }
        }
        readinessJob = job
        job.start()
    }

    private fun schedule() {
        if (uiState.settings.mode == ReaderAutomationMode.CONTINUOUS) {
            choreographer.postFrameCallback(this)
        } else {
            handler.postDelayed(step, session.nextStepDelayMillis(System.nanoTime()).coerceAtLeast(1L))
        }
    }

    private fun isContinuousAllowed(): Boolean =
        viewer()?.supportsContinuousAutoScroll == true &&
            systemAllowsContinuousMotion()

    private fun pauseMessage(reason: ReaderAutomationPause): String = when (reason) {
        ReaderAutomationPause.CHAPTER_END -> "Chapter complete. Choose the next chapter yourself."
        ReaderAutomationPause.NOT_READY ->
            "Paused while images load, layout changes, or zoom is active. Start again when ready."
        ReaderAutomationPause.REDUCED_MOTION ->
            "Paused for system animation or accessibility settings. Timed steps remain available."
        ReaderAutomationPause.BACKGROUND -> "Paused while the reader was inactive. Choose Start to resume."
        ReaderAutomationPause.USER, ReaderAutomationPause.VIEWER_CHANGED -> "Paused. Choose Start to resume."
    }

    private companion object {
        const val READINESS_TIMEOUT_MS = 20_000L
    }
}
