package io.github.selclip

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import io.github.selclip.actions.ActionExecutor
import io.github.selclip.core.Box
import io.github.selclip.core.ContextActionResolver
import io.github.selclip.core.EventDebouncer
import io.github.selclip.core.PopupEffect
import io.github.selclip.core.PopupEvent
import io.github.selclip.core.PopupMode
import io.github.selclip.core.PopupPositioner
import io.github.selclip.core.PopupState
import io.github.selclip.core.PopupStateMachine
import io.github.selclip.core.SelectionFilter
import io.github.selclip.data.PrefsStore
import io.github.selclip.data.ToolSpec
import io.github.selclip.overlay.DrawerRenderer
import io.github.selclip.overlay.OverlayWindowController
import io.github.selclip.service.ClipboardBridge
import io.github.selclip.service.SelectionResolver

/**
 * Lean, strictly event-driven AccessibilityService orchestrator.
 *
 * Zero polling, zero background timers, zero eager threads:
 * Accessibility events -> SelectionResolver -> PopupStateMachine -> OverlayWindowController.
 */
class SelAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: SelAccessibilityService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val stateMachine = PopupStateMachine()
    private val windowsDebouncer = EventDebouncer(140L)
    private val contentDebouncer = EventDebouncer(220L)

    private lateinit var windowManager: WindowManager
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var selectionResolver: SelectionResolver
    private lateinit var clipboardBridge: ClipboardBridge
    private lateinit var overlayController: OverlayWindowController
    private lateinit var actionExecutor: ActionExecutor

    private var activePackage: String = ""
    private var currentNode: AccessibilityNodeInfo? = null
    private var lastSelectionBounds: Box? = null
    private var lastSelectionBoundsMs: Long = 0L

    private var pendingProbe1: Runnable? = null
    private var pendingProbe2: Runnable? = null
    private var pendingProbeSource: AccessibilityNodeInfo? = null
    private var pendingProbePkg: String = ""
    private var pendingProbeSig: String = ""
    private var lastAutoCopyMs: Long = 0L

    override fun onServiceConnected() {
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        selectionResolver = SelectionResolver(this, windowManager)

        overlayController = OverlayWindowController(
            context = this,
            windowManager = windowManager,
            onOutsideTouch = { dispatch(PopupEvent.OutsideTouch(now())) },
            onUserDragged = { dispatch(PopupEvent.UserDragged) }
        )

        actionExecutor = ActionExecutor(
            context = this,
            clipboardManager = clipboardManager,
            onMarkInternalCopy = { clipboardBridge.markInternalCopy() },
            onSwitchMode = { mode -> dispatch(PopupEvent.SwitchMode(mode, now())) },
            onDismiss = { dispatch(PopupEvent.Dismiss) }
        )

        clipboardBridge = ClipboardBridge(
            context = this,
            clipboardManager = clipboardManager,
            windowManager = windowManager,
            shouldAcceptExternalClip = { activePackage.isEmpty() || !PrefsStore.isAppMuted(this, activePackage) },
            onClipCaptured = { clipText -> showFromExternalText(clipText) }
        )
        clipboardBridge.start()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val cur = stateMachine.state
        if (cur is PopupState.Visible) {
            handler.post { renderVisibleState(cur, isNewSession = false) }
        }
    }

    override fun onInterrupt() {
        dispatch(PopupEvent.Dismiss)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (instance === this) instance = null
        cancelProbes()
        if (::clipboardBridge.isInitialized) clipboardBridge.stop()
        if (::actionExecutor.isInitialized) actionExecutor.shutdown()
        if (::overlayController.isInitialized) overlayController.hide()
        currentNode = null
    }

    fun refreshThemeIfVisible() {
        val cur = stateMachine.state
        if (cur is PopupState.Visible) {
            handler.post { renderVisibleState(cur, isNewSession = false) }
        }
    }

    fun showFromExternalText(text: String, openExpanded: Boolean = false) {
        handler.post {
            val clean = SelectionFilter.sanitizeCandidate(text) ?: return@post
            PrefsStore.addClip(this, clean)
            currentNode = selectionResolver.findFocusedNode()
            val candidateBounds = currentNode?.let { n ->
                val r = Rect()
                n.getBoundsInScreen(r)
                Box(r.left, r.top, r.right, r.bottom).takeIf { !it.isEmpty }
            }
            if (candidateBounds != null) {
                lastSelectionBounds = candidateBounds
                lastSelectionBoundsMs = now()
            }
            dispatch(
                PopupEvent.SelectionDetected(
                    text = clean,
                    sourcePackage = activePackage,
                    isEditable = currentNode?.isEditable == true,
                    openExpanded = openExpanded,
                    nowMs = now()
                )
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        runCatching { processAccessibilityEvent(event) }
    }

    private fun processAccessibilityEvent(event: AccessibilityEvent) {
        val type = event.eventType

        // Fast zero-allocation exit for scroll events when popup is not visible
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (stateMachine.state is PopupState.Visible) {
                val pkg = event.packageName?.toString().orEmpty()
                if (pkg != packageName) {
                    dispatch(PopupEvent.Scrolled(now()))
                }
            }
            return
        }

        val pkg = event.packageName?.toString().orEmpty()
        val cls = event.className?.toString().orEmpty()

        // Track foreground app & dismiss immediately on Home / Launcher / App switch
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg.isNotEmpty() && pkg != packageName && !SelectionFilter.isImePackage(pkg)) {
                if (SelectionFilter.isLauncherPackage(pkg)) {
                    activePackage = pkg
                    dispatch(PopupEvent.BackOrHomePressed)
                    return
                }
                if (!SelectionFilter.isSystemUiOrFramework(pkg) && !SelectionFilter.isTransientPopupClass(cls)) {
                    val prev = activePackage
                    activePackage = pkg
                    if (prev.isNotEmpty() && prev != pkg) {
                        dispatch(PopupEvent.AppSwitched(pkg, now()))
                    }
                }
            }
        }

        if (pkg.isNotEmpty() && PrefsStore.isAppMuted(this, pkg)) return

        when (type) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SELECTED -> handleDirectSelectionEvent(event, pkg)

            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                if (pkg != packageName && !SelectionFilter.isSystemUiOrFramework(pkg) && !SelectionFilter.isImePackage(pkg)) {
                    stateMachine.resetToolbarMemory()
                    requestProbe(event.source, activePackage.ifEmpty { pkg }, toolbarSig = "")
                }
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (activePackage.isNotEmpty() && PrefsStore.isAppMuted(this, activePackage)) return
                if (!windowsDebouncer.shouldAllow(now())) return
                val toolbar = selectionResolver.findActiveToolbarNow()
                if (toolbar == null) {
                    selectionResolver.clearCachedToolbar()
                    dispatch(PopupEvent.ToolbarGone(now()))
                } else if (stateMachine.state !is PopupState.Visible) {
                    requestProbe(null, activePackage, toolbar.signature)
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (stateMachine.state !is PopupState.Visible &&
                    pkg != packageName &&
                    !SelectionFilter.isImePackage(pkg) &&
                    SelectionFilter.isTransientPopupClass(cls)
                ) {
                    val toolbar = selectionResolver.findActiveToolbarNow()
                    if (toolbar != null) {
                        requestProbe(event.source, activePackage.ifEmpty { pkg }, toolbar.signature)
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (stateMachine.state !is PopupState.Visible &&
                    pkg.isNotEmpty() &&
                    pkg != packageName &&
                    !SelectionFilter.isSystemUiOrFramework(pkg) &&
                    !SelectionFilter.isImePackage(pkg) &&
                    contentDebouncer.shouldAllow(now())
                ) {
                    val toolbar = selectionResolver.findActiveToolbarNow()
                    if (toolbar == null) {
                        selectionResolver.clearCachedToolbar()
                        dispatch(PopupEvent.ToolbarGone(now()))
                    } else {
                        requestProbe(event.source, pkg, toolbar.signature)
                    }
                }
            }
        }
    }

    private fun handleDirectSelectionEvent(event: AccessibilityEvent, pkg: String) {
        val resolved = selectionResolver.extractSelectionFromEvent(event)
        if (resolved != null) {
            currentNode = resolved.node
            lastSelectionBounds = resolved.bounds
            lastSelectionBoundsMs = now()
            if (pkg.isNotEmpty() && pkg != packageName && !SelectionFilter.isImePackage(pkg)) {
                activePackage = pkg
            }
            dispatch(
                PopupEvent.SelectionDetected(
                    text = resolved.text,
                    sourcePackage = activePackage.ifEmpty { pkg },
                    isEditable = resolved.node?.isEditable == true,
                    nowMs = now()
                )
            )
            return
        }

        // Range signaled on event but text hidden (WebView/Compose) -> probe
        if (event.fromIndex >= 0 && event.toIndex >= 0 && event.fromIndex != event.toIndex) {
            captureCandidateNodeBounds(event.source)
            requestProbe(event.source, activePackage.ifEmpty { pkg }, toolbarSig = "")
            return
        }

        // Selection collapsed on the active node -> clean dismissal
        val src = event.source
        if (stateMachine.state is PopupState.Visible && src != null && src == currentNode) {
            val s = src.textSelectionStart
            val e = src.textSelectionEnd
            if (s >= 0 && s == e) {
                dispatch(PopupEvent.SelectionCleared(now()))
            }
        }
    }

    private fun captureCandidateNodeBounds(source: AccessibilityNodeInfo?) {
        source ?: return
        val r = Rect()
        source.getBoundsInScreen(r)
        val (_, realH) = selectionResolver.readRealScreenSize()
        if (r.width() > 0 && r.height() in 1..(realH * 0.42f).toInt()) {
            lastSelectionBounds = Box(r.left, r.top, r.right, r.bottom)
            lastSelectionBoundsMs = now()
        }
    }

    private fun requestProbe(source: AccessibilityNodeInfo?, pkg: String, toolbarSig: String) {
        pendingProbeSource = source
        pendingProbePkg = pkg
        pendingProbeSig = toolbarSig
        dispatch(PopupEvent.ProbeRequested(pkg, toolbarSig, now()))
    }

    private fun executeProbeAttempt(): Boolean {
        val tNow = now()
        if (tNow - lastAutoCopyMs < 500L) return false
        val pkg = pendingProbePkg
        if (pkg.isNotEmpty() && pkg != packageName && !SelectionFilter.isImePackage(pkg)) {
            activePackage = pkg
        }
        if (activePackage.isNotEmpty() && PrefsStore.isAppMuted(this, activePackage)) return true

        // Tier 1: Zero-side-effect BFS read of selection from accessibility tree
        val treeHit = selectionResolver.findSelectionInTreeBfs(pendingProbeSource ?: rootInActiveWindow)
        if (treeHit != null) {
            currentNode = treeHit.node
            lastSelectionBounds = treeHit.bounds
            lastSelectionBoundsMs = tNow
            dispatch(
                PopupEvent.SelectionDetected(
                    text = treeHit.text,
                    sourcePackage = activePackage,
                    isEditable = treeHit.node?.isEditable == true,
                    toolbarSig = pendingProbeSig,
                    nowMs = tNow
                )
            )
            return true
        }

        // Tier 2: Trigger ACTION_COPY only when a real selection toolbar is on screen
        if (selectionResolver.findActiveToolbarNow() == null) return false
        lastAutoCopyMs = tNow
        captureCandidateNodeBounds(pendingProbeSource)

        val copied = selectionResolver.performBestCopyAction(pendingProbeSource)
        if (copied) {
            val triggerTime = now()
            handler.postDelayed({
                val st = stateMachine.state
                if (st !is PopupState.Visible || st.lastUpdateAtMs < triggerTime) {
                    clipboardBridge.readNowOrProbe()
                }
            }, 60L)
            return true
        }
        return false
    }

    private fun dispatch(event: PopupEvent) {
        val result = stateMachine.onEvent(event)
        for (effect in result.effects) {
            when (effect) {
                is PopupEffect.ShowOrUpdate -> renderVisibleState(effect.state, effect.isNewSession)
                PopupEffect.HideOverlay -> {
                    overlayController.hide()
                    selectionResolver.clearCachedToolbar()
                }
                is PopupEffect.ScheduleProbe -> scheduleProbeRunnables(effect.firstDelayMs, effect.secondDelayMs)
                PopupEffect.CancelProbes -> cancelProbes()
                PopupEffect.CancelNetwork -> actionExecutor.networkClient.cancelPending()
            }
        }
    }

    private fun scheduleProbeRunnables(firstDelayMs: Long, secondDelayMs: Long) {
        cancelProbes()
        val first = Runnable {
            if (!executeProbeAttempt()) {
                val second = Runnable {
                    if (stateMachine.state !is PopupState.Visible) {
                        executeProbeAttempt()
                    }
                }
                pendingProbe2 = second
                handler.postDelayed(second, secondDelayMs)
            }
        }
        pendingProbe1 = first
        handler.postDelayed(first, firstDelayMs)
    }

    private fun cancelProbes() {
        pendingProbe1?.let { handler.removeCallbacks(it) }
        pendingProbe2?.let { handler.removeCallbacks(it) }
        pendingProbe1 = null
        pendingProbe2 = null
    }

    private fun renderVisibleState(visible: PopupState.Visible, isNewSession: Boolean) {
        val snap = selectionResolver.captureScreenSnapshot(force = isNewSession)
        val freshBounds = lastSelectionBounds?.takeIf { now() - lastSelectionBoundsMs < 2600L }
        val anchorZone = PopupPositioner.computeAnchorZone(
            selectionBounds = freshBounds,
            toolbarBounds = snap.toolbar?.bounds,
            viewport = snap.viewport,
            density = selectionResolver.density
        )

        val hiddenIds = PrefsStore.getHiddenToolIds(this)
        val pinnedTools = PrefsStore.getPinnedToolSpecs(this)
        val defaultEngine = PrefsStore.resolveDefaultSearchEngine(this)
        val searchLabel = defaultEngine.glyph.take(6).ifEmpty { "Search" }

        val ctx = ContextActionResolver.analyze(
            rawText = visible.text,
            isEditable = visible.isEditable,
            hasUndo = actionExecutor.hasValidUndo(now())
        )
        val barActions = ContextActionResolver.resolveBarActions(
            ctx = ctx,
            hiddenToolIds = hiddenIds,
            pinnedTools = pinnedTools,
            searchLabel = searchLabel
        )

        val pal = PrefsStore.resolvePalette(this)
        val isDrawerOpen = visible.mode != PopupMode.Bar

        overlayController.showOrUpdate(
            anchorZone = anchorZone,
            viewport = snap.viewport,
            isDrawerOpen = isDrawerOpen,
            isNewSession = isNewSession,
            actions = barActions,
            onActionClick = { act -> actionExecutor.executePopAction(act, visible.text, currentNode) },
            onActionLongClick = { act -> actionExecutor.executePopActionLongPress(act, visible.text, currentNode) },
            onToggleMore = { dispatch(PopupEvent.ToggleMore(now())) },
            onClose = { dispatch(PopupEvent.Dismiss) },
            renderDrawer = { drawerContainer ->
                DrawerRenderer.render(
                    context = this,
                    container = drawerContainer,
                    mode = visible.mode,
                    selectedText = visible.text,
                    activePackage = visible.sourcePackage.ifEmpty { activePackage },
                    pal = pal,
                    executor = actionExecutor,
                    cb = object : DrawerRenderer.Callbacks {
                        override fun onBackToBar() = dispatch(PopupEvent.SwitchMode(PopupMode.Bar, now()))
                        override fun onRerender() {
                            val st = stateMachine.state
                            if (st is PopupState.Visible) renderVisibleState(st, isNewSession = false)
                        }
                        override fun onRelayout() = overlayController.relayout()
                        override fun onExecuteTool(tool: ToolSpec) = actionExecutor.executeToolSpec(tool, visible.text, currentNode)
                        override fun onApplyResult(newText: String) = actionExecutor.applyResult(newText, currentNode)
                        override fun onCopyPlain(text: String, toastMsg: String?) = actionExecutor.copyPlain(text, toastMsg)
                        override fun onOpenUrl(url: String) = actionExecutor.openUrl(url)
                        override fun onMutePackage(pkg: String) {
                            PrefsStore.setAppMuted(this@SelAccessibilityService, pkg, true)
                            Toast.makeText(this@SelAccessibilityService, "Muted popup in $pkg", Toast.LENGTH_SHORT).show()
                            dispatch(PopupEvent.Dismiss)
                        }
                        override fun isModeStillActive(mode: PopupMode): Boolean {
                            val st = stateMachine.state
                            return st is PopupState.Visible && st.mode == mode && overlayController.isAttached
                        }
                    }
                )
            }
        )
    }

    private fun now(): Long = System.currentTimeMillis()
}
