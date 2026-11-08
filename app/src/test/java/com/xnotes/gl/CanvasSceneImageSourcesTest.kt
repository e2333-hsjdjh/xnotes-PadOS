package com.xnotes.gl

import android.os.SharedMemory
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer

/** Real scene source membership and queued requests, without GL calls or bitmap decoding. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class CanvasSceneImageSourcesTest {
    @Test fun geometryUpsertAndRemovingOneSharedMemberRetainThePendingSource() = withScene { f ->
        val source = source("shared")
        val first = item(source, 0.0)
        val second = item(source, 100.0)
        f.scene.batch {
            f.scene.reset(Any())
            f.put(first)
            f.put(second)
        }
        f.drain()
        f.request(source)
        assertEquals(1, f.work.size)
        assertEquals(1, f.textures.pendingCount)

        first.rect = Rect(20.0, 30.0, 240.0, 160.0)
        f.put(first)
        f.drain()
        f.request(source)
        assertEquals("geometry-only upsert must keep the existing source request", 1, f.work.size)
        assertEquals(1, f.textures.pendingCount)

        f.scene.remove(second)
        f.drain()
        f.request(source)
        assertEquals(1, f.scene.itemCount)
        assertEquals("one remaining member still owns the shared source", 1, f.work.size)
        assertEquals(1, f.textures.pendingCount)

        f.scene.remove(first)
        f.drain()
        f.request(source)
        assertEquals(0, f.scene.itemCount)
        assertEquals(0, f.textures.pendingCount)
        assertEquals("absent sources cannot request a decode", 1, f.work.size)
    }

    @Test fun sameBindingRebuildPreservesTheRequestUntilTheFinalMemberIsRemoved() = withScene { f ->
        val binding = Any()
        val source = source("rebuild")
        val first = item(source, 0.0)
        val replacement = item(source, 100.0)
        f.scene.batch { f.scene.reset(binding); f.put(first) }
        f.drain()
        f.request(source)
        val oldWork = f.work.single()

        f.scene.batch {
            f.scene.reset(binding)
            f.put(replacement)
            f.scene.setOrder(listOf(replacement))
        }
        f.drain()
        f.request(source)
        assertEquals(1, f.scene.itemCount)
        assertEquals("the whole committed update retains this source", 1, f.work.size)
        assertEquals(1, f.textures.pendingCount)

        f.scene.remove(replacement)
        f.drain()
        assertEquals(0, f.textures.pendingCount)
        f.request(source)
        assertEquals(1, f.work.size)

        f.put(replacement)
        f.drain()
        f.request(source)
        assertEquals("a source admitted again gets a fresh request", 2, f.work.size)
        assertEquals(1, f.textures.pendingCount)
        oldWork.run()
        assertEquals("revoked work cannot clear the replacement request", 1, f.textures.pendingCount)
        f.request(source)
        assertEquals(2, f.work.size)
        assertEquals(0, f.decodeCalls)
    }

    @Test fun replacingTheBindingRevokesOldWorkEvenWhenImageDataIsReused() = withScene { f ->
        val source = source("reused")
        val image = item(source, 0.0)
        f.scene.batch { f.scene.reset(Any()); f.put(image) }
        f.drain()
        f.request(source)
        val oldWork = f.work.single()
        assertEquals(1, f.textures.pendingCount)

        f.scene.batch { f.scene.reset(Any()); f.put(image) }
        f.drain()
        assertEquals("document replacement revokes the old pending request", 0, f.textures.pendingCount)
        f.request(source)
        assertEquals(2, f.work.size)
        assertEquals(1, f.textures.pendingCount)

        oldWork.run()
        assertEquals(0, f.decodeCalls)
        assertEquals("late old work cannot settle new-document work", 1, f.textures.pendingCount)
        f.request(source)
        assertEquals(2, f.work.size)
        assertEquals(1, f.scene.itemCount)
    }

    @Test fun changingRasterSourceAndReplacingItWithEmptyGeometryRevokesOldRequests() = withScene { f ->
        val oldSource = source("before-change")
        val nextSource = source("after-change")
        val image = item(oldSource, 0.0)
        f.put(image)
        f.drain()
        f.request(oldSource)
        assertEquals(1, f.textures.pendingCount)

        image.image = nextSource
        f.put(image)
        f.drain()
        assertEquals(0, f.textures.pendingCount)
        f.request(oldSource)
        assertEquals("replaced source cannot request again", 1, f.work.size)
        f.request(nextSource)
        assertEquals(2, f.work.size)
        assertEquals(1, f.textures.pendingCount)

        // The non-image upsert path must also retire the former source when no parts survive.
        f.scene.upsert(image, emptyList(), image.bounds())
        f.drain()
        f.request(nextSource)
        assertEquals(0, f.scene.itemCount)
        assertEquals(0, f.textures.pendingCount)
        assertEquals(2, f.work.size)
        f.work.forEach(Runnable::run)
        assertEquals(0, f.decodeCalls)
    }

    @Test fun failedBatchKeepsOldSourcesAndAnEmptyResetRevokesThem() = withScene { f ->
        val binding = Any()
        val oldSource = source("old")
        val nextSource = source("uncommitted")
        val old = item(oldSource, 0.0)
        val next = item(nextSource, 100.0)
        f.scene.batch { f.scene.reset(binding); f.put(old) }
        f.drain()
        f.request(oldSource)
        val oldWork = f.work.single()
        val failure = IllegalArgumentException("intentional producer failure")

        val thrown = runCatching {
            f.scene.batch {
                f.scene.reset(Any())
                f.put(next)
                throw failure
            }
        }.exceptionOrNull()
        assertSame(failure, thrown)
        f.drain()
        f.request(oldSource)
        f.request(nextSource)
        assertEquals(1, f.scene.itemCount)
        assertEquals("a failed batch neither revokes old nor admits new sources", 1, f.work.size)
        assertEquals(1, f.textures.pendingCount)

        f.scene.reset(binding)
        f.drain()
        f.request(oldSource)
        f.request(nextSource)
        assertEquals(0, f.scene.itemCount)
        assertEquals(0, f.textures.pendingCount)
        assertEquals(1, f.work.size)
        oldWork.run()
        assertEquals(0, f.decodeCalls)
        assertEquals(0, f.textures.pendingCount)
    }

    private class Fixture {
        val scene = CanvasScene()
        var decodeCalls = 0
            private set
        val textures = TextureCache(decode = { _, _ ->
            decodeCalls++
            throw AssertionError("Source-membership fixture must never start a decoder")
        })
        val work = ArrayList<Runnable>()

        init {
            // Keep the production cache implementation; only replace its decoder with a guard.
            // No texture is uploaded, so source eviction has no GPU names to delete.
            scene.textures.close()
            CanvasScene::class.java.getDeclaredField("textures").apply { isAccessible = true }
                .set(scene, textures)
            scene.decodeOn = { error("Fixture must explicitly queue each request") }
        }

        fun put(item: ImageItem) = scene.upsertImage(item, item.bounds())

        fun drain() {
            CanvasScene::class.java.getDeclaredMethod("drainEdits").apply { isAccessible = true }
                .invoke(scene)
        }

        fun request(source: ImageData) {
            assertSame(textures, scene.textures)
            assertEquals(0, scene.textures.textureFor(source, 128) { work.add(it) })
            assertEquals(0, textures.textureCount)
            assertEquals(0L, textures.residentBytes)
        }

        fun close() {
            work.clear() // No executor or thread has started the remaining queued work.
            try { textures.close() } finally {
                for (name in listOf("store", "wetStore", "tailStore")) {
                    val store = field<GeometryStore>(scene, name)
                    val mapped = field<MutableSet<ByteBuffer>>(store, "mapped")
                    mapped.toList().forEach(SharedMemory::unmap)
                    mapped.clear()
                }
            }
        }
    }

    private fun withScene(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { test(fixture) } finally { fixture.close() }
    }

    companion object {
        // The guarded decoder never reads these paths; no image files or bitmap objects are made.
        private fun source(name: String) = ImageData(File("unused-$name.png"), 512, 512)

        private fun item(source: ImageData, x: Double) = ImageItem(source, Rect(x, 0.0, 80.0, 80.0))

        @Suppress("UNCHECKED_CAST")
        private fun <T> field(owner: Any, name: String): T =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
    }
}
