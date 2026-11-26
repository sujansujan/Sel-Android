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
            if (explicit != null || service.hasLiveSelectionSignalForShortcut()) {
                // Live selection, Compose handles, or Chrome/WebView toolbar is on screen ->
                // stay windowless so we NEVER steal focus or blink the screen!
                service.triggerFromShortcut(explicit)
            } else {
                // No live highlight/toolbar on screen (e.g. user copied in PDFCraft / Google Messages) ->
                // foreground-to-foreground handoff to 1x1 ClipboardFallbackActivity (never blocked by HyperOS!)
                val fallback = Intent(this, ClipboardFallbackActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                }
                runCatching { startActivity(fallback) }
            }
        }
        finish()
    }
}
