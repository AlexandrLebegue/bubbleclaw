package dev.bubbleclaw

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.sin

enum class BubbleState { Idle, Thinking, AnswerReady }

/**
 * Circular coral badge containing the BubbleClaw lobster head.
 * All geometry is precomputed; onDraw only paints.
 */
class LobsterBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var state: BubbleState = BubbleState.Idle
        set(value) {
            if (field == value) return
            field = value
            updateAnimation()
        }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF8A7A") }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFF8F4") }
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#241A1E") }
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#FF7E6E")
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.parseColor("#66FF5A4E")
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#69D98B") }
    private val badgeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.WHITE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFB4A8") }

    private val smilePath = Path()
    private val antennaLeft = Path()
    private val antennaRight = Path()
    private val clawLeft = Path()
    private val clawRight = Path()

    private var cx = 0f
    private var cy = 0f
    private var bodyRadius = 0f
    private var eyeRadius = 0f
    private var phase = 0f

    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1500
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        bodyRadius = min(w, h) * 0.345f
        eyeRadius = bodyRadius * 0.29f

        bodyPaint.shader = RadialGradient(
            cx - bodyRadius * 0.3f,
            cy - bodyRadius * 0.35f,
            bodyRadius * 1.45f,
            intArrayOf(Color.parseColor("#FF7A69"), Color.parseColor("#FF5A4E"), Color.parseColor("#D8433A")),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        smilePath.reset()
        smilePath.moveTo(cx - bodyRadius * 0.24f, cy + bodyRadius * 0.34f)
        smilePath.quadTo(cx, cy + bodyRadius * 0.52f, cx + bodyRadius * 0.24f, cy + bodyRadius * 0.34f)

        antennaLeft.reset()
        antennaLeft.moveTo(cx - bodyRadius * 0.34f, cy - bodyRadius * 0.66f)
        antennaLeft.quadTo(cx - bodyRadius * 1.0f, cy - bodyRadius * 1.15f, cx - bodyRadius * 0.72f, cy - bodyRadius * 1.5f)

        antennaRight.reset()
        antennaRight.moveTo(cx + bodyRadius * 0.34f, cy - bodyRadius * 0.66f)
        antennaRight.quadTo(cx + bodyRadius * 1.0f, cy - bodyRadius * 1.15f, cx + bodyRadius * 0.72f, cy - bodyRadius * 1.5f)

        clawLeft.reset()
        clawLeft.addCircle(cx - bodyRadius * 1.02f, cy + bodyRadius * 0.48f, bodyRadius * 0.24f, Path.Direction.CW)
        clawLeft.addCircle(cx - bodyRadius * 0.82f, cy + bodyRadius * 0.22f, bodyRadius * 0.18f, Path.Direction.CW)

        clawRight.reset()
        clawRight.addCircle(cx + bodyRadius * 1.02f, cy + bodyRadius * 0.48f, bodyRadius * 0.24f, Path.Direction.CW)
        clawRight.addCircle(cx + bodyRadius * 0.82f, cy + bodyRadius * 0.22f, bodyRadius * 0.18f, Path.Direction.CW)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        pulseAnimator.cancel()
        super.onDetachedFromWindow()
    }

    private fun updateAnimation() {
        if (state == BubbleState.Thinking) {
            if (!pulseAnimator.isStarted) pulseAnimator.start()
        } else {
            pulseAnimator.cancel()
            phase = 0f
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bodyRadius <= 0f) return

        if (state == BubbleState.Thinking) {
            val haloRadius = bodyRadius * (1.12f + 0.10f * sin(phase * Math.PI * 2).toFloat())
            haloPaint.alpha = (110 + 80 * sin(phase * Math.PI * 2).toFloat()).toInt().coerceIn(40, 190)
            canvas.drawCircle(cx, cy, haloRadius, haloPaint)
        }

        canvas.drawPath(clawLeft, highlightPaint)
        canvas.drawPath(clawRight, highlightPaint)

        canvas.drawPath(antennaLeft, linePaint)
        canvas.drawPath(antennaRight, linePaint)

        canvas.drawCircle(cx, cy, bodyRadius, bodyPaint)
        canvas.drawCircle(cx, cy, bodyRadius, highlightEdgePaint())

        val eyeY = cy - bodyRadius * 0.13f
        val eyeOffset = bodyRadius * 0.36f
        val leftX = cx - eyeOffset
        val rightX = cx + eyeOffset
        canvas.drawCircle(leftX, eyeY, eyeRadius, eyePaint)
        canvas.drawCircle(rightX, eyeY, eyeRadius, eyePaint)

        val pupilOffset = eyeRadius * 0.22f
        val pupilY = eyeY + eyeRadius * 0.10f
        val pupilR = eyeRadius * 0.52f
        canvas.drawCircle(leftX + pupilOffset, pupilY, pupilR, pupilPaint)
        canvas.drawCircle(rightX + pupilOffset, pupilY, pupilR, pupilPaint)

        val glintR = eyeRadius * 0.18f
        canvas.drawCircle(leftX + pupilOffset - pupilR * 0.35f, pupilY - pupilR * 0.4f, glintR, glintPaint)
        canvas.drawCircle(rightX + pupilOffset - pupilR * 0.35f, pupilY - pupilR * 0.4f, glintR, glintPaint)

        canvas.drawPath(smilePath, linePaint)

        if (state == BubbleState.Thinking) {
            val baseY = cy + bodyRadius * 0.92f
            for (i in 0..2) {
                val wave = sin((phase + i * 0.18f) * Math.PI * 2).toFloat()
                dotPaint.alpha = (110 + 120 * wave).toInt().coerceIn(30, 255)
                canvas.drawCircle(cx + (i - 1) * dp(6f), baseY, dp(2.2f), dotPaint)
            }
        }

        if (state == BubbleState.AnswerReady) {
            val badgeX = width - dp(11f)
            val badgeY = dp(11f)
            canvas.drawCircle(badgeX, badgeY, dp(7f), badgePaint)
            canvas.drawCircle(badgeX, badgeY, dp(7f), badgeRingPaint)
        }
    }

    private fun highlightEdgePaint(): Paint {
        return edgePaint
    }

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = Color.parseColor("#55FFE3DA")
    }
}
