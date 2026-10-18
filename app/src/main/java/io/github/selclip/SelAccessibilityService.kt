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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SelAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var clipboardManager: ClipboardManager
    private var popupView: View? = null
    private var currentNode: AccessibilityNodeInfo? = null
    private var selectedText: String = ""
    private var lastClipboardTime = 0L

    private val trackingRegex = Regex(
        "([?&])(utm_[a-z_]+|fbclid|gclid|igshid|mc_cid|mc_eid|si|ref|s)=[^&#]*",
        RegexOption.IGNORE_CASE
    )

    override fun onServiceConnected() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager

        clipboardManager.addPrimaryClipChangedListener {
            val now = System.currentTimeMillis()
            if (now - lastClipboardTime < 600) return@addPrimaryClipChangedListener
            val clip = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
                ?: return@addPrimaryClipChangedListener
            if (clip.isNotBlank() && popupView == null) {
                selectedText = clip
                currentNode = null
                showVerticalPalette(Rect(0, 500, 1000, 600))
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val source = event.source ?: return
                val start = event.fromIndex
                val end = event.toIndex
                val rawText = source.text?.toString() ?: ""

                if (start >= 0 && end > start && end <= rawText.length) {
                    selectedText = rawText.substring(start, end)
                    currentNode = source
                    val bounds = Rect()
                    source.getBoundsInScreen(bounds)
                    showVerticalPalette(bounds)
                } else {
                    hidePalette()
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> hidePalette()
        }
    }

    private fun showVerticalPalette(bounds: Rect) {
        hidePalette()
        val dm = resources.displayMetrics
        val dp = { v: Int -> (v * dm.density).toInt() }

        val layoutParams = WindowManager.LayoutParams(
            dp(172),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
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
        }

        // Top TUI Bar: Live Word/Char count + Drag Handle + [×]
        val words = selectedText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        val chars = selectedText.length
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
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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
        enableDragOnHeader(header, rootCard, layoutParams)
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
            hidePalette()
        })
        list.addView(makeRow("↗", "WEB SEARCH") {
            openUrl("https://duckduckgo.com/?q=${Uri.encode(selectedText.trim())}")
        })

        // Load user-defined plain-text rules from rules.txt
        loadCustomRules(list)

        scrollView.addView(list)
        rootCard.addView(scrollView)

        popupView = rootCard
        runCatching { windowManager.addView(popupView, layoutParams) }
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
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
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

        val node = currentNode
        if (node != null && node.isEditable) {
            val full = node.text?.toString() ?: ""
            val start = node.textSelectionStart
            val end = node.textSelectionEnd
            if (start >= 0 && end > start && end <= full.length) {
                val updated = full.replaceRange(start, end, newText)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
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
        popupView?.let {
            runCatching { windowManager.removeView(it) }
            popupView = null
        }
    }

    override fun onInterrupt() { hidePalette() }
}
