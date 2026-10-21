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
import android.util.Base64
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumMap
import java.util.Locale

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

    private data class SelectionResult(
        val text: String,
        val node: AccessibilityNodeInfo?,
        val bounds: Rect
    )

    private data class NepalPhone(
        val display: String,
        val dialNumber: String,
        val waDigits: String
    )

    private lateinit var windowManager: WindowManager
    private lateinit var clipboardManager: ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false

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

    private var currentMode: PopupMode = PopupMode.ACTIONS
    private var activeCustomFolder: String = ""
    private var currentNode: AccessibilityNodeInfo? = null
    private var selectedText: String = ""
    private var lastRawCandidateBounds: Rect = Rect()
    private var lastRawCandidateTime: Long = 0L
    private var lastDetectedToolbarRect: Rect = Rect()
    private var lastDetectedToolbarTime: Long = 0L
    private var currentSafeZone: Rect = Rect()
    private var cachedVisibleBottom: Int = 0
    private var lastMetricsCacheTime: Long = 0L
    private var dynamicMaxScrollHeight: Int = 600
    private var dynamicMaxPopupWidth: Int = 540
    private var dynamicMinPopupWidth: Int = 380
    private var anchoredAboveSelection: Boolean = false
    private var userManuallyDragged: Boolean = false
    private var lastClipboardTime = 0L
    private var lastWebAutoCopyTime = 0L
    private var lastShownTime = 0L
    private var activePackage: String = ""

    // In-Place Undo State
    private var undoOriginalFullText: String? = null
    private var undoNode: AccessibilityNodeInfo? = null
    private var undoTimestamp: Long = 0L

    private val delayedHideRunnable = Runnable { hidePalette() }
    private var pendingWebCopyRunnable: Runnable? = null

    private val grabReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val act = intent?.action ?: return
            if (act == ACTION_GRAB_SCREEN || act == ACTION_EXTRACT_TEXT) {
                triggerScreenGrab(delayMs = 80L)
            }
        }
    }

    private val trackingRegex = Regex(
        "([?&])(utm_[a-z_]+|fbclid|gclid|igshid|mc_cid|mc_eid|si|ref|s)=[^&#]*",
        RegexOption.IGNORE_CASE
    )

    private val urlPattern = Regex(
        "^(https?://|www\\.|[a-zA-Z0-9-]+\\.(com|org|net|io|edu|gov|me|co|app|dev|xyz|info)(/|$)).*",
        RegexOption.IGNORE_CASE
    )

    private fun Int.safeCoerceIn(minVal: Int, maxVal: Int): Int {
        return if (minVal <= maxVal) this.coerceIn(minVal, maxVal) else minVal
    }

    private fun Float.safeCoerceIn(minVal: Float, maxVal: Float): Float {
        return if (minVal <= maxVal) this.coerceIn(minVal, maxVal) else minVal
    }

    override fun onServiceConnected() {
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
            }
        }

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

        FaviconManager.prefetchAllForTools(this)

        clipboardManager.addPrimaryClipChangedListener {
            val now = System.currentTimeMillis()
            if (now - lastClipboardTime < 650) return@addPrimaryClipChangedListener
            if (screenGrabOverlayView != null) return@addPrimaryClipChangedListener
            if (ToolPrefs.isAppBlacklisted(this, activePackage)) return@addPrimaryClipChangedListener
            readClipboardAndShowPalette()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (popupView != null) {
            mainHandler.post { renderPopupBody() }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        hideScreenGrabOverlay()
        hidePalette()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        runCatching { unregisterReceiver(grabReceiver) }
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        hideScreenGrabOverlay()
        hidePalette()
        super.onDestroy()
    }

    fun refreshOpenPopupTheme() {
        if (popupView != null) {
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
                currentNode = findSelectedOrFocusedNode()
                lastShownTime = System.currentTimeMillis()
                val now = System.currentTimeMillis()
                val rawCandidate = when {
                    !lastRawCandidateBounds.isEmpty && now - lastRawCandidateTime < 2800L -> Rect(lastRawCandidateBounds)
                    currentNode != null -> Rect().also { currentNode?.getBoundsInScreen(it) }
                    else -> null
                }
                val safeZone = resolveBestScreenSelectionBounds(rawCandidate, forceRefresh = true)
                currentSafeZone = Rect(safeZone)
                showOrUpdatePalette(currentSafeZone)
            }
        }
    }

    fun triggerScreenGrab(delayMs: Long = 120L) {
        hidePalette()
        hideScreenGrabOverlay()
        mainHandler.postDelayed({
            val blocks = harvestAllScreenText()
            if (blocks.isEmpty()) {
                captureScreenshotFallback()
            } else {
                showLensGrabOverlay(blocks)
            }
        }, delayMs)
    }

    private fun captureScreenshotFallback() {
        hideScreenGrabOverlay()
        if (Build.VERSION.SDK_INT >= 30) {
            Toast.makeText(this, "[sel] Capturing screen image…", Toast.LENGTH_SHORT).show()
            runCatching {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            val hwBuffer = screenshotResult.hardwareBuffer
                            val colorSpace = screenshotResult.colorSpace
                            val bmp = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                            hwBuffer.close()
                            if (bmp != null) {
                                val softBmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
                                bmp.recycle()
                                if (softBmp != null) {
                                    showScreenshotPreviewDialog(softBmp)
                                    return
                                }
                            }
                            Toast.makeText(this@SelAccessibilityService, "Screenshot captured", Toast.LENGTH_SHORT).show()
                        }

                        override fun onFailure(errorCode: Int) {
                            Toast.makeText(
                                this@SelAccessibilityService,
                                "[sel] No text nodes or screenshot available (err $errorCode)",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }
        } else {
            Toast.makeText(this, "[sel] No readable text nodes found on screen", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showScreenshotPreviewDialog(bitmap: Bitmap) {
        val dm = resources.displayMetrics
        val dp = { v: Int -> (v * dm.density).toInt() }
        val pal = ToolPrefs.resolvePalette(this)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(pal.bgPrimary))
                setStroke(dp(1), Color.parseColor(pal.accentGreen))
                cornerRadius = dp(4).toFloat()
            }
        }

        card.addView(TextView(this).apply {
            text = "[IMG] SCREENSHOT CAPTURED (${bitmap.width}x${bitmap.height})"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Color.parseColor(pal.accentGreen))
            setPadding(0, 0, 0, dp(8))
        })

        val previewH = (dm.heightPixels * 0.34f).toInt()
        val previewW = (previewH * (bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))).toInt()
        card.addView(ImageView(this).apply {
            setImageBitmap(bitmap)
            this.layoutParams = LinearLayout.LayoutParams(previewW, previewH).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        })

        card.addView(TextView(this).apply {
            text = "No raw Accessibility text nodes on this canvas/image."
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Color.parseColor(pal.fgMuted))
            setPadding(0, dp(8), 0, dp(6))
        })

        val retryLensBtn = TextView(this).apply {
            text = "[ [+] RE-SCAN ACCESSIBILITY NODES ]"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor(pal.accentGreen))
            setPadding(0, dp(6), 0, dp(4))
            setOnClickListener {
                hideScreenGrabOverlay()
                val blocks = harvestAllScreenText()
                if (blocks.isNotEmpty()) {
                    showLensGrabOverlay(blocks)
                } else {
                    Toast.makeText(this@SelAccessibilityService, "Still 0 text nodes on screen", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val closeBtn = TextView(this).apply {
            text = "[ X CLOSE ]"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor(pal.dangerRed))
            setPadding(0, dp(6), 0, 0)
            setOnClickListener {
                hideScreenGrabOverlay()
            }
        }

        card.addView(retryLensBtn)
        card.addView(closeBtn)

        screenGrabOverlayView = card
        runCatching { windowManager.addView(screenGrabOverlayView, params) }
    }

    private fun harvestAllScreenText(): List<LensBlock> {
        val collected = mutableListOf<LensBlock>()
        val seen = HashSet<String>()
        val dm = resources.displayMetrics

        val visitRoot = { root: AccessibilityNodeInfo? ->
            collectLeafTextBlocksRecursive(root, 0, dm.widthPixels, dm.heightPixels, collected, seen)
        }

        runCatching {
            val winList = windows
            if (!winList.isNullOrEmpty()) {
                for (w in winList) {
                    val pkg = w.root?.packageName?.toString() ?: ""
                    if (pkg == "com.android.systemui") continue
                    visitRoot(w.root)
                }
            }
        }
        if (collected.isEmpty()) {
            visitRoot(rootInActiveWindow)
        }

        return collected.sortedWith(compareBy<LensBlock> { it.bounds.top }.thenBy { it.bounds.left })
    }

    private fun collectLeafTextBlocksRecursive(
        node: AccessibilityNodeInfo?,
        depth: Int,
        screenW: Int,
        screenH: Int,
        out: MutableList<LensBlock>,
        seen: HashSet<String>
    ): Boolean {
        if (node == null || depth > 24) return false

        var childHadText = false
        val count = node.childCount
        for (i in 0 until count) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            if (collectLeafTextBlocksRecursive(child, depth + 1, screenW, screenH, out, seen)) {
                childHadText = true
            }
        }

        if (node.isVisibleToUser && !node.isPassword) {
            val rawText = node.text?.toString()?.trim()
            val descText = node.contentDescription?.toString()?.trim()
            val candidate = when {
                !rawText.isNullOrBlank() -> rawText
                !childHadText && !descText.isNullOrBlank() && descText.length > 1 -> descText
                else -> null
            }

            if (!candidate.isNullOrBlank()) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 14 && rect.height() > 12 &&
                    rect.bottom > 0 && rect.top < screenH &&
                    rect.right > 0 && rect.left < screenW
                ) {
                    val key = "${candidate}_${rect.top / 20}_${rect.left / 20}"
                    if (seen.add(key)) {
                        out.add(LensBlock(candidate, rect))
                        return true
                    }
                }
            }
        }

        return childHadText
    }

    private fun showLensGrabOverlay(blocks: List<LensBlock>) {
        hideScreenGrabOverlay()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        val lensView = LensOverlayView(
            context = this,
            blocks = blocks,
            onCopyText = { chosen ->
                smoothCopyText(chosen)
                hideScreenGrabOverlay()
            },
            onSmartAction = { actionId, chosen ->
                hideScreenGrabOverlay()
                selectedText = chosen
                ToolPrefs.addClipToHistory(this, chosen)
                when (actionId) {
                    "magnet" -> {
                        currentMode = PopupMode.MAGNET
                        val dm = resources.displayMetrics
                        val z = Rect(0, dm.heightPixels / 3, dm.widthPixels, dm.heightPixels / 2)
                        currentSafeZone = Rect(z)
                        showOrUpdatePalette(z)
                    }
                    "clean_url" -> {
                        smoothCopyText(cleanUrl(chosen), "[^C] Cleaned URL copied")
                    }
                    "open_url" -> {
                        openUrl(chosen)
                    }
                    "dial" -> {
                        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(chosen)}")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { startActivity(dialIntent) }
                    }
                    "define" -> {
                        currentMode = PopupMode.DEFINE_WORD
                        val dm = resources.displayMetrics
                        val z = Rect(0, dm.heightPixels / 3, dm.widthPixels, dm.heightPixels / 2)
                        currentSafeZone = Rect(z)
                        showOrUpdatePalette(z)
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
            onCaptureScreenshot = {
                captureScreenshotFallback()
            },
            onDismiss = {
                hideScreenGrabOverlay()
            }
        )

        screenGrabOverlayView = lensView
        runCatching { windowManager.addView(screenGrabOverlayView, params) }
    }

    private fun hideScreenGrabOverlay() {
        screenGrabOverlayView?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        screenGrabOverlayView = null
    }

    private fun smoothCopyText(text: String, toastLabel: String? = null) {
        lastClipboardTime = System.currentTimeMillis()
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

    private fun readClipboardAndShowPalette() {
        if (isPrimaryClipSensitive()) return

        val directClip = runCatching {
            clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull()

        if (!directClip.isNullOrBlank()) {
            showFromExternalText(directClip)
            return
        }

        val probe = View(this)
        val probeParams = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        var cleanedUp = false
        val cleanup = {
            if (!cleanedUp) {
                cleanedUp = true
                runCatching { windowManager.removeView(probe) }
            }
        }

        val tryRead = {
            if (!cleanedUp) {
                if (!isPrimaryClipSensitive()) {
                    val clip = runCatching {
                        clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
                    }.getOrNull()
                    if (!clip.isNullOrBlank()) {
                        cleanup()
                        showFromExternalText(clip)
                    }
                } else {
                    cleanup()
                }
            }
        }

        probe.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) {
                tryRead()
                cleanup()
            }
        }

        runCatching {
            windowManager.addView(probe, probeParams)
            mainHandler.postDelayed({ tryRead() }, 30)
            mainHandler.postDelayed({ cleanup() }, 150)
        }
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

        if (pkg.isNotEmpty() && ToolPrefs.isAppBlacklisted(this, pkg)) {
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                if (popupView != null && pkg != packageName) {
                    val elapsed = System.currentTimeMillis() - lastShownTime
                    if (elapsed > 220L) {
                        hidePalette()
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val result = extractSelection(event)
                if (result != null) {
                    pendingWebCopyRunnable?.let { mainHandler.removeCallbacks(it) }
                    mainHandler.removeCallbacks(delayedHideRunnable)
                    val changedText = result.text != selectedText
                    selectedText = result.text
                    currentNode = result.node
                    activePackage = pkg
                    lastShownTime = System.currentTimeMillis()
                    lastRawCandidateBounds = Rect(result.bounds)
                    lastRawCandidateTime = lastShownTime
                    if (changedText && currentMode != PopupMode.ACTIONS && currentMode != PopupMode.REORDER) {
                        currentMode = PopupMode.ACTIONS
                    }
                    val safeZone = resolveBestScreenSelectionBounds(result.bounds, forceRefresh = true)
                    currentSafeZone = Rect(safeZone)
                    showOrUpdatePalette(currentSafeZone)
                } else if (event.fromIndex >= 0 && event.toIndex >= 0 && event.fromIndex != event.toIndex) {
                    event.source?.let { src ->
                        val r = Rect()
                        src.getBoundsInScreen(r)
                        if (r.width() > 0 && r.height() in 1..(getRealScreenHeight() * 0.38f).toInt()) {
                            lastRawCandidateBounds = Rect(r)
                            lastRawCandidateTime = System.currentTimeMillis()
                        }
                    }
                    scheduleBrowserWebViewExtract(event.source, pkg)
                } else {
                    val src = event.source
                    val elapsed = System.currentTimeMillis() - lastShownTime
                    if (popupView != null && currentMode == PopupMode.ACTIONS && elapsed > 650 && src != null && src == currentNode) {
                        val s = src.textSelectionStart
                        val e = src.textSelectionEnd
                        if (s >= 0 && s == e) {
                            mainHandler.postDelayed(delayedHideRunnable, 180)
                        }
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (popupView == null && isBrowserOrWebApp(pkg)) {
                    val now = System.currentTimeMillis()
                    if (now - lastWebAutoCopyTime > 900 && hasActiveSelectionToolbarFast()) {
                        scheduleBrowserWebViewExtract(event.source, pkg)
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (popupView == null) {
                    val cls = event.className?.toString() ?: ""
                    if (isBrowserOrWebApp(pkg) && (cls.contains("Popup", ignoreCase = true) || cls.contains("Floating", ignoreCase = true))) {
                        scheduleBrowserWebViewExtract(event.source, pkg)
                    }
                    return
                }
                if (pkg == packageName || pkg == "android" || pkg == "com.android.systemui") return
                val cls = event.className?.toString() ?: ""
                if (cls.contains("Popup", ignoreCase = true) ||
                    cls.contains("Floating", ignoreCase = true) ||
                    cls.contains("ActionMode", ignoreCase = true) ||
                    cls.contains("Menu", ignoreCase = true) ||
                    cls.contains("Toast", ignoreCase = true) ||
                    cls.contains("Dialog", ignoreCase = true)
                ) {
                    return
                }
                val elapsed = System.currentTimeMillis() - lastShownTime
                if (activePackage.isNotEmpty() && pkg.isNotEmpty() && pkg != activePackage && elapsed > 900) {
                    hidePalette()
                }
            }
        }
    }

    private fun isBrowserOrWebApp(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("chrome") ||
            p.contains("browser") ||
            p.contains("firefox") ||
            p.contains("mozilla") ||
            p.contains("brave") ||
            p.contains("edge") ||
            p.contains("emmx") ||
            p.contains("opera") ||
            p.contains("vivaldi") ||
            p.contains("duckduckgo") ||
            p.contains("kiwi") ||
            p.contains("twitter") ||
            p.contains("reddit")
    }

    private fun hasActiveSelectionToolbarFast(): Boolean {
        val root = rootInActiveWindow ?: return false
        val copyNodes = runCatching { root.findAccessibilityNodeInfosByText("Copy") }.getOrNull()
        return !copyNodes.isNullOrEmpty() && copyNodes.any { it.isVisibleToUser && (it.isClickable || it.parent?.isClickable == true) }
    }

    private fun scheduleBrowserWebViewExtract(sourceNode: AccessibilityNodeInfo?, pkg: String) {
        pendingWebCopyRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            val now = System.currentTimeMillis()
            if (now - lastWebAutoCopyTime < 650) return@Runnable
            activePackage = pkg

            refreshScreenMetricsCache(force = true)

            val treeResult = findSelectionInTreeBounded(sourceNode ?: rootInActiveWindow)
            if (treeResult != null) {
                selectedText = treeResult.text
                currentNode = treeResult.node
                lastShownTime = System.currentTimeMillis()
                lastRawCandidateBounds = Rect(treeResult.bounds)
                lastRawCandidateTime = lastShownTime
                val safeZone = resolveBestScreenSelectionBounds(treeResult.bounds, forceRefresh = false)
                currentSafeZone = Rect(safeZone)
                showOrUpdatePalette(currentSafeZone)
                return@Runnable
            }

            lastWebAutoCopyTime = now
            var copied = false
            if (sourceNode != null) {
                val r = Rect()
                sourceNode.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() in 1..(getRealScreenHeight() * 0.38f).toInt()) {
                    lastRawCandidateBounds = Rect(r)
                    lastRawCandidateTime = now
                }
                copied = runCatching { sourceNode.performAction(AccessibilityNodeInfo.ACTION_COPY) }.getOrDefault(false)
            }
            if (!copied) {
                val focused = runCatching { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
                if (focused != null) {
                    copied = runCatching { focused.performAction(AccessibilityNodeInfo.ACTION_COPY) }.getOrDefault(false)
                }
            }
            if (!copied) {
                copied = performCopyInTreeBounded(rootInActiveWindow, 0)
            }
            if (copied) {
                mainHandler.postDelayed({ readClipboardAndShowPalette() }, 45)
            }
        }
        pendingWebCopyRunnable = runnable
        mainHandler.postDelayed(runnable, 110)
    }

    private fun performCopyInTreeBounded(root: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (root == null || depth > 6) return false
        val cls = root.className?.toString() ?: ""
        if (cls.contains("WebView", ignoreCase = true) || root.isFocused) {
            if (runCatching { root.performAction(AccessibilityNodeInfo.ACTION_COPY) }.getOrDefault(false)) return true
        }
        val count = minOf(root.childCount, 8)
        for (i in 0 until count) {
            val child = runCatching { root.getChild(i) }.getOrNull() ?: continue
            if (performCopyInTreeBounded(child, depth + 1)) return true
        }
        return false
    }

    private fun extractSelection(event: AccessibilityEvent): SelectionResult? {
        val pkg = event.packageName?.toString() ?: ""
        if (pkg.contains("launcher", ignoreCase = true) && event.source?.isFocused != true) {
            return null
        }

        val source = event.source
        if (source != null) {
            if (source.isPassword) return null
            extractFromNode(source, event.fromIndex, event.toIndex)?.let { return it }
        }

        val eventText = event.text.joinToString("")
        if (eventText.isNotBlank() && event.fromIndex >= 0 && event.toIndex >= 0 && event.fromIndex != event.toIndex) {
            val start = minOf(event.fromIndex, event.toIndex)
            val end = maxOf(event.fromIndex, event.toIndex)
            if (end <= eventText.length) {
                val sub = eventText.substring(start, end)
                if (sub.isNotBlank()) {
                    val rect = if (source != null) {
                        resolveExactSelectionBounds(source, eventText, start, end)
                    } else {
                        Rect(0, 400, 1000, 520)
                    }
                    return SelectionResult(sub, source, rect)
                }
            }
        }

        val focused = findSelectedOrFocusedNode()
        if (focused != null && focused != source) {
            extractFromNode(focused)?.let { return it }
        }

        if (source != null) {
            findSelectionInTreeBounded(source)?.let { return it }
        }

        return null
    }

    private fun getRealScreenHeight(): Int {
        return runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                windowManager.currentWindowMetrics.bounds.height()
            } else {
                val outMetrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(outMetrics)
                outMetrics.heightPixels
            }
        }.getOrDefault(resources.displayMetrics.heightPixels)
    }

    private fun isImePackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("inputmethod") ||
            p.contains("keyboard") ||
            p.contains("honeyboard") ||
            p.contains("latin") ||
            p.contains("gboard") ||
            p.contains("swiftkey")
    }

    private fun refreshScreenMetricsCache(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val dm = resources.displayMetrics
        val dp = { v: Int -> (v * dm.density).toInt() }
        val realH = getRealScreenHeight()

        if (!force && now - lastMetricsCacheTime < 400L && cachedVisibleBottom > 0) {
            return
        }
        lastMetricsCacheTime = now

        var visBottom = realH - dp(68)
        val candidateButtons = mutableListOf<Rect>()

        val scanForCopyButtons = { root: AccessibilityNodeInfo?, maxBottom: Int ->
            val pkg = root?.packageName?.toString() ?: ""
            if (root != null && pkg != packageName && !isImePackage(pkg) && pkg != "com.android.systemui") {
                for (kw in listOf("Copy", "Cut")) {
                    val matches = runCatching { root.findAccessibilityNodeInfosByText(kw) }.getOrNull()
                    if (!matches.isNullOrEmpty()) {
                        for (m in matches) {
                            if (!m.isVisibleToUser) continue
                            val t1 = m.text?.toString()?.trim().orEmpty()
                            val t2 = m.contentDescription?.toString()?.trim().orEmpty()
                            val txt = t1.ifEmpty { t2 }
                            if (!txt.equals(kw, ignoreCase = true)) continue
                            if (!m.isClickable && m.parent?.isClickable != true) continue

                            val r = Rect()
                            m.getBoundsInScreen(r)
                            if (r.width() in dp(20)..dp(220) &&
                                r.height() in dp(16)..dp(80) &&
                                r.top >= dp(28) &&
                                r.bottom <= maxBottom - dp(12) &&
                                r.top <= realH - dp(96)
                            ) {
                                candidateButtons.add(Rect(r))
                            }
                        }
                        if (candidateButtons.isNotEmpty()) break
                    }
                }
            }
        }

        runCatching {
            val winList = windows
            if (!winList.isNullOrEmpty()) {
                for (w in winList) {
                    if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        val imeRect = Rect()
                        w.getBoundsInScreen(imeRect)
                        if (imeRect.height() > dp(80) && imeRect.top in (realH / 4)..(realH - dp(70))) {
                            visBottom = minOf(visBottom, imeRect.top - dp(10))
                        }
                    }
                }
                for (w in winList) {
                    if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                        w.type == AccessibilityWindowInfo.TYPE_SYSTEM
                    ) {
                        continue
                    }
                    val wRect = Rect()
                    w.getBoundsInScreen(wRect)
                    if (w.isActive || wRect.height() in dp(24)..dp(140)) {
                        scanForCopyButtons(w.root, visBottom)
                    }
                }
            }
        }

        cachedVisibleBottom = visBottom.coerceAtLeast(realH / 3)

        if (candidateButtons.isEmpty()) {
            scanForCopyButtons(rootInActiveWindow, cachedVisibleBottom)
        }

        if (candidateButtons.isNotEmpty()) {
            val anchor = candidateButtons.first()
            val rowUnion = Rect(anchor)
            for (other in candidateButtons) {
                if (kotlin.math.abs(other.centerY() - anchor.centerY()) <= dp(24)) {
                    rowUnion.union(other)
                }
            }
            if (rowUnion.height() <= dp(88)) {
                lastDetectedToolbarRect = rowUnion
                lastDetectedToolbarTime = now
            }
        }
    }

    private fun resolveBestScreenSelectionBounds(rawCandidate: Rect?, forceRefresh: Boolean = false): Rect {
        val dm = resources.displayMetrics
        val dp = { v: Int -> (v * dm.density).toInt() }
        val realH = getRealScreenHeight()

        refreshScreenMetricsCache(force = forceRefresh)
        val visibleBottom = cachedVisibleBottom.takeIf { it > 0 } ?: (realH - dp(68))

        val now = System.currentTimeMillis()
        val toolbarRect = lastDetectedToolbarRect.takeIf { !it.isEmpty && now - lastDetectedToolbarTime < 1600L }

        val isCompactCandidate = rawCandidate != null &&
            rawCandidate.width() > 0 &&
            rawCandidate.height() in 1..dp(105) &&
            rawCandidate.top < visibleBottom

        val isMediumCandidate = rawCandidate != null &&
            rawCandidate.width() > 0 &&
            rawCandidate.height() in 1..(realH * 0.36f).toInt() &&
            rawCandidate.top < visibleBottom

        if (isCompactCandidate && rawCandidate != null) {
            var zoneTop = rawCandidate.top - dp(10)
            var zoneBottom = rawCandidate.bottom + dp(18)
            if (toolbarRect != null && kotlin.math.abs(toolbarRect.centerY() - rawCandidate.centerY()) < dp(150)) {
                zoneTop = minOf(zoneTop, toolbarRect.top - dp(10))
                zoneBottom = maxOf(zoneBottom, toolbarRect.bottom + dp(14))
            }
            val clampedTop = zoneTop.safeCoerceIn(dp(48), (visibleBottom - dp(64)).coerceAtLeast(dp(48)))
            val clampedBottom = zoneBottom.safeCoerceIn(
                clampedTop + dp(28),
                (visibleBottom - dp(8)).coerceAtLeast(clampedTop + dp(28))
            )
            val left = rawCandidate.left.coerceAtLeast(dp(12))
            val right = rawCandidate.right.coerceAtMost(dm.widthPixels - dp(12)).coerceAtLeast(left + dp(24))
            return Rect(left, clampedTop, right, clampedBottom)
        }

        if (toolbarRect != null) {
            val clampedTop = (toolbarRect.top - dp(14)).safeCoerceIn(dp(48), (visibleBottom - dp(80)).coerceAtLeast(dp(48)))
            val clampedBottom = (toolbarRect.bottom + dp(66)).safeCoerceIn(
                clampedTop + dp(36),
                (visibleBottom - dp(8)).coerceAtLeast(clampedTop + dp(36))
            )
            val left = toolbarRect.left.coerceAtLeast(dp(12))
            val right = toolbarRect.right.coerceAtMost(dm.widthPixels - dp(12)).coerceAtLeast(left + dp(24))
            return Rect(left, clampedTop, right, clampedBottom)
        }

        if (isMediumCandidate && rawCandidate != null) {
            val clampedTop = (rawCandidate.top - dp(10)).safeCoerceIn(dp(48), (visibleBottom - dp(80)).coerceAtLeast(dp(48)))
            val clampedBottom = (rawCandidate.bottom + dp(20)).safeCoerceIn(
                clampedTop + dp(32),
                (visibleBottom - dp(8)).coerceAtLeast(clampedTop + dp(32))
            )
            val left = rawCandidate.left.coerceAtLeast(dp(12))
            val right = rawCandidate.right.coerceAtMost(dm.widthPixels - dp(12)).coerceAtLeast(left + dp(24))
            return Rect(left, clampedTop, right, clampedBottom)
        }

        val fallbackMidY = (visibleBottom * 0.36f).toInt().safeCoerceIn(dp(96), (visibleBottom - dp(96)).coerceAtLeast(dp(96)))
        return Rect(dp(16), fallbackMidY - dp(28), dm.widthPixels - dp(16), fallbackMidY + dp(36))
    }

    private fun resolveExactSelectionBounds(
        node: AccessibilityNodeInfo,
        raw: String,
        start: Int,
        end: Int
    ): Rect {
        val dm = resources.displayMetrics
        val dp = { v: Int -> (v * dm.density).toInt() }
        val realH = getRealScreenHeight()
        val nodeRect = Rect()
        node.getBoundsInScreen(nodeRect)
        if (nodeRect.width() <= 0 || nodeRect.height() <= 0) {
            nodeRect.set(dp(24), realH / 4, dm.widthPixels - dp(24), realH / 4 + dp(44))
        }

        if (nodeRect.height() in dp(84)..(realH * 0.45f).toInt() && raw.isNotEmpty()) {
            val avgCharsPerLine = ((nodeRect.width() / dp(7).coerceAtLeast(1))).safeCoerceIn(22, 72)
            var currentLine = 0
            var startLine = 0
            var endLine = 0
            var col = 0
            val limit = minOf(raw.length, 4000)
            for (i in 0 until limit) {
                if (i == start) startLine = currentLine
                if (i == end) endLine = currentLine
                val ch = raw[i]
                if (ch == '\n') {
                    currentLine++
                    col = 0
                } else {
                    col++
                    if (col >= avgCharsPerLine) {
                        currentLine++
                        col = 0
                    }
                }
            }
            if (end >= limit) endLine = currentLine
            val totalLines = (currentLine + 1).coerceAtLeast(1)
            val estLineH = (nodeRect.height().toFloat() / totalLines.toFloat()).safeCoerceIn(dp(16).toFloat(), dp(25).toFloat())
            val bandTop = (nodeRect.top + startLine * estLineH).toInt().safeCoerceIn(nodeRect.top, nodeRect.bottom - dp(28))
            val bandBottom = (nodeRect.top + (endLine + 1) * estLineH).toInt()
                .safeCoerceIn(bandTop + dp(24), nodeRect.bottom)
            return Rect(nodeRect.left, bandTop, nodeRect.right, bandBottom)
        }

        return Rect(nodeRect.left, nodeRect.top, nodeRect.right, nodeRect.bottom)
    }

    private fun extractFromNode(
        node: AccessibilityNodeInfo,
        evFrom: Int = -1,
        evTo: Int = -1
    ): SelectionResult? {
        if (node.isPassword) return null
        val raw = node.text?.toString() ?: ""
        val hint = node.hintText?.toString() ?: ""
        if (raw.isEmpty() || (hint.isNotEmpty() && raw == hint)) return null

        val ns = node.textSelectionStart
        val ne = node.textSelectionEnd
        if (ns >= 0 && ne >= 0 && ns != ne) {
            val start = minOf(ns, ne)
            val end = maxOf(ns, ne)
            if (end <= raw.length) {
                val sub = raw.substring(start, end)
                if (sub.isNotBlank()) {
                    val rawBounds = resolveExactSelectionBounds(node, raw, start, end)
                    return SelectionResult(sub, node, rawBounds)
                }
            }
        }

        if (evFrom >= 0 && evTo >= 0 && evFrom != evTo) {
            val start = minOf(evFrom, evTo)
            val end = maxOf(evFrom, evTo)
            if (end <= raw.length) {
                val sub = raw.substring(start, end)
                if (sub.isNotBlank()) {
                    val rawBounds = resolveExactSelectionBounds(node, raw, start, end)
                    return SelectionResult(sub, node, rawBounds)
                }
            }
        }

        return null
    }

    private fun findSelectionInTreeBounded(root: AccessibilityNodeInfo?): SelectionResult? {
        if (root == null) return null
        var budget = 24

        fun search(node: AccessibilityNodeInfo?, depth: Int): SelectionResult? {
            if (node == null || depth > 7 || budget <= 0) return null
            budget--
            extractFromNode(node)?.let { return it }
            val count = minOf(node.childCount, 8)
            for (i in 0 until count) {
                if (budget <= 0) break
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                val res = search(child, depth + 1)
                if (res != null) return res
            }
            return null
        }

        return search(root, 0)
    }

    private fun findSelectedOrFocusedNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val inputFocused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (inputFocused != null && inputFocused.isEditable) return inputFocused
        val a11yFocused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) }.getOrNull()
        if (a11yFocused != null && a11yFocused.isEditable) return a11yFocused
        return null
    }

    private var currentMaxTotalHeight: Int = 700

    private fun applyStrictPlacementAndDynamicSize(safeZone: Rect, params: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        val dp = { v: Int -> (v * dm.density).toInt() }
        val realH = getRealScreenHeight()
        val visibleBottom = cachedVisibleBottom.takeIf { it > 0 } ?: (realH - dp(68))

        currentSafeZone = Rect(safeZone)

        val topSafe = dp(50)
        val gap = dp(14)

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
        currentMaxTotalHeight = chosenSpace.safeCoerceIn(dp(100), (realH * 0.40f).toInt())
        val headerHeightEst = dp(32)
        dynamicMaxScrollHeight = (currentMaxTotalHeight - headerHeightEst).safeCoerceIn(dp(54), dp(290))

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
        }.coerceAtMost(dm.widthPixels - dp(20))

        if (!userManuallyDragged) {
            val snapLeft = ToolPrefs.isSnapLeftEdge(this)
            params.gravity = Gravity.TOP or Gravity.START
            params.x = if (snapLeft) {
                dp(10)
            } else {
                (dm.widthPixels - dynamicMaxPopupWidth - dp(10)).coerceAtLeast(dp(10))
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
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        popupView?.background = GradientDrawable().apply {
            setColor(Color.parseColor(pal.bgPrimary))
            setStroke(dp(1), Color.parseColor(pal.border))
            cornerRadius = 0f
        }
        headerBarView?.setBackgroundColor(Color.parseColor(pal.bgHeader))
        headerDividerView?.setBackgroundColor(Color.parseColor(pal.border))
        statsLabelView?.setTextColor(Color.parseColor(pal.fgMuted))

        val twoCol = ToolPrefs.isTwoColumnGrid(this)
        gridToggleBtn?.text = if (twoCol) "[1C]" else "[2C]"
        gridToggleBtn?.setTextColor(Color.parseColor(if (twoCol) pal.accentGreen else pal.fgPrimary))

        collapseBtnView?.setTextColor(Color.parseColor(pal.fgPrimary))
        reorderToggleBtn?.setTextColor(
            Color.parseColor(if (currentMode == PopupMode.REORDER) pal.accentGreen else pal.fgPrimary)
        )
        closeBtnView?.setTextColor(Color.parseColor(pal.dangerRed))
    }

    // Dynamic autosizing vertical palette in Dracula (Dark) or Alucard (Light)
    private fun showOrUpdatePalette(safeZone: Rect) {
        val dm = resources.displayMetrics
        val dp = { v: Int -> (v * dm.density).toInt() }
        val pal = ToolPrefs.resolvePalette(this)

        val words = selectedText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        val chars = selectedText.length

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
                setColor(Color.parseColor(pal.bgPrimary))
                setStroke(dp(1), Color.parseColor(pal.border))
                cornerRadius = 0f
            }
            addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
                val actualH = bottom - top
                val p = popupParams
                if (actualH > 0 && p != null && !userManuallyDragged && !currentSafeZone.isEmpty) {
                    val topSafe = dp(50)
                    val gap = dp(14)
                    val visBottom = cachedVisibleBottom.takeIf { it > 0 } ?: (getRealScreenHeight() - dp(68))
                    val desiredY = if (anchoredAboveSelection) {
                        (currentSafeZone.top - gap - actualH).coerceAtLeast(topSafe)
                    } else {
                        val maxTop = (visBottom - actualH).coerceAtLeast(topSafe)
                        (currentSafeZone.bottom + gap).safeCoerceIn(topSafe, maxTop)
                    }
                    if (kotlin.math.abs(p.y - desiredY) > dp(3)) {
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
                    if (System.currentTimeMillis() - lastShownTime > 150L) {
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
            setBackgroundColor(Color.parseColor(pal.bgHeader))
            setPadding(dp(6), dp(3), dp(4), dp(3))
        }

        val posGlyph = if (anchoredAboveSelection) "^" else "v"
        val statsLabel = TextView(this).apply {
            text = "${words}W:${chars}C $posGlyph"
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(Color.parseColor(pal.fgMuted))
            includeFontPadding = false
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, dp(2), 0)
            this.layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        fun makePlainHeaderBtn(
            textStr: String,
            fgHex: String,
            onClick: () -> Unit
        ): TextView {
            return TextView(this).apply {
                text = textStr
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(Color.parseColor(fgHex))
                includeFontPadding = false
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(2), dp(3), dp(2))
                background = StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_pressed),
                        GradientDrawable().apply {
                            setColor(Color.parseColor(pal.bgPressed))
                        }
                    )
                    addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
                }
                setOnClickListener {
                    runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    onClick()
                }
            }
        }

        val twoColInitial = ToolPrefs.isTwoColumnGrid(this)
        val gridBtn = makePlainHeaderBtn(
            textStr = if (twoColInitial) "[1C]" else "[2C]",
            fgHex = if (twoColInitial) pal.accentGreen else pal.fgPrimary
        ) {
            lastShownTime = System.currentTimeMillis()
            val next = !ToolPrefs.isTwoColumnGrid(this@SelAccessibilityService)
            ToolPrefs.setTwoColumnGrid(this@SelAccessibilityService, next)
            renderPopupBody()
        }

        val collapseBtn = makePlainHeaderBtn("[_]", pal.fgPrimary) {
            lastShownTime = System.currentTimeMillis()
            currentMode = PopupMode.COLLAPSED_PILL
            renderPopupBody()
        }

        val reorderBtn = makePlainHeaderBtn("[*]", pal.fgPrimary) {
            lastShownTime = System.currentTimeMillis()
            currentMode = if (currentMode == PopupMode.REORDER) PopupMode.ACTIONS else PopupMode.REORDER
            renderPopupBody()
        }

        val closeBtn = makePlainHeaderBtn("[X]", pal.dangerRed) {
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
        runCatching { windowManager.addView(popupView, windowParams) }
    }

    private fun renderPopupBody() {
        val container = bodyContainer ?: return
        val pal = ToolPrefs.resolvePalette(this)
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
            val words = selectedText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
            val chars = selectedText.length
            val posGlyph = if (anchoredAboveSelection) "^" else "v"
            statsLabelView?.text = "${words}W:${chars}C $posGlyph"
        }

        popupView?.requestLayout()
        val view = popupView
        if (view != null && params != null && view.isAttachedToWindow) {
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun populateCollapsedPill(container: LinearLayout) {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val pal = ToolPrefs.resolvePalette(this)
        val pill = TextView(this).apply {
            text = "+-- [sel_ >] --+"
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(Color.parseColor(pal.accentGreen))
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

        val isHash = extractTorrentHash(trimmed) != null || trimmed.startsWith("magnet:?", ignoreCase = true)
        val isSingleTokenUrl = !trimmed.contains(" ") && !trimmed.contains("\n") &&
            (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                urlPattern.matches(trimmed))
        val hasAnyUrl = trimmed.contains("http://", ignoreCase = true) ||
            trimmed.contains("https://", ignoreCase = true) ||
            isSingleTokenUrl
        val wordCount = trimmed.split(Regex("\\s+")).count { it.isNotEmpty() }

        return when (toolId) {
            "magnet" -> isHash
            "clean_url" -> hasAnyUrl && (trimmed.contains("?") || trimmed.contains("&") || trackingRegex.containsMatchIn(trimmed))
            "define" -> wordCount in 1..4 && !hasAnyUrl && !isHash && trimmed.any { it.isLetter() } && trimmed.length <= 48
            "sort" -> trimmed.contains("\n") && trimmed.lines().count { it.isNotBlank() } >= 2
            "clean_text" -> rawText.contains("\n") || rawText.contains("  ") || rawText.contains("\t") ||
                Regex("[\\u200B-\\u200D\\uFEFF]").containsMatchIn(rawText)
            "extract" -> hasExtractableEntities(trimmed) && !isSingleTokenUrl
            "case" -> trimmed.any { it.isLetter() } && !isSingleTokenUrl && !isHash
            "clip_history" -> ToolPrefs.getClipHistory(this).isNotEmpty()
            else -> true
        }
    }

    private fun hasExtractableEntities(text: String): Boolean {
        if (Regex("https?://[^\\s\"<>]+").containsMatchIn(text)) return true
        if (Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}").containsMatchIn(text)) return true
        if (Regex("(?:\\+?\\d[\\d\\- ()]{6,}\\d)").containsMatchIn(text)) return true
        if (Regex("[#@][a-zA-Z0-9_]+").containsMatchIn(text)) return true
        return false
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
            list.addView(makeRow("<-", "< UNDO LAST EDIT", null) {
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

        // 0b. Context-Aware Phone Bar ([CALL] [SMS] [WA] [W4B]) for numbers starting with 98, 97, or +977
        extractNepalPhone(selectedText)?.let { phone ->
            list.addView(makeNepalPhoneActionRow(phone))
            list.addView(makeHorizontalRule())
        }

        // 0c. Computer-Shortcut Inspired Top Edit Row (Icon-Only, 4 items): [^C] [^V] [^X] [^A]
        var hasTopStrips = false
        if (!ToolPrefs.isToolHidden(this, "copy")) {
            pinnedIds.add("copy")
            list.addView(makeIconOnlyEditStrip())
            hasTopStrips = true
        }

        // 0d. Up to 2 User-Assignable Top Icon-Only Rows (Row 1 & Row 2, max 4 items each)
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

        // 1. Instant Math Evaluator
        evalSimpleMath(selectedText)?.let { ans ->
            list.addView(makeRow("==", "= $ans", null) {
                ToolPrefs.recordUse(this, "math")
                applyResult(ans)
            })
        }

        // 2. Instant Unit & Hex/Dec Converter
        evalUnitConversion(selectedText)?.let { conv ->
            list.addView(makeRow("<>", conv, null) {
                ToolPrefs.recordUse(this, "unit_conv")
                applyResult(conv.removePrefix("= ").trim())
            })
        }

        // 2b. Context-Aware Unix Timestamp Decoder
        evalTimestamp(selectedText)?.let { formattedDate ->
            list.addView(makeRow("TS", formattedDate, null) {
                ToolPrefs.recordUse(this, "epoch_conv")
                applyResult(formattedDate)
            })
        }

        // 2c. Context-Aware Base64 Decoder
        evalBase64Decode(selectedText)?.let { decoded ->
            val preview = decoded.replace("\n", " ").let { if (it.length > 20) it.take(19) + "…" else it }
            list.addView(makeRow("B64", "B64 -> $preview", null) {
                ToolPrefs.recordUse(this, "b64_decode")
                applyResult(decoded)
            })
        }

        // 3. Pin HASH -> MAGNET at the top ONLY when the selected text is a torrent hash
        if (isToolRelevantForSelection("magnet", selectedText) && !ToolPrefs.isToolHidden(this, "magnet")) {
            pinnedIds.add("magnet")
            list.addView(makeRow("0x", "HASH -> MAGNET", null) {
                ToolPrefs.recordUse(this, "magnet")
                lastShownTime = System.currentTimeMillis()
                currentMode = PopupMode.MAGNET
                renderPopupBody()
            })
        }

        // 4. Pin CLEAN URL at the top ONLY when the selected URL has query/tracking params
        if (isToolRelevantForSelection("clean_url", selectedText) && !ToolPrefs.isToolHidden(this, "clean_url")) {
            pinnedIds.add("clean_url")
            list.addView(makeRow("?x", "CLEAN URL", null) {
                ToolPrefs.recordUse(this, "clean_url")
                applyResult(cleanUrl(selectedText))
            })
        }

        // 5. Context-filtered, Reorderable & Most-Used-on-Top Plain-Text Tools (Text-Only below top rows!)
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
                    list.addView(makeTwoColumnPairRow(t1, t2, showCounts))
                    i += 2
                } else {
                    val c1 = ToolPrefs.getUseCount(this, t1.id)
                    val b1 = if (showCounts && c1 > 0) "$c1" else null
                    list.addView(makeRow(t1.glyph, t1.label, b1, null, onLongClick = { executeToolLongPress(t1) }) {
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
                list.addView(makeRow(tool.glyph, tool.label, badge, null, onLongClick = { executeToolLongPress(tool) }) {
                    ToolPrefs.recordUse(this, tool.id)
                    executeTool(tool)
                })
            }
        }
    }

    /**
     * Computer-shortcut inspired top edit strip (4 items max, icon-only):
     * `[^C]` (Copy), `[^V]` (Paste), `[^X]` (Cut), `[^A]` (Select All)
     */
    private fun makeIconOnlyEditStrip(): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val pal = ToolPrefs.resolvePalette(this)
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun makeBracketCell(
            token: String,
            fgHex: String,
            onLongPress: () -> Unit,
            onTap: () -> Unit
        ): TextView {
            return TextView(this).apply {
                text = token
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(Color.parseColor(fgHex))
                includeFontPadding = false
                gravity = Gravity.CENTER
                isSingleLine = true
                setPadding(dp(1), dp(4), dp(1), dp(4))
                this.layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                background = StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_pressed),
                        GradientDrawable().apply {
                            setColor(Color.parseColor(pal.bgPressed))
                        }
                    )
                    addState(
                        intArrayOf(),
                        GradientDrawable().apply {
                            setColor(Color.TRANSPARENT)
                        }
                    )
                }
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

        // 1. [^C] — Tap: Copy | Long-press: Append to clipboard (+^C)
        strip.addView(
            makeBracketCell(
                token = "[^C]",
                fgHex = pal.accentGreen,
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

        // 2. [^V] — Tap: Paste | Long-press: Open Clipboard History [≡]
        strip.addView(
            makeBracketCell(
                token = "[^V]",
                fgHex = pal.fgPrimary,
                onLongPress = {
                    lastShownTime = System.currentTimeMillis()
                    currentMode = PopupMode.CLIP_HISTORY
                    renderPopupBody()
                },
                onTap = {
                    performPasteAction()
                }
            )
        )

        // 3. [^X] — Tap: Cut selection
        strip.addView(
            makeBracketCell(
                token = "[^X]",
                fgHex = pal.fgPrimary,
                onLongPress = {
                    Toast.makeText(this, "[^X] Cut selection", Toast.LENGTH_SHORT).show()
                },
                onTap = {
                    performCutAction()
                }
            )
        )

        // 4. [^A] — Tap: Select All
        strip.addView(
            makeBracketCell(
                token = "[^A]",
                fgHex = pal.fgPrimary,
                onLongPress = {
                    Toast.makeText(this, "[^A] Select All", Toast.LENGTH_SHORT).show()
                },
                onTap = {
                    performSelectAllAction()
                }
            )
        )

        return strip
    }

    /**
     * Renders an assignable top icon-only tool row (Row 1 or Row 2, strictly capped at 4 items per row)
     * using the approved bracketed icons (`[/]`, `[|>]`, `[<>]`, `[?=]`, `[≡]`, `[::]`, `[+]`, `[**]`, etc.).
     */
    private fun makeCustomIconToolRow(rowTools: List<ToolSpec>, rowNumber: Int): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val pal = ToolPrefs.resolvePalette(this)
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val fgHex = if (rowNumber == 1) pal.accentPurple else pal.accentCyan

        rowTools.take(ToolPrefs.MAX_TOOLS_PER_ICON_ROW).forEach { tool ->
            val token = formatBracketToken(tool.glyph)
            val cell = TextView(this).apply {
                text = token
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(Color.parseColor(fgHex))
                includeFontPadding = false
                gravity = Gravity.CENTER
                isSingleLine = true
                setPadding(dp(1), dp(4), dp(1), dp(4))
                this.layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                background = StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_pressed),
                        GradientDrawable().apply {
                            setColor(Color.parseColor(pal.bgPressed))
                        }
                    )
                    addState(
                        intArrayOf(),
                        GradientDrawable().apply {
                            setColor(Color.TRANSPARENT)
                        }
                    )
                }
                setOnClickListener {
                    runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    ToolPrefs.recordUse(this@SelAccessibilityService, tool.id)
                    executeTool(tool)
                }
                setOnLongClickListener {
                    runCatching { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
                    Toast.makeText(
                        this@SelAccessibilityService,
                        "$token ${tool.label}",
                        Toast.LENGTH_SHORT
                    ).show()
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

        val targetNode = currentNode?.takeIf { it.isEditable } ?: findSelectedOrFocusedNode()
        if (targetNode != null) {
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
        lastClipboardTime = System.currentTimeMillis()
        clipboardManager.setPrimaryClip(ClipData.newPlainText("sel", selectedText))
        ToolPrefs.addClipToHistory(this, selectedText)

        val targetNode = currentNode?.takeIf { it.isEditable } ?: findSelectedOrFocusedNode()
        var cutOk = false
        if (targetNode != null) {
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
        val targetNode = currentNode ?: findSelectedOrFocusedNode()
        if (targetNode != null) {
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
                ok = runCatching {
                    targetNode.performAction(android.R.id.selectAll)
                }.getOrDefault(false)
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

    private fun extractNepalPhone(input: String): NepalPhone? {
        val regex = Regex(
            "(?:\\+977|00977|977)?[\\s\\-.()]*((?:98|97)[0-9\\s\\-.()]{8,13}|[1-9][0-9\\s\\-.()]{6,10})"
        )
        for (m in regex.findAll(input)) {
            val fullMatch = m.value.trim()
            val hasExplicit977 = fullMatch.startsWith("+977") || fullMatch.startsWith("00977") ||
                (fullMatch.startsWith("977") && fullMatch.filter { it.isDigit() }.length >= 12)
            val rawDigits = fullMatch.filter { it.isDigit() }

            val localDigits = when {
                rawDigits.startsWith("00977") -> rawDigits.removePrefix("00977")
                rawDigits.startsWith("977") && rawDigits.length >= 12 -> rawDigits.removePrefix("977")
                rawDigits.startsWith("098") && rawDigits.length == 11 -> rawDigits.removePrefix("0")
                rawDigits.startsWith("097") && rawDigits.length == 11 -> rawDigits.removePrefix("0")
                else -> rawDigits
            }

            val isValidMobile = (localDigits.startsWith("98") || localDigits.startsWith("97")) && localDigits.length == 10
            val isValidLandlineWith977 = hasExplicit977 && localDigits.length in 7..10

            if (isValidMobile || isValidLandlineWith977) {
                return NepalPhone(
                    display = "+977 $localDigits",
                    dialNumber = "+977$localDigits",
                    waDigits = "977$localDigits"
                )
            }
        }
        return null
    }

    private fun makeNepalPhoneActionRow(phone: NepalPhone): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val pal = ToolPrefs.resolvePalette(this)
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            this.layoutParams = LinearLayout.LayoutParams(
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
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun makePhonePill(
            labelStr: String,
            fgHex: String,
            marginEndDp: Int,
            onClick: () -> Unit
        ): TextView {
            return TextView(this).apply {
                text = labelStr
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(Color.parseColor(fgHex))
                gravity = Gravity.CENTER
                isSingleLine = true
                includeFontPadding = false
                setPadding(dp(2), dp(4), dp(2), dp(4))
                this.layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginEnd = dp(marginEndDp)
                }
                background = StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_pressed),
                        GradientDrawable().apply {
                            setColor(Color.parseColor(pal.bgPressed))
                            cornerRadius = dp(2).toFloat()
                        }
                    )
                    addState(
                        intArrayOf(),
                        GradientDrawable().apply {
                            setColor(Color.TRANSPARENT)
                        }
                    )
                }
                setOnClickListener {
                    runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    onClick()
                }
            }
        }

        strip.addView(
            makePhonePill("[CALL]", pal.accentGreen, 2) {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone.dialNumber)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { startActivity(dialIntent) }
                hidePalette()
            }
        )

        strip.addView(
            makePhonePill("[SMS]", pal.fgPrimary, 2) {
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
            }
        )

        strip.addView(
            makePhonePill("[WA]", pal.accentGreen, 2) {
                openWhatsAppNumber(phone.waDigits, isBusiness = false)
            }
        )

        strip.addView(
            makePhonePill("[W4B]", pal.accentCyan, 0) {
                openWhatsAppNumber(phone.waDigits, isBusiness = true)
            }
        )

        wrapper.addView(strip)
        return wrapper
    }

    private fun openWhatsAppNumber(waDigits: String, isBusiness: Boolean) {
        val targetPkg = if (isBusiness) "com.whatsapp.w4b" else "com.whatsapp"
        val appLabel = if (isBusiness) "WhatsApp Business" else "WhatsApp"

        val nativeUri = Uri.parse("whatsapp://send?phone=$waDigits")
        val nativeIntent = Intent(Intent.ACTION_VIEW, nativeUri).apply {
            setPackage(targetPkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { startActivity(nativeIntent) }.isSuccess) {
            hidePalette()
            return
        }

        val apiUri = Uri.parse("https://api.whatsapp.com/send?phone=$waDigits")
        val webPkgIntent = Intent(Intent.ACTION_VIEW, apiUri).apply {
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

    /**
     * Clean text-only labels for 2-column grid mode below the top rows (icons are shown ONLY on top rows).
     */
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

    private fun makeTwoColumnPairRow(t1: ToolSpec, t2: ToolSpec, showCounts: Boolean): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val pal = ToolPrefs.resolvePalette(this)
        val pairRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun makeCell(tool: ToolSpec): View {
            return TextView(this).apply {
                text = shortGridLabel(tool)
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Color.parseColor(pal.fgPrimary))
                includeFontPadding = false
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                this.layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                background = StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_pressed),
                        GradientDrawable().apply { setColor(Color.parseColor(pal.bgPressed)) }
                    )
                    addState(
                        intArrayOf(),
                        GradientDrawable().apply { setColor(Color.TRANSPARENT) }
                    )
                }
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
            setTextColor(Color.parseColor(pal.divider))
            includeFontPadding = false
            setPadding(dp(2), 0, dp(2), 0)
        }

        pairRow.addView(makeCell(t1))
        pairRow.addView(midSep)
        pairRow.addView(makeCell(t2))
        return pairRow
    }

    // Ring-Buffer Clipboard History Submenu inside the popup
    private fun populateClipHistorySubmenu(container: LinearLayout) {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val pal = ToolPrefs.resolvePalette(this)

        container.addView(makeRow("<-", "< BACK TO TOOLS", null) {
            lastShownTime = System.currentTimeMillis()
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeRow("DEL", "CLEAR UNPINNED", null) {
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
                setTextColor(Color.parseColor(pal.fgMuted))
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
                setTextColor(Color.parseColor(if (entry.pinned) pal.accentGreen else pal.fgMuted))
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
                setTextColor(Color.parseColor(pal.fgPrimary))
                isSingleLine = true
                this.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setOnClickListener {
                    applyResult(entry.text)
                }
            }

            val delBtn = TextView(this).apply {
                text = "[x]"
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor(pal.dangerRed))
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

    // Dedicated Markdown Folder Submenu inside the popup
    private fun populateMarkdownSubmenu(container: LinearLayout) {
        container.addView(makeRow("<-", "< BACK TO TOOLS", null) {
            lastShownTime = System.currentTimeMillis()
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeHorizontalRule())

        val mdTools = ToolPrefs.loadMarkdownTools(this)
        val trimmed = selectedText.trim()
        val looksLikeUrl = trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            urlPattern.matches(trimmed)

        for (mdTool in mdTools) {
            container.addView(makeRow(mdTool.glyph, mdTool.label, null) {
                ToolPrefs.recordUse(this, "markdown_folder")
                ToolPrefs.recordUse(this, mdTool.id)
                if (mdTool.customTarget != null) {
                    applyResult(executeCustomTargetTransform(mdTool.customTarget, selectedText))
                } else {
                    val formatted = when (mdTool.id) {
                        "md_bold" -> "**${selectedText.trim()}**"
                        "md_italic" -> "*${selectedText.trim()}*"
                        "md_code" -> if (selectedText.contains("\n")) "```\n$selectedText\n```" else "`$selectedText`"
                        "md_quote" -> selectedText.lines().joinToString("\n") { "> $it" }
                        "md_link" -> if (looksLikeUrl) "[link]($trimmed)" else "[$selectedText](https://)"
                        "md_strike" -> "~~${selectedText.trim()}~~"
                        "md_bullet" -> selectedText.lines().filter { it.isNotBlank() }.joinToString("\n") { "- ${it.trim()}" }
                        "md_task" -> selectedText.lines().filter { it.isNotBlank() }.joinToString("\n") { "- [ ] ${it.trim()}" }
                        "md_h2" -> selectedText.lines().joinToString("\n") { "## ${it.trim()}" }
                        "md_spoiler" -> "||${selectedText.trim()}||"
                        "md_table" -> "| Col 1 | Col 2 |\n| --- | --- |\n| ${selectedText.trim()} |  |"
                        else -> selectedText
                    }
                    applyResult(formatted)
                }
            })
        }
    }

    // Dedicated Regex & Developer Pipe Submenu inside the popup
    private fun populateRegexPipeSubmenu(container: LinearLayout) {
        container.addView(makeRow("<-", "< BACK TO TOOLS", null) {
            lastShownTime = System.currentTimeMillis()
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeHorizontalRule())

        val pipeTools = ToolPrefs.loadRegexPipeTools(this)
        for (pTool in pipeTools) {
            container.addView(makeRow(pTool.glyph, pTool.label, null) {
                ToolPrefs.recordUse(this, "regex_pipe")
                ToolPrefs.recordUse(this, pTool.id)
                if (pTool.customTarget != null) {
                    applyResult(executeCustomTargetTransform(pTool.customTarget, selectedText))
                } else {
                    val transformed = when (pTool.id) {
                        "pipe_json_pretty" -> runCatching {
                            val t = selectedText.trim()
                            if (t.startsWith("[")) JSONArray(t).toString(2) else JSONObject(t).toString(2)
                        }.getOrElse { selectedText }
                        "pipe_json_min" -> runCatching {
                            val t = selectedText.trim()
                            if (t.startsWith("[")) JSONArray(t).toString() else JSONObject(t).toString()
                        }.getOrElse { selectedText.lines().joinToString("") { it.trim() } }
                        "pipe_url_enc" -> Uri.encode(selectedText)
                        "pipe_url_dec" -> Uri.decode(selectedText)
                        "pipe_b64_enc" -> Base64.encodeToString(selectedText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        "pipe_sha256" -> sha256Hex(selectedText)
                        "pipe_rot13" -> rot13(selectedText)
                        "pipe_num_lines" -> selectedText.lines().mapIndexed { i, l -> "${i + 1}. $l" }.joinToString("\n")
                        "pipe_strip_html" -> selectedText.replace(Regex("<[^>]+>"), "").trim()
                        else -> selectedText
                    }
                    applyResult(transformed)
                }
            })
        }
    }

    // Dedicated Web Search Subgroup
    private fun populateWebSearchSubmenu(container: LinearLayout) {
        container.addView(makeRow("<-", "< BACK TO TOOLS", null) {
            lastShownTime = System.currentTimeMillis()
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeHorizontalRule())

        val engines = ToolPrefs.loadWebSearchTools(this)
        for (engine in engines) {
            val favUrl = engine.faviconUrl ?: engine.customTarget
            container.addView(makeRow(engine.glyph, engine.label, null, favUrl) {
                ToolPrefs.recordUse(this, "web_search_folder")
                ToolPrefs.recordUse(this, engine.id)
                executeTool(engine)
            })
        }
    }

    // Dedicated YouTube Subfolder
    private fun populateYoutubeSubmenu(container: LinearLayout) {
        container.addView(makeRow("<-", "< BACK TO TOOLS", null) {
            lastShownTime = System.currentTimeMillis()
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeHorizontalRule())

        val ytTools = ToolPrefs.loadYoutubeTools(this)
        for (ytTool in ytTools) {
            val favUrl = ytTool.faviconUrl ?: ytTool.customTarget
            container.addView(makeRow(ytTool.glyph, ytTool.label, null, favUrl) {
                ToolPrefs.recordUse(this, "youtube_folder")
                ToolPrefs.recordUse(this, ytTool.id)
                executeTool(ytTool)
            })
        }
    }

    // Custom User-Defined Folder Submenu (supports both assigned built-in tools & custom rules!)
    private fun populateCustomFolderSubmenu(container: LinearLayout, folderName: String) {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val pal = ToolPrefs.resolvePalette(this)

        container.addView(makeRow("<-", "< BACK ($folderName)", null) {
            lastShownTime = System.currentTimeMillis()
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeHorizontalRule())

        val items = ToolPrefs.loadCustomFolderTools(this, folderName)
        if (items.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Folder $folderName is empty.\nAssign tools in Settings -> 02:TOOLS."
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(Color.parseColor(pal.fgMuted))
                setPadding(dp(10), dp(10), dp(10), dp(10))
            })
            return
        }

        for (item in items) {
            val favUrl = item.faviconUrl ?: item.customTarget?.takeIf {
                it.startsWith("http://", true) || it.startsWith("https://", true)
            }
            container.addView(makeRow(item.glyph, item.label, null, favUrl) {
                ToolPrefs.recordUse(this, "folder_custom_$folderName")
                ToolPrefs.recordUse(this, item.id)
                executeTool(item)
            })
        }
    }

    private fun executeCustomTargetTransform(target: String, input: String): String {
        val trimmedTarget = target.trim()
        if (trimmedTarget.startsWith("s/") && trimmedTarget.count { it == '/' } >= 3) {
            return runCatching {
                val firstSlash = 1
                val secondSlash = trimmedTarget.indexOf('/', firstSlash + 1)
                val lastSlash = trimmedTarget.lastIndexOf('/')
                if (secondSlash > firstSlash && lastSlash > secondSlash) {
                    val pattern = trimmedTarget.substring(firstSlash + 1, secondSlash)
                    val replacement = trimmedTarget.substring(secondSlash + 1, lastSlash)
                        .replace("\\n", "\n")
                        .replace("\\t", "\t")
                    val flags = trimmedTarget.substring(lastSlash + 1)
                    val opts = mutableSetOf<RegexOption>()
                    if (flags.contains('i', ignoreCase = true)) opts.add(RegexOption.IGNORE_CASE)
                    if (flags.contains('m', ignoreCase = true)) opts.add(RegexOption.MULTILINE)
                    val regex = Regex(pattern, opts)
                    if (flags.contains('g', ignoreCase = true)) {
                        regex.replace(input, replacement)
                    } else {
                        regex.replaceFirst(input, replacement)
                    }
                } else {
                    input
                }
            }.getOrDefault(input)
        }
        return trimmedTarget.replace("{text}", input)
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
                applyResult(executeCustomTargetTransform(target, selectedText))
            }
            return
        }

        when (tool.id) {
            "copy" -> {
                smoothCopyText(selectedText)
                hidePalette()
            }
            "screen_grab" -> {
                triggerScreenGrab(delayMs = 110L)
            }
            "clip_history" -> {
                lastShownTime = System.currentTimeMillis()
                currentMode = PopupMode.CLIP_HISTORY
                renderPopupBody()
            }
            "markdown_folder" -> {
                lastShownTime = System.currentTimeMillis()
                currentMode = PopupMode.MARKDOWN_FOLDER
                renderPopupBody()
            }
            "regex_pipe" -> {
                lastShownTime = System.currentTimeMillis()
                currentMode = PopupMode.REGEX_PIPE
                renderPopupBody()
            }
            "define" -> {
                lastShownTime = System.currentTimeMillis()
                currentMode = PopupMode.DEFINE_WORD
                renderPopupBody()
            }
            "translate" -> {
                lastShownTime = System.currentTimeMillis()
                currentMode = PopupMode.TRANSLATE
                renderPopupBody()
            }
            "qr" -> {
                lastShownTime = System.currentTimeMillis()
                currentMode = PopupMode.QR_CODE
                renderPopupBody()
            }
            "magnet" -> {
                lastShownTime = System.currentTimeMillis()
                currentMode = PopupMode.MAGNET
                renderPopupBody()
            }
            "clean_url" -> {
                applyResult(cleanUrl(selectedText))
            }
            "extract" -> {
                lastShownTime = System.currentTimeMillis()
                currentMode = PopupMode.EXTRACT
                renderPopupBody()
            }
            "speak" -> {
                if (ttsReady) {
                    tts?.speak(trimmed.take(500), TextToSpeech.QUEUE_FLUSH, null, "sel_tts")
                    Toast.makeText(this, "Speaking…", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "TTS initializing…", Toast.LENGTH_SHORT).show()
                }
            }
            "clean_text" -> {
                val cleaned = selectedText
                    .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
                    .replace(Regex("(?<!\\n)\\n(?!\\n)"), " ")
                    .replace(Regex("[ \\t]+"), " ")
                    .trim()
                applyResult(cleaned)
            }
            "web_search_folder" -> {
                lastShownTime = System.currentTimeMillis()
                currentMode = PopupMode.WEB_SEARCH_FOLDER
                renderPopupBody()
            }
            "youtube_folder" -> {
                lastShownTime = System.currentTimeMillis()
                currentMode = PopupMode.YOUTUBE_FOLDER
                renderPopupBody()
            }
            "case" -> applyResult(cycleCase(selectedText))
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

    // Inline Torrent Hash -> Magnet result view inside the popup
    private fun populateMagnetView(container: LinearLayout) {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val pal = ToolPrefs.resolvePalette(this)

        container.addView(makeRow("<-", "< BACK TO TOOLS", null) {
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeHorizontalRule())

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        val hash = extractTorrentHash(selectedText)
        val magnetUri = when {
            hash != null -> buildMagnetUri(hash)
            selectedText.trim().startsWith("magnet:?", ignoreCase = true) -> selectedText.trim()
            else -> {
                val cleaned = selectedText.trim().replace(Regex("\\s+"), "")
                "magnet:?xt=urn:btih:$cleaned"
            }
        }

        val titleView = TextView(this).apply {
            text = if (hash != null) "[0x] MAGNET READY (${hash.length}C)" else "[0x] MAGNET URI"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Color.parseColor(pal.accentGreen))
            setPadding(0, 0, 0, dp(6))
        }

        val magnetResultText = TextView(this).apply {
            text = magnetUri
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(Color.parseColor(pal.fgPrimary))
            setBackgroundColor(Color.parseColor(pal.bgSurface))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            maxLines = 5
        }

        val copyBtn = TextView(this).apply {
            text = "[ [^C] COPY MAGNET ]"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor(pal.accentGreen))
            setPadding(0, dp(10), 0, dp(4))
            setOnClickListener {
                smoothCopyText(magnetUri, "[^C] Magnet link copied")
                hidePalette()
            }
        }

        val pasteBtn = TextView(this).apply {
            text = "[ [^V] PASTE / REPLACE ]"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor(pal.fgPrimary))
            setPadding(0, dp(6), 0, dp(4))
            setOnClickListener {
                applyResult(magnetUri)
            }
        }

        val openAppBtn = TextView(this).apply {
            text = "[ OPEN IN TORRENT APP ]"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor(pal.accentPurple))
            setPadding(0, dp(6), 0, 0)
            setOnClickListener {
                smoothCopyText(magnetUri, "[0x] Opening magnet…")
                openUrl(magnetUri)
            }
        }

        box.addView(titleView)
        box.addView(magnetResultText)
        box.addView(copyBtn)
        box.addView(pasteBtn)
        box.addView(openAppBtn)
        container.addView(box)
    }

    // Inline QR Code Generator inside the popup
    private fun populateQrView(container: LinearLayout) {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val pal = ToolPrefs.resolvePalette(this)

        container.addView(makeRow("<-", "< BACK TO TOOLS", null) {
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
            val qrImage = ImageView(this).apply {
                setImageBitmap(bmp)
                this.layoutParams = LinearLayout.LayoutParams(dp(180), dp(180))
            }
            box.addView(qrImage)
        } else {
            box.addView(TextView(this).apply {
                text = "Could not encode QR"
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Color.parseColor(pal.dangerRed))
            })
        }

        val caption = TextView(this).apply {
            text = content.take(64) + if (content.length > 64) "…" else ""
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Color.parseColor(pal.fgMuted))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        box.addView(caption)
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

    // Inline Word Definition lookup inside the popup
    private fun populateDefineView(container: LinearLayout) {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val pal = ToolPrefs.resolvePalette(this)
        val rawTerm = selectedText.trim()
        val firstWord = rawTerm.split(Regex("\\s+")).firstOrNull()
            ?.trim { !it.isLetterOrDigit() && it != '-' }
            ?.ifBlank { rawTerm.take(32) }
            ?: rawTerm.take(32)

        container.addView(makeRow("<-", "< BACK TO TOOLS", null) {
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeHorizontalRule())

        val bodyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        val titleView = TextView(this).apply {
            text = "[?=] ${firstWord.uppercase()}"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor(pal.accentGreen))
            setPadding(0, 0, 0, dp(6))
        }

        val defTextView = TextView(this).apply {
            text = "Looking up \"$firstWord\"…"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor(pal.fgPrimary))
            setLineSpacing(0f, 1.18f)
        }

        val copyDefBtn = TextView(this).apply {
            text = "[ [^C] COPY DEFINITION ]"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(Color.parseColor(pal.accentGreen))
            setPadding(0, dp(10), 0, 0)
            visibility = View.GONE
            setOnClickListener {
                smoothCopyText(defTextView.text.toString(), "[^C] Definition copied")
            }
        }

        bodyBox.addView(titleView)
        bodyBox.addView(defTextView)
        bodyBox.addView(copyDefBtn)
        container.addView(bodyBox)

        Thread {
            val resultText = fetchDefinition(firstWord, rawTerm)
            mainHandler.post {
                if (currentMode == PopupMode.DEFINE_WORD) {
                    defTextView.text = resultText
                    copyDefBtn.visibility = View.VISIBLE
                    popupView?.requestLayout()
                    val v = popupView
                    val p = popupParams
                    if (v != null && p != null && v.isAttachedToWindow) {
                        runCatching { windowManager.updateViewLayout(v, p) }
                    }
                }
            }
        }.start()
    }

    private fun fetchDefinition(word: String, fullSelection: String): String {
        runCatching {
            val url = URL("https://api.dictionaryapi.dev/api/v2/entries/en/${Uri.encode(word.lowercase())}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                requestMethod = "GET"
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(body)
                if (arr.length() > 0) {
                    val entry = arr.getJSONObject(0)
                    val phonetic = entry.optString("phonetic", "")
                    val meanings = entry.optJSONArray("meanings")
                    val sb = StringBuilder()
                    if (phonetic.isNotBlank()) {
                        sb.append(phonetic).append("\n\n")
                    }
                    if (meanings != null) {
                        val maxMeanings = minOf(meanings.length(), 3)
                        for (i in 0 until maxMeanings) {
                            val m = meanings.getJSONObject(i)
                            val pos = m.optString("partOfSpeech", "")
                            val defs = m.optJSONArray("definitions")
                            if (defs != null && defs.length() > 0) {
                                val dObj = defs.getJSONObject(0)
                                val defStr = dObj.optString("definition", "")
                                val exStr = dObj.optString("example", "")
                                sb.append("• [").append(pos).append("] ").append(defStr).append("\n")
                                if (exStr.isNotBlank()) {
                                    sb.append("  \"").append(exStr).append("\"\n")
                                }
                            }
                        }
                    }
                    val built = sb.toString().trim()
                    if (built.isNotEmpty()) return built
                }
            }
        }

        val wikiSuccess = runCatching {
            val query = fullSelection.trim().take(60)
            val url = URL("https://en.wikipedia.org/api/rest_v1/page/summary/${Uri.encode(query.replace(' ', '_'))}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                requestMethod = "GET"
                setRequestProperty("User-Agent", "sel-android/2.5")
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(body)
                val extract = obj.optString("extract", "")
                if (extract.isNotBlank()) return@runCatching extract.take(360)
            }
            null
        }.getOrNull()

        return wikiSuccess ?: "No definition found for \"$word\"."
    }

    // Inline Translate view inside the popup
    private fun populateTranslateView(container: LinearLayout) {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val pal = ToolPrefs.resolvePalette(this)
        val raw = selectedText.trim().take(400)

        container.addView(makeRow("<-", "< BACK TO TOOLS", null) {
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeHorizontalRule())

        val bodyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        val titleView = TextView(this).apply {
            text = "[<>] TRANSLATE (-> EN)"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Color.parseColor(pal.accentGreen))
            setPadding(0, 0, 0, dp(6))
        }

        val transTextView = TextView(this).apply {
            text = "Translating…"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor(pal.fgPrimary))
            setLineSpacing(0f, 1.18f)
        }

        val useBtn = TextView(this).apply {
            text = "[ [^C] COPY / APPLY TRANSLATION ]"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(Color.parseColor(pal.accentGreen))
            setPadding(0, dp(10), 0, 0)
            visibility = View.GONE
            setOnClickListener {
                applyResult(transTextView.text.toString())
            }
        }

        bodyBox.addView(titleView)
        bodyBox.addView(transTextView)
        bodyBox.addView(useBtn)
        container.addView(bodyBox)

        Thread {
            val translated = fetchTranslation(raw)
            mainHandler.post {
                if (currentMode == PopupMode.TRANSLATE) {
                    transTextView.text = translated
                    useBtn.visibility = View.VISIBLE
                    popupView?.requestLayout()
                    val v = popupView
                    val p = popupParams
                    if (v != null && p != null && v.isAttachedToWindow) {
                        runCatching { windowManager.updateViewLayout(v, p) }
                    }
                }
            }
        }.start()
    }

    private fun fetchTranslation(text: String): String {
        return runCatching {
            val url = URL(
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=en&dt=t&q=${Uri.encode(text)}"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                requestMethod = "GET"
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val outer = JSONArray(body)
                val sentences = outer.getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until sentences.length()) {
                    val seg = sentences.getJSONArray(i)
                    sb.append(seg.optString(0, ""))
                }
                val res = sb.toString().trim()
                if (res.isNotEmpty()) return@runCatching res
            }
            "Translation unavailable."
        }.getOrDefault("Translation unavailable.")
    }

    // Inline Entity Extractor inside the popup
    private fun populateExtractView(container: LinearLayout) {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val pal = ToolPrefs.resolvePalette(this)

        container.addView(makeRow("<-", "< BACK TO TOOLS", null) {
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeHorizontalRule())

        val matches = mutableListOf<Pair<String, String>>()
        Regex("https?://[^\\s\"<>]+").findAll(selectedText).forEach { matches.add("URL" to it.value) }
        Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}").findAll(selectedText).forEach { matches.add("MAIL" to it.value) }
        Regex("(?:\\+?\\d[\\d\\- ()]{6,}\\d)").findAll(selectedText).forEach { matches.add("TEL" to it.value.trim()) }
        Regex("[#@][a-zA-Z0-9_]+").findAll(selectedText).forEach { matches.add("TAG" to it.value) }

        if (matches.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No URLs, emails, phones, or tags found."
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(Color.parseColor(pal.fgMuted))
                setPadding(dp(12), dp(12), dp(12), dp(12))
            })
            return
        }

        matches.distinctBy { it.second }.take(12).forEach { (_, item) ->
            container.addView(makeRow("", item.take(28), null) {
                applyResult(item)
            })
        }
    }

    // Inline Drag-and-Drop Reorder + Top Row (max 4) Assignment + Folder Assignment inside the popup
    private fun populateReorderView(container: LinearLayout) {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val pal = ToolPrefs.resolvePalette(this)

        container.addView(makeRow("<-", "< DONE (MAX 4 PER TOP ROW)", null) {
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })

        val r1Count = ToolPrefs.getIconRowIds(this, 1).size
        val r2Count = ToolPrefs.getIconRowIds(this, 2).size
        container.addView(
            makeRow(
                "[=]",
                "LOAD 2-ROW PRESET (R1:$r1Count/4 R2:$r2Count/4)",
                null
            ) {
                ToolPrefs.applyDefaultTwoIconRowsPreset(this)
                Toast.makeText(this, "Assigned 4 icons each to Row 1 & Row 2", Toast.LENGTH_SHORT).show()
                renderPopupBody()
            }
        )

        if (r1Count > 0 || r2Count > 0) {
            container.addView(
                makeRow(
                    "[X]",
                    "CLEAR EXTRA TOP ROWS",
                    null
                ) {
                    ToolPrefs.clearIconRow(this, 1)
                    ToolPrefs.clearIconRow(this, 2)
                    renderPopupBody()
                }
            )
        }

        val mostUsedOn = ToolPrefs.isSortMostUsed(this)
        container.addView(
            makeRow(
                "[*]",
                if (mostUsedOn) "MOST USED TOP: ON" else "MOST USED TOP: OFF",
                null
            ) {
                ToolPrefs.setSortMostUsed(this, !mostUsedOn)
                renderPopupBody()
            }
        )

        if (activePackage.isNotBlank() && activePackage != packageName) {
            val isMuted = ToolPrefs.isAppBlacklisted(this, activePackage)
            val shortPkg = activePackage.substringAfterLast('.').uppercase()
            container.addView(
                makeRow(
                    "[-]",
                    if (isMuted) "UNMUTE IN $shortPkg" else "MUTE IN $shortPkg",
                    null
                ) {
                    ToolPrefs.setAppBlacklisted(this, activePackage, !isMuted)
                    Toast.makeText(
                        this,
                        if (!isMuted) "Muted auto-popup in $activePackage" else "Unmuted $activePackage",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (!isMuted) hidePalette() else renderPopupBody()
                }
            )
        }

        container.addView(makeHorizontalRule())

        val dragList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val customFolders = ToolPrefs.getCustomFolders(this)
        val tools = ToolPrefs.getAllSortedTools(this)
        tools.forEach { tool ->
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
                setTextColor(Color.parseColor(pal.accentGreen))
                setPadding(dp(4), dp(2), dp(8), dp(2))
            }

            val label = TextView(this).apply {
                val cnt = ToolPrefs.getUseCount(this@SelAccessibilityService, tool.id)
                val tok = formatBracketToken(tool.glyph)
                text = "$tok ${tool.label}" + if (cnt > 0) " ($cnt)" else ""
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(Color.parseColor(if (hidden) pal.fgMuted else pal.fgPrimary))
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                this.layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            row.addView(dragHandle)
            row.addView(label)

            if (customFolders.isNotEmpty() && tool.id != "copy" && !tool.id.startsWith("folder_custom_")) {
                val folderBtn = TextView(this).apply {
                    text = if (assignedFolder != null) "[$assignedFolder/]" else "[ROOT]"
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                    setTextColor(Color.parseColor(if (assignedFolder != null) pal.accentOrange else pal.fgMuted))
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
                }
                row.addView(folderBtn)
            }

            if (tool.id != "copy") {
                val slotToggle = TextView(this).apply {
                    text = when (assignedRow) {
                        1 -> "[*R1]"
                        2 -> "[*R2]"
                        else -> "[LIST]"
                    }
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                    setTextColor(
                        Color.parseColor(
                            when (assignedRow) {
                                1 -> pal.accentPurple
                                2 -> pal.accentCyan
                                else -> pal.fgMuted
                            }
                        )
                    )
                    setPadding(dp(5), dp(3), dp(5), dp(3))
                    setOnClickListener {
                        val nextRow = ToolPrefs.cycleToolIconRow(this@SelAccessibilityService, tool.id)
                        val tok = formatBracketToken(tool.glyph)
                        val msg = when (nextRow) {
                            1 -> "$tok pinned to Top Row 1 (max 4)"
                            2 -> "$tok pinned to Top Row 2 (max 4)"
                            else -> "${tool.label} returned to text-only list"
                        }
                        Toast.makeText(this@SelAccessibilityService, msg, Toast.LENGTH_SHORT).show()
                        renderPopupBody()
                    }
                }
                row.addView(slotToggle)
            }

            val visToggle = TextView(this).apply {
                text = if (hidden) "[ ]" else "[x]"
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(Color.parseColor(if (hidden) pal.fgMuted else pal.accentGreen))
                setPadding(dp(5), dp(2), dp(2), dp(2))
                setOnClickListener {
                    ToolPrefs.setToolHidden(this@SelAccessibilityService, tool.id, !hidden)
                    renderPopupBody()
                }
            }

            row.addView(visToggle)
            ToolPrefs.attachDragHandle(dragHandle, row, dragList) {
                renderPopupBody()
            }
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

    /**
     * Clean Text-Only Row for list items below the top rows!
     * Icons (`[^C]`, `[/]`, `[|>]`, `[≡]`, `[::]`, `[?=]`, `[+]`, etc.) are shown ONLY when kept on top rows.
     */
    private fun makeRow(
        glyph: String,
        label: String,
        countBadge: String?,
        faviconUrl: String? = null,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit
    ): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val pal = ToolPrefs.resolvePalette(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    GradientDrawable().apply {
                        setColor(Color.parseColor(pal.bgPressed))
                    }
                )
                addState(
                    intArrayOf(),
                    GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                    }
                )
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

        val cleanLabel = label.replace("▸", ">").replace("→", "->").trim()
        val isBackRow = cleanLabel.startsWith("< ")

        val labelView = TextView(this).apply {
            text = cleanLabel
            typeface = if (isBackRow) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor(if (isBackRow) pal.accentPurple else pal.fgPrimary))
            includeFontPadding = false
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            this.layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        row.addView(labelView)

        if (!countBadge.isNullOrBlank()) {
            val badgeView = TextView(this).apply {
                text = "($countBadge)"
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                setTextColor(Color.parseColor(pal.fgMuted))
                includeFontPadding = false
                setPadding(dp(4), 0, 0, 0)
            }
            row.addView(badgeView)
        }

        return row
    }

    private fun makeHorizontalRule(thick: Boolean = false): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val pal = ToolPrefs.resolvePalette(this)
        return View(this).apply {
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                if (!thick) {
                    topMargin = dp(2)
                    bottomMargin = dp(2)
                }
            }
            setBackgroundColor(Color.parseColor(if (thick) pal.border else pal.divider))
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
                val dm = resources.displayMetrics
                val dp = { d: Int -> (d * dm.density).toInt() }
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
                        if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) {
                            userManuallyDragged = true
                        }
                        params.x = (initialX + dx)
                            .safeCoerceIn(dp(6), (dm.widthPixels - rootView.width - dp(6)).coerceAtLeast(dp(6)))
                        params.y = (initialY + dy)
                            .safeCoerceIn(dp(44), (dm.heightPixels - dp(84)).coerceAtLeast(dp(48)))
                        if (rootView.isAttachedToWindow) {
                            windowManager.updateViewLayout(rootView, params)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (kotlin.math.abs(event.rawX - touchX) > dp(8)) {
                            val centerX = params.x + rootView.width / 2
                            val snapLeft = centerX < dm.widthPixels / 2
                            ToolPrefs.setSnapLeftEdge(this@SelAccessibilityService, snapLeft)
                            params.x = if (snapLeft) {
                                dp(10)
                            } else {
                                (dm.widthPixels - rootView.width - dp(10)).coerceAtLeast(dp(10))
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
        lastClipboardTime = System.currentTimeMillis()
        clipboardManager.setPrimaryClip(ClipData.newPlainText("sel", newText))
        ToolPrefs.addClipToHistory(this, newText)

        var replaced = false
        val node = currentNode
        if (node != null && node.isEditable) {
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

    private fun sha256Hex(input: String): String {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        }.getOrDefault(input)
    }

    private fun rot13(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            val out = when (c) {
                in 'a'..'m', in 'A'..'M' -> c + 13
                in 'n'..'z', in 'N'..'Z' -> c - 13
                else -> c
            }
            sb.append(out)
        }
        return sb.toString()
    }

    private fun extractTorrentHash(input: String): String? {
        Regex("\\b([0-9a-fA-F]{40})\\b").find(input)?.let { return it.groupValues[1].lowercase() }
        Regex("\\b([0-9a-fA-F]{64})\\b").find(input)?.let { return it.groupValues[1].lowercase() }
        Regex("\\b([A-Za-z2-7]{32})\\b").find(input)?.let {
            val candidate = it.groupValues[1]
            if (candidate.any { ch -> ch.isDigit() }) return candidate.uppercase()
        }
        return null
    }

    private fun buildMagnetUri(hash: String): String {
        val trackers = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://open.demonii.com:1337/announce"
        ).joinToString("") { "&tr=${Uri.encode(it)}" }

        return if (hash.length == 64) {
            "magnet:?xt=urn:btmh:1220$hash$trackers"
        } else {
            "magnet:?xt=urn:btih:$hash$trackers"
        }
    }

    private fun cleanUrl(input: String): String {
        return input.replace(trackingRegex, "").replace("?&", "?").trimEnd('?', '&')
    }

    private fun cycleCase(input: String): String {
        return when {
            input == input.uppercase() -> input.lowercase()
            input == input.lowercase() -> input.split(" ").joinToString(" ") { w ->
                w.replaceFirstChar { it.uppercase() }
            }
            else -> input.uppercase()
        }
    }

    private fun evalSimpleMath(input: String): String? {
        val match = Regex("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(-?\\d+(?:\\.\\d+)?)\\s*$")
            .find(input) ?: return null
        val a = match.groupValues[1].toDoubleOrNull() ?: return null
        val op = match.groupValues[2]
        val b = match.groupValues[3].toDoubleOrNull() ?: return null
        val res = when (op) {
            "+" -> a + b
            "-" -> a - b
            "*" -> a * b
            "/" -> if (b != 0.0) a / b else return null
            else -> return null
        }
        return if (res % 1.0 == 0.0) res.toLong().toString() else "%.2f".format(res)
    }

    private fun evalUnitConversion(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.startsWith("0x", ignoreCase = true)) {
            val dec = trimmed.substring(2).toLongOrNull(16) ?: return null
            return "= $dec DEC"
        }
        val m = Regex("^(-?\\d+(?:\\.\\d+)?)\\s*°?\\s*(km|mi|kg|lbs?|cm|in|ft|m|c|f)$", RegexOption.IGNORE_CASE)
            .find(trimmed) ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        return when (m.groupValues[2].lowercase()) {
            "km" -> "= %.2f mi".format(v * 0.621371)
            "mi" -> "= %.2f km".format(v * 1.60934)
            "kg" -> "= %.2f lb".format(v * 2.20462)
            "lb", "lbs" -> "= %.2f kg".format(v * 0.453592)
            "cm" -> "= %.2f in".format(v * 0.393701)
            "in" -> "= %.2f cm".format(v * 2.54)
            "ft" -> "= %.2f m".format(v * 0.3048)
            "m" -> "= %.2f ft".format(v * 3.28084)
            "f" -> "= %.1f °C".format((v - 32.0) * 5.0 / 9.0)
            "c" -> "= %.1f °F".format(v * 9.0 / 5.0 + 32.0)
            else -> null
        }
    }

    private fun evalTimestamp(input: String): String? {
        val trimmed = input.trim()
        if (!Regex("^\\d{10}(\\d{3})?$").matches(trimmed)) return null
        val raw = trimmed.toLongOrNull() ?: return null
        val ms = if (trimmed.length == 10) raw * 1000L else raw
        if (ms < 946684800000L || ms > 4102444800000L) return null
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
    }

    private fun evalBase64Decode(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.length < 8 || trimmed.length % 4 != 0 || trimmed.contains(" ")) return null
        if (!Regex("^[A-Za-z0-9+/]+={0,2}$").matches(trimmed)) return null
        if (extractTorrentHash(trimmed) != null) return null
        return runCatching {
            val bytes = Base64.decode(trimmed, Base64.DEFAULT)
            if (bytes.isEmpty() || bytes.size > 4096) return null
            val str = String(bytes, Charsets.UTF_8)
            val printable = str.all { it == '\n' || it == '\r' || it == '\t' || it.code in 32..126 }
            if (printable && str.isNotBlank()) str.trim() else null
        }.getOrNull()
    }

    private fun appendToScratchpad(text: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        File(filesDir, "scratchpad.txt").appendText("[$stamp]\n${text.trim()}\n\n")
    }

    private fun hidePalette() {
        mainHandler.removeCallbacks(delayedHideRunnable)
        pendingWebCopyRunnable?.let { mainHandler.removeCallbacks(it) }
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
