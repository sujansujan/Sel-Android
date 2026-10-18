package io.github.selclip

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

class ScreenGrabTriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = SelAccessibilityService.instance
        if (service != null) {
            service.triggerScreenGrab(delayMs = 150L)
        } else {
            Toast.makeText(
                this,
                "Enable [sel] in Accessibility Settings first",
                Toast.LENGTH_SHORT
            ).show()
            runCatching {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
