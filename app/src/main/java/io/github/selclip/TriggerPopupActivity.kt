package io.github.selclip

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Windowless (`Theme.NoDisplay`) shortcut & activity trigger for third-party gesture/automation apps
 * (UbikiTouch, Tasker, Edge Gestures, Launchers).
 *
 * Finishes synchronously inside `onCreate()` so NO window is ever created, the screen NEVER blinks,
 * and the foreground app NEVER loses focus or clears its highlighted text / selection handles!
 */
class TriggerPopupActivity : Activity() {

    companion object {
        const val ACTION_TRIGGER_POPUP = "io.github.selclip.ACTION_TRIGGER_POPUP"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == Intent.ACTION_CREATE_SHORTCUT) {
            val launchIntent = Intent(this, TriggerPopupActivity::class.java).apply {
                action = ACTION_TRIGGER_POPUP
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            @Suppress("DEPRECATION")
            val result = Intent().apply {
                putExtra(Intent.EXTRA_SHORTCUT_INTENT, launchIntent)
                putExtra(Intent.EXTRA_SHORTCUT_NAME, getString(R.string.shortcut_popup_short))
                putExtra(
                    Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                    Intent.ShortcutIconResource.fromContext(this@TriggerPopupActivity, R.mipmap.ic_launcher)
                )
            }
            setResult(RESULT_OK, result)
            finish()
            return
        }

        val service = SelAccessibilityService.instance
        if (service == null) {
            Toast.makeText(
                this,
                "Enable Sel in Accessibility Settings first.",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            val explicit = intent?.getStringExtra(Intent.EXTRA_TEXT)?.take(8000)?.takeIf { it.isNotBlank() }
            service.triggerFromShortcut(explicit)
        }
        finish()
    }
}
