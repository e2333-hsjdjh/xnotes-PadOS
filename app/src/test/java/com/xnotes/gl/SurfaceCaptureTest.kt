package com.xnotes.gl

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class SurfaceCaptureTest {
    private class Fixture(budget: SurfaceCapture.Budget = SurfaceCapture.Budget()) {
        val diagnostics = RenderDiagnostics().apply {
            attached()
            layoutChanged(12, 8)
            surfaceCreated()
            surfaceChanged(12, 8)
            frameDrawn(rendererResized(contextCreated(), 12, 8), 12, 8)
        }
        val results = mutableListOf<SurfaceCapture.Result>()
        lateinit var bitmap: Bitmap
        lateinit var finish: (Int) -> Unit
        var copies = 0
        val capture = SurfaceCapture(
            readState = { SurfaceCapture.State.from(diagnostics.snapshot(), valid = true) },
            copy = { image, done -> bitmap = image; finish = done; copies++ },
            handler = Handler(Looper.getMainLooper()),
            timeoutMs = 100,
            maxPixels = 1000,
            budget = budget,
        )
        fun request() = capture.request(results::add)
    }

    @Test fun successTransfersBitmapOwnershipAndDuplicateCallbackDoesNotRecycleIt() {
        val f = Fixture()
        f.request()
        f.bitmap.eraseColor(android.graphics.Color.WHITE)
        f.finish(PixelCopy.SUCCESS)
        assertEquals(SurfaceCapture.Status.SUCCESS, f.results.single().status)
        assertSame(f.bitmap, f.results.single().bitmap)
        f.finish(PixelCopy.SUCCESS)
        f.capture.close()
        assertEquals(1, f.results.size)
        assertFalse(f.bitmap.isRecycled)
        assertEquals(android.graphics.Color.WHITE, f.bitmap.getPixel(0, 0))
        f.bitmap.recycle()
    }

    @Test fun timeoutKeepsBitmapAndBudgetUntilNativeCompletion() {
        val f = Fixture()
        f.request()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(101))
        assertEquals(SurfaceCapture.Status.TIMED_OUT, f.results.single().status)
        assertFalse(f.bitmap.isRecycled)
        f.request()
        assertEquals(SurfaceCapture.Status.BUSY, f.results.last().status)
        assertEquals(1, f.copies)
        f.finish(PixelCopy.SUCCESS)
        assertTrue(f.bitmap.isRecycled)
        assertEquals(2, f.results.size)
        f.request()
        assertEquals(2, f.copies)
        f.finish(PixelCopy.ERROR_SOURCE_NO_DATA)
    }

    @Test fun replacementWithIdenticalDimensionsInvalidatesTheCapture() {
        val f = Fixture()
        f.request()
        f.diagnostics.surfaceDestroyed()
        f.diagnostics.surfaceCreated()
        f.diagnostics.surfaceChanged(12, 8)
        f.finish(PixelCopy.SUCCESS)
        assertEquals(SurfaceCapture.Status.CHANGED, f.results.single().status)
        assertNull(f.results.single().bitmap)
        assertTrue(f.bitmap.isRecycled)
    }

    @Test fun newContextOrFrameMakesThePairNonComparable() {
        for (changeContext in listOf(false, true)) {
            val f = Fixture()
            f.request()
            if (changeContext) f.diagnostics.contextCreated()
            else f.diagnostics.frameDrawn(f.diagnostics.snapshot().latestFrame!!.target, 12, 8)
            f.finish(PixelCopy.SUCCESS)
            assertEquals(SurfaceCapture.Status.CHANGED, f.results.single().status)
            assertTrue(f.bitmap.isRecycled)
        }
    }

    @Test fun platformTimeoutEndsNativeOwnershipUnlikeCallerDeadline() {
        val f = Fixture()
        f.request()
        f.finish(PixelCopy.ERROR_TIMEOUT)
        assertEquals(SurfaceCapture.Status.PLATFORM_ERROR, f.results.single().status)
        assertEquals(PixelCopy.ERROR_TIMEOUT, f.results.single().platformStatus)
        assertTrue(f.bitmap.isRecycled)
        f.request()
        assertEquals(2, f.copies)
        f.finish(PixelCopy.ERROR_SOURCE_NO_DATA)
    }

    @Test fun closeCompletesOnceButDoesNotRecycleAnImageStillBeingWritten() {
        val f = Fixture()
        f.request()
        f.capture.close()
        assertEquals(SurfaceCapture.Status.CLOSED, f.results.single().status)
        assertFalse(f.bitmap.isRecycled)
        f.finish(PixelCopy.SUCCESS)
        assertTrue(f.bitmap.isRecycled)
        assertEquals(1, f.results.size)
        f.request()
        assertEquals(SurfaceCapture.Status.CLOSED, f.results.last().status)
        assertEquals(1, f.copies)
    }

    @Test fun invalidAndOversizedSourcesDoNotAllocateOrSubmit() {
        val f = Fixture()
        f.diagnostics.surfaceDestroyed()
        f.request()
        assertEquals(SurfaceCapture.Status.UNAVAILABLE, f.results.last().status)
        f.diagnostics.surfaceCreated()
        f.diagnostics.surfaceChanged(Int.MAX_VALUE, Int.MAX_VALUE)
        f.request()
        assertEquals(SurfaceCapture.Status.TOO_LARGE, f.results.last().status)
        assertEquals(0, f.copies)
    }

    @Test fun synchronousSubmissionFailureReleasesTheBudgetAndBitmap() {
        val f = Fixture()
        var image: Bitmap? = null
        val capture = SurfaceCapture(
            readState = { SurfaceCapture.State.from(f.diagnostics.snapshot(), true) },
            copy = { bitmap, _ -> image = bitmap; throw IllegalArgumentException("no surface") },
        )
        capture.request(f.results::add)
        assertEquals(SurfaceCapture.Status.SUBMIT_FAILED, f.results.single().status)
        assertTrue(image!!.isRecycled)
        capture.request(f.results::add)
        assertEquals(SurfaceCapture.Status.SUBMIT_FAILED, f.results.last().status)
    }

    @Test fun anySubmissionFailureReleasesTheSharedBudgetAndBusySlot() {
        for (thrown in listOf<Throwable>(SecurityException("denied"), NullPointerException(), AssertionError("fatal"))) {
            val f = Fixture()
            val budget = SurfaceCapture.Budget(maxPixels = 1000, maxRequests = 1)
            var fail = true
            val capture = SurfaceCapture(
                readState = { SurfaceCapture.State.from(f.diagnostics.snapshot(), true) },
                copy = { _, _ -> if (fail) throw thrown },
                budget = budget,
            )
            val escaped = runCatching { capture.request(f.results::add) }.exceptionOrNull()
            if (thrown is Exception) assertNull("$thrown is settled, not thrown", escaped)
            else assertSame("an Error still propagates", thrown, escaped)
            assertEquals(SurfaceCapture.Status.SUBMIT_FAILED, f.results.single().status)
            fail = false
            capture.request(f.results::add)
            assertNull("$thrown: neither the budget nor the busy slot leaked", f.results.getOrNull(1)?.status)
            assertTrue(budget.acquire(0).not())
        }
    }

    @Test fun timeoutBudgetSurvivesHostReplacementAndOldDuplicateDoesNotAffectNewRequest() {
        val budget = SurfaceCapture.Budget(maxPixels = 1000, maxRequests = 1)
        val a = Fixture(budget)
        val b = Fixture(budget)
        a.request()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(101))
        b.request()
        assertEquals(SurfaceCapture.Status.BUDGET_EXHAUSTED, b.results.last().status)
        assertEquals(0, b.copies)
        a.finish(PixelCopy.SUCCESS)
        b.request()
        a.finish(PixelCopy.SUCCESS)
        assertFalse(b.bitmap.isRecycled)
        b.request()
        assertEquals(SurfaceCapture.Status.BUSY, b.results.last().status)
        b.finish(PixelCopy.SUCCESS)
        assertEquals(SurfaceCapture.Status.SUCCESS, b.results.last().status)
        b.bitmap.recycle()
    }

    @Test fun stableMissingFrameAndSizeMismatchArePreservedAsFaultEvidence() {
        val f = Fixture()
        f.diagnostics.layoutChanged(24, 8)
        assertNull(f.diagnostics.snapshot().latestFrame)
        f.request()
        f.finish(PixelCopy.SUCCESS)
        val result = f.results.single()
        assertEquals(SurfaceCapture.Status.SUCCESS, result.status)
        assertNull(result.before.state.latestFrame)
        assertNotEquals(result.before.state.layoutSize, result.before.state.holderSize)
        assertEquals(12, result.bitmap!!.width)
        result.bitmap.recycle()
    }
}
