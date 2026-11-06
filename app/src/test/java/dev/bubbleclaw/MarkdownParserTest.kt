package dev.bubbleclaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    private fun firstParagraph(text: String): List<MdInline> {
        val block = MarkdownParser.parse(text).first()
        assertTrue(block is MdBlock.Paragraph)
        return (block as MdBlock.Paragraph).content
    }

    @Test
    fun rendersBold() {
        val inline = firstParagraph("Voici du **gras** ici")
        val bold = inline.filterIsInstance<MdInline.Bold>().single()
        assertEquals("gras", (bold.children.single() as MdInline.Text).text)
    }

    @Test
    fun rendersInlineCode() {
        val inline = firstParagraph("Utilise `adb logcat` maintenant")
        val code = inline.filterIsInstance<MdInline.Code>().single()
        assertEquals("adb logcat", code.text)
    }

    @Test
    fun rendersFencedCodeBlock() {
        val blocks = MarkdownParser.parse("```kotlin\nval x = 1\nprintln(x)\n```")
        val code = blocks.filterIsInstance<MdBlock.CodeBlock>().single()
        assertEquals("val x = 1\nprintln(x)", code.code)
    }

    @Test
    fun rendersBulletList() {
        val blocks = MarkdownParser.parse("- un\n- deux\n\n1. premier\n2. second")
        val bullets = blocks.filterIsInstance<MdBlock.BulletList>().single()
        assertEquals(2, bullets.items.size)
        val numbered = blocks.filterIsInstance<MdBlock.NumberedList>().single()
        assertEquals(2, numbered.items.size)
        assertEquals("premier", (numbered.items.first().single() as MdInline.Text).text)
    }

    @Test
    fun unclosedBoldDuringStreamingDoesNotBreak() {
        val inline = firstParagraph("Réponse en cours **sans fin")
        assertTrue(inline.isNotEmpty())
        assertTrue(inline.filterIsInstance<MdInline.Text>().joinToString("") { it.text }.contains("**"))
    }
}
