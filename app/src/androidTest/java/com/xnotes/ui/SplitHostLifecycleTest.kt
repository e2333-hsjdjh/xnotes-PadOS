package com.xnotes.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.xnotes.PaneActions
import com.xnotes.SplitHost
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Stroke
import com.xnotes.core.tools.Tool
import com.xnotes.pen.PenBinding
import com.xnotes.pen.PenBindingMode
import com.xnotes.pen.PenShortcut
import com.xnotes.pen.PenSignals
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarPosition
import com.xnotes.ui.theme.XnotesTheme
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the actual SplitHost/EditorPane composition, with real editor Views in a window. */
class SplitHostLifecycleTest {
    @Test fun singleCanvasWindowStylusUsesCurrentContentCoordinates() = withWorkspace { host, workspace ->
        val editor = host.onMain { workspace.editor(canvas = true) }
        val homes = mutableStateOf(emptySet<Pane>())
        val look = mutableStateOf(ToolbarLook())
        host.onMain { prepareWindowInk(editor) }
        show(host, editor, homes, look)
        host.await("single canvas window") { editor.infinite.view.isShown && editor.infinite.view.width > 0 }
        windowStroke(host, editor, editor, null)
        host.change {
            editor.infinite.layersSidebarVisible = true
            look.value = look.value.copy(position = ToolbarPosition.RIGHT)
        }
        windowStroke(host, editor, editor, null)
        host.change {
            editor.infinite.layersSidebarVisible = false
            look.value = look.value.copy(position = ToolbarPosition.BOTTOM, floating = true)
        }
        windowStroke(host, editor, editor, null)
        host.change { look.value = look.value.copy(position = ToolbarPosition.LEFT, floating = true) }
        windowStroke(host, editor, editor, null)
    }

    @Test fun windowStylusRoutesToNoteAndPdfAfterUnequalSplitsAndExchanges() = withWorkspace { host, workspace ->
        val editor = host.onMain { workspace.editor().also { it.splitSideBySide = true } }
        val second = host.onMain { workspace.second(editor, pdf = true) }
        val homes = mutableStateOf(emptySet<Pane>())
        val look = mutableStateOf(ToolbarLook())
        host.onMain {
            prepareWindowInk(editor); prepareWindowInk(second)
            editor.splitRatio = .38f
            assertFalse(editor.canvasOpen); assertFalse(second.canvasOpen)
            assertFalse(editor.hasPdf); assertTrue(second.hasPdf)
        }
        show(host, editor, homes, look)
        host.await("note and PDF windows") { editor.view.isShown && second.view.isShown }
        windowStroke(host, editor, second, editor)
        windowStroke(host, editor, editor, second)
        host.change { editor.splitReversed = true }
        host.awaitStableWindowBounds(editor.view, second.view)
        host.onMain { assertTrue(screenX(editor.view) > screenX(second.view)) }
        windowStroke(host, editor, second, editor)
        windowStroke(host, editor, editor, second)
        host.change { editor.splitSideBySide = false; editor.splitRatio = .62f; editor.splitReversed = false }
        windowStroke(host, editor, second, editor)
        windowStroke(host, editor, editor, second)
        host.change { editor.splitReversed = true }
        host.awaitStableWindowBounds(editor.view, second.view)
        host.onMain { assertTrue(screenY(editor.view) > screenY(second.view)) }
        windowStroke(host, editor, second, editor)
        windowStroke(host, editor, editor, second)
        host.change { second.sidePanelTab = SidePanelTab.LAYERS; second.sidebarVisible = true }
        windowStroke(host, editor, second, editor)
        host.change { second.sidebarVisible = false; homes.value = setOf(Pane.SECONDARY) }
        host.change { editor.closePane(second); homes.value = emptySet() }
        host.await("note restored to single pane") { !second.noteOpen && editor.view.width > host.activity.host.width / 2 }
        host.change { look.value = look.value.copy(position = ToolbarPosition.LEFT, floating = true) }
        windowStroke(host, editor, editor, second)
    }

