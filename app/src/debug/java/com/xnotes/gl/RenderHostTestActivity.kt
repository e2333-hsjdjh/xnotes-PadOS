package com.xnotes.gl

import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity

/** A real window for rendering instrumentation; absent from release builds. */
class RenderHostTestActivity : ComponentActivity() {
    lateinit var host: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = FrameLayout(this)
        setContentView(host)
    }
}
