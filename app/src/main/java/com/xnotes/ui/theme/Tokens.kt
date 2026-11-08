package com.xnotes.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The only text sizes chrome may use. Paper content (typed notes, templates, PDF) keeps its own
 * document sizes; this scale is for menus, bars, panels and dialogs.
 */
object TextSize {
    /** Glyphs inside icons and badges. */
    val micro = 10.sp
    val caption = 11.sp
    val label = 12.sp
    val body = 13.sp
    val bodyLarge = 14.sp
    /** Section titles and emphasised rows. */
    val title = 16.sp
    /** Dialog and screen titles. */
    val heading = 18.sp
    val headline = 20.sp
    val display = 24.sp

    /**
     * How much larger a formula is set than the text around it: Latin Modern's x-height is about
     * 0.43 em against the sans's 0.53, so at the same size a formula reads a size smaller.
     */
    const val MathScale = 1.2f
}

/**
 * The only gaps chrome may use for padding, `spacedBy` and spacers: a 4 dp grid with 2 and 6 as
 * fine steps for dense controls. Component sizes (buttons, swatches, thumbnails) are not gaps and
 * stay with their components; 1 dp is reserved for hairline offsets.
 */
object Space {
    val s2 = 2.dp
    val s4 = 4.dp
    val s6 = 6.dp
    val s8 = 8.dp
    val s12 = 12.dp
    val s16 = 16.dp
    val s20 = 20.dp
    val s24 = 24.dp
    val s32 = 32.dp
    val s40 = 40.dp
    val s48 = 48.dp
}

/** Smallest touch target for any chrome control, whatever the toolbar size. */
val MinTouchTarget = 48.dp

/** Docked sidebar with a content preview and 48 dp layer controls. */
val LayerSidebarWidth = 320.dp
val LayerThumbnailSize = 48.dp