    @Test fun singleCanvasToolbarAndSidebarChangesKeepNativeHostAttached() = withWorkspace { host, workspace ->
        val editor = host.onMain { workspace.editor(canvas = true) }
        val canvas = editor.infinite
        val homes = mutableStateOf(emptySet<Pane>())
        val look = mutableStateOf(ToolbarLook())
        val counter = host.onMain { canvas.pad.frontBuffering = false; AttachmentCounter(canvas.surfaces) }
        show(host, editor, homes, look)
        host.await("canvas attached") { canvas.surfaces.isAttachedToWindow && canvas.view.width > 0 }
        val parent = host.onMain { canvas.surfaces.parent }
        val initialView = canvas.view
        val initialPad = canvas.pad
        val attachment = initialView.renderDiagnostics.snapshot().attachmentId
        for (position in ToolbarPosition.entries) for (floating in listOf(false, true)) {
            host.change { look.value = look.value.copy(position = position, floating = floating) }
            assertRetained(host, canvas, parent, counter, attachment)
        }
        for (visible in listOf(true, false)) {
            host.change { canvas.layersSidebarVisible = visible }
            assertRetained(host, canvas, parent, counter, attachment)
        }
        host.onMain { assertSame(initialView, canvas.view); assertSame(initialPad, canvas.pad) }
    }

    @Test fun pagedEmptySplitResizesWithoutRemountingTheOpenDocument() = withWorkspace { host, workspace ->
        val editor = host.onMain { workspace.editor().also { it.splitSideBySide = true; it.pad.frontBuffering = false } }
        val homes = mutableStateOf(emptySet<Pane>())
        val look = mutableStateOf(ToolbarLook())
        val counter = host.onMain { AttachmentCounter(editor.surfaces) }
        show(host, editor, homes, look)
        host.await("paged host attached") { editor.surfaces.isAttachedToWindow && editor.view.width > 0 }
        val parent = host.onMain { editor.surfaces.parent }
        val pad = host.onMain { editor.pad }
        host.change { workspace.emptySecond(editor); homes.value = setOf(Pane.SECONDARY) }
        for (direction in listOf(true, false)) for (ratio in listOf(.18f, .5f, .82f)) {
            host.change { editor.splitSideBySide = direction; editor.splitRatio = ratio }
            assertPagedRetained(host, editor, parent, counter, pad)
        }
        host.change { homes.value = emptySet(); editor.abandonSecondary() }
        assertPagedRetained(host, editor, parent, counter, pad)
    }

    @Test fun exchangingAndClosingEitherPagedPaneRetainsTheSurvivingLogicalHost() {
        for (closePrimary in listOf(false, true)) withWorkspace { host, workspace ->
            val editor = host.onMain { workspace.editor().also { it.splitSideBySide = true } }
            val second = host.onMain { workspace.second(editor, pdf = true) }
            val homes = mutableStateOf(emptySet<Pane>())
            val look = mutableStateOf(ToolbarLook())
            val survivor = if (closePrimary) second else editor
            val closing = if (closePrimary) editor else second
            val counter = host.onMain {
                editor.pad.frontBuffering = false; second.pad.frontBuffering = false
                AttachmentCounter(survivor.surfaces)
            }
            show(host, editor, homes, look)
            host.await("both paged hosts") { editor.view.isAttachedToWindow && second.view.isAttachedToWindow }
            val parent = host.onMain { survivor.surfaces.parent }
            val pad = host.onMain { survivor.pad }
            val document = survivor.state.document
            val normalCanvas = survivor.view
            host.change { editor.splitReversed = true }
            host.awaitStableWindowBounds(editor.view, second.view)
            host.onMain { assertTrue(screenX(editor.view) > screenX(second.view)) }
            assertPagedRetained(host, survivor, parent, counter, pad)
            // The document first becomes the pane's home; closing the home then removes that pane.
            host.change { homes.value = setOf(closing.pane) }
            assertPagedRetained(host, survivor, parent, counter, pad)
            host.change { editor.closePane(closing); homes.value = emptySet() }
            host.await("closed pane removed") { !closing.noteOpen && survivor.view.width > host.activity.host.width / 2 }
            assertPagedRetained(host, survivor, parent, counter, pad)
            host.onMain {
                assertSame(document, survivor.state.document)
                assertSame(normalCanvas, survivor.view)
                if (closePrimary) { assertSame(second, editor.secondary); assertEquals(Pane.SECONDARY, survivor.pane) }
            }
        }
    }

