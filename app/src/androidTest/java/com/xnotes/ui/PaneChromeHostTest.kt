package com.xnotes.ui

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import com.xnotes.core.geometry.Pt
import com.xnotes.gl.RenderHostTestActivity
import com.xnotes.pen.PenTestRunner
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarPosition
import com.xnotes.ui.theme.XnotesTheme
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PaneChromeHostTest {
    @Test fun floatingToolbarInterceptsWindowStylusBeforeNativeContent() = ComposeHostFixture.run { host ->
        val look = mutableStateOf(ToolbarLook(floating = true))
        var toolbarBounds = Rect.Zero
        var toolbarClicks = 0
        var contentDowns = 0
        lateinit var native: View
        host.setContent {
            XnotesTheme(com.xnotes.ui.theme.Palette.DEFAULT) {
                CompositionLocalProvider(LocalToolbarLook provides look.value) {
                    Column(Modifier.fillMaxSize()) {
                        ToolbarAround(bar = {
                            val thickness = barMetrics(look.value.size).thickness
                            val vertical = look.value.position.vertical
                            Box(Modifier
                                .size(if (vertical) thickness else thickness * 2,
                                    if (vertical) thickness * 2 else thickness)
                                .onGloballyPositioned { toolbarBounds = it.boundsInWindow() }
                                .clickable { toolbarClicks++ })
                        }, onCover = { _, _, _, _ -> }) { floatingBar ->
                            Box(Modifier.fillMaxSize()) {
                                AndroidView(factory = { context ->
                                    object : View(context) {
                                        override fun onTouchEvent(event: MotionEvent): Boolean {
                                            if (event.actionMasked == MotionEvent.ACTION_DOWN) contentDowns++
                                            return true
                                        }
                                    }.also { native = it }
                                }, modifier = Modifier.fillMaxSize())
                                floatingBar()
                            }
                        }
                    }
                }
            }
        }
        host.await("floating toolbar window") {
            host.activity.hasWindowFocus() && toolbarBounds.width > 0 && native.width > 0
        }
        for (position in ToolbarPosition.entries) {
            host.change { look.value = look.value.copy(position = position) }
            host.awaitStableWindowBounds(native)
            val toolbarPoint = host.onMain {
                val screen = IntArray(2).also(native::getLocationOnScreen)
                val window = IntArray(2).also(native::getLocationInWindow)
                val toolbar = toolbarBounds.center
                // Compose bounds are in window coordinates; instrumentation requires screen coordinates.
                Pt((toolbar.x + screen[0] - window[0]).toDouble(),
                    (toolbar.y + screen[1] - window[1]).toDouble())
            }
            val clicksBefore = host.onMain { toolbarClicks }
            val downsBefore = host.onMain { contentDowns }
            injectWindowStylus(toolbarPoint, toolbarPoint)
            host.await("floating $position button clicked") { toolbarClicks == clicksBefore + 1 }
            host.onMain { assertEquals("covered content must not receive DOWN", downsBefore, contentDowns) }
            host.awaitStableWindowBounds(native)
            val contentPoint = host.onMain {
                val screen = IntArray(2).also(native::getLocationOnScreen)
                Pt(screen[0] + native.width / 2.0, screen[1] + native.height / 2.0)
            }
            injectWindowStylus(contentPoint, contentPoint)
            host.await("uncovered native content clicked") { contentDowns == downsBefore + 1 }
            host.onMain { assertEquals(clicksBefore + 1, toolbarClicks) }
        }
    }

    @Test fun toolbarModesKeepNativeParentAndPublishCoverWithoutRemounting() = ComposeHostFixture.run { host ->
        val look = mutableStateOf(ToolbarLook())
        val shown = mutableStateOf(true)
        var factories = 0
        var releases = 0
        var cover = listOf(0.0, 0.0, 0.0, 0.0)
        lateinit var native: FrameLayout
        lateinit var attachments: AttachmentCounter
        host.setContent {
            XnotesTheme(com.xnotes.ui.theme.Palette.DEFAULT) {
                CompositionLocalProvider(LocalToolbarLook provides look.value) {
                    Column(Modifier.fillMaxSize()) {
                        ToolbarAround(bar = {
                            val thickness = barMetrics(look.value.size).thickness
                            Box(if (look.value.position.vertical)
                                Modifier.width(thickness).fillMaxHeight()
                            else Modifier.height(thickness).fillMaxWidth())
                        }, onCover = { l, t, r, b -> cover = listOf(l, t, r, b) },
                            showToolbar = shown.value) { floatingBar ->
                            Box(Modifier.fillMaxSize()) {
                                AndroidView(factory = { context ->
                                    factories++
                                    FrameLayout(context).also { native = it; attachments = AttachmentCounter(it) }
                                }, modifier = Modifier.fillMaxSize(), onRelease = { releases++ })
                                floatingBar()
                            }
                        }
                    }
                }
            }
        }
        host.await("first native host") { factories == 1 && native.isAttachedToWindow && native.width > 0 }
        val parent = host.onMain { native.parent }
        for (position in ToolbarPosition.entries) for (floating in listOf(false, true)) for (show in listOf(false, true)) {
            host.change { look.value = look.value.copy(position = position, floating = floating); shown.value = show }
            host.onMain {
                assertEquals(1, factories)
                assertEquals(0, releases)
                assertEquals(1, attachments.attached)
                assertEquals(0, attachments.detached)
                assertSame(parent, native.parent)
                val expected = if (show && floating)
                    (barMetrics(look.value.size).thickness.value + com.xnotes.ui.theme.Space.s8.value) * native.resources.displayMetrics.density
                else 0f
                val selectedEdge = when (position) {
                    ToolbarPosition.LEFT -> 0
                    ToolbarPosition.TOP -> 1
                    ToolbarPosition.RIGHT -> 2
                    ToolbarPosition.BOTTOM -> 3
                }
                cover.forEachIndexed { i, px -> assertEquals(if (i == selectedEdge) expected.toDouble() else 0.0, px, 0.01) }
                assertTrue(native.width > 0 && native.height > 0)
            }
        }
        host.disposeContent()
        host.onMain { assertEquals(1, releases); assertEquals(1, attachments.detached) }
    }
}

