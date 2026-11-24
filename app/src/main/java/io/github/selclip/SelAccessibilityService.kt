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

    private data class PopupColors(
        val cardBg: Int,
        val headerBg: Int,
        val chipBg: Int,
        val pressedBg: Int,
        val border: Int,
        val divider: Int,
        val textPrimary: Int,
        val textMuted: Int,
        val accent: Int,
        val onAccent: Int,
        val accentSoftBg: Int,
        val secondaryAccent: Int,
        val danger: Int
    )

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
    private var currentMaxTotalHeight: Int = 760
    private var dynamicMaxScrollHeight: Int = 640
    private var dynamicMaxPopupWidth: Int = 680
    private var dynamicMinPopupWidth: Int = 480
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
    private fun dpF(v: Float): Float = v * resources.displayMetrics.density

    private fun resolvePopupColors(): PopupColors {
        val isDark = ToolPrefs.resolvePalette(this).isDark
        return if (isDark) {
            PopupColors(
                cardBg = Color.parseColor("#1E2028"),
                headerBg = Color.parseColor("#181A20"),
                chipBg = Color.parseColor("#2A2D38"),
                pressedBg = Color.parseColor("#383D4D"),
                border = Color.parseColor("#383E52"),
                divider = Color.parseColor("#2E3342"),
                textPrimary = Color.parseColor("#F1F3F5"),
                textMuted = Color.parseColor("#9AA0B4"),
                accent = Color.parseColor("#8AB4F8"),
                onAccent = Color.parseColor("#14171D"),
                accentSoftBg = Color.argb(42, 138, 180, 248),
                secondaryAccent = Color.parseColor("#50FA7B"),
                danger = Color.parseColor("#FF6B6B")
            )
        } else {
            PopupColors(
                cardBg = Color.parseColor("#FFFFFF"),
                headerBg = Color.parseColor("#F7F8FA"),
                chipBg = Color.parseColor("#EEF1F6"),
                pressedBg = Color.parseColor("#DFE5EE"),
                border = Color.parseColor("#DADCE0"),
                divider = Color.parseColor("#E8EAED"),
                textPrimary = Color.parseColor("#202124"),
                textMuted = Color.parseColor("#5F6368"),
                accent = Color.parseColor("#1A73E8"),
                onAccent = Color.parseColor("#FFFFFF"),
                accentSoftBg = Color.argb(28, 26, 115, 232),
                secondaryAccent = Color.parseColor("#188038"),
                danger = Color.parseColor("#D93025")
            )
        }
    }

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

    /**
     * Exact Google Lens trigger flow:
     * - On Android 11+ (API 30+): Instantly captures a frozen frame via takeScreenshot(),
     *   shows LensOverlayView immediately with a scan shimmer, and runs on-device ML Kit
     *   word-level OCR (~200ms). Falls back seamlessly to Accessibility word harvesting if
     *   FLAG_SECURE blocks screenshots or OCR detects 0 words.
     * - On Android 8–10: Immediately harvests word-level Accessibility text into LensOverlayView.
     */
    fun triggerScreenGrab(delayMs: Long = 120L) {
        hidePalette()
        hideScreenGrabOverlay()

        mainHandler.postDelayed({
            if (Build.VERSION.SDK_INT >= 30) {
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
                                    launchAccessibilityLensFallback()
                                    return
                                }

                                // Show frozen frame + scan shimmer IMMEDIATELY (just like Google Lens!)
                                val lensView = showLensOverlay(backgroundBitmap = softBmp, initialSnapshot = null)
                                    ?: return

                                screenOcrEngine.recognizeBitmap(
                                    bitmap = softBmp,
                                    density = resources.displayMetrics.density,
                                    onSuccess = { ocrSnap ->
                                        if (screenGrabOverlayView !== lensView) return@recognizeBitmap
                                        if (ocrSnap.words.isNotEmpty()) {
                                            lensView.setSnapshot(ocrSnap)
                                        } else {
                                            val fallback = screenHarvester.harvestAsLensSnapshot()
                                            lensView.setSnapshot(fallback)
                                        }
                                    },
                                    onFailure = {
                                        if (screenGrabOverlayView !== lensView) return@recognizeBitmap
                                        val fallback = screenHarvester.harvestAsLensSnapshot()
                                        lensView.setSnapshot(fallback)
                                    }
                                )
                            }

                            override fun onFailure(errorCode: Int) {
                                // e.g., FLAG_SECURE app -> seamlessly fall back to Accessibility Lens
                                launchAccessibilityLensFallback()
                            }
                        }
                    )
                }.onFailure {
                    launchAccessibilityLensFallback()
                }
            } else {
                launchAccessibilityLensFallback()
            }
        }, delayMs)
    }

    private fun launchAccessibilityLensFallback() {
        val snap = screenHarvester.harvestAsLensSnapshot()
        if (snap.words.isEmpty()) {
            Toast.makeText(this, "No text found on screen", Toast.LENGTH_SHORT).show()
            return
        }
        showLensOverlay(backgroundBitmap = null, initialSnapshot = snap)
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
            networkTools = networkTools,
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
                    "clean_url" -> smoothCopyText(TextTransforms.cleanUrl(chosen), "Cleaned URL copied")
                    "open_url" -> openUrl(chosen)
                    "dial" -> {
                        val dialNum = TextTransforms.extractNepalPhone(chosen)?.dialNumber ?: chosen
                        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(dialNum)}")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { startActivity(dialIntent) }
                    }
                    "math" -> {
                        val ans = TextTransforms.evalSimpleMath(chosen)
                        if (ans != null) smoothCopyText(ans, "Copied result: $ans")
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
                openUrl("https://www.google.com/search?q=${Uri.encode(chosen)}")
            },
            onListenText = { chosen ->
                speakTextLazy(chosen)
            },
            onDismiss = {
                hideScreenGrabOverlay()
            }
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
        val preview = text.replace("\n", " ").let { if (it.length > 28) it.take(27) + "…" else it }
        Toast.makeText(this, toastLabel ?: "Copied: \"$preview\"", Toast.LENGTH_SHORT).show()
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
        runCatching { handleAccessibilityEventInternal(event) }
    }

    private fun handleAccessibilityEventInternal(event: AccessibilityEvent?) {
        event ?: return
        if (screenGrabOverlayView != null) return
        val pkg = event.packageName?.toString() ?: ""
        val cls = event.className?.toString() ?: ""

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
                    if (System.currentTimeMillis() - lastShownTime > 500L) {
                        hidePalette()
                    }
                }
            }
        }

        if (pkg.isNotEmpty() && ToolPrefs.isAppBlacklisted(this, pkg)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (popupView != null && pkg.isNotEmpty() && pkg != packageName) {
                    val elapsed = System.currentTimeMillis() - lastShownTime
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
                    if (selectionDetector.isTransientSystemOrPopupClass(cls)) {
                        val toolbar = selectionDetector.findActiveSelectionToolbarNow()
                        if (toolbar != null) {
                            scheduleUniversalSelectionExtract(event.source, activePackage.ifEmpty { pkg }, toolbar.signature)
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
            spaceBelow >= dp(240) && (spaceBelow >= spaceAbove || spaceBelow >= dp(300)) -> false
            spaceAbove >= dp(150) && spaceAbove > spaceBelow -> true
            spaceBelow >= dp(160) -> false
            else -> spaceAbove > spaceBelow
        }
        anchoredAboveSelection = placeAbove

        val chosenSpace = if (placeAbove) spaceAbove else spaceBelow
        currentMaxTotalHeight = chosenSpace.safeCoerceIn(dp(120), (realH * 0.46f).toInt())
        val headerHeightEst = dp(40)
        dynamicMaxScrollHeight = (currentMaxTotalHeight - headerHeightEst).safeCoerceIn(dp(72), dp(340))

        val twoColGrid = ToolPrefs.isTwoColumnGrid(this) && currentMode == PopupMode.ACTIONS
        dynamicMinPopupWidth = when {
            currentMode != PopupMode.ACTIONS -> dp(244)
            twoColGrid -> dp(256)
            else -> dp(236)
        }
        dynamicMaxPopupWidth = when {
            currentMode != PopupMode.ACTIONS -> dp(288)
            twoColGrid -> dp(284)
            else -> dp(264)
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

    private fun applyPaletteToPopupChrome(c: PopupColors) {
        popupView?.background = GradientDrawable().apply {
            setColor(c.cardBg)
            setStroke(dp(1), c.border)
            cornerRadius = if (currentMode == PopupMode.COLLAPSED_PILL) dpF(22f) else dpF(16f)
        }
        headerBarView?.background = GradientDrawable().apply {
            setColor(c.headerBg)
            cornerRadii = floatArrayOf(dpF(16f), dpF(16f), dpF(16f), dpF(16f), 0f, 0f, 0f, 0f)
        }
        headerDividerView?.setBackgroundColor(c.divider)
        statsLabelView?.setTextColor(c.textMuted)

        val twoCol = ToolPrefs.isTwoColumnGrid(this)
        gridToggleBtn?.text = if (twoCol) "☰" else "▦"
        gridToggleBtn?.setTextColor(if (twoCol) c.accent else c.textMuted)

        collapseBtnView?.setTextColor(c.textMuted)
        reorderToggleBtn?.setTextColor(if (currentMode == PopupMode.REORDER) c.accent else c.textMuted)
        closeBtnView?.setTextColor(c.danger)
    }

    private fun showOrUpdatePalette(safeZone: Rect) {
        val c = resolvePopupColors()

        if (popupView != null && popupView?.isAttachedToWindow != true) {
            hidePalette()
        }

        if (popupView != null && statsLabelView != null && bodyContainer != null && popupParams != null) {
            applyStrictPlacementAndDynamicSize(safeZone, popupParams!!)
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
            elevation = dpF(10f)
            clipToOutline = true
            background = GradientDrawable().apply {
                setColor(c.cardBg)
                setStroke(dp(1), c.border)
                cornerRadius = dpF(16f)
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
            setPadding(dp(12), dp(7), dp(8), dp(7))
        }

        val statsLabel = TextView(this).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(c.textMuted)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        fun makeHeaderIconBtn(icon: String, fgColor: Int, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = icon
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(fgColor)
                gravity = Gravity.CENTER
                minWidth = dp(28)
                minHeight = dp(26)
                setPadding(dp(4), dp(2), dp(4), dp(2))
                background = makePressedDrawable(c.pressedBg, dpF(8f))
                setOnClickListener {
                    runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    onClick()
                }
            }
        }

        val twoColInitial = ToolPrefs.isTwoColumnGrid(this)
        val gridBtn = makeHeaderIconBtn(if (twoColInitial) "☰" else "▦", if (twoColInitial) c.accent else c.textMuted) {
            lastShownTime = System.currentTimeMillis()
            ToolPrefs.setTwoColumnGrid(this@SelAccessibilityService, !ToolPrefs.isTwoColumnGrid(this@SelAccessibilityService))
            renderPopupBody()
        }

        val collapseBtn = makeHeaderIconBtn("—", c.textMuted) {
            lastShownTime = System.currentTimeMillis()
            currentMode = PopupMode.COLLAPSED_PILL
            renderPopupBody()
        }

        val reorderBtn = makeHeaderIconBtn("⚙", c.textMuted) {
            lastShownTime = System.currentTimeMillis()
            currentMode = if (currentMode == PopupMode.REORDER) PopupMode.ACTIONS else PopupMode.REORDER
            renderPopupBody()
        }

        val closeBtn = makeHeaderIconBtn("✕", c.danger) { hidePalette() }

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
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
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

        // Smooth pop-in entrance animation
        rootCard.alpha = 0f
        rootCard.scaleX = 0.94f
        rootCard.scaleY = 0.94f
        val added = runCatching { windowManager.addView(popupView, windowParams) }.isSuccess
        if (!added) {
            hidePalette()
        } else {
            rootCard.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(110L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun renderPopupBody() {
        val container = bodyContainer ?: return
        val c = resolvePopupColors()
        networkTools.cancelPending()
        applyPaletteToPopupChrome(c)
        container.removeAllViews()

        val isPill = currentMode == PopupMode.COLLAPSED_PILL
        headerBarView?.visibility = if (isPill) View.GONE else View.VISIBLE
        headerDividerView?.visibility = if (isPill) View.GONE else View.VISIBLE

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
            val snippet = selectedText.trim().replace("\n", " ").let {
                if (it.length > 16) it.take(15) + "…" else it
            }
            val words = TextTransforms.countWords(selectedText)
            val headerTxt = when (currentMode) {
                PopupMode.ACTIONS, PopupMode.COLLAPSED_PILL -> if (words <= 2 && snippet.isNotEmpty()) "\"$snippet\"" else "$words words"
                PopupMode.WEB_SEARCH_FOLDER -> "‹ Web Search"
                PopupMode.YOUTUBE_FOLDER -> "‹ YouTube"
                PopupMode.CLIP_HISTORY -> "‹ Clipboard"
                PopupMode.MARKDOWN_FOLDER -> "‹ Markdown"
                PopupMode.REGEX_PIPE -> "‹ Text Pipe"
                PopupMode.CUSTOM_FOLDER -> "‹ ${toTitleCase(activeCustomFolder)}"
                PopupMode.QR_CODE -> "‹ QR Code"
                PopupMode.MAGNET -> "‹ Magnet Link"
                PopupMode.DEFINE_WORD -> "‹ Definition"
                PopupMode.TRANSLATE -> "‹ Translate"
                PopupMode.EXTRACT -> "‹ Extracted Links"
                PopupMode.REORDER -> "‹ Done Editing"
            }
            statsLabelView?.text = headerTxt
            statsLabelView?.setTextColor(if (currentMode == PopupMode.ACTIONS) c.textMuted else c.accent)
        }

        relayoutPopupWindow()
    }

    private fun populateCollapsedPill(container: LinearLayout) {
        val c = resolvePopupColors()
        val pill = TextView(this).apply {
            text = "sel ›"
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(c.accent)
            setPadding(dp(14), dp(8), dp(14), dp(8))
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

    private fun friendlyToolLabel(tool: ToolSpec): String {
        return when (tool.id) {
            "copy" -> "Copy"
            "screen_grab" -> "✨ Lens Grab"
            "web_search_folder" -> "🔍 Web Search ›"
            "youtube_folder" -> "▶ YouTube ›"
            "clip_history" -> "📋 Clip History ›"
            "markdown_folder" -> "📝 Markdown ›"
            "regex_pipe" -> "⚡ Text Pipe ›"
            "define" -> "📖 Define Word"
            "translate" -> "🌐 Translate"
            "qr" -> "◼ QR Code"
            "magnet" -> "🧲 Magnet Link"
            "clean_url" -> "✨ Clean URL"
            "extract" -> "🔗 Extract Links"
            "clean_text" -> "🧹 Clean Text"
            "sort" -> "↕ Sort Lines"
            "case" -> "Aa Cycle Case"
            "speak" -> "🔊 Speak Aloud"
            "log" -> "📌 Save to Notes"
            else -> toTitleCase(tool.label.replace(">", "›"))
        }
    }

    private fun friendlyShortChip(tool: ToolSpec): String {
        return when (tool.id) {
            "screen_grab" -> "Lens"
            "web_search_folder" -> "Web"
            "youtube_folder" -> "YT"
            "clip_history" -> "Clips"
            "markdown_folder" -> "MD"
            "regex_pipe" -> "Pipe"
            "define" -> "Define"
            "translate" -> "Trans"
            "qr" -> "QR"
            "magnet" -> "Magnet"
            "clean_url" -> "Clean"
            "extract" -> "Links"
            "clean_text" -> "Tidy"
            "sort" -> "Sort"
            "case" -> "Case"
            "speak" -> "Speak"
            "log" -> "Note"
            else -> toTitleCase(tool.label.replace(">", "").trim()).take(7)
        }
    }

    private fun toTitleCase(input: String): String {
        return input.lowercase().split(' ').joinToString(" ") { w ->
            w.replaceFirstChar { it.uppercase() }
        }
    }

    private fun populateActionRows(list: LinearLayout) {
        val c = resolvePopupColors()
        val pinnedIds = HashSet<String>()

        // 0. 1-Tap Undo banner
        val prevText = undoOriginalFullText
        val uNode = undoNode
        if (prevText != null && uNode != null && System.currentTimeMillis() - undoTimestamp < 45000L) {
            list.addView(makeHighlightBanner("↩ Undo last edit", c.accent) {
                runCatching { uNode.refresh() }
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prevText)
                }
                val ok = runCatching { uNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
                undoOriginalFullText = null
                undoNode = null
                Toast.makeText(this, if (ok) "Restored original text" else "Copied original text", Toast.LENGTH_SHORT).show()
                if (!ok) smoothCopyText(prevText)
                hidePalette()
            })
        }

        // 0b. Context-Aware Phone Quick Strip
        TextTransforms.extractNepalPhone(selectedText)?.let { phone ->
            list.addView(makeNepalPhoneActionRow(phone))
        }

        // 0c. Primary Quick Action Bar: Copy · Paste · Cut · All
        if (!ToolPrefs.isToolHidden(this, "copy")) {
            pinnedIds.add("copy")
            list.addView(makeQuickEditStrip())
        }

        // 0d. User-Pinned Favorite Chip Rows (Row 1 & Row 2, up to 4 readable pills each)
        for (rowNum in 1..2) {
            val rowTools = ToolPrefs.getIconRowTools(this, rowNum).take(ToolPrefs.MAX_TOOLS_PER_ICON_ROW)
            if (rowTools.isNotEmpty()) {
                rowTools.forEach { pinnedIds.add(it.id) }
                list.addView(makePinnedChipsRow(rowTools))
            }
        }

        // 1. Instant Smart Evaluators (Math, Unit, Timestamp, Base64)
        TextTransforms.evalSimpleMath(selectedText)?.let { ans ->
            list.addView(makeHighlightBanner("= $ans  ·  Tap to apply", c.secondaryAccent) {
                ToolPrefs.recordUse(this, "math")
                applyResult(ans)
            })
        }
        TextTransforms.evalUnitConversion(selectedText)?.let { conv ->
            list.addView(makeHighlightBanner("$conv  ·  Tap to apply", c.secondaryAccent) {
                ToolPrefs.recordUse(this, "unit_conv")
                applyResult(conv.removePrefix("= ").trim())
            })
        }
        TextTransforms.evalTimestamp(selectedText)?.let { formattedDate ->
            list.addView(makeHighlightBanner("🕒 $formattedDate", c.secondaryAccent) {
                ToolPrefs.recordUse(this, "epoch_conv")
                applyResult(formattedDate)
            })
        }
        TextTransforms.evalBase64Decode(selectedText)?.let { decoded ->
            val preview = decoded.replace("\n", " ").let { if (it.length > 22) it.take(21) + "…" else it }
            list.addView(makeHighlightBanner("Base64 → $preview", c.secondaryAccent) {
                ToolPrefs.recordUse(this, "b64_decode")
                applyResult(decoded)
            })
        }

        // Context-pinned Magnet or Clean URL
        if (isToolRelevantForSelection("magnet", selectedText) && !ToolPrefs.isToolHidden(this, "magnet")) {
            pinnedIds.add("magnet")
            list.addView(makeHighlightBanner("🧲 Open Magnet Link ›", c.accent) {
                ToolPrefs.recordUse(this, "magnet")
                switchPopupMode(PopupMode.MAGNET)
            })
        }
        if (isToolRelevantForSelection("clean_url", selectedText) && !ToolPrefs.isToolHidden(this, "clean_url")) {
            pinnedIds.add("clean_url")
            list.addView(makeHighlightBanner("✨ Clean Tracking URL", c.accent) {
                ToolPrefs.recordUse(this, "clean_url")
                applyResult(TextTransforms.cleanUrl(selectedText))
            })
        }

        list.addView(makeHorizontalRule())

        // 2. Context-Filtered Tools List (1-Col or 2-Col clean rounded pills)
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
                    list.addView(makeRow(friendlyToolLabel(t1), b1, onLongClick = { executeToolLongPress(t1) }) {
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
                list.addView(makeRow(friendlyToolLabel(tool), badge, onLongClick = { executeToolLongPress(tool) }) {
                    ToolPrefs.recordUse(this, tool.id)
                    executeTool(tool)
                })
            }
        }
    }

    private fun makeHighlightBanner(textStr: String, accentColor: Int, onClick: () -> Unit): View {
        val c = resolvePopupColors()
        return TextView(this).apply {
            text = textStr
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f)
            setTextColor(accentColor)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    GradientDrawable().apply {
                        setColor(c.pressedBg)
                        cornerRadius = dpF(10f)
                    }
                )
                addState(
                    intArrayOf(),
                    GradientDrawable().apply {
                        setColor(c.accentSoftBg)
                        cornerRadius = dpF(10f)
                    }
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            setOnClickListener {
                runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                onClick()
            }
        }
    }

    private fun makePressedDrawable(pressedColor: Int, radiusPx: Float = dpF(10f)): StateListDrawable {
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                GradientDrawable().apply {
                    setColor(pressedColor)
                    cornerRadius = radiusPx
                }
            )
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }
    }

    private fun makeChipDrawable(normalColor: Int, pressedColor: Int, radiusPx: Float = dpF(10f)): StateListDrawable {
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                GradientDrawable().apply {
                    setColor(pressedColor)
                    cornerRadius = radiusPx
                }
            )
            addState(
                intArrayOf(),
                GradientDrawable().apply {
                    setColor(normalColor)
                    cornerRadius = radiusPx
                }
            )
        }
    }

    private fun makeQuickEditStrip(): View {
        val c = resolvePopupColors()
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }

        fun makeQuickCell(
            label: String,
            primary: Boolean,
            marginEndDp: Int,
            onLongPress: () -> Unit,
            onTap: () -> Unit
        ): TextView {
            return TextView(this).apply {
                text = label
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f)
                setTextColor(if (primary) c.onAccent else c.textPrimary)
                gravity = Gravity.CENTER
                isSingleLine = true
                setPadding(dp(4), dp(8), dp(4), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, if (primary) 1.15f else 1f).apply {
                    marginEnd = dp(marginEndDp)
                }
                background = makeChipDrawable(
                    normalColor = if (primary) c.accent else c.chipBg,
                    pressedColor = if (primary) c.accent else c.pressedBg,
                    radiusPx = dpF(10f)
                )
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

        strip.addView(makeQuickCell("Copy", primary = true, marginEndDp = 5, onLongPress = {
            val existing = runCatching {
                clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            }.getOrNull().orEmpty()
            val combined = if (existing.isNotBlank()) "$existing\n$selectedText" else selectedText
            smoothCopyText(combined, "Appended to clipboard")
            hidePalette()
        }) {
            ToolPrefs.recordUse(this, "copy")
            smoothCopyText(selectedText)
            hidePalette()
        })

        strip.addView(makeQuickCell("Paste", primary = false, marginEndDp = 5, onLongPress = {
            switchPopupMode(PopupMode.CLIP_HISTORY)
        }) { performPasteAction() })

        strip.addView(makeQuickCell("Cut", primary = false, marginEndDp = 5, onLongPress = {
            Toast.makeText(this, "Cut selection", Toast.LENGTH_SHORT).show()
        }) { performCutAction() })

        strip.addView(makeQuickCell("All", primary = false, marginEndDp = 0, onLongPress = {
            Toast.makeText(this, "Select all text", Toast.LENGTH_SHORT).show()
        }) { performSelectAllAction() })

        return strip
    }

    private fun makePinnedChipsRow(rowTools: List<ToolSpec>): View {
        val c = resolvePopupColors()
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(5) }
        }

        val items = rowTools.take(ToolPrefs.MAX_TOOLS_PER_ICON_ROW)
        items.forEachIndexed { idx, tool ->
            val label = friendlyShortChip(tool)
            val cell = TextView(this).apply {
                text = label
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(c.accent)
                gravity = Gravity.CENTER
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(4), dp(7), dp(4), dp(7))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx < items.lastIndex) marginEnd = dp(5)
                }
                background = makeChipDrawable(c.chipBg, c.pressedBg, dpF(10f))
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
                val updated = if (end >= start && start <= full.length) full.replaceRange(start, end, clipText) else full + clipText
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
                }
                ok = runCatching { targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
            }
            Toast.makeText(this, if (ok) "Pasted" else "Clipboard ready to paste", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Focus a text field to paste", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(this, if (cutOk) "Cut to clipboard" else "Copied (read-only text)", Toast.LENGTH_SHORT).show()
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
                ok = runCatching { targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args) }.getOrDefault(false)
            }
            if (!ok) runCatching { targetNode.performAction(android.R.id.selectAll) }
            if (full.isNotEmpty()) {
                selectedText = full
                lastShownTime = System.currentTimeMillis()
                Toast.makeText(this, "Selected all (${full.length} chars)", Toast.LENGTH_SHORT).show()
                renderPopupBody()
                return
            }
        }
        Toast.makeText(this, "Select All requested", Toast.LENGTH_SHORT).show()
    }

    private fun makeNepalPhoneActionRow(phone: NepalPhone): View {
        val c = resolvePopupColors()
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }

        fun makePhonePill(labelStr: String, fgColor: Int, marginEndDp: Int, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = labelStr
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(fgColor)
                gravity = Gravity.CENTER
                isSingleLine = true
                setPadding(dp(4), dp(7), dp(4), dp(7))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(marginEndDp)
                }
                background = makeChipDrawable(c.accentSoftBg, c.pressedBg, dpF(10f))
                setOnClickListener {
                    runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    onClick()
                }
            }
        }

        strip.addView(makePhonePill("📞 Call", c.secondaryAccent, 4) {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone.dialNumber)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { startActivity(dialIntent) }
            hidePalette()
        })
        strip.addView(makePhonePill("SMS", c.textPrimary, 4) {
            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(phone.dialNumber)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { startActivity(smsIntent) }.isFailure) {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("sms:${Uri.encode(phone.dialNumber)}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            }
            hidePalette()
        })
        strip.addView(makePhonePill("WA", c.secondaryAccent, 4) {
            openWhatsAppNumber(phone.waDigits, isBusiness = false)
        })
        strip.addView(makePhonePill("W4B", c.accent, 0) {
            openWhatsAppNumber(phone.waDigits, isBusiness = true)
        })
        return strip
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
        val c = resolvePopupColors()
        val pairRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }

        fun makeCell(tool: ToolSpec, marginEndDp: Int): View {
            return TextView(this).apply {
                text = friendlyToolLabel(tool)
                typeface = Typeface.SANS_SERIF
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(c.textPrimary)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(marginEndDp)
                }
                background = makeChipDrawable(c.chipBg, c.pressedBg, dpF(10f))
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

        pairRow.addView(makeCell(t1, 4))
        pairRow.addView(makeCell(t2, 0))
        return pairRow
    }

    private fun populateClipHistorySubmenu(container: LinearLayout) {
        val c = resolvePopupColors()
        container.addView(makeRow("Clear unpinned clips", null) {
            ToolPrefs.clearUnpinnedClips(this)
            renderPopupBody()
        })
        container.addView(makeHorizontalRule())

        val clips = ToolPrefs.getClipHistory(this)
        if (clips.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Clipboard history is empty."
                typeface = Typeface.SANS_SERIF
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(c.textMuted)
                setPadding(dp(10), dp(12), dp(10), dp(12))
            })
            return
        }

        for (entry in clips) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = makeChipDrawable(c.chipBg, c.pressedBg, dpF(10f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
            }

            val pinBtn = TextView(this).apply {
                text = if (entry.pinned) "★" else "☆"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(if (entry.pinned) c.secondaryAccent else c.textMuted)
                setPadding(dp(4), dp(2), dp(8), dp(2))
                setOnClickListener {
                    ToolPrefs.togglePinClip(this@SelAccessibilityService, entry.text)
                    renderPopupBody()
                }
            }

            val textBtn = TextView(this).apply {
                text = entry.text.replace("\n", " ↵ ")
                typeface = Typeface.SANS_SERIF
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(c.textPrimary)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { applyResult(entry.text) }
            }

            val delBtn = TextView(this).apply {
                text = "✕"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(c.danger)
                setPadding(dp(8), dp(2), dp(2), dp(2))
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
        for (mdTool in ToolPrefs.loadMarkdownTools(this)) {
            container.addView(makeRow(toTitleCase(mdTool.label), null) {
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
        for (pTool in ToolPrefs.loadRegexPipeTools(this)) {
            container.addView(makeRow(toTitleCase(pTool.label), null) {
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
        for (engine in ToolPrefs.loadWebSearchTools(this)) {
            container.addView(makeRow(toTitleCase(engine.label), null) {
                ToolPrefs.recordUse(this, "web_search_folder")
                ToolPrefs.recordUse(this, engine.id)
                executeTool(engine)
            })
        }
    }

    private fun populateYoutubeSubmenu(container: LinearLayout) {
        for (ytTool in ToolPrefs.loadYoutubeTools(this)) {
            container.addView(makeRow(toTitleCase(ytTool.label), null) {
                ToolPrefs.recordUse(this, "youtube_folder")
                ToolPrefs.recordUse(this, ytTool.id)
                executeTool(ytTool)
            })
        }
    }

    private fun populateCustomFolderSubmenu(container: LinearLayout, folderName: String) {
        val c = resolvePopupColors()
        val items = ToolPrefs.loadCustomFolderTools(this, folderName)
        if (items.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Folder is empty. Assign tools in Settings → Tools."
                typeface = Typeface.SANS_SERIF
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(c.textMuted)
                setPadding(dp(10), dp(10), dp(10), dp(10))
            })
            return
        }
        for (item in items) {
            container.addView(makeRow(friendlyToolLabel(item), null) {
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
        Toast.makeText(this, "Starting speech…", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "Saved to Notes", Toast.LENGTH_SHORT).show()
                hidePalette()
            }
        }
    }

    private fun switchPopupMode(mode: PopupMode) {
        lastShownTime = System.currentTimeMillis()
        currentMode = mode
        renderPopupBody()
    }

    private fun makeActionPill(label: String, primary: Boolean, onClick: () -> Unit): TextView {
        val c = resolvePopupColors()
        return TextView(this).apply {
            text = label
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(if (primary) c.onAccent else c.textPrimary)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = makeChipDrawable(if (primary) c.accent else c.chipBg, c.pressedBg, dpF(10f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            setOnClickListener {
                runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                onClick()
            }
        }
    }

    private fun populateMagnetView(container: LinearLayout) {
        val c = resolvePopupColors()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val hash = TextTransforms.extractTorrentHash(selectedText)
        val magnetUri = when {
            hash != null -> TextTransforms.buildMagnetUri(hash)
            selectedText.trim().startsWith("magnet:?", ignoreCase = true) -> selectedText.trim()
            else -> "magnet:?xt=urn:btih:${selectedText.trim().replace(TextTransforms.whitespaceRegex, "")}"
        }

        box.addView(TextView(this).apply {
            text = magnetUri
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(c.textPrimary)
            background = GradientDrawable().apply {
                setColor(c.chipBg)
                cornerRadius = dpF(10f)
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        })
        box.addView(makeActionPill("Copy Magnet Link", primary = true) {
            smoothCopyText(magnetUri, "Magnet link copied")
            hidePalette()
        })
        box.addView(makeActionPill("Open in Torrent App", primary = false) {
            smoothCopyText(magnetUri, "Opening magnet…")
            openUrl(magnetUri)
        })
        box.addView(makeActionPill("Replace Selection", primary = false) { applyResult(magnetUri) })
        container.addView(box)
    }

    private fun populateQrView(container: LinearLayout) {
        val c = resolvePopupColors()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val content = selectedText.trim().take(1200)
        val bmp = generateQrBitmap(content, dp(180))
        if (bmp != null) {
            box.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dpF(12f)
                }
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(196), dp(196))
            })
        } else {
            box.addView(TextView(this).apply {
                text = "Could not encode QR"
                setTextColor(c.danger)
            })
        }

        box.addView(TextView(this).apply {
            text = content.take(64) + if (content.length > 64) "…" else ""
            typeface = Typeface.SANS_SERIF
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(c.textMuted)
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
        val c = resolvePopupColors()
        val rawTerm = selectedText.trim()
        val firstWord = rawTerm.split(TextTransforms.whitespaceRegex).firstOrNull()
            ?.trim { !it.isLetterOrDigit() && it != '-' }
            ?.ifBlank { rawTerm.take(32) }
            ?: rawTerm.take(32)

        val bodyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

        bodyBox.addView(TextView(this).apply {
            text = firstWord
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(c.accent)
            setPadding(0, 0, 0, dp(6))
        })

        val defTextView = TextView(this).apply {
            text = "Looking up \"$firstWord\"…"
            typeface = Typeface.SANS_SERIF
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.8f)
            setTextColor(c.textPrimary)
            setLineSpacing(0f, 1.22f)
        }

        val copyDefBtn = makeActionPill("Copy Definition", primary = true) {
            smoothCopyText(defTextView.text.toString(), "Definition copied")
        }.apply { visibility = View.GONE }

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
        val c = resolvePopupColors()
        val raw = selectedText.trim().take(400)

        val bodyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

        val transTextView = TextView(this).apply {
            text = "Translating to English…"
            typeface = Typeface.SANS_SERIF
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.2f)
            setTextColor(c.textPrimary)
            setLineSpacing(0f, 1.22f)
        }

        val useBtn = makeActionPill("Copy / Apply Translation", primary = true) {
            applyResult(transTextView.text.toString())
        }.apply { visibility = View.GONE }

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
        val c = resolvePopupColors()
        val matches = TextTransforms.extractEntities(selectedText, limit = 12)
        if (matches.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No URLs, emails, phones, or tags found."
                typeface = Typeface.SANS_SERIF
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(c.textMuted)
                setPadding(dp(10), dp(12), dp(10), dp(12))
            })
            return
        }
        matches.forEach { entity ->
            container.addView(makeRow("${entity.type}: ${entity.value.take(26)}", null) {
                applyResult(entity.value)
            })
        }
    }

    private fun populateReorderView(container: LinearLayout) {
        val c = resolvePopupColors()
        val mostUsedOn = ToolPrefs.isSortMostUsed(this)
        container.addView(makeRow(if (mostUsedOn) "Sort: Most Used First" else "Sort: Manual Order", null) {
            ToolPrefs.setSortMostUsed(this, !mostUsedOn)
            renderPopupBody()
        })

        if (activePackage.isNotBlank() && activePackage != packageName) {
            val isMuted = ToolPrefs.isAppBlacklisted(this, activePackage)
            val shortPkg = activePackage.substringAfterLast('.')
            container.addView(makeRow(if (isMuted) "Unmute in $shortPkg" else "Mute popup in $shortPkg", null) {
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
                setPadding(dp(6), dp(6), dp(6), dp(6))
                tag = tool.id
            }

            val dragHandle = TextView(this).apply {
                text = "≡"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(c.accent)
                setPadding(dp(4), dp(2), dp(8), dp(2))
            }

            val label = TextView(this).apply {
                text = friendlyToolLabel(tool)
                typeface = Typeface.SANS_SERIF
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(if (hidden) c.textMuted else c.textPrimary)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            row.addView(dragHandle)
            row.addView(label)

            if (customFolders.isNotEmpty() && tool.id != "copy" && !tool.id.startsWith("folder_custom_")) {
                row.addView(TextView(this).apply {
                    text = assignedFolder ?: "Root"
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                    setTextColor(if (assignedFolder != null) c.accent else c.textMuted)
                    setPadding(dp(6), dp(3), dp(6), dp(3))
                    setOnClickListener {
                        ToolPrefs.cycleToolFolderAssignment(this@SelAccessibilityService, tool.id)
                        renderPopupBody()
                    }
                })
            }

            if (tool.id != "copy") {
                row.addView(TextView(this).apply {
                    text = when (assignedRow) {
                        1 -> "Row1"
                        2 -> "Row2"
                        else -> "List"
                    }
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                    setTextColor(if (assignedRow > 0) c.accent else c.textMuted)
                    setPadding(dp(6), dp(3), dp(6), dp(3))
                    setOnClickListener {
                        ToolPrefs.cycleToolIconRow(this@SelAccessibilityService, tool.id)
                        renderPopupBody()
                    }
                })
            }

            row.addView(TextView(this).apply {
                text = if (hidden) "Off" else "On"
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(if (hidden) c.textMuted else c.secondaryAccent)
                setPadding(dp(6), dp(2), dp(2), dp(2))
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
        val c = resolvePopupColors()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(2) }
            background = makePressedDrawable(c.pressedBg, dpF(10f))
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

        row.addView(TextView(this).apply {
            text = label
            typeface = Typeface.SANS_SERIF
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(c.textPrimary)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        if (!countBadge.isNullOrBlank()) {
            row.addView(TextView(this).apply {
                text = countBadge
                typeface = Typeface.SANS_SERIF
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(c.textMuted)
                setPadding(dp(6), 0, 0, 0)
            })
        }

        return row
    }

    private fun makeHorizontalRule(thick: Boolean = false): View {
        val c = resolvePopupColors()
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                if (!thick) {
                    topMargin = dp(4)
                    bottomMargin = dp(4)
                }
            }
            setBackgroundColor(c.divider)
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
                        if (abs(dx) > dp(6) || abs(dy) > dp(6)) userManuallyDragged = true
                        params.x = (initialX + dx).safeCoerceIn(dp(6), (screen.width() - rootView.width - dp(6)).coerceAtLeast(dp(6)))
                        params.y = (initialY + dy).safeCoerceIn(dp(40), (screen.height() - dp(84)).coerceAtLeast(dp(48)))
                        if (rootView.isAttachedToWindow) windowManager.updateViewLayout(rootView, params)
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
                            params.x = if (snapLeft) dp(10) else (screen.width() - rootView.width - dp(10)).coerceAtLeast(dp(10))
                            if (rootView.isAttachedToWindow) windowManager.updateViewLayout(rootView, params)
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
            Toast.makeText(this, "Copied: ${newText.take(28)}", Toast.LENGTH_SHORT).show()
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
        popupView?.let { runCatching { windowManager.removeView(it) } }
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
