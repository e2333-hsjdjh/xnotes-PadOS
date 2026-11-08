package com.xnotes.gl

import com.xnotes.core.geometry.Flip
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem

/** Drawing values captured on the model's thread; the GL consumer never reads the live item. */
internal data class ImageDrawSnapshot(
    val source: ImageData,
    val rect: Rect,
    val orientation: Int,
    val angle: Double,
    val flip: Flip,
    val part: Rect?,
) {
    companion object {
        /** ImageData's fields and encoded file are immutable; resource lifetime is managed separately. */
        fun capture(item: ImageItem) = ImageDrawSnapshot(
            item.image, item.rect, item.orientation, item.angle, item.flip, item.part,
        )
    }
}
