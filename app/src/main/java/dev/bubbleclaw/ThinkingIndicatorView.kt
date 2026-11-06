package dev.bubbleclaw

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import kotlin.math.min
import kotlin.math.sin

/**
 * Three dots bouncing with a 150 ms stagger over a 900 ms loop.
 * Geometry is computed on size changes; onDraw only paints.
 */
class BouncingDotsView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dotCount = 3
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB4A8")
    }
    private val centers = FloatArray(3)
    private var radius = 0f
    private var centerY = 0f
    private var amplitude = 0f
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
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
        radius = min(w.toFloat() / (dotCount * 3.2f), h * 0.16f)
        val gap = radius * 3.0f
        val total = gap * (dotCount - 1)
        val start = w / 2f - total / 2f
        for (i in 0 until dotCount) centers[i] = start + i * gap
        centerY = h / 2f
        amplitude = h * 0.22f
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
        if (radius <= 0f) return
        for (i in 0 until dotCount) {
            val staggered = phase - i * (150f / 900f)
            val wave = sin(staggered * Math.PI * 2).toFloat()
            val y = centerY - wave * amplitude
            paint.alpha = (140 + 110 * ((wave + 1f) / 2f)).toInt().coerceIn(80, 255)
            canvas.drawCircle(centers[i], y, radius, paint)
        }
    }
}

/** Assistant thinking bubble: small thinking lobster plus the bouncing dots. */
class ThinkingIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val dots = BouncingDotsView(context)

    private val pulse = ValueAnimator.ofFloat(1f, 1.04f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val value = it.animatedValue as Float
            scaleX = value
            scaleY = value
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(14), dp(8))
        setBackgroundResource(R.drawable.bg_bubble_assistant)
        pivotX = 0f
        pivotY = 0f
        val lobster = LobsterView(context).apply { mood = LobsterMood.Thinking }
        addView(
            lobster,
            LayoutParams(dp(30), dp(30))
        )
        addView(
            dots,
            LayoutParams(dp(46), dp(22)).apply { marginStart = dp(8) }
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pivotX = width / 2f
        pivotY = height / 2f
        if (!pulse.isStarted) pulse.start()
    }

    override fun onDetachedFromWindow() {
        pulse.cancel()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
