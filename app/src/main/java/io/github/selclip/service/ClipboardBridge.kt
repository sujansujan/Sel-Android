package io.github.selclip.service

import android.content.ClipData
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
 * Clipboard-preserving, on-demand single-shot bridge used ONLY as a last resort when an app
 * (e.g., Firefox GeckoView) refuses to expose selected text via Accessibility nodes.
 *
 * Guarantees:
 * - Zero unsolicited clipboard monitoring (never pops up when user copies normally).
 * - Snapshots the user's previous ClipData before fallback copy and immediately restores it
 *   after reading, so the user's clipboard is NOT polluted.
 */
class ClipboardBridge(
    private val context: Context,
    private val clipboardManager: ClipboardManager,
    private val windowManager: WindowManager
) {

    private val handler = Handler(Looper.getMainLooper())
    private var lastInternalCopyMs: Long = 0L
    private var lastProbeFinishedMs: Long = 0L
    private var activeProbe: View? = null
    private var pendingCleanup: Runnable? = null
    private var pendingRetry1: Runnable? = null
    private var pendingRetry2: Runnable? = null

    val isProbeOrSettling: Boolean
        get() = activeProbe != null || (System.currentTimeMillis() - lastProbeFinishedMs) < 950L

    fun markInternalCopy() {
        lastInternalCopyMs = System.currentTimeMillis()
    }

    fun stop() {
        cancelProbe()
    }

    /**
     * Snapshots current clipboard (if readable without stealing focus), executes [performCopy],
     * reads the copied text once, and immediately restores the user's previous clipboard!
     */
    fun captureFallbackSelection(
        performCopy: () -> Boolean,
        onCaptured: (String) -> Unit
    ): Boolean {
        if (isPrimaryClipSensitive()) return false
        val savedClip: ClipData? = runCatching {
            clipboardManager.primaryClip?.let { clip ->
                if (clip.itemCount > 0) ClipData(clip) else null
            }
        }.getOrNull()

        if (!performCopy()) return false

        // Wait briefly for browser IPC to write the selection, then read & immediately restore!
        handler.postDelayed({
            readOnceAndRestore(savedClip, onCaptured)
        }, 75L)
        return true
    }

    private fun readOnceAndRestore(
        savedClip: ClipData?,
        onCaptured: (String) -> Unit
    ) {
        if (isPrimaryClipSensitive()) return
        val direct = readPrimaryClipText()
        if (!direct.isNullOrBlank()) {
            restoreClipboard(savedClip)
            onCaptured(direct)
            return
        }
        launchSingleFocusProbe(savedClip, onCaptured)
    }

    private fun restoreClipboard(savedClip: ClipData?) {
        markInternalCopy()
        runCatching {
            if (savedClip != null && savedClip.itemCount > 0) {
                clipboardManager.setPrimaryClip(savedClip)
            } else if (Build.VERSION.SDK_INT >= 28) {
                clipboardManager.clearPrimaryClip()
            }
        }
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
        pendingRetry1?.let { handler.removeCallbacks(it) }
        pendingRetry2?.let { handler.removeCallbacks(it) }
        pendingCleanup?.let { handler.removeCallbacks(it) }
        pendingRetry1 = null
        pendingRetry2 = null
        pendingCleanup = null
        activeProbe?.let { v ->
            runCatching { windowManager.removeView(v) }
            lastProbeFinishedMs = System.currentTimeMillis()
        }
        activeProbe = null
    }

    private fun launchSingleFocusProbe(
        savedClip: ClipData?,
        onCaptured: (String) -> Unit
    ) {
        cancelProbe()
        val probe = View(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
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
                restoreClipboard(savedClip)
                finishProbe()
                onCaptured(clip)
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
        pendingRetry1 = r1
        pendingRetry2 = r2
        pendingCleanup = clean
        handler.postDelayed(r1, 45L)
        handler.postDelayed(r2, 160L)
        handler.postDelayed(clean, 360L)
    }
}
