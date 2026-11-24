package io.github.selclip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Intuitive, dual-mode Screen Text Grab overlay:
 *
 * 1. VISUAL OVERLAY MODE (default):
 *    - Keeps the real underlying screen clearly readable (subtle translucent dim, never opaque boxes).
 *    - Highlights selectable text regions cleanly using OS line bounds when available.
 *    - Tap any text block to toggle selection (natural multi-select with #1/#2 badges).
 *    - Drag anywhere to draw a live Lasso / Selection Box that highlights all intersecting text.
 *    - Double-tap any block to open the inline native text editor immediately.
 *    - Surfaces 1-tap quick-grab chips for detected phones, URLs, emails, and hashes.
 *
 * 2. READER LIST & INLINE EDITOR MODE:
 *    - Tap [LIST] for a searchable, scrollable list of all screen text blocks with 1-tap copy & check.
 *    - Tap [EDIT] (or preview text) for a native EditText with full cursor/trim/substring precision.
 */
class LensOverlayView(
    context: Context,
    private val snapshot: ScreenGrabSnapshot,
    private val backgroundBitmap: Bitmap? = null,
    private val isOcrMode: Boolean = false,
    private val onCopyText: (String) -> Unit,
    private val onSmartAction: (String, String) -> Unit,
    private val onOpenInTools: (String) -> Unit,
    private val onSearchText: (String) -> Unit,
    private val onRunPixelOcr: () -> Unit,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private enum class OverlayMode {
        VISUAL,
        LIST,
        EDITOR
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density
    private fun dpInt(v: Int): Int = (v * density).toInt()
    private val pal = ToolPrefs.resolvePalette(context)

    private val blocks: List<GrabbedBlock> = snapshot.blocks
    private val selectedIds = LinkedHashSet<Int>()
    private var overrideEditedText: String? = null
    private var mode: OverlayMode = OverlayMode.VISUAL

    private var topInsetPx: Int = dpInt(36)
    private var bottomInsetPx: Int = dpInt(24)

    // UI References
    private lateinit var highlightCanvas: VisualHighlightCanvas
    private lateinit var topBar: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var listToggleBtn: TextView
    private lateinit var allToggleBtn: TextView

    private lateinit var sheetContainer: LinearLayout
    private lateinit var listSearchInput: EditText
    private lateinit var listItemsContainer: LinearLayout
    private lateinit var editorInput: EditText

    private lateinit var bottomDock: LinearLayout
    private lateinit var idleHintBox: LinearLayout
    private lateinit var selectionActionBox: LinearLayout
    private lateinit var previewTextView: TextView
    private lateinit var smartActionBtn: TextView
    private lateinit var secondaryActionBtn: TextView

    private var cachedSelectedText: String = ""
    private var cachedSmartAction: Pair<String, String> = "tools" to "TOOLS >"

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        buildHierarchy()
        refreshSelectionState()
        updateModeVisibility()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        backgroundBitmap?.let { bmp ->
            if (!bmp.isRecycled) runCatching { bmp.recycle() }
        }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= 30) {
            val sys = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            topInsetPx = maxOf(sys.top + dpInt(8), dpInt(28))
            bottomInsetPx = maxOf(maxOf(sys.bottom, ime.bottom) + dpInt(10), dpInt(18))
        } else {
            @Suppress("DEPRECATION")
            topInsetPx = maxOf(insets.systemWindowInsetTop + dpInt(8), dpInt(28))
            @Suppress("DEPRECATION")
            bottomInsetPx = maxOf(insets.systemWindowInsetBottom + dpInt(10), dpInt(18))
        }
        applySafeMargins()
        return insets
    }

    private fun applySafeMargins() {
        (topBar.layoutParams as? LayoutParams)?.let { lp ->
            lp.topMargin = topInsetPx
            topBar.layoutParams = lp
        }
        (bottomDock.layoutParams as? LayoutParams)?.let { lp ->
            lp.bottomMargin = bottomInsetPx
            bottomDock.layoutParams = lp
        }
        (sheetContainer.layoutParams as? LayoutParams)?.let { lp ->
            lp.topMargin = topInsetPx + dpInt(52)
            lp.bottomMargin = bottomInsetPx + dpInt(104)
            sheetContainer.layoutParams = lp
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            handleBackNavigation()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleBackNavigation() {
        hideKeyboard()
        when (mode) {
            OverlayMode.EDITOR -> {
                overrideEditedText = editorInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                mode = OverlayMode.VISUAL
                updateModeVisibility()
                refreshSelectionState()
            }
            OverlayMode.LIST -> {
                mode = OverlayMode.VISUAL
                updateModeVisibility()
            }
            OverlayMode.VISUAL -> {
                if (hasSelection()) {
                    clearSelection()
                } else {
                    onDismiss()
                }
            }
        }
    }

    private fun hideKeyboard() {
        runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(windowToken, 0)
        }
    }

    private fun hasSelection(): Boolean = selectedIds.isNotEmpty() || !overrideEditedText.isNullOrBlank()

    private fun computeSelectedText(): String {
        val edited = overrideEditedText
        if (!edited.isNullOrBlank()) return edited
        if (selectedIds.isEmpty()) return ""
        val sb = StringBuilder()
        var first = true
        for (id in selectedIds) {
            val block = blocks.getOrNull(id) ?: continue
            if (!first) sb.append('\n')
            sb.append(block.text)
            first = false
        }
        return sb.toString().trim()
    }

    private fun clearSelection() {
        selectedIds.clear()
        overrideEditedText = null
        refreshSelectionState()
        highlightCanvas.invalidate()
        if (mode == OverlayMode.LIST) renderListItems(listSearchInput.text?.toString().orEmpty())
    }

    private fun refreshSelectionState() {
        cachedSelectedText = computeSelectedText()
        cachedSmartAction = TextTransforms.resolveSmartAction(cachedSelectedText)

        val count = selectedIds.size
        val words = if (cachedSelectedText.isEmpty()) 0 else TextTransforms.countWords(cachedSelectedText)
        val modeTag = if (isOcrMode) "OCR" else "GRAB"
        statusTitle.text = when {
            !overrideEditedText.isNullOrBlank() -> "[+] EDITED · ${words}W"
            count > 0 -> "[+] $count SEL · ${words}W"
            else -> "[+] $modeTag · ${blocks.size} BLK"
        }

        allToggleBtn.text = if (blocks.isNotEmpty() && selectedIds.size == blocks.size) "[NONE]" else "[ALL]"

        if (cachedSelectedText.isNotEmpty()) {
            idleHintBox.visibility = View.GONE
            selectionActionBox.visibility = View.VISIBLE
            val single = cachedSelectedText.replace('\n', ' ').let {
                if (it.length > 96) it.take(95) + "…" else it
            }
            previewTextView.text = "\"$single\""
            val (_, smartLabel) = cachedSmartAction
            smartActionBtn.text = smartLabel
            secondaryActionBtn.text = if (cachedSmartAction.first == "tools") "[/] WEB" else "TOOLS >"
        } else {
            idleHintBox.visibility = View.VISIBLE
            selectionActionBox.visibility = View.GONE
        }
    }

    private fun updateModeVisibility() {
        val isVisual = mode == OverlayMode.VISUAL
        highlightCanvas.visibility = View.VISIBLE
        highlightCanvas.setDimmedForSheet(!isVisual)
        sheetContainer.visibility = if (isVisual) View.GONE else View.VISIBLE
        listToggleBtn.text = when (mode) {
            OverlayMode.VISUAL -> "[LIST]"
            OverlayMode.LIST -> "[SCREEN]"
            OverlayMode.EDITOR -> "[SCREEN]"
        }
        listToggleBtn.setTextColor(if (isVisual) pal.fgPrimaryInt else pal.accentGreenInt)

        if (mode == OverlayMode.LIST) {
            buildListSheetContent()
        } else if (mode == OverlayMode.EDITOR) {
            buildEditorSheetContent()
        } else {
            hideKeyboard()
        }
    }

    private fun openEditorForCurrentSelection() {
        mode = OverlayMode.EDITOR
        updateModeVisibility()
    }

    private fun buildHierarchy() {
        // 1. Fullscreen translucent visual highlight & lasso canvas
        highlightCanvas = VisualHighlightCanvas(context)
        addView(highlightCanvas, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 2. Floating Top Bar
        topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpInt(10), dpInt(6), dpInt(8), dpInt(6))
            background = GradientDrawable().apply {
                setColor(withAlpha(pal.bgHeaderInt, 242))
                setStroke(dpInt(1), pal.accentPurpleInt)
                cornerRadius = dp(6f)
            }
            elevation = dp(6f)
        }

        statusTitle = TextView(context).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(pal.accentGreenInt)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        listToggleBtn = makeTopChip("[LIST]", pal.fgPrimaryInt) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            mode = if (mode == OverlayMode.VISUAL) OverlayMode.LIST else OverlayMode.VISUAL
            updateModeVisibility()
        }

        allToggleBtn = makeTopChip("[ALL]", pal.fgPrimaryInt) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            overrideEditedText = null
            if (selectedIds.size == blocks.size) {
                selectedIds.clear()
            } else {
                selectedIds.clear()
                blocks.forEach { selectedIds.add(it.id) }
            }
            refreshSelectionState()
            highlightCanvas.invalidate()
            if (mode == OverlayMode.LIST) renderListItems(listSearchInput.text?.toString().orEmpty())
        }

        val ocrBtn = makeTopChip("[OCR]", pal.accentCyanInt) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onRunPixelOcr()
        }

        val closeBtn = makeTopChip("[X]", pal.dangerRedInt) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onDismiss()
        }

        topBar.addView(statusTitle)
        topBar.addView(listToggleBtn)
        topBar.addView(allToggleBtn)
        topBar.addView(ocrBtn)
        topBar.addView(closeBtn)

        addView(
            topBar,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
                leftMargin = dpInt(10)
                rightMargin = dpInt(10)
                topMargin = topInsetPx
            }
        )

        // 3. Center Sheet (used for List/Reader mode and Inline Editor mode)
        sheetContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dpInt(10), dpInt(10), dpInt(10), dpInt(10))
            background = GradientDrawable().apply {
                setColor(withAlpha(pal.bgPrimaryInt, 248))
                setStroke(dpInt(1), pal.borderInt)
                cornerRadius = dp(6f)
            }
            elevation = dp(8f)
        }
        addView(
            sheetContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                leftMargin = dpInt(10)
                rightMargin = dpInt(10)
                topMargin = topInsetPx + dpInt(52)
                bottomMargin = bottomInsetPx + dpInt(104)
            }
        )

        // 4. Floating Bottom Smart Dock
        bottomDock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(10), dpInt(8), dpInt(10), dpInt(8))
            background = GradientDrawable().apply {
                setColor(withAlpha(pal.bgHeaderInt, 245))
                setStroke(dpInt(1), pal.accentPurpleInt)
                cornerRadius = dp(6f)
            }
            elevation = dp(8f)
        }

        // 4a. Idle Guidance + Quick-Grab Entity Chips
        idleHintBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (snapshot.quickEntities.isNotEmpty()) {
            val chipScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, 0, 0, dpInt(6))
            }
            val chipRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            snapshot.quickEntities.forEach { entity ->
                val shortVal = if (entity.value.length > 22) entity.value.take(21) + "…" else entity.value
                val chip = TextView(context).apply {
                    text = "[${entity.type}] $shortVal"
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    setTextColor(pal.accentCyanInt)
                    isSingleLine = true
                    setPadding(dpInt(8), dpInt(5), dpInt(8), dpInt(5))
                    background = GradientDrawable().apply {
                        setColor(pal.bgSurfaceInt)
                        setStroke(dpInt(1), pal.borderInt)
                        cornerRadius = dp(4f)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dpInt(6) }
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        overrideEditedText = entity.value
                        selectedIds.clear()
                        refreshSelectionState()
                        highlightCanvas.invalidate()
                    }
                }
                chipRow.addView(chip)
            }
            chipScroll.addView(chipRow)
            idleHintBox.addView(chipScroll)
        }

        idleHintBox.addView(TextView(context).apply {
            text = "Tap text to select · Drag box to lasso · Double-tap or [LIST] to edit"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(pal.fgMutedInt)
            gravity = Gravity.CENTER
            setPadding(dpInt(2), dpInt(2), dpInt(2), dpInt(2))
        })

        // 4b. Active Selection Preview + Action Row
        selectionActionBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val previewRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dpInt(7))
        }

        previewTextView = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.8f)
            setTextColor(pal.fgPrimaryInt)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                openEditorForCurrentSelection()
            }
        }

        val editPill = TextView(context).apply {
            text = "[EDIT]"
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(pal.accentYellowInt)
            setPadding(dpInt(7), dpInt(4), dpInt(7), dpInt(4))
            background = GradientDrawable().apply {
                setColor(pal.bgSurfaceInt)
                setStroke(dpInt(1), pal.borderInt)
                cornerRadius = dp(4f)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dpInt(6) }
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                openEditorForCurrentSelection()
            }
        }

        previewRow.addView(previewTextView)
        previewRow.addView(editPill)
        selectionActionBox.addView(previewRow)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val copyBtn = makeDockButton("[^C] COPY", primary = true, weight = 1.15f) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (cachedSelectedText.isNotEmpty()) onCopyText(cachedSelectedText)
        }

        smartActionBtn = makeDockButton("TOOLS >", primary = false, accentStroke = true, weight = 1.15f) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (cachedSelectedText.isNotEmpty()) {
                val actionId = cachedSmartAction.first
                if (actionId == "tools") {
                    onOpenInTools(cachedSelectedText)
                } else {
                    onSmartAction(actionId, cachedSelectedText)
                }
            }
        }

        secondaryActionBtn = makeDockButton("TOOLS >", primary = false, weight = 1.0f) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (cachedSelectedText.isNotEmpty()) {
                if (cachedSmartAction.first == "tools") {
                    onSearchText(cachedSelectedText)
                } else {
                    onOpenInTools(cachedSelectedText)
                }
            }
        }

        val clrBtn = makeDockButton("CLR", primary = false, weight = 0.65f, textColor = pal.dangerRedInt) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            clearSelection()
        }

        btnRow.addView(copyBtn)
        btnRow.addView(smartActionBtn)
        btnRow.addView(secondaryActionBtn)
        btnRow.addView(clrBtn)
        selectionActionBox.addView(btnRow)

        bottomDock.addView(idleHintBox)
        bottomDock.addView(selectionActionBox)

        addView(
            bottomDock,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                leftMargin = dpInt(10)
                rightMargin = dpInt(10)
                bottomMargin = bottomInsetPx
            }
        )
    }

    private fun buildListSheetContent() {
        sheetContainer.removeAllViews()

        sheetContainer.addView(TextView(context).apply {
            text = "SCREEN TEXT READER (${blocks.size} BLOCKS) — TAP TO SELECT · [^C] INSTANT COPY"
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.8f)
            setTextColor(pal.accentPurpleInt)
            setPadding(0, 0, 0, dpInt(6))
        })

        listSearchInput = EditText(context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(pal.fgPrimaryInt)
            setHintTextColor(pal.fgMutedInt)
            hint = "Filter text on screen…"
            isSingleLine = true
            setPadding(dpInt(10), dpInt(8), dpInt(10), dpInt(8))
            background = GradientDrawable().apply {
                setColor(pal.bgInputInt)
                setStroke(dpInt(1), pal.borderInt)
                cornerRadius = dp(4f)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(8) }
        }
        sheetContainer.addView(listSearchInput)

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        listItemsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listItemsContainer)
        sheetContainer.addView(scroll)

        listSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderListItems(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        renderListItems("")
    }

    private fun renderListItems(query: String) {
        if (!::listItemsContainer.isInitialized) return
        listItemsContainer.removeAllViews()
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) blocks else blocks.filter { it.text.lowercase().contains(q) }

        if (filtered.isEmpty()) {
            listItemsContainer.addView(TextView(context).apply {
                text = "No matching text blocks."
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(pal.fgMutedInt)
                setPadding(dpInt(8), dpInt(12), dpInt(8), dpInt(12))
            })
            return
        }

        for (b in filtered) {
            val isSel = b.id in selectedIds
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpInt(8), dpInt(8), dpInt(8), dpInt(8))
                background = GradientDrawable().apply {
                    setColor(if (isSel) withAlpha(pal.accentGreenInt, 42) else pal.bgSurfaceInt)
                    setStroke(dpInt(1), if (isSel) pal.accentGreenInt else pal.dividerInt)
                    cornerRadius = dp(4f)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpInt(5) }
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    overrideEditedText = null
                    if (b.id in selectedIds) selectedIds.remove(b.id) else selectedIds.add(b.id)
                    refreshSelectionState()
                    highlightCanvas.invalidate()
                    renderListItems(query)
                }
            }

            val check = TextView(context).apply {
                text = if (isSel) "[x]" else "[ ]"
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(if (isSel) pal.accentGreenInt else pal.fgMutedInt)
                setPadding(0, 0, dpInt(8), 0)
            }

            val body = TextView(context).apply {
                text = b.text
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.2f)
                setTextColor(pal.fgPrimaryInt)
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val quickCopy = TextView(context).apply {
                text = "[^C]"
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(pal.accentCyanInt)
                setPadding(dpInt(8), dpInt(4), dpInt(4), dpInt(4))
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onCopyText(b.text)
                }
            }

            card.addView(check)
            card.addView(body)
            card.addView(quickCopy)
            listItemsContainer.addView(card)
        }
    }

    private fun buildEditorSheetContent() {
        sheetContainer.removeAllViews()

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dpInt(6))
        }
        headerRow.addView(TextView(context).apply {
            text = "INLINE TEXT EDITOR — SELECT OR TRIM EXACT WORDS"
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.8f)
            setTextColor(pal.accentYellowInt)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(makeTopChip("[TRIM]", pal.accentCyanInt) {
            val cleaned = TextTransforms.cleanText(editorInput.text?.toString().orEmpty())
            editorInput.setText(cleaned)
            editorInput.setSelection(cleaned.length)
        })
        sheetContainer.addView(headerRow)

        editorInput = EditText(context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f)
            setTextColor(pal.fgPrimaryInt)
            setHintTextColor(pal.fgMutedInt)
            gravity = Gravity.TOP or Gravity.START
            setPadding(dpInt(10), dpInt(10), dpInt(10), dpInt(10))
            background = GradientDrawable().apply {
                setColor(pal.bgInputInt)
                setStroke(dpInt(1), pal.accentGreenInt)
                cornerRadius = dp(4f)
            }
            setText(cachedSelectedText)
            setSelection(text?.length ?: 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    overrideEditedText = s?.toString().orEmpty()
                    refreshSelectionState()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        sheetContainer.addView(editorInput)

        editorInput.post {
            editorInput.requestFocus()
            runCatching {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(editorInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun makeTopChip(label: String, textColor: Int, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.2f)
            setTextColor(textColor)
            isSingleLine = true
            setPadding(dpInt(7), dpInt(5), dpInt(7), dpInt(5))
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    GradientDrawable().apply {
                        setColor(pal.bgPressedInt)
                        cornerRadius = dp(4f)
                    }
                )
                addState(
                    intArrayOf(),
                    GradientDrawable().apply {
                        setColor(pal.bgSurfaceInt)
                        setStroke(dpInt(1), pal.borderInt)
                        cornerRadius = dp(4f)
                    }
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dpInt(4) }
            setOnClickListener { onClick() }
        }
    }

    private fun makeDockButton(
        label: String,
        primary: Boolean,
        accentStroke: Boolean = false,
        weight: Float = 1f,
        textColor: Int = if (primary) pal.onAccentInt else pal.fgPrimaryInt,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            text = label
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.6f)
            setTextColor(textColor)
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dpInt(4), dpInt(9), dpInt(4), dpInt(9))
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    GradientDrawable().apply {
                        setColor(pal.bgPressedInt)
                        cornerRadius = dp(4f)
                    }
                )
                addState(
                    intArrayOf(),
                    GradientDrawable().apply {
                        setColor(if (primary) pal.accentGreenInt else pal.bgSurfaceInt)
                        if (!primary) {
                            setStroke(dpInt(1), if (accentStroke) pal.accentPurpleInt else pal.borderInt)
                        }
                        cornerRadius = dp(4f)
                    }
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                weight
            ).apply {
                marginStart = dpInt(2)
                marginEnd = dpInt(2)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }

    /**
     * Zero-allocation-per-frame translucent highlight & lasso canvas.
     * Lets the real app remain completely legible underneath while drawing crisp
     * highlight overlays on grabbable text regions and a live drag-lasso rectangle.
     */
    private inner class VisualHighlightCanvas(context: Context) : View(context) {

        private var dimmedForSheet = false
        private val blockRectFs: List<RectF> = blocks.map {
            RectF(it.bounds.left.toFloat(), it.bounds.top.toFloat(), it.bounds.right.toFloat(), it.bounds.bottom.toFloat())
        }

        private val scrimLightPaint = Paint().apply {
            color = withAlpha(if (pal.isDark) Color.BLACK else pal.bgHeaderInt, if (pal.isDark) 68 else 46)
            style = Paint.Style.FILL
        }

        private val scrimSheetPaint = Paint().apply {
            color = withAlpha(if (pal.isDark) Color.BLACK else pal.bgHeaderInt, 165)
            style = Paint.Style.FILL
        }

        private val idleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(pal.accentCyanInt, if (pal.isDark) 26 else 22)
            style = Paint.Style.FILL
        }

        private val idleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(pal.accentCyanInt, if (pal.isDark) 135 else 155)
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
        }

        private val selectedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(pal.accentGreenInt, if (pal.isDark) 96 else 82)
            style = Paint.Style.FILL
        }

        private val selectedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.accentGreenInt
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
        }

        private val lassoFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(pal.accentPurpleInt, 48)
            style = Paint.Style.FILL
        }

        private val lassoStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.accentPurpleInt
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
            pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(4f)), 0f)
        }

        private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.accentGreenInt
            style = Paint.Style.FILL
        }

        private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            color = pal.onAccentInt
            textSize = dp(9.5f)
            textAlign = Paint.Align.CENTER
        }

        private val drawRect = RectF()
        private val badgeRect = RectF()
        private val lassoRect = RectF()
        private val bgDstRect = Rect()
        private val bgBitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        private var isLassoing = false
        private var downX = 0f
        private var downY = 0f
        private var lastTapBlockId = -1
        private var lastTapTime = 0L
        private val lassoPreviewIds = LinkedHashSet<Int>()

        private val cornerR = dp(4f)
        private val padPx = dp(2f)
        private val dragSlop = dp(10f)

        fun setDimmedForSheet(dimmed: Boolean) {
            if (dimmedForSheet != dimmed) {
                dimmedForSheet = dimmed
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // 0. Frozen screenshot backdrop when running Pixel OCR (freezes videos & animations!)
            val bg = backgroundBitmap
            if (bg != null && !bg.isRecycled) {
                bgDstRect.set(0, 0, width, height)
                canvas.drawBitmap(bg, null, bgDstRect, bgBitmapPaint)
            }

            // 1. Subtle screen scrim so the actual app stays crisp & readable
            canvas.drawRect(
                0f, 0f, width.toFloat(), height.toFloat(),
                if (dimmedForSheet) scrimSheetPaint else scrimLightPaint
            )

            if (dimmedForSheet) return

            // 2. Draw crisp translucent highlights over real text regions
            var orderNum = 0
            for (i in blocks.indices) {
                val b = blocks[i]
                val isSel = (b.id in selectedIds) || (isLassoing && b.id in lassoPreviewIds)
                val fill = if (isSel) selectedFillPaint else idleFillPaint
                val stroke = if (isSel) selectedStrokePaint else idleStrokePaint

                if (b.lineRects.isNotEmpty()) {
                    for (lr in b.lineRects) {
                        drawRect.set(lr.left - padPx, lr.top - padPx, lr.right + padPx, lr.bottom + padPx)
                        canvas.drawRoundRect(drawRect, cornerR, cornerR, fill)
                        canvas.drawRoundRect(drawRect, cornerR, cornerR, stroke)
                    }
                } else {
                    val br = blockRectFs[i]
                    drawRect.set(br.left - padPx, br.top - padPx, br.right + padPx, br.bottom + padPx)
                    canvas.drawRoundRect(drawRect, cornerR, cornerR, fill)
                    canvas.drawRoundRect(drawRect, cornerR, cornerR, stroke)
                }

                // Numbered selection badge (#1, #2...) when multiple blocks are selected
                if (b.id in selectedIds && selectedIds.size > 1) {
                    orderNum++
                    val br = blockRectFs[i]
                    val bw = dp(18f)
                    val bh = dp(14f)
                    val bx = (br.left - dp(2f)).coerceIn(dp(4f), (width - bw - dp(4f)).coerceAtLeast(dp(4f)))
                    val by = (br.top - bh * 0.6f).coerceIn(topInsetPx.toFloat(), (height - bh - dp(4f)).coerceAtLeast(dp(4f)))
                    badgeRect.set(bx, by, bx + bw, by + bh)
                    canvas.drawRoundRect(badgeRect, dp(3f), dp(3f), badgeBgPaint)
                    val ty = badgeRect.centerY() - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
                    canvas.drawText(orderNum.toString(), badgeRect.centerX(), ty, badgeTextPaint)
                }
            }

            // 3. Live Drag-Lasso Selection Rectangle
            if (isLassoing && lassoRect.width() > dp(6f) && lassoRect.height() > dp(6f)) {
                canvas.drawRoundRect(lassoRect, dp(5f), dp(5f), lassoFillPaint)
                canvas.drawRoundRect(lassoRect, dp(5f), dp(5f), lassoStrokePaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (mode != OverlayMode.VISUAL) return false
            val x = event.x
            val y = event.y

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = x
                    downY = y
                    isLassoing = false
                    lassoPreviewIds.clear()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dist = hypot((x - downX).toDouble(), (y - downY).toDouble()).toFloat()
                    if (!isLassoing && dist > dragSlop) {
                        isLassoing = true
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    if (isLassoing) {
                        lassoRect.set(
                            minOf(downX, x),
                            minOf(downY, y),
                            maxOf(downX, x),
                            maxOf(downY, y)
                        )
                        val prevCount = lassoPreviewIds.size
                        lassoPreviewIds.clear()
                        for (i in blocks.indices) {
                            val b = blocks[i]
                            val hits = if (b.lineRects.isNotEmpty()) {
                                b.lineRects.any { RectF.intersects(it, lassoRect) }
                            } else {
                                RectF.intersects(blockRectFs[i], lassoRect)
                            }
                            if (hits) lassoPreviewIds.add(b.id)
                        }
                        if (lassoPreviewIds.size != prevCount) {
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        invalidate()
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (isLassoing) {
                        isLassoing = false
                        if (lassoPreviewIds.isNotEmpty()) {
                            overrideEditedText = null
                            selectedIds.addAll(lassoPreviewIds)
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            refreshSelectionState()
                        }
                        lassoPreviewIds.clear()
                        invalidate()
                        return true
                    }

                    // Single tap or double tap
                    val hitId = findBestHitBlock(x, y)
                    if (hitId != -1) {
                        val now = System.currentTimeMillis()
                        if (hitId == lastTapBlockId && now - lastTapTime < 340L) {
                            // Double-tap block -> ensure selected & open Inline Editor for precision trim!
                            overrideEditedText = null
                            if (hitId !in selectedIds) selectedIds.add(hitId)
                            refreshSelectionState()
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            openEditorForCurrentSelection()
                        } else {
                            // Single tap -> cleanly toggle this block
                            overrideEditedText = null
                            if (hitId in selectedIds) {
                                selectedIds.remove(hitId)
                            } else {
                                selectedIds.add(hitId)
                            }
                            lastTapBlockId = hitId
                            lastTapTime = now
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            refreshSelectionState()
                            invalidate()
                        }
                    } else if (!hasSelection()) {
                        // Tapping empty space with no active selection dismisses smoothly
                        onDismiss()
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    isLassoing = false
                    lassoPreviewIds.clear()
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun findBestHitBlock(x: Float, y: Float): Int {
            // Prefer exact hit on smallest-area block (handles nested/adjacent boxes accurately)
            var bestId = -1
            var bestArea = Float.MAX_VALUE
            val pad = dp(5f)

            for (i in blocks.indices) {
                val b = blocks[i]
                val hit = if (b.lineRects.isNotEmpty()) {
                    b.lineRects.any {
                        x >= it.left - pad && x <= it.right + pad && y >= it.top - pad && y <= it.bottom + pad
                    }
                } else {
                    val r = blockRectFs[i]
                    x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad
                }
                if (hit) {
                    val r = blockRectFs[i]
                    val area = r.width() * r.height()
                    if (area < bestArea) {
                        bestArea = area
                        bestId = b.id
                    }
                }
            }
            if (bestId != -1) return bestId

            // Nearest fallback within 18dp touch slop
            val maxDist = dp(18f)
            var bestDist = maxDist
            for (i in blocks.indices) {
                val r = blockRectFs[i]
                val dx = when {
                    x < r.left -> r.left - x
                    x > r.right -> x - r.right
                    else -> 0f
                }
                val dy = when {
                    y < r.top -> r.top - y
                    y > r.bottom -> y - r.bottom
                    else -> 0f
                }
                val d = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (d < bestDist) {
                    bestDist = d
                    bestId = blocks[i].id
                }
            }
            return bestId
        }
    }
}
