package io.github.selclip.core

/**
 * Active visual surface of the popup overlay.
 */
sealed class PopupMode {
    data object Bar : PopupMode()
    data object MoreTools : PopupMode()
    data object WebSearch : PopupMode()
    data object YouTube : PopupMode()
    data object Markdown : PopupMode()
    data object RegexPipe : PopupMode()
    data class CustomFolder(val folderName: String) : PopupMode()
    data object QrCode : PopupMode()
    data object Magnet : PopupMode()
    data object Define : PopupMode()
    data object Translate : PopupMode()
    data object Extract : PopupMode()
}

/**
 * Explicit lifecycle states for the floating popup.
 */
sealed class PopupState {
    data object Hidden : PopupState()

    data class PendingProbe(
        val token: Long,
        val sourcePackage: String,
        val toolbarSig: String,
        val startedAtMs: Long
    ) : PopupState()

    data class Visible(
        val sessionId: Long,
        val text: String,
        val sourcePackage: String,
        val isEditable: Boolean,
        val mode: PopupMode,
        val shownAtMs: Long,
        val lastUpdateAtMs: Long,
        val toolbarSig: String,
        val manuallyDragged: Boolean
    ) : PopupState()
}

/**
 * Events fed into [PopupStateMachine] from accessibility events, overlay interactions, and action runs.
 */
sealed class PopupEvent {
    data class SelectionDetected(
        val text: String,
        val sourcePackage: String,
        val isEditable: Boolean,
        val toolbarSig: String = "",
        val openExpanded: Boolean = false,
        val nowMs: Long
    ) : PopupEvent()

    data class ProbeRequested(
        val sourcePackage: String,
        val toolbarSig: String = "",
        val nowMs: Long
    ) : PopupEvent()

    data class ToolbarGone(val nowMs: Long) : PopupEvent()
    data class SelectionCleared(val nowMs: Long) : PopupEvent()
    data class OutsideTouch(val nowMs: Long) : PopupEvent()
    data class ExternalViewClicked(val nowMs: Long) : PopupEvent()
    data class Scrolled(val nowMs: Long) : PopupEvent()
    data class AppSwitched(val newPackage: String, val nowMs: Long) : PopupEvent()
    data object BackOrHomePressed : PopupEvent()
    data class SwitchMode(val mode: PopupMode, val nowMs: Long) : PopupEvent()
    data class ToggleMore(val nowMs: Long) : PopupEvent()
    data object UserDragged : PopupEvent()
    data class ActionCompleted(val keepOpen: Boolean = false) : PopupEvent()
    data object Dismiss : PopupEvent()
}

/**
 * Side-effects produced by state transitions so the Android runtime stays thin & deterministic.
 */
sealed class PopupEffect {
    data class ShowOrUpdate(val state: PopupState.Visible, val isNewSession: Boolean) : PopupEffect()
    data object HideOverlay : PopupEffect()
    data class ScheduleProbe(val token: Long, val firstDelayMs: Long, val secondDelayMs: Long) : PopupEffect()
    data object CancelProbes : PopupEffect()
    data object CancelNetwork : PopupEffect()
}

data class TransitionResult(
    val state: PopupState,
    val effects: List<PopupEffect>
)

/**
 * Pure, deterministic finite state machine governing popup visibility, instant lockstep timing
 * with Android's native toolbar, and bulletproof dismissal (impossible to get stuck on screen).
 */
class PopupStateMachine {

    companion object {
        const val OUTSIDE_TOUCH_GRACE_MS = 180L
        const val EXTERNAL_CLICK_GRACE_MS = 220L
        const val TOOLBAR_GONE_GRACE_MS = 220L
        const val SCROLL_DISMISS_GRACE_MS = 220L
        const val APP_SWITCH_GRACE_MS = 320L
        const val SELECTION_CLEAR_GRACE_MS = 260L
        const val DUPLICATE_SUPPRESS_MS = 220L
        const val PROBE_FIRST_DELAY_MS = 30L
        const val PROBE_SECOND_DELAY_MS = 110L
    }

    var state: PopupState = PopupState.Hidden
        private set

    var lastDismissedToolbarSig: String = ""
        private set

    var lastProbeToolbarSig: String = ""
        private set

    var lastProbeTimeMs: Long = 0L
        private set

    private var nextSessionId: Long = 1L
    private var nextProbeToken: Long = 1L

