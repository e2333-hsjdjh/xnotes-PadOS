package com.xnotes.ai

import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.MarkdownParser
import com.xnotes.core.text.ListKind

internal object AiMarkdown {
    sealed interface Block {
        data class Prose(val paragraph: Paragraph, val marker: String) : Block
        data class Code(val text: String, val language: String) : Block
        data class Table(val rows: List<List<List<Paragraph>>>) : Block
        /** A display equation on its own; [pending] while its closing `$$` or `\]` has not arrived. */
        data class Math(val paragraph: Paragraph, val pending: Boolean) : Block {
            val latex: String get() = paragraph.runs.single().text
        }
    }
    fun parse(source: String): List<Block> {
        val paragraphs = MarkdownParser.parse(AiMath.normalize(source))
        val blocks = mutableListOf<Block>()
        val numbers = mutableMapOf<Int, Int>()
        var index = 0
        while (index < paragraphs.size) {
            val p = paragraphs[index]
            val table = p.table
            when {
                table != null -> {
                    val cells = mutableListOf<MutableList<Paragraph>>()
                    while (index < paragraphs.size && paragraphs[index].table === table) {
                        val cell = paragraphs[index++]
                        if (cell.cellStart || cells.isEmpty()) cells.add(mutableListOf())
                        cells.last().add(cell)
                    }
                    blocks += Block.Table(cells.chunked(table.cols))
                }
                p.codeLang != null -> {
                    val lines = mutableListOf<String>()
                    while (index < paragraphs.size && paragraphs[index].codeLang == p.codeLang) {
                        lines += paragraphs[index++].plainText()
                    }
                    blocks += Block.Code(lines.joinToString("\n"), p.codeLang.orEmpty())
                }
                p.runs.size == 1 && p.runs[0].style.mathDisplay -> {
                    index++
                    blocks += Block.Math(p, pending = index == paragraphs.size && AiMath.endsInsideDisplay(source))
                }
                else -> {
                    numbers.keys.removeAll { it > p.indent }
                    val marker = when (p.list) {
                        ListKind.ORDERED -> {
                            val number = p.listStart ?: ((numbers[p.indent] ?: 0) + 1)
                            numbers[p.indent] = number
                            "$number."
                        }
                        ListKind.BULLET -> { numbers.remove(p.indent); "•" }
                        ListKind.CHECK -> { numbers.remove(p.indent); if (p.checked) "☑" else "☐" }
                        ListKind.NONE -> { if (p.length > 0 && p.indent == 0) numbers.clear(); "" }
                    }
                    blocks += Block.Prose(p, marker)
                    index++
                }
            }
        }
        return blocks
    }
}
