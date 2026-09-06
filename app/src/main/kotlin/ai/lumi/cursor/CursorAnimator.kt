package ai.lumi.cursor

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.core.animation.doOnEnd
import timber.log.Timber

/**
 * Animates [CursorView] to a target position via cubic Bezier.
 *
 * PRD §14 animation spec:
 *  - Travel: Bezier ease-in-out, 400 ms, FastOutSlowInInterpolator
 *  - Arrival pulse: scale 1.0→1.5→1.0, 300 ms — immediate attention signal
 *  - Repeat pulse: every 2000 ms, loops until tap detected
 *  - Tap hint: ripple animation beneath cursor
 */
class CursorAnimator(
    private val cursorView: CursorView,
    private val onUpdatePosition: (x: Float, y: Float) -> Unit = { _, _ -> }
) {

    private val handler = Handler(Looper.getMainLooper())
    private var travelAnimator: ValueAnimator? = null
    private var repeatPulseRunnable: Runnable? = null
    private var currentX = 0f
    private var currentY = 0f

    companion object {
        private const val TRAVEL_DURATION_MS = 400L
        private const val ARRIVAL_PULSE_DURATION_MS = 300L
        private const val REPEAT_PULSE_INTERVAL_MS = 2000L
        private const val RIPPLE_MAX_RADIUS = 80f
        private const val RIPPLE_MAX_ALPHA = 80
    }

    /**
     * Animate cursor from its current position to [targetX], [targetY].
     * Calls [onComplete] after arrival pulse finishes.
     */
    fun animateTo(targetX: Float, targetY: Float, onComplete: () -> Unit = {}) {
        stopAll()

        val startX = currentX
        val startY = currentY

        // Calculate arc control points for natural arc motion
        val controlX = (startX + targetX) / 2f + (targetY - startY) * 0.3f
        val controlY = (startY + targetY) / 2f - (targetX - startX) * 0.3f

        travelAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TRAVEL_DURATION_MS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedFraction
                val x = cubicBezier(startX, controlX, targetX, t)
                val y = cubicBezier(startY, controlY, targetY, t)
                val hotspotX = cursorView.width * 0.82f
                val hotspotY = cursorView.height * 0.52f
                cursorView.pivotX = hotspotX
                cursorView.pivotY = hotspotY
                onUpdatePosition(x - hotspotX, y - hotspotY)
                currentX = x
                currentY = y
            }
            doOnEnd {
                startArrivalPulse {
                    startRepeatPulse()
                    onComplete()
                }
            }
            start()
        }
        Timber.d("CursorAnimator: moving to ($targetX, $targetY)")
    }

    private fun startArrivalPulse(onDone: () -> Unit) {
        cursorView.animate()
            .scaleX(1.5f).scaleY(1.5f)
            .setDuration(ARRIVAL_PULSE_DURATION_MS / 2)
            .withEndAction {
                cursorView.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(ARRIVAL_PULSE_DURATION_MS / 2)
                    .withEndAction { onDone() }
                    .start()
            }.start()

        // Ripple
        val rippleAnim = ValueAnimator.ofFloat(0f, RIPPLE_MAX_RADIUS).apply {
            duration = ARRIVAL_PULSE_DURATION_MS
            addUpdateListener {
                val frac = it.animatedFraction
                cursorView.setRipple(it.animatedValue as Float, (RIPPLE_MAX_ALPHA * (1f - frac)).toInt())
            }
            doOnEnd { cursorView.setRipple(0f, 0) }
            start()
        }
    }

    private fun startRepeatPulse() {
        val runnable = object : Runnable {
            override fun run() {
                if (!cursorView.isAttachedToWindow) return
                cursorView.animate()
                    .scaleX(1.4f).scaleY(1.4f)
                    .setDuration(150)
                    .withEndAction {
                        cursorView.animate()
                            .scaleX(1f).scaleY(1f)
                            .setDuration(150)
                            .start()
                    }.start()
                handler.postDelayed(this, REPEAT_PULSE_INTERVAL_MS)
            }
        }
        repeatPulseRunnable = runnable
        handler.postDelayed(runnable, REPEAT_PULSE_INTERVAL_MS)
    }

    fun stopAll() {
        travelAnimator?.cancel()
        travelAnimator = null
        repeatPulseRunnable?.let { handler.removeCallbacks(it) }
        repeatPulseRunnable = null
        cursorView.animate().cancel()
        cursorView.scaleX = 1f
        cursorView.scaleY = 1f
        cursorView.setRipple(0f, 0)
    }

    /** Cubic Bezier interpolation (quadratic with midpoint control). */
    private fun cubicBezier(start: Float, control: Float, end: Float, t: Float): Float {
        val mt = 1f - t
        return mt * mt * start + 2f * mt * t * control + t * t * end
    }
}
