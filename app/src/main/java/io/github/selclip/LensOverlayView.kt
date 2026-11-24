package io.github.selclip

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Google Lens-style on-screen text selection overlay:
 * - Freezes the screen cleanly with a brief scan shimmer while on-device ML Kit OCR runs.
 * - Highlights every detected text line with soft translucent Google Lens pills.
 * - Tap any word to select, double-tap for paragraph, drag across words to sweep-select,
 *   and drag left/right teardrop handles with a floating loupe preview above your finger.
 * - Clean Material-style rounded bottom sheet with Copy text, Smart Action, Search,
 *   inline Translate, Listen, More tools, and inline Edit.
 */
class LensOverlayView(
    context: Context,
    private val backgroundBitmap: Bitmap?,
    initialSnapshot: LensSnapshot?,
    private val networkTools: NetworkTools,
    private val onCopyText: (String) -> Unit,
    private val onSmartAction: (String, String) -> Unit,
    private val onOpenInTools: (String) -> Unit,
    private val onSearchText: (String) -> Unit,
    private val onListenText: (String) -> Unit,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density
    private fun dpInt(v: Int): Int = (v * density).toInt()
    private val isDark = ToolPrefs.resolvePalette(context).isDark

    // Modern Google Lens palette (crisp Material You dark / light surfaces + Google Blue selection)
    private val colorSurface = if (isDark) Color.parseColor("#202124") else Color.parseColor("#FFFFFF")
    private val colorSurfaceElev = if (isDark) Color.parseColor("#303134") else Color.parseColor("#F1F3F4")
    private val colorSurfacePressed = if (isDark) Color.parseColor("#3C4043") else Color.parseColor("#E0E3E7")
    private val colorBorder = if (isDark) Color.parseColor("#3C4043") else Color.parseColor("#DADCE0")
    private val colorTextPrimary = if (isDark) Color.parseColor("#E8EAED") else Color.parseColor("#202124")
    private val colorTextMuted = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
    private val colorAccent = if (isDark) Color.parseColor("#8AB4F8") else Color.parseColor("#1A73E8")
    private val colorOnAccent = if (isDark) Color.parseColor("#17191C") else Color.parseColor("#FFFFFF")
    private val colorHighlightFill = if (isDark) Color.argb(105, 138, 180, 248) else Color.argb(92, 26, 115, 232)
    private val colorHandle = if (isDark) Color.parseColor("#8AB4F8") else Color.parseColor("#1A73E8")
    private val colorPillFrostedBg = Color.argb(188, 24, 25, 28)

    private var words: List<LensWord> = emptyList()
    private var lines: List<LensLine> = emptyList()
    private var quickEntities: List<ExtractedEntity> = emptyList()
    private var isScanning: Boolean = initialSnapshot == null

    private var selStart: Int = -1
    private var selEnd: Int = -1
    private var editedOverride: String? = null
    private var isEditingInline: Boolean = false
    private var translatedText: String? = null

    private var topInsetPx: Int = dpInt(38)
    private var bottomInsetPx: Int = dpInt(24)

    // UI Components
    private lateinit var lensCanvas: LensCanvasView
    private lateinit var topControls: FrameLayout
    private lateinit var selectAllPill: TextView
    private lateinit var bottomSheet: LinearLayout
    private lateinit var idleContainer: LinearLayout
    private lateinit var idleHintLabel: TextView
    private lateinit var quickChipsScroll: HorizontalScrollView
    private lateinit var quickChipsRow: LinearLayout

    private lateinit var selectedContainer: LinearLayout
    private lateinit var previewRow: LinearLayout
    private lateinit var previewLabel: TextView
    private lateinit var editToggleBtn: TextView
    private lateinit var inlineEditorBox: LinearLayout
    private lateinit var inlineEditText: EditText
    private lateinit var translateBanner: LinearLayout
    private lateinit var translateResultLabel: TextView
    private lateinit var actionPillsRow: LinearLayout

    private var cachedSelectedText: String = ""

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        buildUi()
        if (initialSnapshot != null) {
            applySnapshot(initialSnapshot)
        } else {
            lensCanvas.startScanShimmer()
        }
    }

    fun setSnapshot(snapshot: LensSnapshot) {
        post {
            applySnapshot(snapshot)
        }
    }

    private fun applySnapshot(snapshot: LensSnapshot) {
        isScanning = false
        words = snapshot.words
        lines = snapshot.lines
        quickEntities = snapshot.quickEntities
        lensCanvas.stopScanShimmer()
        rebuildQuickEntityChips()
        updateUiForSelection()
        lensCanvas.invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        lensCanvas.stopScanShimmer()
        networkTools.cancelPending()
        backgroundBitmap?.let { bmp ->
            if (!bmp.isRecycled) runCatching { bmp.recycle() }
        }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= 30) {
            val sys = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            topInsetPx = maxOf(sys.top + dpInt(10), dpInt(32))
            bottomInsetPx = maxOf(maxOf(sys.bottom, ime.bottom) + dpInt(12), dpInt(20))
        } else {
            @Suppress("DEPRECATION")
            topInsetPx = maxOf(insets.systemWindowInsetTop + dpInt(10), dpInt(32))
            @Suppress("DEPRECATION")
            bottomInsetPx = maxOf(insets.systemWindowInsetBottom + dpInt(12), dpInt(20))
        }
        applySafeMargins()
        return insets
    }

    private fun applySafeMargins() {
        (topControls.layoutParams as? LayoutParams)?.let { lp ->
            lp.topMargin = topInsetPx
            topControls.layoutParams = lp
        }
        (bottomSheet.layoutParams as? LayoutParams)?.let { lp ->
            lp.bottomMargin = bottomInsetPx
            bottomSheet.layoutParams = lp
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            when {
                isEditingInline -> closeInlineEditor()
                translateBanner.visibility == View.VISIBLE -> {
                    translateBanner.visibility = View.GONE
                    translatedText = null
                }
                hasSelection() -> clearSelection()
                else -> onDismiss()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun hasSelection(): Boolean =
        !editedOverride.isNullOrBlank() || (selStart in words.indices && selEnd in words.indices)

    private fun normalizedRange(): IntRange {
        val maxIdx = (words.size - 1).coerceAtLeast(0)
        val s = minOf(selStart, selEnd).coerceIn(0, maxIdx)
        val e = maxOf(selStart, selEnd).coerceIn(0, maxIdx)
        return s..e
    }

    private fun computeSelectedText(): String {
        val edited = editedOverride
        if (!edited.isNullOrBlank()) return edited
        if (selStart !in words.indices || selEnd !in words.indices) return ""
        val range = normalizedRange()
        val sb = StringBuilder()
        var prevBlock = -1
        for (i in range) {
            val w = words[i]
            if (prevBlock != -1) {
                if (w.blockIndex != prevBlock) sb.append('\n') else sb.append(' ')
            }
            sb.append(w.text)
            prevBlock = w.blockIndex
        }
        return sb.toString().trim()
    }

    private fun clearSelection() {
        selStart = -1
        selEnd = -1
        editedOverride = null
        translatedText = null
        isEditingInline = false
        hideKeyboard()
        translateBanner.visibility = View.GONE
        inlineEditorBox.visibility = View.GONE
        updateUiForSelection()
        lensCanvas.invalidate()
    }

    private fun selectAllWords() {
        if (words.isEmpty()) return
        editedOverride = null
        translatedText = null
        selStart = 0
        selEnd = words.lastIndex
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        updateUiForSelection()
        lensCanvas.invalidate()
    }

    private fun openInlineEditor() {
        isEditingInline = true
        inlineEditorBox.visibility = View.VISIBLE
        previewRow.visibility = View.GONE
        inlineEditText.setText(cachedSelectedText)
        inlineEditText.setSelection(inlineEditText.text?.length ?: 0)
        inlineEditText.post {
            inlineEditText.requestFocus()
            runCatching {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(inlineEditText, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun closeInlineEditor() {
        isEditingInline = false
        hideKeyboard()
        inlineEditorBox.visibility = View.GONE
        previewRow.visibility = View.VISIBLE
        updateUiForSelection()
    }

    private fun hideKeyboard() {
        runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(windowToken, 0)
        }
    }

    private fun buildUi() {
        // 1. Fullscreen Google Lens Canvas (frozen frame + frosted text pills + blue selection + teardrop handles)
        lensCanvas = LensCanvasView(context)
        addView(lensCanvas, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 2. Clean Floating Top Bar: Circular Close (✕) on left, "Select all" pill on right
        topControls = FrameLayout(context).apply {
            setPadding(dpInt(14), 0, dpInt(14), 0)
        }

        val closeCircle = TextView(context).apply {
            text = "✕"
            typeface = Typeface.SANS_SERIF
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorPillFrostedBg)
            }
            elevation = dp(4f)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onDismiss()
            }
        }
        topControls.addView(closeCircle, LayoutParams(dpInt(40), dpInt(40), Gravity.START or Gravity.CENTER_VERTICAL))

        selectAllPill = TextView(context).apply {
            text = "Select all"
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dpInt(14), dpInt(8), dpInt(14), dpInt(8))
            background = GradientDrawable().apply {
                setColor(colorPillFrostedBg)
                cornerRadius = dp(20f)
            }
            elevation = dp(4f)
            setOnClickListener {
                if (words.isNotEmpty() && selStart == 0 && selEnd == words.lastIndex) {
                    clearSelection()
                } else {
                    selectAllWords()
                }
            }
        }
        topControls.addView(
            selectAllPill,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL)
        )

        addView(
            topControls,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
                topMargin = topInsetPx
            }
        )

        // 3. Modern Rounded Google Lens Bottom Action Sheet
        bottomSheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(14), dpInt(12), dpInt(14), dpInt(12))
            background = GradientDrawable().apply {
                setColor(colorSurface)
                setStroke(dpInt(1), colorBorder)
                cornerRadius = dp(22f)
            }
            elevation = dp(12f)
        }

        // 3a. Idle State (Scanning / Tap or drag prompt + Quick Smart Entity Chips)
        idleContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        idleHintLabel = TextView(context).apply {
            text = if (isScanning) "Scanning screen text…" else "Tap or drag over any text to select"
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(colorTextPrimary)
            gravity = Gravity.CENTER
            setPadding(dpInt(4), dpInt(2), dpInt(4), dpInt(2))
        }
        idleContainer.addView(idleHintLabel)

        quickChipsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            setPadding(0, dpInt(10), 0, 0)
        }
        quickChipsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        quickChipsScroll.addView(quickChipsRow)
        idleContainer.addView(quickChipsScroll)

        // 3b. Active Selection State (Preview + Inline Edit + Inline Translate + Google Lens Action Pills)
        selectedContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        previewRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpInt(2), 0, dpInt(2), dpInt(10))
        }

        previewLabel = TextView(context).apply {
            typeface = Typeface.SANS_SERIF
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(colorTextPrimary)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { openInlineEditor() }
        }

        editToggleBtn = TextView(context).apply {
            text = "✎ Edit"
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(colorAccent)
            setPadding(dpInt(10), dpInt(5), dpInt(10), dpInt(5))
            background = GradientDrawable().apply {
                setColor(colorSurfaceElev)
                cornerRadius = dp(14f)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dpInt(8) }
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                openInlineEditor()
            }
        }
        previewRow.addView(previewLabel)
        previewRow.addView(editToggleBtn)
        selectedContainer.addView(previewRow)

        // Inline Editor Box (hidden until user taps preview or Edit)
        inlineEditorBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 0, 0, dpInt(10))
        }
        inlineEditText = EditText(context).apply {
            typeface = Typeface.SANS_SERIF
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(colorTextPrimary)
            setHintTextColor(colorTextMuted)
            maxLines = 4
            setPadding(dpInt(12), dpInt(9), dpInt(12), dpInt(9))
            background = GradientDrawable().apply {
                setColor(colorSurfaceElev)
                setStroke(dpInt(1), colorAccent)
                cornerRadius = dp(12f)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    editedOverride = s?.toString().orEmpty()
                    cachedSelectedText = computeSelectedText()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        val doneEditBtn = TextView(context).apply {
            text = "Done editing"
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(colorAccent)
            gravity = Gravity.END
            setPadding(dpInt(6), dpInt(6), dpInt(4), dpInt(2))
            setOnClickListener { closeInlineEditor() }
        }
        inlineEditorBox.addView(inlineEditText)
        inlineEditorBox.addView(doneEditBtn)
        selectedContainer.addView(inlineEditorBox)

        // Inline Translate Banner (appears smoothly when Translate pill is tapped)
        translateBanner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dpInt(12), dpInt(9), dpInt(12), dpInt(9))
            background = GradientDrawable().apply {
                setColor(colorSurfaceElev)
                cornerRadius = dp(12f)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(10) }
        }
        translateResultLabel = TextView(context).apply {
            typeface = Typeface.SANS_SERIF
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(colorTextPrimary)
            setLineSpacing(0f, 1.18f)
        }
        val copyTransBtn = TextView(context).apply {
            text = "Copy translation"
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(colorAccent)
            setPadding(0, dpInt(6), 0, 0)
            setOnClickListener {
                val tr = translatedText
                if (!tr.isNullOrBlank()) onCopyText(tr)
            }
        }
        translateBanner.addView(translateResultLabel)
        translateBanner.addView(copyTransBtn)
        selectedContainer.addView(translateBanner)

        // Horizontal Scrollable Google Lens Action Pills
        val pillsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        actionPillsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pillsScroll.addView(actionPillsRow)
        selectedContainer.addView(pillsScroll)

        bottomSheet.addView(idleContainer)
        bottomSheet.addView(selectedContainer)

        addView(
            bottomSheet,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                leftMargin = dpInt(12)
                rightMargin = dpInt(12)
                bottomMargin = bottomInsetPx
            }
        )
    }

    private fun rebuildQuickEntityChips() {
        quickChipsRow.removeAllViews()
        if (words.isEmpty()) {
            quickChipsScroll.visibility = View.GONE
            return
        }

        // First chip: Select all text
        quickChipsRow.addView(makeLensPill("Select all", primary = false) {
            selectAllWords()
        })

        // Detected smart entities (phone numbers, URLs, emails, hashes)
        quickEntities.forEach { entity ->
            val icon = when (entity.type) {
                "TEL" -> "📞"
                "URL" -> "🔗"
                "MAIL" -> "✉"
                "HASH" -> "🧲"
                else -> "✦"
            }
            val shortVal = if (entity.value.length > 20) entity.value.take(19) + "…" else entity.value
            quickChipsRow.addView(makeLensPill("$icon $shortVal", primary = false) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                editedOverride = entity.value
                selStart = -1
                selEnd = -1
                updateUiForSelection()
                lensCanvas.invalidate()
            })
        }

        quickChipsScroll.visibility = View.VISIBLE
    }

    private fun updateUiForSelection() {
        cachedSelectedText = computeSelectedText()
        val hasSel = cachedSelectedText.isNotEmpty()

        selectAllPill.visibility = if (words.isNotEmpty()) View.VISIBLE else View.GONE
        selectAllPill.text = if (words.isNotEmpty() && selStart == 0 && selEnd == words.lastIndex) "Clear" else "Select all"

        if (isScanning) {
            idleContainer.visibility = View.VISIBLE
            selectedContainer.visibility = View.GONE
            idleHintLabel.text = "Scanning screen text…"
            return
        }

        if (!hasSel) {
            idleContainer.visibility = View.VISIBLE
            selectedContainer.visibility = View.GONE
            idleHintLabel.text = if (words.isEmpty()) {
                "No text found on screen"
            } else {
                "Tap or drag over any text to select"
            }
            return
        }

        idleContainer.visibility = View.GONE
        selectedContainer.visibility = View.VISIBLE

        val singleLine = cachedSelectedText.replace('\n', ' ')
        previewLabel.text = singleLine

        // Rebuild Google Lens Action Pills based on current selection context
        actionPillsRow.removeAllViews()

        // 1. Primary "Copy text" pill
        actionPillsRow.addView(makeLensPill("Copy text", primary = true) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (cachedSelectedText.isNotEmpty()) onCopyText(cachedSelectedText)
        })

        // 2. Context-aware smart pill (Call / Open link / Clean URL / Magnet / Define / Math)
        val (smartId, rawLabel) = TextTransforms.resolveSmartAction(cachedSelectedText)
        if (smartId != "tools") {
            val friendlyLabel = when (smartId) {
                "dial" -> "📞 Call"
                "open_url" -> "🔗 Open link"
                "clean_url" -> "✨ Clean URL"
                "magnet" -> "🧲 Magnet"
                "define" -> "📖 Define"
                "math" -> rawLabel
                else -> rawLabel
            }
            actionPillsRow.addView(makeLensPill(friendlyLabel, primary = false) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onSmartAction(smartId, cachedSelectedText)
            })
        }

        // 3. Search
        actionPillsRow.addView(makeLensPill("🔍 Search", primary = false) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onSearchText(cachedSelectedText)
        })

        // 4. Inline Translate (just like Google Lens!)
        actionPillsRow.addView(makeLensPill("🌐 Translate", primary = false) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            translateBanner.visibility = View.VISIBLE
            translateResultLabel.text = "Translating…"
            translatedText = null
            networkTools.lookupTranslation(cachedSelectedText) { result ->
                translatedText = result
                translateResultLabel.text = result
            }
        })

        // 5. Listen (TTS)
        actionPillsRow.addView(makeLensPill("🔊 Listen", primary = false) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onListenText(cachedSelectedText)
        })

        // 6. Send to full [sel] Tools palette
        actionPillsRow.addView(makeLensPill("More tools ›", primary = false) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onOpenInTools(cachedSelectedText)
        })
    }

    private fun makeLensPill(label: String, primary: Boolean, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.8f)
            setTextColor(if (primary) colorOnAccent else colorTextPrimary)
            gravity = Gravity.CENTER
            isSingleLine = true
            setPadding(dpInt(16), dpInt(10), dpInt(16), dpInt(10))
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    GradientDrawable().apply {
                        setColor(if (primary) colorAccent else colorSurfacePressed)
                        cornerRadius = dp(20f)
                    }
                )
                addState(
                    intArrayOf(),
                    GradientDrawable().apply {
                        setColor(if (primary) colorAccent else colorSurfaceElev)
                        cornerRadius = dp(20f)
                    }
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dpInt(8) }
            setOnClickListener { onClick() }
        }
    }

    /**
     * Exact Google Lens Canvas:
     * - Renders frozen frame + smooth scan sweep while OCR runs.
     * - Draws subtle frosted Google Lens line pills over selectable text.
     * - Draws continuous per-line blue highlight bands over selected range.
     * - Draws crisp left & right teardrop selection handles with generous 32dp touch hitboxes.
     * - Renders a floating word-loupe pill above the user's thumb while dragging a handle.
     */
    private inner class LensCanvasView(context: Context) : View(context) {

        private enum class DragMode {
            NONE,
            SWEEP_SELECT,
            HANDLE_START,
            HANDLE_END
        }

        private var dragMode = DragMode.NONE
        private var anchorWordIndex = -1
        private var activeTouchX = 0f
        private var activeTouchY = 0f
        private var lastTapWord = -1
        private var lastTapTime = 0L

        private val startHandleTip = FloatArray(2)
        private val endHandleTip = FloatArray(2)
        private val startHandleHit = RectF()
        private val endHandleHit = RectF()

        private val bgDstRect = Rect()
        private val bgBitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        private val subtleScrimPaint = Paint().apply {
            color = Color.argb(if (isDark) 36 else 22, 0, 0, 0)
            style = Paint.Style.FILL
        }

        // Google Lens frosted text indicator pills (clean, legible, zero harsh neon clutter)
        private val unselectedLineFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDark) Color.argb(46, 255, 255, 255) else Color.argb(38, 26, 115, 232)
            style = Paint.Style.FILL
        }
        private val unselectedLineStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDark) Color.argb(80, 255, 255, 255) else Color.argb(65, 26, 115, 232)
            style = Paint.Style.STROKE
            strokeWidth = dp(0.9f)
        }

        private val selectedLineFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorHighlightFill
            style = Paint.Style.FILL
        }

        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorHandle
            style = Paint.Style.FILL
        }
        private val handleStemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorHandle
            style = Paint.Style.STROKE
            strokeWidth = dp(2.4f)
            strokeCap = Paint.Cap.ROUND
        }

        private val loupeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorSurface
            style = Paint.Style.FILL
            setShadowLayer(dp(6f), 0f, dp(2f), Color.argb(90, 0, 0, 0))
        }
        private val loupeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorAccent
            style = Paint.Style.STROKE
            strokeWidth = dp(1.2f)
        }
        private val loupeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            color = colorTextPrimary
            textSize = dp(14f)
            textAlign = Paint.Align.CENTER
        }

        private val scanSweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var scanAnimator: ValueAnimator? = null
        private var scanProgress: Float = 0f

        private val tempRect = RectF()
        private val teardropPath = Path()
        private val padX = dp(3f)
        private val padY = dp(2f)
        private val cornerR = dp(5f)
        private val handleRadius = dp(10.5f)
        private val handleHitRadius = dp(32f)

        fun startScanShimmer() {
            stopScanShimmer()
            scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1100L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    scanProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stopScanShimmer() {
            scanAnimator?.cancel()
            scanAnimator = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            // 1. Frozen screenshot frame (keeps images/videos/apps 100% crisp & stationary!)
            val bmp = backgroundBitmap
            if (bmp != null && !bmp.isRecycled) {
                bgDstRect.set(0, 0, width, height)
                canvas.drawBitmap(bmp, null, bgDstRect, bgBitmapPaint)
            }

            // 2. Very subtle scrim so real screen remains crystal clear
            canvas.drawRect(0f, 0f, w, h, subtleScrimPaint)

            // 3. Scanning shimmer bar while ML Kit OCR processes (~200ms)
            if (isScanning) {
                val bandH = dp(140f)
                val sweepY = -bandH + (h + bandH * 2f) * scanProgress
                scanSweepPaint.shader = LinearGradient(
                    0f, sweepY, 0f, sweepY + bandH,
                    intArrayOf(
                        Color.TRANSPARENT,
                        Color.argb(95, 138, 180, 248),
                        Color.argb(145, 255, 255, 255),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.45f, 0.65f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, sweepY, w, sweepY + bandH, scanSweepPaint)
                return
            }

            // 4. Soft frosted Google Lens pills on every recognized line
            for (line in lines) {
                tempRect.set(
                    line.bounds.left - padX,
                    line.bounds.top - padY,
                    line.bounds.right + padX,
                    line.bounds.bottom + padY
                )
                canvas.drawRoundRect(tempRect, cornerR, cornerR, unselectedLineFillPaint)
                canvas.drawRoundRect(tempRect, cornerR, cornerR, unselectedLineStrokePaint)
            }

            // 5. Continuous per-line blue selection highlight + teardrop handles
            if (selStart in words.indices && selEnd in words.indices) {
                val range = normalizedRange()
                var curLineIdx = -1
                var curLineUnion: RectF? = null

                for (i in range) {
                    val word = words[i]
                    if (word.lineIndex != curLineIdx) {
                        curLineUnion?.let { u ->
                            tempRect.set(u.left - padX, u.top - padY, u.right + padX, u.bottom + padY)
                            canvas.drawRoundRect(tempRect, cornerR, cornerR, selectedLineFillPaint)
                        }
                        curLineIdx = word.lineIndex
                        curLineUnion = RectF(word.rect)
                    } else {
                        curLineUnion?.union(word.rect)
                    }
                }
                curLineUnion?.let { u ->
                    tempRect.set(u.left - padX, u.top - padY, u.right + padX, u.bottom + padY)
                    canvas.drawRoundRect(tempRect, cornerR, cornerR, selectedLineFillPaint)
                }

                // Draw crisp left & right Google Lens teardrop handles
                val firstR = words[range.first].rect
                val lastR = words[range.last].rect

                drawTeardropHandle(canvas, firstR.left, firstR.top, firstR.bottom, isStart = true)
                drawTeardropHandle(canvas, lastR.right, lastR.top, lastR.bottom, isStart = false)

                // Draw floating loupe preview pill above finger while dragging a handle
                if (dragMode == DragMode.HANDLE_START || dragMode == DragMode.HANDLE_END) {
                    val activeIdx = if (dragMode == DragMode.HANDLE_START) selStart else selEnd
                    if (activeIdx in words.indices) {
                        drawFloatingLoupe(canvas, activeIdx)
                    }
                }
            }
        }

        private fun drawTeardropHandle(canvas: Canvas, x: Float, topY: Float, bottomY: Float, isStart: Boolean) {
            // Vertical caret stem along word edge
            canvas.drawLine(x, topY - padY, x, bottomY + padY, handleStemPaint)

            val r = handleRadius
            val cx = if (isStart) x - r * 0.85f else x + r * 0.85f
            val cy = bottomY + padY + r * 1.15f

            if (isStart) {
                startHandleTip[0] = cx
                startHandleTip[1] = cy
                startHandleHit.set(cx - handleHitRadius, cy - handleHitRadius, cx + handleHitRadius, cy + handleHitRadius)
            } else {
                endHandleTip[0] = cx
                endHandleTip[1] = cy
                endHandleHit.set(cx - handleHitRadius, cy - handleHitRadius, cx + handleHitRadius, cy + handleHitRadius)
            }

            // Sleek teardrop shape pointing up-right (start handle) or up-left (end handle)
            teardropPath.reset()
            teardropPath.addCircle(cx, cy, r, Path.Direction.CW)
            // Corner beak connecting circle smoothly to the caret tip at (x, bottomY + padY)
            if (isStart) {
                teardropPath.moveTo(x, bottomY + padY)
                teardropPath.lineTo(cx + r, cy)
                teardropPath.lineTo(cx, cy - r)
                teardropPath.close()
            } else {
                teardropPath.moveTo(x, bottomY + padY)
                teardropPath.lineTo(cx - r, cy)
                teardropPath.lineTo(cx, cy - r)
                teardropPath.close()
            }
            canvas.drawPath(teardropPath, handlePaint)
        }

        private fun drawFloatingLoupe(canvas: Canvas, activeWordIdx: Int) {
            val prevW = words.getOrNull(activeWordIdx - 1)?.text
            val curW = words[activeWordIdx].text
            val nextW = words.getOrNull(activeWordIdx + 1)?.text
            val snippet = buildString {
                if (prevW != null) append(prevW.take(10)).append("  ")
                append("[").append(curW.take(18)).append("]")
                if (nextW != null) append("  ").append(nextW.take(10))
            }
            val textW = loupeTextPaint.measureText(snippet)
            val pillW = (textW + dp(28f)).coerceAtMost(width - dp(24f))
            val pillH = dp(38f)
            val cx = activeTouchX.coerceIn(pillW / 2f + dp(12f), (width - pillW / 2f - dp(12f)).coerceAtLeast(pillW / 2f))
            val cy = (activeTouchY - dp(72f)).coerceIn(topInsetPx + dp(56f), (height - dp(160f)).coerceAtLeast(topInsetPx + dp(56f)))

            tempRect.set(cx - pillW / 2f, cy - pillH / 2f, cx + pillW / 2f, cy + pillH / 2f)
            canvas.drawRoundRect(tempRect, pillH / 2f, pillH / 2f, loupeBgPaint)
            canvas.drawRoundRect(tempRect, pillH / 2f, pillH / 2f, loupeStrokePaint)
            val ty = tempRect.centerY() - (loupeTextPaint.descent() + loupeTextPaint.ascent()) / 2f
            canvas.drawText(snippet, tempRect.centerX(), ty, loupeTextPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (isScanning || isEditingInline) return false
            val x = event.x
            val y = event.y
            activeTouchX = x
            activeTouchY = y

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 1. Check teardrop handle grabs first (generous 32dp radius)
                    if (selStart in words.indices && selEnd in words.indices) {
                        val hitStart = startHandleHit.contains(x, y)
                        val hitEnd = endHandleHit.contains(x, y)
                        if (hitStart || hitEnd) {
                            val r = normalizedRange()
                            selStart = r.first
                            selEnd = r.last
                            dragMode = if (hitStart && (!hitEnd || hypot(x - startHandleTip[0], y - startHandleTip[1]) <= hypot(x - endHandleTip[0], y - endHandleTip[1]))) {
                                DragMode.HANDLE_START
                            } else {
                                DragMode.HANDLE_END
                            }
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            invalidate()
                            return true
                        }
                    }

                    // 2. Hit test words directly
                    val hitIdx = findNearestWord(x, y, maxDist = dp(24f))
                    if (hitIdx != -1) {
                        editedOverride = null
                        translatedText = null
                        translateBanner.visibility = View.GONE
                        val now = System.currentTimeMillis()
                        if (hitIdx == lastTapWord && now - lastTapTime < 340L) {
                            // Double-tap -> select entire paragraph/block (just like Google Lens!)
                            val blk = words[hitIdx].blockIndex
                            val firstInBlk = words.indexOfFirst { it.blockIndex == blk }
                            val lastInBlk = words.indexOfLast { it.blockIndex == blk }
                            if (firstInBlk != -1 && lastInBlk != -1) {
                                selStart = firstInBlk
                                selEnd = lastInBlk
                                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                updateUiForSelection()
                                invalidate()
                            }
                            dragMode = DragMode.NONE
                        } else {
                            // Single tap -> select word & allow continuous sweep-drag!
                            dragMode = DragMode.SWEEP_SELECT
                            anchorWordIndex = hitIdx
                            selStart = hitIdx
                            selEnd = hitIdx
                            lastTapWord = hitIdx
                            lastTapTime = now
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            updateUiForSelection()
                            invalidate()
                        }
                        return true
                    }

                    // 3. Tapped empty area
                    if (hasSelection()) {
                        clearSelection()
                    } else {
                        onDismiss()
                    }
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    when (dragMode) {
                        DragMode.SWEEP_SELECT -> {
                            val hit = findNearestWord(x, y, maxDist = dp(44f))
                            if (hit != -1 && hit != selEnd) {
                                selStart = anchorWordIndex
                                selEnd = hit
                                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                updateUiForSelection()
                                invalidate()
                            }
                        }
                        DragMode.HANDLE_START -> {
                            val hit = findNearestWord(x, y - dp(22f), maxDist = dp(56f))
                            if (hit != -1 && hit != selStart) {
                                selStart = hit
                                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                updateUiForSelection()
                                invalidate()
                            } else {
                                invalidate() // keep loupe tracking smoothly
                            }
                        }
                        DragMode.HANDLE_END -> {
                            val hit = findNearestWord(x, y - dp(22f), maxDist = dp(56f))
                            if (hit != -1 && hit != selEnd) {
                                selEnd = hit
                                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                updateUiForSelection()
                                invalidate()
                            } else {
                                invalidate()
                            }
                        }
                        DragMode.NONE -> {}
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (selStart in words.indices && selEnd in words.indices) {
                        val r = normalizedRange()
                        selStart = r.first
                        selEnd = r.last
                    }
                    dragMode = DragMode.NONE
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun findNearestWord(x: Float, y: Float, maxDist: Float): Int {
            for (w in words) {
                if (x >= w.rect.left - padX && x <= w.rect.right + padX &&
                    y >= w.rect.top - padY && y <= w.rect.bottom + padY
                ) {
                    return w.index
                }
            }
            var bestIdx = -1
            var bestDist = maxDist
            for (w in words) {
                val dx = when {
                    x < w.rect.left -> w.rect.left - x
                    x > w.rect.right -> x - w.rect.right
                    else -> 0f
                }
                val dy = when {
                    y < w.rect.top -> w.rect.top - y
                    y > w.rect.bottom -> y - w.rect.bottom
                    else -> 0f
                }
                val d = hypot(dx, dy)
                if (d < bestDist) {
                    bestDist = d
                    bestIdx = w.index
                }
            }
            return bestIdx
        }
    }
}