    fun onEvent(event: PopupEvent): TransitionResult {
        val cur = state
        val result = when (event) {
            is PopupEvent.SelectionDetected -> handleSelectionDetected(cur, event)
            is PopupEvent.ProbeRequested -> handleProbeRequested(cur, event)
            is PopupEvent.ToolbarGone -> handleToolbarGone(cur, event)
            is PopupEvent.SelectionCleared -> handleSelectionCleared(cur, event)
            is PopupEvent.OutsideTouch -> handleOutsideTouch(cur, event)
            is PopupEvent.ExternalViewClicked -> handleExternalClick(cur, event)
            is PopupEvent.Scrolled -> handleScrolled(cur, event)
            is PopupEvent.AppSwitched -> handleAppSwitched(cur, event)
            PopupEvent.BackOrHomePressed -> dismissIfActive(cur)
            is PopupEvent.SwitchMode -> handleSwitchMode(cur, event)
            is PopupEvent.ToggleMore -> handleToggleMore(cur, event)
            PopupEvent.UserDragged -> handleUserDragged(cur)
            is PopupEvent.ActionCompleted -> if (event.keepOpen) TransitionResult(cur, emptyList()) else dismissIfActive(cur)
            PopupEvent.Dismiss -> dismissIfActive(cur)
        }
        state = result.state
        return result
    }

    fun resetToolbarMemory() {
        lastDismissedToolbarSig = ""
        lastProbeToolbarSig = ""
        lastProbeTimeMs = 0L
    }

    private fun handleSelectionDetected(
        cur: PopupState,
        ev: PopupEvent.SelectionDetected
    ): TransitionResult {
        val cleanText = ev.text
        if (cleanText.isBlank()) return TransitionResult(cur, emptyList())

        val targetMode = if (ev.openExpanded) PopupMode.MoreTools else PopupMode.Bar

        if (cur is PopupState.Visible) {
            val sameText = cur.text == cleanText
            val withinDup = (ev.nowMs - cur.lastUpdateAtMs) < DUPLICATE_SUPPRESS_MS
            if (sameText && withinDup && !ev.openExpanded) {
                return TransitionResult(cur, listOf(PopupEffect.CancelProbes))
            }

            val nextMode = when {
                ev.openExpanded -> PopupMode.MoreTools
                sameText -> cur.mode
                else -> PopupMode.Bar
            }
            val updated = cur.copy(
                text = cleanText,
                sourcePackage = ev.sourcePackage.ifEmpty { cur.sourcePackage },
                isEditable = ev.isEditable,
                mode = nextMode,
                lastUpdateAtMs = ev.nowMs,
                toolbarSig = ev.toolbarSig.ifEmpty { cur.toolbarSig }
            )
            val effects = buildList {
                add(PopupEffect.CancelProbes)
                if (!sameText) add(PopupEffect.CancelNetwork)
                add(PopupEffect.ShowOrUpdate(updated, isNewSession = false))
            }
            return TransitionResult(updated, effects)
        }

        val next = PopupState.Visible(
            sessionId = nextSessionId++,
            text = cleanText,
            sourcePackage = ev.sourcePackage,
            isEditable = ev.isEditable,
            mode = targetMode,
            shownAtMs = ev.nowMs,
            lastUpdateAtMs = ev.nowMs,
            toolbarSig = ev.toolbarSig,
            manuallyDragged = false
        )
        if (ev.toolbarSig.isNotEmpty()) {
            lastProbeToolbarSig = ev.toolbarSig
            lastProbeTimeMs = ev.nowMs
        }
        return TransitionResult(
            state = next,
            effects = listOf(
                PopupEffect.CancelProbes,
                PopupEffect.ShowOrUpdate(next, isNewSession = true)
            )
        )
    }

    private fun handleProbeRequested(
        cur: PopupState,
        ev: PopupEvent.ProbeRequested
    ): TransitionResult {
        if (cur is PopupState.Visible) return TransitionResult(cur, emptyList())

        if (ev.toolbarSig.isNotEmpty() &&
            ev.toolbarSig == lastProbeToolbarSig &&
            (ev.nowMs - lastProbeTimeMs) < 900L
        ) {
            return TransitionResult(cur, emptyList())
        }

        val token = nextProbeToken++
        if (ev.toolbarSig.isNotEmpty()) {
            lastProbeToolbarSig = ev.toolbarSig
            lastProbeTimeMs = ev.nowMs
        }

        val nextState = PopupState.PendingProbe(
            token = token,
            sourcePackage = ev.sourcePackage,
            toolbarSig = ev.toolbarSig,
            startedAtMs = ev.nowMs
        )
        return TransitionResult(
            state = nextState,
            effects = listOf(
                PopupEffect.CancelProbes,
                PopupEffect.ScheduleProbe(token, PROBE_FIRST_DELAY_MS, PROBE_SECOND_DELAY_MS)
            )
        )
    }

    /**
     * Lockstep dismissal with Android's native toolbar:
     * When the native toolbar disappears while Sel is showing in Bar mode (after the brief grace
     * window), Sel dismisses immediately so both popups disappear together and Sel never gets stuck!
     */
    private fun handleToolbarGone(cur: PopupState, ev: PopupEvent.ToolbarGone): TransitionResult {
        resetToolbarMemory()
        return when (cur) {
            is PopupState.PendingProbe -> TransitionResult(PopupState.Hidden, listOf(PopupEffect.CancelProbes))
            is PopupState.Visible -> {
                if (cur.mode == PopupMode.Bar && (ev.nowMs - cur.shownAtMs) >= TOOLBAR_GONE_GRACE_MS) {
                    dismissIfActive(cur)
                } else {
                    TransitionResult(cur, emptyList())
                }
            }
            PopupState.Hidden -> TransitionResult(cur, emptyList())
        }
    }

