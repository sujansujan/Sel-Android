package io.github.selclip.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupStateMachineTest {

    @Test
    fun selectionDetected_transitionsHiddenToVisibleAndSuppressesRapidDuplicate() {
        val sm = PopupStateMachine()
        val r1 = sm.onEvent(PopupEvent.SelectionDetected("hello", "com.app", false, nowMs = 1000L))
        assertTrue(r1.state is PopupState.Visible)
        assertTrue(r1.effects.any { it is PopupEffect.ShowOrUpdate && it.isNewSession })

        // Duplicate within DUPLICATE_SUPPRESS_MS -> no redundant ShowOrUpdate
        val r2 = sm.onEvent(PopupEvent.SelectionDetected("hello", "com.app", false, nowMs = 1100L))
        assertFalse(r2.effects.any { it is PopupEffect.ShowOrUpdate })

        // Changed text -> updates overlay & cancels in-flight network lookups
        val r3 = sm.onEvent(PopupEvent.SelectionDetected("world", "com.app", false, nowMs = 1150L))
        assertEquals("world", (r3.state as PopupState.Visible).text)
        assertTrue(r3.effects.any { it is PopupEffect.CancelNetwork })
        assertTrue(r3.effects.any { it is PopupEffect.ShowOrUpdate && !it.isNewSession })
    }

    @Test
    fun outsideTouch_respectsGracePeriodThenDismissesCleanly() {
        val sm = PopupStateMachine()
        sm.onEvent(PopupEvent.SelectionDetected("hello", "com.app", false, nowMs = 1000L))

        val early = sm.onEvent(PopupEvent.OutsideTouch(nowMs = 1120L))
        assertTrue(early.state is PopupState.Visible)
        assertTrue(early.effects.isEmpty())

        val late = sm.onEvent(PopupEvent.OutsideTouch(nowMs = 1300L))
        assertEquals(PopupState.Hidden, late.state)
        assertTrue(late.effects.contains(PopupEffect.HideOverlay))
        assertEquals("", sm.lastProbeToolbarSig)
    }

    @Test
    fun scroll_dismissesBarAfterGraceButKeepsDrawerOpen() {
        val sm = PopupStateMachine()
        sm.onEvent(PopupEvent.SelectionDetected("serendipity", "com.app", false, nowMs = 1000L))

        // Scroll while in Bar mode after grace -> dismisses
        val dismissed = sm.onEvent(PopupEvent.Scrolled(nowMs = 1400L))
        assertEquals(PopupState.Hidden, dismissed.state)

        // Open drawer (e.g. Define) -> scrolling should NOT dismiss
        sm.onEvent(PopupEvent.SelectionDetected("serendipity", "com.app", false, nowMs = 2000L))
        sm.onEvent(PopupEvent.SwitchMode(PopupMode.Define, nowMs = 2050L))
        val kept = sm.onEvent(PopupEvent.Scrolled(nowMs = 2600L))
        assertTrue(kept.state is PopupState.Visible)
        assertEquals(PopupMode.Define, (kept.state as PopupState.Visible).mode)
    }

    @Test
    fun backOrHome_andAppSwitch_dismissImmediately() {
        val sm = PopupStateMachine()
        sm.onEvent(PopupEvent.SelectionDetected("hello", "com.browser", false, nowMs = 1000L))

        val home = sm.onEvent(PopupEvent.BackOrHomePressed)
        assertEquals(PopupState.Hidden, home.state)
        assertTrue(home.effects.contains(PopupEffect.HideOverlay))

        sm.onEvent(PopupEvent.SelectionDetected("hello", "com.browser", false, nowMs = 2000L))
        val switched = sm.onEvent(PopupEvent.AppSwitched("com.chat", nowMs = 2500L))
        assertEquals(PopupState.Hidden, switched.state)
    }

    @Test
    fun toolbarGone_resetsSignatureSoSameSpotReselectionTriggers() {
        val sm = PopupStateMachine()
        val p1 = sm.onEvent(PopupEvent.ProbeRequested("com.chrome", toolbarSig = "w1_10_20", nowMs = 1000L))
        assertTrue(p1.effects.any { it is PopupEffect.ScheduleProbe })

        // Duplicate probe for same signature within 1.2s is suppressed
        val dup = sm.onEvent(PopupEvent.ProbeRequested("com.chrome", toolbarSig = "w1_10_20", nowMs = 1300L))
        assertTrue(dup.effects.isEmpty())

        // Toolbar disappears -> memory resets -> same signature triggers again immediately!
        sm.onEvent(PopupEvent.ToolbarGone(nowMs = 1400L))
        val p2 = sm.onEvent(PopupEvent.ProbeRequested("com.chrome", toolbarSig = "w1_10_20", nowMs = 1500L))
        assertTrue(p2.effects.any { it is PopupEffect.ScheduleProbe })
    }

    @Test
    fun selectionCleared_dismissesBarButPreservesExpandedDrawer() {
        val sm = PopupStateMachine()
        sm.onEvent(PopupEvent.SelectionDetected("test", "com.notes", true, nowMs = 1000L))
        sm.onEvent(PopupEvent.ToggleMore(nowMs = 1050L))
        assertEquals(PopupMode.MoreTools, (sm.state as PopupState.Visible).mode)

        // Selection cleared while user is browsing MoreTools drawer -> stay open
        val stillOpen = sm.onEvent(PopupEvent.SelectionCleared(nowMs = 1600L))
        assertTrue(stillOpen.state is PopupState.Visible)

        // Collapse back to Bar -> selection clear dismisses
        sm.onEvent(PopupEvent.ToggleMore(nowMs = 1700L))
        val hidden = sm.onEvent(PopupEvent.SelectionCleared(nowMs = 1800L))
        assertEquals(PopupState.Hidden, hidden.state)
    }

    @Test
    fun toolbarGone_andExternalViewClick_dismissVisibleBarInLockstep() {
        val sm = PopupStateMachine()
        sm.onEvent(PopupEvent.SelectionDetected("chrome text", "com.android.chrome", false, toolbarSig = "tb1", nowMs = 1000L))

        // Native toolbar disappears after grace -> Sel dismisses immediately!
        val gone = sm.onEvent(PopupEvent.ToolbarGone(nowMs = 1300L))
        assertEquals(PopupState.Hidden, gone.state)
        assertTrue(gone.effects.contains(PopupEffect.HideOverlay))

        // External click in another view -> dismisses cleanly even if ACTION_OUTSIDE was blocked
        sm.onEvent(PopupEvent.SelectionDetected("other text", "com.notes", false, nowMs = 2000L))
        val clicked = sm.onEvent(PopupEvent.ExternalViewClicked(nowMs = 2300L))
        assertEquals(PopupState.Hidden, clicked.state)
        assertTrue(clicked.effects.contains(PopupEffect.HideOverlay))
    }
}
