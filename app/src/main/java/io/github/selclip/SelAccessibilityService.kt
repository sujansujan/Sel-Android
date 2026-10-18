package io.github.selclip

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.FrameLayout
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

    private data class ScreenTextBlock(
        val text: String,
        val bounds: Rect
    )

    private lateinit var windowManager: WindowManager
    private lateinit var clipboardManager: ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var popupView: View? = null
    private var popupParams: WindowManager.LayoutParams? = null
    private var statsLabelView: TextView? = null
    private var reorderToggleBtn: TextView? = null
    private var bodyContainer: LinearLayout? = null

    private var screenGrabOverlayView: View? = null

    private var currentMode: PopupMode = PopupMode.ACTIONS
    private var currentNode: AccessibilityNodeInfo? = null
    private var selectedText: String = ""
    private var lastClipboardTime = 0L
    private var lastWebAutoCopyTime = 0L
    private var lastShownTime = 0L
    private var activePackage: String = ""

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

        clipboardManager.addPrimaryClipChangedListener {
            val now = System.currentTimeMillis()
            if (now - lastClipboardTime < 650) return@addPrimaryClipChangedListener
            if (screenGrabOverlayView != null) return@addPrimaryClipChangedListener
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
                mainHandler.removeCallbacks(delayedHideRunnable)
                selectedText = text
                currentMode = PopupMode.ACTIONS
                currentNode = findSelectedOrFocusedNode(rootInActiveWindow)
                lastShownTime = System.currentTimeMillis()
                val dm = resources.displayMetrics
                showOrUpdatePalette(Rect(0, dm.heightPixels / 3, dm.widthPixels, dm.heightPixels / 2))
            }
        }
    }

    /**
     * Universal Screen Text Extraction Mode:
     * Harvests all visible text nodes across the screen even when android:textIsSelectable="false".
     * Can be triggered via 3rd-party Intent (io.github.selclip.ACTION_GRAB_SCREEN), Quick Settings Tile, or inside [sel].
     */
    fun triggerScreenGrab(delayMs: Long = 150L) {
        hidePalette()
        hideScreenGrabOverlay()
        mainHandler.postDelayed({
            val blocks = harvestAllScreenText()
            if (blocks.isEmpty()) {
                Toast.makeText(this, "[sel] No readable text nodes found on screen", Toast.LENGTH_SHORT).show()
            } else {
                showScreenGrabOverlay(blocks)
            }
        }, delayMs)
    }

    private fun harvestAllScreenText(): List<ScreenTextBlock> {
        val collected = mutableListOf<ScreenTextBlock>()
        val seen = HashSet<String>()
        val dm = resources.displayMetrics

        val visitRoot = { root: AccessibilityNodeInfo? ->
            collectTextBlocksRecursive(root, 0, dm.widthPixels, dm.heightPixels, collected, seen)
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

        return collected.sortedWith(compareBy<ScreenTextBlock> { it.bounds.top }.thenBy { it.bounds.left })
    }

    private fun collectTextBlocksRecursive(
        node: AccessibilityNodeInfo?,
        depth: Int,
        screenW: Int,
        screenH: Int,
        out: MutableList<ScreenTextBlock>,
        seen: HashSet<String>
    ) {
        if (node == null || depth > 22) return
        if (node.isVisibleToUser) {
            val rawText = node.text?.toString()?.trim()
            val descText = node.contentDescription?.toString()?.trim()
            val candidate = when {
                !rawText.isNullOrBlank() -> rawText
                !descText.isNullOrBlank() && descText.length > 2 -> descText
                else -> null
            }

            if (!candidate.isNullOrBlank()) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 16 && rect.height() > 12 &&
                    rect.bottom > 0 && rect.top < screenH &&
                    rect.right > 0 && rect.left < screenW
                ) {
                    val key = "${candidate}_${rect.top / 24}_${rect.left / 24}"
                    if (seen.add(key)) {
                        out.add(ScreenTextBlock(candidate, rect))
                    }
                }
            }
        }

        val count = node.childCount
        for (i in 0 until count) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collectTextBlocksRecursive(child, depth + 1, screenW, screenH, out, seen)
        }
    }

    private fun showScreenGrabOverlay(blocks: List<ScreenTextBlock>) {
        hideScreenGrabOverlay()
        val dm = resources.displayMetrics
        val dp = { v: Int -> (v * dm.density).toInt() }

        // Focusable full-screen overlay so the built-in EditText inspector allows arbitrary word/substring selection
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
        }

        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#88000000"))
        }

        // Layer 1: Interactive X-Ray Bounding Boxes positioned over every detected text node on screen
        val boxesLayer = FrameLayout(this).apply {
            this.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Inspector card at the bottom where you can highlight specific words/substrings from any tapped box
        val inspectorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0D0D0D"))
                setStroke(dp(1), Color.parseColor("#00FF66"))
                cornerRadius = dp(4).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(12))
            this.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                setMargins(dp(10), dp(10), dp(10), dp(24))
            }
        }

        val inspectorHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }

        val inspectorTitle = TextView(this).apply {
            text = "⊞ X-RAY SCREEN GRAB (${blocks.size} BLOCKS) — TAP ANY BOX"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(Color.parseColor("#00FF66"))
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val closeGrabBtn = TextView(this).apply {
            text = "[× ESC]"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Color.parseColor("#FF5555"))
            setPadding(dp(8), dp(2), 0, dp(2))
            setOnClickListener { hideScreenGrabOverlay() }
        }

        inspectorHeader.addView(inspectorTitle)
        inspectorHeader.addView(closeGrabBtn)
        inspectorCard.addView(inspectorHeader)

        val inspectorEdit = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#F0F0F0"))
            setBackgroundColor(Color.parseColor("#181818"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            minLines = 2
            maxLines = 5
            setTextIsSelectable(true)
            setText(blocks.firstOrNull()?.text ?: "")
        }
        inspectorCard.addView(inspectorEdit)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        val getTargetSubstring = {
            val full = inspectorEdit.text?.toString() ?: ""
            val s = minOf(inspectorEdit.selectionStart, inspectorEdit.selectionEnd)
            val e = maxOf(inspectorEdit.selectionStart, inspectorEdit.selectionEnd)
            if (s >= 0 && e > s && e <= full.length) {
                full.substring(s, e)
            } else {
                full
            }
        }

        val openPaletteBtn = TextView(this).apply {
            text = "[ ⚡ OPEN IN [sel] ]"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#00FF66"))
            setPadding(0, dp(4), dp(12), dp(4))
            setOnClickListener {
                val chosen = getTargetSubstring().trim()
                hideScreenGrabOverlay()
                if (chosen.isNotEmpty()) {
                    showFromExternalText(chosen)
                }
            }
        }

        val copyGrabBtn = TextView(this).apply {
            text = "[ ⎘ COPY ]"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#E0E0E0"))
            setPadding(0, dp(4), dp(12), dp(4))
            setOnClickListener {
                val chosen = getTargetSubstring().trim()
                if (chosen.isNotEmpty()) {
                    lastClipboardTime = System.currentTimeMillis()
                    clipboardManager.setPrimaryClip(ClipData.newPlainText("sel_grab", chosen))
                    Toast.makeText(this@SelAccessibilityService, "Copied (${chosen.length} chars)", Toast.LENGTH_SHORT).show()
                }
                hideScreenGrabOverlay()
            }
        }

        val allTextBtn = TextView(this).apply {
            text = "[ ☰ ALL TEXT ]"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener {
                val combined = blocks.map { it.text }.distinct().joinToString("\n\n")
                inspectorEdit.setText(combined)
                inspectorTitle.text = "⊞ ALL SCREEN TEXT (SELECT PART OR USE ALL)"
            }
        }

        actionRow.addView(openPaletteBtn)
        actionRow.addView(copyGrabBtn)
        actionRow.addView(allTextBtn)
        inspectorCard.addView(actionRow)

        // Populate clickable green bounding boxes on screen
        blocks.take(65).forEach { block ->
            val b = block.bounds
            val w = b.width().coerceAtLeast(dp(24))
            val h = b.height().coerceAtLeast(dp(20))
            val boxView = TextView(this).apply {
                text = block.text
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                setTextColor(Color.parseColor("#E6FFFFFF"))
                maxLines = 2
                setPadding(dp(3), dp(1), dp(3), dp(1))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#44002211"))
                    setStroke(dp(1), Color.parseColor("#00FF66"))
                    cornerRadius = dp(2).toFloat()
                }
                setOnClickListener {
                    inspectorEdit.setText(block.text)
                    inspectorEdit.setSelection(0, block.text.length)
                    inspectorTitle.text = "⊞ SELECTED BLOCK (HIGHLIGHT WORDS OR TAP OPEN)"
                }
            }
            val lp = FrameLayout.LayoutParams(w, h).apply {
                leftMargin = b.left.coerceIn(0, (dm.widthPixels - dp(28)).coerceAtLeast(0))
                topMargin = b.top.coerceIn(0, (dm.heightPixels - dp(160)).coerceAtLeast(0))
            }
            boxesLayer.addView(boxView, lp)
        }

        rootFrame.addView(boxesLayer)
        rootFrame.addView(inspectorCard)

        screenGrabOverlayView = rootFrame
        runCatching { windowManager.addView(screenGrabOverlayView, params) }
    }

    private fun hideScreenGrabOverlay() {
        screenGrabOverlayView?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        screenGrabOverlayView = null
    }

    private fun readClipboardAndShowPalette() {
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
                val clip = runCatching {
                    clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
                }.getOrNull()
                if (!clip.isNullOrBlank()) {
                    cleanup()
                    showFromExternalText(clip)
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
            mainHandler.postDelayed({ tryRead() }, 35)
            mainHandler.postDelayed({ cleanup() }, 160)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (screenGrabOverlayView != null) return
        val pkg = event.packageName?.toString() ?: ""

        when (event.eventType) {
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
                    if (changedText && currentMode != PopupMode.ACTIONS && currentMode != PopupMode.REORDER) {
                        currentMode = PopupMode.ACTIONS
                    }
                    showOrUpdatePalette(result.bounds)
                } else if (event.fromIndex >= 0 && event.toIndex >= 0 && event.fromIndex != event.toIndex) {
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
                    if (now - lastWebAutoCopyTime > 900 && hasActiveSelectionToolbar()) {
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
            p.contains("twitter")
    }

    private fun hasActiveSelectionToolbar(): Boolean {
        val root = rootInActiveWindow ?: return false
        val copyNodes = runCatching { root.findAccessibilityNodeInfosByText("Copy") }.getOrNull()
        if (!copyNodes.isNullOrEmpty() && copyNodes.any { it.isVisibleToUser && it.isClickable }) {
            return true
        }
        return false
    }

    private fun scheduleBrowserWebViewExtract(sourceNode: AccessibilityNodeInfo?, pkg: String) {
        pendingWebCopyRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            val now = System.currentTimeMillis()
            if (now - lastWebAutoCopyTime < 650) return@Runnable
            activePackage = pkg

            val treeResult = findSelectionInTree(rootInActiveWindow, 0)
            if (treeResult != null) {
                selectedText = treeResult.text
                currentNode = treeResult.node
                lastShownTime = System.currentTimeMillis()
                showOrUpdatePalette(treeResult.bounds)
                return@Runnable
            }

            lastWebAutoCopyTime = now
            var copied = false
            if (sourceNode != null) {
                copied = runCatching { sourceNode.performAction(AccessibilityNodeInfo.ACTION_COPY) }.getOrDefault(false)
            }
            if (!copied) {
                copied = performCopyInTree(rootInActiveWindow, 0)
            }
            if (copied) {
                mainHandler.postDelayed({ readClipboardAndShowPalette() }, 45)
            }
        }
        pendingWebCopyRunnable = runnable
        mainHandler.postDelayed(runnable, 140)
    }

    private fun performCopyInTree(root: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (root == null || depth > 14) return false
        val cls = root.className?.toString() ?: ""
        if (cls.contains("WebView", ignoreCase = true) || root.isFocused) {
            if (runCatching { root.performAction(AccessibilityNodeInfo.ACTION_COPY) }.getOrDefault(false)) return true
        }
        val count = root.childCount
        for (i in 0 until count) {
            val child = runCatching { root.getChild(i) }.getOrNull() ?: continue
            if (performCopyInTree(child, depth + 1)) return true
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
            extractFromNode(source, event.fromIndex, event.toIndex)?.let { return it }
            findSelectionInTree(source, 0)?.let { return it }
        }

        findSelectionInTree(rootInActiveWindow, 0)?.let { return it }

        runCatching {
            for (win in windows) {
                findSelectionInTree(win.root, 0)?.let { return it }
            }
        }

        val eventText = event.text.joinToString("")
        if (eventText.isNotBlank()) {
            if (event.fromIndex >= 0 && event.toIndex >= 0 && event.fromIndex != event.toIndex) {
                val start = minOf(event.fromIndex, event.toIndex)
                val end = maxOf(event.fromIndex, event.toIndex)
                if (end <= eventText.length) {
                    val sub = eventText.substring(start, end)
                    if (sub.isNotBlank()) {
                        val rect = Rect(0, 400, 1000, 600)
                        source?.getBoundsInScreen(rect)
                        return SelectionResult(sub, source, rect)
                    }
                }
            }
        }

        return null
    }

    private fun extractFromNode(
        node: AccessibilityNodeInfo,
        evFrom: Int = -1,
        evTo: Int = -1
    ): SelectionResult? {
        val raw = node.text?.toString() ?: ""
        val hint = node.hintText?.toString() ?: ""
        if (raw.isEmpty() || (hint.isNotEmpty() && raw == hint)) return null

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) {
            rect.set(0, 400, 1000, 600)
        }

        val ns = node.textSelectionStart
        val ne = node.textSelectionEnd
        if (ns >= 0 && ne >= 0 && ns != ne) {
            val start = minOf(ns, ne)
            val end = maxOf(ns, ne)
            if (end <= raw.length) {
                val sub = raw.substring(start, end)
                if (sub.isNotBlank()) {
                    return SelectionResult(sub, node, rect)
                }
            }
        }

        if (evFrom >= 0 && evTo >= 0 && evFrom != evTo) {
            val start = minOf(evFrom, evTo)
            val end = maxOf(evFrom, evTo)
            if (end <= raw.length) {
                val sub = raw.substring(start, end)
                if (sub.isNotBlank()) {
                    return SelectionResult(sub, node, rect)
                }
            }
        }

        return null
    }

    private fun findSelectionInTree(root: AccessibilityNodeInfo?, depth: Int): SelectionResult? {
        if (root == null || depth > 16) return null
        extractFromNode(root)?.let { return it }
        val count = root.childCount
        for (i in 0 until count) {
            val child = runCatching { root.getChild(i) }.getOrNull() ?: continue
            val res = findSelectionInTree(child, depth + 1)
            if (res != null) return res
        }
        return null
    }

    private fun findSelectedOrFocusedNode(root: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (root == null || depth > 16) return null
        if (root.isEditable && (root.isFocused || root.textSelectionStart != root.textSelectionEnd)) {
            return root
        }
        val count = root.childCount
        for (i in 0 until count) {
            val child = runCatching { root.getChild(i) }.getOrNull() ?: continue
            val found = findSelectedOrFocusedNode(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    // Dynamic autosizing vertical palette
    private fun showOrUpdatePalette(bounds: Rect) {
        val dm = resources.displayMetrics
        val dp = { v: Int -> (v * dm.density).toInt() }

        val words = selectedText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        val chars = selectedText.length

        if (popupView != null && statsLabelView != null && bodyContainer != null) {
            statsLabelView?.text = "${words}W·${chars}C"
            renderPopupBody()
            return
        }

        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = (bounds.bottom + dp(14)).coerceIn(dp(72), (dm.heightPixels - dp(340)).coerceAtLeast(dp(80)))
        }

        val rootCard = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxW = if (currentMode != PopupMode.ACTIONS) {
                    dp(268)
                } else {
                    dp(224)
                }
                val cappedWidthSpec = MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST)
                super.onMeasure(cappedWidthSpec, heightMeasureSpec)
                val minW = dp(164)
                if (measuredWidth < minW) {
                    super.onMeasure(MeasureSpec.makeMeasureSpec(minW, MeasureSpec.EXACTLY), heightMeasureSpec)
                }
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0A0A0A"))
                setStroke(dp(1), Color.parseColor("#383838"))
                cornerRadius = dp(3).toFloat()
            }
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    if (System.currentTimeMillis() - lastShownTime > 850 && currentMode == PopupMode.ACTIONS) {
                        mainHandler.removeCallbacks(delayedHideRunnable)
                        mainHandler.postDelayed({
                            val activeNode = currentNode
                            val stillSelected = if (activeNode != null) {
                                runCatching { activeNode.refresh() }
                                val s = activeNode.textSelectionStart
                                val e = activeNode.textSelectionEnd
                                s >= 0 && e >= 0 && s != e
                            } else {
                                false
                            }
                            if (!stillSelected) {
                                hidePalette()
                            }
                        }, 320)
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
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(dp(10), dp(6), dp(6), dp(6))
        }

        val statsLabel = TextView(this).apply {
            text = "${words}W·${chars}C"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(Color.parseColor("#777777"))
            setPadding(0, 0, dp(8), 0)
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val reorderBtn = TextView(this).apply {
            text = "⇅"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(Color.parseColor("#888888"))
            setPadding(dp(6), 0, dp(6), 0)
            setOnClickListener {
                lastShownTime = System.currentTimeMillis()
                currentMode = if (currentMode == PopupMode.REORDER) PopupMode.ACTIONS else PopupMode.REORDER
                renderPopupBody()
            }
        }

        val closeBtn = TextView(this).apply {
            text = "×"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor("#888888"))
            setPadding(dp(6), 0, dp(4), 0)
            setOnClickListener { hidePalette() }
        }

        header.addView(statsLabel)
        header.addView(reorderBtn)
        header.addView(closeBtn)
        enableDragOnHeader(header, rootCard, windowParams)
        rootCard.addView(header)
        rootCard.addView(makeHorizontalRule())

        val maxScrollHeight = (dm.heightPixels * 0.52f).toInt().coerceIn(dp(220), dp(420))
        val scrollView = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxHeightSpec = MeasureSpec.makeMeasureSpec(maxScrollHeight, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, maxHeightSpec)
            }
        }.apply {
            isVerticalScrollBarEnabled = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        scrollView.addView(container)
        rootCard.addView(scrollView)

        popupView = rootCard
        popupParams = windowParams
        statsLabelView = statsLabel
        reorderToggleBtn = reorderBtn
        bodyContainer = container

        renderPopupBody()
        runCatching { windowManager.addView(popupView, windowParams) }
    }

    private fun renderPopupBody() {
        val container = bodyContainer ?: return
        container.removeAllViews()

        reorderToggleBtn?.setTextColor(
            Color.parseColor(if (currentMode == PopupMode.REORDER) "#00FF66" else "#888888")
        )

        when (currentMode) {
            PopupMode.ACTIONS -> populateActionRows(container)
            PopupMode.QR_CODE -> populateQrView(container)
            PopupMode.MAGNET -> populateMagnetView(container)
            PopupMode.DEFINE_WORD -> populateDefineView(container)
            PopupMode.TRANSLATE -> populateTranslateView(container)
            PopupMode.EXTRACT -> populateExtractView(container)
            PopupMode.REORDER -> populateReorderView(container)
        }

        popupView?.requestLayout()
        val view = popupView
        val params = popupParams
        if (view != null && params != null && view.isAttachedToWindow) {
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun populateActionRows(list: LinearLayout) {
        val trimmed = selectedText.trim()

        // 1. Contextual URL tools
        if (trimmed.contains("http://") || trimmed.contains("https://") || urlPattern.matches(trimmed)) {
            list.addView(makeRow("⌫", "CLEAN URL", null) {
                ToolPrefs.recordUse(this, "clean_url")
                applyResult(cleanUrl(selectedText))
            })
            list.addView(makeRow("🔗", "MD LINK", null) {
                ToolPrefs.recordUse(this, "md_link")
                applyResult("[link]($trimmed)")
            })
        }

        // 2. Contextual Math Evaluator
        evalSimpleMath(selectedText)?.let { ans ->
            list.addView(makeRow("∑", "= $ans", null) {
                ToolPrefs.recordUse(this, "math")
                applyResult(ans)
            })
        }

        // 3. Contextual Torrent InfoHash -> Opens in-popup Magnet View with Copy & Paste options
        if (extractTorrentHash(selectedText) != null) {
            list.addView(makeRow("🧲", "HASH → MAGNET", null) {
                ToolPrefs.recordUse(this, "magnet")
                lastShownTime = System.currentTimeMillis()
                currentMode = PopupMode.MAGNET
                renderPopupBody()
            })
        }

        // 4. Contextual Unit & Hex/Dec Converter
        evalUnitConversion(selectedText)?.let { conv ->
            list.addView(makeRow("⇄", conv, null) {
                ToolPrefs.recordUse(this, "unit_conv")
                applyResult(conv.removePrefix("= ").trim())
            })
        }

        // 5. Reorderable & Most-Used-on-Top Tools
        val orderedTools = ToolPrefs.getSortedToolsForPopup(this)
        val showCounts = ToolPrefs.isSortMostUsed(this)
        for (tool in orderedTools) {
            val count = ToolPrefs.getUseCount(this, tool.id)
            val badge = if (showCounts && count > 0) "$count" else null
            list.addView(makeRow(tool.glyph, tool.label, badge) {
                ToolPrefs.recordUse(this, tool.id)
                executeTool(tool)
            })
        }
    }

    private fun executeTool(tool: ToolSpec) {
        val trimmed = selectedText.trim()
        val encoded = Uri.encode(trimmed)

        if (tool.customTarget != null) {
            val target = tool.customTarget
            if (target.startsWith("http")) {
                openUrl(target.replace("{text}", encoded))
            } else {
                applyResult(target.replace("{text}", selectedText))
            }
            return
        }

        when (tool.id) {
            "copy" -> {
                lastClipboardTime = System.currentTimeMillis()
                clipboardManager.setPrimaryClip(ClipData.newPlainText("sel", selectedText))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                hidePalette()
            }
            "screen_grab" -> {
                triggerScreenGrab(delayMs = 120L)
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
            "search" -> openUrl("https://duckduckgo.com/?q=$encoded")
            "yt_all" -> openUrl("https://www.youtube.com/results?search_query=$encoded")
            "yt_today" -> openUrl("https://www.youtube.com/results?search_query=$encoded&sp=EgIIAg%3D%3D")
            "yt_week" -> openUrl("https://www.youtube.com/results?search_query=$encoded&sp=EgIIAw%3D%3D")
            "yt_month" -> openUrl("https://www.youtube.com/results?search_query=$encoded&sp=EgIIBA%3D%3D")
            "case" -> applyResult(cycleCase(selectedText))
            "quote" -> applyResult(selectedText.lines().joinToString("\n") { "> $it" })
            "code" -> {
                val wrapped = if (selectedText.contains("\n")) "```\n$selectedText\n```" else "`$selectedText`"
                applyResult(wrapped)
            }
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

    // Inline Torrent Hash -> Magnet result view inside the popup with Copy, Paste/Replace, and Open options
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
                lastClipboardTime = System.currentTimeMillis()
                clipboardManager.setPrimaryClip(ClipData.newPlainText("sel_magnet", magnetUri))
                Toast.makeText(this@SelAccessibilityService, "Magnet link copied", Toast.LENGTH_SHORT).show()
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
                lastClipboardTime = System.currentTimeMillis()
                clipboardManager.setPrimaryClip(ClipData.newPlainText("sel_magnet", magnetUri))
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
                lastClipboardTime = System.currentTimeMillis()
                clipboardManager.setPrimaryClip(ClipData.newPlainText("sel_def", defTextView.text.toString()))
                Toast.makeText(this@SelAccessibilityService, "Definition copied", Toast.LENGTH_SHORT).show()
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
                setRequestProperty("User-Agent", "sel-android/1.5")
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

    // Inline Entity Extractor (URLs, Emails, Numbers, Handles) inside the popup
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

    // Inline Drag-and-Drop Reorder & Show/Hide view inside the popup
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
                setPadding(dp(6), dp(7), dp(8), dp(7))
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

    private fun makeRow(glyph: String, label: String, countBadge: String?, onClick: () -> Unit): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    GradientDrawable().apply { setColor(Color.parseColor("#242424")) }
                )
                addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            }
            setOnClickListener { onClick() }
        }

        val iconView = TextView(this).apply {
            text = glyph
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#00FF66"))
            width = dp(28)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }

        val labelView = TextView(this).apply {
            text = label
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Color.parseColor("#F0F0F0"))
            isSingleLine = true
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        row.addView(iconView)
        row.addView(labelView)

        if (!countBadge.isNullOrBlank()) {
            val badgeView = TextView(this).apply {
                text = "·$countBadge"
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                setTextColor(Color.parseColor("#666666"))
                setPadding(dp(6), 0, 0, 0)
            }
            row.addView(badgeView)
        }

        return row
    }

    private fun makeHorizontalRule(): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        return View(this).apply {
            this.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(Color.parseColor("#2A2A2A"))
        }
    }

    private fun enableDragOnHeader(header: View, rootView: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        header.setOnTouchListener { _, event ->
            runCatching {
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
                        params.x = initialX - (event.rawX - touchX).toInt()
                        params.y = initialY + (event.rawY - touchY).toInt()
                        if (rootView.isAttachedToWindow) {
                            windowManager.updateViewLayout(rootView, params)
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

        var replaced = false
        val node = currentNode
        if (node != null && node.isEditable) {
            val full = node.text?.toString() ?: ""
            val s = node.textSelectionStart
            val e = node.textSelectionEnd
            val start = minOf(s, e)
            val end = maxOf(s, e)
            if (start >= 0 && end > start && end <= full.length) {
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
