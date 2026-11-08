package com.xnotes.gl

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.GLES30
import com.xnotes.core.audio.InkFade
import com.xnotes.core.geometry.Flip
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.infinite.InkPass
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.infinite.MeshBuilder
import com.xnotes.core.infinite.MeshPart
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.SyncMark
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.pen.PenTestRunner
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.PI

/** Real EGL scene/queue tests; these do not exercise a window, front-buffer handoff, or target GPU compatibility. */
class CanvasSceneSnapshotTest {
    @Test fun sharedRasterSourceSurvivesGeometryAndSameBindingRebuildUntilLastRemoval() = withPbuffer {
        val files = mutableListOf<File>()
        try {
            withScene { scene, decodes ->
                val source = quadrantImage(files, 0)
                val a = ImageItem(source, Rect(8.0, 8.0, 20.0, 20.0))
                val b = ImageItem(source, Rect(32.0, 8.0, 20.0, 20.0))
                val binding = Any()
                scene.batch {
                    scene.reset(binding)
                    scene.upsertImage(a, a.paintBounds())
                    scene.upsertImage(b, b.paintBounds())
                }
                drawScene(scene)
                assertEquals(1, decodes.pending.size)
                assertEquals(1, scene.textures.pendingCount)
                a.translate(0.0, 4.0)
                scene.upsertImage(a, a.paintBounds())
                scene.remove(b)
                drawScene(scene)
                assertEquals("geometry/shared-member removal must retain the request", 1, decodes.pending.size)
                scene.batch {
                    scene.reset(binding)
                    scene.upsertImage(a, a.paintBounds())
                }
                drawScene(scene)
                assertEquals("same-binding full rebuild retains the source", 1, decodes.pending.size)
                scene.remove(a)
                drawScene(scene)
                assertEquals(0, scene.textures.pendingCount)
                scene.upsertImage(a, a.paintBounds())
                drawScene(scene)
                assertEquals("reentry gets a new request", 2, decodes.pending.size)
                decodes.finishPending()
                drawScene(scene)
                assertEquals(0, scene.textures.pendingCount)
                assertEquals(1, scene.textures.textureCount)
            }
        } finally { files.forEach { it.delete() } }
    }

    @Test fun newDocumentBindingRejectsReadyDecodeEvenWithTheSameImageData() = withPbuffer {
        val files = mutableListOf<File>()
        try {
            withScene { scene, decodes ->
                val image = ImageItem(quadrantImage(files, 0), Rect(8.0, 8.0, 40.0, 40.0))
                scene.batch { scene.reset(Any()); scene.upsertImage(image, image.paintBounds()) }
                drawScene(scene)
                decodes.finishPending() // A's bitmap is ready, but has never been uploaded.
                scene.batch { scene.reset(Any()); scene.upsertImage(image, image.paintBounds()) }
                drawScene(scene)
                assertEquals("A's result cannot satisfy B", 0, scene.textures.textureCount)
                assertEquals(1, scene.textures.pendingCount)
                assertEquals(1, decodes.pending.size)
                decodes.finishPending()
                drawScene(scene)
                assertEquals(1, scene.textures.textureCount)
                assertEquals(0, scene.textures.pendingCount)
            }
        } finally { files.forEach { it.delete() } }
    }

    @Test fun rasterFailuresAndRejectedSubmissionsSettleWithBoundedRetries() = withPbuffer {
        for (throws in listOf(false, true)) {
            var decoded = 0
            withTextureCache({ _, _ ->
                decoded++
                if (throws) error("decode failed") else null
            }) { cache, image ->
                val work = ArrayDeque<Runnable>()
                repeat(2) {
                    assertEquals(0, cache.textureFor(image, 128) { work.addLast(it) })
                    work.removeFirst().run()
                    cache.uploadPending()
                    assertEquals(0, cache.pendingCount)
                }
                repeat(20) { cache.textureFor(image, 128) { work.addLast(it) } }
                assertTrue(work.isEmpty())
                assertEquals(2, decoded)
                // No GPU name exists here. This tests the recovery notification, not physical
                // context destruction (the separate halo fixture exercises actual recreation).
                cache.onContextCreated(2)
                cache.textureFor(image, 128) { work.addLast(it) }
                assertEquals(1, work.size)
                cache.close()
                work.removeFirst().run()
                assertEquals(2, decoded)
            }
        }
        withTextureCache({ _, _ -> error("Rejected work must not decode") }) { cache, image ->
            var attempts = 0
            repeat(20) {
                cache.textureFor(image, 128) {
                    attempts++
                    throw java.util.concurrent.RejectedExecutionException()
                }
                cache.uploadPending()
                assertEquals(0, cache.pendingCount)
            }
            assertEquals(2, attempts)
        }
    }

