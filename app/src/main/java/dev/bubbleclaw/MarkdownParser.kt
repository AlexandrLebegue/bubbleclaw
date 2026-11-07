package dev.bubbleclaw

/** Inline fragment produced by the markdown parser. */
sealed interface MdInline {
    data class Text(val text: String) : MdInline
    data class Bold(val children: List<MdInline>) : MdInline
    data class Italic(val children: List<MdInline>) : MdInline
    data class Code(val text: String) : MdInline
    data class Link(val label: String, val url: String) : MdInline
}

/** Block produced by the markdown parser, independent from Android spans. */
sealed interface MdBlock {
    data class Paragraph(val content: List<MdInline>) : MdBlock
    data class Heading(val level: Int, val content: List<MdInline>) : MdBlock
    data class CodeBlock(val code: String) : MdBlock
    data class BulletList(val items: List<List<MdInline>>) : MdBlock
    data class NumberedList(val items: List<List<MdInline>>) : MdBlock
    data class Table(
        val headers: List<List<MdInline>>,
        val rows: List<List<List<MdInline>>>
    ) : MdBlock
    data object Divider : MdBlock
}

/**
 * Small, dependency-free markdown parser tuned for streamed assistant replies.
 * Unclosed delimiters are kept as literal text so a partial stream never breaks.
 */
object MarkdownParser {

    private val headingRx = Regex("^(#{1,6})\\s+(.*)$")
    private val dividerRx = Regex("^(-{3,}|\\*{3,}|_{3,})$")
    private val bulletRx = Regex("^\\s*[-*+]\\s+(.*)$")
    private val numberedRx = Regex("^\\s*\\d+[.)]\\s+(.*)$")
    private val bareUrlRx = Regex("^(https?://|www\\.)", RegexOption.IGNORE_CASE)

