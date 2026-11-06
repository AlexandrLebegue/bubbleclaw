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

            literal.append(char)
            index++
        }
        flush()
        return result
    }
}
