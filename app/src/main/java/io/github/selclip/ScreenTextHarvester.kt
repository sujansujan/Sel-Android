package io.github.selclip

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.abs

/**
 * Fallback word-level Accessibility text harvester (used on Android 8–10 or when
 * FLAG_SECURE blocks takeScreenshot() pixel OCR).
 *
 * Outputs the exact same word-level LensSnapshot consumed by LensOverlayView so the
 * Google Lens interaction model works identically in both OCR and Accessibility modes.
 */
class ScreenTextHarvester(
    private val service: AccessibilityService,
    private val detector: SelectionDetector
) {

    fun harvestAsLensSnapshot(): LensSnapshot {
        val screen = detector.getRealScreenBounds()
        val screenW = screen.width()
        val screenH = screen.height()
        val density = service.resources.displayMetrics.density
        val minW = (14 * density).toInt()
        val minH = (12 * density).toInt()

        val rawBlocks = mutableListOf<RawNodeBlock>()
        val seenKeys = HashSet<String>()

        val winList = runCatching { service.windows }.getOrNull()
        if (!winList.isNullOrEmpty()) {
            val sortedWins = winList.sortedByDescending { it.layer }
            val opaqueAppRects = mutableListOf<Rect>()

            for (w in sortedWins) {
                if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                    w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    w.type == AccessibilityWindowInfo.TYPE_SYSTEM
                ) {
                    continue
                }
                val root = runCatching { w.root }.getOrNull() ?: continue
                val pkg = root.packageName?.toString() ?: ""
                if (pkg == service.packageName || pkg == "com.android.systemui" || detector.isImePackage(pkg)) {
                    continue
                }
                val winRect = Rect()
                w.getBoundsInScreen(winRect)
                if (winRect.isEmpty) continue

                if (opaqueAppRects.any { it.contains(winRect) || intersectionRatio(it, winRect) > 0.85f }) {
                    continue
                }

                collectRecursive(root, 0, screenW, screenH, minW, minH, opaqueAppRects, rawBlocks, seenKeys)

                if (winRect.width() > screenW * 0.7f && winRect.height() > screenH * 0.55f) {
                    opaqueAppRects.add(winRect)
                }
            }
        }

        if (rawBlocks.isEmpty()) {
            runCatching { service.rootInActiveWindow }.getOrNull()?.let { root ->
                collectRecursive(root, 0, screenW, screenH, minW, minH, emptyList(), rawBlocks, seenKeys)
            }
        }

        val deduped = deduplicate(rawBlocks)
        val bandPx = (20 * density).toInt().coerceAtLeast(18)
        val sorted = deduped.sortedWith(
            compareBy<RawNodeBlock> { it.bounds.top / bandPx }
                .thenBy { it.bounds.left }
                .thenBy { it.bounds.top }
        )

        val words = ArrayList<LensWord>(128)
        val lines = ArrayList<LensLine>(48)
        var blockIdx = 0
        var lineIdx = 0

        for (rb in sorted) {
            if (rb.charWordRects.isNotEmpty()) {
                // Group OS-accurate word rects into visual lines
                var curLineStart = words.size
                var curLineBounds: RectF? = null
                for ((wordText, rect) in rb.charWordRects) {
                    val prev = curLineBounds
                    if (prev != null && abs(rect.centerY() - prev.centerY()) > maxOf(prev.height(), rect.height()) * 0.65f) {
                        lines.add(LensLine(lineIdx, blockIdx, prev, curLineStart, words.size - 1))
                        lineIdx++
                        curLineStart = words.size
                        curLineBounds = RectF(rect)
                    } else if (prev == null) {
                        curLineBounds = RectF(rect)
                    } else {
                        prev.union(rect)
                    }
                    words.add(LensWord(words.size, wordText, rect, lineIdx, blockIdx))
                }
                curLineBounds?.let { lb ->
                    if (words.size - 1 >= curLineStart) {
                        lines.add(LensLine(lineIdx, blockIdx, lb, curLineStart, words.size - 1))
                        lineIdx++
                    }
                }
                blockIdx++
            } else {
                // Split multi-line or single-line text evenly into lines & words
                val textLines = rb.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (textLines.isEmpty()) continue
                val lineH = rb.bounds.height().toFloat() / textLines.size.coerceAtLeast(1)
                textLines.forEachIndexed { idx, lineText ->
                    val top = rb.bounds.top + idx * lineH
                    val bottom = (top + lineH).coerceAtMost(rb.bounds.bottom.toFloat())
                    val lr = RectF(rb.bounds.left.toFloat(), top, rb.bounds.right.toFloat(), bottom)
                    val startW = words.size
                    ScreenOcrEngine.appendEstimatedWordsForLine(lineText, lr, lineIdx, blockIdx, words)
                    if (words.size - 1 >= startW) {
                        lines.add(LensLine(lineIdx, blockIdx, lr, startW, words.size - 1))
                        lineIdx++
                    }
                }
                blockIdx++
            }
        }

        val allText = sorted.joinToString("\n") { it.text }
        return LensSnapshot(
            words = words,
            lines = lines,
            quickEntities = ScreenOcrEngine.extractQuickEntities(allText),
            isFromOcr = false
        )
    }

    private data class RawNodeBlock(
        val text: String,
        val bounds: Rect,
        val charWordRects: List<Pair<String, RectF>>
    )

    private fun collectRecursive(
        node: AccessibilityNodeInfo?,
        depth: Int,
        screenW: Int,
        screenH: Int,
        minW: Int,
        minH: Int,
        occluders: List<Rect>,
        out: MutableList<RawNodeBlock>,
        seenKeys: HashSet<String>
    ): StringBuilder {
        val subtreeText = StringBuilder()
        if (node == null || depth > 28 || out.size >= 220) return subtreeText

        val count = node.childCount
        for (i in 0 until count) {
            if (out.size >= 220) break
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val childStr = collectRecursive(child, depth + 1, screenW, screenH, minW, minH, occluders, out, seenKeys)
            if (childStr.isNotEmpty()) {
                if (subtreeText.isNotEmpty()) subtreeText.append(' ')
                subtreeText.append(childStr)
            }
        }

        if (!node.isVisibleToUser || node.isPassword) return subtreeText

        val rawText = node.text?.toString()?.trim()
        val descText = node.contentDescription?.toString()?.trim()
        val (candidate, isDesc) = when {
            !rawText.isNullOrBlank() -> rawText to false
            subtreeText.isEmpty() && !descText.isNullOrBlank() && descText.length >= 2 -> descText to true
            else -> return subtreeText
        }

        if (candidate.length == 1 && !candidate[0].isLetterOrDigit()) return subtreeText

        if (subtreeText.isNotEmpty()) {
            val childNorm = subtreeText.toString().replace(TextTransforms.whitespaceRegex, " ").trim()
            val candNorm = candidate.replace(TextTransforms.whitespaceRegex, " ").trim()
            if (childNorm.equals(candNorm, ignoreCase = true) ||
                (candNorm.length > 4 && childNorm.contains(candNorm, ignoreCase = true))
            ) {
                return subtreeText
            }
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val clipped = Rect(
            rect.left.coerceIn(0, screenW),
            rect.top.coerceIn(0, screenH),
            rect.right.coerceIn(0, screenW),
            rect.bottom.coerceIn(0, screenH)
        )
        if (clipped.width() < minW || clipped.height() < minH) return subtreeText
        if (clipped.width() > screenW * 0.95f && clipped.height() > screenH * 0.75f && candidate.length < 40) {
            return subtreeText
        }
        if (occluders.any { intersectionRatio(it, clipped) > 0.75f }) return subtreeText

        val key = "${candidate.lowercase()}_${clipped.top / 16}_${clipped.left / 16}"
        if (seenKeys.add(key)) {
            val wordRects = if (!isDesc && candidate.length <= 240) {
                extractWordRectsFromCharLocations(node, rawText ?: candidate, clipped)
            } else {
                emptyList()
            }
            out.add(RawNodeBlock(candidate, clipped, wordRects))
            if (subtreeText.isNotEmpty()) subtreeText.append(' ')
            subtreeText.append(candidate)
        }
        return subtreeText
    }

    private fun extractWordRectsFromCharLocations(
        node: AccessibilityNodeInfo,
        fullText: String,
        clipBounds: Rect
    ): List<Pair<String, RectF>> {
        return runCatching {
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, fullText.length)
            }
            if (!node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)) {
                return@runCatching emptyList()
            }
            @Suppress("DEPRECATION")
            val locs = node.extras?.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
                ?: return@runCatching emptyList()
            if (locs.size < fullText.length) return@runCatching emptyList()

            val result = mutableListOf<Pair<String, RectF>>()
            var wordStart = -1
            for (i in 0..fullText.length) {
                val isBreak = i == fullText.length || fullText[i].isWhitespace()
                if (!isBreak && wordStart == -1) {
                    wordStart = i
                } else if (isBreak && wordStart != -1) {
                    val word = fullText.substring(wordStart, i)
                    var union: RectF? = null
                    for (ci in wordStart until i) {
                        val rf = locs[ci] as? RectF ?: continue
                        if (rf.width() <= 0.5f || rf.height() <= 0.5f) continue
                        if (rf.right < clipBounds.left || rf.left > clipBounds.right ||
                            rf.bottom < clipBounds.top || rf.top > clipBounds.bottom
                        ) continue
                        if (union == null) union = RectF(rf) else union.union(rf)
                    }
                    if (union != null && union.width() >= 3f && union.height() >= 3f) {
                        result.add(word to union)
                    }
                    wordStart = -1
                }
            }
            result
        }.getOrDefault(emptyList())
    }

    private fun deduplicate(raw: List<RawNodeBlock>): List<RawNodeBlock> {
        val out = mutableListOf<RawNodeBlock>()
        for (c in raw) {
            val dup = out.any {
                intersectionRatio(it.bounds, c.bounds) > 0.7f && it.text.equals(c.text, ignoreCase = true)
            }
            if (!dup) out.add(c)
        }
        return out
    }

    private fun intersectionRatio(a: Rect, b: Rect): Float {
        val il = maxOf(a.left, b.left)
        val it = maxOf(a.top, b.top)
        val ir = minOf(a.right, b.right)
        val ib = minOf(a.bottom, b.bottom)
        if (ir <= il || ib <= it) return 0f
        val area = (ir - il).toFloat() * (ib - it).toFloat()
        val bArea = (b.width().toFloat() * b.height().toFloat()).coerceAtLeast(1f)
        return area / bArea
    }
}