/** A real window injection: callers provide physical screen coordinates, never View-local ones. */
internal fun injectWindowStylus(start: Pt, end: Pt, rawButtons: Int = 0) {
    val runner = PenTestRunner.instance
    val downTime = SystemClock.uptimeMillis()
    fun send(action: Int, point: Pt) {
        val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
        val coords = MotionEvent.PointerCoords().apply {
            x = point.x.toFloat(); y = point.y.toFloat()
            pressure = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) 0f else .5f
        }
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
            arrayOf(pointer), arrayOf(coords), 0, rawButtons, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        try { runner.sendPointerSync(event) } finally { event.recycle() }
    }
    try {
        send(MotionEvent.ACTION_DOWN, start)
        send(MotionEvent.ACTION_MOVE, Pt((start.x + end.x) / 2, (start.y + end.y) / 2))
        send(MotionEvent.ACTION_UP, end)
    } catch (failure: Throwable) {
        runCatching { send(MotionEvent.ACTION_CANCEL, end) }
        throw failure
    }
    runner.waitForIdleSync()
}

/** Shared fixture uses a real window, without starting MainActivity or reading its workspace. */
internal class ComposeHostFixture private constructor(val activity: RenderHostTestActivity) {
    private val runner = PenTestRunner.instance
    private val composition = onMain { ComposeView(activity).also { activity.host.addView(it, FrameLayout.LayoutParams(-1, -1)) } }

    fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        runner.runOnMainSync { result = runCatching(action) }
        return result!!.getOrThrow()
    }

    fun setContent(content: @Composable () -> Unit) = onMain { composition.setContent(content) }

    fun change(action: () -> Unit) {
        onMain(action)
        var frames = 0
        await("composition/layout frame barrier") { ++frames >= 4 }
    }

    /** Sample every requested View in the same real frame, including their animated ancestors. */
    fun awaitStableWindowBounds(vararg views: View) {
        require(views.isNotEmpty())
        var previous: List<ScreenBounds>? = null
        var stableFrames = 0
        await("visible window bounds stable across four frames") {
            val visible = android.graphics.Rect()
            if (!activity.hasWindowFocus() || views.any {
                    !it.isAttachedToWindow || !it.isShown || it.width <= 0 || it.height <= 0 ||
                        !it.getGlobalVisibleRect(visible) || visible.isEmpty
                }) {
                previous = null
                stableFrames = 0
                false
            } else {
                val current = views.map { view ->
                    val screen = IntArray(2).also(view::getLocationOnScreen)
                    ScreenBounds(screen[0], screen[1], view.width, view.height)
                }
                stableFrames = if (current == previous) stableFrames + 1 else 1
                previous = current
                stableFrames >= 4
            }
        }
    }

    private data class ScreenBounds(val left: Int, val top: Int, val width: Int, val height: Int)

    fun await(label: String, condition: () -> Boolean) {
        val done = CountDownLatch(1)
        val deadline = SystemClock.uptimeMillis() + 10_000L
        var failure: Throwable? = null
        onMain {
            val poll = object : Runnable {
                override fun run() {
                    try {
                        if (condition()) {
                            // A frame callback precedes traversal; finish after that traversal.
                            composition.post { done.countDown() }
                        } else if (SystemClock.uptimeMillis() >= deadline) {
                            failure = AssertionError("Timed out: $label")
                            done.countDown()
                        } else composition.postOnAnimation(this)
                    } catch (t: Throwable) { failure = t; done.countDown() }
                }
            }
            composition.postOnAnimation(poll)
        }
        assertTrue(label, done.await(12, TimeUnit.SECONDS))
        failure?.let { throw it }
    }

    fun disposeContent() = onMain { composition.disposeComposition(); activity.host.removeView(composition) }

    companion object {
        fun run(test: (ComposeHostFixture) -> Unit) {
            val runner = PenTestRunner.instance
            val intent = Intent(runner.targetContext, RenderHostTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val activity = runner.startActivitySync(intent) as RenderHostTestActivity
            val host = ComposeHostFixture(activity)
            try { test(host) } finally {
                try { host.disposeContent() } finally {
                    try { host.onMain { activity.finish() } } finally { runner.waitForIdleSync() }
                }
            }
        }
    }
}

internal class AttachmentCounter(view: View) : View.OnAttachStateChangeListener {
    var attached = if (view.isAttachedToWindow) 1 else 0
        private set
    var detached = 0
        private set
    init { view.addOnAttachStateChangeListener(this) }
    override fun onViewAttachedToWindow(v: View) { attached++ }
    override fun onViewDetachedFromWindow(v: View) { detached++ }
}
