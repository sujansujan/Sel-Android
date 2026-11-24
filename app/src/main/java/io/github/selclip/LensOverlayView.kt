package io.github.selclip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.TextUtils
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
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot

/**
 * Effortless tap-to-select Screen Text Grab overlay with crisp ASCII PopClip aesthetics:
 *
 * - Compensates for WindowManager status-bar/cutout offsets via getLocationOnScreen()
 *   so every tap target aligns to the exact pixel under your finger on every device.
 * - Keeps the real screen crystal clear with subtle underlines/tints on tappable text.
 * - Tap any word to select it, double-tap for the full paragraph, drag across words to
 *   sweep-select, or drag `[` and `]` handles with a floating ASCII word loupe.
 * - Spawns a compact, context-aware ASCII PopClip bar right next to your selection.
 */
class LensOverlayView(
    context: Context,
    private val backgroundBitmap: Bitmap?,
    initialSnapshot: LensSnapshot?,
    private val onRunAction: (PopAction, String) -> Unit,
    private val onOpenFullPalette: (String) -> Unit,
    private val onRequestOcr: () -> Unit,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private enum class DragMode {
        NONE,
        SWEEP_SELECT,
        HANDLE_START,
        HANDLE_END
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density
    private fun dpInt(v: Int): Int = (v * density).toInt()
    private val pal = ToolPrefs.resolvePalette(context)

    private var words: List<LensWord> = emptyList()
    private var lines: List<LensLine> = emptyList()
    private var isOcrResult: Boolean = initialSnapshot?.isFromOcr == true
    private var isScanning: Boolean = initialSnapshot == null

    private var selStart: Int = -1
    private var selEnd: Int = -1
    private var isEditingInline: Boolean = false

    private var topInsetPx: Int = dpInt(34)
    private var bottomInsetPx: Int = dpInt(20)
    private val locOnScreen = IntArray(2)

    private lateinit var grabCanvas: GrabCanvasView
    private lateinit var topBar: LinearLayout
    private lateinit var topStatusText: TextView
    private lateinit var allBtn: TextView
    private lateinit var floatingBar: LinearLayout
    private lateinit var barActionsRow: LinearLayout
    private lateinit var inlineEditContainer: LinearLayout
    private lateinit var inlineEditInput: EditText

    private var currentSelectionText: String = ""

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        buildHierarchy()
        if (initialSnapshot != null) {
            applySnapshot(initialSnapshot)
        }
    }

    fun setSnapshot(snapshot: LensSnapshot) {
        post { applySnapshot(snapshot) }
    }

    private fun applySnapshot(snapshot: LensSnapshot) {
        isScanning = false
        isOcrResult = snapshot.isFromOcr
        words = snapshot.words
        lines = snapshot.lines
        updateTopStatus()
        grabCanvas.invalidate()
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
            topInsetPx = maxOf(sys.top + dpInt(6), dpInt(24))
            bottomInsetPx = maxOf(maxOf(sys.bottom, ime.bottom) + dpInt(8), dpInt(16))
        } else {
            @Suppress("DEPRECATION")
            topInsetPx = maxOf(insets.systemWindowInsetTop + dpInt(6), dpInt(24))
            @Suppress("DEPRECATION")
            bottomInsetPx = maxOf(insets.systemWindowInsetBottom + dpInt(8), dpInt(16))
        }
        (topBar.layoutParams as? LayoutParams)?.let { lp ->
            lp.topMargin = topInsetPx
            topBar.layoutParams = lp
        }
        return insets
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            when {
                isEditingInline -> closeInlineEditor()
                hasSelection() -> clearSelection()
                else -> onDismiss()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun hasSelection(): Boolean = selStart in words.indices && selEnd in words.indices

    private fun normalizedRange(): IntRange {
        val maxIdx = (words.size - 1).coerceAtLeast(0)
        val s = minOf(selStart, selEnd).coerceIn(0, maxIdx)
        val e = maxOf(selStart, selEnd).coerceIn(0, maxIdx)
        return s..e
    }

    private fun computeSelectedText(): String {
        if (!hasSelection()) return ""
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
        isEditingInline = false
        hideKeyboard()
        currentSelectionText = ""
        floatingBar.visibility = View.GONE
        inlineEditContainer.visibility = View.GONE
        updateTopStatus()
        grabCanvas.invalidate()
    }

    private fun updateTopStatus() {
        val modeTag = if (isOcrResult) "ocr" else "grab"
        topStatusText.text = when {
            isScanning -> "[sel:ocr] scanning…"
            hasSelection() -> {
                val r = normalizedRange()
                "[sel:$modeTag] ${r.last - r.first + 1}w"
            }
            words.isEmpty() -> "[sel:$modeTag] no text · try [OCR]"
            else -> "[sel:$modeTag] tap any text"
        }
        allBtn.text = if (words.isNotEmpty() && selStart == 0 && selEnd == words.lastIndex) "[CLR]" else "[ALL]"
    }

    private fun hideKeyboard() {
        runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(windowToken, 0)
        }
    }

    private fun openInlineEditor() {
        isEditingInline = true
        inlineEditContainer.visibility = View.VISIBLE
        inlineEditInput.setText(currentSelectionText)
        inlineEditInput.setSelection(inlineEditInput.text?.length ?: 0)
        inlineEditInput.post {
            inlineEditInput.requestFocus()
            runCatching {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(inlineEditInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun closeInlineEditor() {
        isEditingInline = false
        hideKeyboard()
        val edited = inlineEditInput.text?.toString()?.trim().orEmpty()
        if (edited.isNotEmpty()) currentSelectionText = edited
        inlineEditContainer.visibility = View.GONE
    }

    private fun updateFloatingPopBar(anchorLocalRect: RectF?) {
        currentSelectionText = computeSelectedText()
        updateTopStatus()

        if (currentSelectionText.isEmpty()) {
            floatingBar.visibility = View.GONE
            return
        }

        // Build compact, context-aware ASCII PopClip bar right next to the selected text!
        barActionsRow.removeAllViews()
        val ctx = PopupContextEngine.analyze(currentSelectionText, isEditable = false)
        val actions = PopupContextEngine.resolveBarActions(ctx, maxItems = 4)

        actions.forEachIndexed { idx, act ->
            if (idx > 0) barActionsRow.addView(makeBarDivider())
            barActionsRow.addView(makeBarCell(act.label, act.accent) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                val finalText = if (isEditingInline) inlineEditInput.text?.toString()?.trim().orEmpty().ifEmpty { currentSelectionText } else currentSelectionText
                onRunAction(act, finalText)
            })
        }

        barActionsRow.addView(makeBarDivider())
        barActionsRow.addView(makeBarCell("EDIT", false) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (isEditingInline) closeInlineEditor() else openInlineEditor()
        })

        barActionsRow.addView(makeBarDivider())
        barActionsRow.addView(makeBarCell("+", false) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val finalText = if (isEditingInline) inlineEditInput.text?.toString()?.trim().orEmpty().ifEmpty { currentSelectionText } else currentSelectionText
            onOpenFullPalette(finalText)
        })

        floatingBar.visibility = View.VISIBLE

        // Position floating PopClip bar neatly right ABOVE (or BELOW) the selected text!
        if (anchorLocalRect != null && height > 0) {
            floatingBar.post {
                val barW = floatingBar.width.coerceAtLeast(dpInt(220))
                val barH = floatingBar.height.coerceAtLeast(dpInt(44))
                val lp = floatingBar.layoutParams as LayoutParams
                val minTop = topInsetPx + dpInt(46)
                val maxBottom = height - bottomInsetPx - dpInt(8)
                val gap = dpInt(14)
                val handleClearance = dpInt(36)

                val aboveY = anchorLocalRect.top.toInt() - gap - barH
                val belowY = anchorLocalRect.bottom.toInt() + handleClearance
                val targetY = when {
                    aboveY >= minTop -> aboveY
                    belowY + barH <= maxBottom -> belowY
                    else -> (maxBottom - barH).coerceAtLeast(minTop)
                }

                val targetX = (anchorLocalRect.centerX().toInt() - barW / 2)
                    .coerceIn(dpInt(8), (width - barW - dpInt(8)).coerceAtLeast(dpInt(8)))

                lp.gravity = Gravity.TOP or Gravity.START
                lp.leftMargin = targetX
                lp.topMargin = targetY
                floatingBar.layoutParams = lp
            }
        }
    }

    private fun buildHierarchy() {
        grabCanvas = GrabCanvasView(context)
        addView(grabCanvas, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Ultra-compact minimal ASCII top pill bar
        topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpInt(10), dpInt(5), dpInt(6), dpInt(5))
            background = GradientDrawable().apply {
                setColor(withAlpha(pal.bgHeaderInt, 240))
                setStroke(dpInt(1), pal.borderInt)
                cornerRadius = dp(3f)
            }
            elevation = dp(4f)
        }

        topStatusText = TextView(context).apply {
            text = "[sel:grab] tap any text"
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(pal.accentGreenInt)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        allBtn = makeTopAction("[ALL]", pal.fgPrimaryInt) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (words.isNotEmpty() && selStart == 0 && selEnd == words.lastIndex) {
                clearSelection()
            } else if (words.isNotEmpty()) {
                selStart = 0
                selEnd = words.lastIndex
                grabCanvas.invalidate()
                updateFloatingPopBar(grabCanvas.selectionLocalBounds())
            }
        }

        val ocrBtn = makeTopAction("[OCR]", pal.accentCyanInt) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onRequestOcr()
        }

        val closeBtn = makeTopAction("[X]", pal.dangerRedInt) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onDismiss()
        }

        topBar.addView(topStatusText)
        topBar.addView(allBtn)
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

        // Floating Context-Aware ASCII PopClip Bar (appears right next to tapped text!)
        floatingBar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(pal.bgPrimaryInt)
                setStroke(dpInt(1), pal.accentGreenInt)
                cornerRadius = dp(3f)
            }
            elevation = dp(8f)
        }

        barActionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        floatingBar.addView(barActionsRow)

        inlineEditContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dpInt(8), dpInt(6), dpInt(8), dpInt(8))
        }
        inlineEditInput = EditText(context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(pal.fgPrimaryInt)
            setBackgroundColor(pal.bgInputInt)
            setPadding(dpInt(8), dpInt(6), dpInt(8), dpInt(6))
            maxLines = 4
            minWidth = dpInt(220)
        }
        inlineEditContainer.addView(inlineEditInput)
        floatingBar.addView(inlineEditContainer)

        addView(
            floatingBar,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
        )
    }

    private fun makeTopAction(label: String, fgColor: Int, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(fgColor)
            setPadding(dpInt(6), dpInt(4), dpInt(6), dpInt(4))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(pal.bgPressedInt) })
                addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            }
            setOnClickListener { onClick() }
        }
    }

    private fun makeBarCell(label: String, accent: Boolean, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(if (accent) pal.accentGreenInt else pal.fgPrimaryInt)
            gravity = Gravity.CENTER
            isSingleLine = true
            setPadding(dpInt(10), dpInt(10), dpInt(10), dpInt(10))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(pal.bgPressedInt) })
                addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            }
            setOnClickListener { onClick() }
        }
    }

    private fun makeBarDivider(): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpInt(1), dpInt(20))
            setBackgroundColor(pal.dividerInt)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    /**
     * Pixel-aligned tap-to-select Canvas.
     * Always offsets screen coordinates by -getLocationOnScreen() so taps match exact pixels!
     */
    private inner class GrabCanvasView(context: Context) : View(context) {

        private var dragMode = DragMode.NONE
        private var anchorWordIndex = -1
        private var touchX = 0f
        private var touchY = 0f
        private var lastTapWord = -1
        private var lastTapTime = 0L

        private val startHandleRect = RectF()
        private val endHandleRect = RectF()
        private val startHandleHit = RectF()
        private val endHandleHit = RectF()

        private val bgDst = Rect()
        private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        private val clearScrimPaint = Paint().apply {
            color = Color.argb(if (pal.isDark) 28 else 16, 0, 0, 0)
            style = Paint.Style.FILL
        }

        // Ultra-subtle underline/tint so real text stays 100% readable
        private val tappableTintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(pal.accentCyanInt, if (pal.isDark) 22 else 18)
            style = Paint.Style.FILL
        }
        private val tappableUnderlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(pal.accentCyanInt, if (pal.isDark) 110 else 130)
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
        }

        private val selectedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(pal.accentGreenInt, if (pal.isDark) 96 else 80)
            style = Paint.Style.FILL
        }
        private val selectedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.accentGreenInt
            style = Paint.Style.STROKE
            strokeWidth = dp(1.4f)
        }

        private val handleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.bgHeaderInt
            style = Paint.Style.FILL
        }
        private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.accentGreenInt
            style = Paint.Style.STROKE
            strokeWidth = dp(1.6f)
        }
        private val handleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            color = pal.accentGreenInt
            textSize = dp(13f)
            textAlign = Paint.Align.CENTER
        }

        private val loupeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.bgHeaderInt
            style = Paint.Style.FILL
        }
        private val loupeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            color = pal.fgPrimaryInt
            textSize = dp(12f)
            textAlign = Paint.Align.CENTER
        }

        private val tempRect = RectF()
        private val padX = dp(2.5f)
        private val padY = dp(1.5f)
        private val handleW = dp(24f)
        private val handleH = dp(26f)
        private val hitSlop = dp(20f)

        private fun refreshScreenOffset() {
            getLocationOnScreen(locOnScreen)
        }

        private fun toLocal(src: RectF, dst: RectF): RectF {
            dst.set(
                src.left - locOnScreen[0],
                src.top - locOnScreen[1],
                src.right - locOnScreen[0],
                src.bottom - locOnScreen[1]
            )
            return dst
        }

        fun selectionLocalBounds(): RectF? {
            if (!hasSelection()) return null
            refreshScreenOffset()
            val r = normalizedRange()
            val union = RectF()
            var first = true
            for (i in r) {
                val local = toLocal(words[i].rect, RectF())
                if (first) {
                    union.set(local)
                    first = false
                } else {
                    union.union(local)
                }
            }
            return union
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            refreshScreenOffset()

            val bmp = backgroundBitmap
            if (bmp != null && !bmp.isRecycled) {
                bgDst.set(-locOnScreen[0], -locOnScreen[1], bmp.width - locOnScreen[0], bmp.height - locOnScreen[1])
                canvas.drawBitmap(bmp, null, bgDst, bgPaint)
            }

            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), clearScrimPaint)

            // 1. Subtle underlines & faint tint over tappable lines (keeps screen super clean!)
            for (line in lines) {
                toLocal(line.bounds, tempRect)
                canvas.drawRect(tempRect, tappableTintPaint)
                canvas.drawLine(tempRect.left, tempRect.bottom, tempRect.right, tempRect.bottom, tappableUnderlinePaint)
            }

            // 2. Crisp continuous per-line highlight + ASCII `[` `]` handles
            if (hasSelection()) {
                val range = normalizedRange()
                var curLine = -1
                var lineUnion: RectF? = null
                for (i in range) {
                    val w = words[i]
                    val localW = toLocal(w.rect, RectF())
                    if (w.lineIndex != curLine) {
                        lineUnion?.let { u ->
                            tempRect.set(u.left - padX, u.top - padY, u.right + padX, u.bottom + padY)
                            canvas.drawRoundRect(tempRect, dp(2f), dp(2f), selectedFillPaint)
                            canvas.drawRoundRect(tempRect, dp(2f), dp(2f), selectedBorderPaint)
                        }
                        curLine = w.lineIndex
                        lineUnion = localW
                    } else {
                        lineUnion?.union(localW)
                    }
                }
                lineUnion?.let { u ->
                    tempRect.set(u.left - padX, u.top - padY, u.right + padX, u.bottom + padY)
                    canvas.drawRoundRect(tempRect, dp(2f), dp(2f), selectedFillPaint)
                    canvas.drawRoundRect(tempRect, dp(2f), dp(2f), selectedBorderPaint)
                }

                // Draw `[` and `]` ASCII selection handles
                val firstL = toLocal(words[range.first].rect, RectF())
                val lastL = toLocal(words[range.last].rect, RectF())

                val sx = (firstL.left - handleW * 0.55f).coerceIn(dp(4f), (width - handleW - dp(4f)).coerceAtLeast(dp(4f)))
                val sy = (firstL.bottom + dp(3f)).coerceIn(topInsetPx.toFloat(), (height - handleH - dp(4f)).coerceAtLeast(dp(4f)))
                startHandleRect.set(sx, sy, sx + handleW, sy + handleH)
                startHandleHit.set(sx - hitSlop, sy - hitSlop, sx + handleW + hitSlop, sy + handleH + hitSlop)

                val ex = (lastL.right - handleW * 0.45f).coerceIn(dp(4f), (width - handleW - dp(4f)).coerceAtLeast(dp(4f)))
                val ey = (lastL.bottom + dp(3f)).coerceIn(topInsetPx.toFloat(), (height - handleH - dp(4f)).coerceAtLeast(dp(4f)))
                endHandleRect.set(ex, ey, ex + handleW, ey + handleH)
                endHandleHit.set(ex - hitSlop, ey - hitSlop, ex + handleW + hitSlop, ey + handleH + hitSlop)

                drawAsciiHandle(canvas, firstL.left, firstL.top, startHandleRect, "[")
                drawAsciiHandle(canvas, lastL.right, lastL.top, endHandleRect, "]")

                // Floating ASCII loupe while dragging a handle
                if (dragMode == DragMode.HANDLE_START || dragMode == DragMode.HANDLE_END) {
                    val idx = if (dragMode == DragMode.HANDLE_START) selStart else selEnd
                    if (idx in words.indices) {
                        val txt = "[ ${words[idx].text.take(20)} ]"
                        val tw = loupeTextPaint.measureText(txt) + dp(20f)
                        val th = dp(30f)
                        val cx = touchX.coerceIn(tw / 2f + dp(8f), (width - tw / 2f - dp(8f)).coerceAtLeast(tw / 2f))
                        val cy = (touchY - dp(62f)).coerceIn(topInsetPx + dp(52f), (height - dp(100f)).coerceAtLeast(topInsetPx + dp(52f)))
                        tempRect.set(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f)
                        canvas.drawRoundRect(tempRect, dp(3f), dp(3f), loupeBgPaint)
                        canvas.drawRoundRect(tempRect, dp(3f), dp(3f), handleStrokePaint)
                        val ty = tempRect.centerY() - (loupeTextPaint.descent() + loupeTextPaint.ascent()) / 2f
                        canvas.drawText(txt, tempRect.centerX(), ty, loupeTextPaint)
                    }
                }
            }
        }

        private fun drawAsciiHandle(canvas: Canvas, edgeX: Float, topY: Float, rect: RectF, glyph: String) {
            canvas.drawLine(edgeX, topY, edgeX, rect.top, handleStrokePaint)
            canvas.drawRoundRect(rect, dp(3f), dp(3f), handleBgPaint)
            canvas.drawRoundRect(rect, dp(3f), dp(3f), handleStrokePaint)
            val ty = rect.centerY() - (handleTextPaint.descent() + handleTextPaint.ascent()) / 2f
            canvas.drawText(glyph, rect.centerX(), ty, handleTextPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (isScanning || isEditingInline) return false
            refreshScreenOffset()
            val x = event.x
            val y = event.y
            touchX = x
            touchY = y

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (hasSelection()) {
                        val hitStart = startHandleHit.contains(x, y)
                        val hitEnd = endHandleHit.contains(x, y)
                        if (hitStart || hitEnd) {
                            val r = normalizedRange()
                            selStart = r.first
                            selEnd = r.last
                            dragMode = if (hitStart && (!hitEnd || hypot(x - startHandleRect.centerX(), y - startHandleRect.centerY()) <= hypot(x - endHandleRect.centerX(), y - endHandleRect.centerY()))) {
                                DragMode.HANDLE_START
                            } else {
                                DragMode.HANDLE_END
                            }
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            invalidate()
                            return true
                        }
                    }

                    val hitIdx = findWordAtLocal(x, y, maxDist = dp(24f))
                    if (hitIdx != -1) {
                        val now = System.currentTimeMillis()
                        if (hitIdx == lastTapWord && now - lastTapTime < 340L) {
                            // Double-tap -> select full block/paragraph
                            val blk = words[hitIdx].blockIndex
                            selStart = words.indexOfFirst { it.blockIndex == blk }.coerceAtLeast(0)
                            selEnd = words.indexOfLast { it.blockIndex == blk }.coerceAtLeast(selStart)
                            dragMode = DragMode.NONE
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            invalidate()
                            updateFloatingPopBar(selectionLocalBounds())
                        } else {
                            // Single tap -> select tapped word (and sweep if dragged!)
                            dragMode = DragMode.SWEEP_SELECT
                            anchorWordIndex = hitIdx
                            selStart = hitIdx
                            selEnd = hitIdx
                            lastTapWord = hitIdx
                            lastTapTime = now
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            invalidate()
                            updateFloatingPopBar(selectionLocalBounds())
                        }
                        return true
                    }

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
                            val hit = findWordAtLocal(x, y, maxDist = dp(40f))
                            if (hit != -1 && hit != selEnd) {
                                selStart = anchorWordIndex
                                selEnd = hit
                                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                invalidate()
                                updateFloatingPopBar(selectionLocalBounds())
                            }
                        }
                        DragMode.HANDLE_START -> {
                            val hit = findWordAtLocal(x, y - dp(20f), maxDist = dp(52f))
                            if (hit != -1 && hit != selStart) {
                                selStart = hit
                                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                updateFloatingPopBar(selectionLocalBounds())
                            }
                            invalidate()
                        }
                        DragMode.HANDLE_END -> {
                            val hit = findWordAtLocal(x, y - dp(20f), maxDist = dp(52f))
                            if (hit != -1 && hit != selEnd) {
                                selEnd = hit
                                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                updateFloatingPopBar(selectionLocalBounds())
                            }
                            invalidate()
                        }
                        DragMode.NONE -> {}
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (hasSelection()) {
                        val r = normalizedRange()
                        selStart = r.first
                        selEnd = r.last
                        updateFloatingPopBar(selectionLocalBounds())
                    }
                    dragMode = DragMode.NONE
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun findWordAtLocal(lx: Float, ly: Float, maxDist: Float): Int {
            val sx = lx + locOnScreen[0]
            val sy = ly + locOnScreen[1]
            for (w in words) {
                if (sx >= w.rect.left - padX && sx <= w.rect.right + padX &&
                    sy >= w.rect.top - padY && sy <= w.rect.bottom + padY
                ) {
                    return w.index
                }
            }
            var bestIdx = -1
            var bestDist = maxDist
            for (w in words) {
                val dx = when {
                    sx < w.rect.left -> w.rect.left - sx
                    sx > w.rect.right -> sx - w.rect.right
                    else -> 0f
                }
                val dy = when {
                    sy < w.rect.top -> w.rect.top - sy
                    sy > w.rect.bottom -> sy - w.rect.bottom
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
