package io.github.selclip

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.text.TextUtils
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

    private lateinit var windowManager: WindowManager
    private lateinit var clipboardManager: ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var popupView: View? = null
    private var popupParams: WindowManager.LayoutParams? = null
    private var headerBarView: LinearLayout? = null
    private var statsLabelView: TextView? = null
    private var reorderToggleBtn: TextView? = null
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
                // True fallback for images/canvases with zero Accessibility text nodes
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
                setColor(Color.parseColor("#0A0A0A"))
                setStroke(dp(1), Color.parseColor("#00FF66"))
                cornerRadius = dp(4).toFloat()
            }
        }

        card.addView(TextView(this).apply {
            text = "📸 SCREENSHOT CAPTURED (${bitmap.width}×${bitmap.height})"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Color.parseColor("#00FF66"))
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
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, dp(8), 0, dp(6))
        })

        val retryLensBtn = TextView(this).apply {
            text = "[ ⊞ RE-SCAN ACCESSIBILITY NODES ]"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#00FF66"))
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
            text = "[ × CLOSE ]"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#FF5555"))
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
                        val z = Rect(0, dm.heightPixels / 3, dm.widthPixels, dm.heightPixels / 2); currentSafeZone = Rect(z); showOrUpdatePalette(z)
                    }
                    "clean_url" -> {
                        smoothCopyText(cleanUrl(chosen), "⎘ Cleaned URL copied")
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
                        val z = Rect(0, dm.heightPixels / 3, dm.widthPixels, dm.heightPixels / 2); currentSafeZone = Rect(z); showOrUpdatePalette(z)
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
        Toast.makeText(this, toastLabel ?: "⎘ Copied: \"$preview\"", Toast.LENGTH_SHORT).show()
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
        event ?: return
        if (screenGrabOverlayView != null) return
        val pkg = event.packageName?.toString() ?: ""

        // Respect per-app blacklist for automatic popups
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
                // Only inspect when popup is NOT showing and user is in a browser/web app (throttled to 900ms)
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

            // Capture floating "Copy" toolbar bounds BEFORE ACTION_COPY dismisses it
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

        // 1. O(1) direct check on event.source (zero tree walk!)
        val source = event.source
        if (source != null) {
            if (source.isPassword) return null
            extractFromNode(source, event.fromIndex, event.toIndex)?.let { return it }
        }

        // 2. O(1) direct check on event.text with fromIndex..toIndex (zero IPC!)
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

        // 3. O(1) check on focused input node
        val focused = findSelectedOrFocusedNode()
        if (focused != null && focused != source) {
            extractFromNode(focused)?.let { return it }
        }

        // 4. Strictly bounded shallow check (max 24 nodes total, never blocks UI thread)
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

    /**
     * Single lightweight cached check (runs at most once per 400ms on selection) that updates:
     * 1. `cachedVisibleBottom` (detecting open `TYPE_INPUT_METHOD` soft keyboard)
     * 2. `lastDetectedToolbarRect` (detecting native `"Copy"` / `"Cut"` selection bar while excluding
     *    keyboard suggestion strips and browser bottom URL bar `"Share"` buttons).
     * All layout passes (`applyStrictPlacementAndDynamicSize`, `renderPopupBody`, `OnLayoutChangeListener`)
     * read `cachedVisibleBottom` in O(1) memory without making any Binder IPC calls!
     */
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
                // Only query "Copy" (and "Cut" if "Copy" wasn't found) — max 1-2 fast IPC calls, never "Share"!
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
                // Pass 1: find open IME keyboard window first so we know visBottom
                for (w in winList) {
                    if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        val imeRect = Rect()
                        w.getBoundsInScreen(imeRect)
                        if (imeRect.height() > dp(80) && imeRect.top in (realH / 4)..(realH - dp(70))) {
                            visBottom = minOf(visBottom, imeRect.top - dp(10))
                        }
                    }
                }
                // Pass 2: check active or small floating popup window for "Copy" button
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

        // Case 1: Compact single-line / sliced paragraph selection (e.g. "Hello" in 1000014052.jpg)
        if (isCompactCandidate && rawCandidate != null) {
            var zoneTop = rawCandidate.top - dp(10)
            var zoneBottom = rawCandidate.bottom + dp(18)
            if (toolbarRect != null && kotlin.math.abs(toolbarRect.centerY() - rawCandidate.centerY()) < dp(150)) {
                zoneTop = minOf(zoneTop, toolbarRect.top - dp(10))
                zoneBottom = maxOf(zoneBottom, toolbarRect.bottom + dp(14))
            }
            return Rect(
                rawCandidate.left.coerceAtLeast(dp(12)),
                zoneTop.coerceIn(dp(48), (visibleBottom - dp(64)).coerceAtLeast(dp(48))),
                rawCandidate.right.coerceAtMost(dm.widthPixels - dp(12)),
                zoneBottom.coerceIn(zoneTop + dp(32), visibleBottom - dp(8))
            )
        }

        // Case 2: Browser WebView or tall paragraph where floating "Copy | Select all" bar is known (1000014053.jpg)
        if (toolbarRect != null) {
            val zoneTop = (toolbarRect.top - dp(14)).coerceIn(dp(48), (visibleBottom - dp(80)).coerceAtLeast(dp(48)))
            val zoneBottom = (toolbarRect.bottom + dp(66)).coerceIn(zoneTop + dp(44), visibleBottom - dp(8))
            return Rect(
                toolbarRect.left.coerceAtLeast(dp(12)),
                zoneTop,
                toolbarRect.right.coerceAtMost(dm.widthPixels - dp(12)),
                zoneBottom
            )
        }

        // Case 3: Medium paragraph node when no floating toolbar was detected
        if (isMediumCandidate && rawCandidate != null) {
            val zoneTop = (rawCandidate.top - dp(10)).coerceIn(dp(48), (visibleBottom - dp(80)).coerceAtLeast(dp(48)))
            val zoneBottom = (rawCandidate.bottom + dp(20)).coerceIn(zoneTop + dp(36), visibleBottom - dp(8))
            return Rect(
                rawCandidate.left.coerceAtLeast(dp(12)),
                zoneTop,
                rawCandidate.right.coerceAtMost(dm.widthPixels - dp(12)),
                zoneBottom
            )
        }

        // Case 4: Fallback if rawCandidate was a full-screen WebView and no toolbar was detected
        val fallbackMidY = (visibleBottom * 0.36f).toInt().coerceIn(dp(96), visibleBottom - dp(96))
        return Rect(dp(16), fallbackMidY - dp(28), dm.widthPixels - dp(16), fallbackMidY + dp(36))
    }

    /**
     * Pure O(1) arithmetic bounds calculation — zero `refreshWithExtraData` blocking IPC!
     */
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

        // If the node is a multi-line container (e.g. EditText / paragraph > 84dp tall),
        // estimate the vertical line slice of [start..end] using fast arithmetic (capped at 4000 chars)
        if (nodeRect.height() in dp(84)..(realH * 0.45f).toInt() && raw.isNotEmpty()) {
            val avgCharsPerLine = ((nodeRect.width() / dp(7).coerceAtLeast(1))).coerceIn(22, 72)
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
            val estLineH = (nodeRect.height().toFloat() / totalLines.toFloat()).coerceIn(dp(16).toFloat(), dp(25).toFloat())
            val bandTop = (nodeRect.top + startLine * estLineH).toInt().coerceIn(nodeRect.top, nodeRect.bottom - dp(28))
            val bandBottom = (nodeRect.top + (endLine + 1) * estLineH).toInt()
                .coerceIn(bandTop + dp(24), nodeRect.bottom)
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

    /**
     * Strictly bounded pre-order selection search (visits at most 24 nodes total so it never stalls UI thread).
     */
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

    /**
     * O(1) lookup for the currently focused editable node via `findFocus(FOCUS_INPUT)`.
     */
    private fun findSelectedOrFocusedNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val inputFocused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (inputFocused != null && inputFocused.isEditable) return inputFocused
        val a11yFocused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) }.getOrNull()
        if (a11yFocused != null && a11yFocused.isEditable) return a11yFocused
        return null
    }

    /**
     * Computes strict ABOVE (`▲`) or BELOW (`▼`) placement in a single unified coordinate system (`Gravity.TOP`)
     * using O(1) cached screen metrics (`cachedVisibleBottom`) with zero Binder IPC calls.
     */
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

        // Choose BELOW if there is ample room above the keyboard/bottom bar; otherwise ABOVE
        val placeAbove = when {
            spaceBelow >= dp(230) && (spaceBelow >= spaceAbove || spaceBelow >= dp(290)) -> false
            spaceAbove >= dp(140) && spaceAbove > spaceBelow -> true
            spaceBelow >= dp(150) -> false
            else -> spaceAbove > spaceBelow
        }
        anchoredAboveSelection = placeAbove

        val chosenSpace = if (placeAbove) spaceAbove else spaceBelow
        currentMaxTotalHeight = chosenSpace.coerceIn(dp(110), (realH * 0.43f).toInt())
        val headerHeightEst = dp(52)
        dynamicMaxScrollHeight = (currentMaxTotalHeight - headerHeightEst).coerceIn(dp(58), dp(330))

        val tightVertical = chosenSpace < dp(220)
        dynamicMinPopupWidth = if (tightVertical) dp(228) else dp(220)
        dynamicMaxPopupWidth = when {
            currentMode != PopupMode.ACTIONS -> dp(292)
            tightVertical -> dp(272)
            else -> dp(260)
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
                params.y = (safeZone.bottom + gap).coerceIn(topSafe, maxTopY)
            }
        }
    }

    // Dynamic autosizing vertical palette inspired by PlainTextSports.com with strict Above/Below anchor
    private fun showOrUpdatePalette(safeZone: Rect) {
        val dm = resources.displayMetrics
        val dp = { v: Int -> (v * dm.density).toInt() }

        val words = selectedText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        val chars = selectedText.length

        if (popupView != null && statsLabelView != null && bodyContainer != null && popupParams != null) {
            applyStrictPlacementAndDynamicSize(safeZone, popupParams!!)
            val posGlyph = if (anchoredAboveSelection) "▲" else "▼"
            statsLabelView?.text = "${words}W·${chars}C $posGlyph"
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
                setColor(Color.parseColor("#0A0A0A"))
                setStroke(dp(2), Color.parseColor("#444444"))
                cornerRadius = dp(2).toFloat()
            }
            // Keep popup strictly above selection (when ▲) and strictly above open keyboard (when ▼) with 0 IPC calls
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
                        (currentSafeZone.bottom + gap).coerceIn(topSafe, maxTop)
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
            // Immediately hide popup when clicking anywhere outside the popup
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

        // PlainTextSports-inspired single-line scoreboard header bar with oversized Close button
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(dp(10), dp(7), dp(8), dp(7))
        }

        val posGlyph = if (anchoredAboveSelection) "▲" else "▼"
        val statsLabel = TextView(this).apply {
            text = "${words}W·${chars}C $posGlyph"
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(Color.parseColor("#999999"))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, dp(4), 0)
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        fun makeHeaderBoxBtn(
            textStr: String,
            fgHex: String,
            bgHex: String = "#1A1A1A",
            borderHex: String = "#383838",
            textSp: Float = 11.5f,
            hPadDp: Int = 6,
            vPadDp: Int = 5,
            minW: Int = 30,
            minH: Int = 32,
            onClick: () -> Unit
        ): TextView {
            return TextView(this).apply {
                text = textStr
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
                setTextColor(Color.parseColor(fgHex))
                gravity = Gravity.CENTER
                minWidth = dp(minW)
                minHeight = dp(minH)
                setPadding(dp(hPadDp), dp(vPadDp), dp(hPadDp), dp(vPadDp))
                background = StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_pressed),
                        GradientDrawable().apply {
                            setColor(Color.parseColor("#2E2E2E"))
                            setStroke(dp(1), Color.parseColor("#00FF66"))
                            cornerRadius = dp(2).toFloat()
                        }
                    )
                    addState(
                        intArrayOf(),
                        GradientDrawable().apply {
                            setColor(Color.parseColor(bgHex))
                            setStroke(dp(1), Color.parseColor(borderHex))
                            cornerRadius = dp(2).toFloat()
                        }
                    )
                }
                this.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(4)
                }
                setOnClickListener {
                    runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    onClick()
                }
            }
        }

        val quickCopyHeaderBtn = makeHeaderBoxBtn("⎘", "#00FF66", "#101E15", "#295238") {
            ToolPrefs.recordUse(this@SelAccessibilityService, "copy")
            smoothCopyText(selectedText)
            hidePalette()
        }

        val collapseBtn = makeHeaderBoxBtn("–", "#CCCCCC") {
            lastShownTime = System.currentTimeMillis()
            currentMode = PopupMode.COLLAPSED_PILL
            renderPopupBody()
        }

        val reorderBtn = makeHeaderBoxBtn("⇅", "#CCCCCC") {
            lastShownTime = System.currentTimeMillis()
            currentMode = if (currentMode == PopupMode.REORDER) PopupMode.ACTIONS else PopupMode.REORDER
            renderPopupBody()
        }

        // High-contrast Close button ([ × ])
        val closeBtn = makeHeaderBoxBtn(
            textStr = " × ",
            fgHex = "#FF6666",
            bgHex = "#241212",
            borderHex = "#6E3333",
            textSp = 15.5f,
            hPadDp = 10,
            vPadDp = 5,
            minW = 40,
            minH = 34
        ) {
            hidePalette()
        }.apply {
            (this.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(5)
        }

        header.addView(statsLabel)
        header.addView(quickCopyHeaderBtn)
        header.addView(collapseBtn)
        header.addView(reorderBtn)
        header.addView(closeBtn)
        enableDragAndEdgeSnapOnHeader(header, rootCard, windowParams)
        rootCard.addView(header)
        rootCard.addView(makeHorizontalRule(thick = true))

        val scrollView = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxHeightSpec = MeasureSpec.makeMeasureSpec(dynamicMaxScrollHeight, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, maxHeightSpec)
            }
        }.apply {
            isVerticalScrollBarEnabled = true
            setPadding(0, dp(4), 0, dp(4))
            clipToPadding = false
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(4))
        }

        scrollView.addView(container)
        rootCard.addView(scrollView)

        popupView = rootCard
        popupParams = windowParams
        headerBarView = header
        statsLabelView = statsLabel
        reorderToggleBtn = reorderBtn
        bodyContainer = container

        renderPopupBody()
        runCatching { windowManager.addView(popupView, windowParams) }
    }

    private fun renderPopupBody() {
        val container = bodyContainer ?: return
        container.removeAllViews()

        headerBarView?.visibility = if (currentMode == PopupMode.COLLAPSED_PILL) View.GONE else View.VISIBLE

        reorderToggleBtn?.setTextColor(
            Color.parseColor(if (currentMode == PopupMode.REORDER) "#00FF66" else "#CCCCCC")
        )

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
            val posGlyph = if (anchoredAboveSelection) "▲" else "▼"
            statsLabelView?.text = "${words}W·${chars}C $posGlyph"
        }

        popupView?.requestLayout()
        val view = popupView
        if (view != null && params != null && view.isAttachedToWindow) {
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun populateCollapsedPill(container: LinearLayout) {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val pill = TextView(this).apply {
            text = "+── [sel_ ▸] ──+"
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(Color.parseColor("#00FF66"))
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

    private fun populateActionRows(list: LinearLayout) {
        val pinnedIds = HashSet<String>()

        // 0. 1-Tap In-Place Undo row if a recent text replacement occurred in the last 45s
        val prevText = undoOriginalFullText
        val uNode = undoNode
        if (prevText != null && uNode != null && System.currentTimeMillis() - undoTimestamp < 45000L) {
            list.addView(makeRow("↶", "UNDO LAST EDIT", null) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prevText)
                }
                val ok = runCatching { uNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
                undoOriginalFullText = null
                undoNode = null
                Toast.makeText(
                    this,
                    if (ok) "↶ Restored original text" else "⎘ Copied original text",
                    Toast.LENGTH_SHORT
                ).show()
                if (!ok) smoothCopyText(prevText)
                hidePalette()
            })
        }

        // 1. Instant Math Evaluator
        evalSimpleMath(selectedText)?.let { ans ->
            list.addView(makeRow("∑", "= $ans", null) {
                ToolPrefs.recordUse(this, "math")
                applyResult(ans)
            })
        }

        // 2. Instant Unit & Hex/Dec Converter
        evalUnitConversion(selectedText)?.let { conv ->
            list.addView(makeRow("⇄", conv, null) {
                ToolPrefs.recordUse(this, "unit_conv")
                applyResult(conv.removePrefix("= ").trim())
            })
        }

        // 2b. Context-Aware Unix Timestamp Decoder
        evalTimestamp(selectedText)?.let { formattedDate ->
            list.addView(makeRow("🕒", formattedDate, null) {
                ToolPrefs.recordUse(this, "epoch_conv")
                applyResult(formattedDate)
            })
        }

        // 2c. Context-Aware Base64 Decoder
        evalBase64Decode(selectedText)?.let { decoded ->
            val preview = decoded.replace("\n", " ").let { if (it.length > 20) it.take(19) + "…" else it }
            list.addView(makeRow("🔓", "B64 → $preview", null) {
                ToolPrefs.recordUse(this, "b64_decode")
                applyResult(decoded)
            })
        }

        // 3. Pin HASH -> MAGNET at the top ONLY when the selected text is a torrent hash
        if (isToolRelevantForSelection("magnet", selectedText) && !ToolPrefs.isToolHidden(this, "magnet")) {
            pinnedIds.add("magnet")
            list.addView(makeRow("🧲", "HASH → MAGNET", null) {
                ToolPrefs.recordUse(this, "magnet")
                lastShownTime = System.currentTimeMillis()
                currentMode = PopupMode.MAGNET
                renderPopupBody()
            })
        }

        // 4. Pin CLEAN URL at the top ONLY when the selected URL has query/tracking params
        if (isToolRelevantForSelection("clean_url", selectedText) && !ToolPrefs.isToolHidden(this, "clean_url")) {
            pinnedIds.add("clean_url")
            list.addView(makeRow("⌫", "CLEAN URL", null) {
                ToolPrefs.recordUse(this, "clean_url")
                applyResult(cleanUrl(selectedText))
            })
        }

        // 5. Context-filtered, Reorderable & Most-Used-on-Top Tools
        val orderedTools = ToolPrefs.getSortedToolsForPopup(this)
        val showCounts = ToolPrefs.isSortMostUsed(this)
        for (tool in orderedTools) {
            if (tool.id in pinnedIds) continue
            if (!isToolRelevantForSelection(tool.id, selectedText)) continue

            val count = ToolPrefs.getUseCount(this, tool.id)
            val badge = if (showCounts && count > 0) "$count" else null
            val favUrl = tool.faviconUrl ?: tool.customTarget?.takeIf {
                it.startsWith("http://", true) || it.startsWith("https://", true)
            }
            list.addView(makeRow(tool.glyph, tool.label, badge, favUrl) {
                ToolPrefs.recordUse(this, tool.id)
                executeTool(tool)
            })
        }
    }

    // Ring-Buffer Clipboard History Submenu inside the popup
    private fun populateClipHistorySubmenu(container: LinearLayout) {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        container.addView(makeRow("←", "BACK TO TOOLS", null) {
            lastShownTime = System.currentTimeMillis()
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeRow("🧹", "CLEAR UNPINNED", null) {
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
                setTextColor(Color.parseColor("#888888"))
                setPadding(dp(12), dp(12), dp(12), dp(12))
            })
            return
        }

        for (entry in clips) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
            }

            val pinBtn = TextView(this).apply {
                text = if (entry.pinned) "📌" else "·"
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Color.parseColor(if (entry.pinned) "#00FF66" else "#666666"))
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
                setTextColor(Color.parseColor("#F0F0F0"))
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
                text = "×"
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor("#777777"))
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
        container.addView(makeRow("←", "BACK TO TOOLS", null) {
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
        container.addView(makeRow("←", "BACK TO TOOLS", null) {
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

    // Dedicated Web Search Subgroup (Top 4 Search Engines + user-added SEARCH/... engines with live favicons)
    private fun populateWebSearchSubmenu(container: LinearLayout) {
        container.addView(makeRow("←", "BACK TO TOOLS", null) {
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

    // Dedicated YouTube Subfolder (All Time, Last Hour, Today, This Week, This Month, This Year + custom YT/... rules)
    private fun populateYoutubeSubmenu(container: LinearLayout) {
        container.addView(makeRow("←", "BACK TO TOOLS", null) {
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

    // Custom User-Defined Folder Submenu (from FOLDER/LABEL rules in rules.txt — also shows favicons for web engines!)
    private fun populateCustomFolderSubmenu(container: LinearLayout, folderName: String) {
        container.addView(makeRow("←", "BACK ($folderName)", null) {
            lastShownTime = System.currentTimeMillis()
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeHorizontalRule())

        val items = ToolPrefs.loadCustomFolderTools(this, folderName)
        for (item in items) {
            val favUrl = item.faviconUrl ?: item.customTarget?.takeIf {
                it.startsWith("http://", true) || it.startsWith("https://", true)
            }
            container.addView(makeRow(item.glyph, item.label, null, favUrl) {
                ToolPrefs.recordUse(this, "folder_custom_$folderName")
                executeTool(item)
            })
        }
    }

    private fun executeCustomTargetTransform(target: String, input: String): String {
        val trimmedTarget = target.trim()
        // Support sed-style s/pattern/replacement/flags regex rules!
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
            "incognito" -> {
                val targetUrl = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    cleanUrl(trimmed)
                } else if (urlPattern.matches(trimmed) && !trimmed.contains(" ")) {
                    "https://$trimmed"
                } else {
                    "https://duckduckgo.com/?q=$encoded"
                }
                openIncognito(targetUrl)
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

        container.addView(makeRow("←", "BACK TO TOOLS", null) {
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
            text = if (hash != null) "🧲 MAGNET READY (${hash.length}C)" else "🧲 MAGNET URI"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Color.parseColor("#00FF66"))
            setPadding(0, 0, 0, dp(6))
        }

        val magnetResultText = TextView(this).apply {
            text = magnetUri
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(Color.parseColor("#E0E0E0"))
            setBackgroundColor(Color.parseColor("#161616"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            maxLines = 5
        }

        val copyBtn = TextView(this).apply {
            text = "[ ⎘ COPY MAGNET ]"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#00FF66"))
            setPadding(0, dp(10), 0, dp(4))
            setOnClickListener {
                smoothCopyText(magnetUri, "⎘ Magnet link copied")
                hidePalette()
            }
        }

        val pasteBtn = TextView(this).apply {
            text = "[ 📋 PASTE / REPLACE ]"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#F0F0F0"))
            setPadding(0, dp(6), 0, dp(4))
            setOnClickListener {
                applyResult(magnetUri)
            }
        }

        val openAppBtn = TextView(this).apply {
            text = "[ ⚡ OPEN IN TORRENT APP ]"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#CCCCCC"))
            setPadding(0, dp(6), 0, 0)
            setOnClickListener {
                smoothCopyText(magnetUri, "⎘ Opening magnet…")
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

        container.addView(makeRow("←", "BACK TO TOOLS", null) {
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
                setTextColor(Color.parseColor("#FF5555"))
            })
        }

        val caption = TextView(this).apply {
            text = content.take(64) + if (content.length > 64) "…" else ""
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Color.parseColor("#999999"))
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
        val rawTerm = selectedText.trim()
        val firstWord = rawTerm.split(Regex("\\s+")).firstOrNull()
            ?.trim { !it.isLetterOrDigit() && it != '-' }
            ?.ifBlank { rawTerm.take(32) }
            ?: rawTerm.take(32)

        container.addView(makeRow("←", "BACK TO TOOLS", null) {
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeHorizontalRule())

        val bodyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        val titleView = TextView(this).apply {
            text = "📖 ${firstWord.uppercase()}"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#00FF66"))
            setPadding(0, 0, 0, dp(6))
        }

        val defTextView = TextView(this).apply {
            text = "Looking up \"$firstWord\"…"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#E0E0E0"))
            setLineSpacing(0f, 1.18f)
        }

        val copyDefBtn = TextView(this).apply {
            text = "[ ⎘ COPY DEFINITION ]"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(Color.parseColor("#00FF66"))
            setPadding(0, dp(10), 0, 0)
            visibility = View.GONE
            setOnClickListener {
                smoothCopyText(defTextView.text.toString(), "⎘ Definition copied")
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
                setRequestProperty("User-Agent", "sel-android/1.7")
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
        val raw = selectedText.trim().take(400)

        container.addView(makeRow("←", "BACK TO TOOLS", null) {
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })
        container.addView(makeHorizontalRule())

        val bodyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        val titleView = TextView(this).apply {
            text = "🌐 TRANSLATE (→ EN)"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Color.parseColor("#00FF66"))
            setPadding(0, 0, 0, dp(6))
        }

        val transTextView = TextView(this).apply {
            text = "Translating…"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#E0E0E0"))
            setLineSpacing(0f, 1.18f)
        }

        val useBtn = TextView(this).apply {
            text = "[ ⎘ APPLY / COPY TRANSLATION ]"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(Color.parseColor("#00FF66"))
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

        container.addView(makeRow("←", "BACK TO TOOLS", null) {
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
                setTextColor(Color.parseColor("#888888"))
                setPadding(dp(12), dp(12), dp(12), dp(12))
            })
            return
        }

        matches.distinctBy { it.second }.take(12).forEach { (kind, item) ->
            container.addView(makeRow(kind.take(2), item.take(26), null) {
                applyResult(item)
            })
        }
    }

    // Inline Drag-and-Drop Reorder + Per-App Blacklist Toggle inside the popup
    private fun populateReorderView(container: LinearLayout) {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        container.addView(makeRow("←", "DONE (DRAG ≡ TO SORT)", null) {
            currentMode = PopupMode.ACTIONS
            renderPopupBody()
        })

        val mostUsedOn = ToolPrefs.isSortMostUsed(this)
        container.addView(
            makeRow(
                "★",
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
                    "⊘",
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

        val tools = ToolPrefs.getAllSortedTools(this)
        tools.forEach { tool ->
            val hidden = ToolPrefs.isToolHidden(this, tool.id)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(11), dp(12), dp(11))
                tag = tool.id
            }

            val dragHandle = TextView(this).apply {
                text = "≡"
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(Color.parseColor("#00FF66"))
                setPadding(dp(6), dp(2), dp(10), dp(2))
            }

            val label = TextView(this).apply {
                val cnt = ToolPrefs.getUseCount(this@SelAccessibilityService, tool.id)
                text = "${tool.glyph} ${tool.label}" + if (cnt > 0) " ($cnt)" else ""
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(Color.parseColor(if (hidden) "#555555" else "#E0E0E0"))
                isSingleLine = true
                this.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val visToggle = TextView(this).apply {
                text = if (hidden) "[ ]" else "[✓]"
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(Color.parseColor(if (hidden) "#666666" else "#00FF66"))
                setPadding(dp(6), dp(2), dp(2), dp(2))
                setOnClickListener {
                    ToolPrefs.setToolHidden(this@SelAccessibilityService, tool.id, !hidden)
                    renderPopupBody()
                }
            }

            row.addView(dragHandle)
            row.addView(label)
            row.addView(visToggle)
            ToolPrefs.attachDragHandle(dragHandle, row, dragList) {
                renderPopupBody()
            }
            dragList.addView(row)
        }

        container.addView(dragList)
    }

    private fun openIncognito(url: String) {
        val intent = Intent(this, IncognitoActivity::class.java).apply {
            putExtra("url", url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { startActivity(intent) }
        hidePalette()
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
        hidePalette()
    }

    /**
     * PlainTextSports.com-inspired boxed scoreboard row with generous vertical/horizontal padding
     * (`dp(14)` horizontal, `dp(12)` vertical, `dp(4)` vertical gap between items) so items never feel cramped.
     */
    private fun makeRow(
        glyph: String,
        label: String,
        countBadge: String?,
        faviconUrl: String? = null,
        onClick: () -> Unit
    ): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(46)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
                bottomMargin = dp(2)
            }
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    GradientDrawable().apply {
                        setColor(Color.parseColor("#1D2620"))
                        setStroke(dp(1), Color.parseColor("#00FF66"))
                        cornerRadius = dp(2).toFloat()
                    }
                )
                addState(
                    intArrayOf(),
                    GradientDrawable().apply {
                        setColor(Color.parseColor("#111111"))
                        setStroke(dp(1), Color.parseColor("#262626"))
                        cornerRadius = dp(2).toFloat()
                    }
                )
            }
            setOnClickListener {
                runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                onClick()
            }
        }

        val iconHolder = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            this.layoutParams = LinearLayout.LayoutParams(dp(30), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        if (!faviconUrl.isNullOrBlank() && FaviconManager.extractDomain(faviconUrl) != null) {
            val iconSize = dp(18)
            val favImg = ImageView(this).apply {
                this.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            FaviconManager.bindFavicon(this, favImg, faviconUrl, glyph, iconSize)
            iconHolder.addView(favImg)
        } else {
            val iconView = TextView(this).apply {
                text = glyph
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(Color.parseColor("#00FF66"))
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            iconHolder.addView(iconView)
        }

        val pipeSep = TextView(this).apply {
            text = "│"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, dp(10), 0)
        }

        val labelView = TextView(this).apply {
            text = label
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#F2F2F2"))
            isSingleLine = true
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        row.addView(iconHolder)
        row.addView(pipeSep)
        row.addView(labelView)

        if (!countBadge.isNullOrBlank()) {
            val badgeView = TextView(this).apply {
                text = "[$countBadge]"
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(Color.parseColor("#777777"))
                setPadding(dp(8), 0, 0, 0)
            }
            row.addView(badgeView)
        }

        return row
    }

    private fun makeHorizontalRule(thick: Boolean = false): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        return View(this).apply {
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (thick) dp(2) else dp(1)
            ).apply {
                if (!thick) {
                    topMargin = dp(4)
                    bottomMargin = dp(4)
                }
            }
            setBackgroundColor(Color.parseColor(if (thick) "#383838" else "#2A2A2A"))
        }
    }

    // Header Drag + Magnetic Edge-Snap on release (supports both Gravity.TOP and Gravity.BOTTOM)
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
                            .coerceIn(dp(6), (dm.widthPixels - rootView.width - dp(6)).coerceAtLeast(dp(6)))
                        params.y = (initialY + dy)
                            .coerceIn(dp(44), (dm.heightPixels - dp(84)).coerceAtLeast(dp(48)))
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
                // Save Undo snapshot before replacing in-place
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
            Toast.makeText(this, "⎘ Copied: ${newText.take(28)}", Toast.LENGTH_SHORT).show()
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
        statsLabelView = null
        reorderToggleBtn = null
        bodyContainer = null
        currentMode = PopupMode.ACTIONS
    }

    override fun onInterrupt() {
        hideScreenGrabOverlay()
        hidePalette()
    }
}
