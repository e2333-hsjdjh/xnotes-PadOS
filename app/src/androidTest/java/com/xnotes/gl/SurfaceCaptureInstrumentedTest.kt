package com.xnotes.gl

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.widget.FrameLayout
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.Rgba
import com.xnotes.pen.PenTestRunner
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Uses PixelCopy on an actual on-screen GL Surface, never a bitmap drawn from the model. */
class SurfaceCaptureInstrumentedTest {
    @Test fun capturesPixelsFromTheCurrentGlBuffer() = RenderHostFixture.withHost { fixture ->
        val firstPaper = Rgba(64, 64, 64)
        val secondPaper = Rgba(208, 208, 208)
        val view = RenderHostFixture.onMain {
            InfiniteCanvasView(fixture.activity).apply {
                background = CanvasBackground(pattern = PagePattern.NONE)
                paperColor = firstPaper
            }
        }
        fixture.attach(view, 320, 240)
        fixture.awaitStableFrames(view)
        val firstFrame = capturePaper(view, firstPaper)

        RenderHostFixture.onMain { view.paperColor = secondPaper }
        fixture.awaitStableFrames(view)
        val secondFrame = capturePaper(view, secondPaper)
        assertTrue("The changed paper must be drawn in a newer frame", secondFrame > firstFrame)
    }

    @Test fun unmountedViewHasNoSurfaceToCapture() = RenderHostFixture.withHost { fixture ->
        val view = RenderHostFixture.onMain { InfiniteCanvasView(fixture.activity) }
        try {
            val result = capture(view)
            assertEquals(SurfaceCapture.Status.UNAVAILABLE, result.status)
            assertNull(result.bitmap)
            assertNull(result.platformStatus)
        } finally {
            // setRenderer starts a GL thread even before attachment. Give this unused view a
            // real detach so framework teardown can stop that thread as well.
            RenderHostFixture.onMain {
                fixture.activity.host.addView(view, FrameLayout.LayoutParams(320, 240))
                fixture.activity.host.removeView(view)
            }
        }
    }

