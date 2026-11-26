package io.github.selclip

import android.app.Activity
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast

/**
 * Internal-only (not exported) 1x1 invisible fallback invoked ONLY when the user explicitly
 * triggered the Sel shortcut AND no on-screen highlighted text was accessible (e.g. after
 * copying text in Artefact, PDFCraft, Google Messages, or Chrome 158).
 *
 * Strictly READ-ONLY on the clipboard — never writes or modifies the system clipboard.
 */
class ClipboardFallbackActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var dispatched = false
    private val failsafeRunnable = Runnable { readAndFinish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        window?.apply {
            setLayout(1, 1)
            setGravity(Gravity.TOP or Gravity.START)
            setDimAmount(0f)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            attributes = attributes.apply {
                width = 1
                height = 1
                x = 0
                y = 0
                windowAnimations = 0
            }
        }
        handler.postDelayed(failsafeRunnable, 180L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !dispatched) {
            readAndFinish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(failsafeRunnable)
        super.onDestroy()
    }

    private fun readAndFinish() {
        if (dispatched) return
        dispatched = true
        handler.removeCallbacks(failsafeRunnable)

        val clipText = runCatching {
            val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return@runCatching null
            val clip = cm.primaryClip ?: return@runCatching null
            if (clip.itemCount <= 0) return@runCatching null
            clip.getItemAt(0)?.coerceToText(this)?.toString()?.take(8000)?.takeIf { it.isNotBlank() }
        }.getOrNull()

        val service = SelAccessibilityService.instance
        if (service != null && clipText != null) {
            service.showFromExternalText(clipText, isStandalone = true)
        } else {
            Toast.makeText(this, "No selected or copied text found", Toast.LENGTH_SHORT).show()
        }

        finish()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 34) {
            overridePendingTransition(0, 0)
        }
    }
}