    fun parse(markdown: String): List<MdBlock> {
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<MdBlock>()
        val paragraph = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks.add(MdBlock.Paragraph(parseInline(paragraph.toString())))
                paragraph.clear()
            }
        }

        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()

            if (trimmed.startsWith("```")) {
                flushParagraph()
                val code = StringBuilder()
                index++
                while (index < lines.size && !lines[index].trim().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[index])
                    index++
                }
                if (index < lines.size) index++
                blocks.add(MdBlock.CodeBlock(code.toString()))
                continue
            }

            if (trimmed.isEmpty()) {
                flushParagraph()
                index++
                continue
            }

            if (trimmed.contains('|') && index + 1 < lines.size &&
                isTableSeparator(lines[index + 1])
            ) {
                flushParagraph()
                val headers = splitTableRow(trimmed).map(::parseInline)
                val rows = mutableListOf<List<List<MdInline>>>()
                index += 2
                while (index < lines.size) {
                    val rowLine = lines[index].trim()
                    if (rowLine.isEmpty() || !rowLine.contains('|')) break
                    val cells = splitTableRow(rowLine).map(::parseInline)
                    rows.add(normalizeRow(cells, headers.size))
                    index++
                }
                blocks.add(MdBlock.Table(headers, rows))
                continue
            }

            val heading = headingRx.matchEntire(trimmed)
            if (heading != null) {
                flushParagraph()
                blocks.add(
                    MdBlock.Heading(
                        heading.groupValues[1].length,
                        parseInline(heading.groupValues[2].trim())
                    )
                )
                index++
                continue
            }

            if (dividerRx.matches(trimmed)) {
                flushParagraph()
                blocks.add(MdBlock.Divider)
                index++
                continue
            }

            val bullet = bulletRx.matchEntire(line)
            if (bullet != null) {
                flushParagraph()
                val items = mutableListOf<List<MdInline>>()
                while (index < lines.size) {
                    val item = bulletRx.matchEntire(lines[index]) ?: break
                    items.add(parseInline(item.groupValues[1].trim()))
                    index++
                }
                blocks.add(MdBlock.BulletList(items))
                continue
            }

            val numbered = numberedRx.matchEntire(line)
            if (numbered != null) {
                flushParagraph()
                val items = mutableListOf<List<MdInline>>()
                while (index < lines.size) {
                    val item = numberedRx.matchEntire(lines[index]) ?: break
                    items.add(parseInline(item.groupValues[1].trim()))
                    index++
                }
                blocks.add(MdBlock.NumberedList(items))
                continue
            }

            if (paragraph.isNotEmpty()) paragraph.append('\n')
            paragraph.append(trimmed)
            index++
        }
        flushParagraph()
        return blocks
    }

    fun parseInline(text: String): List<MdInline> {
        val result = mutableListOf<MdInline>()
        val literal = StringBuilder()

        fun flush() {
            if (literal.isNotEmpty()) {
                result.add(MdInline.Text(literal.toString()))
                literal.clear()
            }
        }

        var index = 0
        while (index < text.length) {
            val char = text[index]

            if (char == '`') {
                val end = text.indexOf('`', index + 1)
                if (end > index + 1) {
                    flush()
                    result.add(MdInline.Code(text.substring(index + 1, end)))
                    index = end + 1
                    continue
                }
            }

            if (char == '*' || char == '_') {
                val strong = if (char == '*') "**" else "__"
                if (text.startsWith(strong, index)) {
                    val end = text.indexOf(strong, index + 2)
                    if (end > index + 2) {
                        flush()
                        result.add(MdInline.Bold(parseInline(text.substring(index + 2, end))))
                        index = end + 2
                        continue
                    }
                }
                val marker = char.toString()
                val end = text.indexOf(marker, index + 1)
                if (end > index + 1) {
                    flush()
                    result.add(MdInline.Italic(parseInline(text.substring(index + 1, end))))
                    index = end + 1
                    continue
                }
            }

            if (char == '[') {
                val labelEnd = text.indexOf(']', index + 1)
                if (labelEnd > index && labelEnd + 1 < text.length && text[labelEnd + 1] == '(') {
                    val urlEnd = text.indexOf(')', labelEnd + 2)
                    if (urlEnd > labelEnd + 2) {
                        flush()
                        result.add(
                            MdInline.Link(
                                text.substring(index + 1, labelEnd),
                                text.substring(labelEnd + 2, urlEnd)
                            )
                        )
                        index = urlEnd + 1
                        continue
                    }
                }
            }

            if (char == '<') {
                val end = text.indexOf('>', index + 1)
                if (end > index + 1) {
                    val inner = text.substring(index + 1, end).trim()
                    if (bareUrlRx.containsMatchIn(inner) || inner.startsWith("mailto:", true)) {
                        flush()
                        result.add(MdInline.Link(inner, normalizeUrl(inner)))
                        index = end + 1
                        continue
                    }
                }
            }

            if (bareUrlRx.containsMatchIn(text.substring(index, minOf(text.length, index + 8)))) {
                val end = findUrlEnd(text, index)
                if (end > index) {
                    val url = text.substring(index, end)
                    flush()
                    result.add(MdInline.Link(url, normalizeUrl(url)))
                    index = end
                    continue
                }
            }

            literal.append(char)
            index++
        }
        flush()
        return result
    }

    private fun isTableSeparator(line: String): Boolean {
        val cells = splitTableRow(line)
        if (cells.size < 2) return false
        return cells.all { cell -> Regex("^:?-{3,}:?$").matches(cell.trim()) }
    }

    private fun splitTableRow(line: String): List<String> {
        var value = line.trim()
        if (value.startsWith("|")) value = value.substring(1)
        if (value.endsWith("|")) value = value.dropLast(1)
        return value.split('|').map { it.trim() }
    }

    private fun normalizeRow(cells: List<List<MdInline>>, size: Int): List<List<MdInline>> {
        if (cells.size == size) return cells
        if (cells.size > size) return cells.take(size)
        return cells + List(size - cells.size) { emptyList() }
    }

    private fun findUrlEnd(text: String, start: Int): Int {
        var end = start
        while (end < text.length) {
            val char = text[end]
            if (char.isWhitespace() || char == '<' || char == '>') break
            end++
        }
        while (end > start && text[end - 1] in ".,;:!?") end--
        while (end > start && text[end - 1] == ')' &&
            text.substring(start, end).count { it == ')' } > text.substring(start, end).count { it == '(' }
        ) {
            end--
        }
        return end
    }

    private fun normalizeUrl(url: String): String =
        if (url.startsWith("www.", ignoreCase = true)) "https://$url" else url
}
