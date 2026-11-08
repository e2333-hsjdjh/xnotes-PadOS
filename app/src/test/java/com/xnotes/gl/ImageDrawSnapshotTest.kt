package com.xnotes.gl

import com.xnotes.core.geometry.Flip
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ImageDrawSnapshotTest {
    @Test fun laterModelEditsCannotChangeThePublishedDrawingValues() {
        val original = ImageData(File("original.png"), 80, 40)
        val item = ImageItem(original, Rect(5.0, 10.0, 80.0, 40.0), 90, 0.3, Flip.X,
            Rect(0.1, 0.2, 0.5, 0.6))
        val captured = ImageDrawSnapshot.capture(item)

        val replacement = ImageData(File("replacement.png"), 200, 100)
        item.image = replacement
        item.rect = Rect(100.0, 200.0, 400.0, 500.0)
        item.orientation = 270
        item.angle = 1.2
        item.flip = Flip.Y
        item.part = null

        assertSame(original, captured.source)
        assertEquals(Rect(5.0, 10.0, 80.0, 40.0), captured.rect)
        assertEquals(90, captured.orientation)
        assertEquals(0.3, captured.angle, 0.0)
        assertEquals(Flip.X, captured.flip)
        assertEquals(Rect(0.1, 0.2, 0.5, 0.6), captured.part)

        val next = ImageDrawSnapshot.capture(item)
        assertSame(replacement, next.source)
        assertEquals(item.rect, next.rect)
        assertEquals(270, next.orientation)
        assertEquals(1.2, next.angle, 0.0)
        assertEquals(Flip.Y, next.flip)
        assertNull(next.part)
    }

    @Test fun addingACropDoesNotChangeAnEarlierWholeImageSnapshot() {
        val item = ImageItem(ImageData(File("image.png"), 100, 100), Rect(0.0, 0.0, 100.0, 100.0))
        val whole = ImageDrawSnapshot.capture(item)
        item.part = Rect(0.0, 0.0, 0.5, 1.0)
        val cropped = ImageDrawSnapshot.capture(item)

        assertNull(whole.part)
        assertEquals(Rect(0.0, 0.0, 0.5, 1.0), cropped.part)
        assertSame(whole.source, cropped.source)
    }
}
