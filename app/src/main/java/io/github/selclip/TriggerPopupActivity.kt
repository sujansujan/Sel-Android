package io.github.selclip

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast

/**
 * Zero-flash exported shortcut & activity entry point for third-party gesture/automation apps
 * (UbikiTouch, Tasker, Edge Gestures, Niagara/Nova Launcher shortcuts).
 *
 * - Supports `ACTION_CREATE_SHORTCUT` so UbikiTouch / launchers can bind "Sel Popup" directly.
 * - On trigger, asks `SelAccessibilityService` to scan for any active on-screen selection first,
 *   and if none is accessible (e.g. custom-canvas PDF readers, Chrome/Hermit on Android 16.0),
 *   passes the current clipboard text READ-ONLY (never modifies/pollutes clipboard!) so you can
 *   copy anywhere and swipe UbikiTouch to summon Sel's full floating bar instantaneously.
 */
class TriggerPopupActivity : Activity() {

    companion object {
        const val ACTION_TRIGGER_POPUP = "io.github.selclip.ACTION_TRIGGER_POPUP"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var dispatched = false
    private val failsafeRunnable = Runnable { dispatchAndFinish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disableTransitions()

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
            disableLegacyTransitions()
            return
        }

        // Tiny 1x1 invisible floating window at (0,0) so HyperOS never flashes/dims the screen!
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

        // Brief failsafe in case window focus doesn't arrive on a locked/constrained surface
        handler.postDelayed(failsafeRunnable, 180L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !dispatched) {
            dispatchAndFinish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(failsafeRunnable)
        super.onDestroy()
    }

    private fun dispatchAndFinish() {
        if (dispatched) return
        dispatched = true
        handler.removeCallbacks(failsafeRunnable)

        val service = SelAccessibilityService.instance
        if (service == null) {
            Toast.makeText(
                this,
                "Enable Sel in Accessibility Settings first.",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            // Optional explicit text extra (e.g. from Tasker) or READ-ONLY clipboard snapshot while focused
            val explicit = intent?.getStringExtra(Intent.EXTRA_TEXT)?.take(8000)?.takeIf { it.isNotBlank() }
            val clipFallback = explicit ?: readClipboardTextReadOnly()
            service.triggerFromShortcut(clipFallback)
        }

        finish()
        disableLegacyTransitions()
    }

    private fun readClipboardTextReadOnly(): String? = runCatching {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        clip.getItemAt(0)?.coerceToText(this)?.toString()?.take(8000)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun disableTransitions() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
    }

    private fun disableLegacyTransitions() {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 34) {
            overridePendingTransition(0, 0)
        }
    }
}
