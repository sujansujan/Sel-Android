package io.github.selclip

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SelAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: SelAccessibilityService? = null
    }

    private data class SelectionResult(
        val text: String,
        val node: AccessibilityNodeInfo?,
        val bounds: Rect
    )

    private lateinit var windowManager: WindowManager
    private lateinit var clipboardManager: ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var popupView: View? = null
    private var popupParams: WindowManager.LayoutParams? = null
    private var statsLabelView: TextView? = null
    private var actionsListLayout: LinearLayout? = null

    private var currentNode: AccessibilityNodeInfo? = null
    private var selectedText: String = ""
    private var lastClipboardTime = 0L
    private var lastShownTime = 0L
    private var activePackage: String = ""

    private val delayedHideRunnable = Runnable { hidePalette() }

    private val trackingRegex = Regex(
        "([?&])(utm_[a-z_]+|fbclid|gclid|igshid|mc_cid|mc_eid|si|ref|s)=[^&#]*",
        RegexOption.IGNORE_CASE
    )

    override fun onServiceConnected() {
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager

        clipboardManager.addPrimaryClipChangedListener {
            val now = System.currentTimeMillis()
            if (now - lastClipboardTime < 750) return@addPrimaryClipChangedListener
            readClipboardAndShowPalette()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        hidePalette()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        hidePalette()
        super.onDestroy()
    }

    fun showFromExternalText(text: String) {
        mainHandler.post {
            if (text.isNotBlank()) {
                mainHandler.removeCallbacks(delayedHideRunnable)
                selectedText = text
                currentNode = findSelectedOrFocusedNode(rootInActiveWindow)
                lastShownTime = System.currentTimeMillis()
                val dm = resources.displayMetrics
                showOrUpdatePalette(Rect(0, dm.heightPixels / 3, dm.widthPixels, dm.heightPixels / 2))
            }
        }
    }

    // Reads clipboard on Android 10-14 even from background by using a 1x1 temporary focus probe if needed
    private fun readClipboardAndShowPalette() {
        val directClip = runCatching {
            clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull()

        if (!directClip.isNullOrBlank()) {
            showFromExternalText(directClip)
            return
        }

        // Android 10+ restricts background clipboard access unless the service overlay briefly holds focus
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

        probe.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (hasFocus && !cleanedUp) {
                val clip = runCatching {
                    clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
                }.getOrNull()
                cleanup()
                if (!clip.isNullOrBlank()) {
                    showFromExternalText(clip)
                }
            }
        }

        runCatching {
            windowManager.addView(probe, probeParams)
            mainHandler.postDelayed({ cleanup() }, 180)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: ""

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val result = extractSelection(event)
                if (result != null) {
                    mainHandler.removeCallbacks(delayedHideRunnable)
                    selectedText = result.text
                    currentNode = result.node
                    activePackage = pkg
                    lastShownTime = System.currentTimeMillis()
                    showOrUpdatePalette(result.bounds)
                } else {
                    // Only hide if the same node explicitly collapsed its selection after the initial popup settled
                    val src = event.source
                    val elapsed = System.currentTimeMillis() - lastShownTime
                    if (popupView != null && elapsed > 650 && src != null && src == currentNode) {
                        val s = src.textSelectionStart
                        val e = src.textSelectionEnd
                        if (s >= 0 && s == e) {
                            mainHandler.postDelayed(delayedHideRunnable, 180)
                        }
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (popupView == null) return
                // Ignore our own overlay, system UI, keyboards, and floating ActionMode/Popup toolbars
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

    private fun extractSelection(event: AccessibilityEvent): SelectionResult? {
        val pkg = event.packageName?.toString() ?: ""
        // Ignore idle home screen launcher events unless the user is actively inside a focused input
        if (pkg.contains("launcher", ignoreCase = true) && event.source?.isFocused != true) {
            return null
        }

        val source = event.source
        if (source != null) {
            extractFromNode(source, event.fromIndex, event.toIndex)?.let { return it }
            findSelectionInTree(source, 0)?.let { return it }
        }

        // Traverse active window for Google Keep, Twitter/X, Jetpack Compose, and Chrome/WebViews
        findSelectionInTree(rootInActiveWindow, 0)?.let { return it }

        // Also search interactive windows (for split-screen, WebView layers, or custom overlays)
        runCatching {
            for (win in windows) {
                findSelectionInTree(win.root, 0)?.let { return it }
            }
        }

        // Fallback to event.text slice for virtual WebView / browser nodes
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

        // 1. Check node's own textSelectionStart / textSelectionEnd (handles right-to-left selection)
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

        // 2. Check AccessibilityEvent's fromIndex / toIndex
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
            val child = root.getChild(i) ?: continue
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
            val child = root.getChild(i) ?: continue
            val found = findSelectedOrFocusedNode(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun showOrUpdatePalette(bounds: Rect) {
        val dm = resources.displayMetrics
        val dp = { v: Int -> (v * dm.density).toInt() }

        val words = selectedText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        val chars = selectedText.length

        // If already on screen, update in-place to prevent WindowManager surface flicker
        if (popupView != null && statsLabelView != null && actionsListLayout != null) {
            statsLabelView?.text = "${words}W·${chars}C"
            populateActionRows(actionsListLayout!!)
            return
        }

        val windowParams = WindowManager.LayoutParams(
            dp(172),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = (bounds.bottom + dp(16)).coerceIn(dp(80), dm.heightPixels - dp(320))
        }

        val rootCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0A0A0A"))
                setStroke(dp(1), Color.parseColor("#383838"))
                cornerRadius = dp(2).toFloat()
            }
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    if (System.currentTimeMillis() - lastShownTime > 850) {
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
            this.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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
        header.addView(closeBtn)
        enableDragOnHeader(header, rootCard, windowParams)
        rootCard.addView(header)
        rootCard.addView(makeHorizontalRule())

        val scrollView = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxHeightSpec = MeasureSpec.makeMeasureSpec(dp(270), MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, maxHeightSpec)
            }
        }.apply {
            isVerticalScrollBarEnabled = false
        }

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        populateActionRows(list)

        scrollView.addView(list)
        rootCard.addView(scrollView)

        popupView = rootCard
        popupParams = windowParams
        statsLabelView = statsLabel
        actionsListLayout = list
        runCatching { windowManager.addView(popupView, windowParams) }
    }

    private fun populateActionRows(list: LinearLayout) {
        list.removeAllViews()

        // Context-Aware URL tools
        if (selectedText.contains("http://") || selectedText.contains("https://")) {
            list.addView(makeRow("⌫", "CLEAN URL") { applyResult(cleanUrl(selectedText)) })
            list.addView(makeRow("🔗", "MD LINK") { applyResult("[link](${selectedText.trim()})") })
        }

        // Context-Aware Math Evaluator
        evalSimpleMath(selectedText)?.let { ans ->
            list.addView(makeRow("∑", "= $ans") { applyResult(ans) })
        }

        // Built-in Plain-Text Actions
        list.addView(makeRow("⎘", "COPY TEXT") {
            lastClipboardTime = System.currentTimeMillis()
            clipboardManager.setPrimaryClip(ClipData.newPlainText("sel", selectedText))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            hidePalette()
        })
        list.addView(makeRow("Aa", "CYCLE CASE") { applyResult(cycleCase(selectedText)) })
        list.addView(makeRow("❝", "MD QUOTE") {
            applyResult(selectedText.lines().joinToString("\n") { "> $it" })
        })
        list.addView(makeRow("⟨⟩", "CODE BLOCK") {
            val wrapped = if (selectedText.contains("\n")) "```\n$selectedText\n```" else "`$selectedText`"
            applyResult(wrapped)
        })
        list.addView(makeRow("⇅", "SORT LINES") {
            val sorted = selectedText.lines().filter { it.isNotBlank() }.distinct().sorted().joinToString("\n")
            applyResult(sorted)
        })
        list.addView(makeRow("⊕", "APPEND LOG") {
            appendToScratchpad(selectedText)
            Toast.makeText(this, "Appended to log", Toast.LENGTH_SHORT).show()
            hidePalette()
        })
        list.addView(makeRow("↗", "WEB SEARCH") {
            openUrl("https://duckduckgo.com/?q=${Uri.encode(selectedText.trim())}")
        })

        // Load user-defined plain-text rules from rules.txt
        loadCustomRules(list)
    }

    // Format: GLYPH | LABEL | URL_TEMPLATE
    private fun loadCustomRules(list: LinearLayout) {
        val rulesFile = File(filesDir, "rules.txt")
        if (!rulesFile.exists()) return
        rulesFile.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val parts = trimmed.split("|").map { it.trim() }
            if (parts.size >= 3) {
                val glyph = parts[0].take(2)
                val label = parts[1].uppercase()
                val target = parts[2]
                list.addView(makeRow(glyph, label) {
                    if (target.startsWith("http")) {
                        openUrl(target.replace("{text}", Uri.encode(selectedText.trim())))
                    } else {
                        applyResult(target.replace("{text}", selectedText))
                    }
                })
            }
        }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
        hidePalette()
    }

    private fun makeRow(glyph: String, label: String, onClick: () -> Unit): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
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
        }

        row.addView(iconView)
        row.addView(labelView)
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
            when (event.action) {
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
                    runCatching { windowManager.updateViewLayout(rootView, params) }
                    true
                }
                else -> false
            }
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
                replaced = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } else {
                replaced = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
        }
        if (!replaced) {
            Toast.makeText(this, "Copied: ${newText.take(28)}", Toast.LENGTH_SHORT).show()
        }
        hidePalette()
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

    private fun appendToScratchpad(text: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        File(filesDir, "scratchpad.txt").appendText("[$stamp]\n${text.trim()}\n\n")
    }

    private fun hidePalette() {
        mainHandler.removeCallbacks(delayedHideRunnable)
        popupView?.let {
            runCatching { windowManager.removeView(it) }
        }
        popupView = null
        popupParams = null
        statsLabelView = null
        actionsListLayout = null
    }

    override fun onInterrupt() { hidePalette() }
}
