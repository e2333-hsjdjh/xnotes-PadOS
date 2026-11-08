package com.xnotes.gl

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy

/**
 * Explicit diagnostic copy of the real Surface, without requesting a new frame or redrawing the
 * model. One instance is retained by each view; even a timed-out native copy keeps its budget.
 * No images are persisted or uploaded here. All operations and callbacks use the main looper.
 */
class SurfaceCapture internal constructor(
    private val readState: () -> State,
    private val copy: (Bitmap, (Int) -> Unit) -> Unit,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val timeoutMs: Long = 1500,
    private val maxPixels: Long = 16_777_216,
    private val nanoTime: () -> Long = System::nanoTime,
    private val budget: Budget = processBudget,
) {
    data class State(
        val viewId: Long,
        val attachmentId: Long,
        val contextId: Long,
        val surfaceRevision: Long,
        val available: Boolean,
        val layoutSize: RenderDiagnostics.Size,
        val holderSize: RenderDiagnostics.Size,
        val rendererSize: RenderDiagnostics.Size,
        val latestFrame: RenderDiagnostics.DrawnFrame?,
    ) {
        companion object {
            fun from(snapshot: RenderDiagnostics.Snapshot, valid: Boolean): State = State(
                snapshot.viewId, snapshot.attachmentId, snapshot.contextId, snapshot.surfaceRevision,
                snapshot.attached && snapshot.surfaceAvailable && valid,
                snapshot.layoutSize, snapshot.holderSize, snapshot.rendererSize, snapshot.latestFrame,
            )
        }
    }

    data class Sample(val state: State, val atNanos: Long)
    enum class Status {
        SUCCESS, UNAVAILABLE, BUSY, TOO_LARGE, BUDGET_EXHAUSTED, ALLOCATION_FAILED, SUBMIT_FAILED,
        TIMED_OUT, PLATFORM_ERROR, CHANGED, CLOSED,
    }
    /**
     * SUCCESS copies the most recently queued buffer, not a proven frame ID or presentation.
     * Missing observed frames or stable size mismatches are retained as diagnostic evidence;
     * callers must inspect the samples before comparing this image with a window screenshot.
     */
    data class Result(
        val status: Status,
        /** Non-null only on SUCCESS. Ownership is transferred to the caller, who must recycle it. */
        val bitmap: Bitmap?,
        val before: Sample,
        val after: Sample,
        val platformStatus: Int? = null,
    )

    private class Pending(val bitmap: Bitmap, val pixels: Long, val before: Sample, callback: (Result) -> Unit) {
        var callback: ((Result) -> Unit)? = callback
        var completed = false
        lateinit var timeout: Runnable
    }
    private var pending: Pending? = null
    private var closed = false

    /** Shared across replaced hosts: a lost native callback cannot enable unbounded allocation. */
    internal class Budget(private val maxPixels: Long = 16_777_216, private val maxRequests: Int = 2) {
        private var pixels = 0L
        private var requests = 0
        fun acquire(count: Long): Boolean {
            if (requests >= maxRequests || count > maxPixels - pixels) return false
            pixels += count
            requests++
            return true
        }
        fun release(count: Long) {
            check(requests > 0 && pixels >= count)
            pixels -= count
            requests--
        }
    }

    init {
        require(timeoutMs > 0 && maxPixels > 0)
        require(handler.looper == Looper.getMainLooper())
    }

    fun request(callback: (Result) -> Unit) {
        checkMainThread()
        val before = sample()
        fun reject(status: Status) = callback(Result(status, null, before, before))
        if (closed) return reject(Status.CLOSED)
        if (pending != null) return reject(Status.BUSY)
        val state = before.state
        val (width, height) = state.holderSize
        if (!state.available || width <= 0 || height <= 0) return reject(Status.UNAVAILABLE)
        val pixels = width.toLong() * height
        if (width > MAX_EDGE || height > MAX_EDGE || pixels > maxPixels) {
            return reject(Status.TOO_LARGE)
        }
        if (!budget.acquire(pixels)) return reject(Status.BUDGET_EXHAUSTED)
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            budget.release(pixels)
            return reject(Status.ALLOCATION_FAILED)
        }
        val request = Pending(bitmap, pixels, before, callback)
        request.timeout = Runnable {
            // PixelCopy may still be writing. Keep the bitmap AND the in-flight slot until its
            // callback, but release the caller's closure after notifying it exactly once.
            notify(request, Status.TIMED_OUT)
        }
        pending = request
        handler.postDelayed(request.timeout, timeoutMs)
        try {
            copy(bitmap) { status -> complete(request, status) }
        } catch (error: Throwable) {
            // Any failure to submit means no native callback will come: release the budget and the
            // busy slot now, or two such failures would block every capture until a restart.
            submissionFailed(request)
            if (error !is Exception) throw error
        }
    }

    /** A final owner shutdown. A late native callback still owns cleanup of its destination. */
    fun close() {
        checkMainThread()
        closed = true
        pending?.let {
            handler.removeCallbacks(it.timeout)
            notify(it, Status.CLOSED)
        }
    }

    private fun complete(request: Pending, platformStatus: Int) {
        checkMainThread()
        if (request.completed) return // Do not recycle an already delivered image on a duplicate.
        request.completed = true
        budget.release(request.pixels)
        handler.removeCallbacks(request.timeout)
        if (pending === request) pending = null
        val after = sample()
        val status = when {
            platformStatus != PixelCopy.SUCCESS -> Status.PLATFORM_ERROR
            after.state != request.before.state -> Status.CHANGED
            else -> Status.SUCCESS
        }
        val callback = request.callback
        request.callback = null
        val delivered = status == Status.SUCCESS && callback != null
        if (!delivered) request.bitmap.recycle()
        callback?.invoke(Result(status, if (delivered) request.bitmap else null,
            request.before, after, platformStatus))
    }

    private fun submissionFailed(request: Pending) {
        if (request.completed) return
        request.completed = true
        budget.release(request.pixels)
        handler.removeCallbacks(request.timeout)
        if (pending === request) pending = null
        request.bitmap.recycle()
        notify(request, Status.SUBMIT_FAILED)
    }

    private fun notify(request: Pending, status: Status) {
        val callback = request.callback ?: return
        request.callback = null
        callback(Result(status, null, request.before, sample()))
    }

    private fun sample() = Sample(readState(), nanoTime())
    private fun checkMainThread() = check(Looper.myLooper() == handler.looper)

    companion object {
        private const val MAX_EDGE = 16_384
        private val processBudget = Budget()

        internal fun forView(view: InfiniteCanvasView): SurfaceCapture {
            val main = Handler(Looper.getMainLooper())
            return SurfaceCapture(
                readState = {
                    State.from(view.renderDiagnostics.snapshot(), view.holder.surface.isValid)
                },
                copy = { bitmap, complete -> PixelCopy.request(view, bitmap, complete, main) },
                handler = main,
            )
        }
    }
}
