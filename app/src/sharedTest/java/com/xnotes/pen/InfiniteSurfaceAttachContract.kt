package com.xnotes.pen

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.AddCanvasItem
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.gl.GlRenderer
import com.xnotes.gl.InfiniteCanvasView
import com.xnotes.ui.InfiniteEditor
import org.junit.Assert.*
import org.junit.Test
import java.util.Queue

/** Host-lifecycle/state contracts; no compositor, GPU pixels or Compose clipping are simulated. */
abstract class InfiniteSurfaceAttachContract {
    protected abstract fun runOnMain(block: (Context) -> Unit)

    private fun withEditor(block: (InfiniteEditor) -> Unit) = runOnMain { context ->
        val editor = InfiniteEditor(context)
        try { block(editor) } finally { detach(editor.view) }
    }

    @Test fun firstHostKeepsTheNeverAttachedSurfaces() = withEditor { editor ->
        val view = editor.view
        val pad = editor.pad
        assertSame(editor.surfaces, editor.surfacesForAttach())
        assertSame(view, editor.view)
        assertSame(pad, editor.pad)
        assertEquals(3, editor.surfaces.childCount)
    }

    @Test fun reattachmentKeepsTheDocumentViewportAppearanceAndInputBindings() = withEditor { editor ->
        val old = editor.view
        old.layout(0, 0, 320, 800)
        val viewport = editor.viewport
        viewport.zoom = 2.0
        viewport.scrollX = 19.0
        viewport.scrollY = -31.0
        viewport.insetRight = 48.0
        val document = editor.document
        val history = editor.history
        val stroke = Stroke(Tool.PEN, ToolConfig()).apply {
            addSample(Sample(10.0, 20.0, 1.0))
            addSample(Sample(30.0, 40.0, 1.0))
        }
        document.add(stroke)
        history.push(AddCanvasItem(document, stroke))
        old.background = CanvasBackground(spacing = 43.0)
        old.paperColor = Rgba(0, 0, 0, 255)
        old.darkAppearance = true
        old.accent = Rgba(255, 255, 255, 255)
        old.minimapVisible = true
        old.contentBounds = Rect(10.0, 20.0, 30.0, 40.0)
        old.setEraserCursor(Pt(70.0, 80.0), 9.0)
        var taps = 0
        old.onTwoFingerTap = { taps += 2 }
        old.onThreeFingerTap = { taps += 3 }
        old.onFourFingerTap = { taps += 4 }
        old.onKey = { true }
        val oldPad = editor.pad
        oldPad.frontBuffering = false
        oldPad.lightBlackInk = true
        val scene = old.scene

        detach(old)
        assertSame(editor.surfaces, editor.surfacesForAttach())
        val next = editor.view
        assertNotSame(old, next)
        assertNotSame(oldPad, editor.pad)
        assertNull(old.parent)
        assertNull(oldPad.parent)
        assertNull(oldPad.onSurfaceLost)
        assertFalse(editor.pad.frontBuffering)
        assertTrue(editor.pad.lightBlackInk)
        assertSame(next, editor.surfaces.getChildAt(0))
        assertSame(editor.pad, editor.surfaces.getChildAt(1))
        assertSame(editor.toolOverlay, editor.surfaces.getChildAt(2))
        assertSame(document, editor.document)
        assertSame(history, editor.history)
        assertSame(scene, next.scene)
        assertSame(viewport, editor.viewport)
        assertSame(old.input, next.input)
        assertSame(old.hover, next.hover)
        assertSame(old.genericMotion, next.genericMotion)
        assertSame(old.onInputLost, next.onInputLost)
        assertSame(old.afterLayout, next.afterLayout)
        assertSame(old.onContextReady, next.onContextReady)
        assertSame(old.crossPaneTouchGate.shouldBlock, next.crossPaneTouchGate.shouldBlock)
        next.onTwoFingerTap!!()
        next.onThreeFingerTap!!()
        next.onFourFingerTap!!()
        assertEquals(9, taps)
        assertTrue(next.onKey!!(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))

        // The new host expands from one third to the full width. The same viewport object that
        // InfiniteInteraction captured must receive the layout and publish the full-size frame.
        next.layout(0, 0, 960, 800)
        assertEquals(960, viewport.widthPx)
        assertEquals(800, viewport.heightPx)
        assertEquals(2.0, viewport.zoom, 0.0)
        assertEquals(19.0, viewport.scrollX, 0.0)
        assertEquals(-31.0, viewport.scrollY, 0.0)
        val frame = renderer(next).frame
        assertEquals(960, frame.widthPx)
        assertEquals(800, frame.heightPx)
        assertEquals(old.background, frame.background)
        assertEquals(old.paperColor, frame.paper)
        assertEquals(old.accent, frame.accent)
        assertEquals(old.contentBounds, frame.contentBounds)
        assertTrue(frame.lightBlackInk)
        assertTrue(frame.minimapVisible)
        assertEquals(48.0, frame.insetRight, 0.0)
        assertEquals(70.0, frame.cursorX, 0.0)
        assertEquals(80.0, frame.cursorY, 0.0)
        assertEquals(9.0, frame.cursorRadius, 0.0)
        assertTrue(frame.cursorVisible)
        editor.undo()
        assertTrue(document.isEmpty)
        editor.redo()
        assertSame(stroke, document.items.single())
    }

