package io.github.selclip

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumMap
import java.util.Locale
import kotlin.math.abs

class SelAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_GRAB_SCREEN = "io.github.selclip.ACTION_GRAB_SCREEN"
        const val ACTION_EXTRACT_TEXT = "io.github.selclip.EXTRACT_SCREEN_TEXT"

        @Volatile
        var instance: SelAccessibilityService? = null
    }

    private enum class PopupMode {
        ACTIONS,
        COLLAPSED_PILL,
        WEB_SEARCH_FOLDER,
        YOUTUBE_FOLDER,
        CLIP_HISTORY,
        MARKDOWN_FOLDER,
        REGEX_PIPE,
        CUSTOM_FOLDER,
        QR_CODE,
        MAGNET,
        DEFINE_WORD,
        TRANSLATE,
        EXTRACT,
        REORDER
    }

    private lateinit var windowManager: WindowManager
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var selectionDetector: SelectionDetector
    private lateinit var screenHarvester: ScreenTextHarvester
    private val screenOcrEngine = ScreenOcrEngine()
    private val networkTools = NetworkTools()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingTtsText: String? = null

    private var popupView: View? = null
    private var popupParams: WindowManager.LayoutParams? = null
    private var headerBarView: LinearLayout? = null
    private var headerDividerView: View? = null
    private var statsLabelView: TextView? = null
    private var gridToggleBtn: TextView? = null
    private var collapseBtnView: TextView? = null
    private var reorderToggleBtn: TextView? = null
    private var closeBtnView: TextView? = null
    private var bodyContainer: LinearLayout? = null

    private var screenGrabOverlayView: View? = null
    private var activeClipboardProbe: View? = null

    private var currentMode: PopupMode = PopupMode.ACTIONS
    private var activeCustomFolder: String = ""
    private var currentNode: AccessibilityNodeInfo? = null
    private var selectedText: String = ""
    private var lastRawCandidateBounds: Rect = Rect()
    private var lastRawCandidateTime: Long = 0L
    private var currentSafeZone: Rect = Rect()
    private var currentMaxTotalHeight: Int = 700
    private var dynamicMaxScrollHeight: Int = 600
    private var dynamicMaxPopupWidth: Int = 540
    private var dynamicMinPopupWidth: Int = 380
    private var anchoredAboveSelection: Boolean = false
    private var userManuallyDragged: Boolean = false

    private var lastInternalCopyTime = 0L
    private var lastAutoCopyTime = 0L
    private var lastAutoCopyToolbarSig = ""
    private var lastContentScanTime = 0L
    private var lastShownTime = 0L
    private var activePackage: String = ""

    // In-Place Undo State
    private var undoOriginalFullText: String? = null
    private var undoNode: AccessibilityNodeInfo? = null
    private var undoTimestamp: Long = 0L

    private val delayedHideRunnable = Runnable { hidePalette() }
    private var pendingUniversalExtractRunnable: Runnable? = null

    private val grabReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val act = intent?.action ?: return
            if (act == ACTION_GRAB_SCREEN || act == ACTION_EXTRACT_TEXT) {
                triggerScreenGrab(delayMs = 80L)
            }
        }
    }

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        val now = System.currentTimeMillis()
        if (now - lastInternalCopyTime < 650L) return@OnPrimaryClipChangedListener
        if (screenGrabOverlayView != null) return@OnPrimaryClipChangedListener
        if (activePackage.isNotEmpty() && ToolPrefs.isAppBlacklisted(this, activePackage)) {
            return@OnPrimaryClipChangedListener
        }
        readClipboardAndShowPalette()
    }

    private fun Int.safeCoerceIn(minVal: Int, maxVal: Int): Int =
        if (minVal <= maxVal) this.coerceIn(minVal, maxVal) else minVal

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onServiceConnected() {
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        selectionDetector = SelectionDetector(this, windowManager)
        screenHarvester = ScreenTextHarvester(this, selectionDetector)

        val filter = IntentFilter().apply {
            addAction(ACTION_GRAB_SCREEN)
            addAction(ACTION_EXTRACT_TEXT)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(grabReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(grabReceiver, filter)
            }
        }

        runCatching {
            clipboardManager.removePrimaryClipChangedListener(clipboardListener)
            clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (popupView != null) {
            mainHandler.post { renderPopupBody() }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanupServiceResources()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanupServiceResources()
        super.onDestroy()
    }

    private fun cleanupServiceResources() {
        if (instance === this) instance = null
        runCatching { unregisterReceiver(grabReceiver) }
        runCatching { clipboardManager.removePrimaryClipChangedListener(clipboardListener) }
        removeClipboardProbe()
        networkTools.shutdown()
        screenOcrEngine.close()
        runCatching {
            tts?.stop()
            tts?.shutdown()
            tts = null
            ttsReady = false
        }
        hideScreenGrabOverlay()
        hidePalette()
    }

    fun refreshOpenPopupTheme() {
        if (popupView != null && popupView?.isAttachedToWindow == true) {
            mainHandler.post { renderPopupBody() }
        }
    }

    fun showFromExternalText(text: String) {
        mainHandler.post {
            if (text.isNotBlank()) {
                ToolPrefs.addClipToHistory(this, text)
                mainHandler.removeCallbacks(delayedHideRunnable)
                selectedText = text
                currentMode = PopupMode.ACTIONS
                currentNode = selectionDetector.findFocusedEditableOrSelectableNode()
                lastShownTime = System.currentTimeMillis()
                val now = lastShownTime
                val rawCandidate = when {
                    !lastRawCandidateBounds.isEmpty && now - lastRawCandidateTime < 2800L -> Rect(lastRawCandidateBounds)
                    currentNode != null -> Rect().also { currentNode?.getBoundsInScreen(it) }
                    else -> null
                }
                val safeZone = selectionDetector.resolveBestSafeZone(rawCandidate, forceRefresh = true)
                currentSafeZone = Rect(safeZone)
                showOrUpdatePalette(currentSafeZone)
            }
        }
    }

    fun triggerScreenGrab(delayMs: Long = 120L) {
        hidePalette()
        hideScreenGrabOverlay()
        mainHandler.postDelayed({
            val snapshot = screenHarvester.harvest()
            if (snapshot.blocks.isEmpty()) {
                runScreenOcrCapture(delayMs = 0L)
            } else {
                showLensGrabOverlay(snapshot, backgroundBitmap = null, isOcrMode = false)
            }
        }, delayMs)
    }

    /**
     * Captures the screen via API 30+ AccessibilityService.takeScreenshot() and runs
     * Google Play Services ML Kit on-device OCR to recognize text directly off images,
     * videos, memes, PDFs, and canvas surfaces, freezing the frame under LensOverlayView.
     */
    private fun runScreenOcrCapture(delayMs: Long = 140L) {
        hidePalette()
        hideScreenGrabOverlay()
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(this, "[sel] On-device pixel OCR requires Android 11+", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "[sel] Scanning pixels with OCR…", Toast.LENGTH_SHORT).show()
        mainHandler.postDelayed({
            runCatching {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            val hwBuffer = screenshotResult.hardwareBuffer
                            val colorSpace = screenshotResult.colorSpace
                            val hwBmp = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                            hwBuffer.close()
                            if (hwBmp == null) {
                                Toast.makeText(this@SelAccessibilityService, "[sel] Screenshot unavailable", Toast.LENGTH_SHORT).show()
                                return
                            }
                            val softBmp = hwBmp.copy(Bitmap.Config.ARGB_8888, false)
                            hwBmp.recycle()
                            if (softBmp == null) {
                                Toast.makeText(this@SelAccessibilityService, "[sel] Could not decode screen frame", Toast.LENGTH_SHORT).show()
                                return
                            }

                            screenOcrEngine.recognizeBitmap(
                                bitmap = softBmp,
                                density = resources.displayMetrics.density,
                                onSuccess = { ocrSnapshot ->
                                    if (ocrSnapshot.blocks.isNotEmpty()) {
                                        showLensGrabOverlay(
                                            snapshot = ocrSnapshot,
                                            backgroundBitmap = softBmp,
                                            isOcrMode = true
                                        )
                                    } else {
                                        if (!softBmp.isRecycled) runCatching { softBmp.recycle() }
                                        Toast.makeText(
                                            this@SelAccessibilityService,
                                            "[sel] No text detected in screen image",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                onFailure = { err ->
                                    if (!softBmp.isRecycled) runCatching { softBmp.recycle() }
                                    val msg = err.localizedMessage?.take(60) ?: "OCR unavailable"
                                    Toast.makeText(
                                        this@SelAccessibilityService,
                                        "[sel] OCR error: $msg",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }

                        override fun onFailure(errorCode: Int) {
                            Toast.makeText(
                                this@SelAccessibilityService,
                                "[sel] Screen capture blocked or unavailable (err $errorCode)",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }
        }, delayMs)
    }

    private fun showLensGrabOverlay(
        snapshot: ScreenGrabSnapshot,
        backgroundBitmap: Bitmap? = null,
        isOcrMode: Boolean = false
    ) {
        hideScreenGrabOverlay()

        // Focusable so hardware/gesture Back key and inline Editor/Search keyboard work seamlessly!
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        val lensView = LensOverlayView(
            context = this,
            snapshot = snapshot,
            backgroundBitmap = backgroundBitmap,
            isOcrMode = isOcrMode,
            onCopyText = { chosen ->
                smoothCopyText(chosen)
                hideScreenGrabOverlay()
            },
            onSmartAction = { actionId, chosen ->
                hideScreenGrabOverlay()
                selectedText = chosen
                ToolPrefs.addClipToHistory(this, chosen)
                val dm = resources.displayMetrics
                val centerZone = Rect(0, dm.heightPixels / 3, dm.widthPixels, dm.heightPixels / 2)
                when (actionId) {
                    "magnet" -> {
                        currentMode = PopupMode.MAGNET
                        currentSafeZone = Rect(centerZone)
                        showOrUpdatePalette(centerZone)
                    }
                    "clean_url" -> {
                        smoothCopyText(TextTransforms.cleanUrl(chosen), "[^C] Cleaned URL copied")
                    }
                    "open_url" -> {
                        openUrl(chosen)
                    }
                    "dial" -> {
                        val dialNum = TextTransforms.extractNepalPhone(chosen)?.dialNumber ?: chosen
                        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(dialNum)}")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { startActivity(dialIntent) }
                    }
                    "math" -> {
                        val ans = TextTransforms.evalSimpleMath(chosen)
                        if (ans != null) smoothCopyText(ans, "[==] Copied result: $ans")
                    }
                    "define" -> {
                        currentMode = PopupMode.DEFINE_WORD
                        currentSafeZone = Rect(centerZone)
                        showOrUpdatePalette(centerZone)
                    }
                    else -> showFromExternalText(chosen)
                }
            },
            onOpenInTools = { chosen ->
                hideScreenGrabOverlay()
                showFromExternalText(chosen)
            },
            onSearchText = { chosen ->
                hideScreenGrabOverlay()
                openUrl("https://duckduckgo.com/?q=${Uri.encode(chosen)}")
            },
            onRunPixelOcr = {
                runScreenOcrCapture(delayMs = 140L)
            },
            onDismiss = {
                hideScreenGrabOverlay()
            }
        )

        screenGrabOverlayView = lensView
        val added = runCatching { windowManager.addView(screenGrabOverlayView, params) }.isSuccess
        if (!added) {
            screenGrabOverlayView = null
            if (backgroundBitmap != null && !backgroundBitmap.isRecycled) {
                runCatching { backgroundBitmap.recycle() }
            }
        }
    }

    private fun hideScreenGrabOverlay() {
        screenGrabOverlayView?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        screenGrabOverlayView = null
    }

    private fun smoothCopyText(text: String, toastLabel: String? = null) {
        lastInternalCopyTime = System.currentTimeMillis()
        clipboardManager.setPrimaryClip(ClipData.newPlainText("sel", text))
        ToolPrefs.addClipToHistory(this, text)
        popupView?.let { runCatching { it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } }
        val preview = text.replace("\n", " ").let { if (it.length > 26) it.take(25) + "…" else it }
        Toast.makeText(this, toastLabel ?: "[^C] Copied: \"$preview\"", Toast.LENGTH_SHORT).show()
    }

    private fun isPrimaryClipSensitive(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val desc = runCatching { clipboardManager.primaryClipDescription }.getOrNull()
            val isSens = desc?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) ?: false
            if (isSens) return true
        }
        return false
    }

    private fun removeClipboardProbe() {
        activeClipboardProbe?.let { p ->
            runCatching { windowManager.removeView(p) }
        }
        activeClipboardProbe = null
    }

    private fun readClipboardAndShowPalette() {
        if (isPrimaryClipSensitive()) return

        val directClip = runCatching {
            clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull()

        if (!directClip.isNullOrBlank()) {
            showFromExternalText(directClip)
            return
        }

        // Single-flight 1x1 focusable-non-touchable probe for Android 10+ background clipboard access
        removeClipboardProbe()
        val probe = View(this)
        val probeParams = WindowManager.LayoutParams(
            1,
            1,
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

        var done = false
        val cleanup = {
            if (!done) {
                done = true
                if (activeClipboardProbe === probe) {
                    removeClipboardProbe()
                } else {
                    runCatching { windowManager.removeView(probe) }
                }
            }
        }

        val tryRead = {
            if (!done) {
                if (isPrimaryClipSensitive()) {
                    cleanup()
                } else {
                    val clip = runCatching {
                        clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
                    }.getOrNull()
                    if (!clip.isNullOrBlank()) {
                        cleanup()
                        showFromExternalText(clip)
                    }
                }
            }
        }

        probe.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) {
                tryRead()
                cleanup()
            }
        }

        activeClipboardProbe = probe
        val added = runCatching { windowManager.addView(probe, probeParams) }.isSuccess
        if (!added) {
            activeClipboardProbe = null
            return
        }
        mainHandler.postDelayed({ tryRead() }, 30L)
        mainHandler.postDelayed({ cleanup() }, 130L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        runCatching {
            handleAccessibilityEventInternal(event)
        }
    }

    private fun handleAccessibilityEventInternal(event: AccessibilityEvent?) {
        event ?: return
        if (screenGrabOverlayView != null) return
        val pkg = event.packageName?.toString() ?: ""
        val cls = event.className?.toString() ?: ""

        // Track foreground app package accurately on real window switches (critical for blacklist & auto-dismiss)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg.isNotEmpty() &&
                pkg != packageName &&
                pkg != "android" &&
                pkg != "com.android.systemui" &&
                !selectionDetector.isImePackage(pkg) &&
                !selectionDetector.isTransientSystemOrPopupClass(cls)
            ) {
                val prevPkg = activePackage
                activePackage = pkg
                if (popupView != null && prevPkg.isNotEmpty() && prevPkg != pkg) {
                    val elapsed = System.currentTimeMillis() - lastShownTime
                    if (elapsed > 500L) {
                        hidePalette()
                    }
                }
            }
        }

        if (pkg.isNotEmpty() && ToolPrefs.isAppBlacklisted(this, pkg)) {
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (popupView != null && pkg.isNotEmpty() && pkg != packageName) {
                    val elapsed = System.currentTimeMillis() - lastShownTime
                    // Don't close if user tapped a selection toolbar button (e.g. Copy/Select all)
                    val clickedLabel = (event.text.firstOrNull()?.toString()
                        ?: event.contentDescription?.toString() ?: "").lowercase()
                    val isSelectionToolbarTap = clickedLabel in setOf("copy", "cut", "select all", "share")
                    if (elapsed > 260L && !isSelectionToolbarTap) {
                        hidePalette()
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val result = selectionDetector.extractSelection(event)
                if (result != null) {
                    pendingUniversalExtractRunnable?.let { mainHandler.removeCallbacks(it) }
                    mainHandler.removeCallbacks(delayedHideRunnable)
                    val changedText = result.text != selectedText
                    selectedText = result.text
                    currentNode = result.node
                    if (pkg.isNotEmpty() && pkg != packageName) activePackage = pkg
                    lastShownTime = System.currentTimeMillis()
                    lastRawCandidateBounds = Rect(result.bounds)
                    lastRawCandidateTime = lastShownTime
                    if (changedText && currentMode != PopupMode.ACTIONS && currentMode != PopupMode.REORDER) {
                        currentMode = PopupMode.ACTIONS
                    }
                    val safeZone = selectionDetector.resolveBestSafeZone(result.bounds, forceRefresh = true)
                    currentSafeZone = Rect(safeZone)
                    showOrUpdatePalette(currentSafeZone)
                } else if (event.fromIndex >= 0 && event.toIndex >= 0 && event.fromIndex != event.toIndex) {
                    // Selection indices reported, but node didn't expose substring (common in WebView/Chrome/Compose)
                    event.source?.let { src ->
                        val r = Rect()
                        src.getBoundsInScreen(r)
                        val realH = selectionDetector.getRealScreenBounds().height()
                        if (r.width() > 0 && r.height() in 1..(realH * 0.40f).toInt()) {
                            lastRawCandidateBounds = Rect(r)
                            lastRawCandidateTime = System.currentTimeMillis()
                        }
                    }
                    scheduleUniversalSelectionExtract(event.source, pkg, toolbarSig = "")
                } else {
                    // Selection collapsed to cursor
                    val src = event.source
                    val elapsed = System.currentTimeMillis() - lastShownTime
                    if (popupView != null && currentMode == PopupMode.ACTIONS && elapsed > 600L &&
                        src != null && src == currentNode
                    ) {
                        val s = src.textSelectionStart
                        val e = src.textSelectionEnd
                        if (s >= 0 && s == e) {
                            mainHandler.removeCallbacks(delayedHideRunnable)
                            mainHandler.postDelayed(delayedHideRunnable, 220L)
                        }
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (popupView == null && pkg != packageName && !selectionDetector.isImePackage(pkg)) {
                    // A floating ActionMode / Selection toolbar popup appeared in ANY app!
                    if (selectionDetector.isTransientSystemOrPopupClass(cls)) {
                        val toolbar = selectionDetector.findActiveSelectionToolbarNow()
                        if (toolbar != null) {
                            scheduleUniversalSelectionExtract(event.source, activePackage.ifEmpty { pkg }, toolbar.signature)
                        }
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Throttled universal toolbar scan for apps that render selection toolbars inline (Compose, WebView, etc.)
                if (popupView == null &&
                    pkg.isNotEmpty() &&
                    pkg != packageName &&
                    pkg != "com.android.systemui" &&
                    !selectionDetector.isImePackage(pkg)
                ) {
                    val now = System.currentTimeMillis()
                    if (now - lastContentScanTime >= 260L && now - lastAutoCopyTime > 850L) {
                        lastContentScanTime = now
                        val toolbar = selectionDetector.findActiveSelectionToolbarNow()
                        if (toolbar != null && toolbar.signature != lastAutoCopyToolbarSig) {
                            scheduleUniversalSelectionExtract(event.source, pkg, toolbar.signature)
                        }
                    }
                }
            }
        }
    }

    /**
     * Universal selection extraction when a floating selection toolbar or opaque selection event
     * is detected in ANY app (Chrome, Firefox, WebViews, Compose, React Native, Gmail, WhatsApp, etc.).
     */
    private fun scheduleUniversalSelectionExtract(
        sourceNode: AccessibilityNodeInfo?,
        pkg: String,
        toolbarSig: String
    ) {
        pendingUniversalExtractRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            val now = System.currentTimeMillis()
            if (now - lastAutoCopyTime < 650L) return@Runnable
            if (toolbarSig.isNotEmpty() && toolbarSig == lastAutoCopyToolbarSig && now - lastAutoCopyTime < 2200L) {
                return@Runnable
            }
            if (pkg.isNotEmpty() && pkg != packageName && !selectionDetector.isImePackage(pkg)) {
                activePackage = pkg
            }

            // Tier 1: Zero-side-effect deep BFS for any node with readable selection text
            val treeResult = selectionDetector.findSelectionInTreeBfs(sourceNode ?: rootInActiveWindow)
            if (treeResult != null) {
                selectedText = treeResult.text
                currentNode = treeResult.node
                lastShownTime = System.currentTimeMillis()
                lastRawCandidateBounds = Rect(treeResult.bounds)
                lastRawCandidateTime = lastShownTime
                if (toolbarSig.isNotEmpty()) lastAutoCopyToolbarSig = toolbarSig
                val safeZone = selectionDetector.resolveBestSafeZone(treeResult.bounds, forceRefresh = false)
                currentSafeZone = Rect(safeZone)
                showOrUpdatePalette(currentSafeZone)
                return@Runnable
            }

            // Tier 2: Class-agnostic ACTION_COPY on copy-capable node (WebView, GeckoView, Compose, etc.)
            lastAutoCopyTime = now
            if (toolbarSig.isNotEmpty()) lastAutoCopyToolbarSig = toolbarSig
            if (sourceNode != null) {
                val r = Rect()
                sourceNode.getBoundsInScreen(r)
                val realH = selectionDetector.getRealScreenBounds().height()
                if (r.width() > 0 && r.height() in 1..(realH * 0.40f).toInt()) {
                    lastRawCandidateBounds = Rect(r)
                    lastRawCandidateTime = now
                }
            }

            val copied = selectionDetector.performBestCopyAction(sourceNode)
            if (copied) {
                // Only run fallback read if clipboardListener didn't already show the palette within 70ms
                val triggerTime = System.currentTimeMillis()
                mainHandler.postDelayed({
                    if (lastShownTime < triggerTime && popupView == null) {
                        readClipboardAndShowPalette()
                    }
                }, 70L)
            }
        }
        pendingUniversalExtractRunnable = runnable
        mainHandler.postDelayed(runnable, 110L)
    }

    private fun applyStrictPlacementAndDynamicSize(safeZone: Rect, params: WindowManager.LayoutParams) {
        val metrics = selectionDetector.getScreenMetrics(force = false)
        val realW = metrics.screenWidth
        val realH = metrics.screenHeight
        val topSafe = metrics.topSafeInset
        val visibleBottom = metrics.visibleBottom

        currentSafeZone = Rect(safeZone)
        val gap = dp(12)

        val spaceAbove = (safeZone.top - gap) - topSafe
        val spaceBelow = visibleBottom - (safeZone.bottom + gap)

        val placeAbove = when {
            spaceBelow >= dp(230) && (spaceBelow >= spaceAbove || spaceBelow >= dp(290)) -> false
            spaceAbove >= dp(140) && spaceAbove > spaceBelow -> true
            spaceBelow >= dp(150) -> false
            else -> spaceAbove > spaceBelow
        }
        anchoredAboveSelection = placeAbove

        val chosenSpace = if (placeAbove) spaceAbove else spaceBelow
        currentMaxTotalHeight = chosenSpace.safeCoerceIn(dp(100), (realH * 0.42f).toInt())
        val headerHeightEst = dp(32)
        dynamicMaxScrollHeight = (currentMaxTotalHeight - headerHeightEst).safeCoerceIn(dp(54), dp(296))

        val tightVertical = chosenSpace < dp(220)
        val twoColGrid = ToolPrefs.isTwoColumnGrid(this) && currentMode == PopupMode.ACTIONS
        dynamicMinPopupWidth = when {
            currentMode != PopupMode.ACTIONS -> dp(208)
            twoColGrid -> dp(212)
            tightVertical -> dp(178)
            else -> dp(174)
        }
        dynamicMaxPopupWidth = when {
            currentMode != PopupMode.ACTIONS -> dp(252)
            twoColGrid -> dp(236)
            tightVertical -> dp(208)
            else -> dp(204)
        }.coerceAtMost(realW - dp(20))

        if (!userManuallyDragged) {
            val snapLeft = ToolPrefs.isSnapLeftEdge(this)
            params.gravity = Gravity.TOP or Gravity.START
            params.x = if (snapLeft) {
                dp(10)
            } else {
                (realW - dynamicMaxPopupWidth - dp(10)).coerceAtLeast(dp(10))
            }

            val estH = popupView?.height?.takeIf { it > dp(60) }?.coerceAtMost(currentMaxTotalHeight)
                ?: currentMaxTotalHeight

            if (placeAbove) {
                params.y = (safeZone.top - gap - estH).coerceAtLeast(topSafe)
            } else {
                val maxTopY = (visibleBottom - estH).coerceAtLeast(topSafe)
                params.y = (safeZone.bottom + gap).safeCoerceIn(topSafe, maxTopY)
            }
        }
    }

    private fun applyPaletteToPopupChrome(pal: ThemePalette) {
        popupView?.background = GradientDrawable().apply {
            setColor(pal.bgPrimaryInt)
            setStroke(dp(1), pal.borderInt)
            cornerRadius = 0f
        }
        headerBarView?.setBackgroundColor(pal.bgHeaderInt)
        headerDividerView?.setBackgroundColor(pal.borderInt)
        statsLabelView?.setTextColor(pal.fgMutedInt)

        val twoCol = ToolPrefs.isTwoColumnGrid(this)
        gridToggleBtn?.text = if (twoCol) "[1C]" else "[2C]"
        gridToggleBtn?.setTextColor(if (twoCol) pal.accentGreenInt else pal.fgPrimaryInt)

        collapseBtnView?.setTextColor(pal.fgPrimaryInt)
        reorderToggleBtn?.setTextColor(if (currentMode == PopupMode.REORDER) pal.accentGreenInt else pal.fgPrimaryInt)
        closeBtnView?.setTextColor(pal.dangerRedInt)
    }

    private fun showOrUpdatePalette(safeZone: Rect) {
        val pal = ToolPrefs.resolvePalette(this)
        val words = TextTransforms.countWords(selectedText)
        val chars = selectedText.length

        // Recover cleanly if a previous popup view became detached unexpectedly
        if (popupView != null && popupView?.isAttachedToWindow != true) {
            hidePalette()
        }

        if (popupView != null && statsLabelView != null && bodyContainer != null && popupParams != null) {
            applyStrictPlacementAndDynamicSize(safeZone, popupParams!!)
            val posGlyph = if (anchoredAboveSelection) "^" else "v"
            statsLabelView?.text = "${words}W:${chars}C $posGlyph"
            renderPopupBody()
            return
        }

        userManuallyDragged = false
        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        applyStrictPlacementAndDynamicSize(safeZone, windowParams)

        val rootCard = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                if (currentMode == PopupMode.COLLAPSED_PILL) {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                    return
                }
                val cappedWidthSpec = MeasureSpec.makeMeasureSpec(dynamicMaxPopupWidth, MeasureSpec.AT_MOST)
                val cappedHeightSpec = MeasureSpec.makeMeasureSpec(currentMaxTotalHeight, MeasureSpec.AT_MOST)
                super.onMeasure(cappedWidthSpec, cappedHeightSpec)
                if (measuredWidth < dynamicMinPopupWidth) {
                    super.onMeasure(
                        MeasureSpec.makeMeasureSpec(dynamicMinPopupWidth, MeasureSpec.EXACTLY),
                        cappedHeightSpec
                    )
                }
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(pal.bgPrimaryInt)
                setStroke(dp(1), pal.borderInt)
                cornerRadius = 0f
            }
            addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
                val actualH = bottom - top
                val p = popupParams
                if (actualH > 0 && p != null && !userManuallyDragged && !currentSafeZone.isEmpty) {
                    val m = selectionDetector.getScreenMetrics(force = false)
                    val topSafe = m.topSafeInset
                    val gap = dp(12)
                    val desiredY = if (anchoredAboveSelection) {
                        (currentSafeZone.top - gap - actualH).coerceAtLeast(topSafe)
                    } else {
                        val maxTop = (m.visibleBottom - actualH).coerceAtLeast(topSafe)
                        (currentSafeZone.bottom + gap).safeCoerceIn(topSafe, maxTop)
                    }
                    if (abs(p.y - desiredY) > dp(3)) {
                        p.y = desiredY
                        if (v.isAttachedToWindow) {
                            mainHandler.post {
                                if (v.isAttachedToWindow) {
                                    runCatching { windowManager.updateViewLayout(v, p) }
                                }
                            }
                        }
                    }
                }
            }
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE || event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    if (System.currentTimeMillis() - lastShownTime > 220L) {
                        hidePalette()
                    }
                    true
                } else {
                    false
                }
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(pal.bgHeaderInt)
            setPadding(dp(6), dp(3), dp(4), dp(3))
        }

        val posGlyph = if (anchoredAboveSelection) "^" else "v"
        val statsLabel = TextView(this).apply {
            text = "${words}W:${chars}C $posGlyph"
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(pal.fgMutedInt)
            includeFontPadding = false
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, dp(2), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        fun makePlainHeaderBtn(textStr: String, fgColor: Int, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = textStr
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(fgColor)
                includeFontPadding = false
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(2), dp(3), dp(2))
                background = makePressedDrawable(pal.bgPressedInt)
                setOnClickListener {
                    runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    onClick()
                }
            }
        }

        val twoColInitial = ToolPrefs.isTwoColumnGrid(this)
        val gridBtn = makePlainHeaderBtn(
            textStr = if (twoColInitial) "[1C]" else "[2C]",
            fgColor = if (twoColInitial) pal.accentGreenInt else pal.fgPrimaryInt
        ) {
            lastShownTime = System.currentTimeMillis()
            ToolPrefs.setTwoColumnGrid(this@SelAccessibilityService, !ToolPrefs.isTwoColumnGrid(this@SelAccessibilityService))
            renderPopupBody()
        }

        val collapseBtn = makePlainHeaderBtn("[_]", pal.fgPrimaryInt) {
            lastShownTime = System.currentTimeMillis()
            currentMode = PopupMode.COLLAPSED_PILL
            renderPopupBody()
        }

        val reorderBtn = makePlainHeaderBtn("[*]", pal.fgPrimaryInt) {
            lastShownTime = System.currentTimeMillis()
            currentMode = if (currentMode == PopupMode.REORDER) PopupMode.ACTIONS else PopupMode.REORDER
            renderPopupBody()
        }

        val closeBtn = makePlainHeaderBtn("[X]", pal.dangerRedInt) {
            hidePalette()
        }

        header.addView(statsLabel)
        header.addView(gridBtn)
        header.addView(collapseBtn)
        header.addView(reorderBtn)
        header.addView(closeBtn)
        enableDragAndEdgeSnapOnHeader(header, rootCard, windowParams)
        rootCard.addView(header)

        val hDiv = makeHorizontalRule(thick = true)
        rootCard.addView(hDiv)

        val scrollView = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxHeightSpec = MeasureSpec.makeMeasureSpec(dynamicMaxScrollHeight, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, maxHeightSpec)
            }
        }.apply {
            isVerticalScrollBarEnabled = true
            setPadding(0, 0, 0, 0)
            clipToPadding = false
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(1), dp(4), dp(2))
        }

        scrollView.addView(container)
        rootCard.addView(scrollView)

        popupView = rootCard
        popupParams = windowParams
        headerBarView = header
        headerDividerView = hDiv
        statsLabelView = statsLabel
        gridToggleBtn = gridBtn
        collapseBtnView = collapseBtn
        reorderToggleBtn = reorderBtn
        closeBtnView = closeBtn
        bodyContainer = container

        renderPopupBody()
        val added = runCatching { windowManager.addView(popupView, windowParams) }.isSuccess
        if (!added) {
            hidePalette()
        }
    }

    private fun renderPopupBody() {
        val container = bodyContainer ?: return
        val pal = ToolPrefs.resolvePalette(this)
        networkTools.cancelPending()
        applyPaletteToPopupChrome(pal)
        container.removeAllViews()

        headerBarView?.visibility = if (currentMode == PopupMode.COLLAPSED_PILL) View.GONE else View.VISIBLE
        headerDividerView?.visibility = if (currentMode == PopupMode.COLLAPSED_PILL) View.GONE else View.VISIBLE

        when (currentMode) {
            PopupMode.ACTIONS -> populateActionRows(container)
            PopupMode.COLLAPSED_PILL -> populateCollapsedPill(container)
            PopupMode.WEB_SEARCH_FOLDER -> populateWebSearchSubmenu(container)
            PopupMode.YOUTUBE_FOLDER -> populateYoutubeSubmenu(container)
            PopupMode.CLIP_HISTORY -> populateClipHistorySubmenu(container)
            PopupMode.MARKDOWN_FOLDER -> populateMarkdownSubmenu(container)
            PopupMode.REGEX_PIPE -> populateRegexPipeSubmenu(container)
            PopupMode.CUSTOM_FOLDER -> populateCustomFolderSubmenu(container, activeCustomFolder)
            PopupMode.QR_CODE -> populateQrView(container)
            PopupMode.MAGNET -> populateMagnetView(container)
            PopupMode.DEFINE_WORD -> populateDefineView(container)
            PopupMode.TRANSLATE -> populateTranslateView(container)
            PopupMode.EXTRACT -> populateExtractView(container)
            PopupMode.REORDER -> populateReorderView(container)
        }

        val params = popupParams
        if (params != null && !currentSafeZone.isEmpty) {
            applyStrictPlacementAndDynamicSize(currentSafeZone, params)
            val words = TextTransforms.countWords(selectedText)
            val chars = selectedText.length
            val posGlyph = if (anchoredAboveSelection) "^" else "v"
            val headerTxt = when (currentMode) {
                PopupMode.ACTIONS, PopupMode.COLLAPSED_PILL -> "${words}W:${chars}C $posGlyph"
                PopupMode.WEB_SEARCH_FOLDER -> "< BACK · WEB"
                PopupMode.YOUTUBE_FOLDER -> "< BACK · YT"
                PopupMode.CLIP_HISTORY -> "< BACK · CLIPS"
                PopupMode.MARKDOWN_FOLDER -> "< BACK · MD"
                PopupMode.REGEX_PIPE -> "< BACK · PIPE"
                PopupMode.CUSTOM_FOLDER -> "< BACK · ${activeCustomFolder.take(7)}"
                PopupMode.QR_CODE -> "< BACK · QR"
                PopupMode.MAGNET -> "< BACK · MAGNET"
                PopupMode.DEFINE_WORD -> "< BACK · DEFINE"
                PopupMode.TRANSLATE -> "< BACK · TRANS"
                PopupMode.EXTRACT -> "< BACK · LINKS"
                PopupMode.REORDER -> "< DONE · ORDER"
            }
            statsLabelView?.text = headerTxt
            statsLabelView?.setTextColor(if (currentMode == PopupMode.ACTIONS) pal.fgMutedInt else pal.accentPurpleInt)
        }

        popupView?.requestLayout()
        val view = popupView
        if (view != null && params != null && view.isAttachedToWindow) {
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun populateCollapsedPill(container: LinearLayout) {
        val pal = ToolPrefs.resolvePalette(this)
        val pill = TextView(this).apply {
            text = "+-- [sel_ >] --+"
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(pal.accentGreenInt)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                lastShownTime = System.currentTimeMillis()
                currentMode = PopupMode.ACTIONS
                renderPopupBody()
            }
        }
        container.addView(pill)
    }

    private fun isToolRelevantForSelection(toolId: String, rawText: String): Boolean {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return false

        val isHash = TextTransforms.extractTorrentHash(trimmed) != null || trimmed.startsWith("magnet:?", ignoreCase = true)
        val isSingleTokenUrl = !trimmed.contains(' ') && !trimmed.contains('\n') &&
            (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                TextTransforms.urlPattern.matches(trimmed))
        val hasAnyUrl = trimmed.contains("http://", ignoreCase = true) ||
            trimmed.contains("https://", ignoreCase = true) ||
            isSingleTokenUrl
        val wordCount = TextTransforms.countWords(trimmed)

        return when (toolId) {
            "magnet" -> isHash
            "clean_url" -> hasAnyUrl && (trimmed.contains('?') || trimmed.contains('&') || TextTransforms.trackingRegex.containsMatchIn(trimmed))
            "define" -> wordCount in 1..4 && !hasAnyUrl && !isHash && trimmed.any { it.isLetter() } && trimmed.length <= 48
            "sort" -> trimmed.contains('\n') && trimmed.lines().count { it.isNotBlank() } >= 2
            "clean_text" -> rawText.contains('\n') || rawText.contains("  ") || rawText.contains('\t') ||
                TextTransforms.invisibleCharsRegex.containsMatchIn(rawText)
            "extract" -> TextTransforms.hasExtractableEntities(trimmed) && !isSingleTokenUrl
            "case" -> trimmed.any { it.isLetter() } && !isSingleTokenUrl && !isHash
            "clip_history" -> ToolPrefs.getClipHistory(this).isNotEmpty()
            else -> true
        }
    }

    private fun formatBracketToken(rawGlyph: String): String {
        val g = rawGlyph.trim().removePrefix("[").removeSuffix("]").trim()
        return "[$g]"
    }

    private fun populateActionRows(list: LinearLayout) {
        val pinnedIds = HashSet<String>()

        // 0. 1-Tap In-Place Undo row if a recent text replacement occurred in the last 45s
        val prevText = undoOriginalFullText
        val uNode = undoNode
        if (prevText != null && uNode != null && System.currentTimeMillis() - undoTimestamp < 45000L) {
            list.addView(makeRow("< UNDO LAST EDIT", null) {
                runCatching { uNode.refresh() }
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prevText)
                }
                val ok = runCatching { uNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
                undoOriginalFullText = null
                undoNode = null
                Toast.makeText(
                    this,
                    if (ok) "<- Restored original text" else "[^C] Copied original text",
                    Toast.LENGTH_SHORT
                ).show()
                if (!ok) smoothCopyText(prevText)
                hidePalette()
            })
        }

        // 0b. Context-Aware Phone Bar ([CALL] [SMS] [WA] [W4B])
        TextTransforms.extractNepalPhone(selectedText)?.let { phone ->
            list.addView(makeNepalPhoneActionRow(phone))
            list.addView(makeHorizontalRule())
        }

        // 0c. Top Edit Row (Icon-Only, 4 items): [^C] [^V] [^X] [^A]
        var hasTopStrips = false
        if (!ToolPrefs.isToolHidden(this, "copy")) {
            pinnedIds.add("copy")
            list.addView(makeIconOnlyEditStrip())
            hasTopStrips = true
        }

        // 0d. User-Assignable Top Icon-Only Rows (Row 1 & Row 2, max 4 items each)
        for (rowNum in 1..2) {
            val rowTools = ToolPrefs.getIconRowTools(this, rowNum).take(ToolPrefs.MAX_TOOLS_PER_ICON_ROW)
            if (rowTools.isNotEmpty()) {
                rowTools.forEach { pinnedIds.add(it.id) }
                list.addView(makeCustomIconToolRow(rowTools, rowNum))
                hasTopStrips = true
            }
        }

        if (hasTopStrips) {
            list.addView(makeHorizontalRule())
        }

        // 1-2c. Instant contextual evaluators
        TextTransforms.evalSimpleMath(selectedText)?.let { ans ->
            list.addView(makeRow("= $ans", null) {
                ToolPrefs.recordUse(this, "math")
                applyResult(ans)
            })
        }
        TextTransforms.evalUnitConversion(selectedText)?.let { conv ->
            list.addView(makeRow(conv, null) {
                ToolPrefs.recordUse(this, "unit_conv")
                applyResult(conv.removePrefix("= ").trim())
            })
        }
        TextTransforms.evalTimestamp(selectedText)?.let { formattedDate ->
            list.addView(makeRow(formattedDate, null) {
                ToolPrefs.recordUse(this, "epoch_conv")
                applyResult(formattedDate)
            })
        }
        TextTransforms.evalBase64Decode(selectedText)?.let { decoded ->
            val preview = decoded.replace("\n", " ").let { if (it.length > 20) it.take(19) + "…" else it }
            list.addView(makeRow("B64 -> $preview", null) {
                ToolPrefs.recordUse(this, "b64_decode")
                applyResult(decoded)
            })
        }

        // 3. Context-pinned HASH -> MAGNET
        if (isToolRelevantForSelection("magnet", selectedText) && !ToolPrefs.isToolHidden(this, "magnet")) {
            pinnedIds.add("magnet")
            list.addView(makeRow("HASH -> MAGNET", null) {
                ToolPrefs.recordUse(this, "magnet")
                lastShownTime = System.currentTimeMillis()
                currentMode = PopupMode.MAGNET
                renderPopupBody()
            })
        }

        // 4. Context-pinned CLEAN URL
        if (isToolRelevantForSelection("clean_url", selectedText) && !ToolPrefs.isToolHidden(this, "clean_url")) {
            pinnedIds.add("clean_url")
            list.addView(makeRow("CLEAN URL", null) {
                ToolPrefs.recordUse(this, "clean_url")
                applyResult(TextTransforms.cleanUrl(selectedText))
            })
        }

        // 5. Context-filtered, sorted tools
        val orderedTools = ToolPrefs.getSortedToolsForPopup(this).filter {
            it.id !in pinnedIds && isToolRelevantForSelection(it.id, selectedText)
        }
        val showCounts = ToolPrefs.isSortMostUsed(this)
        val useTwoCol = ToolPrefs.isTwoColumnGrid(this)

        if (useTwoCol) {
            var i = 0
            while (i < orderedTools.size) {
                val t1 = orderedTools[i]
                val t2 = orderedTools.getOrNull(i + 1)
                if (t2 != null) {
                    list.addView(makeTwoColumnPairRow(t1, t2))
                    i += 2
                } else {
                    val c1 = ToolPrefs.getUseCount(this, t1.id)
                    val b1 = if (showCounts && c1 > 0) "$c1" else null
                    list.addView(makeRow(t1.label, b1, onLongClick = { executeToolLongPress(t1) }) {
                        ToolPrefs.recordUse(this, t1.id)
                        executeTool(t1)
                    })
                    i += 1
                }
            }
        } else {
            for (tool in orderedTools) {
                val count = ToolPrefs.getUseCount(this, tool.id)
                val badge = if (showCounts && count > 0) "$count" else null
                list.addView(makeRow(tool.label, badge, onLongClick = { executeToolLongPress(tool) }) {
                    ToolPrefs.recordUse(this, tool.id)
                    executeTool(tool)
                })
            }
        }
    }

    private fun makePressedDrawable(pressedColor: Int, radiusPx: Float = 0f): StateListDrawable {
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                GradientDrawable().apply {
                    setColor(pressedColor)
                    if (radiusPx > 0f) cornerRadius = radiusPx
                }
            )
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }
    }

    private fun makeIconOnlyEditStrip(): View {
        val pal = ToolPrefs.resolvePalette(this)
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun makeBracketCell(token: String, fgColor: Int, onLongPress: () -> Unit, onTap: () -> Unit): TextView {
            return TextView(this).apply {
                text = token
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(fgColor)
                includeFontPadding = false
                gravity = Gravity.CENTER
                isSingleLine = true
                setPadding(dp(1), dp(4), dp(1), dp(4))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                background = makePressedDrawable(pal.bgPressedInt)
                setOnClickListener {
                    runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    onTap()
                }
                setOnLongClickListener {
                    runCatching { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
                    onLongPress()
                    true
                }
            }
        }

        strip.addView(
            makeBracketCell(
                token = "[^C]",
                fgColor = pal.accentGreenInt,
                onLongPress = {
                    val existing = runCatching {
                        clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
                    }.getOrNull().orEmpty()
                    val combined = if (existing.isNotBlank()) "$existing\n$selectedText" else selectedText
                    smoothCopyText(combined, "[+^C] Appended to clipboard")
                    hidePalette()
                },
                onTap = {
                    ToolPrefs.recordUse(this, "copy")
                    smoothCopyText(selectedText)
                    hidePalette()
                }
            )
        )
        strip.addView(
            makeBracketCell(
                token = "[^V]",
                fgColor = pal.fgPrimaryInt,
                onLongPress = {
                    lastShownTime = System.currentTimeMillis()
                    currentMode = PopupMode.CLIP_HISTORY
                    renderPopupBody()
                },
                onTap = { performPasteAction() }
            )
        )
        strip.addView(
            makeBracketCell(
                token = "[^X]",
                fgColor = pal.fgPrimaryInt,
                onLongPress = { Toast.makeText(this, "[^X] Cut selection", Toast.LENGTH_SHORT).show() },
                onTap = { performCutAction() }
            )
        )
        strip.addView(
            makeBracketCell(
                token = "[^A]",
                fgColor = pal.fgPrimaryInt,
                onLongPress = { Toast.makeText(this, "[^A] Select All", Toast.LENGTH_SHORT).show() },
                onTap = { performSelectAllAction() }
            )
        )
        return strip
    }

    private fun makeCustomIconToolRow(rowTools: List<ToolSpec>, rowNumber: Int): View {
        val pal = ToolPrefs.resolvePalette(this)
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val fgColor = if (rowNumber == 1) pal.accentPurpleInt else pal.accentCyanInt

        rowTools.take(ToolPrefs.MAX_TOOLS_PER_ICON_ROW).forEach { tool ->
            val token = formatBracketToken(tool.glyph)
            val cell = TextView(this).apply {
                text = token
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(fgColor)
                includeFontPadding = false
                gravity = Gravity.CENTER
                isSingleLine = true
                setPadding(dp(1), dp(4), dp(1), dp(4))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                background = makePressedDrawable(pal.bgPressedInt)
                setOnClickListener {
                    runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    ToolPrefs.recordUse(this@SelAccessibilityService, tool.id)
                    executeTool(tool)
                }
                setOnLongClickListener {
                    runCatching { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
                    Toast.makeText(this@SelAccessibilityService, "$token ${tool.label}", Toast.LENGTH_SHORT).show()
                    executeToolLongPress(tool)
                    true
                }
            }
            strip.addView(cell)
        }
        return strip
    }

    private fun performPasteAction() {
        val clipText = runCatching {
            clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull().orEmpty()

        val targetNode = currentNode?.takeIf { it.isEditable } ?: selectionDetector.findFocusedEditableNode()
        if (targetNode != null) {
            runCatching { targetNode.refresh() }
            val full = targetNode.text?.toString() ?: ""
            undoOriginalFullText = full
            undoNode = targetNode
            undoTimestamp = System.currentTimeMillis()

            var ok = runCatching { targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)
            if (!ok && clipText.isNotEmpty()) {
                val s = targetNode.textSelectionStart
                val e = targetNode.textSelectionEnd
                val start = minOf(s, e).coerceAtLeast(0)
                val end = maxOf(s, e).coerceAtMost(full.length)
                val updated = if (end >= start && start <= full.length) {
                    full.replaceRange(start, end, clipText)
                } else {
                    full + clipText
                }
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
                }
                ok = runCatching { targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
            }
            Toast.makeText(
                this,
                if (ok) "[^V] Pasted from clipboard" else "[^V] Clipboard ready to paste",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(this, "[^V] Focus a text box to paste", Toast.LENGTH_SHORT).show()
        }
        hidePalette()
    }

    private fun performCutAction() {
        lastInternalCopyTime = System.currentTimeMillis()
        clipboardManager.setPrimaryClip(ClipData.newPlainText("sel", selectedText))
        ToolPrefs.addClipToHistory(this, selectedText)

        val targetNode = currentNode?.takeIf { it.isEditable } ?: selectionDetector.findFocusedEditableNode()
        var cutOk = false
        if (targetNode != null) {
            runCatching { targetNode.refresh() }
            val full = targetNode.text?.toString() ?: ""
            val s = targetNode.textSelectionStart
            val e = targetNode.textSelectionEnd
            val start = minOf(s, e)
            val end = maxOf(s, e)
            undoOriginalFullText = full
            undoNode = targetNode
            undoTimestamp = System.currentTimeMillis()

            cutOk = runCatching { targetNode.performAction(AccessibilityNodeInfo.ACTION_CUT) }.getOrDefault(false)
            if (!cutOk && start >= 0 && end > start && end <= full.length) {
                val updated = full.removeRange(start, end)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
                }
                cutOk = runCatching { targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
            }
        }
        Toast.makeText(
            this,
            if (cutOk) "[^X] Cut to clipboard" else "[^C] Copied (read-only text)",
            Toast.LENGTH_SHORT
        ).show()
        hidePalette()
    }

    private fun performSelectAllAction() {
        val targetNode = currentNode ?: selectionDetector.findFocusedEditableOrSelectableNode()
        if (targetNode != null) {
            runCatching { targetNode.refresh() }
            val full = targetNode.text?.toString() ?: ""
            var ok = false
            if (full.isNotEmpty()) {
                val args = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, full.length)
                }
                ok = runCatching {
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
                }.getOrDefault(false)
            }
            if (!ok) {
                runCatching { targetNode.performAction(android.R.id.selectAll) }
            }
            if (full.isNotEmpty()) {
                selectedText = full
                lastShownTime = System.currentTimeMillis()
                Toast.makeText(this, "[^A] Selected all (${full.length}C)", Toast.LENGTH_SHORT).show()
                renderPopupBody()
                return
            }
        }
        Toast.makeText(this, "[^A] Select All requested", Toast.LENGTH_SHORT).show()
    }

    private fun makeNepalPhoneActionRow(phone: NepalPhone): View {
        val pal = ToolPrefs.resolvePalette(this)
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
                bottomMargin = dp(3)
            }
        }

        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun makePhonePill(labelStr: String, fgColor: Int, marginEndDp: Int, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = labelStr
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(fgColor)
                gravity = Gravity.CENTER
                isSingleLine = true
                includeFontPadding = false
                setPadding(dp(2), dp(4), dp(2), dp(4))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(marginEndDp)
                }
                background = makePressedDrawable(pal.bgPressedInt, dp(2).toFloat())
                setOnClickListener {
                    runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    onClick()
                }
            }
        }

        strip.addView(makePhonePill("[CALL]", pal.accentGreenInt, 2) {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone.dialNumber)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { startActivity(dialIntent) }
            hidePalette()
        })
        strip.addView(makePhonePill("[SMS]", pal.fgPrimaryInt, 2) {
            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(phone.dialNumber)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { startActivity(smsIntent) }.isFailure) {
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("sms:${Uri.encode(phone.dialNumber)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { startActivity(fallback) }
            }
            hidePalette()
        })
        strip.addView(makePhonePill("[WA]", pal.accentGreenInt, 2) {
            openWhatsAppNumber(phone.waDigits, isBusiness = false)
        })
        strip.addView(makePhonePill("[W4B]", pal.accentCyanInt, 0) {
            openWhatsAppNumber(phone.waDigits, isBusiness = true)
        })

        wrapper.addView(strip)
        return wrapper
    }

    private fun openWhatsAppNumber(waDigits: String, isBusiness: Boolean) {
        val targetPkg = if (isBusiness) "com.whatsapp.w4b" else "com.whatsapp"
        val appLabel = if (isBusiness) "WhatsApp Business" else "WhatsApp"

        val nativeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?phone=$waDigits")).apply {
            setPackage(targetPkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { startActivity(nativeIntent) }.isSuccess) {
            hidePalette()
            return
        }

        val webPkgIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$waDigits")).apply {
            setPackage(targetPkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { startActivity(webPkgIntent) }.isSuccess) {
            hidePalette()
            return
        }

        Toast.makeText(this, "$appLabel not found — opening wa.me", Toast.LENGTH_SHORT).show()
        openUrl("https://wa.me/$waDigits")
    }

    private fun shortGridLabel(tool: ToolSpec): String {
        return when (tool.id) {
            "screen_grab" -> "LENS GRAB"
            "web_search_folder" -> "WEB >"
            "youtube_folder" -> "YOUTUBE >"
            "clip_history" -> "CLIPS >"
            "markdown_folder" -> "MARKDOWN >"
            "regex_pipe" -> "REGEX/PIPE >"
            "define" -> "DEFINE"
            "translate" -> "TRANSLATE"
            "qr" -> "QR CODE"
            "magnet" -> "MAGNET"
            "clean_url" -> "CLEAN URL"
            "extract" -> "LINKS"
            "clean_text" -> "CLEAN TXT"
            "sort" -> "SORT LINES"
            "case" -> "CYCLE CASE"
            "speak" -> "SPEAK"
            "log" -> "APPEND LOG"
            else -> tool.label.replace(" ▸", " >").take(12)
        }
    }

    private fun executeToolLongPress(tool: ToolSpec) {
        val trimmed = selectedText.trim()
        val encoded = Uri.encode(trimmed)
        when (tool.id) {
            "web_search_folder" -> {
                ToolPrefs.recordUse(this, "web_search_folder")
                openUrl("https://www.google.com/search?q=$encoded")
            }
            "youtube_folder" -> {
                ToolPrefs.recordUse(this, "youtube_folder")
                openUrl("https://www.youtube.com/results?search_query=$encoded")
            }
            "markdown_folder" -> {
                ToolPrefs.recordUse(this, "markdown_folder")
                applyResult("**$trimmed**")
            }
            else -> executeTool(tool)
        }
    }

    private fun makeTwoColumnPairRow(t1: ToolSpec, t2: ToolSpec): View {
        val pal = ToolPrefs.resolvePalette(this)
        val pairRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun makeCell(tool: ToolSpec): View {
            return TextView(this).apply {
                text = shortGridLabel(tool)
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(pal.fgPrimaryInt)
                includeFontPadding = false
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                background = makePressedDrawable(pal.bgPressedInt)
                setOnClickListener {
                    runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    ToolPrefs.recordUse(this@SelAccessibilityService, tool.id)
                    executeTool(tool)
                }
                setOnLongClickListener {
                    runCatching { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
                    executeToolLongPress(tool)
                    true
                }
            }
        }

        val midSep = TextView(this).apply {
            text = "|"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(pal.dividerInt)
            includeFontPadding = false
            setPadding(dp(2), 0, dp(2), 0)
        }

        pairRow.addView(makeCell(t1))
        pairRow.addView(midSep)
        pairRow.addView(makeCell(t2))
        return pairRow
    }

    private fun populateClipHistorySubmenu(container: LinearLayout) {
        val pal = ToolPrefs.resolvePalette(this)

        container.addView(makeRow("< BACK TO TOOLS", null) {
            lastShownTime = System.currentTimeMillis()
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeRow("CLEAR UNPINNED", null) {
            ToolPrefs.clearUnpinnedClips(this)
            renderPopupBody()
        })
        container.addView(makeHorizontalRule())

        val clips = ToolPrefs.getClipHistory(this)
        if (clips.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Clipboard history is empty."
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(pal.fgMutedInt)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            })
            return
        }

        for (entry in clips) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }

            val pinBtn = TextView(this).apply {
                text = if (entry.pinned) "[*]" else "[.]"
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(if (entry.pinned) pal.accentGreenInt else pal.fgMutedInt)
                setPadding(dp(4), dp(2), dp(8), dp(2))
                setOnClickListener {
                    ToolPrefs.togglePinClip(this@SelAccessibilityService, entry.text)
                    renderPopupBody()
                }
            }

            val textBtn = TextView(this).apply {
                val preview = entry.text.replace("\n", " ↵ ").let {
                    if (it.length > 24) it.take(23) + "…" else it
                }
                text = preview
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(pal.fgPrimaryInt)
                isSingleLine = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setOnClickListener { applyResult(entry.text) }
            }

            val delBtn = TextView(this).apply {
                text = "[x]"
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(pal.dangerRedInt)
                setPadding(dp(6), dp(2), dp(2), dp(2))
                setOnClickListener {
                    ToolPrefs.deleteClip(this@SelAccessibilityService, entry.text)
                    renderPopupBody()
                }
            }

            row.addView(pinBtn)
            row.addView(textBtn)
            row.addView(delBtn)
            container.addView(row)
        }
    }

    private fun populateMarkdownSubmenu(container: LinearLayout) {
        container.addView(makeRow("< BACK TO TOOLS", null) {
            lastShownTime = System.currentTimeMillis()
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeHorizontalRule())

        for (mdTool in ToolPrefs.loadMarkdownTools(this)) {
            container.addView(makeRow(mdTool.label, null) {
                ToolPrefs.recordUse(this, "markdown_folder")
                ToolPrefs.recordUse(this, mdTool.id)
                val out = if (mdTool.customTarget != null) {
                    TextTransforms.executeCustomTargetTransform(mdTool.customTarget, selectedText)
                } else {
                    TextTransforms.applyMarkdownTool(mdTool.id, selectedText)
                }
                applyResult(out)
            })
        }
    }

    private fun populateRegexPipeSubmenu(container: LinearLayout) {
        container.addView(makeRow("< BACK TO TOOLS", null) {
            lastShownTime = System.currentTimeMillis()
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeHorizontalRule())

        for (pTool in ToolPrefs.loadRegexPipeTools(this)) {
            container.addView(makeRow(pTool.label, null) {
                ToolPrefs.recordUse(this, "regex_pipe")
                ToolPrefs.recordUse(this, pTool.id)
                val out = if (pTool.customTarget != null) {
                    TextTransforms.executeCustomTargetTransform(pTool.customTarget, selectedText)
                } else {
                    TextTransforms.applyPipeTool(pTool.id, selectedText)
                }
                applyResult(out)
            })
        }
    }

    private fun populateWebSearchSubmenu(container: LinearLayout) {
        container.addView(makeRow("< BACK TO TOOLS", null) {
            lastShownTime = System.currentTimeMillis()
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeHorizontalRule())

        for (engine in ToolPrefs.loadWebSearchTools(this)) {
            container.addView(makeRow(engine.label, null) {
                ToolPrefs.recordUse(this, "web_search_folder")
                ToolPrefs.recordUse(this, engine.id)
                executeTool(engine)
            })
        }
    }

    private fun populateYoutubeSubmenu(container: LinearLayout) {
        container.addView(makeRow("< BACK TO TOOLS", null) {
            lastShownTime = System.currentTimeMillis()
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeHorizontalRule())

        for (ytTool in ToolPrefs.loadYoutubeTools(this)) {
            container.addView(makeRow(ytTool.label, null) {
                ToolPrefs.recordUse(this, "youtube_folder")
                ToolPrefs.recordUse(this, ytTool.id)
                executeTool(ytTool)
            })
        }
    }

    private fun populateCustomFolderSubmenu(container: LinearLayout, folderName: String) {
        val pal = ToolPrefs.resolvePalette(this)
        container.addView(makeRow("< BACK ($folderName)", null) {
            lastShownTime = System.currentTimeMillis()
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeHorizontalRule())

        val items = ToolPrefs.loadCustomFolderTools(this, folderName)
        if (items.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Folder $folderName is empty.\nAssign tools in Settings -> TOOLS."
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(pal.fgMutedInt)
                setPadding(dp(10), dp(10), dp(10), dp(10))
            })
            return
        }

        for (item in items) {
            container.addView(makeRow(item.label, null) {
                ToolPrefs.recordUse(this, "folder_custom_$folderName")
                ToolPrefs.recordUse(this, item.id)
                executeTool(item)
            })
        }
    }

    private fun speakTextLazy(text: String) {
        val snippet = text.take(500)
        if (ttsReady && tts != null) {
            tts?.speak(snippet, TextToSpeech.QUEUE_FLUSH, null, "sel_tts")
            Toast.makeText(this, "Speaking…", Toast.LENGTH_SHORT).show()
            return
        }
        pendingTtsText = snippet
        Toast.makeText(this, "Starting TTS…", Toast.LENGTH_SHORT).show()
        if (tts == null) {
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.US
                    ttsReady = true
                    val queued = pendingTtsText
                    pendingTtsText = null
                    if (!queued.isNullOrBlank()) {
                        tts?.speak(queued, TextToSpeech.QUEUE_FLUSH, null, "sel_tts")
                    }
                }
            }
        }
    }

    private fun executeTool(tool: ToolSpec) {
        val trimmed = selectedText.trim()
        val encoded = Uri.encode(trimmed)

        if (tool.folderName != null && tool.customTarget == null) {
            lastShownTime = System.currentTimeMillis()
            activeCustomFolder = tool.folderName
            currentMode = PopupMode.CUSTOM_FOLDER
            renderPopupBody()
            return
        }

        if (tool.customTarget != null) {
            val target = tool.customTarget.trim()
            if (target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)) {
                openUrl(target.replace("{text}", encoded))
            } else {
                applyResult(TextTransforms.executeCustomTargetTransform(target, selectedText))
            }
            return
        }

        when (tool.id) {
            "copy" -> {
                smoothCopyText(selectedText)
                hidePalette()
            }
            "screen_grab" -> triggerScreenGrab(delayMs = 110L)
            "clip_history" -> switchPopupMode(PopupMode.CLIP_HISTORY)
            "markdown_folder" -> switchPopupMode(PopupMode.MARKDOWN_FOLDER)
            "regex_pipe" -> switchPopupMode(PopupMode.REGEX_PIPE)
            "define" -> switchPopupMode(PopupMode.DEFINE_WORD)
            "translate" -> switchPopupMode(PopupMode.TRANSLATE)
            "qr" -> switchPopupMode(PopupMode.QR_CODE)
            "magnet" -> switchPopupMode(PopupMode.MAGNET)
            "clean_url" -> applyResult(TextTransforms.cleanUrl(selectedText))
            "extract" -> switchPopupMode(PopupMode.EXTRACT)
            "speak" -> speakTextLazy(trimmed)
            "clean_text" -> applyResult(TextTransforms.cleanText(selectedText))
            "web_search_folder" -> switchPopupMode(PopupMode.WEB_SEARCH_FOLDER)
            "youtube_folder" -> switchPopupMode(PopupMode.YOUTUBE_FOLDER)
            "case" -> applyResult(TextTransforms.cycleCase(selectedText))
            "sort" -> {
                val sorted = selectedText.lines().filter { it.isNotBlank() }.distinct().sorted().joinToString("\n")
                applyResult(sorted)
            }
            "log" -> {
                appendToScratchpad(selectedText)
                Toast.makeText(this, "Appended to log", Toast.LENGTH_SHORT).show()
                hidePalette()
            }
        }
    }

    private fun switchPopupMode(mode: PopupMode) {
        lastShownTime = System.currentTimeMillis()
        currentMode = mode
        renderPopupBody()
    }

    private fun populateMagnetView(container: LinearLayout) {
        val pal = ToolPrefs.resolvePalette(this)
        container.addView(makeRow("< BACK TO TOOLS", null) {
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeHorizontalRule())

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        val hash = TextTransforms.extractTorrentHash(selectedText)
        val magnetUri = when {
            hash != null -> TextTransforms.buildMagnetUri(hash)
            selectedText.trim().startsWith("magnet:?", ignoreCase = true) -> selectedText.trim()
            else -> "magnet:?xt=urn:btih:${selectedText.trim().replace(TextTransforms.whitespaceRegex, "")}"
        }

        box.addView(TextView(this).apply {
            text = if (hash != null) "[0x] MAGNET READY (${hash.length}C)" else "[0x] MAGNET URI"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(pal.accentGreenInt)
            setPadding(0, 0, 0, dp(6))
        })
        box.addView(TextView(this).apply {
            text = magnetUri
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(pal.fgPrimaryInt)
            setBackgroundColor(pal.bgSurfaceInt)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            maxLines = 5
        })
        box.addView(TextView(this).apply {
            text = "[ [^C] COPY MAGNET ]"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(pal.accentGreenInt)
            setPadding(0, dp(10), 0, dp(4))
            setOnClickListener {
                smoothCopyText(magnetUri, "[^C] Magnet link copied")
                hidePalette()
            }
        })
        box.addView(TextView(this).apply {
            text = "[ [^V] PASTE / REPLACE ]"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(pal.fgPrimaryInt)
            setPadding(0, dp(6), 0, dp(4))
            setOnClickListener { applyResult(magnetUri) }
        })
        box.addView(TextView(this).apply {
            text = "[ OPEN IN TORRENT APP ]"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(pal.accentPurpleInt)
            setPadding(0, dp(6), 0, 0)
            setOnClickListener {
                smoothCopyText(magnetUri, "[0x] Opening magnet…")
                openUrl(magnetUri)
            }
        })
        container.addView(box)
    }

    private fun populateQrView(container: LinearLayout) {
        val pal = ToolPrefs.resolvePalette(this)
        container.addView(makeRow("< BACK TO TOOLS", null) {
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeHorizontalRule())

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val content = selectedText.trim().take(1200)
        val bmp = generateQrBitmap(content, dp(180))
        if (bmp != null) {
            box.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                layoutParams = LinearLayout.LayoutParams(dp(180), dp(180))
            })
        } else {
            box.addView(TextView(this).apply {
                text = "Could not encode QR"
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(pal.dangerRedInt)
            })
        }

        box.addView(TextView(this).apply {
            text = content.take(64) + if (content.length > 64) "…" else ""
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(pal.fgMutedInt)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        container.addView(box)
    }

    private fun generateQrBitmap(text: String, sizePx: Int): Bitmap? {
        return runCatching {
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
                put(EncodeHintType.CHARACTER_SET, "UTF-8")
                put(EncodeHintType.MARGIN, 1)
            }
            val matrix = QRCodeWriter().encode(text.ifBlank { " " }, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val offset = y * w
                for (x in 0 until w) {
                    pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        }.getOrNull()
    }

    private fun populateDefineView(container: LinearLayout) {
        val pal = ToolPrefs.resolvePalette(this)
        val rawTerm = selectedText.trim()
        val firstWord = rawTerm.split(TextTransforms.whitespaceRegex).firstOrNull()
            ?.trim { !it.isLetterOrDigit() && it != '-' }
            ?.ifBlank { rawTerm.take(32) }
            ?: rawTerm.take(32)

        container.addView(makeRow("< BACK TO TOOLS", null) {
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeHorizontalRule())

        val bodyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        bodyBox.addView(TextView(this).apply {
            text = "[?=] ${firstWord.uppercase()}"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(pal.accentGreenInt)
            setPadding(0, 0, 0, dp(6))
        })

        val defTextView = TextView(this).apply {
            text = "Looking up \"$firstWord\"…"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(pal.fgPrimaryInt)
            setLineSpacing(0f, 1.18f)
        }

        val copyDefBtn = TextView(this).apply {
            text = "[ [^C] COPY DEFINITION ]"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(pal.accentGreenInt)
            setPadding(0, dp(10), 0, 0)
            visibility = View.GONE
            setOnClickListener {
                smoothCopyText(defTextView.text.toString(), "[^C] Definition copied")
            }
        }

        bodyBox.addView(defTextView)
        bodyBox.addView(copyDefBtn)
        container.addView(bodyBox)

        networkTools.lookupDefinition(firstWord, rawTerm) { resultText ->
            if (currentMode == PopupMode.DEFINE_WORD && popupView?.isAttachedToWindow == true) {
                defTextView.text = resultText
                copyDefBtn.visibility = View.VISIBLE
                relayoutPopupWindow()
            }
        }
    }

    private fun populateTranslateView(container: LinearLayout) {
        val pal = ToolPrefs.resolvePalette(this)
        val raw = selectedText.trim().take(400)

        container.addView(makeRow("< BACK TO TOOLS", null) {
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeHorizontalRule())

        val bodyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        bodyBox.addView(TextView(this).apply {
            text = "[<>] TRANSLATE (-> EN)"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(pal.accentGreenInt)
            setPadding(0, 0, 0, dp(6))
        })

        val transTextView = TextView(this).apply {
            text = "Translating…"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(pal.fgPrimaryInt)
            setLineSpacing(0f, 1.18f)
        }

        val useBtn = TextView(this).apply {
            text = "[ [^C] COPY / APPLY TRANSLATION ]"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(pal.accentGreenInt)
            setPadding(0, dp(10), 0, 0)
            visibility = View.GONE
            setOnClickListener { applyResult(transTextView.text.toString()) }
        }

        bodyBox.addView(transTextView)
        bodyBox.addView(useBtn)
        container.addView(bodyBox)

        networkTools.lookupTranslation(raw) { translated ->
            if (currentMode == PopupMode.TRANSLATE && popupView?.isAttachedToWindow == true) {
                transTextView.text = translated
                useBtn.visibility = View.VISIBLE
                relayoutPopupWindow()
            }
        }
    }

    private fun relayoutPopupWindow() {
        popupView?.requestLayout()
        val v = popupView
        val p = popupParams
        if (v != null && p != null && v.isAttachedToWindow) {
            runCatching { windowManager.updateViewLayout(v, p) }
        }
    }

    private fun populateExtractView(container: LinearLayout) {
        val pal = ToolPrefs.resolvePalette(this)
        container.addView(makeRow("< BACK TO TOOLS", null) {
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeHorizontalRule())

        val matches = TextTransforms.extractEntities(selectedText, limit = 12)
        if (matches.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No URLs, emails, phones, or tags found."
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(pal.fgMutedInt)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            })
            return
        }

        matches.forEach { entity ->
            container.addView(makeRow("[${entity.type}] ${entity.value.take(24)}", null) {
                applyResult(entity.value)
            })
        }
    }

    private fun populateReorderView(container: LinearLayout) {
        val pal = ToolPrefs.resolvePalette(this)
        container.addView(makeRow("< DONE (MAX 4 PER TOP ROW)", null) {
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })

        val r1Count = ToolPrefs.getIconRowIds(this, 1).size
        val r2Count = ToolPrefs.getIconRowIds(this, 2).size
        container.addView(makeRow("LOAD 2-ROW PRESET (R1:$r1Count/4 R2:$r2Count/4)", null) {
            ToolPrefs.applyDefaultTwoIconRowsPreset(this)
            Toast.makeText(this, "Assigned 4 icons each to Row 1 & Row 2", Toast.LENGTH_SHORT).show()
            renderPopupBody()
        })

        if (r1Count > 0 || r2Count > 0) {
            container.addView(makeRow("CLEAR EXTRA TOP ROWS", null) {
                ToolPrefs.clearIconRow(this, 1)
                ToolPrefs.clearIconRow(this, 2)
                renderPopupBody()
            })
        }

        val mostUsedOn = ToolPrefs.isSortMostUsed(this)
        container.addView(makeRow(if (mostUsedOn) "MOST USED TOP: ON" else "MOST USED TOP: OFF", null) {
            ToolPrefs.setSortMostUsed(this, !mostUsedOn)
            renderPopupBody()
        })

        if (activePackage.isNotBlank() && activePackage != packageName) {
            val isMuted = ToolPrefs.isAppBlacklisted(this, activePackage)
            val shortPkg = activePackage.substringAfterLast('.').uppercase()
            container.addView(makeRow(if (isMuted) "UNMUTE IN $shortPkg" else "MUTE IN $shortPkg", null) {
                ToolPrefs.setAppBlacklisted(this, activePackage, !isMuted)
                Toast.makeText(
                    this,
                    if (!isMuted) "Muted auto-popup in $activePackage" else "Unmuted $activePackage",
                    Toast.LENGTH_SHORT
                ).show()
                if (!isMuted) hidePalette() else renderPopupBody()
            })
        }

        container.addView(makeHorizontalRule())

        val dragList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val customFolders = ToolPrefs.getCustomFolders(this)
        ToolPrefs.getAllSortedTools(this).forEach { tool ->
            val hidden = ToolPrefs.isToolHidden(this, tool.id)
            val assignedRow = ToolPrefs.getToolIconRow(this, tool.id)
            val assignedFolder = ToolPrefs.getToolFolderAssignment(this, tool.id)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                tag = tool.id
            }

            val dragHandle = TextView(this).apply {
                text = "::"
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(pal.accentGreenInt)
                setPadding(dp(4), dp(2), dp(8), dp(2))
            }

            val cnt = ToolPrefs.getUseCount(this, tool.id)
            val tok = formatBracketToken(tool.glyph)
            val label = TextView(this).apply {
                text = "$tok ${tool.label}" + if (cnt > 0) " ($cnt)" else ""
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(if (hidden) pal.fgMutedInt else pal.fgPrimaryInt)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            row.addView(dragHandle)
            row.addView(label)

            if (customFolders.isNotEmpty() && tool.id != "copy" && !tool.id.startsWith("folder_custom_")) {
                row.addView(TextView(this).apply {
                    text = if (assignedFolder != null) "[$assignedFolder/]" else "[ROOT]"
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                    setTextColor(if (assignedFolder != null) pal.accentOrangeInt else pal.fgMutedInt)
                    setPadding(dp(4), dp(3), dp(4), dp(3))
                    setOnClickListener {
                        val nextF = ToolPrefs.cycleToolFolderAssignment(this@SelAccessibilityService, tool.id)
                        Toast.makeText(
                            this@SelAccessibilityService,
                            if (nextF != null) "Moved ${tool.label} -> $nextF >" else "Returned ${tool.label} to root list",
                            Toast.LENGTH_SHORT
                        ).show()
                        renderPopupBody()
                    }
                })
            }

            if (tool.id != "copy") {
                row.addView(TextView(this).apply {
                    text = when (assignedRow) {
                        1 -> "[*R1]"
                        2 -> "[*R2]"
                        else -> "[LIST]"
                    }
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                    setTextColor(
                        when (assignedRow) {
                            1 -> pal.accentPurpleInt
                            2 -> pal.accentCyanInt
                            else -> pal.fgMutedInt
                        }
                    )
                    setPadding(dp(5), dp(3), dp(5), dp(3))
                    setOnClickListener {
                        val nextRow = ToolPrefs.cycleToolIconRow(this@SelAccessibilityService, tool.id)
                        val msg = when (nextRow) {
                            1 -> "$tok pinned to Top Row 1 (max 4)"
                            2 -> "$tok pinned to Top Row 2 (max 4)"
                            else -> "${tool.label} returned to text-only list"
                        }
                        Toast.makeText(this@SelAccessibilityService, msg, Toast.LENGTH_SHORT).show()
                        renderPopupBody()
                    }
                })
            }

            row.addView(TextView(this).apply {
                text = if (hidden) "[ ]" else "[x]"
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(if (hidden) pal.fgMutedInt else pal.accentGreenInt)
                setPadding(dp(5), dp(2), dp(2), dp(2))
                setOnClickListener {
                    ToolPrefs.setToolHidden(this@SelAccessibilityService, tool.id, !hidden)
                    renderPopupBody()
                }
            })

            ToolPrefs.attachDragHandle(dragHandle, row, dragList) { renderPopupBody() }
            dragList.addView(row)
        }

        container.addView(dragList)
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
        hidePalette()
    }

    private fun makeRow(
        label: String,
        countBadge: String?,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit
    ): View {
        val pal = ToolPrefs.resolvePalette(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = makePressedDrawable(pal.bgPressedInt)
            setOnClickListener {
                runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                onClick()
            }
            if (onLongClick != null) {
                setOnLongClickListener {
                    runCatching { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
                    onLongClick()
                    true
                }
            }
        }

        val cleanLabel = label.replace("▸", ">").replace("→", "->").trim()
        val isBackRow = cleanLabel.startsWith("< ")

        row.addView(TextView(this).apply {
            text = cleanLabel
            typeface = if (isBackRow) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(if (isBackRow) pal.accentPurpleInt else pal.fgPrimaryInt)
            includeFontPadding = false
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        if (!countBadge.isNullOrBlank()) {
            row.addView(TextView(this).apply {
                text = "($countBadge)"
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                setTextColor(pal.fgMutedInt)
                includeFontPadding = false
                setPadding(dp(4), 0, 0, 0)
            })
        }

        return row
    }

    private fun makeHorizontalRule(thick: Boolean = false): View {
        val pal = ToolPrefs.resolvePalette(this)
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                if (!thick) {
                    topMargin = dp(2)
                    bottomMargin = dp(2)
                }
            }
            setBackgroundColor(if (thick) pal.borderInt else pal.dividerInt)
        }
    }

    private fun enableDragAndEdgeSnapOnHeader(
        header: View,
        rootView: View,
        params: WindowManager.LayoutParams
    ) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        header.setOnTouchListener { v, event ->
            runCatching {
                val screen = selectionDetector.getRealScreenBounds()
                val isBottomGrav = (params.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        mainHandler.removeCallbacks(delayedHideRunnable)
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = if (isBottomGrav) (touchY - event.rawY).toInt() else (event.rawY - touchY).toInt()
                        if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                            userManuallyDragged = true
                        }
                        params.x = (initialX + dx)
                            .safeCoerceIn(dp(6), (screen.width() - rootView.width - dp(6)).coerceAtLeast(dp(6)))
                        params.y = (initialY + dy)
                            .safeCoerceIn(dp(40), (screen.height() - dp(84)).coerceAtLeast(dp(48)))
                        if (rootView.isAttachedToWindow) {
                            windowManager.updateViewLayout(rootView, params)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val movedX = abs(event.rawX - touchX)
                        val movedY = abs(event.rawY - touchY)
                        if (movedX <= dp(6) && movedY <= dp(6) &&
                            currentMode != PopupMode.ACTIONS &&
                            currentMode != PopupMode.COLLAPSED_PILL
                        ) {
                            lastShownTime = System.currentTimeMillis()
                            currentMode = PopupMode.ACTIONS
                            runCatching { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                            renderPopupBody()
                            return@runCatching true
                        }
                        if (movedX > dp(8)) {
                            val centerX = params.x + rootView.width / 2
                            val snapLeft = centerX < screen.width() / 2
                            ToolPrefs.setSnapLeftEdge(this@SelAccessibilityService, snapLeft)
                            params.x = if (snapLeft) {
                                dp(10)
                            } else {
                                (screen.width() - rootView.width - dp(10)).coerceAtLeast(dp(10))
                            }
                            if (rootView.isAttachedToWindow) {
                                windowManager.updateViewLayout(rootView, params)
                            }
                            runCatching { v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                        }
                        true
                    }
                    else -> false
                }
            }.getOrDefault(false)
        }
    }

    private fun applyResult(newText: String) {
        lastInternalCopyTime = System.currentTimeMillis()
        clipboardManager.setPrimaryClip(ClipData.newPlainText("sel", newText))
        ToolPrefs.addClipToHistory(this, newText)

        var replaced = false
        val node = currentNode
        if (node != null && node.isEditable) {
            runCatching { node.refresh() }
            val full = node.text?.toString() ?: ""
            val s = node.textSelectionStart
            val e = node.textSelectionEnd
            val start = minOf(s, e)
            val end = maxOf(s, e)
            if (start >= 0 && end > start && end <= full.length) {
                undoOriginalFullText = full
                undoNode = node
                undoTimestamp = System.currentTimeMillis()

                val updated = full.replaceRange(start, end, newText)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
                }
                replaced = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
            } else {
                replaced = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)
            }
        }
        if (!replaced) {
            Toast.makeText(this, "[^C] Copied: ${newText.take(28)}", Toast.LENGTH_SHORT).show()
        }
        hidePalette()
    }

    private fun appendToScratchpad(text: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        File(filesDir, "scratchpad.txt").appendText("[$stamp]\n${text.trim()}\n\n")
    }

    private fun hidePalette() {
        mainHandler.removeCallbacks(delayedHideRunnable)
        pendingUniversalExtractRunnable?.let { mainHandler.removeCallbacks(it) }
        networkTools.cancelPending()
        popupView?.let {
            runCatching { windowManager.removeView(it) }
        }
        popupView = null
        popupParams = null
        headerBarView = null
        headerDividerView = null
        statsLabelView = null
        gridToggleBtn = null
        collapseBtnView = null
        reorderToggleBtn = null
        closeBtnView = null
        bodyContainer = null
        currentMode = PopupMode.ACTIONS
    }

    override fun onInterrupt() {
        hideScreenGrabOverlay()
        hidePalette()
    }
}
