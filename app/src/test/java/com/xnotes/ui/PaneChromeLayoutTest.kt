package com.xnotes.ui

import com.xnotes.settings.ToolbarPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneChromeLayoutTest {
    @Test fun dockedToolbarUsesItsMeasuredDepthAndPhysicalEdge() {
        assertEquals(PaneChromeGeometry(0, 60, 800, 540, 0, 0),
            paneChromeGeometry(800, 600, 800, 60, ToolbarPosition.TOP, true))
        assertEquals(PaneChromeGeometry(0, 0, 800, 540, 0, 540),
            paneChromeGeometry(800, 600, 800, 60, ToolbarPosition.BOTTOM, true))
        assertEquals(PaneChromeGeometry(60, 0, 740, 600, 0, 0),
            paneChromeGeometry(800, 600, 60, 600, ToolbarPosition.LEFT, true))
        assertEquals(PaneChromeGeometry(0, 0, 740, 600, 740, 0),
            paneChromeGeometry(800, 600, 60, 600, ToolbarPosition.RIGHT, true))
    }

    @Test fun floatingOrHiddenToolbarDoesNotShrinkOrOffsetContent() {
        for (position in ToolbarPosition.entries) {
            val geometry = paneChromeGeometry(803, 607, 60, 60, position, false)
            assertEquals(0, geometry.contentX)
            assertEquals(0, geometry.contentY)
            assertEquals(803, geometry.contentWidth)
            assertEquals(607, geometry.contentHeight)
        }
    }

    @Test fun unboundedAxisSizesToContentInsteadOfFailing() {
        // A toolbar docked across this axis adds its depth; one lying along it only sets a floor.
        assertEquals(560, paneChromeUnboundedExtent(500, 60, toolbarAlongAxis = true, min = 0))
        assertEquals(800, paneChromeUnboundedExtent(500, 800, toolbarAlongAxis = false, min = 0))
        assertEquals(500, paneChromeUnboundedExtent(500, 60, toolbarAlongAxis = false, min = 0))
        assertEquals("parent minimum is respected", 900, paneChromeUnboundedExtent(500, 60, toolbarAlongAxis = true, min = 900))
        assertEquals("empty content still fits the toolbar", 60, paneChromeUnboundedExtent(0, 60, toolbarAlongAxis = true, min = 0))
        // The resulting size feeds the same geometry as a bounded pane.
        val height = paneChromeUnboundedExtent(500, 60, toolbarAlongAxis = true, min = 0)
        assertEquals(PaneChromeGeometry(0, 60, 800, 500, 0, 0),
            paneChromeGeometry(800, height, 800, 60, ToolbarPosition.TOP, true))
    }

    @Test fun narrowAndZeroSizePanesNeverProduceNegativeContent() {
        for (width in listOf(0, 1, 59, 801)) {
            for (height in listOf(0, 1, 59, 603)) {
                for (position in ToolbarPosition.entries) {
                    val barWidth = if (position.vertical) minOf(60, width) else width
                    val barHeight = if (position.vertical) height else minOf(60, height)
                    val g = paneChromeGeometry(width, height, barWidth, barHeight, position, true)
                    assertTrue(g.contentWidth >= 0 && g.contentHeight >= 0)
                    assertTrue(g.contentX >= 0 && g.contentY >= 0)
                    assertTrue(g.contentX + g.contentWidth <= width)
                    assertTrue(g.contentY + g.contentHeight <= height)
                    assertEquals(width * height,
                        g.contentWidth * g.contentHeight + barWidth * barHeight)
                }
            }
        }
    }
}
