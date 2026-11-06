package dev.bubbleclaw

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

enum class LobsterMood { Idle, Thinking, Happy }

/**
 * Hand-drawn cartoon lobster, entirely painted with Canvas.
 * Geometry is computed once in onSizeChanged; onDraw only paints.
 */
class LobsterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var mood: LobsterMood = LobsterMood.Idle
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C73F36") }
    private val limbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#E24E43")
    }
    private val antennaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#FF8A7A")
    }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFF8F4") }
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#241A1E") }
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#7A2620")
    }
    private val cheekPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#66FF9D8C") }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFB4A8") }

    private val bodyRect = RectF()
    private val tailPath = Path()
    private val leftArm = Path()
    private val rightArm = Path()
    private val leftClaw = Path()
    private val rightClaw = Path()
    private val leftAntenna = Path()
    private val rightAntenna = Path()
    private val smilePath = Path()
    private val legPaths = ArrayList<Path>()
    private val leftEyeRect = RectF()
    private val rightEyeRect = RectF()
    private val leftHappyEye = Path()
    private val rightHappyEye = Path()
    private val leftCheekRect = RectF()
    private val rightCheekRect = RectF()

    private var cx = 0f
    private var cy = 0f
    private var scale = 0f
    private var eyeRadius = 0f
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 4200
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
        scale = min(w, h).toFloat()
        cx = w / 2f
        cy = h * 0.54f
        val s = scale

        bodyRect.set(cx - s * 0.245f, cy - s * 0.20f, cx + s * 0.245f, cy + s * 0.30f)
        eyeRadius = s * 0.105f

        bodyPaint.shader = RadialGradient(
            cx - s * 0.12f,
            cy - s * 0.12f,
            s * 0.55f,
            intArrayOf(Color.parseColor("#FF8271"), Color.parseColor("#FF5A4E"), Color.parseColor("#D8433A")),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        tailPath.reset()
        tailPath.addOval(cx - s * 0.10f, cy + s * 0.24f, cx + s * 0.10f, cy + s * 0.46f, Path.Direction.CW)
        tailPath.addOval(cx - s * 0.22f, cy + s * 0.22f, cx - s * 0.01f, cy + s * 0.40f, Path.Direction.CW)
        tailPath.addOval(cx + s * 0.01f, cy + s * 0.22f, cx + s * 0.22f, cy + s * 0.40f, Path.Direction.CW)

        leftArm.reset()
        leftArm.moveTo(cx - s * 0.20f, cy + s * 0.02f)
        leftArm.quadTo(cx - s * 0.40f, cy - s * 0.06f, cx - s * 0.44f, cy + s * 0.08f)
        rightArm.reset()
        rightArm.moveTo(cx + s * 0.20f, cy + s * 0.02f)
        rightArm.quadTo(cx + s * 0.40f, cy - s * 0.06f, cx + s * 0.44f, cy + s * 0.08f)

        leftClaw.reset()
        leftClaw.addOval(cx - s * 0.55f, cy + s * 0.01f, cx - s * 0.35f, cy + s * 0.17f, Path.Direction.CW)
        leftClaw.addOval(cx - s * 0.53f, cy + s * 0.13f, cx - s * 0.40f, cy + s * 0.24f, Path.Direction.CW)
        rightClaw.reset()
        rightClaw.addOval(cx + s * 0.35f, cy + s * 0.01f, cx + s * 0.55f, cy + s * 0.17f, Path.Direction.CW)
        rightClaw.addOval(cx + s * 0.40f, cy + s * 0.13f, cx + s * 0.53f, cy + s * 0.24f, Path.Direction.CW)

        leftAntenna.reset()
        leftAntenna.moveTo(cx - s * 0.11f, cy - s * 0.18f)
        leftAntenna.quadTo(cx - s * 0.30f, cy - s * 0.40f, cx - s * 0.24f, cy - s * 0.52f)
        rightAntenna.reset()
        rightAntenna.moveTo(cx + s * 0.11f, cy - s * 0.18f)
        rightAntenna.quadTo(cx + s * 0.30f, cy - s * 0.40f, cx + s * 0.24f, cy - s * 0.52f)

        smilePath.reset()
        smilePath.moveTo(cx - s * 0.06f, cy + s * 0.13f)
        smilePath.quadTo(cx, cy + s * 0.18f, cx + s * 0.06f, cy + s * 0.13f)

        legPaths.clear()
        for (i in 0..2) {
            val y = cy + s * (0.16f + i * 0.045f)
            val dx = s * (0.18f + i * 0.01f)
            legPaths.add(Path().apply {
                moveTo(cx - dx, y)
                quadTo(cx - dx - s * 0.07f, y + s * 0.03f, cx - dx - s * 0.10f, y + s * 0.08f)
            })
            legPaths.add(Path().apply {
                moveTo(cx + dx, y)
                quadTo(cx + dx + s * 0.07f, y + s * 0.03f, cx + dx + s * 0.10f, y + s * 0.08f)
            })
        }

        leftEyeRect.set(cx - s * 0.235f, cy - s * 0.145f, cx - s * 0.025f, cy + s * 0.065f)
        rightEyeRect.set(cx + s * 0.025f, cy - s * 0.145f, cx + s * 0.235f, cy + s * 0.065f)
        leftCheekRect.set(cx - s * 0.21f, cy + s * 0.05f, cx - s * 0.13f, cy + s * 0.10f)
        rightCheekRect.set(cx + s * 0.13f, cy + s * 0.05f, cx + s * 0.21f, cy + s * 0.10f)

        leftHappyEye.reset()
        leftHappyEye.moveTo(leftEyeRect.left + s * 0.012f, leftEyeRect.centerY() + s * 0.018f)
        leftHappyEye.quadTo(leftEyeRect.centerX(), leftEyeRect.centerY() - s * 0.040f, leftEyeRect.right - s * 0.012f, leftEyeRect.centerY() + s * 0.018f)
        rightHappyEye.reset()
        rightHappyEye.moveTo(rightEyeRect.left + s * 0.012f, rightEyeRect.centerY() + s * 0.018f)
        rightHappyEye.quadTo(rightEyeRect.centerX(), rightEyeRect.centerY() - s * 0.040f, rightEyeRect.right - s * 0.012f, rightEyeRect.centerY() + s * 0.018f)

        limbPaint.strokeWidth = s * 0.030f
        antennaPaint.strokeWidth = s * 0.018f
        mouthPaint.strokeWidth = s * 0.018f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (scale <= 0f) return
        val s = scale
        val wave = sin(phase * Math.PI * 2).toFloat()

        var bob = 0f
        when (mood) {
            LobsterMood.Idle -> bob = wave * s * 0.012f
            LobsterMood.Thinking -> bob = sin(phase * Math.PI * 4).toFloat() * s * 0.006f
            LobsterMood.Happy -> bob = -abs(sin(phase * Math.PI * 12).toFloat()) * s * 0.05f
        }

        canvas.save()
        canvas.translate(0f, bob)

        canvas.drawPath(tailPath, shellPaint)
        for (leg in legPaths) canvas.drawPath(leg, limbPaint)

        if (mood == LobsterMood.Thinking) {
            val tap = sin(phase * Math.PI * 8).toFloat() * s * 0.025f
            canvas.save()
            canvas.translate(0f, tap)
            canvas.drawPath(leftArm, limbPaint)
            canvas.drawPath(leftClaw, shellPaint)
            canvas.restore()
            canvas.save()
            canvas.translate(0f, -tap)
            canvas.drawPath(rightArm, limbPaint)
            canvas.drawPath(rightClaw, shellPaint)
            canvas.restore()
        } else {
            canvas.drawPath(leftArm, limbPaint)
            canvas.drawPath(rightArm, limbPaint)
            canvas.drawPath(leftClaw, shellPaint)
            canvas.drawPath(rightClaw, shellPaint)
        }

        canvas.drawOval(bodyRect, bodyPaint)

        canvas.save()
        canvas.rotate(wave * 3.5f, cx - s * 0.11f, cy - s * 0.18f)
        canvas.drawPath(leftAntenna, antennaPaint)
        canvas.restore()
        canvas.save()
        canvas.rotate(-wave * 3.5f, cx + s * 0.11f, cy - s * 0.18f)
        canvas.drawPath(rightAntenna, antennaPaint)
        canvas.restore()

        drawEyes(canvas, s)

        canvas.drawOval(leftCheekRect, cheekPaint)
        canvas.drawOval(rightCheekRect, cheekPaint)

        if (mood == LobsterMood.Thinking) {
            canvas.drawPath(smilePath, mouthPaint)
            val baseY = cy + s * 0.31f
            for (i in 0..2) {
                val local = sin((phase * 3 + i * 0.22f) * Math.PI * 2).toFloat()
                dotPaint.alpha = (110 + 120 * local).toInt().coerceIn(30, 255)
                canvas.drawCircle(cx + (i - 1) * s * 0.055f, baseY, s * 0.018f, dotPaint)
            }
        } else {
            canvas.drawPath(smilePath, mouthPaint)
        }

        canvas.restore()
    }

    private fun drawEyes(canvas: Canvas, s: Float) {
        val blinkWindow = phase > 0.965f
        val idleBlink = mood == LobsterMood.Idle && blinkWindow
        val closed = idleBlink || mood == LobsterMood.Thinking && phase > 0.90f

        if (mood == LobsterMood.Happy) {
            drawHappyEye(canvas, leftHappyEye, s)
            drawHappyEye(canvas, rightHappyEye, s)
            return
        }

        if (closed) {
            val y = leftEyeRect.centerY()
            mouthPaint.alpha = 255
            canvas.drawLine(leftEyeRect.left + s * 0.012f, y, leftEyeRect.right - s * 0.012f, y, mouthPaint)
            canvas.drawLine(rightEyeRect.left + s * 0.012f, y, rightEyeRect.right - s * 0.012f, y, mouthPaint)
            return
        }

        val squish = if (mood == LobsterMood.Thinking) 0.80f else 1f
        drawEye(canvas, leftEyeRect, s, squish)
        drawEye(canvas, rightEyeRect, s, squish)
    }

    private fun drawEye(canvas: Canvas, rect: RectF, s: Float, squish: Float) {
        canvas.save()
        canvas.scale(1f, squish, rect.centerX(), rect.centerY())
        canvas.drawOval(rect, eyePaint)
        val pupilR = s * 0.048f
        val px = rect.centerX() + s * 0.012f
        val py = rect.centerY() + s * 0.010f
        canvas.drawCircle(px, py, pupilR, pupilPaint)
        canvas.drawCircle(px - pupilR * 0.35f, py - pupilR * 0.40f, pupilR * 0.36f, glintPaint)
        canvas.drawCircle(px + pupilR * 0.30f, py + pupilR * 0.35f, pupilR * 0.18f, glintPaint)
        canvas.restore()
    }

    private fun drawHappyEye(canvas: Canvas, path: Path, s: Float) {
        antennaPaint.strokeWidth = s * 0.026f
        antennaPaint.color = Color.parseColor("#4A1814")
        canvas.drawPath(path, antennaPaint)
        antennaPaint.color = Color.parseColor("#FF8A7A")
        antennaPaint.strokeWidth = s * 0.018f
    }
}