    @Test fun runningLowResolutionShowsFirstThenTheHigherResolutionReplacesIt() = withPbuffer {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val low = AtomicReference<Bitmap?>()
        val high = AtomicReference<Bitmap?>()
        val failure = AtomicReference<Throwable?>()
        withTextureCache({ _, edge ->
            val bitmap = solidBitmap()
            if (edge == 128) {
                low.set(bitmap)
                started.countDown()
                try {
                    check(finish.await(5, TimeUnit.SECONDS))
                } catch (error: Throwable) {
                    failure.set(error) // TextureCache handles decoder exceptions; keep fixture failures visible.
                    bitmap.recycle()
                    throw error
                }
            } else high.set(bitmap)
            bitmap
        }) { cache, image ->
            val work = ArrayDeque<Runnable>()
            cache.textureFor(image, 128) { work.addLast(it) }
            val first = work.removeFirst()
            val worker = Thread { try { first.run() } catch (error: Throwable) { failure.set(error) } }
            worker.start()
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertEquals("nothing to draw yet", 0, cache.textureFor(image, 512) { work.addLast(it) })
                assertTrue("a running decode is not superseded", work.isEmpty())
                finish.countDown()
                worker.join(5_000)
                assertFalse(worker.isAlive)
                failure.get()?.let { throw AssertionError(it) }
                cache.uploadPending()
                assertTrue(checkNotNull(low.get()).isRecycled)
                val coarse = cache.textureFor(image, 512) { work.addLast(it) }
                assertTrue("the finished smaller decode draws at once", coarse != 0)
                assertEquals("then the larger size is asked for", 1, work.size)
                work.removeFirst().run()
                cache.uploadPending()
                assertTrue(checkNotNull(high.get()).isRecycled)
                val sharp = cache.textureFor(image, 512) { work.addLast(it) }
                assertTrue(sharp != 0)
                assertEquals(1, cache.textureCount)
                assertEquals(0, cache.pendingCount)
                assertTrue(work.isEmpty())
            } finally {
                finish.countDown()
                worker.join(5_000)
            }
        }
    }

    @Test fun sameDocumentRemovalKeepsTheTextureForReentryButANewDocumentDoesNot() = withPbuffer {
        withTextureCache({ _, _ -> solidBitmap() }) { cache, image ->
            val binding = Any()
            cache.retainSources(binding, listOf(image))
            val work = ArrayDeque<Runnable>()
            cache.textureFor(image, 128) { work.addLast(it) }
            work.removeFirst().run()
            cache.uploadPending()
            val texture = cache.textureFor(image, 128) { work.addLast(it) }
            assertTrue(texture != 0)
            cache.retainSources(binding, emptyList())
            assertEquals("kept for undo or a layer shown again", 1, cache.textureCount)
            cache.retainSources(binding, listOf(image))
            assertEquals("draws at once on reentry", texture, cache.textureFor(image, 128) { work.addLast(it) })
            assertTrue("and needs no new decode", work.isEmpty())
            cache.retainSources(Any(), listOf(image))
            assertEquals("another document never reuses it", 0, cache.textureCount)
        }
    }

    @Test fun validCpuResultsSurviveContextNotificationBeforeAndAfterCompletion() = withPbuffer {
        for (completeFirst in listOf(false, true)) {
            val bitmap = AtomicReference<Bitmap?>()
            withTextureCache({ _, _ -> solidBitmap().also(bitmap::set) }) { cache, image ->
                val work = ArrayDeque<Runnable>()
                cache.textureFor(image, 128) { work.addLast(it) }
                if (completeFirst) work.removeFirst().run()
                // There are no GPU entries to abandon: exercise CPU ownership across the context
                // callback while using this pbuffer solely for the eventual real GL upload.
                cache.onContextCreated(2)
                if (!completeFirst) work.removeFirst().run()
                assertFalse(checkNotNull(bitmap.get()).isRecycled)
                cache.uploadPending()
                assertTrue(checkNotNull(bitmap.get()).isRecycled)
                assertTrue(cache.textureFor(image, 128) { work.addLast(it) } != 0)
                assertEquals(0, cache.pendingCount)
                assertTrue(work.isEmpty())
            }
        }
    }

    @Test fun failedUploadKeepsExistingTextureAndRecyclesTheNewBitmap() = withPbuffer {
        val decoded = ArrayList<Bitmap>()
        withTextureCache({ _, _ -> solidBitmap().also(decoded::add) }) { cache, image ->
            val work = ArrayDeque<Runnable>()
            cache.textureFor(image, 128) { work.addLast(it) }
            work.removeFirst().run()
            cache.uploadPending()
            val low = cache.textureFor(image, 128) { work.addLast(it) }
            assertTrue(GLES30.glIsTexture(low))
            val bytes = cache.residentBytes
            cache.textureFor(image, 512) { work.addLast(it) }
            work.removeFirst().run()
            // Inject a GL error, forcing the upload validation path to reject its new name.
            GLES30.glBindTexture(-1, 0)
            cache.uploadPending()
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
            assertTrue(decoded.all { it.isRecycled })
            assertEquals(bytes, cache.residentBytes)
            assertEquals(1, cache.textureCount)
            assertTrue(GLES30.glIsTexture(low))
            assertEquals(low, cache.textureFor(image, 128) { work.addLast(it) })
            assertEquals(0, cache.pendingCount)
        }
    }

    @Test fun closeRecyclesReadyBitmapAndCannotBeReopenedByContextNotification() = withPbuffer {
        val bitmap = AtomicReference<Bitmap?>()
        withTextureCache({ _, _ -> solidBitmap().also(bitmap::set) }) { cache, image ->
            val work = ArrayDeque<Runnable>()
            cache.textureFor(image, 128) { work.addLast(it) }
            work.removeFirst().run()
            cache.close()
            assertTrue(checkNotNull(bitmap.get()).isRecycled)
            cache.onContextCreated(2)
            cache.retainSources(Any(), listOf(image))
            cache.uploadPending()
            assertEquals(0, cache.textureFor(image, 128) { work.addLast(it) })
            assertTrue(work.isEmpty())
            assertEquals(0, cache.pendingCount)
        }
    }

    private fun solidBitmap() = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }

    private fun withTextureCache(decode: (ImageData, Int) -> Bitmap?, block: (TextureCache, ImageData) -> Unit) {
        val cache = TextureCache(decode = decode)
        try {
            cache.onContextCreated(1)
            // The injected decoder does not read/create this file; image dimensions permit the
            // fixture's two distinct quality requests without allocating large Android bitmaps.
            val image = ImageData(File(PenTestRunner.instance.targetContext.cacheDir, "unused-decode-source"), 1024, 1024)
            cache.retainSources(Any(), listOf(image))
            block(cache, image)
        } finally { cache.release() }
    }

    @Test fun queuedImageFieldsStayFrozenUntilAnotherUpsert() = withPbuffer {
        val files = mutableListOf<File>()
        try {
            val original = quadrantImage(files, 0)
            val replacement = quadrantImage(files, 32)
            val mutations = listOf(
                ImageMutation("source", listOf(32, 96, 160, 224)) { it.image = replacement },
                ImageMutation("rect", listOf(255, 0, 255, 128)) { it.rect = Rect(24.0, 8.0, 40.0, 40.0) },
                ImageMutation("orientation", listOf(64, 192, 0, 128)) { it.orientation = 90 },
                ImageMutation("angle", listOf(192, 128, 64, 0)) { it.angle = PI },
                ImageMutation("flip", listOf(64, 0, 192, 128)) { it.flip = Flip.X },
                ImageMutation("part", listOf(0, 0, 128, 128)) { it.part = Rect(0.0, 0.0, 0.5, 1.0) },
            )
            for ((field, expected, mutate) in mutations) withScene { scene, decodes ->
                val item = ImageItem(original, Rect(8.0, 8.0, 40.0, 40.0))
                scene.fade = InkFade(RECORDING_ID, PLAYHEAD_MS)
                scene.upsertImage(item, item.paintBounds())
                // Change one image field after enqueue, before even the first scene drain/decode.
                mutate(item)
                item.sync = FUTURE_MARK
                drawReady(scene, decodes)
                val before = imageProbes()
                for (i in before.indices) assertGray("$field: queued image at probe $i", i * 64, before[i])
                assertEquals("$field: original source decoded once", 1, decodes.completed)

                // An explicit upsert must adopt that field. Freeze null sync again so a missing
                // image-field update cannot pass merely because the playback alpha changed.
                item.sync = null
                scene.upsertImage(item, item.paintBounds())
                item.sync = FUTURE_MARK
                drawReady(scene, decodes)
                val after = imageProbes()
                for (i in after.indices) assertGray("$field: explicit upsert at probe $i", expected[i], after[i])
            }
        } finally {
            files.forEach { it.delete() }
        }
    }

    @Test fun syncMessagesFreezeMarksWithoutReplacingGeometry() = withPbuffer {
        withScene { scene, decodes ->
            val stroke = strokeAt(32.0)
            val mesh = checkNotNull(ItemMesher.mesh(stroke))
            scene.fade = InkFade(RECORDING_ID, PLAYHEAD_MS)
            scene.upsert(stroke, mesh.parts, mesh.bounds)
            // Recording stamps ink after its initial model-add/upsert. Without a message, the
            // already queued null mark must keep full opacity even though the live model changed.
            stroke.sync = FUTURE_MARK
            drawReady(scene, decodes)
            assertGray("upsert froze null sync", 0, grayAt(32, 32))
            val geometry = scene.describe(GlStats())
            assertTrue(geometry.vertices > 0 && geometry.liveGeometryBytes > 0)

            scene.syncChanged(listOf(stroke))
            stroke.sync = null
            drawReady(scene, decodes)
            assertGray("syncChanged froze the future mark", FADED_BLACK, grayAt(32, 32))
            assertSameGeometry(geometry, scene.describe(GlStats()))

            // Deliberately withhold a geometry notification: a metadata-only message must leave
            // the stored mesh/bounds at y=32 instead of rebuilding from this moved model.
            stroke.translate(0.0, -20.0)
            scene.syncChanged(listOf(stroke))
            stroke.sync = FUTURE_MARK
            drawReady(scene, decodes)
            assertGray("syncChanged froze null", 0, grayAt(32, 32))
            assertGray("metadata did not rebuild moved geometry", 255, grayAt(32, 12))
            assertSameGeometry(geometry, scene.describe(GlStats()))
            assertEquals(0, decodes.completed)
        }
    }

    @Test fun syncMessagesNeverCreateAbsentRecords() = withPbuffer {
        withScene { scene, decodes ->
            val removed = strokeAt(20.0)
            val notYetCommitted = strokeAt(44.0)
            val removedMesh = checkNotNull(ItemMesher.mesh(removed))
            val heldMesh = checkNotNull(ItemMesher.mesh(notYetCommitted))
            scene.upsert(removed, removedMesh.parts, removedMesh.bounds)
            drawReady(scene, decodes)
            assertEquals(1, scene.itemCount)
            assertGray("record exists before removal", 0, grayAt(32, 20))

            scene.remove(removed)
            removed.sync = FUTURE_MARK
            notYetCommitted.sync = FUTURE_MARK
            // Hidden/removed and held items can both lack a scene record. This checks that queue
            // boundary only; it does not simulate the editor's layer or front-buffer lifecycle.
            scene.syncChanged(listOf(removed, notYetCommitted))
            drawReady(scene, decodes)
            assertEquals(0, scene.itemCount)
            assertEquals(0, scene.describe(GlStats()).vertices)
            assertGray("removed record stayed absent", 255, grayAt(32, 20))
            assertGray("uncommitted record stayed absent", 255, grayAt(32, 44))

            // A later real upsert is still accepted and captures the latest metadata.
            scene.fade = InkFade(RECORDING_ID, PLAYHEAD_MS)
            scene.upsert(notYetCommitted, heldMesh.parts, heldMesh.bounds)
            notYetCommitted.sync = null
            drawReady(scene, decodes)
            assertEquals(1, scene.itemCount)
            assertGray("only an upsert creates the record", FADED_BLACK, grayAt(32, 44))
            assertGray("other missing record stayed absent", 255, grayAt(32, 20))
        }
    }

    @Test fun failedReplacementBatchPreservesTheDrawnScene() = withPbuffer {
        for (caughtNestedFailure in listOf(false, true)) withScene { scene, decodes ->
            val old = strokeAt(20.0)
            val next = strokeAt(44.0)
            val oldMesh = checkNotNull(ItemMesher.mesh(old))
            val nextMesh = checkNotNull(ItemMesher.mesh(next))
            scene.upsert(old, oldMesh.parts, oldMesh.bounds)
            drawReady(scene, decodes)
            assertGray("old scene is initially drawn", 0, grayAt(32, 20))
            val before = scene.describe(GlStats())
            val failure = IllegalArgumentException("intentional batch failure")
            var committed = 0

            val thrown = runCatching {
                scene.batch(afterCommit = { committed++ }) {
                    scene.reset()
                    if (caughtNestedFailure) {
                        try {
                            scene.batch {
                                scene.upsert(next, nextMesh.parts, nextMesh.bounds)
                                scene.setOrder(listOf(next))
                                throw failure
                            }
                        } catch (caught: IllegalArgumentException) {
                            assertSame(failure, caught)
                        }
                        // Returning normally cannot commit a batch poisoned by its nested work.
                    } else {
                        scene.upsert(next, nextMesh.parts, nextMesh.bounds)
                        scene.setOrder(listOf(next))
                        throw failure
                    }
                }
            }.exceptionOrNull()
            assertNotNull("failed batch must report failure (nested=$caughtNestedFailure)", thrown)
            assertTrue("the original failure is retained", thrown === failure || thrown?.cause === failure)
            assertEquals("failed batch must not start commit effects", 0, committed)
            drawReady(scene, decodes)
            assertSameGeometry(before, scene.describe(GlStats()))
            assertGray("failed reset did not clear the old scene", 0, grayAt(32, 20))
            assertGray("failed upsert did not reach the scene", 255, grayAt(32, 44))

            scene.batch(afterCommit = { committed++ }) {
                scene.reset()
                scene.upsert(next, nextMesh.parts, nextMesh.bounds)
                scene.setOrder(listOf(next))
            }
            assertEquals("a later successful batch still commits", 1, committed)
            drawReady(scene, decodes)
            assertGray("successful reset replaces old contents", 255, grayAt(32, 20))
            assertGray("successful upsert is drawn", 0, grayAt(32, 44))
        }
    }

    @Test fun partsContainersAreFrozenBeforeTheBatchFinishes() = withPbuffer {
        for (entry in PartsEntry.entries) withScene { scene, decodes ->
            val stroke = strokeAt(32.0)
            val mesh = checkNotNull(ItemMesher.mesh(stroke))
            val callerParts = ArrayList(mesh.parts)
            scene.batch {
                when (entry) {
                    PartsEntry.UPSERT -> scene.upsert(stroke, callerParts, mesh.bounds)
                    PartsEntry.WHOLE -> scene.setWetParts(callerParts, mesh.bounds)
                    PartsEntry.SETTLED -> scene.appendWetRun(callerParts, mesh.bounds)
                    PartsEntry.TAIL -> scene.setWetTail(callerParts, mesh.bounds)
                }
                // Copying only when the outer batch commits would already be too late.
                callerParts.clear()
            }
            drawReady(scene, decodes)
            assertGray("$entry retained the posted parts", 0, grayAt(32, 32))
            val stats = scene.describe(GlStats())
            if (entry == PartsEntry.UPSERT) {
                assertEquals(1, stats.items)
                assertTrue(stats.vertices > 0)
                assertEquals(0, stats.wetVertices)
            } else {
                assertEquals(0, stats.items)
                assertTrue("$entry retained wet vertices", stats.wetVertices > 0)
            }
        }
    }

    @Test fun reservationsFollowEachResetWithoutGrowingForDiscardedGeometry() = withPbuffer {
        for (resetCount in listOf(1, 2)) withScene { scene, decodes ->
            val old = strokeAt(16.0)
            scene.upsert(old, densePartsAt(16.0), Rect(8.0, 12.0, 48.0, 8.0))
            drawReady(scene, decodes)
            val before = scene.describe(GlStats())
            assertEquals(DENSE_VERTICES, before.vertices)
            assertEquals(8_192, before.vertexCapacity)
            assertGray("dense old contents are drawn", 0, grayAt(32, 16))

            scene.batch {
                repeat(resetCount) { step ->
                    val y = 32.0 + step * 16.0
                    val next = strokeAt(y)
                    scene.reset()
                    scene.upsert(next, densePartsAt(y), Rect(8.0, y - 4.0, 48.0, 8.0))
                    scene.setOrder(listOf(next))
                }
            }
            drawReady(scene, decodes)
            val after = scene.describe(GlStats())
            assertEquals("only the final reset segment remains", 1, after.items)
            assertEquals(DENSE_VERTICES, after.vertices)
            assertEquals(before.indices, after.indices)
            assertEquals("$resetCount reset(s): reserve must exclude discarded vertices",
                before.vertexCapacity, after.vertexCapacity)
            assertEquals(before.indexCapacity, after.indexCapacity)
            assertEquals(before.geometryBytes, after.geometryBytes)
            assertGray("reset removed old pixels", 255, grayAt(32, 16))
            if (resetCount == 2) assertGray("second reset removed the first segment", 255, grayAt(32, 32))
            assertGray("final segment is actually drawn", 0, grayAt(32, if (resetCount == 1) 32 else 48))
        }
    }

    private enum class PartsEntry { UPSERT, WHOLE, SETTLED, TAIL }

    /** Repeated opaque quads give an exact vertex load without involving stroke simplification. */
    private fun densePartsAt(y: Double): List<MeshPart> {
        val builder = MeshBuilder(DENSE_VERTICES, DENSE_VERTICES / 4 * 6)
        repeat(DENSE_VERTICES / 4) { builder.rect(8.0, y - 4.0, 48.0, 8.0) }
        val mesh = builder.build()
        check(mesh.vertexCount == DENSE_VERTICES)
        return listOf(MeshPart(mesh, Rgba(0, 0, 0), InkPass.OPAQUE))
    }

    private fun strokeAt(y: Double) = Stroke(
        Tool.PEN,
        ToolDefaults.configFor(Tool.PEN).copy(rgba = Rgba(0, 0, 0), baseWidth = 8.0),
        straight = true,
    ).apply {
        addSample(Sample(8.0, y, 1.0, 0.0))
        addSample(Sample(56.0, y, 1.0, 10.0))
    }

    private data class ImageMutation(val field: String, val expected: List<Int>, val mutate: (ImageItem) -> Unit)

    private fun assertSameGeometry(before: GlStats, after: GlStats) {
        assertEquals(before.items, after.items)
        assertEquals(before.vertices, after.vertices)
        assertEquals(before.indices, after.indices)
        assertEquals(before.vertexCapacity, after.vertexCapacity)
        assertEquals(before.indexCapacity, after.indexCapacity)
        assertEquals(before.liveGeometryBytes, after.liveGeometryBytes)
        assertEquals(before.geometryBytes, after.geometryBytes)
        assertEquals(before.wetVertices, after.wetVertices)
    }

    private fun quadrantImage(files: MutableList<File>, offset: Int): ImageData {
        val file = File.createTempFile("canvas-scene-", ".png", PenTestRunner.instance.targetContext.cacheDir)
        files += file
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until 16) for (x in 0 until 16) {
                val gray = offset + (if (x < 8) 0 else 64) + (if (y < 8) 0 else 128)
                bitmap.setPixel(x, y, Color.rgb(gray, gray, gray))
            }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return ImageData(file, 16, 16)
    }

    private fun imageProbes() = listOf(grayAt(18, 18), grayAt(38, 18), grayAt(18, 38), grayAt(38, 38))

    private fun grayAt(x: Int, y: Int): Int {
        val bytes = ByteBuffer.allocateDirect(4)
        GLES30.glReadPixels(x, SIZE - 1 - y, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
        assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
        val red = bytes.get(0).toInt() and 255
        assertTrue(abs(red - (bytes.get(1).toInt() and 255)) <= COLOR_TOLERANCE)
        assertTrue(abs(red - (bytes.get(2).toInt() and 255)) <= COLOR_TOLERANCE)
        return red
    }

    private fun assertGray(label: String, expected: Int, actual: Int) {
        assertTrue("$label: expected $expected, got $actual", abs(expected - actual) <= COLOR_TOLERANCE)
    }

    /** Decode work runs only when the test advances it; no executor or timing race is involved. */
    private class Decodes {
        val pending = ArrayDeque<Runnable>()
        var completed = 0
            private set

        fun submit(work: Runnable) {
            check(pending.size < MAX_DECODE_TASKS) { "Unexpected decode queue growth" }
            pending.addLast(work)
        }

        fun finishPending() {
            val count = pending.size
            repeat(count) {
                check(completed < MAX_DECODE_TASKS) { "Unexpected repeated image decode" }
                pending.removeFirst().run()
                completed++
            }
            check(pending.isEmpty()) { "Decode work unexpectedly scheduled more decode work" }
        }
    }

    private fun drawReady(scene: CanvasScene, decodes: Decodes) {
        repeat(MAX_DECODE_PASSES) {
            drawScene(scene)
            if (decodes.pending.isEmpty()) {
                assertEquals("all requested image decodes completed", 0, scene.textures.pendingCount)
                return
            }
            decodes.finishPending()
        }
        fail("Image did not settle within $MAX_DECODE_PASSES controlled draws")
    }

    private fun drawScene(scene: CanvasScene) {
        GLES30.glViewport(0, 0, SIZE, SIZE)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_STENCIL_TEST)
        GLES30.glColorMask(true, true, true, true)
        GLES30.glDepthMask(true)
        GLES30.glStencilMask(0xff)
        GLES30.glClearColor(1f, 1f, 1f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT or GLES30.GL_STENCIL_BUFFER_BIT)
        scene.drawContent(FRAME)
        assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
    }

    private fun withScene(block: (CanvasScene, Decodes) -> Unit) {
        val store = GeometryStore()
        val scene = CanvasScene(store)
        val decodes = Decodes()
        try {
            scene.decodeOn = decodes::submit
            scene.onContextCreated(1)
            block(scene, decodes)
        } finally {
            decodes.pending.clear() // Unstarted tasks own no decoded bitmap.
            // Final release also recycles any unreceived bitmap left by a failed draw/assertion.
            scene.textures.release()
            // A later context notification cannot reopen the closed cache.
            scene.textures.onContextCreated(2)
            store.release()
            // The enclosing EGL context owns the scene's other programs and transient buffers.
        }
    }

    /** Same ES3/RGBA8/stencil pbuffer requirements as NightInkRenderTest, with partial-setup cleanup. */
    private fun withPbuffer(block: () -> Unit) {
        check(EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT) { "Test thread already has an EGL context" }
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertNotEquals(EGL14.EGL_NO_DISPLAY, display)
        var initialized = false
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            val versions = IntArray(2)
            initialized = EGL14.eglInitialize(display, versions, 0, versions, 1)
            assertTrue(initialized)
            assertTrue(EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API))
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            assertTrue(EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_STENCIL_SIZE, 8, EGL14.EGL_NONE,
            ), 0, configs, 0, 1, count, 0))
            assertTrue(count[0] > 0)
            val config = checkNotNull(configs[0])
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            assertNotEquals(EGL14.EGL_NO_CONTEXT, context)
            surface = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, SIZE, EGL14.EGL_HEIGHT, SIZE, EGL14.EGL_NONE), 0)
            assertNotEquals(EGL14.EGL_NO_SURFACE, surface)
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
            GLES30.glDisable(GLES30.GL_DITHER)
            block()
        } finally {
            if (initialized) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            EGL14.eglReleaseThread()
        }
    }

    companion object {
        private const val SIZE = 64
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x40
        private const val COLOR_TOLERANCE = 3
        private const val RECORDING_ID = 7
        private const val PLAYHEAD_MS = 100
        private const val FADED_BLACK = 191
        private const val MAX_DECODE_PASSES = 3
        private const val MAX_DECODE_TASKS = 4
        private const val DENSE_VERTICES = 6_000
        private val FUTURE_MARK = SyncMark(RECORDING_ID, 1_000)
        private val FRAME = FrameState(1.0, 0.0, 0.0, SIZE, SIZE, CanvasBackground(), Rgba(255, 255, 255))
    }
}
