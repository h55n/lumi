package ai.lumi.cursor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.PathParser

/**
 * Custom view for the Lumi pointing cursor — sleek aerodynamic arrow pointer.
 *
 * Design matching user reference:
 *  - Vertical left edge with rounded corners
 *  - Slanted top edge sloping down-right
 *  - Rounded right pointing tip (hotspot)
 *  - Concave bottom notch curving back to bottom-left corner
 *  - Fill: #26282B (dark charcoal)
 *  - Stroke: 3.5 dp #E0E3E8 (light silver/metallic)
 *  - Drop shadow: 6 dp blur, soft opacity
 *  - Ripple: silver ring at the right pointing tip
 */
class CursorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val FILL_COLOR = 0xFFF5A100.toInt()
        const val STROKE_COLOR = 0xFF1A1A2E.toInt()
        const val STROKE_WIDTH_DP = 3f
        const val SHADOW_RADIUS_DP = 8f
        const val SHADOW_COLOR = 0x591A1A2E             // 35% Lumi Ink
        const val RIPPLE_COLOR = 0x88E0E3E8.toInt()     // Silver ripple
    }

    private val density = resources.displayMetrics.density
    private val strokeWidth = STROKE_WIDTH_DP * density
    private val shadowRadius = SHADOW_RADIUS_DP * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FILL_COLOR
        style = Paint.Style.FILL
        setShadowLayer(shadowRadius, 2f * density, 3f * density, SHADOW_COLOR)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = STROKE_COLOR
        style = Paint.Style.STROKE
        strokeWidth = this@CursorView.strokeWidth
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = RIPPLE_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }

    private val basePointerPath: Path = Path().apply {
        // Viewport 56x56
        // Top-left start
        moveTo(14f, 16f)
        // Straight vertical left edge
        lineTo(14f, 42f)
        // Rounded bottom-left corner
        cubicTo(14f, 46.5f, 17f, 49f, 21f, 47.5f)
        // Concave bottom edge sweeping inward and upward towards the right tip
        cubicTo(28f, 44f, 36f, 38f, 44f, 33f)
        // Rounded right tip (pointing hotspot)
        cubicTo(47.5f, 31f, 48.5f, 29f, 48f, 27f)
        cubicTo(47.5f, 25f, 45f, 23.5f, 42f, 21.5f)
        // Slanted top edge sloping up-left
        lineTo(21f, 11.5f)
        // Rounded top-left corner
        cubicTo(17f, 9.5f, 14f, 12f, 14f, 16f)
        close()
    }

    private val scaledPointerPath = Path()

    private var rippleRadius = 0f
    var rippleAlpha = 0

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val matrix = Matrix()
        // Path viewport is 56x56. Scale into view with padding for shadow & stroke
        val pad = shadowRadius + strokeWidth
        val availW = w - pad * 2
        val availH = h - pad * 2
        val scale = minOf(availW / 56f, availH / 56f)
        matrix.setScale(scale, scale)
        matrix.postTranslate(pad, pad)

        scaledPointerPath.reset()
        basePointerPath.transform(matrix, scaledPointerPath)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Ripple ring at pointing tip (hotspot: x ~ 85.7%, y ~ 48.2% matching drawn tip at 48,27 in 56x56 viewport)
        if (rippleRadius > 0f && rippleAlpha > 0) {
            ripplePaint.alpha = rippleAlpha
            val tipX = width * 0.857f
            val tipY = height * 0.482f
            canvas.drawCircle(tipX, tipY, rippleRadius, ripplePaint)
        }

        // Solid charcoal fill with drop shadow
        canvas.drawPath(scaledPointerPath, fillPaint)
        // Outer metallic silver border
        canvas.drawPath(scaledPointerPath, strokePaint)
    }

    fun setRipple(radius: Float, alpha: Int) {
        rippleRadius = radius
        rippleAlpha = alpha
        invalidate()
    }
}