    @Test fun realPenHoldAndDeferredSelectionSurviveLayoutChanges() = withWorkspace { host, workspace ->
        val editor = host.onMain { workspace.editor(canvas = true) }
        val canvas = editor.infinite
        val homes = mutableStateOf(emptySet<Pane>())
        val look = mutableStateOf(ToolbarLook())
        host.onMain {
            canvas.pad.frontBuffering = false
            canvas.armTool(Tool.PEN)
            val held = stylus(MotionEvent.ACTION_HOVER_MOVE, 128)
            val selected = stylus(MotionEvent.ACTION_HOVER_MOVE, 256)
            try {
                val bindings = listOf(
                    PenShortcut("Hold", PenSignals.motion(held).toList(), PenBinding("eraser", PenBindingMode.HOLD)),
                    PenShortcut("Select", PenSignals.motion(selected).toList(), PenBinding("pan", PenBindingMode.SELECT)),
                )
                canvas.penShortcuts = { bindings }
            } finally { held.recycle(); selected.recycle() }
        }
        show(host, editor, homes, look)
        host.await("input host attached") { canvas.view.isAttachedToWindow && canvas.view.width > 0 }
        try {
            host.onMain {
                deliver(canvas, MotionEvent.ACTION_HOVER_MOVE, 128)
                assertEquals(Tool.ERASER, canvas.tool)
                assertEquals(Tool.PEN, canvas.toolbarTool)
            }
            host.change { canvas.layersSidebarVisible = true; look.value = look.value.copy(position = ToolbarPosition.RIGHT) }
            host.onMain { assertEquals(Tool.ERASER, canvas.tool); assertEquals(Tool.PEN, canvas.toolbarTool) }
            host.change { canvas.layersSidebarVisible = false; look.value = look.value.copy(position = ToolbarPosition.TOP) }
            host.onMain {
                assertEquals(Tool.ERASER, canvas.tool)
                deliver(canvas, MotionEvent.ACTION_HOVER_MOVE, 0)
                assertEquals(Tool.PEN, canvas.tool)
                deliver(canvas, MotionEvent.ACTION_DOWN, 0, 100f)
                deliver(canvas, MotionEvent.ACTION_MOVE, 256, 140f)
                assertEquals(Tool.PEN, canvas.tool) // SELECT remains deferred until normal UP.
                deliver(canvas, MotionEvent.ACTION_UP, 256, 180f)
                assertEquals(Tool.PAN, canvas.tool)
                assertEquals(Tool.PAN, canvas.toolbarTool)
                assertTrue(canvas.document.items.isNotEmpty())
            }
            val stroke = host.onMain { canvas.document.items.last() }
            host.change { look.value = look.value.copy(position = ToolbarPosition.LEFT, floating = true) }
            host.onMain {
                assertTrue(canvas.document.items.any { it === stroke })
                deliver(canvas, MotionEvent.ACTION_HOVER_MOVE, 256)
                canvas.armTool(Tool.PEN) // Explicit selection supersedes an already observed SELECT.
            }
            host.change { look.value = look.value.copy(position = ToolbarPosition.BOTTOM, floating = false) }
            host.onMain {
                deliver(canvas, MotionEvent.ACTION_HOVER_MOVE, 256)
                assertEquals(Tool.PEN, canvas.tool)
                deliver(canvas, MotionEvent.ACTION_HOVER_MOVE, 0)
                deliver(canvas, MotionEvent.ACTION_DOWN, 0, 200f)
                deliver(canvas, MotionEvent.ACTION_MOVE, 256, 240f)
                deliver(canvas, MotionEvent.ACTION_CANCEL, 256, 260f)
                assertEquals(Tool.PEN, canvas.tool)
                assertEquals(Tool.PEN, canvas.toolbarTool)
                assertTrue(canvas.document.items.any { it === stroke })
            }
        } finally { host.disposeContent() }
    }

