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
    fun parsesMarkdownTableIntoStructuredBlock() {
        val blocks = MarkdownParser.parse(
            "| Service | Rôle | État |\n" +
                "|---|---|---|\n" +
                "| `nvidia-powerlimit` | one-shot | enabled |\n" +
                "| gpumon | log CSV | enabled |"
        )
        val table = blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals(3, table.headers.size)
        assertEquals(2, table.rows.size)
        assertEquals("Service", (table.headers[0].single() as MdInline.Text).text)
        assertEquals("nvidia-powerlimit", (table.rows[0][0].single() as MdInline.Code).text)
        assertEquals("gpumon", (table.rows[1][0].single() as MdInline.Text).text)
    }

    @Test
    fun tableRowsArePaddedAndTruncatedToHeaderWidth() {
        val blocks = MarkdownParser.parse(
            "A | B\n" +
                "--- | ---\n" +
                "1 |\n" +
                "1 | 2 | 3"
        )
        val table = blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals(2, table.rows.size)
        assertEquals(2, table.rows[0].size)
        assertEquals(2, table.rows[1].size)
        assertEquals("2", (table.rows[1][1].single() as MdInline.Text).text)
    }

    @Test
    fun angleBracketUrlBecomesClickableLink() {
        val inline = firstParagraph("<https://www.amazon.fr/ETOPARS-Voiture-Autocollant-Protection-Véhicule/dp/B0D7LQ8JZP>")
        val link = inline.filterIsInstance<MdInline.Link>().single()
        assertEquals("https://www.amazon.fr/ETOPARS-Voiture-Autocollant-Protection-Véhicule/dp/B0D7LQ8JZP", link.url)
        assertEquals(link.url, link.label)
    }

    @Test
    fun bareUrlBecomesLinkAndTrailingPunctuationIsDropped() {
        val inline = firstParagraph("Voir https://example.com/page, puis la suite.")
        val link = inline.filterIsInstance<MdInline.Link>().single()
        assertEquals("https://example.com/page", link.url)
    }

    @Test
    fun wwwUrlIsNormalizedToHttps() {
        val inline = firstParagraph("Le site www.example.com/x fonctionne")
        val link = inline.filterIsInstance<MdInline.Link>().single()
        assertEquals("https://www.example.com/x", link.url)
        assertEquals("www.example.com/x", link.label)
    }

    @Test
    fun unclosedBoldDuringStreamingDoesNotBreak() {
        val inline = firstParagraph("Réponse en cours **sans fin")
        assertTrue(inline.isNotEmpty())
        assertTrue(inline.filterIsInstance<MdInline.Text>().joinToString("") { it.text }.contains("**"))
    }
}
