package com.xnotes.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.infinite.InkPass
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.infinite.LiftTransform
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.abs

/** Real ES3 pbuffers, not a window/Surface handoff or target-device compatibility test. */
class CanvasHaloLifecycleTest {
    @Test fun realContextRecreationRebuildsBothHalosWithoutChangingSceneOrLift() = withEgl { egl ->
        withScene { scene ->
            val before = drawAndProbe(scene)
            assertVisibleHalos(before)
            assertNotNull(field<String?>(scene, "haloKey"))
            assertNotNull(field<String?>(scene, "liftHaloKey"))
            val revision = field<Int>(scene, "revision")
            val stats = scene.describe(GlStats())

            // Destroy the actual old context/surface. Reusing a scene with only a changed integer
            // would not reproduce the loss of the texture contents whose keys are being checked.
            egl.recreate()
            scene.onContextCreated(2)
            val target = field<GlowTarget>(scene, "glowTarget")
            assertTrue(target.resize(SIZE, SIZE, 2))
            // Preallocate and explicitly clear the new cache layers: their initial contents are
            // unspecified. The next draw sees an unchanged size, so only context invalidation can
            // stop old CPU keys from treating these known-empty layers as cached halo pixels.
            clearCachedLayers(target)
            assertEquals(LIFT, field<LiftTransform>(scene, "liftAt"))
            assertEquals(revision, field<Int>(scene, "revision"))
            val after = drawAndProbe(scene)
            assertVisibleHalos(after)
            assertSamePixels(before, after)
            assertEquals(stats.items, scene.itemCount)
            assertEquals(stats.vertices, scene.describe(GlStats()).vertices)
            assertEquals(revision, field<Int>(scene, "revision"))
        }
    }

    @Test fun resizeInvalidatesBeforeAnEarlyReturnAndAnUnpaintedSizeRoundTrip() = withEgl {
        withScene { scene ->
            val before = drawAndProbe(scene)
            assertVisibleHalos(before)
            val revision = field<Int>(scene, "revision")
            val program = checkNotNull(field<Any?>(scene, "ink"))
            try {
                // Controlled fault injection, not a claim to reproduce a natural shader failure.
                // Neither intermediate frame can reach the halo key/rebuild paths.
                setField(scene, "ink", null)
                draw(scene, FRAME.copy(widthPx = 160, heightPx = 160))
                assertNull(field<String?>(scene, "haloKey"))
                assertNull(field<String?>(scene, "liftHaloKey"))
                assertEquals(LiftTransform.NONE, field<LiftTransform>(scene, "liftHaloAt"))
                assertEquals(LIFT, field<LiftTransform>(scene, "liftAt"))
                draw(scene, FRAME)
                assertNull(field<String?>(scene, "haloKey"))
                assertNull(field<String?>(scene, "liftHaloKey"))
                clearCachedLayers(field(scene, "glowTarget"))
            } finally {
                setField(scene, "ink", program)
            }
            val after = drawAndProbe(scene)
            assertVisibleHalos(after)
            assertSamePixels(before, after)
            assertEquals(revision, field<Int>(scene, "revision"))
            assertEquals(LIFT, field<LiftTransform>(scene, "liftAt"))
        }
    }

    @Test fun glowResizeReportsOnlyActualBufferRecreation() = withEgl {
        val target = GlowTarget()
        try {
            target.onContextCreated(1)
            assertTrue(target.resize(191, 191, 1))
            assertEquals(96, target.bufferWidth)
            val texture = target.texture(target.layerIndex)
            assertTrue(texture != 0)
            assertFalse("same rounded buffer size retains its pixels", target.resize(192, 192, 1))
            assertEquals(texture, target.texture(target.layerIndex))
            assertTrue(target.resize(193, 193, 1))
            assertEquals(97, target.bufferWidth)
            val resized = target.texture(target.layerIndex)
            assertFalse("a foreign context generation cannot resize", target.resize(64, 64, 2))
            assertEquals(97, target.bufferWidth)
            assertEquals(resized, target.texture(target.layerIndex))
            target.release()
            assertFalse(target.ready)
            assertTrue(target.resize(193, 193, 1))
            assertTrue(target.ready)
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
        } finally {
            // This test never changes its context; these names still belong to the current one.
            target.release()
        }
    }

