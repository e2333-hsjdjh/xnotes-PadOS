package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarPosition
import com.xnotes.settings.ToolbarSize
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.TextSize
import com.xnotes.ui.theme.MinTouchTarget
import com.xnotes.ui.theme.toComposeColor
import com.xnotes.ui.theme.Space

/** The toolbar look from Preferences, provided once above both panes. */
val LocalToolbarLook = staticCompositionLocalOf { ToolbarLook() }

/** What every toolbar widget sizes itself by, and which way the bar runs. */
internal data class BarMetrics(
    val button: Dp,
    val circle: Dp,
    val icon: Dp,
    val swatch: Dp,
    val rule: Dp,
    val label: TextUnit,
    val vertical: Boolean = false,
) {
    /** Across the bar: one button with a little air either side. */
    val thickness: Dp get() = button + 8.dp
}

/** Every size keeps a [MinTouchTarget] button; smaller sizes shrink only what is drawn inside it. */
internal fun barMetrics(size: ToolbarSize): BarMetrics = when (size) {
    ToolbarSize.COMPACT -> BarMetrics(MinTouchTarget, 32.dp, 20.dp, 24.dp, 22.dp, TextSize.caption)
    ToolbarSize.REGULAR -> BarMetrics(52.dp, 38.dp, 22.dp, 28.dp, 26.dp, TextSize.label)
    ToolbarSize.COMFORTABLE -> BarMetrics(56.dp, 42.dp, 26.dp, 34.dp, 30.dp, TextSize.body)
}

internal val LocalBar = staticCompositionLocalOf { barMetrics(ToolbarSize.REGULAR) }

/** Where a menu opens from its anchor; a side rail sets it so its menus open beside the rail. */
internal val LocalMenuOffset = compositionLocalOf { DpOffset.Zero }

/** What a floating bar covers of the canvas, for overlays that must stay out from under it. */
internal val LocalToolbarCover = compositionLocalOf { PaddingValues(0.dp) }

/**
 * Lays a pane out with its [bar] along the edge Preferences chose and [content] in the rest. A
 * floating bar is handed to [content] to lay over its canvas instead, and [onCover] learns how many
 * px of each edge (left, top, right, bottom) it covers so the canvas keeps its pages clear of it.
 * [showToolbar] changes only chrome; the editor remains in the same native host.
 */
@Composable
internal fun ColumnScope.ToolbarAround(
    bar: @Composable () -> Unit,
    onCover: (Double, Double, Double, Double) -> Unit,
    showToolbar: Boolean = true,
    content: @Composable (floatingBar: @Composable BoxScope.() -> Unit) -> Unit,
) {
    PaneChromeLayout(
        look = LocalToolbarLook.current,
        showToolbar = showToolbar,
        bar = bar,
        onCover = onCover,
        modifier = Modifier.weight(1f).fillMaxWidth(),
        content = content,
    )
}

/** Only the focused pane advertises an active editing tool; view settings stay per document. */
internal val LocalToolbarFocused = compositionLocalOf { true }

internal val LocalEmbeddedToolbar = compositionLocalOf { false }
internal val LocalWorkspaceToolbarLayout = compositionLocalOf<com.xnotes.core.tools.ToolbarLayout?> { null }

/**
 * The strip both toolbars are laid out in: a row along the top or bottom, a column down either
 * side. [content] runs along it and scrolls when it runs long, [trailing] stays pinned at the end,
 * and [armed] is the tool the gliding circle sits under.
 */
@Composable
internal fun ToolbarFrame(
    armed: Any?,
    trailing: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (LocalEmbeddedToolbar.current) { content(); return }
    val look = LocalToolbarLook.current
    val bar = barMetrics(look.size).copy(vertical = look.position.vertical)
    val glide = remember { ToolGlide() }
    val visibleTool = armed.takeIf { LocalToolbarFocused.current }
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.extraLarge
    // Docked, the strip fills its edge; floating, a pill only as long as its tools, up to that edge.
    val surface = if (!look.floating) {
        Modifier.background(palette.panel.toComposeColor())
    } else {
        Modifier
            .clip(shape)
            .background(palette.panel.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), shape)
    }
    val stretch = when {
        look.floating -> Modifier
        bar.vertical -> Modifier.fillMaxHeight()
        else -> Modifier.fillMaxWidth()
    }
    // Material places a menu below its anchor; shifting it one bar across and one button up puts it
    // beside the rail, and on a right rail material mirrors it to the left side on its own.
    val menuOffset = if (bar.vertical) DpOffset(bar.thickness, -bar.button) else DpOffset.Zero
    val tipSide = when (look.position) {
        ToolbarPosition.TOP -> TipSide.BELOW
        ToolbarPosition.BOTTOM -> TipSide.ABOVE
        ToolbarPosition.LEFT -> TipSide.RIGHT
        ToolbarPosition.RIGHT -> TipSide.LEFT
    }
    CompositionLocalProvider(LocalToolGlide provides glide, LocalBar provides bar, LocalMenuOffset provides menuOffset, LocalTipSide provides tipSide) {
        if (bar.vertical) {
            Column(
                stretch.width(bar.thickness).then(surface),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                leading?.invoke()
                Column(
                    Modifier
                        .weight(1f, fill = !look.floating)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = Space.s4)
                        .toolGlide(glide, visibleTool),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { content() }
                trailing?.invoke()
            }
        } else {
            Row(
                stretch.height(bar.thickness).then(surface),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leading?.invoke()
                Row(
                    Modifier
                        .weight(1f, fill = !look.floating)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Space.s4)
                        .toolGlide(glide, visibleTool),
                    verticalAlignment = Alignment.CenterVertically,
                ) { content() }
                trailing?.invoke()
            }
        }
    }
}