    @Test fun replacementHasIndependentDiagnosticsAndCaptureWhileRetainingRendererGeneration() = RenderHostFixture.withHost { fixture ->
        val contexts = CopyOnWriteArrayList<Int>()
        val scene = object : GlScene {
            override fun onContextCreated(contextGen: Int) { contexts += contextGen }
            override fun drawContent(frame: FrameState) = Unit
        }
        val views = mutableListOf<InfiniteCanvasView>()
        try {
            val oldView = RenderHostFixture.onMain {
                InfiniteCanvasView(fixture.activity).also {
                    views += it
                    it.scene = scene
                    it.background = CanvasBackground(pattern = PagePattern.NONE)
                    it.paperColor = Rgba(64, 64, 64)
                }
            }
            fixture.attach(oldView, 320, 240)
            fixture.awaitStableFrames(oldView)
            val oldDiagnostics = oldView.renderDiagnostics
            val oldGeneration = RenderHostFixture.onMain { oldView.stats.contextGen }
            val first = capture(oldView)
            try {
                assertEquals(SurfaceCapture.Status.SUCCESS, first.status)
                assertEquals(oldDiagnostics.viewId, first.before.state.viewId)
                assertPaperPixel("old Surface", checkNotNull(first.bitmap).getPixel(160, 120), Rgba(64, 64, 64))
            } finally { first.bitmap?.recycle() }

            // Use the mainline replacement protocol: only a completed framework detach permits
            // a new View. A plain detach does not close this View's diagnostic capture service.
            RenderHostFixture.onMain {
                fixture.activity.host.removeView(oldView)
                assertTrue(oldView.detachedFromWindow)
                assertFalse(oldView.isAttachedToWindow)
            }
            val detached = capture(oldView)
            try {
                assertEquals(SurfaceCapture.Status.UNAVAILABLE, detached.status)
                assertEquals(oldDiagnostics.viewId, detached.before.state.viewId)
                assertNull(detached.bitmap)
            } finally { detached.bitmap?.recycle() }

            val replacement = RenderHostFixture.onMain {
                oldView.replacementForAttach().also {
                    views += it
                    assertNotSame(oldView, it)
                    assertNotSame(oldDiagnostics, it.renderDiagnostics)
                    assertNotEquals(oldDiagnostics.viewId, it.renderDiagnostics.viewId)
                    assertSame(oldView.viewport, it.viewport)
                    assertSame(scene, it.scene)
                    it.paperColor = Rgba(208, 208, 208)
                }
            }
            fixture.attach(replacement, 320, 240)
            fixture.awaitStableFrames(replacement)
            RenderHostFixture.onMain {
                assertTrue(replacement.stats.contextGen > oldGeneration)
                assertEquals(replacement.stats.contextGen, contexts.last())
                val snapshot = replacement.renderDiagnostics.snapshot()
                assertEquals(snapshot.viewId, checkNotNull(snapshot.latestFrame).target.context.viewId)
                assertFalse(oldDiagnostics.snapshot().attached)
            }

            // Retiring the native View closes only its capture service. Its caller must not be
            // silently redirected to the current editor View or consume the replacement's pixels.
            val retired = capture(oldView)
            try {
                assertEquals(SurfaceCapture.Status.CLOSED, retired.status)
                assertEquals(oldDiagnostics.viewId, retired.before.state.viewId)
                assertEquals(oldDiagnostics.viewId, retired.after.state.viewId)
                assertNull(retired.bitmap)
            } finally { retired.bitmap?.recycle() }
            val current = capture(replacement)
            try {
                assertEquals(SurfaceCapture.Status.SUCCESS, current.status)
                assertEquals(replacement.renderDiagnostics.viewId, current.before.state.viewId)
                assertEquals(replacement.renderDiagnostics.viewId, current.after.state.viewId)
                assertPaperPixel("replacement Surface", checkNotNull(current.bitmap).getPixel(160, 120), Rgba(208, 208, 208))
            } finally { current.bitmap?.recycle() }
        } finally {
            RenderHostFixture.onMain {
                for (view in views) {
                    if (view.isAttachedToWindow) fixture.activity.host.removeView(view)
                    else if (!view.detachedFromWindow) {
                        // setRenderer starts GLThread in the constructor. An assertion before
                        // attach must still lead through real framework teardown for this View.
                        try { fixture.activity.host.addView(view, FrameLayout.LayoutParams(320, 240)) }
                        finally { fixture.activity.host.removeView(view) }
                    }
                }
            }
        }
    }

