package io.github.selclip

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

data class LensBlock(
    val text: String,
    val bounds: Rect
)

data class LensWord(
    val text: String,
    val rect: RectF,
    val blockIndex: Int,
    val globalIndex: Int
)

/**
 * Google Lens-style on-screen text selection rendered in a crisp plain-text terminal aesthetic:
 * - Breaks every on-screen text block into individual interactive word chips at their exact screen coordinates.
 * - Supports both contiguous range selection (with draggable `[` and `]` handles) AND multi-region `[ + ADD ]` selection!
 * - Dynamically adapts the bottom smart button (`🧲 MAGNET`, `⌫ CLEAN URL`, `📖 DEFINE`, `📞 DIAL`, or `⚡ TOOLS ▸`) based on what you highlight.
 */
class LensOverlayView(
    context: Context,
    private val blocks: List<LensBlock>,
    private val onCopyText: (String) -> Unit,
    private val onSmartAction: (String, String) -> Unit,
    private val onOpenInTools: (String) -> Unit,
    private val onSearchText: (String) -> Unit,
    private val onCaptureScreenshot: () -> Unit,
    private val onDismiss: () -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val words = mutableListOf<LensWord>()
    private val blockRects = mutableListOf<RectF>()

    private var selStart: Int = -1
    private var selEnd: Int = -1
    private var multiSelectMode: Boolean = false
    private val multiSelectedIndices = LinkedHashSet<Int>()

    private enum class DragMode {
        NONE,
        SWIPE_WORDS,
        HANDLE_START,
        HANDLE_END
    }

    private var dragMode = DragMode.NONE
    private var anchorWordIndex = -1
    private var lastTappedWord = -1
    private var lastTapTime = 0L

    private val startHandleCenter = RectF()
    private val endHandleCenter = RectF()

    private val topMultiBtnRect = RectF()
    private val topSnapBtnRect = RectF()
    private val topAllBtnRect = RectF()
    private val topEscBtnRect = RectF()

    private val bottomBarRect = RectF()
    private val btnCopyRect = RectF()
    private val btnSmartRect = RectF()
    private val btnSearchRect = RectF()
    private val btnClearRect = RectF()

    private val scrimPaint = Paint().apply {
        color = Color.parseColor("#C8060606")
        style = Paint.Style.FILL
    }

    private val blockFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E00FF66")
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
    }

    private val chipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EB111613")
        style = Paint.Style.FILL
    }

    private val chipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5500FF66")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }

    private val chipSelectedBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF66")
        style = Paint.Style.FILL
    }

    private val wordTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        color = Color.parseColor("#F2F2F2")
        textAlign = Paint.Align.CENTER
    }

    private val wordSelectedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = Color.parseColor("#080808")
        textAlign = Paint.Align.CENTER
    }

    private val handleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A0A0A")
        style = Paint.Style.FILL
    }

    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF66")
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
    }

    private val handleGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = Color.parseColor("#00FF66")
        textSize = dp(13f)
        textAlign = Paint.Align.CENTER
    }

    private val hudBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F50B0B0B")
        style = Paint.Style.FILL
    }

    private val hudStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF66")
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }

    private val hudTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = Color.parseColor("#00FF66")
        textSize = dp(11f)
    }

    private val hudSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        color = Color.parseColor("#CFCFCF")
        textSize = dp(11f)
    }

    private val btnPrimaryBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF66")
        style = Paint.Style.FILL
    }

    private val btnSecondaryBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1A1A")
        style = Paint.Style.FILL
    }

    private val btnSecondaryStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3E3E3E")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }

    private val btnPrimaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = Color.parseColor("#080808")
        textSize = dp(11f)
        textAlign = Paint.Align.CENTER
    }

    private val btnSecondaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = Color.parseColor("#F0F0F0")
        textSize = dp(11f)
        textAlign = Paint.Align.CENTER
    }

    init {
        buildWordLayout()
    }

    private fun buildWordLayout() {
        words.clear()
        blockRects.clear()
        var gIdx = 0

        blocks.forEachIndexed { bIdx, block ->
            val b = block.bounds
            val bRect = RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            blockRects.add(bRect)

            val rawLines = block.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (rawLines.isEmpty()) return@forEachIndexed

            val estLines = if (rawLines.size > 1) {
                rawLines
            } else {
                val single = rawLines[0]
                val avgCharW = dp(7.5f).coerceAtLeast(1f)
                val maxCharsPerLine = ((b.width() / avgCharW).toInt()).coerceAtLeast(8)
                if (single.length <= maxCharsPerLine) {
                    listOf(single)
                } else {
                    wrapTextIntoLines(single, maxCharsPerLine)
                }
            }

            val lineCount = estLines.size.coerceAtLeast(1)
            val lineH = (b.height().toFloat() / lineCount).coerceIn(dp(18f), dp(42f))

            estLines.forEachIndexed { lineIdx, lineText ->
                val tokens = lineText.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (tokens.isEmpty()) return@forEachIndexed

                val totalChars = tokens.sumOf { it.length }.coerceAtLeast(1)
                val gap = dp(4f)
                val totalGaps = gap * (tokens.size - 1).coerceAtLeast(0)
                val availW = (b.width().toFloat() - totalGaps).coerceAtLeast(dp(24f))

                var curX = b.left.toFloat()
                val topY = b.top.toFloat() + lineIdx * lineH
                val bottomY = (topY + lineH - dp(2f)).coerceAtLeast(topY + dp(16f))

                tokens.forEachIndexed { tIdx, token ->
                    val frac = token.length.toFloat() / totalChars.toFloat()
                    val w = if (tIdx == tokens.lastIndex) {
                        (b.right.toFloat() - curX).coerceAtLeast(dp(18f))
                    } else {
                        (availW * frac).coerceAtLeast(dp(18f))
                    }
                    val rect = RectF(curX, topY, curX + w, bottomY)
                    words.add(
                        LensWord(
                            text = token,
                            rect = rect,
                            blockIndex = bIdx,
                            globalIndex = gIdx++
                        )
                    )
                    curX += w + gap
                }
            }
        }
    }

    private fun wrapTextIntoLines(text: String, maxCharsPerLine: Int): List<String> {
        val result = mutableListOf<String>()
        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var current = StringBuilder()
        for (tok in tokens) {
            if (current.isEmpty()) {
                current.append(tok)
            } else if (current.length + 1 + tok.length <= maxCharsPerLine) {
                current.append(' ').append(tok)
            } else {
                result.add(current.toString())
                current = StringBuilder(tok)
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }

    private fun hasSelection(): Boolean {
        return if (multiSelectMode) {
            multiSelectedIndices.isNotEmpty()
        } else {
            selStart in words.indices && selEnd in words.indices
        }
    }

    private fun normalizedRange(): IntRange {
        val s = minOf(selStart, selEnd).coerceIn(0, (words.size - 1).coerceAtLeast(0))
        val e = maxOf(selStart, selEnd).coerceIn(0, (words.size - 1).coerceAtLeast(0))
        return s..e
    }

    private fun isWordSelected(idx: Int): Boolean {
        return if (multiSelectMode) {
            idx in multiSelectedIndices
        } else {
            hasSelection() && idx in normalizedRange()
        }
    }

    fun getSelectedText(): String {
        if (!hasSelection() || words.isEmpty()) return ""
        val indices = if (multiSelectMode) {
            multiSelectedIndices.filter { it in words.indices }
        } else {
            normalizedRange().toList()
        }
        val sb = StringBuilder()
        var prevBlock = -1
        for (i in indices) {
            val w = words[i]
            if (prevBlock != -1) {
                if (w.blockIndex != prevBlock) {
                    sb.append('\n')
                } else {
                    sb.append(' ')
                }
            }
            sb.append(w.text)
            prevBlock = w.blockIndex
        }
        return sb.toString().trim()
    }

    /**
     * Context-aware smart action pill in Lens Bottom Dock:
     * Inspects the highlighted words and returns (actionId, buttonLabel).
     */
    private fun resolveSmartAction(text: String): Pair<String, String> {
        val trimmed = text.trim()
        if (Regex("\\b([0-9a-fA-F]{40}|[0-9a-fA-F]{64}|[A-Za-z2-7]{32})\\b").containsMatchIn(trimmed) ||
            trimmed.startsWith("magnet:?", ignoreCase = true)
        ) {
            return "magnet" to "🧲 MAGNET"
        }
        if (!trimmed.contains(" ") && (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true))) {
            return if (trimmed.contains("?") || trimmed.contains("&")) {
                "clean_url" to "⌫ CLEAN URL"
            } else {
                "open_url" to "↗ OPEN URL"
            }
        }
        if (Regex("^\\+?[0-9][0-9\\- ()]{6,16}[0-9]$").matches(trimmed)) {
            return "dial" to "📞 DIAL"
        }
        val wordCount = trimmed.split(Regex("\\s+")).count { it.isNotEmpty() }
        if (wordCount in 1..3 && trimmed.any { it.isLetter() } && trimmed.length <= 36) {
            return "define" to "📖 DEFINE"
        }
        return "tools" to "⚡ TOOLS ▸"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val viewW = width.toFloat()
        val viewH = height.toFloat()

        // 1. Dark Google-Lens translucent terminal backdrop
        canvas.drawRect(0f, 0f, viewW, viewH, scrimPaint)

        // 2. Subtle block outlines
        for (bRect in blockRects) {
            canvas.drawRoundRect(bRect, dp(3f), dp(3f), blockFramePaint)
        }

        // 3. Draw each detected word chip at its screen position
        for (w in words) {
            val isSel = isWordSelected(w.globalIndex)
            val r = w.rect
            val corner = dp(3f)

            if (isSel) {
                canvas.drawRoundRect(r, corner, corner, chipSelectedBgPaint)
            } else {
                canvas.drawRoundRect(r, corner, corner, chipBgPaint)
                canvas.drawRoundRect(r, corner, corner, chipBorderPaint)
            }

            val paint = if (isSel) wordSelectedTextPaint else wordTextPaint
            val maxTextH = (r.height() * 0.62f).coerceIn(dp(9.5f), dp(14f))
            val fitTextW = ((r.width() - dp(4f)) / (w.text.length.coerceAtLeast(1) * 0.62f)).coerceIn(dp(8.5f), maxTextH)
            paint.textSize = fitTextW

            val textY = r.centerY() - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(w.text, r.centerX(), textY, paint)
        }

        // 4. Draw draggable plain-text terminal handles '[' and ']' when contiguous text is selected
        if (hasSelection() && !multiSelectMode) {
            val range = normalizedRange()
            val firstWord = words[range.first].rect
            val lastWord = words[range.last].rect

            val handleW = dp(24f)
            val handleH = dp(26f)

            startHandleCenter.set(
                (firstWord.left - handleW * 0.55f).coerceIn(dp(6f), viewW - handleW - dp(6f)),
                (firstWord.bottom + dp(3f)).coerceIn(dp(40f), viewH - handleH - dp(8f)),
                0f,
                0f
            )
            startHandleCenter.right = startHandleCenter.left + handleW
            startHandleCenter.bottom = startHandleCenter.top + handleH

            endHandleCenter.set(
                (lastWord.right - handleW * 0.45f).coerceIn(dp(6f), viewW - handleW - dp(6f)),
                (lastWord.bottom + dp(3f)).coerceIn(dp(40f), viewH - handleH - dp(8f)),
                0f,
                0f
            )
            endHandleCenter.right = endHandleCenter.left + handleW
            endHandleCenter.bottom = endHandleCenter.top + handleH

            canvas.drawLine(firstWord.left, firstWord.top, firstWord.left, startHandleCenter.top, handleStrokePaint)
            canvas.drawRoundRect(startHandleCenter, dp(4f), dp(4f), handleBgPaint)
            canvas.drawRoundRect(startHandleCenter, dp(4f), dp(4f), handleStrokePaint)
            val sy = startHandleCenter.centerY() - (handleGlyphPaint.descent() + handleGlyphPaint.ascent()) / 2f
            canvas.drawText("[", startHandleCenter.centerX(), sy, handleGlyphPaint)

            canvas.drawLine(lastWord.right, lastWord.top, lastWord.right, endHandleCenter.top, handleStrokePaint)
            canvas.drawRoundRect(endHandleCenter, dp(4f), dp(4f), handleBgPaint)
            canvas.drawRoundRect(endHandleCenter, dp(4f), dp(4f), handleStrokePaint)
            val ey = endHandleCenter.centerY() - (handleGlyphPaint.descent() + handleGlyphPaint.ascent()) / 2f
            canvas.drawText("]", endHandleCenter.centerX(), ey, handleGlyphPaint)
        }

        // 5. Top Plain-Text Lens HUD Bar with [+ADD], [📸], [ALL], [×ESC]
        val topHudRect = RectF(dp(10f), dp(36f), viewW - dp(10f), dp(84f))
        canvas.drawRoundRect(topHudRect, dp(4f), dp(4f), hudBgPaint)
        canvas.drawRoundRect(topHudRect, dp(4f), dp(4f), hudStrokePaint)

        val selectedCount = if (multiSelectMode) {
            multiSelectedIndices.size
        } else if (hasSelection()) {
            val r = normalizedRange()
            r.last - r.first + 1
        } else {
            0
        }

        val hudTitle = if (selectedCount > 0) {
            "⊞ ${selectedCount}W SELECTED"
        } else {
            "⊞ LENS (${words.size}W)"
        }
        val titleY = topHudRect.centerY() - (hudTitlePaint.descent() + hudTitlePaint.ascent()) / 2f
        canvas.drawText(hudTitle, topHudRect.left + dp(10f), titleY, hudTitlePaint)

        val btnHPad = dp(6f)
        val escW = dp(64f)
        val allW = dp(42f)
        val snapW = dp(40f)
        val multiW = dp(54f)

        topEscBtnRect.set(
            topHudRect.right - escW - dp(6f),
            topHudRect.top + btnHPad,
            topHudRect.right - dp(6f),
            topHudRect.bottom - btnHPad
        )
        topAllBtnRect.set(
            topEscBtnRect.left - allW - dp(5f),
            topHudRect.top + btnHPad,
            topEscBtnRect.left - dp(5f),
            topHudRect.bottom - btnHPad
        )
        topSnapBtnRect.set(
            topAllBtnRect.left - snapW - dp(5f),
            topHudRect.top + btnHPad,
            topAllBtnRect.left - dp(5f),
            topHudRect.bottom - btnHPad
        )
        topMultiBtnRect.set(
            topSnapBtnRect.left - multiW - dp(5f),
            topHudRect.top + btnHPad,
            topSnapBtnRect.left - dp(5f),
            topHudRect.bottom - btnHPad
        )

        // [+ADD] multi-select toggle button
        if (multiSelectMode) {
            canvas.drawRoundRect(topMultiBtnRect, dp(3f), dp(3f), btnPrimaryBgPaint)
            canvas.drawText(
                "+ADD✓",
                topMultiBtnRect.centerX(),
                topMultiBtnRect.centerY() - (btnPrimaryTextPaint.descent() + btnPrimaryTextPaint.ascent()) / 2f,
                btnPrimaryTextPaint
            )
        } else {
            canvas.drawRoundRect(topMultiBtnRect, dp(3f), dp(3f), btnSecondaryBgPaint)
            canvas.drawRoundRect(topMultiBtnRect, dp(3f), dp(3f), btnSecondaryStrokePaint)
            canvas.drawText(
                "+ADD",
                topMultiBtnRect.centerX(),
                topMultiBtnRect.centerY() - (btnSecondaryTextPaint.descent() + btnSecondaryTextPaint.ascent()) / 2f,
                btnSecondaryTextPaint
            )
        }

        // [📸] screenshot/image fallback button
        canvas.drawRoundRect(topSnapBtnRect, dp(3f), dp(3f), btnSecondaryBgPaint)
        canvas.drawRoundRect(topSnapBtnRect, dp(3f), dp(3f), btnSecondaryStrokePaint)
        canvas.drawText(
            "📸",
            topSnapBtnRect.centerX(),
            topSnapBtnRect.centerY() - (btnSecondaryTextPaint.descent() + btnSecondaryTextPaint.ascent()) / 2f,
            btnSecondaryTextPaint
        )

        // [ALL] button
        canvas.drawRoundRect(topAllBtnRect, dp(3f), dp(3f), btnSecondaryBgPaint)
        canvas.drawRoundRect(topAllBtnRect, dp(3f), dp(3f), btnSecondaryStrokePaint)
        canvas.drawText(
            "ALL",
            topAllBtnRect.centerX(),
            topAllBtnRect.centerY() - (btnSecondaryTextPaint.descent() + btnSecondaryTextPaint.ascent()) / 2f,
            btnSecondaryTextPaint
        )

        // [×ESC] button
        canvas.drawRoundRect(topEscBtnRect, dp(3f), dp(3f), btnSecondaryBgPaint)
        canvas.drawRoundRect(topEscBtnRect, dp(3f), dp(3f), btnSecondaryStrokePaint)
        canvas.drawText(
            "× ESC",
            topEscBtnRect.centerX(),
            topEscBtnRect.centerY() - (btnSecondaryTextPaint.descent() + btnSecondaryTextPaint.ascent()) / 2f,
            btnSecondaryTextPaint
        )

        // 6. Bottom Google Lens-Style Action Dock when words are selected
        if (hasSelection()) {
            val selText = getSelectedText()
            val (_, smartLabel) = resolveSmartAction(selText)
            val barH = dp(98f)
            bottomBarRect.set(dp(12f), viewH - barH - dp(22f), viewW - dp(12f), viewH - dp(22f))
            canvas.drawRoundRect(bottomBarRect, dp(5f), dp(5f), hudBgPaint)
            canvas.drawRoundRect(bottomBarRect, dp(5f), dp(5f), hudStrokePaint)

            val singleLinePreview = selText.replace("\n", " ↵ ").let {
                if (it.length > 44) it.take(43) + "…" else it
            }
            canvas.drawText(
                "\"$singleLinePreview\"",
                bottomBarRect.left + dp(12f),
                bottomBarRect.top + dp(24f),
                hudSubPaint
            )

            val btnTop = bottomBarRect.top + dp(40f)
            val btnBottom = bottomBarRect.bottom - dp(10f)
            val innerW = bottomBarRect.width() - dp(24f)
            val gap = dp(8f)

            val clearW = dp(40f)
            val mainBtnW = (innerW - clearW - gap * 3f) / 3f

            var curLeft = bottomBarRect.left + dp(12f)
            btnCopyRect.set(curLeft, btnTop, curLeft + mainBtnW, btnBottom)
            curLeft += mainBtnW + gap
            btnSmartRect.set(curLeft, btnTop, curLeft + mainBtnW, btnBottom)
            curLeft += mainBtnW + gap
            btnSearchRect.set(curLeft, btnTop, curLeft + mainBtnW, btnBottom)
            curLeft += mainBtnW + gap
            btnClearRect.set(curLeft, btnTop, curLeft + clearW, btnBottom)

            // Primary green [ ⎘ COPY ] button
            canvas.drawRoundRect(btnCopyRect, dp(3f), dp(3f), btnPrimaryBgPaint)
            val btnTextY = btnCopyRect.centerY() - (btnPrimaryTextPaint.descent() + btnPrimaryTextPaint.ascent()) / 2f
            canvas.drawText("⎘ COPY", btnCopyRect.centerX(), btnTextY, btnPrimaryTextPaint)

            // Context-Aware Smart Pill ([🧲 MAGNET], [⌫ CLEAN URL], [📖 DEFINE], [📞 DIAL], or [⚡ TOOLS ▸])
            canvas.drawRoundRect(btnSmartRect, dp(3f), dp(3f), btnSecondaryBgPaint)
            canvas.drawRoundRect(btnSmartRect, dp(3f), dp(3f), hudStrokePaint)
            canvas.drawText(smartLabel, btnSmartRect.centerX(), btnTextY, btnSecondaryTextPaint)

            // [ ⚡ TOOLS ▸ ] or [ ↗ SEARCH ] button
            val thirdLabel = if (smartLabel == "⚡ TOOLS ▸") "↗ SEARCH" else "⚡ TOOLS ▸"
            canvas.drawRoundRect(btnSearchRect, dp(3f), dp(3f), btnSecondaryBgPaint)
            canvas.drawRoundRect(btnSearchRect, dp(3f), dp(3f), btnSecondaryStrokePaint)
            canvas.drawText(thirdLabel, btnSearchRect.centerX(), btnTextY, btnSecondaryTextPaint)

            // [ ✕ ] clear selection button
            canvas.drawRoundRect(btnClearRect, dp(3f), dp(3f), btnSecondaryBgPaint)
            canvas.drawRoundRect(btnClearRect, dp(3f), dp(3f), btnSecondaryStrokePaint)
            canvas.drawText("✕", btnClearRect.centerX(), btnTextY, btnSecondaryTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (topEscBtnRect.contains(x, y)) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onDismiss()
                    return true
                }
                if (topMultiBtnRect.contains(x, y)) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (!multiSelectMode && hasSelection()) {
                        multiSelectedIndices.clear()
                        multiSelectedIndices.addAll(normalizedRange())
                    }
                    multiSelectMode = !multiSelectMode
                    if (!multiSelectMode && multiSelectedIndices.isNotEmpty()) {
                        selStart = multiSelectedIndices.minOrNull() ?: -1
                        selEnd = multiSelectedIndices.maxOrNull() ?: -1
                    }
                    invalidate()
                    return true
                }
                if (topSnapBtnRect.contains(x, y)) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onCaptureScreenshot()
                    return true
                }
                if (topAllBtnRect.contains(x, y)) {
                    if (words.isNotEmpty()) {
                        if (multiSelectMode) {
                            multiSelectedIndices.clear()
                            multiSelectedIndices.addAll(words.indices)
                        } else {
                            selStart = 0
                            selEnd = words.lastIndex
                        }
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        invalidate()
                    }
                    return true
                }

                if (hasSelection() && bottomBarRect.contains(x, y)) {
                    val txt = getSelectedText()
                    val (smartId, _) = resolveSmartAction(txt)
                    when {
                        btnCopyRect.contains(x, y) -> {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            if (txt.isNotEmpty()) onCopyText(txt)
                        }
                        btnSmartRect.contains(x, y) -> {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            if (txt.isNotEmpty()) {
                                if (smartId == "tools") onOpenInTools(txt) else onSmartAction(smartId, txt)
                            }
                        }
                        btnSearchRect.contains(x, y) -> {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            if (txt.isNotEmpty()) {
                                if (smartId == "tools") onSearchText(txt) else onOpenInTools(txt)
                            }
                        }
                        btnClearRect.contains(x, y) -> {
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            selStart = -1
                            selEnd = -1
                            multiSelectedIndices.clear()
                            invalidate()
                        }
                    }
                    return true
                }

                if (hasSelection() && !multiSelectMode) {
                    val slop = dp(18f)
                    if (expandRect(startHandleCenter, slop).contains(x, y)) {
                        dragMode = DragMode.HANDLE_START
                        val r = normalizedRange()
                        selStart = r.first
                        selEnd = r.last
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        return true
                    }
                    if (expandRect(endHandleCenter, slop).contains(x, y)) {
                        dragMode = DragMode.HANDLE_END
                        val r = normalizedRange()
                        selStart = r.first
                        selEnd = r.last
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        return true
                    }
                }

                val hitWord = findNearestWordIndex(x, y, maxDist = dp(26f))
                if (hitWord != -1) {
                    if (multiSelectMode) {
                        if (hitWord in multiSelectedIndices) {
                            multiSelectedIndices.remove(hitWord)
                        } else {
                            multiSelectedIndices.add(hitWord)
                        }
                        dragMode = DragMode.SWIPE_WORDS
                        anchorWordIndex = hitWord
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        invalidate()
                        return true
                    }

                    val now = System.currentTimeMillis()
                    if (hitWord == lastTappedWord && now - lastTapTime < 340L) {
                        val targetBlock = words[hitWord].blockIndex
                        val blockWordIndices = words.filter { it.blockIndex == targetBlock }.map { it.globalIndex }
                        if (blockWordIndices.isNotEmpty()) {
                            selStart = blockWordIndices.first()
                            selEnd = blockWordIndices.last()
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            invalidate()
                        }
                        dragMode = DragMode.NONE
                    } else {
                        dragMode = DragMode.SWIPE_WORDS
                        anchorWordIndex = hitWord
                        selStart = hitWord
                        selEnd = hitWord
                        lastTappedWord = hitWord
                        lastTapTime = now
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        invalidate()
                    }
                    return true
                }

                if (hasSelection()) {
                    selStart = -1
                    selEnd = -1
                    multiSelectedIndices.clear()
                    invalidate()
                } else {
                    onDismiss()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                when (dragMode) {
                    DragMode.SWIPE_WORDS -> {
                        val hit = findNearestWordIndex(x, y, maxDist = dp(38f))
                        if (hit != -1) {
                            if (multiSelectMode) {
                                if (multiSelectedIndices.add(hit)) {
                                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    invalidate()
                                }
                            } else if (hit != selEnd) {
                                selStart = anchorWordIndex
                                selEnd = hit
                                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                invalidate()
                            }
                        }
                    }
                    DragMode.HANDLE_START -> {
                        val hit = findNearestWordIndex(x, y - dp(18f), maxDist = dp(48f))
                        if (hit != -1 && hit != selStart) {
                            selStart = hit
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            invalidate()
                        }
                    }
                    DragMode.HANDLE_END -> {
                        val hit = findNearestWordIndex(x, y - dp(18f), maxDist = dp(48f))
                        if (hit != -1 && hit != selEnd) {
                            selEnd = hit
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            invalidate()
                        }
                    }
                    DragMode.NONE -> {}
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (hasSelection() && !multiSelectMode) {
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

    private fun expandRect(r: RectF, delta: Float): RectF {
        return RectF(r.left - delta, r.top - delta, r.right + delta, r.bottom + delta)
    }

    private fun findNearestWordIndex(x: Float, y: Float, maxDist: Float): Int {
        for (w in words) {
            if (w.rect.contains(x, y)) return w.globalIndex
        }
        var bestIdx = -1
        var bestD = maxDist
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
            if (d < bestD) {
                bestD = d
                bestIdx = w.globalIndex
            }
        }
        return bestIdx
    }
}
