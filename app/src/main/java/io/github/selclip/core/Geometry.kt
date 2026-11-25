package io.github.selclip.core

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
 * Anchor metadata telling [PopupPositioner] whether we are docking directly to Android's native
 * floating selection toolbar or anchoring around raw selection bounds.
 */
data class AnchorTarget(
    val zone: Box,
    val dockedToNativeToolbar: Boolean
)

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
 * Native Android Docking Behavior:
 * - When Android's native floating selection toolbar is detected, docks directly BELOW it
 *   (`toolbar.bottom + 4dp`) aligned to the toolbar's center so both pills feel like one cohesive
 *   native Android UI (flipping cleanly to `toolbar.top - 4dp - popupH` only if bottom/IME is cramped).
 * - When no native toolbar is detected, anchors cleanly below the selection + teardrop handles,
 *   or flips above when cramped by keyboard/bottom edge, with a clean fallback band if bounds are absent.
 */
object PopupPositioner {

    private fun Int.safeClamp(minVal: Int, maxVal: Int): Int =
        if (minVal <= maxVal) this.coerceIn(minVal, maxVal) else minVal

    fun computeAnchorTarget(
        selectionBounds: Box?,
        toolbarBounds: Box?,
        viewport: Viewport,
        density: Float
    ): AnchorTarget {
        fun dp(v: Int): Int = (v * density).toInt()
        val topSafe = viewport.topSafeInset
        val visBottom = viewport.visibleBottom
        val realW = viewport.screenWidth.coerceAtLeast(dp(240))
        val realH = viewport.screenHeight.coerceAtLeast(dp(360))

        // Priority 1: Attach seamlessly directly below Android's native floating toolbar!
        if (toolbarBounds != null && !toolbarBounds.isEmpty &&
            toolbarBounds.top >= topSafe - dp(12) &&
            toolbarBounds.bottom <= visBottom + dp(12)
        ) {
            val left = toolbarBounds.left.coerceAtLeast(dp(8))
            val right = toolbarBounds.right.coerceAtMost(realW - dp(8)).coerceAtLeast(left + dp(24))
            val top = toolbarBounds.top.safeClamp(topSafe, (visBottom - dp(32)).coerceAtLeast(topSafe))
            val bottom = toolbarBounds.bottom.safeClamp(top + dp(28), visBottom)
            return AnchorTarget(Box(left, top, right, bottom), dockedToNativeToolbar = true)
        }

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

        if (isCompactSelection && selectionBounds != null) {
            val clampedTop = (selectionBounds.top - dp(10)).safeClamp(topSafe, (visBottom - dp(64)).coerceAtLeast(topSafe))
            val clampedBottom = (selectionBounds.bottom + dp(26)).safeClamp(
                clampedTop + dp(28),
                (visBottom - dp(8)).coerceAtLeast(clampedTop + dp(28))
            )
            val left = selectionBounds.left.coerceAtLeast(dp(12))
            val right = selectionBounds.right.coerceAtMost(realW - dp(12)).coerceAtLeast(left + dp(24))
            return AnchorTarget(Box(left, clampedTop, right, clampedBottom), dockedToNativeToolbar = false)
        }

        if (isMediumSelection && selectionBounds != null) {
            val clampedTop = (selectionBounds.top - dp(10)).safeClamp(topSafe, (visBottom - dp(80)).coerceAtLeast(topSafe))
            val clampedBottom = (selectionBounds.bottom + dp(22)).safeClamp(
                clampedTop + dp(32),
                (visBottom - dp(8)).coerceAtLeast(clampedTop + dp(32))
            )
            val left = selectionBounds.left.coerceAtLeast(dp(12))
            val right = selectionBounds.right.coerceAtMost(realW - dp(12)).coerceAtLeast(left + dp(24))
            return AnchorTarget(Box(left, clampedTop, right, clampedBottom), dockedToNativeToolbar = false)
        }

        // Clean fallback anchor when an app omits both selection and toolbar bounds
        val midY = (topSafe + (visBottom - topSafe) * 0.36f).toInt()
            .safeClamp(topSafe + dp(40), (visBottom - dp(88)).coerceAtLeast(topSafe + dp(40)))
        return AnchorTarget(
            Box(dp(16), midY - dp(24), (realW - dp(16)).coerceAtLeast(dp(120)), midY + dp(32)),
            dockedToNativeToolbar = false
        )
    }

    fun place(
        target: AnchorTarget,
        viewport: Viewport,
        popupWidth: Int,
        popupHeight: Int,
        density: Float,
        isDrawerOpen: Boolean
    ): PopupPlacement {
        fun dp(v: Int): Int = (v * density).toInt()
        val anchorZone = target.zone
        val topSafe = viewport.topSafeInset
        val visBottom = viewport.visibleBottom
        val realW = viewport.screenWidth.coerceAtLeast(dp(240))
        val marginH = dp(10)
        // Tight 4dp native attachment gap when docked to Android's floating toolbar!
        val gap = if (target.dockedToNativeToolbar) dp(4) else dp(10)

        val effectiveW = popupWidth.takeIf { it > dp(72) }
            ?: (realW - marginH * 2).coerceAtMost(dp(348))
        val effectiveH = popupHeight.takeIf { it > dp(28) }
            ?: if (isDrawerOpen) dp(260) else dp(48)

        val spaceBelow = visBottom - (anchorZone.bottom + gap)
        val spaceAbove = (anchorZone.top - gap) - topSafe

        // Always prefer attached directly BELOW the native toolbar when it fits!
        val placeAbove = when {
            spaceBelow >= effectiveH + dp(4) -> false
            spaceAbove >= effectiveH + dp(4) -> true
            else -> spaceAbove > spaceBelow
        }

        val chosenSpace = if (placeAbove) spaceAbove else spaceBelow
        val maxDrawerH = (chosenSpace - dp(54)).safeClamp(dp(120), dp(310))

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
