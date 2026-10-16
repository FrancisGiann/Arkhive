// Modified for the private ReadingApp prototype; see READINGAPP-PROTOTYPE.md.
package eu.kanade.tachiyomi.ui.reader.viewer

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutoAdvanceResult
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters

/**
 * Interface for implementing a viewer.
 */
interface Viewer {

    /**
     * Returns the view this viewer uses.
     */
    fun getView(): View

    /**
     * Destroys this viewer. Called when leaving the reader or swapping viewers.
     */
    fun destroy() {}

    /**
     * Tells this viewer to set the given [chapters] as active.
     */
    fun setChapters(chapters: ViewerChapters)

    /**
     * Tells this viewer to move to the given [page].
     */
    fun moveToPage(page: ReaderPage)

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    fun handleGenericMotionEvent(event: MotionEvent): Boolean

    val supportsContinuousAutoScroll: Boolean
        get() = false

    /**
     * Advance only inside [chapterId]. Null [pixels] requests one page/screen step;
     * a positive value requests a continuous-scroll increment in an eligible strip viewer.
     * Automated navigation must never enter transitions or preloaded adjacent chapters.
     */
    fun autoAdvanceWithinChapter(chapterId: Long, pixels: Int?): ReaderAutoAdvanceResult =
        ReaderAutoAdvanceResult.UNSUPPORTED

    /** Suspend until a temporary decode/layout blocker clears, or return false on a hard error. */
    suspend fun awaitAutoAdvanceReady(chapterId: Long): Boolean = false

    /** Stop a timed auto-read transition when playback is interrupted. */
    fun cancelAutoReadStepAnimation() = Unit

    /** Stop user motion only when the user explicitly starts auto-read. */
    fun prepareAutoReadStart() = Unit
}
