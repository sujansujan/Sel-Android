package io.github.selclip

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (!raw.isNullOrBlank()) {
            val service = SelAccessibilityService.instance
            if (service != null) {
                service.showFromExternalText(raw)
            } else {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("sel", raw))
                Toast.makeText(
                    this,
                    "Copied. Enable [sel] in Accessibility Settings for overlay.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
