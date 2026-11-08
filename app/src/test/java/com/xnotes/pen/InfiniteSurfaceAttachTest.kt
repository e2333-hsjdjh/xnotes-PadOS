package com.xnotes.pen

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class InfiniteSurfaceAttachTest : InfiniteSurfaceAttachContract() {
    @Before fun initializeNoEglHandles() {
        // ShadowEGL14 omits native initialization of these zero-handle sentinels. This fixture
        // supplies only "no EGL resource" values; it creates no context and simulates no pixels.
        if (EGL14.EGL_NO_DISPLAY == null) EGL14.EGL_NO_DISPLAY = noHandle(EGLDisplay::class.java)
        if (EGL14.EGL_NO_CONTEXT == null) EGL14.EGL_NO_CONTEXT = noHandle(EGLContext::class.java)
        if (EGL14.EGL_NO_SURFACE == null) EGL14.EGL_NO_SURFACE = noHandle(EGLSurface::class.java)
    }

    private fun <T> noHandle(type: Class<T>): T = ReflectionHelpers.callConstructor(
        type, ReflectionHelpers.ClassParameter.from(java.lang.Long.TYPE, 0L),
    )

    override fun runOnMain(block: (Context) -> Unit) = block(RuntimeEnvironment.getApplication())
}
