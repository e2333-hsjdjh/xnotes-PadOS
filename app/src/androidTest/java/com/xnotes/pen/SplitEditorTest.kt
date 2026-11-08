package com.xnotes.pen

import android.view.ContextThemeWrapper
import com.xnotes.R
import com.xnotes.ui.Editor
import com.xnotes.ui.Pane
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import com.xnotes.format.DocumentCodec

/** Real editor models/controllers and codec, on Android's UI thread. No production data is used. */
class SplitEditorTest {
    @Test fun pagedHostReattachmentReplacesOnlyWetViewAndKeepsItsPreference() = onMain { editor ->
        val document = editor.state.document
        val page = document.pages.first()
        val stroke = com.xnotes.core.model.Stroke(com.xnotes.core.tools.Tool.PEN,
            com.xnotes.core.tools.ToolDefaults.configFor(com.xnotes.core.tools.Tool.PEN))
        stroke.addSample(com.xnotes.core.stroke.Sample(10.0, 20.0, 1.0, 0.0))
        page.items.add(stroke)
        val canvas = editor.view
        val container = editor.surfaces
        val old = editor.pad
        val oldFront = editor.controller.frontInk
        old.frontBuffering = false
        assertSame(container, editor.surfacesForAttach())
        assertSame(document, editor.state.document)
        assertSame(page, editor.state.document.pages.first())
        assertSame(stroke, page.items.last())
        assertSame(canvas, container.getChildAt(0))
        assertEquals(2, container.childCount)
        assertNotSame(old, editor.pad)
        assertNotSame(oldFront, editor.controller.frontInk)
        assertNull(old.parent)
        assertNull(old.onSurfaceLost)
        assertFalse(editor.pad.frontBuffering)
        val second = editor.pad
        second.frontBuffering = true
        editor.surfacesForAttach()
        assertNotSame(second, editor.pad)
        assertTrue(editor.pad.frontBuffering)
        assertSame(editor.pad, container.getChildAt(1))
    }

    @Test fun layerDeletionUndoRestoresObjectsAndClearsSelection() = onMain { editor ->
        val page = editor.state.document.pages.first()
        editor.editLayers(page) { page.layers.create("Draft") }
        val layerId = page.layers.activeId
        val stroke = com.xnotes.core.model.Stroke(com.xnotes.core.tools.Tool.PEN,
            com.xnotes.core.tools.ToolDefaults.configFor(com.xnotes.core.tools.Tool.PEN))
        stroke.addSample(com.xnotes.core.stroke.Sample(10.0, 20.0, 1.0))
        page.items.add(stroke)
        editor.controller.selectPlaced(0, listOf(stroke))
        assertTrue(editor.controller.hasSelection)
        assertTrue(editor.editLayers(page) {
            page.items.removeAll { it.layerId == layerId }
            page.layers.delete(layerId)
        })
        assertFalse(editor.controller.hasSelection)
        assertTrue(page.items.isEmpty())
        editor.undo()
        assertSame(stroke, page.items.single())
        assertEquals(layerId, page.layers.activeId)
        editor.redo()
        assertTrue(page.items.isEmpty())
    }

    @Test fun layersSidebarTogglesAndSelectionDoesNotConsumeUndo() = onMain { editor ->
        editor.sidebarVisible = false
        editor.toggleLayersSidebar()
        assertTrue(editor.sidebarVisible)
        assertEquals(com.xnotes.ui.SidePanelTab.LAYERS, editor.sidePanelTab)
        editor.toggleLayersSidebar()
        assertFalse(editor.sidebarVisible)
        val page = editor.state.document.pages.first()
        editor.editLayers(page) { page.layers.create("Second") }
        editor.selectLayer(page, com.xnotes.core.model.DrawingLayers.DEFAULT)
        editor.undo()
        assertEquals(1, page.layers.entries.size)
    }

    private fun onMain(test: (Editor) -> Unit) {
        val runner = PenTestRunner.instance
        var error: Throwable? = null
        runner.runOnMainSync {
            try {
                val editor = Editor(ContextThemeWrapper(runner.targetContext, R.style.Theme_Xnotes))
                editor.newNote()
                test(editor)
                editor.goHomeAll()
            } catch (t: Throwable) { error = t }
        }
        error?.let { throw it }
    }

    @Test fun freshSplitPositionIgnoresPreviousOrderForEitherSurvivingPane() = onMain { a ->
        val original = a.preferences
        try {
            for (position in listOf("left", "right", "top", "bottom")) {
                a.applyPreferences(original.copy(newSplitPosition = position))
                for (current in Pane.entries) {
                    for (previous in listOf(false, true)) {
                        a.splitReversed = previous
                        a.startFreshSplit(current)
                        val first = if (a.splitReversed) Pane.SECONDARY else Pane.PRIMARY
                        assertEquals(position == "right" || position == "bottom", first == current)
                        assertEquals(position == "left" || position == "right", a.splitSideBySide)
                        assertEquals(0.5f, a.splitRatio, 0.001f)
                    }
                }
            }
        } finally { a.applyPreferences(original) }
    }

