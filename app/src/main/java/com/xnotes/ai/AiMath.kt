package com.xnotes.ai

/**
 * Reads the maths delimiters models write the way chat renderers do (remark-math / Pandoc
 * `tex_math_dollars`, as DeepSeek Harness's client does) before an answer reaches the note parser,
 * which reads `$` the way typing converts it and so would set prices as formulas:
 *
 * - a single `$` opens inline maths only when the next character is not a space, and closes on the
 *   same line at a `$` that follows a non-space, is not half of `$$` and is not followed by a digit,
 *   so `$5 and $10`, `$5-$10` and `$5，$10` stay prose;
 * - `5 and 10` between two dollars is an amount, not a formula;
 * - every other single `$` becomes a literal `\$`; `$$`, `\(…\)` and `\[…\]` pass through, and
 *   nothing inside code fences or code spans is touched.
 */
internal object AiMath {

    fun normalize(source: String): String {
        if ('$' !in source) return source
        val out = StringBuilder(source.length + 8)
        var fence: String? = null
        source.split('\n').forEachIndexed { index, line ->
            if (index > 0) out.append('\n')
            val marker = fenceMarker(line)
            when {
                fence != null -> {
                    if (marker != null && marker[0] == fence!![0] && marker.length >= fence!!.length && line.trimEnd().endsWith(marker)) fence = null
                    out.append(line)
                }
                marker != null -> { fence = marker; out.append(line) }
                else -> normalizeLine(line, out)
            }
        }
        return out.toString()
    }

    /**
     * Whether the trimmed answer leaves a display formula open at its end: while it streams in, the
     * half-written formula is shown as source rather than set.
     */
    fun endsInsideDisplay(source: String): Boolean {
        val tail = source.trimEnd()
        return !(tail.endsWith("$$") || tail.endsWith("\\]"))
    }

    /** The backtick or tilde run opening a fence on [line], after any quote markers and indent. */
    private fun fenceMarker(line: String): String? {
        val s = line.trimStart(' ', '>', '\t')
        val c = s.firstOrNull() ?: return null
        if (c != '`' && c != '~') return null
        val run = s.takeWhile { it == c }
        return run.takeIf { it.length >= 3 }
    }

    private fun normalizeLine(line: String, out: StringBuilder) {
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' && i + 1 < line.length -> { out.append(c).append(line[i + 1]); i += 2 }
                c == '`' -> {
                    val end = codeSpanEnd(line, i)
                    out.append(line, i, end); i = end
                }
                c == '$' && line.getOrNull(i + 1) == '$' -> {
                    var j = i
                    while (j < line.length && line[j] == '$') j++
                    out.append(line, i, j); i = j
                }
                c == '$' -> {
                    val close = inlineCloser(line, i)
                    if (close < 0) { out.append("\\$"); i++ }
                    else { out.append(line, i, close + 1); i = close + 1 }
                }
                else -> { out.append(c); i++ }
            }
        }
    }

    /** Past the code span opening at [open], or past its backticks alone when none closes it. */
    private fun codeSpanEnd(line: String, open: Int): Int {
        var n = open
        while (n < line.length && line[n] == '`') n++
        val run = n - open
        var k = n
        while (k < line.length) {
            if (line[k] != '`') { k++; continue }
            var e = k
            while (e < line.length && line[e] == '`') e++
            if (e - k == run) return e
            k = e
        }
        return n
    }

    /** Where the inline formula the `$` at [open] starts closes, or -1 when that `$` is prose. */
    internal fun inlineCloser(line: String, open: Int): Int {
        val first = line.getOrNull(open + 1) ?: return -1
        if (first == '$' || first.isWhitespace()) return -1
        if (open > 0 && line[open - 1] == '$') return -1
        var k = open + 1
        while (k < line.length) {
            val c = line[k]
            if (c == '`') return -1
            if (c == '\\' && k + 1 < line.length) { k += 2; continue }
            if (c == '$') break
            k++
        }
        if (k >= line.length) return -1
        val next = line.getOrNull(k + 1)
        if (next == '$' || line[k - 1].isWhitespace() || (next != null && next.isDigit())) return -1
        return if (readsAsAmount(line.substring(open + 1, k))) -1 else k
    }

    /** `5 and 10`: a number, then words, and nothing a formula would hold. */
    private fun readsAsAmount(body: String): Boolean =
        body.first().isDigit() && body.any { it.isWhitespace() } && body.none { it in "\\^_{}=+<>" }
}