    @Test fun retiredCaptureClosesLazilyAndThrowingPendingCallbackCannotInterruptReplacement() {
        for (initializeCapture in listOf(false, true)) RenderHostFixture.withHost { fixture ->
            val oldView = RenderHostFixture.onMain { InfiniteCanvasView(fixture.activity) }
            var replacement: InfiniteCanvasView? = null
            var destination: Bitmap? = null
            val callbacks = mutableListOf<SurfaceCapture.Status>()
            val expectedCallbacks = if (initializeCapture) listOf(SurfaceCapture.Status.CLOSED) else emptyList()
            try {
                fixture.attach(oldView, 320, 240)
                fixture.awaitStableFrames(oldView)
                RenderHostFixture.onMain {
                    if (initializeCapture) {
                        oldView.captureSurface { result ->
                            callbacks += result.status
                            result.bitmap?.recycle()
                            if (result.status == SurfaceCapture.Status.CLOSED) {
                                throw IllegalStateException("controlled diagnostic consumer failure")
                            }
                        }
                        // PixelCopy completion is delivered on Main. Keeping request/detach/replacement
                        // in this turn makes close deterministically precede that native completion.
                        assertTrue(callbacks.isEmpty())
                        val pending = checkNotNull(pendingCapture(oldView))
                        destination = pending.javaClass.getDeclaredField("bitmap").apply { isAccessible = true }
                            .get(pending) as Bitmap
                    }
                    fixture.activity.host.removeView(oldView)
                    replacement = oldView.replacementForAttach()
                    assertEquals(expectedCallbacks, callbacks)
                    destination?.let {
                        assertFalse("close cannot recycle a destination still owned by PixelCopy", it.isRecycled)
                    }
                }
                val current = checkNotNull(replacement)
                fixture.attach(current, 320, 240)
                fixture.awaitStableFrames(current)
                awaitNativeCaptureReturn(oldView)
                RenderHostFixture.onMain {
                    assertEquals("the throwing callback stays consumed", expectedCallbacks, callbacks)
                    destination?.let { assertTrue(it.isRecycled) }
                }
                // With initializeCapture=false this is the retired View's first ever request.
                val retired = capture(oldView)
                try {
                    assertEquals(SurfaceCapture.Status.CLOSED, retired.status)
                    assertEquals(oldView.renderDiagnostics.viewId, retired.before.state.viewId)
                    assertNull(retired.bitmap)
                } finally { retired.bitmap?.recycle() }
                val result = capture(current)
                try {
                    assertEquals(SurfaceCapture.Status.SUCCESS, result.status)
                    assertEquals(current.renderDiagnostics.viewId, result.before.state.viewId)
                } finally { result.bitmap?.recycle() }
            } finally {
                // A late native completion keeps its real bitmap/slot until it returns. Await it
                // without blocking Main; do not reset the process budget or recycle it from the test.
                try { awaitNativeCaptureReturn(oldView) } finally {
                    RenderHostFixture.onMain {
                        for (view in listOfNotNull(oldView, replacement)) {
                            if (view.isAttachedToWindow) fixture.activity.host.removeView(view)
                            else if (!view.detachedFromWindow) {
                                try { fixture.activity.host.addView(view, FrameLayout.LayoutParams(320, 240)) }
                                finally { fixture.activity.host.removeView(view) }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Read ownership only; the test never completes or cancels a platform copy itself. Main only. */
    private fun pendingCapture(view: InfiniteCanvasView): Any? {
        val capture = InfiniteCanvasView::class.java.getDeclaredField("diagnosticCapture")
            .apply { isAccessible = true }.get(view) as Lazy<*>
        if (!capture.isInitialized()) return null
        return SurfaceCapture::class.java.getDeclaredField("pending").apply { isAccessible = true }
            .get(capture.value)
    }

    private fun awaitNativeCaptureReturn(view: InfiniteCanvasView) {
        val handler = Handler(Looper.getMainLooper())
        val finished = CountDownLatch(1)
        val deadline = SystemClock.uptimeMillis() + 2_000L
        var returned = false
        val poll = object : Runnable {
            override fun run() {
                returned = pendingCapture(view) == null
                if (returned || SystemClock.uptimeMillis() >= deadline) finished.countDown()
                else handler.postDelayed(this, 16L)
            }
        }
        handler.post(poll)
        try {
            RenderHostFixture.await("native PixelCopy ownership return", finished)
            assertTrue("PixelCopy still owns the retired View's bitmap", returned)
        } finally { handler.removeCallbacks(poll) }
    }

    private data class ScreenSample(
        val atNanos: Long,
        val state: SurfaceCapture.State,
        val x: Int,
        val y: Int,
        val displayId: Int,
        val rotation: Int,
    ) {
        fun sameTarget(other: ScreenSample) = state == other.state && x == other.x && y == other.y &&
            displayId == other.displayId && rotation == other.rotation
    }

    private data class CapturePair(
        val before: ScreenSample,
        val surface: SurfaceCapture.Result,
        val screenBefore: ScreenSample,
        val screen: Bitmap?,
        val screenAfter: ScreenSample,
        val reasons: List<String>,
    ) : java.io.Closeable {
        val comparable: Boolean get() = reasons.isEmpty()
        override fun close() {
            surface.bitmap?.recycle()
            screen?.recycle()
        }
    }

    private fun capturePaper(view: InfiniteCanvasView, expected: Rgba): Long = capturePair(view).use { pair ->
        // This explicit instrumentation entry writes at most one bounded pair, replacing the
        // previous paper colour's pair. Production capture never calls this exporter.
        exportPair(pair)
        assertTrue("Capture pair is not comparable: ${pair.reasons}", pair.comparable)
        val image = checkNotNull(pair.surface.bitmap)
        val screen = checkNotNull(pair.screen)
        assertEquals(320, image.width)
        assertEquals(240, image.height)
        val frame = checkNotNull(pair.before.state.latestFrame)
        for ((x, y) in listOf(2 to 2, 317 to 2, 2 to 237, 317 to 237, 160 to 120)) {
            assertPaperPixel("Surface ($x,$y)", image.getPixel(x, y), expected)
            val screenX = pair.screenBefore.x + x
            val screenY = pair.screenBefore.y + y
            assertPaperPixel("screen ($screenX,$screenY)", screen.getPixel(screenX, screenY), expected)
        }
        frame.frameId
    }

    private fun assertPaperPixel(label: String, pixel: Int, expected: Rgba) {
        assertEquals("$label alpha", 255, Color.alpha(pixel))
        assertTrue("$label red: $pixel", kotlin.math.abs(Color.red(pixel) - expected.r) <= 2)
        assertTrue("$label green: $pixel", kotlin.math.abs(Color.green(pixel) - expected.g) <= 2)
        assertTrue("$label blue: $pixel", kotlin.math.abs(Color.blue(pixel) - expected.b) <= 2)
    }

    private fun screenSample(view: InfiniteCanvasView): ScreenSample = RenderHostFixture.onMain {
        val position = IntArray(2)
        view.getLocationOnScreen(position)
        ScreenSample(
            System.nanoTime(),
            SurfaceCapture.State.from(view.renderDiagnostics.snapshot(), view.holder.surface.isValid),
            position[0], position[1], view.display?.displayId ?: -1, view.display?.rotation ?: -1,
        )
    }

    private fun capturePair(view: InfiniteCanvasView): CapturePair {
        val before = screenSample(view)
        val surface = capture(view)
        var screen: Bitmap? = null
        try {
            val screenBefore = screenSample(view)
            // UiAutomation captures the composed display. Window PixelCopy would only copy the
            // window's own buffer and is deliberately not substituted here.
            screen = PenTestRunner.instance.uiAutomation.takeScreenshot()
            val screenAfter = screenSample(view)
            val reasons = mutableListOf<String>()
            if (surface.status != SurfaceCapture.Status.SUCCESS) reasons += "surface-${surface.status}"
            if (screen == null) reasons += "screen-unavailable"
            if (before.state != surface.before.state || before.state != surface.after.state ||
                !before.sameTarget(screenBefore) || !before.sameTarget(screenAfter)) {
                reasons += "observed-state-or-position-changed"
            }
            val state = before.state
            if (!state.available || state.latestFrame == null ||
                state.layoutSize != state.holderSize || state.layoutSize != state.rendererSize ||
                state.latestFrame?.frameSize != state.layoutSize) {
                reasons += "missing-frame-or-size-mismatch"
            }
            if (before.displayId != Display.DEFAULT_DISPLAY) reasons += "not-default-display"
            val size = state.layoutSize
            if (screen != null && (before.x < 0 || before.y < 0 ||
                    before.x.toLong() + size.width > screen.width ||
                    before.y.toLong() + size.height > screen.height)) {
                reasons += "view-outside-screen-capture"
            }
            return CapturePair(before, surface, screenBefore, screen, screenAfter, reasons)
        } catch (failure: Throwable) {
            surface.bitmap?.recycle()
            screen?.recycle()
            throw failure
        }
    }

    private fun exportPair(pair: CapturePair) {
        val directory = File(PenTestRunner.instance.targetContext.cacheDir, "render-diagnostics")
        check(directory.isDirectory || directory.mkdirs()) { "Could not create diagnostic directory" }
        val surfaceFile = File(directory, "surface.png")
        val screenFile = File(directory, "screen.png")
        val metadataFile = File(directory, "pair.json")
        // Remove metadata first so an interrupted overwrite cannot label a mixed pair as valid.
        for (file in listOf(metadataFile, surfaceFile, screenFile)) {
            check(!file.exists() || file.delete()) { "Could not replace ${file.name}" }
        }
        pair.surface.bitmap?.let { image -> surfaceFile.outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Surface PNG export failed" }
        } }
        pair.screen?.let { image -> screenFile.outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Screen PNG export failed" }
        } }
        fun size(value: RenderDiagnostics.Size) = JSONObject()
            .put("width", value.width).put("height", value.height)
        fun state(value: SurfaceCapture.State): JSONObject = JSONObject()
            .put("viewId", value.viewId).put("attachmentId", value.attachmentId)
            .put("contextId", value.contextId).put("surfaceRevision", value.surfaceRevision)
            .put("available", value.available).put("layoutSize", size(value.layoutSize))
            .put("holderSize", size(value.holderSize)).put("rendererSize", size(value.rendererSize))
            .put("latestFrame", value.latestFrame?.let { frame -> JSONObject()
                .put("frameId", frame.frameId).put("drawnAtNanos", frame.drawnAtNanos)
                .put("frameSize", size(frame.frameSize))
                .put("targetViewId", frame.target.context.viewId)
                .put("targetAttachmentId", frame.target.context.attachmentId)
                .put("targetContextId", frame.target.context.contextId)
                .put("targetSurfaceRevision", frame.target.surfaceRevision)
            } ?: JSONObject.NULL)
        fun sample(value: ScreenSample) = JSONObject().put("atNanos", value.atNanos)
            .put("state", state(value.state)).put("screenX", value.x).put("screenY", value.y)
            .put("displayId", value.displayId).put("rotation", value.rotation)
        val metadata = JSONObject()
            .put("comparable", pair.comparable)
            .put("reasons", org.json.JSONArray(pair.reasons))
            .put("sameFrameProven", false)
            .put("comparisonMeaning", "Sequential captures with unchanged observed state; not an atomic same-frame pair")
            .put("screenSource", "UiAutomation.takeScreenshot")
            .put("pairBefore", sample(pair.before))
            .put("surfaceStatus", pair.surface.status.name)
            .put("platformStatus", pair.surface.platformStatus ?: JSONObject.NULL)
            .put("surfaceBefore", JSONObject().put("atNanos", pair.surface.before.atNanos)
                .put("state", state(pair.surface.before.state)))
            .put("surfaceAfter", JSONObject().put("atNanos", pair.surface.after.atNanos)
                .put("state", state(pair.surface.after.state)))
            .put("screenBefore", sample(pair.screenBefore))
            .put("screenAfter", sample(pair.screenAfter))
            .put("screenWidth", pair.screen?.width ?: 0).put("screenHeight", pair.screen?.height ?: 0)
        metadataFile.writeText(metadata.toString(2))
    }

    private fun capture(view: InfiniteCanvasView): SurfaceCapture.Result {
        val finished = CountDownLatch(1)
        val ownership = Any()
        var accepting = true
        var result: SurfaceCapture.Result? = null
        try {
            RenderHostFixture.onMain {
                view.captureSurface {
                    synchronized(ownership) {
                        if (accepting) result = it else it.bitmap?.recycle()
                    }
                    finished.countDown()
                }
            }
            RenderHostFixture.await("PixelCopy completion", finished)
            return synchronized(ownership) { checkNotNull(result).also { result = null } }
        } finally {
            synchronized(ownership) {
                accepting = false
                result?.bitmap?.recycle()
                result = null
            }
        }
    }
}

/** Shared real-window fixture; later resize/reattach tests can use the same host. */
internal class RenderHostFixture private constructor(val activity: RenderHostTestActivity) {
    fun attach(view: View, width: Int, height: Int) = onMain {
        activity.host.addView(view, FrameLayout.LayoutParams(width, height))
    }

    /** Wait for layout and two render completions without blocking the UI or GL thread. */
    fun awaitStableFrames(view: InfiniteCanvasView) {
        val laidOut = CountDownLatch(1)
        val listener = View.OnLayoutChangeListener { target, _, _, _, _, _, _, _, _ ->
            if (target.width > 0 && target.height > 0) laidOut.countDown()
        }
        onMain {
            view.addOnLayoutChangeListener(listener)
            if (view.isLaidOut && view.width > 0 && view.height > 0) laidOut.countDown()
        }
        try {
            await("canvas layout", laidOut)
        } finally {
            onMain { view.removeOnLayoutChangeListener(listener) }
        }
        val dimensions = onMain { view.width to view.height }
        repeat(2) {
            val rendered = CountDownLatch(1)
            onMain { view.publishThen { rendered.countDown() } }
            await("canvas render completion ${it + 1}", rendered)
        }
        awaitQuietDiagnostics(view)
        onMain {
            check(view.isAttachedToWindow && view.holder.surface.isValid) {
                "canvas detached while waiting for frames"
            }
            check(dimensions == (view.width to view.height)) { "canvas changed size while settling" }
            check(view.failure == null) { "GL renderer failed: ${view.failure}" }
        }
    }


    private fun awaitQuietDiagnostics(view: InfiniteCanvasView) {
        val finished = CountDownLatch(1)
        val handler = Handler(Looper.getMainLooper())
        val deadline = SystemClock.uptimeMillis() + 2_000L
        var previous: RenderDiagnostics.Snapshot? = null
        var unchanged = 0
        var settled = false
        val poll = object : Runnable {
            override fun run() {
                val current = view.renderDiagnostics.snapshot()
                val frame = current.latestFrame
                val matches = current.attached && current.surfaceAvailable && frame != null &&
                    current.layoutSize == current.holderSize &&
                    current.layoutSize == current.rendererSize &&
                    frame.frameSize == current.layoutSize
                unchanged = if (matches && current == previous) unchanged + 1 else 0
                previous = current
                if (unchanged >= 2) {
                    settled = true
                    finished.countDown()
                } else if (SystemClock.uptimeMillis() >= deadline) {
                    finished.countDown()
                } else {
                    handler.postDelayed(this, 32L)
                }
            }
        }
        handler.post(poll)
        try {
            await("stable GL frame metadata", finished)
            assertTrue("GL frame did not settle: $previous", settled)
        } finally {
            handler.removeCallbacks(poll)
        }
    }

    companion object {
        private const val WAIT_SECONDS = 10L

        fun <T> withHost(block: (RenderHostFixture) -> T): T {
            val instrumentation = PenTestRunner.instance
            val intent = Intent(instrumentation.targetContext, RenderHostTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val activity = instrumentation.startActivitySync(intent) as RenderHostTestActivity
            try {
                return block(RenderHostFixture(activity))
            } finally {
                onMain {
                    // Detach the GL view synchronously even if an assertion or capture failed.
                    try { activity.host.removeAllViews() } finally { activity.finish() }
                }
            }
        }

        fun <T> onMain(action: () -> T): T {
            var result: Result<T>? = null
            PenTestRunner.instance.runOnMainSync { result = runCatching(action) }
            return checkNotNull(result).getOrThrow()
        }

        fun await(label: String, latch: CountDownLatch) {
            assertTrue("Timed out waiting for $label", latch.await(WAIT_SECONDS, TimeUnit.SECONDS))
        }
    }
}
