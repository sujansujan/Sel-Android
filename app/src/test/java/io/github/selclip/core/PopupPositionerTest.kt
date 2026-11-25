package io.github.selclip.core

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
    fun selectionWithRoomBelow_placesBelowSelectionAndHandlesWithoutOverlap() {
        val sel = Box(200, 600, 700, 660)
        val anchor = PopupPositioner.computeAnchorZone(sel, toolbarBounds = null, phoneViewport, density)
        val placement = PopupPositioner.place(anchor, phoneViewport, popupWidth = 800, popupHeight = 150, density, isDrawerOpen = false)

        assertFalse(placement.placedAbove)
        assertTrue("Popup should clear teardrop handles below selection", placement.y >= sel.bottom + (28 * density).toInt())
        assertTrue(placement.x >= 0)
        assertTrue(placement.x + 800 <= phoneViewport.screenWidth)
    }

    @Test
    fun nearbyNativeToolbar_isIncludedInExclusionZoneSoPopupDoesNotCoverIt() {
        val sel = Box(200, 700, 700, 760)
        val toolbar = Box(180, 540, 860, 660)
        val anchor = PopupPositioner.computeAnchorZone(sel, toolbar, phoneViewport, density)

        assertTrue("Anchor top should cover native toolbar", anchor.top <= toolbar.top)
        val placement = PopupPositioner.place(anchor, phoneViewport, popupWidth = 800, popupHeight = 150, density, isDrawerOpen = false)
        val popupBottom = placement.y + 150
        val overlapsToolbar = !(popupBottom <= toolbar.top || placement.y >= toolbar.bottom)
        assertFalse("Popup must not overlap native Copy/Cut toolbar", overlapsToolbar)
    }

    @Test
    fun openKeyboard_flipsPopupAboveSelectionAndNeverClipsIntoIme() {
        val imeViewport = phoneViewport.copy(imeTop = 1350)
        val sel = Box(150, 1180, 850, 1240)
        val anchor = PopupPositioner.computeAnchorZone(sel, toolbarBounds = null, imeViewport, density)
        val placement = PopupPositioner.place(anchor, imeViewport, popupWidth = 760, popupHeight = 150, density, isDrawerOpen = false)

        assertTrue("Should flip above selection when IME leaves no room below", placement.placedAbove)
        assertTrue("Popup must stay above IME", placement.y + 150 <= imeViewport.visibleBottom)
        assertTrue("Popup must stay below status bar", placement.y >= imeViewport.topSafeInset)
    }

    @Test
    fun missingSelectionAndToolbarBounds_usesSafeFallbackAnchorInsideViewport() {
        val anchor = PopupPositioner.computeAnchorZone(null, null, phoneViewport, density)
        val placement = PopupPositioner.place(anchor, phoneViewport, popupWidth = 700, popupHeight = 150, density, isDrawerOpen = false)

        assertFalse(anchor.isEmpty)
        assertTrue(placement.y >= phoneViewport.topSafeInset)
        assertTrue(placement.y + 150 <= phoneViewport.visibleBottom)
        assertTrue(placement.x >= 0 && placement.x + 700 <= phoneViewport.screenWidth)
    }

    @Test
    fun clampDragged_keepsPopupStrictlyInsideSafeViewport() {
        val (x1, y1) = PopupPositioner.clampDragged(-500, -100, 600, 150, phoneViewport, density)
        assertTrue(x1 >= 0)
        assertTrue(y1 >= phoneViewport.topSafeInset)

        val (x2, y2) = PopupPositioner.clampDragged(5000, 5000, 600, 150, phoneViewport, density)
        assertTrue(x2 + 600 <= phoneViewport.screenWidth)
        assertTrue(y2 + 150 <= phoneViewport.visibleBottom)
    }
}
