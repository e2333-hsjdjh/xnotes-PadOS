package com.xnotes.platform

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The touch-down check answers from the clip description, never the clip's contents. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SystemClipboardTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val clipboard get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Test fun anImageOrUriClipMayHoldAnImage() {
        clipboard.setPrimaryClip(ClipData(ClipDescription("shot", arrayOf("image/png")),
            ClipData.Item(Uri.parse("content://test/shot.png"))))
        assertTrue(SystemClipboard.mayHoldImage(context))
        clipboard.setPrimaryClip(ClipData(ClipDescription("link", arrayOf(ClipDescription.MIMETYPE_TEXT_URILIST)),
            ClipData.Item(Uri.parse("content://test/unknown"))))
        assertTrue(SystemClipboard.mayHoldImage(context))
    }

    @Test fun textIsNotCountedEvenWhenItIsSvgSource() {
        clipboard.setPrimaryClip(ClipData.newPlainText("note", "hello"))
        assertFalse(SystemClipboard.mayHoldImage(context))
        // Telling SVG source from other text means reading it, which the touch-down path must not.
        clipboard.setPrimaryClip(ClipData.newPlainText("svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>"))
        assertFalse(SystemClipboard.mayHoldImage(context))
    }

    @Test fun anEmptyClipboardHoldsNothing() {
        clipboard.clearPrimaryClip()
        assertFalse(SystemClipboard.mayHoldImage(context))
    }
}