    @Test fun sharedInsertSpaceRelayoutsBothViewsAndSurvivesUndoRedo() = onMain { a ->
        val b = a.secondaryPane()
        b.openSharedView(a)
        for (pane in listOf(a, b)) {
            pane.state.viewportW = 800; pane.state.viewportH = 1200
            pane.state.zoom = 0.5; pane.state.relayout()
        }
        val page = a.state.document.pages.first()
        page.style = com.xnotes.core.model.PageStyle(template = "lines")
        val originalHeight = page.height
        val originalRect = b.state.pageRects.first()
        var paints = 0
        val paint = b.state.paintPageBackground!!
        b.state.paintPageBackground = { p, r, scale, region -> paints++; paint(p, r, scale, region) }
        assertNotNull(b.state.backgroundFor(page))
        val beforePaints = paints
        a.selectTool(com.xnotes.core.tools.Tool.INSERT_SPACE)
        val start = a.state.contentToViewport(a.state.pageRects.first().topLeft + com.xnotes.core.geometry.Pt(100.0, 100.0))
        val now = android.os.SystemClock.uptimeMillis()
        for ((index, action) in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE, android.view.MotionEvent.ACTION_UP).withIndex()) {
            val event = android.view.MotionEvent.obtain(now, now + index * 20L, action, start.x.toFloat(), (start.y + if (index == 0) 0.0 else 100.0).toFloat(), 0)
            try { a.controller.onTouch(event) } finally { event.recycle() }
        }
        assertTrue(page.rooms.isNotEmpty())
        val grownHeight = page.height
        assertTrue(grownHeight > originalHeight)
        assertEquals(a.state.pageRects.first().h, b.state.pageRects.first().h, 0.001)
        assertTrue(b.state.pageRects.first().h > originalRect.h)
        b.state.backgroundFor(page)
        assertTrue("the other view must rebuild the resized page background", paints > beforePaints)
        b.undo()
        assertTrue(page.rooms.isEmpty())
        assertEquals(originalHeight, page.height, 0.001)
        assertEquals(originalRect.h, b.state.pageRects.first().h, 0.001)
        assertEquals(originalRect.h, a.state.pageRects.first().h, 0.001)
        a.redo()
        assertEquals(grownHeight, page.height, 0.001)
        assertEquals(a.state.pageRects.first().h, b.state.pageRects.first().h, 0.001)
    }

    @Test fun sharedViewsEditUndoSaveAndDetachWithoutLosingHistory() = onMain { a ->
        val original = a.pageCount
        val b = a.secondaryPane()
        b.openSharedView(a)
        assertSame(a.state.document, b.state.document)
        a.state.zoom = 1.25
        b.state.zoom = 2.5
        a.addPageAtEnd()
        b.addPageAtEnd()
        assertEquals(original + 2, a.pageCount)
        assertEquals(original + 2, b.pageCount)
        assertEquals(1.25, a.state.zoom, 0.001)
        assertEquals(2.5, b.state.zoom, 0.001)
        a.undo()
        assertEquals(original + 1, b.pageCount)
        b.undo()
        assertEquals(original, a.pageCount)
        b.redo()
        assertEquals(original + 1, a.pageCount)
        val out = ByteArrayOutputStream()
        b.save(out, "content://test/shared", "shared.xnote")
        assertEquals("content://test/shared", a.currentUri)
        assertEquals(original + 1, DocumentCodec(com.xnotes.platform.AndroidImageCodec(), com.xnotes.platform.AndroidTextMeasurer()).read(out.toByteArray().inputStream()).pages.size)
        b.goHome()
        assertTrue(a.noteOpen)
        assertFalse(b.noteOpen)
        a.undo()
        assertEquals(original, a.pageCount)
        b.newNote()
        b.addPageAtEnd()
        assertEquals(original, a.pageCount)
        assertNotSame(a.state.document, b.state.document)
    }

    @Test fun closingOwnerRetainsOtherViewportAndSharedEdits() = onMain { a ->
        val b = a.secondaryPane()
        b.openSharedView(a)
        b.addPageAtEnd()
        val pages = b.pageCount
        b.state.zoom = 2.0
        b.state.scrollY = 300.0
        a.goHome()
        assertTrue(a.noteOpen)
        assertFalse(b.noteOpen)
        assertEquals(pages, a.pageCount)
        assertNotNull(a.state.pendingInitialView)
        assertTrue(a.history.canUndo)
        a.undo()
        assertEquals(pages - 1, a.pageCount)
    }

    @Test fun replacingBorrowedViewWithCanvasLeavesOriginalEditable() = onMain { a ->
        val b = a.secondaryPane()
        b.openSharedView(a)
        a.addPageAtEnd()
        val pages = a.pageCount
        b.newCanvas()
        assertTrue(a.inSplit)
        assertTrue(b.canvasOpen)
        assertFalse(a.isSharedView)
        a.undo()
        assertEquals(pages - 1, a.pageCount)
        assertTrue(b.canvasOpen)
    }

    @Test fun secondaryOwnerCanSplitAndCloseAllWithoutLeavingHiddenDocument() = onMain { a ->
        a.goHome()
        val b = a.secondaryPane()
        b.newNote()
        a.openSharedView(b)
        b.addPageAtEnd()
        assertSame(b, a.documentOwner)
        a.focusPane(Pane.SECONDARY)
        a.goHomeAll()
        assertFalse(a.noteOpen)
        assertFalse(b.noteOpen)
    }
    @Test fun sharedWorkspaceRestoresBothPositionsEvenWithSecondaryOwner() {
        val runner = PenTestRunner.instance
        val context = ContextThemeWrapper(runner.targetContext, R.style.Theme_Xnotes)
        val sessionFile = java.io.File(context.filesDir, "session/document.xnote")
        val metaFile = java.io.File(context.filesDir, "session/session.json")
        var error: Throwable? = null
        runner.runOnMainSync {
            try {
                val a = Editor(context)
                kotlinx.coroutines.runBlocking { a.restoreSession() }
                a.newNote()
                a.goHome()
                val b = a.secondaryPane()
                b.newNote()
                a.openSharedView(b)
                repeat(2) { b.addPageAtEnd() }
                a.state.zoom = 1.3; a.state.scrollY = 321.0
                b.state.zoom = 2.1; b.state.scrollY = 987.0
                // Make this test's asynchronous session write distinguishable from an older run.
                a.renameCurrentDocument("split-recovery-test")
                a.persist()
            } catch (t: Throwable) { error = t }
        }
        error?.let { throw it }
        val deadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < deadline) {
            val saved = runCatching {
                val meta = org.json.JSONObject(metaFile.readText())
                sessionFile.length() > 0 && meta.optDouble("zoom") == 1.3
            }.getOrDefault(false)
            if (saved) break
            Thread.sleep(50)
        }
        runner.runOnMainSync {
            try {
                val restored = Editor(context)
                kotlinx.coroutines.runBlocking {
                    restored.restoreSession()
                    restored.restoreSplitWorkspace()
                }
                assertTrue(restored.inSplit)
                val other = restored.secondary!!
                assertSame(restored.state.document, other.state.document)
                val left = restored.state.pendingInitialView as com.xnotes.canvas.InitialView.Restore
                val right = other.state.pendingInitialView as com.xnotes.canvas.InitialView.Restore
                assertEquals(1.3, left.zoom, 0.001)
                assertEquals(321.0, left.scrollY, 0.001)
                assertEquals(2.1, right.zoom, 0.001)
                assertEquals(987.0, right.scrollY, 0.001)
                restored.goHomeAll()
            } catch (t: Throwable) { error = t }
        }
        error?.let { throw it }
    }

    @Test fun pdfSharedViewKeepsSourceAndSurvivesClosingBorrower() = onMain { a ->
        val context = PenTestRunner.instance.targetContext
        val pdfFile = java.io.File(context.cacheDir, "split-source.pdf")
        val pdf = android.graphics.pdf.PdfDocument()
        try {
            repeat(2) { index ->
                val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(400, 600, index + 1).create())
                page.canvas.drawText("PDF page ${index + 1}", 40f, 80f, android.graphics.Paint().apply { textSize = 24f })
                pdf.finishPage(page)
            }
            pdfFile.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        val original = pdfFile.readBytes()
        val codec = DocumentCodec(com.xnotes.platform.AndroidImageCodec(), com.xnotes.platform.AndroidTextMeasurer())
        val doc = com.xnotes.core.model.Document()
        doc.pdfFile = pdfFile
        repeat(2) { doc.pages.add(com.xnotes.core.model.Page(800.0, 1200.0, pdfPage = it)) }
        val note = java.io.File(context.cacheDir, "split-source.xnote")
        note.outputStream().use { codec.write(doc, it) }
        kotlinx.coroutines.runBlocking { a.openAsync(android.net.Uri.fromFile(note).toString(), "source.xnote") }
        assertTrue(a.hasPdf)
        val b = a.secondaryPane()
        b.openSharedView(a)
        assertTrue(b.hasPdf)
        val shownPage = b.state.document.pages.first()
        var backgroundPaints = 0
        val painter = b.state.paintPageBackground!!
        b.state.paintPageBackground = { page, renderer, res, region ->
            backgroundPaints++
            painter(page, renderer, res, region)
        }
        val background = b.state.backgroundFor(shownPage)
        val ink = b.state.cacheFor(shownPage)
        a.addPageAtEnd()
        assertSame("shared edit must retain PDF until refresh is ready", background, b.state.backgroundForOrSchedule(shownPage))
        assertSame("shared edit must retain previous ink until rebuild", ink, b.state.cacheForOrSchedule(shownPage))
        assertEquals("ink invalidation must not redraw PDF", 1, backgroundPaints)
        b.state.refreshAllInk()
        assertSame(background, b.state.backgroundFor(shownPage))
        assertEquals("pending ink must not invalidate PDF", 1, backgroundPaints)
        a.undo()
        b.addPageAtEnd()
        b.goHome()
        a.releaseClosedSecondary()
        assertNull(a.secondary)
        assertTrue(a.state.document.pdfFile!!.exists())
        val out = ByteArrayOutputStream()
        a.save(out, android.net.Uri.fromFile(note).toString(), "source.xnote")
        assertEquals(3, codec.read(out.toByteArray().inputStream(), context.cacheDir).pages.size)
        assertArrayEquals(original, pdfFile.readBytes())
    }

    @Test fun canvasWorkspaceUsesVisibleIdentityInsteadOfUnderlyingPdf() = onMain { a ->
        val b = a.secondaryPane()
        b.newNote()
        b.state.document.path = "content://test/underlying-pdf"
        b.newCanvas()
        assertNull(b.visibleDocumentUri)
        assertEquals("content://test/underlying-pdf", b.currentUri)
        a.persist()
        val metadata = org.json.JSONObject(java.io.File(PenTestRunner.instance.targetContext.filesDir,
            "split-workspace.json").readText())
        assertEquals("", metadata.getString("secondPath"))
        assertFalse(metadata.getBoolean("shared"))
    }

    @Test fun palmInOtherPaneCannotStealFocusDuringPenContact() = onMain { a ->
        val b = a.secondaryPane()
        b.openSharedView(a)
        fun event(action: Int, stylus: Boolean): android.view.MotionEvent {
            val properties = android.view.MotionEvent.PointerProperties().apply {
                id = 0
                toolType = if (stylus) android.view.MotionEvent.TOOL_TYPE_STYLUS else android.view.MotionEvent.TOOL_TYPE_FINGER
            }
            val coords = android.view.MotionEvent.PointerCoords().apply { x = 100f; y = 100f; pressure = 0.5f }
            return android.view.MotionEvent.obtain(1, 2, action, 1, arrayOf(properties), arrayOf(coords),
                0, 0, 1f, 1f, 19, 0, if (stylus) android.view.InputDevice.SOURCE_STYLUS else android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
        event(android.view.MotionEvent.ACTION_DOWN, true).let { e -> try { a.view.onTouchEvent(e) } finally { e.recycle() } }
        assertTrue(a.stylusInContact)
        a.focusPane(Pane.SECONDARY)
        assertEquals(Pane.PRIMARY, a.focusedPane)
        assertTrue(a.stylusInContact)
        a.closePane(b)
        assertTrue("closing sibling must not interrupt an active pen", b.noteOpen)
        event(android.view.MotionEvent.ACTION_DOWN, false).let { e -> try { b.view.onTouchEvent(e) } finally { e.recycle() } }
        assertFalse(b.stylusInContact)
        event(android.view.MotionEvent.ACTION_CANCEL, true).let { e -> try { a.view.onTouchEvent(e) } finally { e.recycle() } }
        assertFalse(a.stylusInContact)
        a.focusPane(Pane.SECONDARY)
        assertEquals(Pane.SECONDARY, a.focusedPane)
    }

    @Test fun blockedPalmCannotReachTwoFingerUndoEvenAfterPenLift() = onMain { a ->
        val b = a.secondaryPane()
        b.openSharedView(a)
        var taps = 0
        b.view.onTwoFingerTap = { taps++ }
        var penDown = true
        b.view.crossPaneTouchGate.shouldBlock = { penDown }
        fun finger(action: Int, count: Int) {
            val properties = Array(count) { i -> android.view.MotionEvent.PointerProperties().apply {
                id = i; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
            } }
            val coords = Array(count) { i -> android.view.MotionEvent.PointerCoords().apply {
                x = 100f + i * 50; y = 200f; pressure = 0.5f
            } }
            val e = android.view.MotionEvent.obtain(1, 20, action, count, properties, coords,
                0, 0, 1f, 1f, 19, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { b.view.onTouchEvent(e) } finally { e.recycle() }
        }
        finger(android.view.MotionEvent.ACTION_DOWN, 1)
        finger(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 2)
        penDown = false
        finger(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl 8), 2)
        finger(android.view.MotionEvent.ACTION_UP, 1)
        assertEquals(0, taps)
        // A fresh gesture after the pen lifts is accepted normally.
        finger(android.view.MotionEvent.ACTION_DOWN, 1)
        finger(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 2)
        finger(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl 8), 2)
        finger(android.view.MotionEvent.ACTION_UP, 1)
        assertEquals(1, taps)
    }

    @Test fun closingOneDifferentDocumentPreservesTheOtherAndItsEdits() = onMain { a ->
        val b = a.secondaryPane()
        b.newNote()
        b.addPageAtEnd()
        val kept = b.state.document
        val count = b.pageCount
        a.closePane(a)
        assertFalse(a.noteOpen)
        assertTrue(b.noteOpen)
        assertSame(kept, b.state.document)
        assertEquals(count, b.pageCount)
        a.newNote()
        assertTrue(a.inSplit)
        assertSame(kept, b.state.document)
        a.closePane(b)
        assertTrue(a.noteOpen)
        assertFalse(b.noteOpen)
    }

    @Test fun sharedToolsFollowFocusBetweenPagedNoteAndCanvas() = onMain { a ->
        val pen = com.xnotes.core.tools.Tool.PEN
        val eraser = com.xnotes.core.tools.Tool.ERASER
        val b = a.secondaryPane()
        b.newCanvas()
        a.updateToolConfig(pen, a.toolConfig(pen).copy(baseWidth = 8.5, pressureEnabled = false))
        a.selectTool(eraser)
        a.focusPane(Pane.SECONDARY)
        assertEquals(eraser, b.infinite.tool)
        assertEquals(8.5, b.infinite.toolConfig(pen).baseWidth, 0.001)
        assertFalse(b.infinite.toolConfig(pen).pressureEnabled)
        b.infinite.updateToolConfig(pen, b.infinite.toolConfig(pen).copy(baseWidth = 12.0))
        b.infinite.armTool(pen)
        a.focusPane(Pane.PRIMARY)
        assertEquals(pen, a.tool)
        assertEquals(12.0, a.toolConfig(pen).baseWidth, 0.001)
        assertFalse(a.toolConfig(pen).pressureEnabled)
    }

    @Test fun replacingCanvasWithPagedFileKeepsOtherDocumentOpen() = onMain { a ->
        val b = a.secondaryPane()
        b.newCanvas()
        val note = java.io.File(PenTestRunner.instance.targetContext.cacheDir, "replace-canvas.xnote")
        val codec = DocumentCodec(com.xnotes.platform.AndroidImageCodec(), com.xnotes.platform.AndroidTextMeasurer())
        note.outputStream().use { codec.write(a.state.document, it) }
        kotlinx.coroutines.runBlocking { b.openAsync(android.net.Uri.fromFile(note).toString(), "replacement.xnote") }
        assertTrue(a.noteOpen)
        assertTrue(b.noteOpen)
        assertFalse(b.canvasOpen)
        assertEquals(android.net.Uri.fromFile(note).toString(), b.visibleDocumentUri)
    }

    @Test fun failedCanvasSaveKeepsCanvasOpenOnReplacement() = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val context = PenTestRunner.instance.targetContext
            val a = Editor(ContextThemeWrapper(context, R.style.Theme_Xnotes))
            a.newCanvas()
            val original = a.infinite.document
            original.dirty = true
            val binding = Editor::class.java.getDeclaredField("canvasAutosaveUri").apply { isAccessible = true }
            binding.set(a, "content://invalid.split.test/missing")
            val note = java.io.File(context.cacheDir, "save-failure-replacement.xnote")
            val codec = DocumentCodec(com.xnotes.platform.AndroidImageCodec(), com.xnotes.platform.AndroidTextMeasurer())
            note.outputStream().use { codec.write(com.xnotes.core.model.Document(), it) }
            a.openAsync(android.net.Uri.fromFile(note).toString(), "replacement.xnote")
            assertTrue(a.canvasOpen)
            assertSame(original, a.infinite.document)
            assertTrue(original.dirty)
            binding.set(a, null)
            assertFalse("unbound dirty canvas must remain recoverable", a.saveCanvasBeforeReplacement())
            assertTrue(a.canvasOpen)
            a.goHomeAll()
        }
    }

    @Test fun fourFingerTapAllowsStaggeredLiftButRejectsDragPenAndCancel() {
        fun sequence(drag: Boolean = false, allowed: Boolean = true, cancel: Boolean = false, earlyDrag: Boolean = false): FourFingerTap.Result {
            val detector = FourFingerTap(40f)
            var clock = 10L
            fun send(action: Int, ids: List<Int>, moved: Boolean = false): FourFingerTap.Result {
                val properties = ids.map { id -> android.view.MotionEvent.PointerProperties().apply {
                    this.id = id; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
                } }.toTypedArray()
                val coords = ids.map { id -> android.view.MotionEvent.PointerCoords().apply {
                    x = id * 200f + if (moved) 80f else 0f; y = 200f; pressure = 1f
                } }.toTypedArray()
                clock += 25
                val event = android.view.MotionEvent.obtain(10, clock, action, ids.size, properties, coords,
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                return try { detector.track(event, allowed) } finally { event.recycle() }
            }
            send(android.view.MotionEvent.ACTION_DOWN, listOf(0))
            if (earlyDrag) send(android.view.MotionEvent.ACTION_MOVE, listOf(0), moved = true)
            for (count in 2..4) send(android.view.MotionEvent.ACTION_POINTER_DOWN or ((count - 1) shl 8), (0 until count).toList())
            if (cancel) return send(android.view.MotionEvent.ACTION_CANCEL, listOf(0, 1, 2, 3))
            send(android.view.MotionEvent.ACTION_MOVE, listOf(0, 1, 2, 3), drag)
            for (count in 4 downTo 2) {
                send(android.view.MotionEvent.ACTION_POINTER_UP or ((count - 1) shl 8), (0 until count).toList())
                send(android.view.MotionEvent.ACTION_MOVE, (0 until count - 1).toList())
            }
            return send(android.view.MotionEvent.ACTION_UP, listOf(0))
        }
        assertEquals(FourFingerTap.Result.TRIGGER, sequence())
        assertEquals(FourFingerTap.Result.CONSUME, sequence(drag = true))
        assertEquals(FourFingerTap.Result.CONSUME, sequence(earlyDrag = true))
        assertEquals(FourFingerTap.Result.PASS, sequence(allowed = false))
        assertEquals(FourFingerTap.Result.CONSUME, sequence(cancel = true))
    }

    @Test fun fileFromAnotherLocationKeepsItsOwnSaveBinding() = onMain { a ->
        val context = PenTestRunner.instance.targetContext
        val root = com.xnotes.platform.AppStorageDocumentsProvider.treeUri(context)
        val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(root, "root")
        val uri = android.provider.DocumentsContract.createDocument(context.contentResolver, parent,
            "application/octet-stream", "location-save-test.xnote")!!
        try {
            val codec = DocumentCodec(com.xnotes.platform.AndroidImageCodec(), com.xnotes.platform.AndroidTextMeasurer())
            context.contentResolver.openOutputStream(uri)!!.use { codec.write(a.state.document, it) }
            val previousRoot = a.browseRoot
            a.updateBrowseRoot("content://different.location/tree/other")
            kotlinx.coroutines.runBlocking { a.openAsync(uri.toString(), "location-save-test.xnote") }
            assertEquals(uri.toString(), a.autosaveUri)
            assertEquals("content://different.location/tree/other", a.browseRoot)
            a.updateBrowseRoot(previousRoot ?: root.toString())
            a.goHomeAll()
        } finally { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
    }

    @Test fun eraserHoverCrossesPanesWithoutTouchOrEditing() = onMain { a ->
        val b = a.secondaryPane()
        b.openSharedView(a)
        a.selectTool(com.xnotes.core.tools.Tool.ERASER)
        val config = a.toolConfig(com.xnotes.core.tools.Tool.ERASER).copy(baseWidth = 27.0)
        a.controller.setToolConfig(com.xnotes.core.tools.Tool.ERASER, config)
        fun hover(view: android.view.View, action: Int, x: Float) {
            val property = android.view.MotionEvent.PointerProperties().apply {
                id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_STYLUS
            }
            val coords = android.view.MotionEvent.PointerCoords().apply { this.x = x; y = 80f }
            val event = android.view.MotionEvent.obtain(1, 2, action, 1, arrayOf(property), arrayOf(coords),
                0, 0, 1f, 1f, 19, 0, android.view.InputDevice.SOURCE_STYLUS, 0)
            try { view.onHoverEvent(event) } finally { event.recycle() }
        }
        fun cursor(editor: Editor): Any? = editor.controller.javaClass.getDeclaredField("eraserCursor")
            .apply { isAccessible = true }.get(editor.controller)
        hover(a.view, android.view.MotionEvent.ACTION_HOVER_ENTER, 100f)
        assertNotNull(cursor(a))
        // Deliberately omit EXIT: the new owner must also clear the previous cursor.
        hover(b.view, android.view.MotionEvent.ACTION_HOVER_ENTER, 5f)
        assertSame(b, a.active)
        assertEquals(com.xnotes.core.tools.Tool.ERASER, b.tool)
        assertEquals(27.0, b.toolConfig(com.xnotes.core.tools.Tool.ERASER).baseWidth, 0.001)
        assertNull(cursor(a))
        assertEquals(com.xnotes.core.geometry.Pt(5.0, 80.0), cursor(b))
        hover(b.view, android.view.MotionEvent.ACTION_HOVER_MOVE, 150f)
        assertEquals(com.xnotes.core.geometry.Pt(150.0, 80.0), cursor(b))
        hover(b.view, android.view.MotionEvent.ACTION_HOVER_EXIT, 151f)
        assertNull(cursor(b))
        a.focusPane(Pane.PRIMARY)
        b.newCanvas()
        hover(b.infinite.view, android.view.MotionEvent.ACTION_HOVER_ENTER, 6f)
        assertSame(b, a.active)
        assertEquals(com.xnotes.core.tools.Tool.ERASER, b.infinite.tool)
        val canvasCursor = b.infinite.view.javaClass.getDeclaredField("cursorAt").apply { isAccessible = true }
        assertEquals(com.xnotes.core.geometry.Pt(6.0, 80.0), canvasCursor.get(b.infinite.view))
        hover(b.infinite.view, android.view.MotionEvent.ACTION_HOVER_EXIT, 7f)
        assertNull(canvasCursor.get(b.infinite.view))
        assertFalse(b.infinite.document.dirty)
        assertFalse(a.history.canUndo)
    }

    @Test fun heldShortcutSurvivesCrossingAndReturningThenReleases() = onMain { a ->
        val oldBindings = a.preferences.penShortcuts
        val b = a.secondaryPane()
        b.openSharedView(a)
        a.setPenShortcuts(listOf(PenShortcut("Button", listOf(PenTrigger(PenTrigger.Kind.MOTION, 0x20, "", android.view.InputDevice.SOURCE_STYLUS)), PenBinding("eraser", PenBindingMode.HOLD))))
        a.selectTool(com.xnotes.core.tools.Tool.PEN)
        fun hover(editor: Editor, action: Int, mask: Int) {
            val property = android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_STYLUS }
            val coords = android.view.MotionEvent.PointerCoords().apply { x = 10f; y = 80f }
            val event = android.view.MotionEvent.obtain(1, 2, action, 1, arrayOf(property), arrayOf(coords),
                0, mask, 1f, 1f, 19, 0, android.view.InputDevice.SOURCE_STYLUS, 0)
            try { editor.view.onHoverEvent(event) } finally { event.recycle() }
        }
        try {
            hover(a, android.view.MotionEvent.ACTION_HOVER_ENTER, 0x20)
            assertEquals(com.xnotes.core.tools.Tool.ERASER, a.tool)
            hover(a, android.view.MotionEvent.ACTION_HOVER_EXIT, 0)
            hover(b, android.view.MotionEvent.ACTION_HOVER_ENTER, 0x20)
            assertEquals(com.xnotes.core.tools.Tool.ERASER, b.tool)
            hover(b, android.view.MotionEvent.ACTION_HOVER_EXIT, 0)
            hover(a, android.view.MotionEvent.ACTION_HOVER_ENTER, 0x20)
            assertEquals(com.xnotes.core.tools.Tool.ERASER, a.tool)
            hover(a, android.view.MotionEvent.ACTION_HOVER_MOVE, 0)
            assertEquals(com.xnotes.core.tools.Tool.PEN, a.tool)
            assertFalse(a.history.canUndo)
        } finally { a.setPenShortcuts(oldBindings) }
    }

    @Test fun titleFocusDoesNotReviveOverriddenMotionHold() = onMain { a ->
        val old = a.preferences.penShortcuts
        val b = a.secondaryPane()
        b.openSharedView(a)
        try {
            a.setPenShortcuts(listOf(PenShortcut("Button", listOf(PenTrigger(PenTrigger.Kind.MOTION, 32, "", android.view.InputDevice.SOURCE_STYLUS)), PenBinding("eraser"))))
            a.selectTool(com.xnotes.core.tools.Tool.PEN)
            recordedHover(a, 32)
            assertEquals(com.xnotes.core.tools.Tool.ERASER, a.tool)
            a.selectTool(com.xnotes.core.tools.Tool.PEN)
            a.focusPane(Pane.SECONDARY) // title/finger focus occurs before the first hover in B
            recordedHover(b, 32)
            assertEquals(com.xnotes.core.tools.Tool.PEN, b.tool)
        } finally { a.setPenShortcuts(old) }
    }

    @Test fun closingSharedOwnerKeepsHeldKeyOnTheActualSurvivingEditor() = onMain { a ->
        val old = a.preferences.penShortcuts
        val b = a.secondaryPane()
        b.openSharedView(a)
        val down = android.view.KeyEvent(1, 2, android.view.KeyEvent.ACTION_DOWN, 600, 0, 0, 9999, 42, 0, android.view.InputDevice.SOURCE_KEYBOARD)
        val up = android.view.KeyEvent(1, 3, android.view.KeyEvent.ACTION_UP, 600, 0, 0, 9999, 42, 0, android.view.InputDevice.SOURCE_KEYBOARD)
        try {
            a.setPenShortcuts(listOf(PenShortcut("Button", listOf(PenSignals.key(down)!!), PenBinding("eraser"))))
            a.selectTool(com.xnotes.core.tools.Tool.PEN)
            a.onRecordedPenKey(down)
            a.closePane(a)
            assertTrue(a.noteOpen) // shared owner retains the remaining view
            assertFalse(b.noteOpen)
            assertEquals(com.xnotes.core.tools.Tool.ERASER, a.tool)
            a.onRecordedPenKey(up)
            assertEquals(com.xnotes.core.tools.Tool.PEN, a.tool)
        } finally { a.setPenShortcuts(old) }
    }

    private fun recordedHover(editor: Editor, mask: Int) {
        val p = android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_STYLUS }
        val c = android.view.MotionEvent.PointerCoords().apply { x = 10f; y = 80f }
        val e = android.view.MotionEvent.obtain(1, 2, android.view.MotionEvent.ACTION_HOVER_MOVE, 1, arrayOf(p), arrayOf(c),
            0, mask, 1f, 1f, 9999, 0, android.view.InputDevice.SOURCE_STYLUS, 0)
        try { editor.view.onHoverEvent(e) } finally { e.recycle() }
    }

    @Test fun savedSplitReopensFilesOrderRatioAndBothViewports() = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val context = PenTestRunner.instance.targetContext
            val store = java.io.File(context.filesDir, "saved-split.json")
            val old = store.takeIf { it.exists() }?.readBytes()
            val root = com.xnotes.platform.AppStorageDocumentsProvider.treeUri(context)
            val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(root, "root")
            val files = mutableListOf<android.net.Uri>()
            val savedFiles = mutableListOf<java.io.File>()
            val a = Editor(ContextThemeWrapper(context, R.style.Theme_Xnotes))
            try {
                val codec = DocumentCodec(com.xnotes.platform.AndroidImageCodec(), com.xnotes.platform.AndroidTextMeasurer())
                repeat(2) { index ->
                    val uri = android.provider.DocumentsContract.createDocument(context.contentResolver, parent,
                        "application/octet-stream", "saved-split-test-$index.xnote")!!
                    files.add(uri)
                    // Canvases no longer join splits; savedSplitWithCanvasIsRefusedWithoutDetaching covers them.
                    // Each file needs its own document identity: two files claiming one id are refused
                    // as duplicates, which would leave the second pane closed and the split unsaved.
                    val doc = if (index == 0) a.state.document else com.xnotes.core.model.Document.blank()
                    context.contentResolver.openOutputStream(uri)!!.use { codec.write(doc, it) }
                }
                a.openAsync(files[0].toString())
                val b = a.secondaryPane()
                b.openAsync(files[1].toString())
                a.state.zoom = 1.25; a.state.scrollY = 140.0
                b.state.zoom = 2.0; b.state.scrollY = 450.0
                a.splitRatio = 0.38f; a.splitReversed = true; a.splitSideBySide = false
                val before = a.storedSplitFiles().toSet()
                assertTrue(a.saveCurrentSplit())
                val file = a.storedSplitFiles().single { it !in before }
                savedFiles.add(file)
                assertEquals(a.splitFileName(), file.name.substringBeforeLast(".xsplit") .replace(Regex("_\\d+$"), "") + ".xsplit")
                val bytes = file.readBytes()
                assertTrue(file.readText().contains("xnotes-split"))
                val external = java.io.File(context.cacheDir, "external-test.xsplit")
                try {
                    external.writeBytes(bytes)
                    assertFalse("external split files are not opened", a.openSplitFile(android.net.Uri.fromFile(external).toString()))
                    assertTrue(a.inSplit)
                } finally { external.delete() }
                a.message = null
                a.splitReversed = false
                a.state.zoom = 2.0
                assertTrue(a.saveCurrentSplit())
                assertNull("duplicate save must be silent", a.message)
                savedFiles.addAll(a.storedSplitFiles().filter { it !in before && it != file })
                assertEquals(1, savedFiles.size)
                assertTrue("saving again preserves the existing arrangement", bytes.contentEquals(file.readBytes()))
                val splitUri = android.net.Uri.fromFile(file).toString()
                assertTrue("replace an already open split without a late close callback", a.openSplitFile(splitUri))
                assertTrue(a.inSplit)
                val restored = Editor(ContextThemeWrapper(context, R.style.Theme_Xnotes))
                restored.newNote(); restored.addPageAtEnd()
                val unsaved = restored.state.document
                assertFalse("default must protect an unsaved note", restored.openSplitFile(splitUri))
                assertFalse("discarding another editor is not authorization", restored.openSplitFile(splitUri, setOf(a)))
                assertSame(unsaved, restored.state.document)
                assertTrue(restored.openSplitFile(splitUri, setOf(restored)))
                assertTrue(restored.inSplit)
                assertEquals(files[0].toString(), restored.currentUri)
                assertEquals(files[1].toString(), restored.secondary!!.visibleDocumentUri)
                assertFalse(restored.secondary!!.canvasOpen)
                assertTrue(restored.splitReversed)
                assertEquals(0.38f, restored.splitRatio, 0.001f)
                assertEquals(false, restored.splitSideBySide)
                assertNotNull(restored.state.pendingInitialView)
                val second = restored.secondary!!.state.pendingInitialView as com.xnotes.canvas.InitialView.Restore
                assertEquals(2.0, second.zoom, 0.001)
                assertEquals(450.0, second.scrollY, 0.001)
                val outside = Editor(ContextThemeWrapper(context, R.style.Theme_Xnotes))
                val externalNote = java.io.File(context.cacheDir, "discard-split-test.xnote")
                val originalBytes = context.contentResolver.openInputStream(files[0])!!.use { it.readBytes() }
                try {
                    externalNote.writeBytes(originalBytes)
                    outside.openAsync(android.net.Uri.fromFile(externalNote).toString())
                    assertNull(outside.autosaveUri)
                    outside.addPageAtEnd()
                    assertTrue(outside.dirty)
                    assertTrue(outside.openSplitFile(splitUri, setOf(outside)))
                    assertTrue("explicit Discard must not write existing files", originalBytes.contentEquals(externalNote.readBytes()))
                } finally { outside.goHomeAll(); externalNote.delete() }

                assertFalse(restored.openSingleFromHome("file:///missing-home-test.xnote", "missing.xnote"))
                assertTrue("failed selection keeps the parked split", restored.inSplit)
                assertTrue(restored.openSingleFromHome(files[0].toString(), "note.xnote"))
                assertFalse("main Home must open one document", restored.inSplit)
                assertNull(restored.secondary)
                assertEquals(files[0].toString(), restored.visibleDocumentUri)
                kotlinx.coroutines.delay(250)
                assertTrue("no late close callback closes the selected note", restored.noteOpen)
                assertTrue(restored.openSplitFile(splitUri))
                assertTrue("explicit split restore still works", restored.inSplit)
                assertTrue(restored.openSingleFromHome(files[1].toString(), "second.xnote"))
                assertEquals(files[1].toString(), restored.visibleDocumentUri)
                assertFalse(restored.inSplit)
                restored.goHomeAll()
                assertTrue(a.deleteSplitFile(splitUri))
                assertFalse(file.exists())
                assertFalse(a.deleteSplitFile(splitUri))
                assertFalse(a.deleteSplitFile(files[0].toString()))
                files.forEach { uri -> assertNotNull(context.contentResolver.openInputStream(uri)?.use { it.read() }) }
                assertTrue(a.saveCurrentSplit())
                savedFiles.addAll(a.storedSplitFiles().filter { it !in before })
                Unit
            } finally {
                a.goHomeAll()
                files.forEach { android.provider.DocumentsContract.deleteDocument(context.contentResolver, it) }
                savedFiles.forEach { it.delete() }
                if (old == null) store.delete() else store.writeBytes(old)
            }
        }
    }

    @Test fun restoredViewportAppliesEvenWhenReusedPaneSizeIsUnchanged() = onMain { a ->
        a.view.layout(0, 0, 800, 1200)
        a.state.pendingInitialView = com.xnotes.canvas.InitialView.Restore(1.8, 0.0, 220.0)
        a.state.didInitialFit = false
        a.view.forceLayout()
        a.view.measure(android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1200, android.view.View.MeasureSpec.EXACTLY))
        a.view.layout(0, 0, 800, 1200)
        assertTrue(a.state.didInitialFit)
        assertNull(a.state.pendingInitialView)
        assertEquals(1.8, a.state.zoom, 0.001)
    }

    @Test fun invalidSplitDoesNotCloseCurrentDocument() = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val context = PenTestRunner.instance.targetContext
            val a = Editor(ContextThemeWrapper(context, R.style.Theme_Xnotes))
            a.newNote(); a.addPageAtEnd()
            val original = a.state.document
            val file = java.io.File(java.io.File(context.filesDir, "splits").apply { mkdirs() }, "invalid-test.xsplit")
            try {
                file.writeText("""{"format":"xnotes-split","version":1,"primaryPath":"content://missing/first","secondPath":"content://missing/second"}""")
                assertFalse(a.openSplitFile(android.net.Uri.fromFile(file).toString()))
                assertTrue(a.noteOpen); assertSame(original, a.state.document)
                assertTrue(a.dirty)
            } finally { file.delete(); a.goHomeAll() }
        }
    }

    @Test fun savedSplitWithCanvasIsRefusedWithoutDetaching() = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val context = PenTestRunner.instance.targetContext
            val a = Editor(ContextThemeWrapper(context, R.style.Theme_Xnotes))
            a.newNote(); a.addPageAtEnd()
            val original = a.state.document
            val file = java.io.File(java.io.File(context.filesDir, "splits").apply { mkdirs() }, "canvas-split-test.xsplit")
            try {
                file.writeText("""{"format":"xnotes-split","version":2,"primaryPath":"content://missing/first","secondPath":"content://missing/second","secondCanvas":true}""")
                assertFalse(a.openSplitFile(android.net.Uri.fromFile(file).toString()))
                assertEquals(context.getString(R.string.split_canvas_saved_unsupported), a.message)
                assertTrue(a.noteOpen); assertSame(original, a.state.document)
                assertTrue(a.dirty)
                assertNull(a.secondary)
            } finally { file.delete(); a.goHomeAll() }
        }
    }

    @Test fun lastSessionSplitWithCanvasRestoresOnlyThePrimaryPane() = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val context = PenTestRunner.instance.targetContext
            val store = java.io.File(context.filesDir, "split-workspace.json")
            val old = store.takeIf { it.exists() }?.readBytes()
            try {
                for (prefix in listOf("primary", "second")) {
                    store.writeText("""{"format":"xnotes-split","version":2,"secondOpen":true,"primaryOpen":true,"${prefix}Canvas":true,"primaryPath":"content://missing/first","secondPath":"content://missing/second"}""")
                    val restored = Editor(ContextThemeWrapper(context, R.style.Theme_Xnotes))
                    restored.restoreSplitWorkspace()
                    assertNull("$prefix canvas: no second pane", restored.secondary)
                    assertFalse(restored.inSplit)
                    assertNull("$prefix canvas: no missing-file placeholder", restored.missingDocument)
                }
            } finally { if (old == null) store.delete() else store.writeBytes(old) }
        }
    }

    @Test fun savingSharedSplitCommitsBorrowerTextBeforeWriting() = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val context = PenTestRunner.instance.targetContext
            val root = com.xnotes.platform.AppStorageDocumentsProvider.treeUri(context)
            val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(root, "root")
            val uri = android.provider.DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", "borrower-text-test.xnote")!!
            val before = java.io.File(context.filesDir, "splits").listFiles()?.toSet() ?: emptySet()
            val a = Editor(ContextThemeWrapper(context, R.style.Theme_Xnotes))
            try {
                a.newNote()
                val codec = DocumentCodec(com.xnotes.platform.AndroidImageCodec(), com.xnotes.platform.AndroidTextMeasurer())
                context.contentResolver.openOutputStream(uri)!!.use { codec.write(a.state.document, it) }
                a.openAsync(uri.toString())
                val b = a.secondaryPane(); b.openSharedView(a)
                b.state.viewportW = 800; b.state.viewportH = 1200; b.state.relayout()
                b.selectTool(com.xnotes.core.tools.Tool.TEXT_BOX)
                val point = b.state.contentToViewport(b.state.pageRects.first().topLeft + com.xnotes.core.geometry.Pt(60.0, 60.0))
                val now = android.os.SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(now, now + 10, action, point.x.toFloat(), point.y.toFloat(), 0)
                    try { b.controller.onTouch(event) } finally { event.recycle() }
                }
                assertNotNull(b.editingField)
                b.updateEditingText("borrower pending text")
                assertTrue(a.saveCurrentSplit())
                assertNull(b.editingField)
                val read = context.contentResolver.openInputStream(uri)!!.use { codec.read(it) }
                assertTrue(read.pages.flatMap { it.items }.filterIsInstance<com.xnotes.core.model.TextItem>().any { it.text == "borrower pending text" })
            } finally {
                a.goHomeAll()
                android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
                a.storedSplitFiles().filter { it !in before }.forEach { it.delete() }
            }
        }
    }

}
