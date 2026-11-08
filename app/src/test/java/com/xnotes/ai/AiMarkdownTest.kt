package com.xnotes.ai

import org.junit.Assert.*
import org.junit.Test

class AiMarkdownTest {
    @Test fun rendersStylesAndNestedListNumbers() {
        val blocks = AiMarkdown.parse("# 标题\n\n**粗体** *斜体* ~~删除~~ `code`\n\n3. 三\n4. 四\n   - 内层")
        val prose = blocks.filterIsInstance<AiMarkdown.Block.Prose>()
        assertEquals(1, prose.first().paragraph.headingLevel)
        val styles = prose.flatMap { it.paragraph.runs }.map { it.style }
        assertTrue(styles.any { it.bold }); assertTrue(styles.any { it.italic })
        assertTrue(styles.any { it.strike }); assertTrue(styles.any { it.code })
        assertTrue(prose.any { it.marker == "3." }); assertTrue(prose.any { it.marker == "4." })
        assertTrue(prose.any { it.marker == "•" })
    }
    @Test fun unfinishedFenceKeepsLiteralCodeAndCompletesDuringStreaming() {
        val partial = AiMarkdown.parse("```kotlin\nval x = \"**literal**\"\nnext")
        val code = partial.filterIsInstance<AiMarkdown.Block.Code>().single()
        assertEquals("val x = \"**literal**\"\nnext", code.text)
        val complete = AiMarkdown.parse("```kotlin\nval x = 1\n```\n\nDone")
        assertEquals("val x = 1", complete.filterIsInstance<AiMarkdown.Block.Code>().single().text)
        assertTrue(complete.filterIsInstance<AiMarkdown.Block.Prose>().any { it.paragraph.plainText() == "Done" })
    }
    @Test fun groupsTableCellsWithoutLosingInlineStyles() {
        val table = AiMarkdown.parse("| A | B |\n| --- | --- |\n| **bold** | two |").filterIsInstance<AiMarkdown.Block.Table>().single()
        assertEquals(2, table.rows.size); assertEquals(2, table.rows.first().size)
        assertEquals("two", table.rows[1][1].single().plainText())
        assertTrue(table.rows[1][0].single().runs.first().style.bold)
    }
    @Test fun untrustedHtmlAndImagesStayTextAndUnsafeLinksCannotOpen() {
        val text = "[safe](https://example.org) [bad](javascript:alert) ![alt](https://example.org/track.png)\n\n<script>alert(1)</script>"
        val runs = AiMarkdown.parse(text).filterIsInstance<AiMarkdown.Block.Prose>().flatMap { it.paragraph.runs }
        assertEquals(listOf("https://example.org"), runs.mapNotNull { it.style.link })
        assertTrue(runs.joinToString("") { it.text }.contains("alt"))
        assertTrue(runs.joinToString("") { it.text }.contains("<script>"))
    }
    @Test fun tasksQuotesAndEveryStreamingPrefixRemainReadable() {
        val source = "> 引用\n\n- [x] 完成\n- [ ] 待办\n\n**加粗** 与 [链接](https://example.org)"
        for (length in 0..source.length) AiMarkdown.parse(source.take(length))
        val prose = AiMarkdown.parse(source).filterIsInstance<AiMarkdown.Block.Prose>()
        assertTrue(prose.any { it.paragraph.quote != null })
        assertTrue(prose.any { it.marker == "☑" })
        assertTrue(prose.any { it.marker == "☐" })
        assertTrue(AiMarkdown.parse("").isEmpty())
    }
    @Test fun mathDelimitersFromModelsBecomeFormulas() {
        val source = "设 \\(a^2+b^2=c^2\\)，且 \$x_1\$ 为根。\n\n\$\$\n\\int_0^1 x\\,dx\n\$\$\n\n\\[\n\\frac{1}{2}\n\\]"
        val blocks = AiMarkdown.parse(source)
        val inline = blocks.filterIsInstance<AiMarkdown.Block.Prose>().first().paragraph.runs.filter { it.style.math }.map { it.text }
        assertEquals(listOf("a^2+b^2=c^2", "x_1"), inline)
        val display = blocks.filterIsInstance<AiMarkdown.Block.Math>()
        assertEquals(listOf("\\int_0^1 x\\,dx", "\\frac{1}{2}"), display.map { it.latex.trim() })
        assertTrue(display.none { it.pending })
    }
    @Test fun pricesStayProseLikeRemarkMath() {
        for (text in listOf("价格 \$5 和 \$10", "价格 \$5-\$10 元", "价格 \$5，\$10", "\$5 and 10\$", "公式 \$ x \$ 空格", "a \\\$x\\\$ b")) {
            val runs = AiMarkdown.parse(text).filterIsInstance<AiMarkdown.Block.Prose>().flatMap { it.paragraph.runs }
            assertTrue(text, runs.none { it.style.math })
            assertEquals(text, text.replace("\\\$", "\$"), runs.joinToString("") { it.text })
        }
        val formula = AiMarkdown.parse("当 \$x=5\$ 时，\$y_{10}\$ 与 \$2x+1\$").filterIsInstance<AiMarkdown.Block.Prose>().single()
        assertEquals(listOf("x=5", "y_{10}", "2x+1"), formula.paragraph.runs.filter { it.style.math }.map { it.text })
    }
    @Test fun codeKeepsDollarsAndBackslashesVerbatim() {
        assertEquals("`\$5 and \$10` \\\$", AiMath.normalize("`\$5 and \$10` \$"))
        val fenced = "```sh\necho \$HOME \$1\n```\n> ~~~\n> \$a \$b\n> ~~~"
        assertEquals(fenced, AiMath.normalize(fenced))
        val code = AiMarkdown.parse("```sh\necho \$HOME\n```").filterIsInstance<AiMarkdown.Block.Code>().single()
        assertEquals("echo \$HOME", code.text)
    }
    @Test fun displayFormulaStillStreamingIsPendingUntilItCloses() {
        val partial = AiMarkdown.parse("前文\n\n\$\$\n\\frac{a}{").filterIsInstance<AiMarkdown.Block.Math>().single()
        assertTrue(partial.pending)
        val bracket = AiMarkdown.parse("前文\n\n\\[\n\\frac{a}{").filterIsInstance<AiMarkdown.Block.Math>()
        assertTrue(bracket.all { it.pending })
        val done = AiMarkdown.parse("前文\n\n\$\$\n\\frac{a}{b}\n\$\$\n").filterIsInstance<AiMarkdown.Block.Math>().single()
        assertFalse(done.pending)
        val source = "说明 \$x^2\$ 与\n\n\$\$\n\\sum_{i=1}^n i\n\$\$\n\n价格 \$5-\$10"
        for (length in 0..source.length) AiMarkdown.parse(source.take(length))
    }
    @Test fun boldFormulasParseInTheBundledRenderer() {
        val nodes = com.xnotes.platform.LatexInternals.parse("\\boldsymbol{x^2}")
        assertTrue(nodes.isNotEmpty())
        assertFalse(com.xnotes.platform.LatexInternals.ownColors(nodes))
    }
}
