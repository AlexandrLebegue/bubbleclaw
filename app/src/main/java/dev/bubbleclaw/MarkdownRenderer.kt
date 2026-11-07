package dev.bubbleclaw

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.ReplacementSpan
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
                is MdBlock.Table -> appendTable(builder, block, context, density)
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

    private fun appendTable(
        builder: SpannableStringBuilder,
        block: MdBlock.Table,
        context: Context,
        density: Float
    ) {
        val start = builder.length
        builder.append('\uFFFC')
        val span = TableSpan(
            headers = block.headers.map { inlineText(it) },
            rows = block.rows.map { row -> row.map { inlineText(it) } },
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.78f).toInt(),
            textColor = ContextCompat.getColor(context, R.color.text_primary),
            headerColor = ContextCompat.getColor(context, R.color.coral_light),
            borderColor = ContextCompat.getColor(context, R.color.panel_high),
            headerBackground = ContextCompat.getColor(context, R.color.panel_high),
            density = density
        )
        builder.setSpan(span, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun inlineText(content: List<MdInline>): String {
        val builder = StringBuilder()
        fun append(items: List<MdInline>) {
            for (item in items) {
                when (item) {
                    is MdInline.Text -> builder.append(item.text)
                    is MdInline.Bold -> append(item.children)
                    is MdInline.Italic -> append(item.children)
                    is MdInline.Code -> builder.append(item.text)
                    is MdInline.Link -> builder.append(item.label)
                }
            }
        }
        append(content)
        return builder.toString()
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

    private class TableSpan(
        private val headers: List<String>,
        private val rows: List<List<String>>,
        private val maxWidth: Int,
        private val textColor: Int,
        private val headerColor: Int,
        private val borderColor: Int,
        private val headerBackground: Int,
        private val density: Float
    ) : ReplacementSpan() {

        private val horizontalPadding = (density * 8).toInt()
        private val verticalPadding = (density * 6).toInt()
        private val borderWidth = (density * 1).coerceAtLeast(1f)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = borderColor
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
        }
        private val headerBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = headerBackground
            style = Paint.Style.FILL
        }

        private var cachedWidth = 0
        private var cachedHeight = 0
        private var columnWidths: IntArray = IntArray(0)
        private var rowHeights: IntArray = IntArray(0)
        private var headerLayouts: List<StaticLayout> = emptyList()
        private var rowLayouts: List<List<StaticLayout>> = emptyList()

        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int {
            compute(paint)
            fm?.let {
                it.ascent = -cachedHeight
                it.descent = 0
                it.top = -cachedHeight
                it.bottom = 0
            }
            return cachedWidth
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            baseline: Int,
            bottom: Int,
            paint: Paint
        ) {
            if (columnWidths.isEmpty()) compute(paint)
            val tableTop = top.toFloat()
            var y = tableTop
            canvas.drawRect(x, y, x + cachedWidth, y + rowHeights[0], headerBackgroundPaint)
            drawRow(canvas, headerLayouts, columnWidths, x, y, rowHeights[0])
            y += rowHeights[0]
            rows.forEachIndexed { index, _ ->
                drawRow(canvas, rowLayouts[index], columnWidths, x, y, rowHeights[index + 1])
                y += rowHeights[index + 1]
            }
            drawBorders(canvas, x, tableTop)
        }

        private fun drawRow(
            canvas: Canvas,
            layouts: List<StaticLayout>,
            widths: IntArray,
            left: Float,
            top: Float,
            height: Int
        ) {
            var x = left
            layouts.forEachIndexed { index, layout ->
                val textTop = top + verticalPadding
                canvas.save()
                canvas.translate(x + horizontalPadding, textTop)
                layout.draw(canvas)
                canvas.restore()
                x += widths[index]
            }
        }

        private fun drawBorders(canvas: Canvas, left: Float, top: Float) {
            var x = left
            for (width in columnWidths) {
                canvas.drawLine(x, top, x, top + cachedHeight, borderPaint)
                x += width
            }
            canvas.drawLine(x, top, x, top + cachedHeight, borderPaint)
            var y = top
            for (height in rowHeights) {
                canvas.drawLine(left, y, left + cachedWidth, y, borderPaint)
                y += height
            }
            canvas.drawLine(left, y, left + cachedWidth, y, borderPaint)
        }

        private fun compute(paint: Paint) {
            val columns = headers.size.coerceAtLeast(1)
            val textPaint = TextPaint(paint).apply {
                color = textColor
                typeface = Typeface.DEFAULT
            }
            val headerPaint = TextPaint(paint).apply {
                color = headerColor
                typeface = Typeface.DEFAULT_BOLD
            }
            val weights = IntArray(columns) { 1 }
            for (column in 0 until columns) {
                var longest = headers.getOrElse(column) { "" }.length
                for (row in rows) {
                    longest = maxOf(longest, row.getOrElse(column) { "" }.length)
                }
                weights[column] = longest.coerceIn(4, 60)
            }
            val totalWeight = weights.sum().coerceAtLeast(1)
            val available = (maxWidth - borderWidth.toInt() * (columns + 1))
                .coerceAtLeast(columns * 48)
            columnWidths = IntArray(columns) { index ->
                (available * weights[index] / totalWeight).coerceAtLeast(48)
            }
            headerLayouts = headers.mapIndexed { index, value ->
                layout(value, headerPaint, columnWidths[index] - horizontalPadding * 2)
            }
            rowLayouts = rows.map { row ->
                row.mapIndexed { index, value ->
                    layout(value, textPaint, columnWidths[index] - horizontalPadding * 2)
                }
            }
            val headerHeight = headerLayouts.maxOfOrNull { it.height } ?: 0
            rowHeights = IntArray(rows.size + 1)
            rowHeights[0] = headerHeight + verticalPadding * 2
            rows.forEachIndexed { index, _ ->
                val height = rowLayouts[index].maxOfOrNull { it.height } ?: 0
                rowHeights[index + 1] = height + verticalPadding * 2
            }
            cachedWidth = columnWidths.sum().coerceAtLeast(1)
            cachedHeight = rowHeights.sum()
        }

        private fun layout(value: String, paint: TextPaint, width: Int): StaticLayout =
            StaticLayout.Builder.obtain(value, 0, value.length, paint, width.coerceAtLeast(1))
                .setIncludePad(false)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
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
