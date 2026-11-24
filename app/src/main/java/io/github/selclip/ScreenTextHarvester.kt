package io.github.selclip

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.abs

data class GrabbedBlock(
    val id: Int,
    val text: String,
    val bounds: Rect,
    val lineRects: List<RectF>,
    val isContentDescription: Boolean = false
)

data class ScreenGrabSnapshot(
    val blocks: List<GrabbedBlock>,
    val quickEntities: List<ExtractedEntity>
)

/**
 * High-accuracy, z-order-aware screen text harvester for Lens Grab.
 *
 * Key improvements over naive recursive harvesting:
 * 1. Processes AccessibilityWindows top-to-bottom by layer and skips windows occluded by
 *    higher opaque application windows (prevents launcher / background app ghost text).
 * 2. Skips parent container text when children already provide the same text.
 * 3. Queries API 26+ EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY to compute true per-line
 *    bounding boxes so highlights hug the real text instead of covering huge empty boxes.
 * 4. Deduplicates overlapping rects and extracts quick smart entities (URLs, phones, emails, hashes).
 */
class ScreenTextHarvester(
    private val service: AccessibilityService,
    private val detector: SelectionDetector
) {

    fun harvest(): ScreenGrabSnapshot {
        val screen = detector.getRealScreenBounds()
        val screenW = screen.width()
        val screenH = screen.height()
        val density = service.resources.displayMetrics.density
        val minW = (14 * density).toInt()
        val minH = (12 * density).toInt()

        val rawBlocks = mutableListOf<RawBlock>()
        val seenKeys = HashSet<String>()

        val winList = runCatching { service.windows }.getOrNull()
        if (!winList.isNullOrEmpty()) {
            // Sort highest layer (topmost on screen) first
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

                // Skip windows completely occluded by a higher-layer application window
                val isOccluded = opaqueAppRects.any { higher ->
                    higher.contains(winRect) || intersectionRatio(higher, winRect) > 0.85f
                }
                if (isOccluded) continue

                collectNodesRecursive(
                    node = root,
                    depth = 0,
                    screenW = screenW,
                    screenH = screenH,
                    minW = minW,
                    minH = minH,
                    occluders = opaqueAppRects,
                    out = rawBlocks,
                    seenKeys = seenKeys
                )

                // Record full/large app window as occluder for lower layers
                if (winRect.width() > screenW * 0.7f && winRect.height() > screenH * 0.55f) {
                    opaqueAppRects.add(winRect)
                }
            }
        }

        if (rawBlocks.isEmpty()) {
            val root = runCatching { service.rootInActiveWindow }.getOrNull()
            if (root != null) {
                collectNodesRecursive(
                    node = root,
                    depth = 0,
                    screenW = screenW,
                    screenH = screenH,
                    minW = minW,
                    minH = minH,
                    occluders = emptyList(),
                    out = rawBlocks,
                    seenKeys = seenKeys
                )
            }
        }

        // Deduplicate heavily overlapping blocks with identical or subsumed text
        val deduped = deduplicateBlocks(rawBlocks)

        // Sort cleanly in human reading order (group into ~18dp vertical bands, then left-to-right)
        val bandPx = (18 * density).toInt().coerceAtLeast(16)
        val sorted = deduped.sortedWith(
            compareBy<RawBlock> { it.bounds.top / bandPx }
                .thenBy { it.bounds.left }
                .thenBy { it.bounds.top }
        )

        val finalBlocks = sorted.mapIndexed { idx, rb ->
            GrabbedBlock(
                id = idx,
                text = rb.text,
                bounds = rb.bounds,
                lineRects = rb.lineRects,
                isContentDescription = rb.isDesc
            )
        }

        // Extract top quick entities across all visible text for 1-tap grab chips
        val allText = finalBlocks.joinToString("\n") { it.text }
        val entities = mutableListOf<ExtractedEntity>()
        val seenVals = HashSet<String>()
        TextTransforms.extractTorrentHash(allText)?.let { h ->
            if (seenVals.add(h)) entities.add(ExtractedEntity("HASH", h))
        }
        TextTransforms.extractEntities(allText, limit = 8).forEach { e ->
            if (seenVals.add(e.value)) entities.add(e)
        }

        return ScreenGrabSnapshot(
            blocks = finalBlocks,
            quickEntities = entities.take(6)
        )
    }

    private data class RawBlock(
        val text: String,
        val bounds: Rect,
        val lineRects: List<RectF>,
        val isDesc: Boolean
    )

    private fun collectNodesRecursive(
        node: AccessibilityNodeInfo?,
        depth: Int,
        screenW: Int,
        screenH: Int,
        minW: Int,
        minH: Int,
        occluders: List<Rect>,
        out: MutableList<RawBlock>,
        seenKeys: HashSet<String>
    ): StringBuilder {
        val subtreeText = StringBuilder()
        if (node == null || depth > 28 || out.size >= 240) return subtreeText

        val count = node.childCount
        for (i in 0 until count) {
            if (out.size >= 240) break
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val childStr = collectNodesRecursive(
                child, depth + 1, screenW, screenH, minW, minH, occluders, out, seenKeys
            )
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

        // Skip single non-alphanumeric symbol/icon chrome
        if (candidate.length == 1 && !candidate[0].isLetterOrDigit()) {
            return subtreeText
        }

        // If children already emitted essentially the same text, skip redundant container
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
        // Clip to visible screen area
        val clipped = Rect(
            rect.left.coerceIn(0, screenW),
            rect.top.coerceIn(0, screenH),
            rect.right.coerceIn(0, screenW),
            rect.bottom.coerceIn(0, screenH)
        )
        if (clipped.width() < minW || clipped.height() < minH) return subtreeText
        // Skip full-screen invisible/container rects that have short text (likely a wrapper)
        if (clipped.width() > screenW * 0.95f && clipped.height() > screenH * 0.75f && candidate.length < 40) {
            return subtreeText
        }

        if (occluders.any { intersectionRatio(it, clipped) > 0.75f }) {
            return subtreeText
        }

        val key = "${candidate.lowercase()}_${clipped.top / 16}_${clipped.left / 16}"
        if (seenKeys.add(key)) {
            val lineRects = if (!isDesc && candidate.length <= 240) {
                extractTrueLineRects(node, candidate.length, clipped)
            } else {
                emptyList()
            }
            val refinedBounds = if (lineRects.isNotEmpty()) {
                unionRects(lineRects, clipped)
            } else {
                clipped
            }
            out.add(RawBlock(candidate, refinedBounds, lineRects, isDesc))
            if (subtreeText.isNotEmpty()) subtreeText.append(' ')
            subtreeText.append(candidate)
        }

        return subtreeText
    }

    /**
     * Queries OS character bounding boxes via EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY (API 26+)
     * and merges adjacent characters on the same visual baseline into crisp per-line RectFs.
     */
    private fun extractTrueLineRects(
        node: AccessibilityNodeInfo,
        textLen: Int,
        clipBounds: Rect
    ): List<RectF> {
        return runCatching {
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, textLen)
            }
            if (!node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)) {
                return@runCatching emptyList()
            }
            @Suppress("DEPRECATION")
            val locs = node.extras?.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
                ?: return@runCatching emptyList()
            if (locs.isEmpty()) return@runCatching emptyList()

            val lines = mutableListOf<RectF>()
            var curLine: RectF? = null

            for (p in locs) {
                val rf = p as? RectF ?: continue
                if (rf.width() <= 0.5f || rf.height() <= 0.5f) continue
                if (rf.right < clipBounds.left || rf.left > clipBounds.right ||
                    rf.bottom < clipBounds.top || rf.top > clipBounds.bottom
                ) continue

                val existing = curLine
                if (existing == null) {
                    curLine = RectF(rf)
                } else {
                    val sameLine = abs(rf.centerY() - existing.centerY()) <= maxOf(existing.height(), rf.height()) * 0.65f
                    if (sameLine) {
                        existing.union(rf)
                    } else {
                        lines.add(existing)
                        curLine = RectF(rf)
                    }
                }
            }
            curLine?.let { lines.add(it) }
            lines
        }.getOrDefault(emptyList())
    }

    private fun unionRects(rects: List<RectF>, fallback: Rect): Rect {
        var minL = Float.MAX_VALUE
        var minT = Float.MAX_VALUE
        var maxR = -Float.MAX_VALUE
        var maxB = -Float.MAX_VALUE
        for (r in rects) {
            if (r.left < minL) minL = r.left
            if (r.top < minT) minT = r.top
            if (r.right > maxR) maxR = r.right
            if (r.bottom > maxB) maxB = r.bottom
        }
        return if (minL < maxR && minT < maxB) {
            Rect(minL.toInt(), minT.toInt(), maxR.toInt(), maxB.toInt())
        } else {
            fallback
        }
    }

    private fun deduplicateBlocks(raw: List<RawBlock>): List<RawBlock> {
        val result = mutableListOf<RawBlock>()
        for (candidate in raw) {
            var isDup = false
            for (existing in result) {
                val overlap = intersectionRatio(existing.bounds, candidate.bounds)
                if (overlap > 0.7f && existing.text.equals(candidate.text, ignoreCase = true)) {
                    isDup = true
                    break
                }
            }
            if (!isDup) result.add(candidate)
        }
        return result
    }

    private fun intersectionRatio(a: Rect, b: Rect): Float {
        val interL = maxOf(a.left, b.left)
        val interT = maxOf(a.top, b.top)
        val interR = minOf(a.right, b.right)
        val interB = minOf(a.bottom, b.bottom)
        if (interR <= interL || interB <= interT) return 0f
        val interArea = (interR - interL).toFloat() * (interB - interT).toFloat()
        val bArea = (b.width().toFloat() * b.height().toFloat()).coerceAtLeast(1f)
        return interArea / bArea
    }
}
