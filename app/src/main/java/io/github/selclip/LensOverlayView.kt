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
 * - Tap any word to select it, double-tap to select its whole block, or drag across words to highlight a phrase.
 * - Drag the plain-text `[` and `]` terminal handles to fine-tune the start/end of your selection at 60fps.
 * - Bottom plain-text action bar provides instant 1-tap `[ ⎘ COPY ]`, `[ ⚡ TOOLS ▸ ]`, and `[ ↗ SEARCH ]`.
 */
class LensOverlayView(
    context: Context,
    private val blocks: List<LensBlock>,
    private val onCopyText: (String) -> Unit,
    private val onOpenInTools: (String) -> Unit,
    private val onSearchText: (String) -> Unit,
    private val onDismiss: () -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val words = mutableListOf<LensWord>()
    private val blockRects = mutableListOf<RectF>()

    private var selStart: Int = -1
    private var selEnd: Int = -1

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

    private val topAllBtnRect = RectF()
    private val topEscBtnRect = RectF()

    private val bottomBarRect = RectF()
    private val btnCopyRect = RectF()
    private val btnToolsRect = RectF()
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
        textSize = dp(11.5f)
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
        textSize = dp(11.5f)
        textAlign = Paint.Align.CENTER
    }

    private val btnSecondaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = Color.parseColor("#F0F0F0")
        textSize = dp(11.5f)
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
        return selStart in words.indices && selEnd in words.indices
    }

    private fun normalizedRange(): IntRange {
        val s = minOf(selStart, selEnd).coerceIn(0, (words.size - 1).coerceAtLeast(0))
        val e = maxOf(selStart, selEnd).coerceIn(0, (words.size - 1).coerceAtLeast(0))
        return s..e
    }

    fun getSelectedText(): String {
        if (!hasSelection() || words.isEmpty()) return ""
        val range = normalizedRange()
        val sb = StringBuilder()
        var prevBlock = -1
        for (i in range) {
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
        val activeRange = if (hasSelection()) normalizedRange() else IntRange.EMPTY
        for (w in words) {
            val isSel = w.globalIndex in activeRange
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

        // 4. Draw draggable plain-text terminal handles '[' and ']' when text is selected
        if (hasSelection()) {
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

            // Stem line connecting word to handle
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

        // 5. Top Plain-Text Lens HUD Bar
        val topHudRect = RectF(dp(12f), dp(36f), viewW - dp(12f), dp(78f))
        canvas.drawRoundRect(topHudRect, dp(4f), dp(4f), hudBgPaint)
        canvas.drawRoundRect(topHudRect, dp(4f), dp(4f), hudStrokePaint)

        val hudTitle = if (hasSelection()) {
            val range = normalizedRange()
            val count = range.last - range.first + 1
            "⊞ LENS // $count WORD${if (count == 1) "" else "S"} SELECTED"
        } else {
            "⊞ LENS // TAP OR SWIPE WORDS (${words.size} FOUND)"
        }
        val titleY = topHudRect.centerY() - (hudTitlePaint.descent() + hudTitlePaint.ascent()) / 2f
        canvas.drawText(hudTitle, topHudRect.left + dp(12f), titleY, hudTitlePaint)

        // [ALL] and [ESC] buttons on right of top bar
        val escW = dp(58f)
        val allW = dp(58f)
        topEscBtnRect.set(
            topHudRect.right - escW - dp(8f),
            topHudRect.top + dp(6f),
            topHudRect.right - dp(8f),
            topHudRect.bottom - dp(6f)
        )
        topAllBtnRect.set(
            topEscBtnRect.left - allW - dp(6f),
            topHudRect.top + dp(6f),
            topEscBtnRect.left - dp(6f),
            topHudRect.bottom - dp(6f)
        )

        canvas.drawRoundRect(topAllBtnRect, dp(3f), dp(3f), btnSecondaryBgPaint)
        canvas.drawRoundRect(topAllBtnRect, dp(3f), dp(3f), btnSecondaryStrokePaint)
        canvas.drawText(
            "ALL",
            topAllBtnRect.centerX(),
            topAllBtnRect.centerY() - (btnSecondaryTextPaint.descent() + btnSecondaryTextPaint.ascent()) / 2f,
            btnSecondaryTextPaint
        )

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
            val barH = dp(98f)
            bottomBarRect.set(dp(12f), viewH - barH - dp(22f), viewW - dp(12f), viewH - dp(22f))
            canvas.drawRoundRect(bottomBarRect, dp(5f), dp(5f), hudBgPaint)
            canvas.drawRoundRect(bottomBarRect, dp(5f), dp(5f), hudStrokePaint)

            // Preview line of selected text
            val singleLinePreview = selText.replace("\n", " ↵ ").let {
                if (it.length > 44) it.take(43) + "…" else it
            }
            canvas.drawText(
                "\"$singleLinePreview\"",
                bottomBarRect.left + dp(12f),
                bottomBarRect.top + dp(24f),
                hudSubPaint
            )

            // Action Buttons row: [ ⎘ COPY ]  [ ⚡ TOOLS ▸ ]  [ ↗ SEARCH ]  [ ✕ ]
            val btnTop = bottomBarRect.top + dp(40f)
            val btnBottom = bottomBarRect.bottom - dp(10f)
            val innerW = bottomBarRect.width() - dp(24f)
            val gap = dp(8f)

            val clearW = dp(40f)
            val mainBtnW = (innerW - clearW - gap * 3f) / 3f

            var curLeft = bottomBarRect.left + dp(12f)
            btnCopyRect.set(curLeft, btnTop, curLeft + mainBtnW, btnBottom)
            curLeft += mainBtnW + gap
            btnToolsRect.set(curLeft, btnTop, curLeft + mainBtnW, btnBottom)
            curLeft += mainBtnW + gap
            btnSearchRect.set(curLeft, btnTop, curLeft + mainBtnW, btnBottom)
            curLeft += mainBtnW + gap
            btnClearRect.set(curLeft, btnTop, curLeft + clearW, btnBottom)

            // Primary green [ ⎘ COPY ] button
            canvas.drawRoundRect(btnCopyRect, dp(3f), dp(3f), btnPrimaryBgPaint)
            val btnTextY = btnCopyRect.centerY() - (btnPrimaryTextPaint.descent() + btnPrimaryTextPaint.ascent()) / 2f
            canvas.drawText("⎘ COPY", btnCopyRect.centerX(), btnTextY, btnPrimaryTextPaint)

            // [ ⚡ TOOLS ▸ ] button
            canvas.drawRoundRect(btnToolsRect, dp(3f), dp(3f), btnSecondaryBgPaint)
            canvas.drawRoundRect(btnToolsRect, dp(3f), dp(3f), hudStrokePaint)
            canvas.drawText("⚡ TOOLS ▸", btnToolsRect.centerX(), btnTextY, btnSecondaryTextPaint)

            // [ ↗ SEARCH ] button
            canvas.drawRoundRect(btnSearchRect, dp(3f), dp(3f), btnSecondaryBgPaint)
            canvas.drawRoundRect(btnSearchRect, dp(3f), dp(3f), btnSecondaryStrokePaint)
            canvas.drawText("↗ SEARCH", btnSearchRect.centerX(), btnTextY, btnSecondaryTextPaint)

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
                // 1. Check top HUD buttons
                if (topEscBtnRect.contains(x, y)) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onDismiss()
                    return true
                }
                if (topAllBtnRect.contains(x, y)) {
                    if (words.isNotEmpty()) {
                        selStart = 0
                        selEnd = words.lastIndex
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        invalidate()
                    }
                    return true
                }

                // 2. Check bottom action dock buttons if visible
                if (hasSelection() && bottomBarRect.contains(x, y)) {
                    when {
                        btnCopyRect.contains(x, y) -> {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            val txt = getSelectedText()
                            if (txt.isNotEmpty()) onCopyText(txt)
                        }
                        btnToolsRect.contains(x, y) -> {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            val txt = getSelectedText()
                            if (txt.isNotEmpty()) onOpenInTools(txt)
                        }
                        btnSearchRect.contains(x, y) -> {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            val txt = getSelectedText()
                            if (txt.isNotEmpty()) onSearchText(txt)
                        }
                        btnClearRect.contains(x, y) -> {
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            selStart = -1
                            selEnd = -1
                            invalidate()
                        }
                    }
                    return true
                }

                // 3. Check if dragging '[' or ']' selection handles
                if (hasSelection()) {
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

                // 4. Check if touching a word chip (or nearby word)
                val hitWord = findNearestWordIndex(x, y, maxDist = dp(26f))
                if (hitWord != -1) {
                    val now = System.currentTimeMillis()
                    if (hitWord == lastTappedWord && now - lastTapTime < 340L) {
                        // Double-tap selects the entire text block!
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

                // 5. Tapped empty background: clear selection if active, otherwise dismiss
                if (hasSelection()) {
                    selStart = -1
                    selEnd = -1
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
                        if (hit != -1 && hit != selEnd) {
                            selStart = anchorWordIndex
                            selEnd = hit
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            invalidate()
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
                if (hasSelection()) {
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