    @Test fun pagedHomeRoundTripStillReplacesWetViewAndPreservesDocument() = withWorkspace { host, workspace ->
        val editor = host.onMain { workspace.editor().also { it.pad.frontBuffering = false } }
        val homes = mutableStateOf(emptySet<Pane>())
        val look = mutableStateOf(ToolbarLook())
        show(host, editor, homes, look)
        host.await("paged host") { editor.surfaces.isAttachedToWindow }
        val oldPad = host.onMain { editor.pad }
        val document = editor.state.document
        val normalCanvas = editor.view
        try {
            host.change { homes.value = setOf(Pane.PRIMARY) }
            host.onMain { assertFalse(editor.surfaces.isAttachedToWindow) }
            host.change { homes.value = emptySet() }
            host.await("paged host reattached") { editor.surfaces.isAttachedToWindow }
            host.onMain {
                assertNotSame(oldPad, editor.pad)
                assertNull(oldPad.onSurfaceLost)
                assertFalse(editor.pad.frontBuffering)
                assertSame(document, editor.state.document)
                assertSame(normalCanvas, editor.view)
            }
        } finally { host.disposeContent() }
    }

    @Test fun hidingWholeCanvasHostReplacesNativeViewsAndPreservesEditingState() = withWorkspace { host, workspace ->
        val editor = host.onMain { workspace.editor(canvas = true) }
        val canvas = editor.infinite
        val homes = mutableStateOf(emptySet<Pane>())
        val look = mutableStateOf(ToolbarLook())
        val shown = mutableStateOf(true)
        host.onMain { prepareWindowInk(editor) }
        show(host, editor, homes, look, shown)
        host.await("canvas window attached") { canvas.view.isAttachedToWindow && canvas.view.width > 0 }
        windowStroke(host, editor, editor, null)
        val oldView = canvas.view
        val oldPad = canvas.pad
        val document = canvas.document
        val viewport = canvas.viewport
        val stroke = host.onMain { document.items.single() }
        host.change { shown.value = false }
        host.onMain { assertFalse(oldView.isAttachedToWindow); assertFalse(oldPad.isAttachedToWindow) }
        host.change { shown.value = true }
        host.await("replacement canvas window attached") { canvas.view !== oldView && canvas.view.isAttachedToWindow }
        host.onMain {
            assertNotSame(oldView, canvas.view)
            assertNotSame(oldPad, canvas.pad)
            assertNotSame(oldView.renderDiagnostics, canvas.view.renderDiagnostics)
            assertSame(viewport, canvas.viewport)
            assertSame(document, canvas.document)
            assertSame(stroke, document.items.single())
            assertFalse(canvas.pad.frontBuffering)
            assertNull(oldPad.onSurfaceLost)
        }
        windowStroke(host, editor, editor, null)
    }

    private fun show(host: ComposeHostFixture, editor: Editor,
                     homes: androidx.compose.runtime.MutableState<Set<Pane>>,
                     look: androidx.compose.runtime.MutableState<ToolbarLook>,
                     shown: androidx.compose.runtime.State<Boolean>? = null) = host.setContent {
        XnotesTheme(editor.palette, editor.cornerStyle) {
            CompositionLocalProvider(LocalToolbarLook provides look.value) {
                if (shown?.value != false) SplitHost(editor, noActions(), homes.value, onDocumentFocus = {}) { Box(Modifier.fillMaxSize()) }
            }
        }
    }

    private fun assertRetained(host: ComposeHostFixture, canvas: InfiniteEditor, parent: android.view.ViewParent,
                               counter: AttachmentCounter, attachment: Long) = host.onMain {
        assertSame(parent, canvas.surfaces.parent)
        assertEquals(1, counter.attached)
        assertEquals(0, counter.detached)
        assertEquals(attachment, canvas.view.renderDiagnostics.snapshot().attachmentId)
    }

    private fun assertPagedRetained(host: ComposeHostFixture, editor: Editor, parent: android.view.ViewParent,
                                   counter: AttachmentCounter, pad: com.xnotes.gl.GlWetPad) = host.onMain {
        assertSame(parent, editor.surfaces.parent)
        assertSame(pad, editor.pad)
        assertEquals(1, counter.attached)
        assertEquals(0, counter.detached)
    }

