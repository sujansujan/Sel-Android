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
import android.view.animation.DecelerateInterpolator
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
        BAR,            // Compact, thumb-friendly PopClip ASCII bar (1 or 2 clean rows)
        MORE_TOOLS,     // Expanded ASCII drawer when [+] is tapped
        WEB_SEARCH,
        YOUTUBE,
        CLIP_HISTORY,
        MARKDOWN,
        REGEX_PIPE,
        CUSTOM_FOLDER,
        QR_CODE,
        MAGNET,
        DEFINE_WORD,
        TRANSLATE,
        EXTRACT
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

    private var popupView: LinearLayout? = null
    private var popupParams: WindowManager.LayoutParams? = null
    private var barRowsContainer: LinearLayout? = null
    private var drawerDividerView: View? = null
    private var drawerScrollView: ScrollView? = null
    private var drawerContainer: LinearLayout? = null

    private var screenGrabOverlayView: View? = null
    private var activeClipboardProbe: View? = null

    private var currentMode: PopupMode = PopupMode.BAR
    private var activeCustomFolder: String = ""
    private var currentNode: AccessibilityNodeInfo? = null
    private var selectedText: String = ""
    private var lastRawCandidateBounds: Rect = Rect()
    private var lastRawCandidateTime: Long = 0L
    private var currentSafeZone: Rect = Rect()
    private var dynamicMaxDrawerHeight: Int = 560
    private var anchoredAboveSelection: Boolean = true
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
    private var pendingExtractFirst: Runnable? = null
    private var pendingExtractSecond: Runnable? = null

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
    private fun dpF(v: Float): Float = v * resources.displayMetrics.density

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
        if (popupView != null) mainHandler.post { renderPopup() }
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
        cancelPendingExtracts()
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

    private fun cancelPendingExtracts() {
        pendingExtractFirst?.let { mainHandler.removeCallbacks(it) }
        pendingExtractSecond?.let { mainHandler.removeCallbacks(it) }
        pendingExtractFirst = null
        pendingExtractSecond = null
    }

    fun refreshOpenPopupTheme() {
        if (popupView != null && popupView?.isAttachedToWindow == true) {
            mainHandler.post { renderPopup() }
        }
    }

    fun showFromExternalText(text: String, openExpanded: Boolean = false) {
        mainHandler.post {
            if (text.isNotBlank()) {
                ToolPrefs.addClipToHistory(this, text)
                mainHandler.removeCallbacks(delayedHideRunnable)
                selectedText = text
                currentMode = if (openExpanded) PopupMode.MORE_TOOLS else PopupMode.BAR
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

    /**
     * Instant block-first Screen Text Grab:
     * 1) Harvests visible Accessibility text blocks immediately (<15ms) for effortless 1-tap grab.
     * 2) Automatically runs on-device ML Kit OCR if 0 accessibility blocks exist (images/videos)
     *    OR whenever the user taps [OCR] in the minimal top bar.
     */
    fun triggerScreenGrab(delayMs: Long = 120L) {
        hidePalette()
        hideScreenGrabOverlay()
        mainHandler.postDelayed({
            val a11ySnap = screenHarvester.harvestAsLensSnapshot()
            if (a11ySnap.words.isNotEmpty()) {
                showLensOverlay(backgroundBitmap = null, initialSnapshot = a11ySnap)
            } else {
                runScreenOcrCapture(delayMs = 0L)
            }
        }, delayMs)
    }

    private fun runScreenOcrCapture(delayMs: Long = 120L) {
        hidePalette()
        hideScreenGrabOverlay()
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(this, "Pixel OCR requires Android 11+", Toast.LENGTH_SHORT).show()
            return
        }
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
                            val softBmp = hwBmp?.copy(Bitmap.Config.ARGB_8888, false)
                            hwBmp?.recycle()

                            if (softBmp == null) {
                                Toast.makeText(this@SelAccessibilityService, "Could not capture screen", Toast.LENGTH_SHORT).show()
                                return
                            }

                            val lensView = showLensOverlay(backgroundBitmap = softBmp, initialSnapshot = null) ?: return
                            screenOcrEngine.recognizeBitmap(
                                bitmap = softBmp,
                                density = resources.displayMetrics.density,
                                onSuccess = { ocrSnap ->
                                    if (screenGrabOverlayView !== lensView) return@recognizeBitmap
                                    lensView.setSnapshot(ocrSnap)
                                    if (ocrSnap.words.isEmpty()) {
                                        Toast.makeText(this@SelAccessibilityService, "No text detected in image", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onFailure = {
                                    if (screenGrabOverlayView !== lensView) return@recognizeBitmap
                                    lensView.setSnapshot(screenHarvester.harvestAsLensSnapshot())
                                }
                            )
                        }

                        override fun onFailure(errorCode: Int) {
                            Toast.makeText(this@SelAccessibilityService, "Screen capture blocked on this app", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }, delayMs)
    }

    private fun showLensOverlay(backgroundBitmap: Bitmap?, initialSnapshot: LensSnapshot?): LensOverlayView? {
        hideScreenGrabOverlay()

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
            backgroundBitmap = backgroundBitmap,
            initialSnapshot = initialSnapshot,
            onRunAction = { popAction, chosenText ->
                hideScreenGrabOverlay()
                selectedText = chosenText
                currentNode = null
                ToolPrefs.addClipToHistory(this, chosenText)
                val dm = resources.displayMetrics
                currentSafeZone = Rect(0, dm.heightPixels / 3, dm.widthPixels, dm.heightPixels / 2)
                executePopAction(popAction)
            },
            onOpenFullPalette = { chosenText ->
                hideScreenGrabOverlay()
                showFromExternalText(chosenText, openExpanded = true)
            },
            onRequestOcr = { runScreenOcrCapture(delayMs = 120L) },
            onDismiss = { hideScreenGrabOverlay() }
        )

        screenGrabOverlayView = lensView
        val added = runCatching { windowManager.addView(lensView, params) }.isSuccess
        if (!added) {
            screenGrabOverlayView = null
            if (backgroundBitmap != null && !backgroundBitmap.isRecycled) {
                runCatching { backgroundBitmap.recycle() }
            }
            return null
        }
        return lensView
    }

    private fun hideScreenGrabOverlay() {
        screenGrabOverlayView?.let { v -> runCatching { windowManager.removeView(v) } }
        screenGrabOverlayView = null
    }

    private fun smoothCopyText(text: String, toastLabel: String? = null) {
        lastInternalCopyTime = System.currentTimeMillis()
        clipboardManager.setPrimaryClip(ClipData.newPlainText("sel", text))
        ToolPrefs.addClipToHistory(this, text)
        popupView?.let { runCatching { it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } }
        val preview = text.replace("\n", " ").let { if (it.length > 26) it.take(25) + "…" else it }
        Toast.makeText(this, toastLabel ?: "Copied: \"$preview\"", Toast.LENGTH_SHORT).show()
    }

    private fun isPrimaryClipSensitive(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val desc = runCatching { clipboardManager.primaryClipDescription }.getOrNull()
            if (desc?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true) return true
        }
        return false
    }

    private fun removeClipboardProbe() {
        activeClipboardProbe?.let { p -> runCatching { windowManager.removeView(p) } }
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

        // Reliable single-flight 1x1 non-touchable focus probe (360ms max; removes in 0ms on focus)
        removeClipboardProbe()
        val probe = View(this)
        val probeParams = WindowManager.LayoutParams(
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

        var done = false
        val cleanup = {
            if (!done) {
                done = true
                if (activeClipboardProbe === probe) removeClipboardProbe()
                else runCatching { windowManager.removeView(probe) }
            }
        }

        val tryRead = {
            if (!done) {
                if (isPrimaryClipSensitive()) cleanup()
                else {
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
        mainHandler.postDelayed({ tryRead() }, 40L)
        mainHandler.postDelayed({ tryRead() }, 130L)
        mainHandler.postDelayed({ cleanup() }, 360L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        runCatching { handleAccessibilityEventInternal(event) }
    }

    private fun handleAccessibilityEventInternal(event: AccessibilityEvent?) {
        event ?: return
        if (screenGrabOverlayView != null) return
        val pkg = event.packageName?.toString() ?: ""
        val cls = event.className?.toString() ?: ""

        // Track foreground package cleanly on real app transitions
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
                    if (System.currentTimeMillis() - lastShownTime > 450L) hidePalette()
                }
            }
        }

        if (pkg.isNotEmpty() && ToolPrefs.isAppBlacklisted(this, pkg)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                val result = selectionDetector.extractSelection(event)
                if (result != null) {
                    cancelPendingExtracts()
                    mainHandler.removeCallbacks(delayedHideRunnable)
                    val changedText = result.text != selectedText
                    selectedText = result.text
                    currentNode = result.node
                    if (pkg.isNotEmpty() && pkg != packageName && !selectionDetector.isImePackage(pkg)) {
                        activePackage = pkg
                    }
                    lastShownTime = System.currentTimeMillis()
                    lastRawCandidateBounds = Rect(result.bounds)
                    lastRawCandidateTime = lastShownTime
                    if (changedText) currentMode = PopupMode.BAR
                    val safeZone = selectionDetector.resolveBestSafeZone(result.bounds, forceRefresh = true)
                    currentSafeZone = Rect(safeZone)
                    showOrUpdatePalette(currentSafeZone)
                } else if (event.fromIndex >= 0 && event.toIndex >= 0 && event.fromIndex != event.toIndex) {
                    event.source?.let { src ->
                        val r = Rect()
                        src.getBoundsInScreen(r)
                        val realH = selectionDetector.getRealScreenBounds().height()
                        if (r.width() > 0 && r.height() in 1..(realH * 0.42f).toInt()) {
                            lastRawCandidateBounds = Rect(r)
                            lastRawCandidateTime = System.currentTimeMillis()
                        }
                    }
                    scheduleReliableSelectionExtract(event.source, pkg, toolbarSig = "")
                } else {
                    val src = event.source
                    val elapsed = System.currentTimeMillis() - lastShownTime
                    if (popupView != null && currentMode == PopupMode.BAR && elapsed > 550L &&
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

            // Long-pressing text in Chrome, Compose, WebViews, WhatsApp, PDF viewers initiates selection!
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                if (pkg != packageName && pkg != "com.android.systemui" && !selectionDetector.isImePackage(pkg)) {
                    lastAutoCopyToolbarSig = ""
                    scheduleReliableSelectionExtract(event.source, activePackage.ifEmpty { pkg }, toolbarSig = "")
                }
            }

            // TYPE_WINDOWS_CHANGED fires whenever Android adds/moves the floating ActionMode selection toolbar!
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (popupView == null && !ToolPrefs.isAppBlacklisted(this, activePackage)) {
                    val now = System.currentTimeMillis()
                    if (now - lastContentScanTime >= 160L) {
                        lastContentScanTime = now
                        val toolbar = selectionDetector.findActiveSelectionToolbarNow()
                        if (toolbar == null) {
                            lastAutoCopyToolbarSig = ""
                        } else if (toolbar.signature != lastAutoCopyToolbarSig || now - lastAutoCopyTime > 1600L) {
                            scheduleReliableSelectionExtract(null, activePackage, toolbar.signature)
                        }
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (popupView == null && pkg != packageName && !selectionDetector.isImePackage(pkg)) {
                    if (selectionDetector.isTransientSystemOrPopupClass(cls)) {
                        val toolbar = selectionDetector.findActiveSelectionToolbarNow()
                        if (toolbar != null) {
                            scheduleReliableSelectionExtract(event.source, activePackage.ifEmpty { pkg }, toolbar.signature)
                        }
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (popupView == null &&
                    pkg.isNotEmpty() &&
                    pkg != packageName &&
                    pkg != "com.android.systemui" &&
                    !selectionDetector.isImePackage(pkg)
                ) {
                    val now = System.currentTimeMillis()
                    if (now - lastContentScanTime >= 220L) {
                        lastContentScanTime = now
                        val toolbar = selectionDetector.findActiveSelectionToolbarNow()
                        if (toolbar == null) {
                            // Selection dismissed -> reset signature so next selection on same line ALWAYS triggers!
                            lastAutoCopyToolbarSig = ""
                        } else if (toolbar.signature != lastAutoCopyToolbarSig || now - lastAutoCopyTime > 1600L) {
                            scheduleReliableSelectionExtract(event.source, pkg, toolbar.signature)
                        }
                    }
                }
            }
        }
    }

    /**
     * Two-stage selection probe (110ms + 290ms) so slower Chrome/WebView/Compose selection
     * transitions never miss showing the popup.
     */
    private fun scheduleReliableSelectionExtract(
        sourceNode: AccessibilityNodeInfo?,
        pkg: String,
        toolbarSig: String
    ) {
        cancelPendingExtracts()
        val firstRun = Runnable {
            if (!attemptUniversalExtract(sourceNode, pkg, toolbarSig)) {
                val secondRun = Runnable {
                    if (popupView == null) {
                        attemptUniversalExtract(sourceNode, pkg, toolbarSig)
                    }
                }
                pendingExtractSecond = secondRun
                mainHandler.postDelayed(secondRun, 190L)
            }
        }
        pendingExtractFirst = firstRun
        mainHandler.postDelayed(firstRun, 100L)
    }

    private fun attemptUniversalExtract(
        sourceNode: AccessibilityNodeInfo?,
        pkg: String,
        toolbarSig: String
    ): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAutoCopyTime < 550L) return false
        if (pkg.isNotEmpty() && pkg != packageName && !selectionDetector.isImePackage(pkg)) {
            activePackage = pkg
        }
        if (activePackage.isNotEmpty() && ToolPrefs.isAppBlacklisted(this, activePackage)) return true

        // Tier 1: Zero-side-effect deep BFS for readable selection in tree
        val treeResult = selectionDetector.findSelectionInTreeBfs(sourceNode ?: rootInActiveWindow)
        if (treeResult != null) {
            selectedText = treeResult.text
            currentNode = treeResult.node
            currentMode = PopupMode.BAR
            lastShownTime = System.currentTimeMillis()
            lastRawCandidateBounds = Rect(treeResult.bounds)
            lastRawCandidateTime = lastShownTime
            if (toolbarSig.isNotEmpty()) lastAutoCopyToolbarSig = toolbarSig
            val safeZone = selectionDetector.resolveBestSafeZone(treeResult.bounds, forceRefresh = false)
            currentSafeZone = Rect(safeZone)
            showOrUpdatePalette(currentSafeZone)
            return true
        }

        // Only trigger ACTION_COPY when a selection toolbar is actually visible on screen
        val activeToolbar = selectionDetector.findActiveSelectionToolbarNow() ?: return false
        val effectiveSig = toolbarSig.ifEmpty { activeToolbar.signature }
        if (effectiveSig == lastAutoCopyToolbarSig && now - lastAutoCopyTime < 1500L) return false

        lastAutoCopyTime = now
        lastAutoCopyToolbarSig = effectiveSig

        if (sourceNode != null) {
            val r = Rect()
            sourceNode.getBoundsInScreen(r)
            val realH = selectionDetector.getRealScreenBounds().height()
            if (r.width() > 0 && r.height() in 1..(realH * 0.42f).toInt()) {
                lastRawCandidateBounds = Rect(r)
                lastRawCandidateTime = now
            }
        }

        val copied = selectionDetector.performBestCopyAction(sourceNode)
        if (copied) {
            val triggerTime = System.currentTimeMillis()
            mainHandler.postDelayed({
                if (lastShownTime < triggerTime && popupView == null) {
                    readClipboardAndShowPalette()
                }
            }, 65L)
            return true
        }
        return false
    }

    private fun positionPopClipBar(safeZone: Rect, params: WindowManager.LayoutParams, actualW: Int = 0, actualH: Int = 0) {
        val metrics = selectionDetector.getScreenMetrics(force = false)
        val realW = metrics.screenWidth
        val topSafe = metrics.topSafeInset
        val visibleBottom = metrics.visibleBottom
        currentSafeZone = Rect(safeZone)

        val gap = dp(12)
        val estH = actualH.takeIf { it > dp(28) } ?: if (currentMode == PopupMode.BAR) dp(54) else dp(280)
        val estW = actualW.takeIf { it > dp(100) } ?: (realW - dp(20)).coerceAtMost(dp(348))

        val spaceAbove = (safeZone.top - gap) - topSafe
        val spaceBelow = visibleBottom - (safeZone.bottom + gap)

        // Place neatly below native toolbar/selection if room, else above (keeps both readable!)
        val placeAbove = when {
            spaceBelow >= estH + dp(16) -> false
            spaceAbove >= estH -> true
            else -> spaceAbove > spaceBelow
        }
        anchoredAboveSelection = placeAbove

        val chosenSpace = if (placeAbove) spaceAbove else spaceBelow
        dynamicMaxDrawerHeight = (chosenSpace - dp(56)).safeCoerceIn(dp(110), dp(310))

        if (!userManuallyDragged) {
            params.gravity = Gravity.TOP or Gravity.START
            params.x = (safeZone.centerX() - estW / 2)
                .safeCoerceIn(dp(8), (realW - estW - dp(8)).coerceAtLeast(dp(8)))
            params.y = if (placeAbove) {
                (safeZone.top - gap - estH).coerceAtLeast(topSafe)
            } else {
                val maxTop = (visibleBottom - estH).coerceAtLeast(topSafe)
                (safeZone.bottom + gap).safeCoerceIn(topSafe, maxTop)
            }
        }
    }

    private fun showOrUpdatePalette(safeZone: Rect) {
        if (popupView != null && popupView?.isAttachedToWindow != true) {
            hidePalette()
        }

        if (popupView != null && popupParams != null && barRowsContainer != null) {
            positionPopClipBar(safeZone, popupParams!!, popupView?.width ?: 0, popupView?.height ?: 0)
            renderPopup()
            return
        }

        val pal = ToolPrefs.resolvePalette(this)
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
        positionPopClipBar(safeZone, windowParams)

        val maxBarW = (selectionDetector.getRealScreenBounds().width() - dp(16)).coerceAtLeast(dp(260))

        val rootCard = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cappedW = MeasureSpec.makeMeasureSpec(maxBarW, MeasureSpec.AT_MOST)
                super.onMeasure(cappedW, heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            elevation = dpF(10f)
            background = GradientDrawable().apply {
                setColor(pal.bgPrimaryInt)
                setStroke(dp(1), pal.borderInt)
                cornerRadius = dpF(4f)
            }
            addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                val actualW = right - left
                val actualH = bottom - top
                val p = popupParams
                if (actualH > 0 && actualW > 0 && p != null && !userManuallyDragged && !currentSafeZone.isEmpty) {
                    val prevX = p.x
                    val prevY = p.y
                    positionPopClipBar(currentSafeZone, p, actualW, actualH)
                    if (abs(p.x - prevX) > dp(3) || abs(p.y - prevY) > dp(3)) {
                        if (v.isAttachedToWindow) {
                            mainHandler.post {
                                if (v.isAttachedToWindow) runCatching { windowManager.updateViewLayout(v, p) }
                            }
                        }
                    }
                }
            }
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE || event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    if (System.currentTimeMillis() - lastShownTime > 240L) hidePalette()
                    true
                } else {
                    false
                }
            }
        }

        // 1. Thumb-friendly 1-row or 2-row wrapped ASCII PopClip bar (zero horizontal scroll friction!)
        val barContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bgHeaderInt)
        }
        rootCard.addView(barContainer)

        // 2. Hairline divider + Expandable Drawer
        val div = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(pal.borderInt)
            visibility = View.GONE
        }
        rootCard.addView(div)

        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxH = MeasureSpec.makeMeasureSpec(dynamicMaxDrawerHeight, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, maxH)
            }
        }.apply {
            isVerticalScrollBarEnabled = true
            visibility = View.GONE
        }
        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        scroll.addView(drawer)
        rootCard.addView(scroll)

        popupView = rootCard
        popupParams = windowParams
        barRowsContainer = barContainer
        drawerDividerView = div
        drawerScrollView = scroll
        drawerContainer = drawer

        renderPopup()

        rootCard.alpha = 0f
        rootCard.scaleX = 0.96f
        rootCard.scaleY = 0.96f
        val added = runCatching { windowManager.addView(rootCard, windowParams) }.isSuccess
        if (!added) {
            hidePalette()
        } else {
            rootCard.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(90L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun renderPopup() {
        val root = popupView ?: return
        val barContainer = barRowsContainer ?: return
        val div = drawerDividerView ?: return
        val scroll = drawerScrollView ?: return
        val drawer = drawerContainer ?: return
        val pal = ToolPrefs.resolvePalette(this)

        networkTools.cancelPending()

        root.background = GradientDrawable().apply {
            setColor(pal.bgPrimaryInt)
            setStroke(dp(1), pal.borderInt)
            cornerRadius = dpF(4f)
        }
        barContainer.setBackgroundColor(pal.bgHeaderInt)
        div.setBackgroundColor(pal.borderInt)

        // Build clean, big (46dp tall!) context-aware PopClip bar without redundant Copy/Cut/Paste/All
        barContainer.removeAllViews()

        val isEditable = currentNode?.isEditable == true
        val hasUndo = undoOriginalFullText != null && undoNode != null &&
            (System.currentTimeMillis() - undoTimestamp < 45000L)
        val hiddenIds = ToolPrefs.builtInTools.map { it.id }.filter { ToolPrefs.isToolHidden(this, it) }.toSet()
        val pinnedTools = ToolPrefs.getPinnedToolSpecs(this)
        val defaultEngine = ToolPrefs.resolveDefaultSearchEngine(this)
        val searchLabel = defaultEngine.glyph.take(4).ifEmpty { "WEB" }

        val ctx = PopupContextEngine.analyze(selectedText, isEditable = isEditable, hasUndo = hasUndo)
        val actions = PopupContextEngine.resolveBarActions(
            ctx = ctx,
            hiddenToolIds = hiddenIds,
            pinnedTools = pinnedTools,
            searchLabel = searchLabel,
            includeCopy = false
        )

        // Mobile UI best practice: wrap cleanly into 1 row (<= 4 actions + controls) or 2 tidy rows!
        val moreOpen = currentMode != PopupMode.BAR
        val rows: List<List<PopAction>> = if (actions.size <= 4) {
            listOf(actions)
        } else {
            val split = (actions.size + 1) / 2
            listOf(actions.subList(0, split), actions.subList(split, actions.size))
        }

        rows.forEachIndexed { rowIdx, rowActions ->
            if (rowIdx > 0) {
                barContainer.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                    setBackgroundColor(pal.dividerInt)
                })
            }
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            if (rowIdx == 0) {
                val dragGrip = TextView(this).apply {
                    text = "::"
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setTextColor(pal.fgMutedInt)
                    gravity = Gravity.CENTER
                    minHeight = dp(46)
                    setPadding(dp(10), dp(10), dp(8), dp(10))
                }
                enableDragOnView(dragGrip, root)
                rowView.addView(dragGrip)
                rowView.addView(makeVertDivider(pal))
            }

            rowActions.forEachIndexed { colIdx, act ->
                if (colIdx > 0) rowView.addView(makeVertDivider(pal))
                rowView.addView(
                    makePopCell(
                        label = act.label,
                        fgColor = if (act.accent) pal.accentGreenInt else pal.fgPrimaryInt,
                        pal = pal,
                        onLongClick = { executePopActionLongPress(act) }
                    ) {
                        executePopAction(act)
                    }
                )
            }

            if (rowIdx == 0) {
                rowView.addView(makeVertDivider(pal))
                rowView.addView(
                    makePopCell(if (moreOpen) "-" else "+", if (moreOpen) pal.accentPurpleInt else pal.accentCyanInt, pal) {
                        lastShownTime = System.currentTimeMillis()
                        currentMode = if (currentMode == PopupMode.BAR) PopupMode.MORE_TOOLS else PopupMode.BAR
                        renderPopup()
                    }
                )
                rowView.addView(makeVertDivider(pal))
                rowView.addView(makePopCell("x", pal.dangerRedInt, pal) { hidePalette() })
            }

            barContainer.addView(rowView)
        }

        // Rebuild Drawer when expanded
        drawer.removeAllViews()
        if (currentMode == PopupMode.BAR) {
            div.visibility = View.GONE
            scroll.visibility = View.GONE
        } else {
            div.visibility = View.VISIBLE
            scroll.visibility = View.VISIBLE
            when (currentMode) {
                PopupMode.MORE_TOOLS -> populateMoreDrawer(drawer, pal)
                PopupMode.WEB_SEARCH -> populateWebSearchDrawer(drawer)
                PopupMode.YOUTUBE -> populateYoutubeDrawer(drawer)
                PopupMode.CLIP_HISTORY -> populateClipHistoryDrawer(drawer, pal)
                PopupMode.MARKDOWN -> populateMarkdownDrawer(drawer)
                PopupMode.REGEX_PIPE -> populateRegexPipeDrawer(drawer)
                PopupMode.CUSTOM_FOLDER -> populateCustomFolderDrawer(drawer, activeCustomFolder, pal)
                PopupMode.QR_CODE -> populateQrDrawer(drawer, pal)
                PopupMode.MAGNET -> populateMagnetDrawer(drawer, pal)
                PopupMode.DEFINE_WORD -> populateDefineDrawer(drawer, pal)
                PopupMode.TRANSLATE -> populateTranslateDrawer(drawer, pal)
                PopupMode.EXTRACT -> populateExtractDrawer(drawer, pal)
                PopupMode.BAR -> {}
            }
        }

        relayoutPopupWindow()
    }

    private fun makeVertDivider(pal: ThemePalette): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(24))
            setBackgroundColor(pal.dividerInt)
        }
    }

    private fun makePopCell(
        label: String,
        fgColor: Int,
        pal: ThemePalette,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(fgColor)
            gravity = Gravity.CENTER
            isSingleLine = true
            minHeight = dp(46)
            minWidth = dp(44)
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(pal.bgPressedInt) })
                addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            }
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
    }

    private fun executePopActionLongPress(act: PopAction) {
        when (act.id) {
            "default_search", "web_search_folder" -> switchMode(PopupMode.WEB_SEARCH)
            "youtube_direct", "youtube_folder" -> switchMode(PopupMode.YOUTUBE)
            "copy" -> {
                val existing = runCatching {
                    clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
                }.getOrNull().orEmpty()
                val combined = if (existing.isNotBlank()) "$existing\n$selectedText" else selectedText
                smoothCopyText(combined, "[+^C] Appended")
                hidePalette()
            }
            else -> executePopAction(act)
        }
    }

    private fun executePopAction(act: PopAction) {
        if (act.customTool != null) {
            ToolPrefs.recordUse(this, act.customTool.id)
            executeToolSpec(act.customTool)
            return
        }

        val encoded = Uri.encode(selectedText.trim())
        when (act.id) {
            "undo" -> performUndoAction()
            "copy" -> {
                ToolPrefs.recordUse(this, "copy")
                smoothCopyText(selectedText)
                hidePalette()
            }
            "default_search" -> {
                ToolPrefs.recordUse(this, "web_search_folder")
                val engine = ToolPrefs.resolveDefaultSearchEngine(this)
                val target = engine.customTarget ?: "https://www.google.com/search?q={text}"
                openUrl(target.replace("{text}", encoded))
            }
            "youtube_direct" -> {
                ToolPrefs.recordUse(this, "youtube_folder")
                openUrl("https://www.youtube.com/results?search_query=$encoded")
            }
            "phone_call" -> {
                val num = act.payload ?: selectedText.trim()
                runCatching {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(num)}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
                hidePalette()
            }
            "phone_sms" -> {
                val num = act.payload ?: selectedText.trim()
                val sms = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(num)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (runCatching { startActivity(sms) }.isFailure) {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("sms:${Uri.encode(num)}")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                }
                hidePalette()
            }
            "phone_wa" -> openWhatsAppNumber(act.payload ?: "", isBusiness = false)
            "phone_w4b" -> openWhatsAppNumber(act.payload ?: "", isBusiness = true)
            "open_url" -> {
                val raw = act.payload ?: selectedText.trim()
                val url = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"
                openUrl(url)
            }
            "email_send" -> {
                val mail = act.payload ?: selectedText.trim()
                runCatching {
                    startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(mail)}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
                hidePalette()
            }
            "open_magnet" -> {
                val mag = act.payload ?: selectedText.trim()
                smoothCopyText(mag, "[0x] Opening magnet…")
                openUrl(mag)
            }
            "apply_computed" -> act.payload?.let { applyResult(it) }
            "pipe_json_pretty" -> applyResult(TextTransforms.applyPipeTool("pipe_json_pretty", selectedText))
            "pipe_strip_html" -> applyResult(TextTransforms.applyPipeTool("pipe_strip_html", selectedText))
            "clean_url" -> {
                ToolPrefs.recordUse(this, "clean_url")
                applyResult(TextTransforms.cleanUrl(selectedText))
            }
            "clean_text" -> {
                ToolPrefs.recordUse(this, "clean_text")
                applyResult(TextTransforms.cleanText(selectedText))
            }
            "sort" -> {
                ToolPrefs.recordUse(this, "sort")
                applyResult(selectedText.lines().filter { it.isNotBlank() }.distinct().sorted().joinToString("\n"))
            }
            "case" -> {
                ToolPrefs.recordUse(this, "case")
                applyResult(TextTransforms.cycleCase(selectedText))
            }
            "speak" -> {
                ToolPrefs.recordUse(this, "speak")
                speakTextLazy(selectedText.trim())
            }
            "log" -> {
                ToolPrefs.recordUse(this, "log")
                appendToScratchpad(selectedText)
                Toast.makeText(this, "[>>] Saved to notes", Toast.LENGTH_SHORT).show()
                hidePalette()
            }
            "define" -> {
                ToolPrefs.recordUse(this, "define")
                switchMode(PopupMode.DEFINE_WORD)
            }
            "translate" -> {
                ToolPrefs.recordUse(this, "translate")
                switchMode(PopupMode.TRANSLATE)
            }
            "web_search_folder" -> switchMode(PopupMode.WEB_SEARCH)
            "youtube_folder" -> switchMode(PopupMode.YOUTUBE)
            "qr" -> {
                ToolPrefs.recordUse(this, "qr")
                switchMode(PopupMode.QR_CODE)
            }
            "magnet" -> {
                ToolPrefs.recordUse(this, "magnet")
                switchMode(PopupMode.MAGNET)
            }
            "extract" -> {
                ToolPrefs.recordUse(this, "extract")
                switchMode(PopupMode.EXTRACT)
            }
            "regex_pipe" -> {
                ToolPrefs.recordUse(this, "regex_pipe")
                switchMode(PopupMode.REGEX_PIPE)
            }
            else -> showFromExternalText(selectedText, openExpanded = true)
        }
    }

    private fun switchMode(mode: PopupMode) {
        lastShownTime = System.currentTimeMillis()
        currentMode = mode
        if (popupView == null) {
            showOrUpdatePalette(currentSafeZone)
        } else {
            renderPopup()
        }
    }

    private fun populateMoreDrawer(container: LinearLayout, pal: ThemePalette) {
        val tools = ToolPrefs.getSortedToolsForPopup(this).filter { it.id != "copy" }
        var i = 0
        while (i < tools.size) {
            val t1 = tools[i]
            val t2 = tools.getOrNull(i + 1)
            if (t2 != null) {
                container.addView(makeTwoColDrawerRow(t1, t2, pal))
                i += 2
            } else {
                container.addView(makeDrawerRow("[${t1.glyph}] ${t1.label}", pal) {
                    ToolPrefs.recordUse(this, t1.id)
                    executeToolSpec(t1)
                })
                i += 1
            }
        }

        if (activePackage.isNotBlank() && activePackage != packageName) {
            container.addView(makeHorizontalRule(pal))
            val shortPkg = activePackage.substringAfterLast('.').uppercase()
            container.addView(makeDrawerRow("[-] MUTE POPUP IN $shortPkg", pal, pal.fgMutedInt) {
                ToolPrefs.setAppBlacklisted(this, activePackage, true)
                Toast.makeText(this, "Muted in $activePackage", Toast.LENGTH_SHORT).show()
                hidePalette()
            })
        }
    }

    private fun makeTwoColDrawerRow(t1: ToolSpec, t2: ToolSpec, pal: ThemePalette): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        fun cell(t: ToolSpec): TextView = TextView(this).apply {
            text = t.label.replace(" ▸", " >")
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(pal.fgPrimaryInt)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            minHeight = dp(40)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(pal.bgPressedInt) })
                addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            }
            setOnClickListener {
                runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                ToolPrefs.recordUse(this@SelAccessibilityService, t.id)
                executeToolSpec(t)
            }
        }
        val sep = TextView(this).apply {
            text = "|"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(pal.dividerInt)
        }
        row.addView(cell(t1))
        row.addView(sep)
        row.addView(cell(t2))
        return row
    }

    private fun executeToolSpec(tool: ToolSpec) {
        val trimmed = selectedText.trim()
        val encoded = Uri.encode(trimmed)

        if (tool.folderName != null && tool.customTarget == null) {
            activeCustomFolder = tool.folderName
            switchMode(PopupMode.CUSTOM_FOLDER)
            return
        }
        if (tool.customTarget != null) {
            val target = tool.customTarget.trim()
            if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
                openUrl(target.replace("{text}", encoded))
            } else {
                applyResult(TextTransforms.executeCustomTargetTransform(target, selectedText))
            }
            return
        }

        when (tool.id) {
            "screen_grab" -> triggerScreenGrab(delayMs = 110L)
            "web_search_folder" -> switchMode(PopupMode.WEB_SEARCH)
            "youtube_folder" -> switchMode(PopupMode.YOUTUBE)
            "clip_history" -> switchMode(PopupMode.CLIP_HISTORY)
            "markdown_folder" -> switchMode(PopupMode.MARKDOWN)
            "regex_pipe" -> switchMode(PopupMode.REGEX_PIPE)
            "define" -> switchMode(PopupMode.DEFINE_WORD)
            "translate" -> switchMode(PopupMode.TRANSLATE)
            "qr" -> switchMode(PopupMode.QR_CODE)
            "magnet" -> switchMode(PopupMode.MAGNET)
            "extract" -> switchMode(PopupMode.EXTRACT)
            "clean_url" -> applyResult(TextTransforms.cleanUrl(selectedText))
            "clean_text" -> applyResult(TextTransforms.cleanText(selectedText))
            "case" -> applyResult(TextTransforms.cycleCase(selectedText))
            "sort" -> applyResult(selectedText.lines().filter { it.isNotBlank() }.distinct().sorted().joinToString("\n"))
            "speak" -> speakTextLazy(trimmed)
            "log" -> {
                appendToScratchpad(selectedText)
                Toast.makeText(this, "[>>] Saved to notes", Toast.LENGTH_SHORT).show()
                hidePalette()
            }
        }
    }

    private fun populateWebSearchDrawer(container: LinearLayout) {
        val pal = ToolPrefs.resolvePalette(this)
        val defaultId = ToolPrefs.getDefaultSearchEngineId(this)
        container.addView(makeBackRow("WEB SEARCH (LONG-PRESS TO SET DEFAULT)", pal))
        for (engine in ToolPrefs.loadWebSearchTools(this)) {
            val star = if (engine.id == defaultId) " *" else ""
            val row = makeDrawerRow("[${engine.glyph}] ${engine.label}$star", pal,
                if (engine.id == defaultId) pal.accentGreenInt else pal.fgPrimaryInt
            ) {
                ToolPrefs.recordUse(this, "web_search_folder")
                executeToolSpec(engine)
            }
            row.setOnLongClickListener {
                runCatching { row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
                ToolPrefs.setDefaultSearchEngineId(this, engine.id)
                Toast.makeText(this, "Default search: ${engine.label}", Toast.LENGTH_SHORT).show()
                renderPopup()
                true
            }
            container.addView(row)
        }
    }

    private fun populateYoutubeDrawer(container: LinearLayout) {
        val pal = ToolPrefs.resolvePalette(this)
        container.addView(makeBackRow("YOUTUBE FILTERS", pal))
        for (yt in ToolPrefs.loadYoutubeTools(this)) {
            container.addView(makeDrawerRow("[${yt.glyph}] ${yt.label}", pal) {
                ToolPrefs.recordUse(this, "youtube_folder")
                executeToolSpec(yt)
            })
        }
    }

    private fun populateMarkdownDrawer(container: LinearLayout) {
        val pal = ToolPrefs.resolvePalette(this)
        container.addView(makeBackRow("MARKDOWN", pal))
        for (md in ToolPrefs.loadMarkdownTools(this)) {
            container.addView(makeDrawerRow(md.label, pal) {
                val out = if (md.customTarget != null) {
                    TextTransforms.executeCustomTargetTransform(md.customTarget, selectedText)
                } else {
                    TextTransforms.applyMarkdownTool(md.id, selectedText)
                }
                applyResult(out)
            })
        }
    }

    private fun populateRegexPipeDrawer(container: LinearLayout) {
        val pal = ToolPrefs.resolvePalette(this)
        container.addView(makeBackRow("REGEX / PIPE", pal))
        for (pTool in ToolPrefs.loadRegexPipeTools(this)) {
            container.addView(makeDrawerRow(pTool.label, pal) {
                val out = if (pTool.customTarget != null) {
                    TextTransforms.executeCustomTargetTransform(pTool.customTarget, selectedText)
                } else {
                    TextTransforms.applyPipeTool(pTool.id, selectedText)
                }
                applyResult(out)
            })
        }
    }

    private fun populateCustomFolderDrawer(container: LinearLayout, folderName: String, pal: ThemePalette) {
        container.addView(makeBackRow(folderName, pal))
        val items = ToolPrefs.loadCustomFolderTools(this, folderName)
        if (items.isEmpty()) {
            container.addView(makeMutedNote("Folder is empty.", pal))
            return
        }
        for (item in items) {
            container.addView(makeDrawerRow(item.label, pal) { executeToolSpec(item) })
        }
    }

    private fun populateClipHistoryDrawer(container: LinearLayout, pal: ThemePalette) {
        container.addView(makeBackRow("CLIPS", pal))
        val clips = ToolPrefs.getClipHistory(this)
        if (clips.isEmpty()) {
            container.addView(makeMutedNote("No saved clips yet.", pal))
            return
        }
        for (entry in clips) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(7), dp(4), dp(7))
            }
            row.addView(TextView(this).apply {
                text = if (entry.pinned) "[*]" else "[.]"
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(if (entry.pinned) pal.accentGreenInt else pal.fgMutedInt)
                setPadding(dp(4), dp(4), dp(8), dp(4))
                setOnClickListener {
                    ToolPrefs.togglePinClip(this@SelAccessibilityService, entry.text)
                    renderPopup()
                }
            })
            row.addView(TextView(this).apply {
                text = entry.text.replace("\n", " ↵ ")
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(pal.fgPrimaryInt)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { applyResult(entry.text) }
            })
            row.addView(TextView(this).apply {
                text = "[x]"
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(pal.dangerRedInt)
                setPadding(dp(8), dp(4), dp(4), dp(4))
                setOnClickListener {
                    ToolPrefs.deleteClip(this@SelAccessibilityService, entry.text)
                    renderPopup()
                }
            })
            container.addView(row)
        }
    }

    private fun populateDefineDrawer(container: LinearLayout, pal: ThemePalette) {
        container.addView(makeBackRow("DEFINE", pal))
        val rawTerm = selectedText.trim()
        val firstWord = rawTerm.split(TextTransforms.whitespaceRegex).firstOrNull()
            ?.trim { !it.isLetterOrDigit() && it != '-' }
            ?.ifBlank { rawTerm.take(32) } ?: rawTerm.take(32)

        val defView = TextView(this).apply {
            text = "Looking up \"$firstWord\"…"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(pal.fgPrimaryInt)
            setPadding(dp(8), dp(6), dp(8), dp(8))
            setLineSpacing(0f, 1.2f)
        }
        val copyBtn = makeDrawerRow("[ COPY DEFINITION ]", pal, pal.accentGreenInt) {
            smoothCopyText(defView.text.toString(), "Definition copied")
        }.apply { visibility = View.GONE }

        container.addView(defView)
        container.addView(copyBtn)

        networkTools.lookupDefinition(firstWord, rawTerm) { res ->
            if (currentMode == PopupMode.DEFINE_WORD && popupView?.isAttachedToWindow == true) {
                defView.text = res
                copyBtn.visibility = View.VISIBLE
                relayoutPopupWindow()
            }
        }
    }

    private fun populateTranslateDrawer(container: LinearLayout, pal: ThemePalette) {
        container.addView(makeBackRow("TRANSLATE -> EN", pal))
        val trView = TextView(this).apply {
            text = "Translating…"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(pal.fgPrimaryInt)
            setPadding(dp(8), dp(6), dp(8), dp(8))
            setLineSpacing(0f, 1.2f)
        }
        val applyBtn = makeDrawerRow("[ COPY / APPLY ]", pal, pal.accentGreenInt) {
            applyResult(trView.text.toString())
        }.apply { visibility = View.GONE }

        container.addView(trView)
        container.addView(applyBtn)

        networkTools.lookupTranslation(selectedText.trim().take(400)) { res ->
            if (currentMode == PopupMode.TRANSLATE && popupView?.isAttachedToWindow == true) {
                trView.text = res
                applyBtn.visibility = View.VISIBLE
                relayoutPopupWindow()
            }
        }
    }

    private fun populateQrDrawer(container: LinearLayout, pal: ThemePalette) {
        container.addView(makeBackRow("QR CODE", pal))
        val content = selectedText.trim().take(1200)
        val bmp = generateQrBitmap(content, dp(172))
        if (bmp != null) {
            container.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                layoutParams = LinearLayout.LayoutParams(dp(172), dp(172)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(8)
                    bottomMargin = dp(8)
                }
            })
        } else {
            container.addView(makeMutedNote("Could not encode QR.", pal))
        }
    }

    private fun populateMagnetDrawer(container: LinearLayout, pal: ThemePalette) {
        container.addView(makeBackRow("MAGNET", pal))
        val hash = TextTransforms.extractTorrentHash(selectedText)
        val magnetUri = when {
            hash != null -> TextTransforms.buildMagnetUri(hash)
            selectedText.trim().startsWith("magnet:?", true) -> selectedText.trim()
            else -> "magnet:?xt=urn:btih:${selectedText.trim().replace(TextTransforms.whitespaceRegex, "")}"
        }
        container.addView(TextView(this).apply {
            text = magnetUri
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(pal.fgPrimaryInt)
            setBackgroundColor(pal.bgSurfaceInt)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            maxLines = 4
        })
        container.addView(makeDrawerRow("[ COPY MAGNET ]", pal, pal.accentGreenInt) {
            smoothCopyText(magnetUri, "Magnet copied")
            hidePalette()
        })
        container.addView(makeDrawerRow("[ OPEN TORRENT APP ]", pal, pal.accentPurpleInt) {
            smoothCopyText(magnetUri, "Opening magnet…")
            openUrl(magnetUri)
        })
    }

    private fun populateExtractDrawer(container: LinearLayout, pal: ThemePalette) {
        container.addView(makeBackRow("EXTRACTED LINKS", pal))
        val items = TextTransforms.extractEntities(selectedText, limit = 12)
        if (items.isEmpty()) {
            container.addView(makeMutedNote("No URLs, emails, phones, or tags found.", pal))
            return
        }
        items.forEach { entity ->
            container.addView(makeDrawerRow("[${entity.type}] ${entity.value.take(28)}", pal) {
                applyResult(entity.value)
            })
        }
    }

    private fun makeBackRow(title: String, pal: ThemePalette): View {
        return makeDrawerRow("< BACK · $title", pal, pal.accentPurpleInt) {
            currentMode = PopupMode.BAR
            renderPopup()
        }
    }

    private fun makeMutedNote(msg: String, pal: ThemePalette): TextView {
        return TextView(this).apply {
            text = msg
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(pal.fgMutedInt)
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
    }

    private fun makeDrawerRow(label: String, pal: ThemePalette, colorInt: Int = pal.fgPrimaryInt, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.8f)
            setTextColor(colorInt)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            minHeight = dp(42)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(pal.bgPressedInt) })
                addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            }
            setOnClickListener {
                runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                onClick()
            }
        }
    }

    private fun makeHorizontalRule(pal: ThemePalette): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
            setBackgroundColor(pal.dividerInt)
        }
    }

    private fun performUndoAction() {
        val prevText = undoOriginalFullText
        val uNode = undoNode
        if (prevText != null && uNode != null) {
            runCatching { uNode.refresh() }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prevText)
            }
            val ok = runCatching { uNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
            undoOriginalFullText = null
            undoNode = null
            Toast.makeText(this, if (ok) "Restored" else "Copied original", Toast.LENGTH_SHORT).show()
            if (!ok) smoothCopyText(prevText)
        }
        hidePalette()
    }

    private fun openWhatsAppNumber(waDigits: String, isBusiness: Boolean) {
        val targetPkg = if (isBusiness) "com.whatsapp.w4b" else "com.whatsapp"
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
        openUrl("https://wa.me/$waDigits")
    }

    private fun speakTextLazy(text: String) {
        val snippet = text.take(500)
        if (ttsReady && tts != null) {
            tts?.speak(snippet, TextToSpeech.QUEUE_FLUSH, null, "sel_tts")
            Toast.makeText(this, "Speaking…", Toast.LENGTH_SHORT).show()
            return
        }
        pendingTtsText = snippet
        if (tts == null) {
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.US
                    ttsReady = true
                    pendingTtsText?.takeIf { it.isNotBlank() }?.let {
                        tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "sel_tts")
                    }
                    pendingTtsText = null
                }
            }
        }
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
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, w, 0, 0, w, h) }
        }.getOrNull()
    }

    private fun relayoutPopupWindow() {
        val v = popupView
        val p = popupParams
        if (v != null && p != null && v.isAttachedToWindow) {
            v.requestLayout()
            runCatching { windowManager.updateViewLayout(v, p) }
        }
    }

    private fun enableDragOnView(handle: View, rootView: View) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        handle.setOnTouchListener { _, event ->
            val p = popupParams ?: return@setOnTouchListener false
            val screen = selectionDetector.getRealScreenBounds()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mainHandler.removeCallbacks(delayedHideRunnable)
                    initialX = p.x
                    initialY = p.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) userManuallyDragged = true
                    p.x = (initialX + dx).safeCoerceIn(dp(6), (screen.width() - rootView.width - dp(6)).coerceAtLeast(dp(6)))
                    p.y = (initialY + dy).safeCoerceIn(dp(36), (screen.height() - dp(72)).coerceAtLeast(dp(40)))
                    if (rootView.isAttachedToWindow) runCatching { windowManager.updateViewLayout(rootView, p) }
                    true
                }
                else -> false
            }
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        hidePalette()
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
            val start = minOf(node.textSelectionStart, node.textSelectionEnd)
            val end = maxOf(node.textSelectionStart, node.textSelectionEnd)
            if (start >= 0 && end > start && end <= full.length) {
                undoOriginalFullText = full
                undoNode = node
                undoTimestamp = System.currentTimeMillis()
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, full.replaceRange(start, end, newText))
                }
                replaced = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
            } else {
                replaced = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)
            }
        }
        if (!replaced) Toast.makeText(this, "Copied: ${newText.take(28)}", Toast.LENGTH_SHORT).show()
        hidePalette()
    }

    private fun appendToScratchpad(text: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        File(filesDir, "scratchpad.txt").appendText("[$stamp]\n${text.trim()}\n\n")
    }

    private fun hidePalette() {
        mainHandler.removeCallbacks(delayedHideRunnable)
        cancelPendingExtracts()
        networkTools.cancelPending()
        // Reset toolbar signature so selecting again in the same spot ALWAYS triggers!
        lastAutoCopyToolbarSig = ""
        lastAutoCopyTime = 0L
        popupView?.let { runCatching { windowManager.removeView(it) } }
        popupView = null
        popupParams = null
        barRowsContainer = null
        drawerDividerView = null
        drawerScrollView = null
        drawerContainer = null
        currentMode = PopupMode.BAR
    }

    override fun onInterrupt() {
        hideScreenGrabOverlay()
        hidePalette()
    }
}
