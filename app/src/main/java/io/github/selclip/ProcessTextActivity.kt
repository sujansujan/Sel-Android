package io.github.selclip

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Translucent zero-flash universal entry point hooked into Android's native selection menu
 * (`ACTION_PROCESS_TEXT`, e.g. Chrome/Hermit/WebView) and Share sheet (`ACTION_SEND`, e.g.
 * custom-canvas PDF readers like Artefact / PDFCraft) with ZERO clipboard pollution.
 */
class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        val raw = (intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            ?: intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent?.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent?.getStringExtra(Intent.EXTRA_SUBJECT))
            ?.take(8000)
        if (!raw.isNullOrBlank()) {
            val service = SelAccessibilityService.instance
            if (service != null) {
                service.showFromExternalText(raw, isStandalone = true)
            } else {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("sel", raw))
                Toast.makeText(
                    this,
                    "Copied. Enable Sel in Accessibility Settings to show the floating bar.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        finish()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 34) {
            overridePendingTransition(0, 0)
        }
    }
}