    private fun withScene(block: (CanvasScene) -> Unit) {
        val store = GeometryStore()
        val scene = CanvasScene(store)
        // The enclosing withEgl destroys every context and its objects, including partial setup.
        // Do not release scene handles here: recreation may succeed but onContextCreated may fail
        // halfway, leaving some names owned by the destroyed context. There are no decoded bitmaps,
        // files, worker threads or posted decode tasks in this fixture that need separate cleanup.
        scene.decodeOn = { error("Halo fixture must not schedule an image decode") }
        scene.onContextCreated(1)
        val committed = neon(COMMITTED_Y)
        val lifted = neon(LIFTED_Y)
        for (stroke in listOf(committed, lifted)) {
            val mesh = checkNotNull(ItemMesher.mesh(stroke))
            assertTrue(mesh.parts.any { it.pass == InkPass.GLOW })
            scene.upsert(stroke, mesh.parts, mesh.bounds)
        }
        scene.setOrder(listOf(committed, lifted))
        scene.setLift(listOf(lifted), LIFT)
        block(scene)
    }

    private fun neon(y: Double) = Stroke(
        Tool.PEN,
        ToolDefaults.configFor(Tool.PEN).copy(rgba = Rgba(180, 180, 180), baseWidth = 6.0, neon = true, neonStrength = .7),
        straight = true,
    ).apply {
        addSample(Sample(48.0, y, 1.0, 0.0))
        addSample(Sample(136.0, y, 1.0, 10.0))
    }

    private data class Pixels(
        val background: Int,
        val committedBody: Int,
        val committedHalo: Int,
        val liftedBody: Int,
        val liftedHalo: Int,
        val originalLiftLocation: Int,
    )

    private fun drawAndProbe(scene: CanvasScene): Pixels {
        draw(scene, FRAME)
        return Pixels(
            grayAt(8, 8), grayAt(96, COMMITTED_Y.toInt()), grayAt(96, COMMITTED_Y.toInt() + HALO_OFFSET),
            grayAt(96, (LIFTED_Y + LIFT.dy).toInt()), grayAt(96, (LIFTED_Y + LIFT.dy).toInt() + HALO_OFFSET),
            grayAt(96, LIFTED_Y.toInt()),
        )
    }

    private fun assertVisibleHalos(pixels: Pixels) {
        assertTrue("committed body must be drawn: $pixels", pixels.committedBody > 160)
        assertTrue("lifted body must be drawn at its translated position: $pixels", pixels.liftedBody > 160)
        // These probes are ten pixels from a six-pixel-wide straight body; neither can pass on
        // body/core rendering alone. A blank baseline cannot pass by comparing with another blank.
        assertTrue("committed outer halo must exceed the background: $pixels", pixels.committedHalo > pixels.background + 8)
        assertTrue("lifted outer halo must exceed the background: $pixels", pixels.liftedHalo > pixels.background + 8)
        assertTrue("the lift must not reset to its original position: $pixels", pixels.liftedBody > pixels.originalLiftLocation + 80)
    }

    private fun assertSamePixels(before: Pixels, after: Pixels) {
        val expected = listOf(before.committedBody, before.committedHalo, before.liftedBody, before.liftedHalo, before.originalLiftLocation)
        val actual = listOf(after.committedBody, after.committedHalo, after.liftedBody, after.liftedHalo, after.originalLiftLocation)
        expected.indices.forEach { i ->
            assertTrue("probe $i: expected ${expected[i]}, got ${actual[i]}", abs(expected[i] - actual[i]) <= 4)
        }
    }

