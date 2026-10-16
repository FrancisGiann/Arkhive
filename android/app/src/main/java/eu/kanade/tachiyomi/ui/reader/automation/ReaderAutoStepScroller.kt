package eu.kanade.tachiyomi.ui.reader.automation

import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator
import androidx.core.content.getSystemService
import mihon.app.di.appGraph
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import kotlin.math.roundToInt

/** A single cancellable timed-step animation. Continuous auto-read keeps its frame-clock path. */
class ReaderAutoStepScroller(private val context: Context) {
    private var animation: ValueAnimator? = null

    fun scrollBy(view: View, dx: Int, dy: Int) {
        cancel()
        if (dx == 0 && dy == 0) return
        if (!animationsAllowed(context)) {
            view.scrollBy(dx, dy)
            return
        }

        var appliedX = 0
        var appliedY = 0
        val durationScale = context.animatorDurationScale
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (STEP_DURATION_MS * durationScale).toLong().coerceIn(80L, 600L)
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { value ->
                val fraction = value.animatedValue as Float
                val targetX = (dx * fraction).roundToInt()
                val targetY = (dy * fraction).roundToInt()
                view.scrollBy(targetX - appliedX, targetY - appliedY)
                appliedX = targetX
                appliedY = targetY
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (this@ReaderAutoStepScroller.animation === animation) {
                        this@ReaderAutoStepScroller.animation = null
                    }
                }
            })
        }
        animation = animator
        animator.start()
    }

    fun cancel() {
        animation?.cancel()
        animation = null
    }

    companion object {
        private const val STEP_DURATION_MS = 260L

        fun animationsAllowed(context: Context): Boolean =
            context.animatorDurationScale > 0f && ValueAnimator.areAnimatorsEnabled() &&
                !context.appGraph.uiPreferences.arkhiveReducedMotion.get() &&
                context.getSystemService<AccessibilityManager>()?.isTouchExplorationEnabled != true
    }
}
