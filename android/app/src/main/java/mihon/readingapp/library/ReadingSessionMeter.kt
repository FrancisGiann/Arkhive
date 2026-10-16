package mihon.readingapp.library

import android.os.SystemClock

/** Monotonic foreground intervals. Completions are deduplicated; nothing is written during frames. */
class ReadingSessionMeter(
    private val store: LibraryToolsStore,
    private val eligible: () -> Boolean,
    private val key: () -> String?,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) {
    private var started: Long? = null
    private var series: String? = null
    fun resume() {
        if (started != null || !store.state.value.statsEnabled || !eligible()) return
        series = key() ?: return
        started = elapsed()
    }
    fun pause() {
        val start = started ?: return
        val selected = series
        started = null; series = null
        if (selected != null && eligible()) store.recordReading(selected, (elapsed() - start).coerceAtLeast(0))
    }
    fun complete(chapter: String) {
        val selected = key() ?: return
        if(eligible() && store.state.value.statsEnabled && chapter !in store.state.value.stats[selected]?.completedChapters.orEmpty()) {
            store.recordReading(selected, 0, chapter)
        }
    }
}
