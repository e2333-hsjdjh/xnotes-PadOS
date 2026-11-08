package com.xnotes.gl

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class RenderDiagnosticsTest {
    private fun ready(d: RenderDiagnostics, width: Int = 100): RenderDiagnostics.TargetToken {
        d.attached()
        d.layoutChanged(width, 80)
        d.surfaceCreated()
        d.surfaceChanged(width, 80)
        return d.rendererResized(d.contextCreated(), width, 80)!!
    }

    @Test fun lifecycleRingIsBoundedAndSnapshotsDoNotChange() {
        val d = RenderDiagnostics(capacity = 3)
        d.attached()
        val old = d.snapshot()
        repeat(10) { d.layoutChanged(it + 1, 80) }
        val now = d.snapshot()
        assertEquals(3, now.events.size)
        assertEquals(listOf(9L, 10L, 11L), now.events.map { it.sequence })
        assertEquals(1, old.events.size)
        assertEquals(RenderDiagnostics.Size(0, 0), old.layoutSize)
        assertNotEquals(d.viewId, RenderDiagnostics().viewId)
    }

    @Test fun regularFramesOnlyReplaceLatestFrame() {
        val d = RenderDiagnostics()
        val target = ready(d)
        d.frameDrawn(target, 100, 80)
        val first = d.snapshot()
        repeat(100) { d.frameDrawn(target, 100, 80) }
        val now = d.snapshot()
        assertEquals(first.events, now.events)
        assertEquals(101L, now.latestFrame!!.frameId)
        assertEquals(1L, first.latestFrame!!.frameId)
        assertEquals(RenderDiagnostics.Size(100, 80), now.latestFrame!!.frameSize)
    }

    @Test fun identicalSizeReattachmentRejectsOldFramesAndOldContextCallbacks() {
        val d = RenderDiagnostics()
        val old = ready(d)
        d.detached()
        val current = ready(d)
        assertFalse(d.frameDrawn(old, 100, 80))
        assertNull(d.rendererResized(old.context, 100, 80))
        assertNull(d.snapshot().latestFrame)
        assertTrue(d.frameDrawn(current, 100, 80))
        assertTrue(current.context.attachmentId > old.context.attachmentId)
        assertTrue(current.context.contextId > old.context.contextId)
    }

    @Test fun surfaceRecreationAndResizeInvalidateOldTargetsWithoutLosingContext() {
        val d = RenderDiagnostics()
        val old = ready(d)
        d.frameDrawn(old, 100, 80)
        d.surfaceDestroyed()
        assertFalse(d.snapshot().surfaceAvailable)
        assertNull(d.snapshot().latestFrame)
        d.surfaceCreated()
        d.surfaceChanged(100, 80)
        assertFalse(d.frameDrawn(old, 100, 80))
        val current = d.rendererResized(old.context, 100, 80)!!
        assertTrue(d.frameDrawn(current, 100, 80))
        d.surfaceChanged(200, 80)
        assertFalse(d.frameDrawn(current, 100, 80))
        assertNull(d.snapshot().latestFrame)
        assertTrue(d.frameDrawn(d.rendererResized(old.context, 200, 80), 200, 80))
        assertEquals(old.context.contextId, d.snapshot().contextId)
    }

    @Test fun contextRecreationInvalidatesOldFramesAndRecordsOnlyFailureCategories() {
        val d = RenderDiagnostics()
        val old = ready(d)
        val context = d.contextCreated()
        val recreated = d.snapshot()
        d.failure(old.context, RenderDiagnostics.Failure.SHADER)
        assertEquals(recreated, d.snapshot())
        d.failure(context, RenderDiagnostics.Failure.SHADER)
        assertFalse(d.frameDrawn(old, 100, 80))
        assertEquals(RenderDiagnostics.Failure.SHADER, d.snapshot().events.last().failure)
        d.detached()
        val count = d.snapshot().events.size
        d.failure(context, RenderDiagnostics.Failure.SELF_CHECK)
        assertEquals(count, d.snapshot().events.size)
    }

    @Test fun lateContextCreationSurvivesSurfaceRecreationInTheSameAttachment() {
        val d = RenderDiagnostics()
        d.attached()
        d.layoutChanged(100, 80)
        d.surfaceCreated()
        d.surfaceChanged(100, 80)
        // The UI can mark the Surface lost before GLThread delivers onSurfaceCreated.
        d.surfaceDestroyed()
        val context = d.contextCreated()
        assertNotNull(context)
        val waiting = d.snapshot()
        assertFalse(waiting.surfaceAvailable)
        assertEquals(RenderDiagnostics.Kind.CONTEXT_CREATED, waiting.events.last().kind)
        assertEquals(context!!.contextId, waiting.contextId)
        assertEquals(context.attachmentId, waiting.attachmentId)

        // GLSurfaceView may preserve that context and only deliver onSurfaceChanged here.
        d.surfaceCreated()
        d.surfaceChanged(100, 80)
        val target = d.rendererResized(context, 100, 80)
        assertNotNull(target)
        assertTrue(d.frameDrawn(target, 100, 80))
        val restored = d.snapshot()
        assertEquals(context, restored.latestFrame!!.target.context)
        assertEquals(waiting.contextId, restored.contextId)
        assertEquals(1, restored.events.count { it.kind == RenderDiagnostics.Kind.CONTEXT_CREATED })
    }

    @Test fun recordedContextCannotPublishTargetsOrFramesWithoutASurface() {
        val d = RenderDiagnostics()
        d.attached()
        d.surfaceCreated()
        d.surfaceChanged(100, 80)
        d.surfaceDestroyed()
        val context = d.contextCreated()
        assertNotNull(context)
        val waiting = d.snapshot()

        assertNull(d.rendererResized(context, 100, 80))
        // Even matching observed identities do not make an unavailable Surface drawable.
        val unavailableTarget = RenderDiagnostics.TargetToken(context!!, waiting.surfaceRevision)
        assertFalse(d.frameDrawn(unavailableTarget, 100, 80))
        assertEquals(waiting, d.snapshot())
    }

    @Test fun lateContextCreationAfterDetachIsRejectedWithoutChangingDiagnostics() {
        val d = RenderDiagnostics()
        val target = ready(d)
        d.detached()
        val detached = d.snapshot()

        assertNull(d.contextCreated())
        assertNull(d.rendererResized(target.context, 100, 80))
        assertFalse(d.frameDrawn(target, 100, 80))
        assertEquals(detached, d.snapshot())
    }

    @Test fun contextFailuresRemainObservableWhileTheSurfaceIsUnavailable() {
        val d = RenderDiagnostics()
        val target = ready(d)
        d.surfaceDestroyed()
        for (failure in RenderDiagnostics.Failure.entries) {
            d.failure(target.context, failure)
            val failed = d.snapshot()
            assertFalse(failed.surfaceAvailable)
            assertEquals(RenderDiagnostics.Kind.FAILURE, failed.events.last().kind)
            assertEquals(failure, failed.events.last().failure)
            assertEquals(target.context.contextId, failed.events.last().contextId)
        }
        assertNull(d.rendererResized(target.context, 100, 80))
        assertFalse(d.frameDrawn(target, 100, 80))
        d.surfaceCreated()
        d.surfaceChanged(100, 80)
        assertTrue(d.frameDrawn(d.rendererResized(target.context, 100, 80), 100, 80))
        assertEquals(2, d.snapshot().events.count { it.kind == RenderDiagnostics.Kind.FAILURE })
    }

    @Test fun lateResizeCannotClaimTheLatestSurfaceRevision() {
        val d = RenderDiagnostics()
        val old = ready(d)
        // GLThread can have copied the old dimensions before the UI publishes another resize.
        d.surfaceChanged(200, 80)
        val late = d.rendererResized(old.context, 100, 80)
        assertNull(late)
        assertFalse(d.frameDrawn(late, 100, 80))
        val snapshot = d.snapshot()
        assertEquals(RenderDiagnostics.Size(100, 80), snapshot.rendererSize)
        assertEquals(RenderDiagnostics.Size(200, 80), snapshot.holderSize)
        assertEquals(RenderDiagnostics.Kind.RENDERER_RESIZED, snapshot.events.last().kind)
        assertNull(snapshot.latestFrame)
        val current = d.rendererResized(old.context, 200, 80)
        assertTrue(d.frameDrawn(current, 200, 80))
    }

    @Test fun concurrentSnapshotsNeverMixFrameAndSurfaceIdentity() {
        val d = RenderDiagnostics()
        val target = ready(d)
        val gate = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val writer = Thread {
            try {
                gate.await()
                repeat(10_000) { d.frameDrawn(target, 100, 80) }
            } catch (t: Throwable) { failure.set(t) }
        }
        writer.start()
        gate.countDown()
        repeat(1_000) {
            val snapshot = d.snapshot()
            snapshot.latestFrame?.let { frame ->
                assertEquals(snapshot.attachmentId, frame.target.context.attachmentId)
                assertEquals(snapshot.contextId, frame.target.context.contextId)
                assertEquals(snapshot.surfaceRevision, frame.target.surfaceRevision)
            }
        }
        d.surfaceDestroyed()
        writer.join(5_000)
        assertFalse(writer.isAlive)
        failure.get()?.let { throw it }
        assertNull(d.snapshot().latestFrame)
    }
}
