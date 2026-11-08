package com.xnotes.gl

import android.opengl.GLES20
import android.os.SharedMemory
import com.xnotes.core.infinite.InkPass
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.infinite.LiftTransform
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import java.nio.ByteBuffer

/**
 * Runs the real scene/target lifecycle with deterministic GLES names and successful shader status.
 * No EGL context or pixels are produced: these are cache/CPU-state regressions, not GPU validation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], shadows = [CanvasHaloInvalidationTest.Gles::class])
class CanvasHaloInvalidationTest {
    @Before fun resetGles() = Gles.reset()

    @Test fun contextReplacementRevokesBothHaloKeysAndPreservesCpuGeometryAndDrag() = withScene { scene, store ->
        val before = cpuState(scene, store)
        val target = field<GlowTarget>(scene, "glowTarget")
        assertTrue(target.texture(target.layerIndex) != 0)

        scene.onContextCreated(2)

        assertInvalidated(scene)
        assertEquals(0, target.texture(target.layerIndex))
        assertFalse(target.ready)
        assertCpuState(before, scene, store)
        // The preserved records can actually take the normal draw path again.
        scene.drawContent(FRAME)
        assertCached(scene)
        assertCpuState(before, scene, store)
    }

    @Test fun contextReplacementRevokesCachesEvenWhenTheNewInkShaderCannotBeCreated() = withScene { scene, store ->
        val before = cpuState(scene, store)
        Gles.failShaderCreation = true

        scene.onContextCreated(2)

        assertNull(field<Any?>(scene, "ink"))
        assertInvalidated(scene)
        assertCpuState(before, scene, store)
        // Failure is injected at shader creation, before any draw can repair stale keys.
        assertTrue(Gles.failedShaderCreations > 0)
    }

    @Test fun resizeRevokesCachesBeforeMissingInkReturnsAndSurvivesAnUnpaintedSizeRoundTrip() = withScene { scene, store ->
        val before = cpuState(scene, store)
        val target = field<GlowTarget>(scene, "glowTarget")
        val firstTexture = target.texture(target.layerIndex)
        setField(scene, "ink", null)

        scene.drawContent(FRAME.copy(widthPx = 160, heightPx = 160))

        assertEquals(80, target.bufferWidth)
        assertNotEquals(firstTexture, target.texture(target.layerIndex))
        assertInvalidated(scene)
        assertCpuState(before, scene, store)
        val intermediateTexture = target.texture(target.layerIndex)

        scene.drawContent(FRAME)

        assertEquals(96, target.bufferWidth)
        assertNotEquals(intermediateTexture, target.texture(target.layerIndex))
        assertInvalidated(scene)
        assertCpuState(before, scene, store)
    }

    @Test fun sameRoundedBufferSizeDoesNotRevokeCachesOnAnEarlyReturn() = withScene(
        FRAME.copy(widthPx = 191, heightPx = 191),
    ) { scene, store ->
        val before = cpuState(scene, store)
        val target = field<GlowTarget>(scene, "glowTarget")
        val texture = target.texture(target.layerIndex)
        val committedKey = field<String>(scene, "haloKey")
        val liftedKey = field<String>(scene, "liftHaloKey")
        val allocations = Gles.framebufferAllocations
        setField(scene, "ink", null)

        // 191 and 192 viewport pixels both use a 96-pixel glow buffer. The deliberately absent
        // ink program prevents the normal per-view halo rebuild from obscuring target ownership.
        scene.drawContent(FRAME)

        assertEquals(texture, target.texture(target.layerIndex))
        assertEquals(allocations, Gles.framebufferAllocations)
        assertEquals(committedKey, field<String>(scene, "haloKey"))
        assertEquals(liftedKey, field<String>(scene, "liftHaloKey"))
        assertEquals(LIFT, field<LiftTransform>(scene, "liftHaloAt"))
        assertCpuState(before, scene, store)
    }

    @Test fun targetOnlyRecreatesForChangedRoundedSizeOrReleasedStorageInItsOwnContext() {
        val target = GlowTarget()
        try {
            target.onContextCreated(1)
            target.resize(191, 191, 1)
            val first = target.texture(target.layerIndex)
            assertTrue(first != 0)
            assertEquals(96, target.bufferWidth)
            val allocations = Gles.framebufferAllocations

            target.resize(192, 192, 1)
            assertEquals(first, target.texture(target.layerIndex))
            assertEquals(allocations, Gles.framebufferAllocations)

            target.resize(193, 193, 1)
            val resized = target.texture(target.layerIndex)
            assertNotEquals(first, resized)
            assertEquals(97, target.bufferWidth)
            assertEquals(allocations + 1, Gles.framebufferAllocations)

            target.resize(64, 64, 2)
            assertEquals(resized, target.texture(target.layerIndex))
            assertEquals(97, target.bufferWidth)
            assertEquals(allocations + 1, Gles.framebufferAllocations)

            target.release()
            assertFalse(target.ready)
            target.resize(193, 193, 1)
            assertTrue(target.ready)
            assertNotEquals(resized, target.texture(target.layerIndex))
            assertEquals(allocations + 2, Gles.framebufferAllocations)
        } finally { target.release() }
    }

    private data class CpuState(
        val revision: Int,
        val vertices: Int,
        val indices: Int,
        val records: Map<CanvasItem, Any>,
        val vertexMirror: ByteBuffer,
        val indexMirror: ByteBuffer,
        val lifted: List<CanvasItem>,
        val lift: LiftTransform,
    )

    private fun cpuState(scene: CanvasScene, store: GeometryStore) = CpuState(
        field(scene, "revision"), store.usedVertices, store.usedIndices,
        field<Map<CanvasItem, Any>>(scene, "records").toMap(),
        field(store, "vertexMirror"), field(store, "indexMirror"),
        field<List<CanvasItem>>(scene, "lifted").toList(), field(scene, "liftAt"),
    )

    private fun assertCpuState(expected: CpuState, scene: CanvasScene, store: GeometryStore) {
        assertEquals(expected.revision, field<Int>(scene, "revision"))
        assertEquals(expected.vertices, store.usedVertices)
        assertEquals(expected.indices, store.usedIndices)
        assertSame(expected.vertexMirror, field<ByteBuffer>(store, "vertexMirror"))
        assertSame(expected.indexMirror, field<ByteBuffer>(store, "indexMirror"))
        val records = field<Map<CanvasItem, Any>>(scene, "records")
        assertEquals(expected.records.size, scene.itemCount)
        expected.records.forEach { (item, record) -> assertSame(record, records[item]) }
        val lifted = field<List<CanvasItem>>(scene, "lifted")
        assertEquals(expected.lifted.size, lifted.size)
        expected.lifted.indices.forEach { assertSame(expected.lifted[it], lifted[it]) }
        assertEquals(expected.lift, field<LiftTransform>(scene, "liftAt"))
    }

    private fun assertInvalidated(scene: CanvasScene) {
        assertNull(field<String?>(scene, "haloKey"))
        assertNull(field<String?>(scene, "liftHaloKey"))
        assertEquals(LiftTransform.NONE, field<LiftTransform>(scene, "liftHaloAt"))
    }

    private fun assertCached(scene: CanvasScene) {
        assertNotNull("normal draw built the committed halo cache", field<String?>(scene, "haloKey"))
        assertNotNull("normal draw built the lifted halo cache", field<String?>(scene, "liftHaloKey"))
        assertEquals(LIFT, field<LiftTransform>(scene, "liftHaloAt"))
    }

    private fun withScene(frame: FrameState = FRAME, block: (CanvasScene, GeometryStore) -> Unit) {
        val store = GeometryStore()
        val scene = CanvasScene(store)
        try {
            scene.decodeOn = { error("Halo fixture has no images") }
            scene.onContextCreated(1)
            val committed = neon(52.0)
            val lifted = neon(110.0)
            for (stroke in listOf(committed, lifted)) {
                val mesh = checkNotNull(ItemMesher.mesh(stroke))
                assertTrue(mesh.parts.any { it.pass == InkPass.GLOW })
                scene.upsert(stroke, mesh.parts, mesh.bounds)
            }
            scene.setOrder(listOf(committed, lifted))
            scene.setLift(listOf(lifted), LIFT)
            scene.drawContent(frame)
            assertEquals(2, scene.itemCount)
            assertTrue(store.usedVertices > 0)
            assertCached(scene)
            block(scene, store)
        } finally {
            field<GlowTarget>(scene, "glowTarget").release()
            scene.textures.release()
            // These are actual mapped CPU mirrors even though GLES itself is shadowed.
            for (bufferStore in listOf(store, field<GeometryStore>(scene, "wetStore"), field<GeometryStore>(scene, "tailStore"))) {
                bufferStore.release()
                val mapped = field<MutableSet<ByteBuffer>>(bufferStore, "mapped")
                mapped.toList().forEach(SharedMemory::unmap)
                mapped.clear()
            }
        }
    }

    private fun neon(y: Double) = Stroke(
        Tool.PEN,
        ToolDefaults.configFor(Tool.PEN).copy(rgba = Rgba(180, 180, 180), baseWidth = 6.0, neon = true, neonStrength = .7),
        straight = true,
    ).apply {
        addSample(Sample(48.0, y, 1.0, 0.0))
        addSample(Sample(136.0, y, 1.0, 10.0))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T

    private fun setField(owner: Any, name: String, value: Any?) {
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    }

    /** GLES30 inherits these static entry points from GLES20; no second GLES30 shadow is needed. */
    @Implements(GLES20::class)
    class Gles {
        companion object {
            private var nextName = 1
            var framebufferAllocations = 0
                private set
            var failShaderCreation = false
            var failedShaderCreations = 0
                private set

            @JvmStatic @Resetter fun reset() {
                nextName = 1
                framebufferAllocations = 0
                failShaderCreation = false
                failedShaderCreations = 0
            }

            private fun names(count: Int, out: IntArray, offset: Int) {
                repeat(count) { out[offset + it] = nextName++ }
            }

            @JvmStatic @Implementation fun glGenFramebuffers(count: Int, out: IntArray, offset: Int) {
                framebufferAllocations++
                names(count, out, offset)
            }
            @JvmStatic @Implementation fun glGenTextures(count: Int, out: IntArray, offset: Int) = names(count, out, offset)
            @JvmStatic @Implementation fun glGenBuffers(count: Int, out: IntArray, offset: Int) = names(count, out, offset)
            @JvmStatic @Implementation fun glCreateShader(type: Int): Int {
                if (failShaderCreation) { failedShaderCreations++; return 0 }
                check(type == GLES20.GL_VERTEX_SHADER || type == GLES20.GL_FRAGMENT_SHADER)
                return nextName++
            }
            @JvmStatic @Implementation fun glCreateProgram(): Int = nextName++
            @JvmStatic @Implementation fun glGetShaderiv(shader: Int, parameter: Int, out: IntArray, offset: Int) {
                if (parameter == GLES20.GL_COMPILE_STATUS) out[offset] = 1
            }
            @JvmStatic @Implementation fun glGetProgramiv(program: Int, parameter: Int, out: IntArray, offset: Int) {
                if (parameter == GLES20.GL_LINK_STATUS) out[offset] = 1
            }
            @JvmStatic @Implementation fun glCheckFramebufferStatus(target: Int): Int = GLES20.GL_FRAMEBUFFER_COMPLETE
        }
    }

    companion object {
        private val FRAME = FrameState.EMPTY.copy(widthPx = 192, heightPx = 192)
        private val LIFT = LiftTransform.shift(0.0, 12.0)
    }
}
