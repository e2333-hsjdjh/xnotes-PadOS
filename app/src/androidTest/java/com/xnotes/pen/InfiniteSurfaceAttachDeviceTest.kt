package com.xnotes.pen

import android.content.Context

/** Runs the same state contracts on Android; target-device pixel acceptance remains separate. */
class InfiniteSurfaceAttachDeviceTest : InfiniteSurfaceAttachContract() {
    override fun runOnMain(block: (Context) -> Unit) {
        val runner = PenTestRunner.instance
        var failure: Throwable? = null
        runner.runOnMainSync {
            try { block(runner.targetContext) } catch (t: Throwable) { failure = t }
        }
        failure?.let { throw it }
    }
}