    @Test fun replacementRetiresPendingInkAndDoesNotReuseOldRendererCompletions() = withEditor { editor ->
        val old = editor.view
        val oldRenderer = renderer(old)
        // Seed a context history without creating EGL in the state-contract test.
        field(GlRenderer::class.java, "contextGen").setInt(oldRenderer, 7)
        oldRenderer.runAfterFrame { fail("old surface completion must not reach its replacement") }
        val stroke = Stroke(Tool.PEN, ToolConfig()).apply {
            addSample(Sample(10.0, 20.0, 1.0))
            addSample(Sample(30.0, 40.0, 1.0))
        }
        field(InfiniteEditor::class.java, "heldItems").set(editor, listOf(stroke))
        editor.document.add(stroke)
        editor.history.push(AddCanvasItem(editor.document, stroke))
        field(InfiniteEditor::class.java, "awaitingPublish").setBoolean(editor, true)
        val handoff = field(InfiniteEditor::class.java, "handoffGen").getInt(editor)

        detach(old)
        editor.surfacesForAttach()

        val nextRenderer = renderer(editor.view)
        assertNotSame(oldRenderer, nextRenderer)
        assertEquals(7, nextRenderer.contextGen)
        assertTrue((field(GlRenderer::class.java, "afterFrame").get(nextRenderer) as Queue<*>).isEmpty())
        assertTrue((field(InfiniteEditor::class.java, "heldItems").get(editor) as List<*>).isEmpty())
        assertFalse(field(InfiniteEditor::class.java, "awaitingPublish").getBoolean(editor))
        assertTrue(field(InfiniteEditor::class.java, "handoffGen").getInt(editor) > handoff)
        assertSame(stroke, editor.document.items.single())
        editor.undo()
        assertTrue(editor.document.isEmpty)
        editor.redo()
        assertSame(stroke, editor.document.items.single())
    }

    @Test fun liveStrokeFinishesInTheSameContentCoordinatesAfterReattachment() = withEditor { editor ->
        editor.view.layout(0, 0, 320, 800)
        editor.viewport.zoom = 2.0
        editor.viewport.scrollX = 19.0
        editor.viewport.scrollY = -31.0
        editor.pad.frontBuffering = false
        editor.armTool(Tool.PEN)
        val downAt = android.os.SystemClock.uptimeMillis()
        stylus(editor.view, downAt, 0, MotionEvent.ACTION_DOWN, 100f, 200f)
        stylus(editor.view, downAt, 20, MotionEvent.ACTION_MOVE, 150f, 250f)
        detach(editor.view)
        editor.surfacesForAttach()
        editor.view.layout(0, 0, 960, 800)
        stylus(editor.view, downAt, 40, MotionEvent.ACTION_UP, 200f, 300f)
        val stroke = editor.document.items.single() as Stroke
        assertEquals(69.0, stroke.samples.first().x, 0.01)
        assertEquals(69.0, stroke.samples.first().y, 0.01)
        assertEquals(119.0, stroke.samples.last().x, 0.01)
        assertEquals(119.0, stroke.samples.last().y, 0.01)
        editor.undo()
        assertTrue(editor.document.isEmpty)
        editor.redo()
        assertSame(stroke, editor.document.items.single())
    }

    private fun stylus(view: InfiniteCanvasView, downAt: Long, elapsed: Long, action: Int, x: Float, y: Float) {
        val pointer = MotionEvent.PointerProperties().apply { id = 7; toolType = MotionEvent.TOOL_TYPE_STYLUS }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = .5f }
        val event = MotionEvent.obtain(downAt, downAt + elapsed, action, 1, arrayOf(pointer), arrayOf(coords),
            0, 0, 1f, 1f, 17, 0, InputDevice.SOURCE_STYLUS, 0)
        try { assertTrue(view.onTouchEvent(event)) } finally { event.recycle() }
    }

    private fun renderer(view: InfiniteCanvasView): GlRenderer =
        field(InfiniteCanvasView::class.java, "glRenderer").get(view) as GlRenderer

    private fun field(owner: Class<*>, name: String) = owner.getDeclaredField(name).apply { isAccessible = true }

    private fun detach(view: InfiniteCanvasView) {
        InfiniteCanvasView::class.java.getDeclaredMethod("onDetachedFromWindow").apply {
            isAccessible = true
        }.invoke(view)
    }
}
