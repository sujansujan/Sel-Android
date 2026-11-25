package io.github.selclip.service

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Single-flight internal clipboard bridge for reading browser/WebView selections on Android 10+.
 *
 * Includes a post-probe focus-settle cooldown (`950ms`) so Chrome restoring focus after the 1x1
 * probe finishes NEVER triggers a second selection probe or causes Chrome's toolbar to blink.
 */
class ClipboardBridge(
    private val context: Context,
    private val clipboardManager: ClipboardManager,
    private val windowManager: WindowManager,
    private val shouldAcceptExternalClip: () -> Boolean,
    private val onClipCaptured: (String) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private var lastInternalCopyMs: Long = 0L
    private var lastProbeFinishedMs: Long = 0L
    private var lastReadAttemptMs: Long = 0L
    private var activeProbe: View? = null
    private var probeCleanupRunnable: Runnable? = null
    private var probeRetry1: Runnable? = null
    private var probeRetry2: Runnable? = null

    /**
     * True while the 1x1 focus probe is attached OR within 950ms after it detaches, so the
     * host browser's focus-restore window events are cleanly ignored (zero blinking loop!).
     */
    val isProbeOrSettling: Boolean
        get() = activeProbe != null || (System.currentTimeMillis() - lastProbeFinishedMs) < 950L

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        val now = System.currentTimeMillis()
        if (now - lastInternalCopyMs < 650L) return@OnPrimaryClipChangedListener
        if (!shouldAcceptExternalClip()) return@OnPrimaryClipChangedListener
        readNowOrProbe()
    }

    fun start() {
        runCatching {
            clipboardManager.removePrimaryClipChangedListener(listener)
            clipboardManager.addPrimaryClipChangedListener(listener)
        }
    }

    fun stop() {
        runCatching { clipboardManager.removePrimaryClipChangedListener(listener) }
        cancelProbe()
    }

    fun markInternalCopy() {
        lastInternalCopyMs = System.currentTimeMillis()
    }

    fun readNowOrProbe() {
        if (isPrimaryClipSensitive()) return
        val now = System.currentTimeMillis()
        // Prevent double-launching probe within 350ms
        if (activeProbe != null || (now - lastReadAttemptMs) < 350L) return
        lastReadAttemptMs = now

        val direct = readPrimaryClipText()
        if (!direct.isNullOrBlank()) {
            onClipCaptured(direct)
            return
        }
        launchFocusProbe()
    }

    private fun isPrimaryClipSensitive(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val desc = runCatching { clipboardManager.primaryClipDescription }.getOrNull()
            if (desc?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true) return true
        }
        return false
    }

    private fun readPrimaryClipText(): String? = runCatching {
        clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
    }.getOrNull()

    private fun cancelProbe() {
        probeRetry1?.let { handler.removeCallbacks(it) }
        probeRetry2?.let { handler.removeCallbacks(it) }
        probeCleanupRunnable?.let { handler.removeCallbacks(it) }
        probeRetry1 = null
        probeRetry2 = null
        probeCleanupRunnable = null
        activeProbe?.let { v ->
            runCatching { windowManager.removeView(v) }
            lastProbeFinishedMs = System.currentTimeMillis()
        }
        activeProbe = null
    }

    private fun launchFocusProbe() {
        cancelProbe()
        val probe = View(context)
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        var finished = false
        fun finishProbe() {
            if (finished) return
            finished = true
            lastProbeFinishedMs = System.currentTimeMillis()
            if (activeProbe === probe) cancelProbe()
            else runCatching { windowManager.removeView(probe) }
        }

        fun tryRead() {
            if (finished) return
            if (isPrimaryClipSensitive()) {
                finishProbe()
                return
            }
            val clip = readPrimaryClipText()
            if (!clip.isNullOrBlank()) {
                finishProbe()
                onClipCaptured(clip)
            }
        }

        probe.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) {
                tryRead()
                finishProbe()
            }
        }

        activeProbe = probe
        val added = runCatching { windowManager.addView(probe, params) }.isSuccess
        if (!added) {
            activeProbe = null
            return
        }

        val r1 = Runnable { tryRead() }
        val r2 = Runnable { tryRead() }
        val clean = Runnable { finishProbe() }
        probeRetry1 = r1
        probeRetry2 = r2
        probeCleanupRunnable = clean
        handler.postDelayed(r1, 45L)
        handler.postDelayed(r2, 150L)
        handler.postDelayed(clean, 380L)
    }
}
