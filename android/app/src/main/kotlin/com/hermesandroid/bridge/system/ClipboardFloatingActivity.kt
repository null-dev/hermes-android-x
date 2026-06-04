package com.hermesandroid.bridge.system

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

/**
 * Invisible 1×1 overlay activity that briefly gains foreground/focus status so Android
 * allows clipboard access from a background process (Android 10+ restriction).
 *
 * Lifecycle: onCreate → addOverlay (unfocusable) → makeFocusable → onGlobalLayout fires
 *            → readClipboard → deliver to ClipboardLatch → removeOverlay → finish()
 */
class ClipboardFloatingActivity : AppCompatActivity() {

    private lateinit var wm: WindowManager
    private lateinit var overlay: View
    private var attached = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addOverlay()
        overlay.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                overlay.viewTreeObserver.removeOnGlobalLayoutListener(this)
                try {
                    readAndDeliver()
                } finally {
                    removeOverlay()
                    finish()
                }
            }
        })
    }

    private fun addOverlay() {
        overlay = View(this)
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply { x = 0; y = 0 }
        wm.addView(overlay, params)
        attached = true
        // Remove FLAG_NOT_FOCUSABLE so the window can receive focus — this is what
        // causes onGlobalLayout to fire and also what grants clipboard access.
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        wm.updateViewLayout(overlay, params)
    }

    private fun readAndDeliver() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
        ClipboardLatch.deliver(text)
    }

    private fun removeOverlay() {
        if (attached) {
            try { wm.removeViewImmediate(overlay) } catch (_: Exception) {}
            attached = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
    }

    companion object {
        fun intent(context: Context) = Intent(context, ClipboardFloatingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        }
    }
}