    private fun handleSelectionCleared(
        cur: PopupState,
        ev: PopupEvent.SelectionCleared
    ): TransitionResult {
        if (cur !is PopupState.Visible) return TransitionResult(cur, emptyList())
        if (cur.mode != PopupMode.Bar) return TransitionResult(cur, emptyList())
        if (ev.nowMs - cur.shownAtMs < SELECTION_CLEAR_GRACE_MS) {
            return TransitionResult(cur, emptyList())
        }
        return dismissIfActive(cur)
    }

    private fun handleOutsideTouch(
        cur: PopupState,
        ev: PopupEvent.OutsideTouch
    ): TransitionResult {
        if (cur !is PopupState.Visible) return TransitionResult(cur, emptyList())
        if (ev.nowMs - cur.shownAtMs < OUTSIDE_TOUCH_GRACE_MS) {
            return TransitionResult(cur, emptyList())
        }
        return dismissIfActive(cur)
    }

    private fun handleExternalClick(
        cur: PopupState,
        ev: PopupEvent.ExternalViewClicked
    ): TransitionResult {
        if (cur !is PopupState.Visible) return TransitionResult(cur, emptyList())
        if (ev.nowMs - cur.shownAtMs < EXTERNAL_CLICK_GRACE_MS) {
            return TransitionResult(cur, emptyList())
        }
        return dismissIfActive(cur)
    }

    private fun handleScrolled(
        cur: PopupState,
        ev: PopupEvent.Scrolled
    ): TransitionResult {
        if (cur !is PopupState.Visible) return TransitionResult(cur, emptyList())
        if (cur.mode != PopupMode.Bar) return TransitionResult(cur, emptyList())
        if (ev.nowMs - cur.lastUpdateAtMs < SCROLL_DISMISS_GRACE_MS) {
            return TransitionResult(cur, emptyList())
        }
        return dismissIfActive(cur)
    }

    private fun handleAppSwitched(
        cur: PopupState,
        ev: PopupEvent.AppSwitched
    ): TransitionResult {
        if (cur !is PopupState.Visible) {
            return if (cur is PopupState.PendingProbe && ev.newPackage != cur.sourcePackage) {
                resetToolbarMemory()
                TransitionResult(PopupState.Hidden, listOf(PopupEffect.CancelProbes))
            } else {
                TransitionResult(cur, emptyList())
            }
        }
        if (ev.newPackage.isNotEmpty() && cur.sourcePackage.isNotEmpty() && ev.newPackage != cur.sourcePackage) {
            if (ev.nowMs - cur.shownAtMs >= APP_SWITCH_GRACE_MS) {
                return dismissIfActive(cur)
            }
        }
        return TransitionResult(cur, emptyList())
    }

    private fun handleSwitchMode(
        cur: PopupState,
        ev: PopupEvent.SwitchMode
    ): TransitionResult {
        if (cur !is PopupState.Visible) return TransitionResult(cur, emptyList())
        val updated = cur.copy(mode = ev.mode, lastUpdateAtMs = ev.nowMs)
        return TransitionResult(
            state = updated,
            effects = listOf(
                PopupEffect.CancelNetwork,
                PopupEffect.ShowOrUpdate(updated, isNewSession = false)
            )
        )
    }

    private fun handleToggleMore(
        cur: PopupState,
        ev: PopupEvent.ToggleMore
    ): TransitionResult {
        if (cur !is PopupState.Visible) return TransitionResult(cur, emptyList())
        val nextMode = if (cur.mode == PopupMode.Bar) PopupMode.MoreTools else PopupMode.Bar
        val updated = cur.copy(mode = nextMode, lastUpdateAtMs = ev.nowMs)
        return TransitionResult(
            state = updated,
            effects = listOf(
                PopupEffect.CancelNetwork,
                PopupEffect.ShowOrUpdate(updated, isNewSession = false)
            )
        )
    }

    private fun handleUserDragged(cur: PopupState): TransitionResult {
        if (cur !is PopupState.Visible) return TransitionResult(cur, emptyList())
        return TransitionResult(cur.copy(manuallyDragged = true), emptyList())
    }

    private fun dismissIfActive(cur: PopupState): TransitionResult {
        if (cur is PopupState.Visible) {
            lastDismissedToolbarSig = cur.toolbarSig
        }
        resetToolbarMemory()
        return when (cur) {
            PopupState.Hidden -> TransitionResult(PopupState.Hidden, emptyList())
            is PopupState.PendingProbe -> TransitionResult(PopupState.Hidden, listOf(PopupEffect.CancelProbes))
            is PopupState.Visible -> TransitionResult(
                PopupState.Hidden,
                listOf(
                    PopupEffect.CancelProbes,
                    PopupEffect.CancelNetwork,
                    PopupEffect.HideOverlay
                )
            )
        }
    }
}
