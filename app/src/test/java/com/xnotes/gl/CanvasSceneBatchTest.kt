package com.xnotes.gl

import android.os.SharedMemory
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InkPass
import com.xnotes.core.infinite.MeshBuilder
import com.xnotes.core.infinite.MeshPart
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

/** Exercises the real scene producer and CPU consumer without creating a GL context or drawing. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class CanvasSceneBatchTest {
    @Test fun throwingBatchDoesNotPublishItsPartialResetOrUpsert() = withScene { f ->
        val old = strokeAt(20.0)
        val next = strokeAt(40.0)
        f.put(old)
        f.drain()
        val oldRecord = f.record(old)
        val before = geometry(f.store)
        val failure = IllegalArgumentException("intentional producer failure")

        val thrown = runCatching {
            f.scene.batch {
                f.scene.reset()
                f.put(next)
                f.scene.setOrder(listOf(next))
                throw failure
            }
        }.exceptionOrNull()
        assertSame(failure, thrown)
        f.drain()

        assertSame("failed reset must leave the old record intact", oldRecord, f.record(old))
        assertNull(f.record(next))
        assertEquals(1, f.scene.itemCount)
        assertEquals(before, geometry(f.store))

        // The failed producer scope must also release its staging state for the next operation.
        f.scene.batch { f.scene.reset(); f.put(next); f.scene.setOrder(listOf(next)) }
        f.drain()
        assertNull(f.record(old))
        assertNotNull(f.record(next))
        assertEquals(1, f.scene.itemCount)
    }

    @Test fun caughtNestedFailureStillRejectsTheWholeOuterBatch() = withScene { f ->
        val old = strokeAt(20.0)
        val inner = strokeAt(40.0)
        val afterCatch = strokeAt(60.0)
        f.put(old)
        f.drain()
        val oldRecord = f.record(old)
        val before = geometry(f.store)
        val failure = IllegalArgumentException("intentional nested failure")

        val thrown = runCatching {
            f.scene.batch {
                f.scene.reset()
                try {
                    f.scene.batch { f.put(inner); throw failure }
                } catch (caught: IllegalArgumentException) {
                    assertSame(failure, caught)
                }
                f.put(afterCatch)
                f.scene.setOrder(listOf(afterCatch, inner))
            }
        }.exceptionOrNull()
        f.drain()

        assertNotNull("catching the nested exception must not commit its outer batch", thrown)
        assertTrue(thrown === failure || thrown?.cause === failure)
        assertSame(oldRecord, f.record(old))
        assertNull(f.record(inner))
        assertNull(f.record(afterCatch))
        assertEquals(1, f.scene.itemCount)
        assertEquals(before, geometry(f.store))
    }

    @Test fun allPartsEntrypointsOwnTheirContainersBeforeTheBatchEnds() {
        for (entry in PartsEntry.entries) withScene { f ->
            val item = strokeAt(32.0)
            val callerParts = ArrayList(partsAt(32.0, 4))
            f.scene.batch {
                when (entry) {
                    PartsEntry.UPSERT -> f.scene.upsert(item, callerParts, boundsAt(32.0))
                    PartsEntry.WHOLE -> f.scene.setWetParts(callerParts, boundsAt(32.0))
                    PartsEntry.SETTLED -> f.scene.appendWetRun(callerParts, boundsAt(32.0))
                    PartsEntry.TAIL -> f.scene.setWetTail(callerParts, boundsAt(32.0))
                }
                callerParts.clear()
            }
            f.drain()

            val store = when (entry) {
                PartsEntry.UPSERT -> f.store
                PartsEntry.SETTLED -> field<GeometryStore>(f.scene, "wetStore")
                PartsEntry.WHOLE, PartsEntry.TAIL -> field<GeometryStore>(f.scene, "tailStore")
            }
            assertEquals("$entry retains the queued quad's vertices", 4, store.usedVertices)
            assertEquals("$entry retains the queued quad's indices", 6, store.usedIndices)
            assertEquals(if (entry == PartsEntry.UPSERT) 1 else 0, f.scene.itemCount)
            val parts = when (entry) {
                PartsEntry.UPSERT -> field<List<Any>>(checkNotNull(f.record(item)), "parts")
                PartsEntry.SETTLED -> field<List<Any>>(f.scene, "wetParts")
                PartsEntry.WHOLE, PartsEntry.TAIL -> field<List<Any>>(f.scene, "tailParts")
            }
            assertEquals("$entry consumer built a real part", 1, parts.size)
        }
    }

    @Test fun reservationsFollowSingleRepeatedAndConsecutiveResetsAndPreserveFinalOrder() {
        for (sequence in ResetSequence.entries) withScene { f ->
            val old = strokeAt(16.0)
            val discarded = strokeAt(32.0)
            val first = strokeAt(48.0)
            val second = strokeAt(64.0)
            f.put(old, 6_000)
            f.drain()
            val before = geometry(f.store)
            assertEquals(6_000, before.vertices)
            assertEquals(8_192, before.vertexCapacity)

            f.scene.batch {
                f.scene.reset()
                when (sequence) {
                    ResetSequence.SINGLE -> Unit
                    ResetSequence.TWO_SEGMENTS -> {
                        f.put(discarded, 6_000)
                        f.scene.setOrder(listOf(discarded))
                        f.scene.reset()
                    }
                    ResetSequence.CONSECUTIVE -> {
                        f.scene.reset()
                        f.scene.reset()
                    }
                }
                f.put(first, 3_000)
                f.put(second, 3_000)
                f.scene.setOrder(listOf(second, first), activeLayerEnd = 0)
            }
            f.drain()

            assertNull("$sequence discarded old contents", f.record(old))
            assertNull("$sequence discarded intermediate contents", f.record(discarded))
            assertEquals(2, f.scene.itemCount)
            assertEquals("$sequence must not reserve old/discarded geometry", before, geometry(f.store))
            val firstRecord = checkNotNull(f.record(first))
            val secondRecord = checkNotNull(f.record(second))
            assertSame(first, field<CanvasItem>(firstRecord, "item"))
            assertSame(second, field<CanvasItem>(secondRecord, "item"))
            assertEquals(1, field<Int>(firstRecord, "z"))
            assertEquals(0, field<Int>(secondRecord, "z"))
            assertEquals(0, field<Int?>(f.scene, "activeLayerEnd"))
        }
    }

    private enum class PartsEntry { UPSERT, WHOLE, SETTLED, TAIL }
    private enum class ResetSequence { SINGLE, TWO_SEGMENTS, CONSECUTIVE }

    private data class Geometry(val vertices: Int, val indices: Int, val vertexCapacity: Int, val indexCapacity: Int)

    private fun geometry(store: GeometryStore) = Geometry(
        store.usedVertices, store.usedIndices, store.vertexCapacity, store.indexCapacity,
    )

    private class Fixture {
        val scene = CanvasScene()
        val store = field<GeometryStore>(scene, "store")

        init { scene.decodeOn = { error("Batch fixture must not decode images") } }

        fun put(item: Stroke, vertices: Int = 4) {
            val y = item.yAt(0)
            scene.upsert(item, partsAt(y, vertices), boundsAt(y))
        }

        fun drain() {
            CanvasScene::class.java.getDeclaredMethod("drainEdits").apply { isAccessible = true }.invoke(scene)
        }

        fun record(item: CanvasItem): Any? = field<Map<CanvasItem, Any>>(scene, "records")[item]

        fun close() {
            for (name in listOf("store", "wetStore", "tailStore")) {
                val bufferStore = field<GeometryStore>(scene, name)
                val mapped = field<MutableSet<ByteBuffer>>(bufferStore, "mapped")
                mapped.toList().forEach(SharedMemory::unmap)
                mapped.clear()
            }
        }
    }

    private fun withScene(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { test(fixture) } finally { fixture.close() }
    }

    companion object {
        private fun strokeAt(y: Double) = Stroke(
            Tool.PEN, ToolDefaults.configFor(Tool.PEN).copy(rgba = Rgba(0, 0, 0), baseWidth = 8.0),
        ).apply {
            addSample(Sample(8.0, y, 1.0, 0.0))
            addSample(Sample(56.0, y, 1.0, 10.0))
        }

        private fun boundsAt(y: Double) = Rect(8.0, y - 4.0, 48.0, 8.0)

        /** Exact loads use repeated real opaque quads, independent of stroke simplification. */
        private fun partsAt(y: Double, vertices: Int): List<MeshPart> {
            require(vertices > 0 && vertices % 4 == 0)
            val builder = MeshBuilder(vertices, vertices / 4 * 6)
            repeat(vertices / 4) { builder.rect(8.0, y - 4.0, 48.0, 8.0) }
            val mesh = builder.build()
            check(mesh.vertexCount == vertices)
            return listOf(MeshPart(mesh, Rgba(0, 0, 0), InkPass.OPAQUE))
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> field(owner: Any, name: String): T =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
    }
}
