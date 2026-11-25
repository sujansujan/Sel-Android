package io.github.selclip.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionFilterTest {

    @Test
    fun sanitizeCandidate_rejectsPasswordsBlankAndHintOnly() {
        assertNull(SelectionFilter.sanitizeCandidate("secret123", isPassword = true))
        assertNull(SelectionFilter.sanitizeCandidate("   \n\t  "))
        assertNull(SelectionFilter.sanitizeCandidate("Search...", hintText = "Search..."))
        assertEquals("hello", SelectionFilter.sanitizeCandidate("hello", hintText = "Search..."))
    }

    @Test
    fun extractRange_handlesNormalAndReversedIndicesSafely() {
        assertEquals("world", SelectionFilter.extractRange("hello world", 6, 11))
        assertEquals("world", SelectionFilter.extractRange("hello world", 11, 6))
        assertNull(SelectionFilter.extractRange("hello world", 5, 5))
        assertNull(SelectionFilter.extractRange("hello world", -1, 5))
        assertNull(SelectionFilter.extractRange("hello world", 0, 99))
    }

    @Test
    fun packageClassifiers_detectImeLauncherAndTransientPopups() {
        assertTrue(SelectionFilter.isImePackage("com.google.android.inputmethod.latin"))
        assertTrue(SelectionFilter.isImePackage("com.samsung.android.honeyboard"))
        assertFalse(SelectionFilter.isImePackage("com.android.chrome"))

        assertTrue(SelectionFilter.isLauncherPackage("com.google.android.apps.nexuslauncher"))
        assertTrue(SelectionFilter.isTransientPopupClass("android.widget.PopupWindow\$PopupDecorView"))
        assertFalse(SelectionFilter.isTransientPopupClass("android.widget.LinearLayout"))
    }

    @Test
    fun eventDebouncer_throttlesWithinMinInterval() {
        val debouncer = EventDebouncer(150L)
        assertTrue(debouncer.shouldAllow(1000L))
        assertFalse(debouncer.shouldAllow(1080L))
        assertTrue(debouncer.shouldAllow(1150L))
        debouncer.reset()
        assertTrue(debouncer.shouldAllow(1160L))
    }
}
