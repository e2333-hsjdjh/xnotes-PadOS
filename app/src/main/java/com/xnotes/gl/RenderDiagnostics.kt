package com.xnotes.gl

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded, process-local rendering metadata. Never accepts model data, file names or error text.
 *
 * This observes the existing renderer; it does not schedule frames or grant rendering ownership.
 * All snapshots are copied under one lock so UI/GL callbacks cannot publish mixed identities.
 * Document and scene revisions are intentionally absent until those identities exist in runtime.
 */
class RenderDiagnostics(
    private val capacity: Int = 64,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    data class Size(val width: Int, val height: Int)
    data class ContextToken(val viewId: Long, val attachmentId: Long, val contextId: Long)
    /** Application-observed target, not a framework buffer identity or rendering permission. */
    data class TargetToken(val context: ContextToken, val surfaceRevision: Long)
    data class DrawnFrame(
        val frameId: Long,
        val target: TargetToken,
        val frameSize: Size,
        val drawnAtNanos: Long,
    )
    enum class Kind {
        ATTACHED, DETACHED, LAYOUT_CHANGED, SURFACE_CREATED, SURFACE_CHANGED,
        SURFACE_DESTROYED, CONTEXT_CREATED, RENDERER_RESIZED, FIRST_FRAME, FAILURE,
    }
    enum class Failure { SHADER, SELF_CHECK }
    data class Event(
        val sequence: Long,
        val atNanos: Long,
        val threadId: Long,
        val kind: Kind,
        val attachmentId: Long,
        val contextId: Long,
        val surfaceRevision: Long,
        val layoutSize: Size,
        val holderSize: Size,
        val rendererSize: Size,
        val failure: Failure? = null,
    )
    data class Snapshot(
        val viewId: Long,
        val attachmentId: Long,
        val attached: Boolean,
        val contextId: Long,
        val surfaceRevision: Long,
        val surfaceAvailable: Boolean,
        val layoutSize: Size,
        val holderSize: Size,
        /** Last viewport size configured by onSurfaceChanged, not a per-frame GL state query. */
        val rendererSize: Size,
        /** Renderer callback completed; neither eglSwapBuffers success nor presentation is known. */
        val latestFrame: DrawnFrame?,
        val events: List<Event>,
    )

    val viewId: Long = nextViewId.incrementAndGet()
    private var attachmentId = 0L
    private var attached = false
    private var contextId = 0L
    private var surfaceRevision = 0L
    private var surfaceAvailable = false
    private var layoutSize = Size(0, 0)
    private var holderSize = Size(0, 0)
    private var rendererSize = Size(0, 0)
    private var latestFrame: DrawnFrame? = null
    private var frameSequence = 0L
    private var eventSequence = 0L
    private var firstFrameTarget: TargetToken? = null
    private val events = ArrayDeque<Event>()

    init { require(capacity > 0) }

    @Synchronized fun snapshot(): Snapshot = Snapshot(
        viewId, attachmentId, attached, contextId, surfaceRevision, surfaceAvailable,
        layoutSize, holderSize, rendererSize, latestFrame, events.toList(),
    )

    @Synchronized fun attached() {
        attachmentId++
        attached = true
        latestFrame = null
        record(Kind.ATTACHED)
    }

    /** Called before framework teardown can wait for the old render thread. */
    @Synchronized fun detached() {
        attached = false
        surfaceAvailable = false
        surfaceRevision++
        latestFrame = null
        record(Kind.DETACHED)
    }

    @Synchronized fun layoutChanged(width: Int, height: Int) {
        val size = Size(width, height)
        if (size == layoutSize) return
        layoutSize = size
        latestFrame = null
        record(Kind.LAYOUT_CHANGED)
    }

    @Synchronized fun surfaceCreated() {
        surfaceRevision++
        surfaceAvailable = true
        holderSize = Size(0, 0)
        rendererSize = Size(0, 0)
        latestFrame = null
        record(Kind.SURFACE_CREATED)
    }

    @Synchronized fun surfaceChanged(width: Int, height: Int) {
        // A same-size callback may still belong to a replacement buffer queue.
        surfaceRevision++
        holderSize = Size(width, height)
        latestFrame = null
        record(Kind.SURFACE_CHANGED)
    }

    @Synchronized fun surfaceDestroyed() {
        surfaceRevision++
        surfaceAvailable = false
        latestFrame = null
        record(Kind.SURFACE_DESTROYED)
    }

    @Synchronized fun contextCreated(): ContextToken? {
        // This GL callback can arrive while UI Surface teardown waits for GLThread. The context
        // may survive that teardown, so retain its identity until the Surface becomes available.
        if (!attached) return null
        contextId++
        rendererSize = Size(0, 0)
        latestFrame = null
        record(Kind.CONTEXT_CREATED)
        return ContextToken(viewId, attachmentId, contextId)
    }

    @Synchronized fun rendererResized(context: ContextToken?, width: Int, height: Int): TargetToken? {
        if (!surfaceAvailable || !currentContext(context)) return null
        rendererSize = Size(width, height)
        latestFrame = null
        record(Kind.RENDERER_RESIZED)
        // GLThread copies resize arguments before invoking the renderer outside its lock. A newer
        // UI resize may already be observed here. Preserve the mismatching dimensions as evidence,
        // but do not let that late callback claim the newer target. Same-size native replacements
        // cannot be distinguished by this observer; these tokens never authorize GPU access.
        if (rendererSize != holderSize) return null
        return TargetToken(context!!, surfaceRevision)
    }

    /** Called after drawing, before GLSurfaceView's swap. Regular frames never enter the ring. */
    @Synchronized fun frameDrawn(target: TargetToken?, width: Int, height: Int): Boolean {
        if (target == null || !surfaceAvailable || !currentContext(target.context) ||
            target.surfaceRevision != surfaceRevision) return false
        latestFrame = DrawnFrame(++frameSequence, target, Size(width, height), nanoTime())
        if (firstFrameTarget != target) {
            firstFrameTarget = target
            record(Kind.FIRST_FRAME)
        }
        return true
    }

    @Synchronized fun failure(context: ContextToken?, failure: Failure) {
        // Shader/self-check failures belong to the context, even while its Surface is unavailable.
        if (currentContext(context)) record(Kind.FAILURE, failure)
    }

    private fun currentContext(context: ContextToken?): Boolean =
        context != null && attached && context.viewId == viewId &&
            context.attachmentId == attachmentId && context.contextId == contextId

    private fun record(kind: Kind, failure: Failure? = null) {
        if (events.size == capacity) events.removeFirst()
        events.addLast(Event(
            ++eventSequence, nanoTime(), Thread.currentThread().id, kind, attachmentId,
            contextId, surfaceRevision, layoutSize, holderSize, rendererSize, failure,
        ))
    }

    companion object { private val nextViewId = AtomicLong() }
}
