package dev.bubbleclaw

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator

/** Lightweight moving sheen used while the screen capture is being prepared. */
class ShimmerDrawable : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        val width = bounds.width().toFloat()
        val band = width * 0.45f
        val start = bounds.left + (width + band) * progress - band
        paint.shader = LinearGradient(
            start,
            0f,
            start + band,
            bounds.height().toFloat(),
            intArrayOf(Color.TRANSPARENT, Color.argb(46, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        invalidateSelf()
    }

    fun start() {
        if (!animator.isStarted) animator.start()
    }

    fun stop() {
        animator.cancel()
    }
}
