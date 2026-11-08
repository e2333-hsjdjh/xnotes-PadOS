package com.xnotes.gl

import com.xnotes.core.model.ImageData
import com.xnotes.platform.ImageDecoder
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBitmapFactory
import java.io.File
import java.util.concurrent.RejectedExecutionException

/** Real TextureCache dispatch/terminal paths, with no EGL context or successful bitmap upload. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class TextureCacheDispatchTest {
    @Before fun rejectInvalidImageBytes() {
        // Legacy Robolectric otherwise fabricates a 100x100 image for malformed input.
        ShadowBitmapFactory.setAllowInvalidImageData(false)
    }

    @After fun restoreBitmapFactoryDefault() {
        ShadowBitmapFactory.setAllowInvalidImageData(true)
    }

    @Test fun nullDecodeSettlesPendingAndRetriesAreBoundedUntilAnExplicitRecovery() = withFixture { f ->
        repeat(2) { attempt ->
            f.request()
            assertEquals(attempt + 1, f.submissions)
            assertEquals(1, f.cache.pendingCount)
            repeat(4) { f.request() }
            assertEquals("same pending target is not submitted twice", attempt + 1, f.submissions)
            assertEquals(1, f.work.size)

            f.work.removeFirst().run()
            f.cache.uploadPending()

            assertEquals("null decode must complete its pending request", 0, f.cache.pendingCount)
            assertEquals(0, f.cache.textureCount)
            assertEquals(0L, f.cache.residentBytes)
        }

        repeat(8) {
            f.cache.beginFrame()
            f.request()
            f.cache.uploadPending()
        }
        assertEquals("bad input must not be decoded on every frame", 2, f.submissions)
        assertTrue(f.work.isEmpty())
        assertEquals(0, f.cache.pendingCount)

        // A context change is an explicit recovery boundary, not an automatic per-frame retry.
        f.cache.onContextCreated(2)
        f.request()
        assertEquals(3, f.submissions)
        assertEquals(1, f.work.size)
        f.work.removeFirst().run()
        f.cache.uploadPending()
        assertEquals(0, f.cache.pendingCount)
        assertEquals(0, f.cache.textureCount)
        assertTrue("decode failure must not delete its borrowed source", f.file.isFile)
    }

    @Test fun executorRejectionDoesNotEscapeAndTheNextUploadSettlesIt() = withFixture { f ->
        var rejectedSubmissions = 0
        val escaped = runCatching {
            assertEquals(0, f.cache.textureFor(f.image, 128) {
                rejectedSubmissions++
                throw RejectedExecutionException("fixture executor rejected work")
            })
        }.exceptionOrNull()

        assertNull("ordinary executor rejection is a failed request, not a renderer exception", escaped)
        assertEquals(1, rejectedSubmissions)
        f.cache.uploadPending()
        assertEquals(0, f.cache.pendingCount)
        assertEquals(0, f.cache.textureCount)
        assertEquals(0L, f.cache.residentBytes)

        f.request()
        repeat(4) { f.request() }
        assertEquals("settled rejection permits one bounded retry", 1, f.submissions)
        assertEquals(1, f.work.size)
        f.work.removeFirst().run()
        f.cache.uploadPending()
        assertEquals(0, f.cache.pendingCount)
        assertTrue(f.file.isFile)
    }

    private class Fixture {
        val file = File.createTempFile("texture-dispatch-", ".invalid", RuntimeEnvironment.getApplication().cacheDir)
        val image = ImageData(file, 512, 512)
        val cache = TextureCache()
        val work = ArrayDeque<Runnable>()
        var submissions = 0
            private set

        init {
            file.writeText("This is deliberately not an encoded image.")
            cache.onContextCreated(1)
            cache.retainSources(Any(), listOf(image))
        }

        fun assertInvalidSource() {
            val result = ImageDecoder.decodeSampledFile(file.path, 128, 128)
            try {
                assertNull("fixture must exercise the actual decoder's null path", result)
            } finally { result?.recycle() }
        }

        fun request() {
            assertEquals(0, cache.textureFor(image, 128) {
                submissions++
                work.addLast(it)
            })
        }

        fun close() {
            work.clear() // No executor/thread has started these remaining tasks.
            try { cache.release() } finally { file.delete() }
        }
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            fixture.assertInvalidSource()
            test(fixture)
        } finally { fixture.close() }
    }
}
