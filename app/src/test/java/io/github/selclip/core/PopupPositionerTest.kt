package io.github.selclip.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupPositionerTest {

    private val density = 3f // xxhdpi (1dp = 3px)
    private val phoneViewport = Viewport(
        screenWidth = 1080,
        screenHeight = 2400,
        topSafeInset = 120,
        bottomSafeInset = 144,
        imeTop = null
    )

    @Test
    fun nativeToolbarPresent_docksDirectlyBelowToolbarWith4dpGap() {
        val toolbar = Box(180, 540, 860, 684)
        val sel = Box(200, 720, 700, 780)
        val target = PopupPositioner.computeAnchorTarget(sel, toolbar, phoneViewport, density)
        assertTrue(target.dockedToNativeToolbar)

        val placement = PopupPositioner.place(target, phoneViewport, popupWidth = 760, popupHeight = 144, density, isDrawerOpen = false)
        assertFalse(placement.placedAbove)
        assertEquals(toolbar.bottom + (4 * density).toInt(), placement.y)
        assertTrue(placement.x >= 0 && placement.x + 760 <= phoneViewport.screenWidth)
    }

    @Test
    fun nativeToolbarNearIme_flipsCleanlyDirectlyAboveToolbar() {
        val imeViewport = phoneViewport.copy(imeTop = 1350)
        val toolbar = Box(160, 1180, 880, 1320)
        val target = PopupPositioner.computeAnchorTarget(null, toolbar, imeViewport, density)
        assertTrue(target.dockedToNativeToolbar)

        val placement = PopupPositioner.place(target, imeViewport, popupWidth = 760, popupHeight = 144, density, isDrawerOpen = false)
        assertTrue("Should flip above native toolbar when IME leaves no room below", placement.placedAbove)
        assertEquals(toolbar.top - (4 * density).toInt() - 144, placement.y)
        assertTrue(placement.y >= imeViewport.topSafeInset)
    }

    @Test
    fun noToolbar_anchorsBelowSelectionAndTeardropHandles() {
        val sel = Box(200, 600, 700, 660)
        val target = PopupPositioner.computeAnchorTarget(sel, toolbarBounds = null, phoneViewport, density)
        assertFalse(target.dockedToNativeToolbar)

        val placement = PopupPositioner.place(target, phoneViewport, popupWidth = 760, popupHeight = 144, density, isDrawerOpen = false)
        assertFalse(placement.placedAbove)
        assertTrue(placement.y >= sel.bottom + (26 * density).toInt())
    }

    @Test
    fun missingSelectionAndToolbarBounds_usesSafeFallbackAnchorInsideViewport() {
        val target = PopupPositioner.computeAnchorTarget(null, null, phoneViewport, density)
        val placement = PopupPositioner.place(target, phoneViewport, popupWidth = 700, popupHeight = 144, density, isDrawerOpen = false)

        assertFalse(target.zone.isEmpty)
        assertTrue(placement.y >= phoneViewport.topSafeInset)
        assertTrue(placement.y + 144 <= phoneViewport.visibleBottom)
        assertTrue(placement.x >= 0 && placement.x + 700 <= phoneViewport.screenWidth)
    }

    @Test
    fun clampDragged_keepsPopupStrictlyInsideSafeViewport() {
        val (x1, y1) = PopupPositioner.clampDragged(-500, -100, 600, 144, phoneViewport, density)
        assertTrue(x1 >= 0)
        assertTrue(y1 >= phoneViewport.topSafeInset)

        val (x2, y2) = PopupPositioner.clampDragged(5000, 5000, 600, 144, phoneViewport, density)
        assertTrue(x2 + 600 <= phoneViewport.screenWidth)
        assertTrue(y2 + 144 <= phoneViewport.visibleBottom)
    }
}
