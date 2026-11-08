package com.xnotes.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import android.content.ClipData
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.measure.LatexMeasurerState
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.xnotes.ai.AiMarkdown
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.ParaAlign
import com.xnotes.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Native Compose text: selectable, no WebView, HTML execution or remote image fetches. */
@Composable
internal fun AiMarkdownText(source: String) {
    val palette = LocalPalette.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    fun copy(text: String) { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("AI", text))) } }
    val blocks by produceState<List<AiMarkdown.Block>>(emptyList(), source) {
        value = withContext(Dispatchers.Default) {
            runCatching { AiMarkdown.parse(source) }.getOrElse {
                listOf(AiMarkdown.Block.Code(source, ""))
            }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s6)) {
        blocks.forEach { block ->
            when (block) {
                is AiMarkdown.Block.Prose -> {
                    val p = block.paragraph
                    if (p.rule) HorizontalDivider(color = palette.border.toComposeColor())
                    else Row(Modifier.fillMaxWidth().padding(start = Space.s12 * p.indent)) {
                        if (p.quote != null) Text("│ ", color = palette.textDim.toComposeColor())
                        if (block.marker.isNotEmpty()) Text("${block.marker} ", color = palette.text.toComposeColor(), fontSize = TextSize.bodyLarge)
                        MarkdownParagraph(p, Modifier.weight(1f))
                    }
                }
                is AiMarkdown.Block.Math -> Row(Modifier.fillMaxWidth().padding(start = Space.s12 * block.paragraph.indent)) {
                    if (block.paragraph.quote != null) Text("│ ", color = palette.textDim.toComposeColor())
                    DisplayMath(block.latex, block.pending, Modifier.weight(1f))
                }
                is AiMarkdown.Block.Code -> Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                    .background(palette.surface.toComposeColor()).padding(start = Space.s12, end = Space.s4, bottom = Space.s12)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(block.language.ifBlank { stringResource(com.xnotes.R.string.ai_code) }, fontSize = TextSize.caption, color = palette.textDim.toComposeColor())
                        val copyLabel = stringResource(com.xnotes.R.string.ai_copy_code)
                        Tooltip(copyLabel) {
                            IconButton(onClick = { copy(block.text) }, Modifier.size(MinTouchTarget)) {
                                Icon(com.xnotes.ui.icons.XnotesIcons.copy, copyLabel, Modifier.size(16.dp), tint = palette.textDim.toComposeColor())
                            }
                        }
                    }
                    Text(block.text, Modifier.horizontalScroll(rememberScrollState()), softWrap = false,
                        fontFamily = FontFamily.Monospace, fontSize = TextSize.body, color = palette.text.toComposeColor())
                }
                is AiMarkdown.Block.Table -> Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    block.rows.forEachIndexed { rowIndex, row ->
                        Row(Modifier.height(IntrinsicSize.Min)) {
                            row.forEach { cell ->
                                Column(Modifier.width(180.dp).fillMaxHeight().border(1.dp, palette.border.toComposeColor())
                                    .background(if (rowIndex == 0) palette.surface.toComposeColor() else palette.menuBg.toComposeColor()).padding(Space.s8)) {
                                    cell.forEach { MarkdownParagraph(it, header = rowIndex == 0) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Formulas inked in the text colour on a clear ground, [MathScale] larger than text of [size]. */
@Composable
private fun mathConfig(size: TextUnit): LatexConfig {
    val ink = LocalPalette.current.text.toComposeColor()
    return remember(ink, size) { LatexConfig(fontSize = size * TextSize.MathScale, theme = LatexTheme.light(color = ink, backgroundColor = Color.Transparent)) }
}

/**
 * Whether [latex] can be typeset in this black-and-white UI: something to set, and nothing the
 * library would paint in a colour of its own (`\color`, or the red it marks unknown commands in).
 * Anything else stays as its LaTeX source, which is still readable and copyable.
 */
private fun typesets(latex: String): Boolean = runCatching {
    val nodes = com.xnotes.platform.LatexInternals.parse(latex)
    nodes.isNotEmpty() && !com.xnotes.platform.LatexInternals.ownColors(nodes)
}.getOrDefault(false)

/**
 * A `$$…$$` or `\[…\]` equation: centred, scrolling sideways when wider than the answer. While it
 * is still streaming in, or when it will not set, its source shows muted and wrapped instead.
 */
@Composable
private fun DisplayMath(latex: String, pending: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val ok = !pending && remember(latex) { typesets(latex) }
    val config = mathConfig(TextSize.bodyLarge)
    if (!ok) {
        Text(latex.trim(), modifier.padding(vertical = Space.s4), fontFamily = FontFamily.Monospace,
            fontSize = TextSize.body, color = palette.textDim.toComposeColor())
        return
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = maxWidth
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Box(Modifier.widthIn(min = width).padding(vertical = Space.s4), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Latex("\\displaystyle $latex", Modifier, config)
            }
        }
    }
}

@Composable
private fun MarkdownParagraph(p: Paragraph, modifier: Modifier = Modifier, header: Boolean = false) {
    val palette = LocalPalette.current
    val uriHandler = LocalUriHandler.current
    val density = LocalDensity.current
    var linkError by remember { mutableStateOf(false) }
    val fontSize = when (p.headingLevel) { 1 -> TextSize.display; 2 -> TextSize.headline; 3 -> TextSize.heading; 4, 5, 6 -> TextSize.title; else -> TextSize.bodyLarge }
    val mathConfig = mathConfig(fontSize)
    val measurer: LatexMeasurerState? = if (p.runs.any { it.style.math }) rememberLatexMeasurer(mathConfig) else null
    // Inline formulas sit in the line as placeholders the size the measurer gives them; one in
    // bold text or a header row is set bold like the words around it.
    val formulas = p.runs.filter { it.style.math }.map { it.text to (it.style.bold || header) }
    val sets = remember(formulas, mathConfig, measurer) {
        formulas.distinct().associateWith { (latex, bold) ->
            val source = listOfNotNull("\\boldsymbol{$latex}".takeIf { bold }, latex).firstOrNull { typesets(it) }
            val size = source?.let { runCatching { measurer?.measure(it, mathConfig) }.getOrNull() }
            if (size != null && size.widthPx > 0f && size.heightPx > 0f) source to size else null
        }
    }
    val inline = mutableMapOf<String, InlineTextContent>()
    var formula = 0
    p.runs.forEachIndexed { index, run ->
        if (!run.style.math) return@forEachIndexed
        val (latex, size) = sets[formulas[formula++]] ?: return@forEachIndexed
        inline["math$index"] = InlineTextContent(Placeholder(
            with(density) { size.widthPx.toSp() }, with(density) { size.heightPx.toSp() }, PlaceholderVerticalAlign.TextCenter,
        )) { Latex(latex, Modifier.fillMaxSize(), mathConfig) }
    }
    val text = buildAnnotatedString {
        p.runs.forEachIndexed { index, run ->
            val style = run.style
            // The source stands in when the text is copied.
            if ("math$index" in inline) { appendInlineContent("math$index", run.text); return@forEachIndexed }
            withStyle(SpanStyle(
                fontWeight = if (style.bold || header) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
                fontFamily = if (style.code || style.math) FontFamily.Monospace else FontFamily.Default,
                background = if (style.code || style.highlight != null) palette.surface.toComposeColor() else androidx.compose.ui.graphics.Color.Unspecified,
                textDecoration = TextDecoration.combine(buildList {
                    if (style.strike) add(TextDecoration.LineThrough)
                    if (style.underline || style.link != null) add(TextDecoration.Underline)
                }),
            )) {
                val url = style.link
                if (url == null) append(run.text)
                else withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = palette.text.toComposeColor()))) {
                    runCatching { uriHandler.openUri(url) }.onFailure { linkError = true }
                }) { append(run.text) }
            }
        }
    }
    Text(text, modifier, color = palette.text.toComposeColor(), inlineContent = inline,
        fontSize = fontSize,
        textAlign = when (p.align) { ParaAlign.CENTER -> TextAlign.Center; ParaAlign.RIGHT -> TextAlign.Right; else -> TextAlign.Start })
    if (linkError) AlertDialog(onDismissRequest = { linkError = false }, text = { Text(stringResource(com.xnotes.R.string.ai_link_failed)) },
        confirmButton = { TextButton(onClick = { linkError = false }) { Text(stringResource(com.xnotes.R.string.ai_ok)) } })
}
