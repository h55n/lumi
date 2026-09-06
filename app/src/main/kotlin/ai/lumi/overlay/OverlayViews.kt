package ai.lumi.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import ai.lumi.R

// ── BubbleView ─────────────────────────────────────────────────────────────────

class BubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class State { IDLE, LISTENING, LOADING, GUIDING, ERROR }

    companion object {
        private const val SAFFRON = 0xFFF5A100.toInt()
        private const val GREEN   = 0xFF22C55E.toInt()
        private const val INK     = 0xFF1A1A2E.toInt()
        private const val ERROR   = 0xFFDC2626.toInt()
    }

    private var currentState = State.IDLE
    private var pulseRadius = 0f
    private var pulseAlpha = 0
    private var pulseAnimator: ValueAnimator? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        setShadowLayer(4f * resources.displayMetrics.density, 0f, 2f * resources.displayMetrics.density, 0x401A1A2E)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 24f * resources.displayMetrics.density
    }

    fun setState(state: State) {
        currentState = state
        pulseAnimator?.cancel()
        when (state) {
            State.IDLE -> {
                fillPaint.color = SAFFRON
                stopPulse()
            }
            State.LISTENING -> {
                fillPaint.color = GREEN
                startPulse(GREEN, 1400)
            }
            State.LOADING -> {
                fillPaint.color = SAFFRON
                startPulse(SAFFRON, 1400)
            }
            State.GUIDING -> {
                fillPaint.color = SAFFRON
                startPulse(SAFFRON, 2000)
            }
            State.ERROR -> {
                fillPaint.color = ERROR
                stopPulse()
            }
        }
        invalidate()
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        contentDescription = "Lumi assistant"
    }

    private fun startPulse(color: Int, intervalMs: Long) {
        pulsePaint.color = color
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = intervalMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener {
                val f = it.animatedFraction
                pulseRadius = width * 0.5f * (1f + f * 0.6f)
                pulseAlpha = (120 * (1f - f)).toInt()
                pulsePaint.alpha = pulseAlpha
                invalidate()
            }
            start()
        }
    }

    private fun stopPulse() { pulseAnimator?.cancel(); pulseRadius = 0f; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy) - 2f
        if (pulseRadius > 0) canvas.drawCircle(cx, cy, pulseRadius, pulsePaint)
        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r - ringPaint.strokeWidth, ringPaint)
        // "✦" star icon in centre
        canvas.drawText("✦", cx, cy + iconPaint.textSize / 3f, iconPaint)
    }
}

// ── GuidanceBubbleView ─────────────────────────────────────────────────────────

class GuidanceBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val textView: TextView

    init {
        val density = resources.displayMetrics.density
        val padH = (18 * density).toInt()
        val padV = (10 * density).toInt()
        val rad = 22 * density

        elevation = 6 * density
        setPadding(padH, padV, padH, padV)

        // Glassmorphism-style background (Dark Ink with 90% opacity + soft silver stroke)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xE61A1A2E.toInt()) // 90% opacity
            setStroke((1f * density).toInt(), 0x55F5A100)
            cornerRadius = rad
        }

        textView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            maxLines = 2
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setLineSpacing(3 * density, 1.05f)
        }
        addView(textView)
    }

    fun setInstruction(text: String) {
        textView.text = text
    }
}

// ── VoiceIndicatorView ─────────────────────────────────────────────────────────

class VoiceIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val barCount = 5
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5A100.toInt()
        style = Paint.Style.FILL
    }
    private val barHeights = FloatArray(barCount) { 0.3f }
    private var animators = emptyList<ValueAnimator>()

    fun startAnimating() {
        stopAnimating()
        animators = (0 until barCount).map { i ->
            ValueAnimator.ofFloat(0.2f, 1f).apply {
                duration = 400 + i * 80L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                startDelay = i * 60L
                addUpdateListener {
                    barHeights[i] = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }

    fun stopAnimating() {
        animators.forEach { it.cancel() }
        barHeights.fill(0.3f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val barW = width / (barCount * 2f)
        val spacing = barW
        for (i in 0 until barCount) {
            val barH = height * barHeights[i]
            val left = i * (barW + spacing) + spacing / 2f
            val top = (height - barH) / 2f
            canvas.drawRoundRect(left, top, left + barW, top + barH, barW / 2f, barW / 2f, barPaint)
        }
    }
}
