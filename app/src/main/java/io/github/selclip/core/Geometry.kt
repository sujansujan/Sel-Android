package io.github.selclip.core

import kotlin.math.abs

/**
 * Pure JVM rectangle (no android.graphics.Rect dependency) for deterministic geometry & unit tests.
 */
data class Box(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/**
 * Safe viewport metrics accounting for status bar, display cutout, nav bar, and active keyboard (IME).
 */
data class Viewport(
    val screenWidth: Int,
    val screenHeight: Int,
    val topSafeInset: Int,
    val bottomSafeInset: Int,
    val imeTop: Int? = null
) {
    val visibleBottom: Int
        get() {
            val baseBottom = screenHeight - bottomSafeInset
            val effective = if (imeTop != null && imeTop > topSafeInset + 80) {
                minOf(baseBottom, imeTop)
            } else {
                baseBottom
            }
            return effective.coerceAtLeast(topSafeInset + 120)
        }
}

/**
 * Final computed screen coordinates and drawer height budget for the floating popup window.
 */
data class PopupPlacement(
    val x: Int,
    val y: Int,
    val placedAbove: Boolean,
    val maxDrawerHeight: Int,
    val anchorZone: Box
)

/**
 * Pure, deterministic popup positioning & clamping engine.
 *
 * Guarantees:
 * - Never clips off-screen horizontally or vertically.
 * - Avoids colliding with system selection handles (~10dp top / ~28dp bottom) and nearby native
 *   ActionMode floating toolbars.
 * - Stays strictly below status bar/cutout and above nav bar / open IME keyboard.
 * - Falls back cleanly when an app omits exact selection bounds.
 */
object PopupPositioner {

    private fun Int.safeClamp(minVal: Int, maxVal: Int): Int =
        if (minVal <= maxVal) this.coerceIn(minVal, maxVal) else minVal

    /**
     * Resolves an exclusion zone around the selected text + handles + nearby system toolbar.
     */
    fun computeAnchorZone(
        selectionBounds: Box?,
        toolbarBounds: Box?,
        viewport: Viewport,
        density: Float
    ): Box {
        fun dp(v: Int): Int = (v * density).toInt()
        val topSafe = viewport.topSafeInset
        val visBottom = viewport.visibleBottom
        val realW = viewport.screenWidth.coerceAtLeast(dp(240))
        val realH = viewport.screenHeight.coerceAtLeast(dp(360))

        val isCompactSelection = selectionBounds != null &&
            !selectionBounds.isEmpty &&
            selectionBounds.height <= dp(110) &&
            selectionBounds.top < visBottom &&
            selectionBounds.bottom > topSafe

        val isMediumSelection = selectionBounds != null &&
            !selectionBounds.isEmpty &&
            selectionBounds.height <= (realH * 0.38f).toInt() &&
            selectionBounds.top < visBottom &&
            selectionBounds.bottom > topSafe

        // Case 1: Compact selection bounds (single line or short multi-line)
        if (isCompactSelection && selectionBounds != null) {
            var zTop = selectionBounds.top - dp(10)
            // Reserve extra clearance below for Android's teardrop selection handles
            var zBottom = selectionBounds.bottom + dp(28)

            // Merge nearby native floating toolbar so we never cover Copy/Cut/Paste
            if (toolbarBounds != null && !toolbarBounds.isEmpty &&
                abs(toolbarBounds.centerY - selectionBounds.centerY) <= dp(165)
            ) {
                zTop = minOf(zTop, toolbarBounds.top - dp(8))
                zBottom = maxOf(zBottom, toolbarBounds.bottom + dp(12))
            }

            val clampedTop = zTop.safeClamp(topSafe, (visBottom - dp(64)).coerceAtLeast(topSafe))
            val clampedBottom = zBottom.safeClamp(
                clampedTop + dp(28),
                (visBottom - dp(8)).coerceAtLeast(clampedTop + dp(28))
            )
            val left = selectionBounds.left.coerceAtLeast(dp(12))
            val right = selectionBounds.right.coerceAtMost(realW - dp(12)).coerceAtLeast(left + dp(24))
            return Box(left, clampedTop, right, clampedBottom)
        }

        // Case 2: App didn't expose compact selection bounds, but native floating toolbar is visible
        if (toolbarBounds != null && !toolbarBounds.isEmpty) {
            val clampedTop = (toolbarBounds.top - dp(10)).safeClamp(topSafe, (visBottom - dp(80)).coerceAtLeast(topSafe))
            val clampedBottom = (toolbarBounds.bottom + dp(56)).safeClamp(
                clampedTop + dp(36),
                (visBottom - dp(8)).coerceAtLeast(clampedTop + dp(36))
            )
            val left = toolbarBounds.left.coerceAtLeast(dp(12))
            val right = toolbarBounds.right.coerceAtMost(realW - dp(12)).coerceAtLeast(left + dp(24))
            return Box(left, clampedTop, right, clampedBottom)
        }

        // Case 3: Medium multi-line container bounds
        if (isMediumSelection && selectionBounds != null) {
            val clampedTop = (selectionBounds.top - dp(10)).safeClamp(topSafe, (visBottom - dp(80)).coerceAtLeast(topSafe))
            val clampedBottom = (selectionBounds.bottom + dp(24)).safeClamp(
                clampedTop + dp(32),
                (visBottom - dp(8)).coerceAtLeast(clampedTop + dp(32))
            )
            val left = selectionBounds.left.coerceAtLeast(dp(12))
            val right = selectionBounds.right.coerceAtMost(realW - dp(12)).coerceAtLeast(left + dp(24))
            return Box(left, clampedTop, right, clampedBottom)
        }

        // Case 4: Clean fallback anchor (thumb-reachable upper-middle band)
        val midY = (topSafe + (visBottom - topSafe) * 0.36f).toInt()
            .safeClamp(topSafe + dp(40), (visBottom - dp(88)).coerceAtLeast(topSafe + dp(40)))
        return Box(dp(16), midY - dp(24), (realW - dp(16)).coerceAtLeast(dp(120)), midY + dp(32))
    }

    /**
     * Computes exact window (x, y) and drawer height budget from the anchor zone and measured popup size.
     */
    fun place(
        anchorZone: Box,
        viewport: Viewport,
        popupWidth: Int,
        popupHeight: Int,
        density: Float,
        isDrawerOpen: Boolean
    ): PopupPlacement {
        fun dp(v: Int): Int = (v * density).toInt()
        val topSafe = viewport.topSafeInset
        val visBottom = viewport.visibleBottom
        val realW = viewport.screenWidth.coerceAtLeast(dp(240))
        val marginH = dp(10)
        val gap = dp(10)

        val effectiveW = popupWidth.takeIf { it > dp(80) }
            ?: (realW - marginH * 2).coerceAtMost(dp(356))
        val effectiveH = popupHeight.takeIf { it > dp(28) }
            ?: if (isDrawerOpen) dp(280) else dp(54)

        val spaceBelow = visBottom - (anchorZone.bottom + gap)
        val spaceAbove = (anchorZone.top - gap) - topSafe

        // Prefer below the exclusion zone (thumb-friendly & naturally beneath native toolbar),
        // flip above if cramped below (e.g., IME open or near bottom of screen).
        val placeAbove = when {
            spaceBelow >= effectiveH + dp(10) -> false
            spaceAbove >= effectiveH -> true
            else -> spaceAbove > spaceBelow
        }

        val chosenSpace = if (placeAbove) spaceAbove else spaceBelow
        val maxDrawerH = (chosenSpace - dp(58)).safeClamp(dp(120), dp(320))

        val x = (anchorZone.centerX - effectiveW / 2)
            .safeClamp(marginH, (realW - effectiveW - marginH).coerceAtLeast(marginH))

        val maxTop = (visBottom - effectiveH - dp(4)).coerceAtLeast(topSafe)
        val y = if (placeAbove) {
            (anchorZone.top - gap - effectiveH).safeClamp(topSafe, maxTop)
        } else {
            (anchorZone.bottom + gap).safeClamp(topSafe, maxTop)
        }

        return PopupPlacement(
            x = x,
            y = y,
            placedAbove = placeAbove,
            maxDrawerHeight = maxDrawerH,
            anchorZone = anchorZone
        )
    }

    /**
     * Clamps a manually dragged popup position safely inside viewport bounds.
     */
    fun clampDragged(
        targetX: Int,
        targetY: Int,
        popupWidth: Int,
        popupHeight: Int,
        viewport: Viewport,
        density: Float
    ): Pair<Int, Int> {
        fun dp(v: Int): Int = (v * density).toInt()
        val margin = dp(6)
        val maxX = (viewport.screenWidth - popupWidth - margin).coerceAtLeast(margin)
        val maxY = (viewport.visibleBottom - popupHeight - margin).coerceAtLeast(viewport.topSafeInset)
        return targetX.safeClamp(margin, maxX) to targetY.safeClamp(viewport.topSafeInset, maxY)
    }
}