    private fun draw(scene: CanvasScene, frame: FrameState) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, frame.widthPx, frame.heightPx)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_STENCIL_TEST)
        GLES30.glDisable(GLES30.GL_DITHER)
        GLES30.glColorMask(true, true, true, true)
        GLES30.glDepthMask(true)
        GLES30.glStencilMask(0xff)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT or GLES30.GL_STENCIL_BUFFER_BIT)
        scene.drawContent(frame)
        assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
    }

    private fun clearCachedLayers(target: GlowTarget) {
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glColorMask(true, true, true, true)
        for (layer in listOf(target.layerIndex, target.liftLayerIndex)) {
            target.bind(layer)
            assertEquals(GLES30.GL_FRAMEBUFFER_COMPLETE, GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER))
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            val pixel = ByteBuffer.allocateDirect(4)
            GLES30.glReadPixels(0, 0, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixel)
            repeat(4) { assertEquals(0, pixel.get(it).toInt() and 255) }
        }
        target.unbind(SIZE, SIZE)
        assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
    }

    private fun grayAt(x: Int, y: Int): Int {
        val pixel = ByteBuffer.allocateDirect(4)
        GLES30.glReadPixels(x, SIZE - 1 - y, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixel)
        assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
        val gray = pixel.get(0).toInt() and 255
        assertTrue(abs(gray - (pixel.get(1).toInt() and 255)) <= 3)
        assertTrue(abs(gray - (pixel.get(2).toInt() and 255)) <= 3)
        return gray
    }

    private fun withEgl(block: (Egl) -> Unit) {
        check(EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT) { "Test thread already owns an EGL context" }
        Egl().use(block)
    }

    private class Egl : AutoCloseable {
        private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private var initialized = false
        private var config: EGLConfig? = null
        private var context = EGL14.EGL_NO_CONTEXT
        private var surface = EGL14.EGL_NO_SURFACE

        init {
            try {
                assertNotEquals(EGL14.EGL_NO_DISPLAY, display)
                val versions = IntArray(2)
                initialized = EGL14.eglInitialize(display, versions, 0, versions, 1)
                assertTrue(initialized)
                assertTrue(EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API))
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                assertTrue(EGL14.eglChooseConfig(display, intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_STENCIL_SIZE, 8, EGL14.EGL_NONE,
                ), 0, configs, 0, 1, count, 0))
                assertTrue(count[0] > 0)
                config = checkNotNull(configs[0])
                createContext()
            } catch (failure: Throwable) {
                try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        private fun createContext() {
            context = EGL14.eglCreateContext(display, checkNotNull(config), EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            assertNotEquals(EGL14.EGL_NO_CONTEXT, context)
            surface = EGL14.eglCreatePbufferSurface(display, checkNotNull(config),
                intArrayOf(EGL14.EGL_WIDTH, SIZE, EGL14.EGL_HEIGHT, SIZE, EGL14.EGL_NONE), 0)
            assertNotEquals(EGL14.EGL_NO_SURFACE, surface)
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
        }

        fun recreate() {
            assertTrue(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT))
            assertTrue(EGL14.eglDestroySurface(display, surface))
            surface = EGL14.EGL_NO_SURFACE
            assertTrue(EGL14.eglDestroyContext(display, context))
            context = EGL14.EGL_NO_CONTEXT
            createContext()
        }

        override fun close() {
            if (initialized) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                surface = EGL14.EGL_NO_SURFACE
                context = EGL14.EGL_NO_CONTEXT
                EGL14.eglTerminate(display)
                initialized = false
            }
            EGL14.eglReleaseThread()
        }
    }

    companion object {
        private const val SIZE = 192
        private const val HALO_OFFSET = 10
        private const val COMMITTED_Y = 48.0
        private const val LIFTED_Y = 128.0
        private val LIFT = LiftTransform(dx = 12.0, dy = 16.0)
        private val FRAME = FrameState(1.0, 0.0, 0.0, SIZE, SIZE, CanvasBackground(), Rgba(0, 0, 0))

        @Suppress("UNCHECKED_CAST") private fun <T> field(scene: CanvasScene, name: String): T =
            CanvasScene::class.java.getDeclaredField(name).apply { isAccessible = true }.get(scene) as T
        private fun setField(scene: CanvasScene, name: String, value: Any?) {
            CanvasScene::class.java.getDeclaredField(name).apply { isAccessible = true }.set(scene, value)
        }
    }
}
