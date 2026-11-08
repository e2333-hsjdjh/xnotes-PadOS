package com.xnotes.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import com.xnotes.core.model.ImageData
import com.xnotes.platform.ImageDecoder
import java.util.IdentityHashMap

/**
 * GL textures for placed images, decoded on demand at the size the current zoom needs.
 *
 * Images keep their encoded bytes on disk and are decoded only when drawn, which is what keeps a
 * canvas full of photographs off the heap. That property has to survive the move to GL, so nothing
 * here holds a full-resolution bitmap: a decode produces a texture at the size the screen can
 * actually show, the bitmap is recycled immediately, and the texture is re-made only when the zoom
 * has moved far enough that the one in hand is visibly coarse.
 *
 * Decoding runs off the render thread, because it reads a file and can take tens of milliseconds.
 * The upload has to happen with the context current, so a finished decode is queued and picked up
 * at the start of the next frame.
 */
class TextureCache internal constructor(
    private val budgetBytes: Long = DEFAULT_BUDGET_BYTES,
    /** Each success transfers a newly owned bitmap, never an ImageDecoder SVG cache entry. */
    private val decode: (ImageData, Int) -> Bitmap? = { image, edge ->
        if (ImageDecoder.isVector(image.file.path)) null
        else ImageDecoder.decodeSampledFile(image.file.path, edge, edge)
    },
) {

    private class Entry(
        val texture: Int,
        val width: Int,
        val height: Int,
        /** Longest edge, in pixels, this texture was decoded for. */
        val decodedFor: Int,
        /** Adopted anew when the image leaves the scene and comes back in the same document. */
        var source: DecodeMailbox.Source<ImageData, Bitmap>,
        var lastUsedFrame: Long,
    ) {
        val bytes: Long get() = width.toLong() * height * 4
    }

    private val entries = IdentityHashMap<ImageData, Entry>()
    private val requests = DecodeMailbox<ImageData, Bitmap>({ it.recycle() })

    private var contextGen = -1
    private var sourceBinding: Any? = null
    private var frame = 0L

    /** Bytes the live textures occupy, for the debug readout. */
    var residentBytes: Long = 0
        private set

    /** Images whose decode has been asked for and not yet arrived. */
    val pendingCount: Int get() = requests.pendingCount

    val textureCount: Int get() = entries.size

    /** A new context loses GPU names, but valid CPU decodes can still feed the new context. */
    fun onContextCreated(gen: Int) {
        val changed = contextGen != gen
        contextGen = gen
        entries.clear()
        residentBytes = 0
        if (changed) requests.retryFailures()
    }

    /** Consumer-only, after a complete scene update, with a current context for GPU eviction. */
    internal fun retainSources(binding: Any, images: Collection<ImageData>) {
        val sameDocument = binding === sourceBinding
        sourceBinding = binding
        requests.retainSources(binding, images)
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (image, entry) = iterator.next()
            if (requests.isCurrent(entry.source)) continue
            if (sameDocument) {
                // Deleted and undone, a crop undone, a layer hidden and shown: the same image is
                // likely back soon, so keep its texture for the budget to evict like any unused
                // one, and adopt its new source so it draws at once instead of blank until a
                // fresh decode lands. Its pixels cannot have changed: ImageData is immutable.
                requests.source(image)?.let { entry.source = it }
                continue
            }
            GLES30.glDeleteTextures(1, intArrayOf(entry.texture), 0)
            residentBytes -= entry.bytes
            iterator.remove()
        }
    }

    /** Take in whatever finished decoding since the last frame. Call with the context current. */
    fun uploadPending() {
        while (true) {
            val next = requests.receive() ?: return
            val request = next.request
            val bitmap = next.value
            var allocated: IntArray? = null
            try {
                if (bitmap == null || bitmap.isRecycled || !requests.isCurrent(request)) continue
                val name = IntArray(1)
                allocated = name
                val image = request.source.key
                val existing = entries[image]
                if (existing != null && existing.decodedFor >= request.target) {
                    requests.finish(request, success = true)
                    continue
                }
                GLES30.glGenTextures(1, name, 0)
                check(name[0] != 0) { "Texture allocation returned no name" }
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, name[0])
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
                check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "Texture upload failed" }
                val entry = Entry(name[0], bitmap.width, bitmap.height, request.target, request.source, frame)
                // close/reset can revoke the request while GL owns the received bitmap. They
                // cannot recycle it, and a rejected commit must only delete this new texture.
                if (!requests.finish(request, success = true)) continue
                entries[image] = entry
                name[0] = 0 // Ownership has moved to entries before any later operation can fail.
                residentBytes += entry.bytes - (existing?.bytes ?: 0L)
                if (existing != null) GLES30.glDeleteTextures(1, intArrayOf(existing.texture), 0)
            } catch (error: Throwable) {
                android.util.Log.w("xnotes.gl", "Image texture upload failed", error)
                if (error !is Exception) throw error
            } finally {
                // A successful/stale finish is a no-op here; it never clears a newer request.
                try {
                    requests.finish(request, success = false)
                } finally {
                    try {
                        allocated?.let { if (it[0] != 0) GLES30.glDeleteTextures(1, it, 0) }
                    } finally {
                        bitmap?.recycle()
                    }
                }
            }
        }
    }

    /**
     * The texture for [image] at a size that suits [wantedLongEdge] device pixels, or 0 when one is
     * not ready yet. Asks for a decode when there is nothing, or when what is held is coarse enough
     * that the difference would show.
     */
    fun textureFor(image: ImageData, wantedLongEdge: Int, decodeOn: (Runnable) -> Unit): Int {
        if (requests.source(image) == null) return 0
        val target = targetSize(wantedLongEdge).coerceAtMost(maxEdge(image))
        val entry = entries[image]
        if (entry != null) {
            entry.lastUsedFrame = frame
            // Re-decode only on a real step up: re-uploading for every small zoom change would
            // spend more time decoding than drawing.
            if (target > entry.decodedFor && entry.decodedFor < maxEdge(image)) request(image, target, decodeOn)
            return entry.texture
        }
        request(image, target, decodeOn)
        return 0
    }

    /** Mark the start of a frame and evict whatever the budget no longer covers. */
    fun beginFrame() {
        frame++
        if (residentBytes <= budgetBytes) return
        // Least recently drawn first: what is off screen goes before what is on it.
        val ordered = entries.entries.sortedBy { it.value.lastUsedFrame }
        for ((image, entry) in ordered) {
            if (residentBytes <= budgetBytes) break
            if (entry.lastUsedFrame == frame) continue // in this very frame, so still needed
            GLES30.glDeleteTextures(1, intArrayOf(entry.texture), 0)
            residentBytes -= entry.bytes
            entries.remove(image)
        }
    }

    /** Stop CPU delivery without issuing GL commands; ordinary host detachment is not closure. */
    fun close() = requests.close()

    /** Final release: stop delivery and delete GPU names. Call only with their context current. */
    fun release() {
        close()
        for (entry in entries.values) GLES30.glDeleteTextures(1, intArrayOf(entry.texture), 0)
        entries.clear()
        residentBytes = 0
    }

    private fun request(image: ImageData, target: Int, decodeOn: (Runnable) -> Unit) {
        val request = requests.request(image, target) ?: return
        val work = Runnable {
            if (!requests.start(request)) return@Runnable
            val bitmap = try {
                decode(image, target)
            } catch (error: Throwable) {
                requests.complete(request, null)
                if (error !is Exception) throw error
                return@Runnable
            }
            requests.complete(request, bitmap)
        }
        try {
            decodeOn(work)
        } catch (error: Throwable) {
            requests.reject(request)
            if (error !is Exception) throw error
        }
    }

    /**
     * The decode size for a wanted edge, rounded up to a power of two. Rounding is what stops a
     * slow pinch from re-decoding on every frame: the target only changes at doublings.
     */
    private fun targetSize(wantedLongEdge: Int): Int {
        var size = MIN_EDGE
        while (size < wantedLongEdge && size < MAX_EDGE) size *= 2
        return size
    }

    private fun maxEdge(image: ImageData): Int = maxOf(image.width, image.height).coerceAtLeast(1)

    companion object {
        /** Texture memory the cache aims to stay under. */
        const val DEFAULT_BUDGET_BYTES = 96L * 1024 * 1024

        /** Smallest and largest decode edge, in pixels. */
        const val MIN_EDGE = 128
        const val MAX_EDGE = 4096
    }
}
