package mihon.readingapp.books

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
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutoAdvanceResult
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationAction
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationMode
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationPause
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationSession
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationSettings
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationUiState

/** Reuses the tested clock; schedules display callbacks only while continuous motion is running. */
class BookAutoController(
    context: Context,
    private val boundary: () -> Long?,
    private val active: () -> Boolean,
    private val supportsContinuous: () -> Boolean,
    private val move: (Long, Int?) -> ReaderAutoAdvanceResult,
    private val boundaryMessage: () -> String = { "Chapter complete. Choose the next chapter yourself." },
    private val cancelStepAnimation: () -> Unit = {},
) : Choreographer.FrameCallback {
    private val reducedMotion = context.appGraph.uiPreferences.arkhiveReducedMotion
    private val prefs = context.getSharedPreferences("arkhive_book_automation", Context.MODE_PRIVATE)
    private val accessibility = context.getSystemService<AccessibilityManager>()
    private val density = context.resources.displayMetrics.density
    private val session = ReaderAutomationSession()
    private val handler = Handler(Looper.getMainLooper())
    private val frames = Choreographer.getInstance()
    private var key = ""
    private var capturedBoundary: Long? = null
    private var interruptedTouch = false
    private var seriesKey: String? = null
    var state by mutableStateOf(ReaderAutomationUiState())
        private set
    private val step = Runnable { advance(System.nanoTime()) }
    fun bind(mode: String) {
        pause()
        key = mode
        val settings = ReaderAutomationSettings(
            mode = ReaderAutomationMode.entries.firstOrNull { it.name == prefs.getString("${key}_mode", "TIMED_STEP") }
                ?: ReaderAutomationMode.TIMED_STEP,
            speedDpPerSecond = prefs.getInt("${key}_speed", 60), intervalSeconds = prefs.getInt("${key}_interval", 8),
        ).normalized()
        val speed=seriesKey?.let {contextGraph.libraryToolsStore.profile(it).autoSpeed}
        state = ReaderAutomationUiState(if(speed==null) settings else settings.copy(speedDpPerSecond=speed), false, allowed())
    }
    private val contextGraph=context.appGraph
    fun bindSeries(key:String) {seriesKey=key;if(this.key.isNotEmpty()) bind(this.key)}
    fun prepareSettingsForMode(mode: String, settings: ReaderAutomationSettings) {
        val normalized = settings.normalized()
        val selected = seriesKey
        val hasSeriesSpeed = selected != null && contextGraph.libraryToolsStore.profile(selected).autoSpeed != null
        val edit = prefs.edit()
            .putString("${mode}_mode", normalized.mode.name)
            .putInt("${mode}_interval", normalized.intervalSeconds)
        if (!hasSeriesSpeed) edit.putInt("${mode}_speed", normalized.speedDpPerSecond)
        edit.apply()
    }
    fun systemAllowsContinuousMotion() =
        ValueAnimator.areAnimatorsEnabled() && !reducedMotion.get() && accessibility?.isTouchExplorationEnabled != true
    fun update(settings: ReaderAutomationSettings, persist: Boolean = true) {
        pause()
        state = state.copy(settings = settings.normalized(), continuousAvailable = allowed())
        val selected=seriesKey
        val hasSeriesSpeed=persist && selected?.let {contextGraph.libraryToolsStore.profile(it).autoSpeed} != null
        if (hasSeriesSpeed) {
            val profileKey=requireNotNull(selected)
            contextGraph.libraryToolsStore.setProfile(profileKey,contextGraph.libraryToolsStore.profile(profileKey).copy(autoSpeed=state.settings.speedDpPerSecond))
        }
        if(persist) {
            val edit=prefs.edit().putString("${key}_mode", state.settings.mode.name)
                .putInt("${key}_interval", state.settings.intervalSeconds)
            if(!hasSeriesSpeed) edit.putInt("${key}_speed",state.settings.speedDpPerSecond)
            edit.apply()
        }
    }
    fun start() {
        if (interruptedTouch || !active() || state.running) return
        val scope = boundary() ?: return
        if (!session.start(scope, state.settings, System.nanoTime(), allowed())) {
            state = state.copy(message = "Continuous motion needs scrolling text and enabled system motion. Choose timed steps.")
            return
        }
        capturedBoundary = scope
        state = state.copy(running = true, continuousAvailable = allowed(), message = "Touch anywhere to pause.")
        schedule()
    }
    fun touchDown() { interruptedTouch = interruptedTouch || state.running; pause() }
    fun touchUp() { interruptedTouch = false }
    fun pause(reason: ReaderAutomationPause = ReaderAutomationPause.USER) {
        val wasRunning = state.running
        session.pause(); handler.removeCallbacks(step); frames.removeFrameCallback(this); capturedBoundary = null
        cancelStepAnimation()
        if (reason == ReaderAutomationPause.BACKGROUND) interruptedTouch = false
        if (wasRunning) state = state.copy(running = false, message = when(reason) {
            ReaderAutomationPause.CHAPTER_END -> boundaryMessage()
            ReaderAutomationPause.BACKGROUND -> "Paused while the reader was inactive. Choose Start to resume."
            ReaderAutomationPause.REDUCED_MOTION -> "Paused for system motion or accessibility settings."
            ReaderAutomationPause.NOT_READY -> "Paused while content loads. Choose Start when ready."
            else -> "Paused. Choose Start to resume."
        })
    }
    override fun doFrame(frameTimeNanos: Long) { advance(frameTimeNanos) }
    private fun advance(now: Long) {
        if (!active()) { pause(ReaderAutomationPause.BACKGROUND); return }
        val action = session.next(now, boundary(), allowed(), density)
        when (action) {
            is ReaderAutomationAction.Paused -> pause(action.reason)
            is ReaderAutomationAction.Scroll -> perform(action.pixels)
            ReaderAutomationAction.Step -> perform(null)
            ReaderAutomationAction.Idle -> Unit
        }
        if (session.isRunning) schedule()
    }
    private fun perform(pixels: Int?) {
        val scope = capturedBoundary ?: return
        when (move(scope, pixels)) {
            ReaderAutoAdvanceResult.ADVANCED -> Unit
            ReaderAutoAdvanceResult.CHAPTER_END -> pause(ReaderAutomationPause.CHAPTER_END)
            else -> pause(ReaderAutomationPause.NOT_READY)
        }
    }
    private fun allowed() = supportsContinuous() && systemAllowsContinuousMotion()
    private fun schedule() {
        if (state.settings.mode == ReaderAutomationMode.CONTINUOUS) frames.postFrameCallback(this)
        else handler.postDelayed(step, session.nextStepDelayMillis(System.nanoTime()).coerceAtLeast(1))
    }
}