    private fun screenX(view: View): Int = IntArray(2).also(view::getLocationOnScreen)[0]
    private fun screenY(view: View): Int = IntArray(2).also(view::getLocationOnScreen)[1]

    private fun prepareWindowInk(editor: Editor) {
        if (!editor.canvasOpen) {
            editor.pad.frontBuffering = false
            editor.selectTool(Tool.PEN)
            editor.state.zoomLocked = false
            return
        }
        editor.infinite.apply {
            pad.frontBuffering = false
            penShortcuts = { emptyList() }
            applyInputPrefs(false, null, "both", false)
            armTool(Tool.PEN)
            viewport.zoom = 1.35
            viewport.scrollX = -230.5
            viewport.scrollY = 117.25
        }
    }

    private data class WindowStrokePlan(
        val screenStart: Pt, val screenEnd: Pt,
        val contentStart: Pt, val contentEnd: Pt,
        val tolerance: Double, val before: Int, val otherBefore: Int?,
    )

    private fun windowStroke(host: ComposeHostFixture, workspace: Editor, target: Editor, other: Editor?) {
        val canvas = target.canvasOpen
        val view: View = if (canvas) target.infinite.view else target.view
        host.awaitStableWindowBounds(view)
        val originalInput = host.onMain { if (canvas) target.infinite.view.input else target.view.input }
        val received = mutableListOf<Triple<Int, Int, Int>>()
        val rawButtons = 0x20
        fun items(editor: Editor) = if (editor.canvasOpen) editor.infinite.document.items else editor.state.document.pages.first().items
        fun setInput(input: ((MotionEvent) -> Boolean)?) {
            if (canvas) target.infinite.view.input = input else target.view.input = input
        }
        val plan = host.onMain {
            val start: Pt
            val end: Pt
            val contentStart: Pt
            val contentEnd: Pt
            val zoom: Double
            if (canvas) {
                val viewport = target.infinite.viewport
                val clearWidth = view.width - viewport.insetLeft - viewport.insetRight
                val clearHeight = view.height - viewport.insetTop - viewport.insetBottom
                assertTrue("canvas has an unobscured input area", clearWidth > 20 && clearHeight > 20)
                start = Pt(viewport.insetLeft + clearWidth * .4, viewport.insetTop + clearHeight * .45)
                end = Pt(viewport.insetLeft + clearWidth * .6, viewport.insetTop + clearHeight * .55)
                contentStart = viewport.viewportToContent(start)
                contentEnd = viewport.viewportToContent(end)
                zoom = viewport.zoom
            } else {
                val state = target.state
                // Fit the actual page into this pane's current unobscured rectangle. Test points
                // are then independently inverted through content and page coordinates.
                state.fitPage()
                val page = state.document.pages.first()
                start = state.contentToViewport(state.fromPageSpace(0, Pt(page.width * .4, page.height * .45)))
                end = state.contentToViewport(state.fromPageSpace(0, Pt(page.width * .6, page.height * .55)))
                contentStart = state.toPageSpace(0, state.viewportToContent(start))
                contentEnd = state.toPageSpace(0, state.viewportToContent(end))
                zoom = state.zoom
                for (point in listOf(start, end)) {
                    assertTrue(point.x > state.insetLeft && point.x < view.width - state.insetRight)
                    assertTrue(point.y > state.insetTop && point.y < view.height - state.insetBottom)
                }
            }
            val location = IntArray(2).also(view::getLocationOnScreen)
            setInput { event ->
                if (event.actionMasked in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP))
                    received.add(Triple(event.actionMasked, event.getToolType(0), event.buttonState))
                originalInput?.invoke(event) ?: false
            }
            WindowStrokePlan(
                Pt(location[0] + start.x, location[1] + start.y),
                Pt(location[0] + end.x, location[1] + end.y),
                contentStart, contentEnd, 2.0 / zoom, items(target).size, other?.let { items(it).size },
            )
        }
        try {
            // Real screen coordinates go through the window, Compose hit testing and AndroidView.
            injectWindowStylus(plan.screenStart, plan.screenEnd, rawButtons)
            host.await("injected stroke committed to intended document") { items(target).size > plan.before }
            host.onMain {
                assertSame("pen input must focus its logical pane", target, workspace.active)
                assertEquals(plan.before + 1, items(target).size)
                if (other != null) assertEquals(plan.otherBefore!!.toInt(), items(other).size)
                val stroke = items(target).last() as Stroke
                assertEquals(Tool.PEN, stroke.tool)
                assertEquals(plan.contentStart.x, stroke.xAt(0), plan.tolerance)
                assertEquals(plan.contentStart.y, stroke.yAt(0), plan.tolerance)
                assertEquals(plan.contentEnd.x, stroke.xAt(stroke.sampleCount - 1), plan.tolerance)
                assertEquals(plan.contentEnd.y, stroke.yAt(stroke.sampleCount - 1), plan.tolerance)
                assertTrue("DOWN and UP passed through the real view",
                    received.any { it.first == MotionEvent.ACTION_DOWN } && received.any { it.first == MotionEvent.ACTION_UP })
                assertTrue("stylus type and raw button bits remain intact",
                    received.all { it.second == MotionEvent.TOOL_TYPE_STYLUS && it.third == rawButtons })
            }
        } finally { host.onMain { setInput(originalInput) } }
    }

    private var injectedDownTime: Long? = null

    private fun stylus(action: Int, buttons: Int, x: Float = 120f): MotionEvent {
        val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; y = 160f; pressure = .5f }
        val at = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) injectedDownTime = at
        val downTime = injectedDownTime ?: at
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) injectedDownTime = null
        return MotionEvent.obtain(downTime, at, action, 1, arrayOf(pointer), arrayOf(coords), 0, buttons,
            1f, 1f, 9999, 0, InputDevice.SOURCE_STYLUS, 0)
    }

    private fun deliver(canvas: InfiniteEditor, action: Int, buttons: Int, x: Float = 120f) {
        val event = stylus(action, buttons, x)
        try {
            if (action == MotionEvent.ACTION_HOVER_MOVE) canvas.view.dispatchGenericMotionEvent(event)
            else canvas.view.dispatchTouchEvent(event)
        } finally { event.recycle() }
    }

    /** Keep these window tests independent of the app's saved workspace and global settings. */
    private fun withWorkspace(test: (ComposeHostFixture, Workspace) -> Unit) = ComposeHostFixture.run { host ->
        val workspace = host.onMain { Workspace(host) }
        try { test(host, workspace) } finally {
            try { host.disposeContent() } finally { workspace.close() }
        }
    }

    private class Workspace(private val host: ComposeHostFixture) {
        private val root = java.nio.file.Files.createTempDirectory(host.activity.cacheDir.toPath(), "pane-layout-").toFile()
        private val context = object : android.view.ContextThemeWrapper(host.activity, com.xnotes.R.style.Theme_Xnotes) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): File = File(root, "files").also { it.mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").also { it.mkdirs() }
            override fun getNoBackupFilesDir(): File = File(root, "no-backup").also { it.mkdirs() }
        }
        private val globals = listOf(
            com.xnotes.settings.LiveSettings::class.java.getDeclaredField("value"),
            com.xnotes.storage.NotebookStorage::class.java.getDeclaredField("instance"),
            com.xnotes.storage.PdfResources::class.java.getDeclaredField("instance"),
        ).map { field -> field.isAccessible = true; field to field.get(null) }
        private val editors = mutableListOf<Editor>()
        init { globals.forEach { (field, _) -> field.set(null, null) } }

        fun editor(canvas: Boolean = false): Editor = Editor(context).also {
            editors.add(it)
            if (canvas) it.newCanvas() else it.newNote()
        }

        fun emptySecond(owner: Editor): Editor = owner.secondaryPane().also {
            if (it !in editors) editors.add(it)
            // No persisted workspace is under test. closePane still writes split JSON into root.
            Editor::class.java.getDeclaredField("sessionLoaded").apply { isAccessible = true }.setBoolean(it, false)
        }

        fun second(owner: Editor, pdf: Boolean = false): Editor = emptySecond(owner).also { editor ->
            editor.newNote()
            if (pdf) {
                val file = File(root, "source.pdf")
                val writer = android.graphics.pdf.PdfDocument()
                try {
                    val page = writer.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(600, 800, 1).create())
                    page.canvas.drawColor(android.graphics.Color.WHITE)
                    writer.finishPage(page)
                    file.outputStream().use(writer::writeTo)
                } finally { writer.close() }
                val doc = com.xnotes.core.model.Document(
                    pages = mutableListOf(com.xnotes.core.model.Page(600.0, 800.0, pdfPage = 0)),
                    pdfFile = file,
                )
                // Install an actual one-page PDF through the production binding method, without
                // involving a storage provider/registry, migration or an autosave destination.
                Editor::class.java.getDeclaredMethod("replaceDocument", com.xnotes.core.model.Document::class.java)
                    .apply { isAccessible = true }.invoke(editor, doc)
                assertTrue(editor.hasPdf)
            }
        }

        fun close() {
            var released = false
            try {
                val jobs = host.onMain {
                    editors.map { editor ->
                        val canvas = field(editor, "infiniteOrNull") as? InfiniteEditor
                        if (canvas != null && !canvas.view.isAttachedToWindow && !canvas.view.detachedFromWindow) {
                            // setRenderer starts a GLThread even before the first window attach.
                            // If setup failed before composition, give the existing View a real
                            // detach so cleanup does not depend on GLSurfaceView.finalize().
                            val surfaces = canvas.surfaces
                            (surfaces.parent as? android.view.ViewGroup)?.removeView(surfaces)
                            try { host.activity.host.addView(surfaces) } finally {
                                host.activity.host.removeView(surfaces)
                            }
                        }
                        val scope = field(editor, "autosaveScope") as kotlinx.coroutines.CoroutineScope
                        val job = requireNotNull(scope.coroutineContext[Job])
                        scope.cancel()
                        (field(editor, "pdfSource") as? com.xnotes.platform.PdfSource)?.close()
                        editor.state.invalidateAllCaches()
                        com.xnotes.platform.MathRendering.removeListener(
                            field(editor, "mathLanded") as com.xnotes.platform.MathRendering.Listener)
                        (field(editor, "thumbDispatcher") as kotlinx.coroutines.ExecutorCoroutineDispatcher).close()
                        job
                    }
                }
                runBlocking { withTimeout(10_000) { jobs.forEach { it.join() } } }
                val decoders = host.onMain {
                    editors.mapNotNull { field(it, "infiniteOrNull") as? InfiniteEditor }.map { canvas ->
                        (field(canvas, "decodeExecutor") as java.util.concurrent.ExecutorService).also { it.shutdown() }
                    }
                }
                decoders.forEach { assertTrue("canvas decoder terminates", it.awaitTermination(10, TimeUnit.SECONDS)) }
                val pdfClosed = CountDownLatch(1)
                com.xnotes.platform.PdfiumThread.shared.submit(com.xnotes.platform.PdfPriority.WARM) { pdfClosed.countDown() }
                assertTrue("PDF close completes before deleting fixture source", pdfClosed.await(10, TimeUnit.SECONDS))
                com.xnotes.pen.PenTestRunner.instance.waitForIdleSync()
                released = true
            } finally {
                host.onMain { globals.forEach { (field, value) -> field.set(null, value) } }
                // A timed-out worker may still use the files; preserve them in that failure case.
                if (released) root.deleteRecursively()
            }
        }

        private fun field(owner: Any, name: String): Any? =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    }

    private fun noActions() = PaneActions(
        onToggleFullscreen = {}, onMainMenu = {}, onSaveSplit = {}, onOpenBackstage = {},
        onClosePane = {}, onSplitDocument = {}, onPickDocument = {}, onInsertImage = { _, _ -> },
        onInsertCanvasImage = { _, _ -> }, onTakePhoto = {}, onAddStickers = {}, onInsertAudio = {},
        onRecordAudio = {}, onSaveAudio = { _, _ -> }, onShareAudio = { _, _ -> }, onShareImage = {},
        onOpenFile = { _, _ -> }, onImportTemplate = {}, onTemplateFromImage = {},
        onSharePages = { _, _, _ -> }, onSavePagesAsPdf = { _, _ -> }, onSavePagesAsImages = { _, _ -> },
        onInsertPdf = { _, _ -> },
    )
}
