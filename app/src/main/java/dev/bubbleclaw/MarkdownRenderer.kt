package dev.bubbleclaw

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import androidx.core.content.ContextCompat

/** Turns the parser output into Android spans without pulling in a markdown library. */
object MarkdownRenderer {

    private const val CODE_BACKGROUND = 0xFF241C20.toInt()

    fun render(context: Context, markdown: String): CharSequence {
        val density = context.resources.displayMetrics.density
        val accent = ContextCompat.getColor(context, R.color.coral_light)
        val builder = SpannableStringBuilder()
        val blocks = MarkdownParser.parse(markdown)

        blocks.forEachIndexed { blockIndex, block ->
            if (blockIndex > 0) builder.append('\n')
            when (block) {
                is MdBlock.Paragraph -> appendInline(builder, block.content, accent, density)
                is MdBlock.Heading -> {
                    val start = builder.length
                    appendInline(builder, block.content, accent, density)
                    val end = builder.length
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val scale = if (block.level <= 1) 1.35f else if (block.level == 2) 1.18f else 1.08f
                    builder.setSpan(RelativeSizeSpan(scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is MdBlock.CodeBlock -> appendCodeBlock(builder, block.code, density)
                is MdBlock.BulletList -> {
                    block.items.forEach { item ->
                        val start = builder.length
                        builder.append("• ")
                        appendInline(builder, item, accent, density)
                        val end = builder.length
                        builder.setSpan(
                            LeadingMarginSpan.Standard(0, (density * 18).toInt()),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        builder.append('\n')
                    }
                    if (builder.isNotEmpty() && builder[builder.length - 1] == '\n') {
                        builder.delete(builder.length - 1, builder.length)
                    }
                }
                is MdBlock.NumberedList -> {
                    block.items.forEachIndexed { itemIndex, item ->
                        val start = builder.length
                        builder.append((itemIndex + 1).toString()).append(". ")
                        appendInline(builder, item, accent, density)
                        val end = builder.length
                        builder.setSpan(
                            LeadingMarginSpan.Standard(0, (density * 18).toInt()),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        builder.append('\n')
                    }
                    if (builder.isNotEmpty() && builder[builder.length - 1] == '\n') {
                        builder.delete(builder.length - 1, builder.length)
                    }
                }
                MdBlock.Divider -> builder.append("────────")
            }
        }
        return builder
    }

    private fun appendInline(
        builder: SpannableStringBuilder,
        content: List<MdInline>,
        accent: Int,
        density: Float
    ) {
        for (item in content) {
            when (item) {
                is MdInline.Text -> builder.append(item.text)
                is MdInline.Bold -> {
                    val start = builder.length
                    appendInline(builder, item.children, accent, density)
                    builder.setSpan(
                        StyleSpan(Typeface.BOLD), start, builder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                is MdInline.Italic -> {
                    val start = builder.length
                    appendInline(builder, item.children, accent, density)
                    builder.setSpan(
                        StyleSpan(Typeface.ITALIC), start, builder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                is MdInline.Code -> {
                    val start = builder.length
                    builder.append(item.text)
                    val end = builder.length
                    builder.setSpan(
                        TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    builder.setSpan(
                        BackgroundColorSpan(CODE_BACKGROUND), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                is MdInline.Link -> {
                    val start = builder.length
                    builder.append(item.label)
                    val end = builder.length
                    builder.setSpan(
                        CoralUrlSpan(item.url, accent), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    builder.setSpan(
                        ForegroundColorSpan(accent), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }
    }

    private fun appendCodeBlock(builder: SpannableStringBuilder, code: String, density: Float) {
        val start = builder.length
        builder.append(code)
        val end = builder.length
        if (end == start) return
        builder.setSpan(
            TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            CodeBlockSpan(CODE_BACKGROUND, (density * 10).toInt(), (density * 5).toInt()),
            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            LeadingMarginSpan.Standard((density * 10).toInt()),
            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private class CoralUrlSpan(url: String, private val color: Int) : URLSpan(url) {
        override fun updateDrawState(ds: android.text.TextPaint) {
            super.updateDrawState(ds)
            ds.color = color
            ds.isUnderlineText = false
        }
    }

    private class CodeBlockSpan(
        private val color: Int,
        private val horizontalPadding: Int,
        private val verticalPadding: Int
    ) : LineBackgroundSpan {

        override fun drawBackground(
            canvas: Canvas,
            paint: Paint,
            left: Int,
            right: Int,
            top: Int,
            baseline: Int,
            bottom: Int,
            text: CharSequence,
            start: Int,
            end: Int,
            lineNumber: Int
        ) {
            val background = Paint(paint)
            background.color = color
            background.style = Paint.Style.FILL
            val rect = RectF(
                (left - horizontalPadding).toFloat(),
                (top - verticalPadding).toFloat(),
                (right + horizontalPadding).toFloat(),
                (bottom + verticalPadding).toFloat()
            )
            canvas.drawRoundRect(rect, horizontalPadding.toFloat(), horizontalPadding.toFloat(), background)
        }
    }
}
