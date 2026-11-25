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
import io.github.selclip.data.FaviconRepository
import io.github.selclip.data.PrefsStore
import io.github.selclip.data.ToolSpec
import io.github.selclip.overlay.DrawerRenderer
import io.github.selclip.overlay.OverlayWindowController
import io.github.selclip.service.ClipboardBridge
import io.github.selclip.service.ScreenSnapshot
import io.github.selclip.service.SelectionResolver
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Lean, strictly event-driven AccessibilityService bound in lockstep to Android's native popup:
 *
 * - Zero mid-fade teleport: when a direct text-selection event arrives ~20-40ms before Android's
 *   native FloatingToolbarPopup, briefly buffers it (max 55ms) and spawns ONCE in the exact frame
 *   Android's toolbar appears at the exact final docked position.
 * - Zero Frame-1 main-thread freeze: suppresses self-echo `TYPE_WINDOWS_CHANGED` events, passes
 *   pre-captured `ScreenSnapshot` straight into rendering (1× IPC instead of 3-4×), and runs
 *   multi-window browser spatial scans off the main UI thread on a lazy daemon worker.
 * - Multi-layer anti-stuck guarantees: live ToolbarGone lockstep, O(1) ExternalViewClicked,
 *   OutsideTouch, Scrolled, AppSwitched, Back/Home/SystemUI, drawer × button, and safety timeout.
 */
class SelAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: SelAccessibilityService? = null

        // Wait long enough for a real human long-press + Magnifier to finish before ever
        // falling back to non-toolbar bounds; spawns in 0ms the instant Android's toolbar appears!
        private const val DIRECT_TOOLBAR_WAIT_MS = 420L
        private const val DIRECT_MAGNIFIER_RECHECK_MS = 260L
    }

    private data class PendingDirectSelection(
        val text: String,
        val sourcePackage: String,
        val isEditable: Boolean,
        val node: AccessibilityNodeInfo?,
        val bounds: Box,
        val capturedAtMs: Long
    )

    private val handler = Handler(Looper.getMainLooper())
    private val stateMachine = PopupStateMachine()
    private val contentDebouncer = EventDebouncer(350L)
    private val scanGeneration = AtomicLong(0L)

    @Volatile
    private var scanExecutor: ExecutorService? = null

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

    private var pendingDirectSelection: PendingDirectSelection? = null
    private var pendingDirectFlush: Runnable? = null

    private var pendingWindowsCheck: Runnable? = null
    private var pendingProbe1: Runnable? = null
    private var pendingProbe2: Runnable? = null
    private var pendingProbeSource: AccessibilityNodeInfo? = null
    private var pendingProbePkg: String = ""
    private var pendingProbeSig: String = ""

    private var lastFallbackCopyMs: Long = 0L
    private var lastFallbackCopySig: String = ""

    private fun getOrCreateScanExecutor(): ExecutorService {
        scanExecutor?.let { if (!it.isShutdown) return it }
        synchronized(this) {
            scanExecutor?.let { if (!it.isShutdown) return it }
            return Executors.newSingleThreadExecutor { r ->
                Thread(r, "sel-a11y-scan").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY
                }
            }.also { scanExecutor = it }
        }
    }

    override fun onServiceConnected() {
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        selectionResolver = SelectionResolver(this, windowManager)

        overlayController = OverlayWindowController(
            context = this,
            windowManager = windowManager,
            onOutsideTouch = { dispatch(PopupEvent.OutsideTouch(now())) },
            onSafetyTimeout = { dispatch(PopupEvent.Dismiss) }
        )

        clipboardBridge = ClipboardBridge(
            context = this,
            clipboardManager = clipboardManager,
            windowManager = windowManager
        )

        actionExecutor = ActionExecutor(
            context = this,
            clipboardManager = clipboardManager,
            onMarkInternalCopy = { clipboardBridge.markInternalCopy() },
            onSwitchMode = { mode -> dispatch(PopupEvent.SwitchMode(mode, now())) },
            onDismiss = { dispatch(PopupEvent.Dismiss) }
        )

        // Pre-warm RAM favicon cache off the main thread for bar portals (0ms UI cost!)
        val warmUrls = buildList {
            PrefsStore.resolveDefaultSearchEngine(this@SelAccessibilityService).customTarget?.let { add(it) }
            PrefsStore.getPinnedToolSpecs(this@SelAccessibilityService).mapNotNullTo(this) { it.customTarget }
        }
        if (warmUrls.isNotEmpty()) {
            FaviconRepository.warmMemoryCacheAsync(this, warmUrls)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        PrefsStore.invalidatePaletteCache()
        val cur = stateMachine.state
        if (cur is PopupState.Visible) {
            handler.post { renderVisibleState(cur, isNewSession = false, preCapturedSnap = null) }
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
        scanGeneration.incrementAndGet()
        cancelPendingDirectSelection()
        cancelWindowsCheck()
        cancelProbes()
        scanExecutor?.shutdownNow()
        scanExecutor = null
        if (::clipboardBridge.isInitialized) clipboardBridge.stop()
        if (::actionExecutor.isInitialized) actionExecutor.shutdown()
        if (::overlayController.isInitialized) overlayController.hide()
        currentNode = null
    }

    fun refreshThemeIfVisible() {
        PrefsStore.invalidatePaletteCache()
        val cur = stateMachine.state
        if (cur is PopupState.Visible) {
            handler.post { renderVisibleState(cur, isNewSession = false, preCapturedSnap = null) }
        }
    }

    fun showFromExternalText(text: String, openExpanded: Boolean = false) {
        handler.post {
            cancelPendingDirectSelection()
            val clean = SelectionFilter.sanitizeCandidate(text) ?: return@post
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
            val snap = selectionResolver.captureScreenSnapshot(force = true, allowStaleToolbar = true)
            dispatch(
                event = PopupEvent.SelectionDetected(
                    text = clean,
                    sourcePackage = activePackage,
                    isEditable = currentNode?.isEditable == true,
                    toolbarSig = pendingProbeSig.ifEmpty { snap.toolbar?.signature.orEmpty() },
                    openExpanded = openExpanded,
                    nowMs = now()
                ),
                preCapturedSnap = snap
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        runCatching { processAccessibilityEvent(event) }
    }

    private fun processAccessibilityEvent(event: AccessibilityEvent) {
        val type = event.eventType

        // O(1) zero-allocation exit for scroll & click events whenever popup is NOT visible!
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            cancelPendingDirectSelection()
            if (stateMachine.state is PopupState.Visible) {
                val pkg = event.packageName?.toString().orEmpty()
                if (pkg != packageName) dispatch(PopupEvent.Scrolled(now()))
            }
            return
        }
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            if (stateMachine.state is PopupState.Visible) {
                val pkg = event.packageName?.toString().orEmpty()
                if (pkg != packageName) {
                    dispatch(PopupEvent.ExternalViewClicked(now()))
                }
            }
            return
        }

        val pkg = event.packageName?.toString().orEmpty()
        val cls = event.className?.toString().orEmpty()

        // Immediate dismissal on Home/Launcher switch, SystemUI (Recents/Shade/Lockscreen), or App Switch.
        // IMPORTANT: Never treat a transient PopupWindow (Cut/Copy/Share) inside a launcher search bar as Home!
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg.isNotEmpty() && pkg != packageName && !SelectionFilter.isImePackage(pkg)) {
                val isTransient = SelectionFilter.isTransientPopupClass(cls)
                if (pkg == "com.android.systemui" && !isTransient) {
                    cancelPendingDirectSelection()
                    dispatch(PopupEvent.BackOrHomePressed)
                    return
                }
                if (!isTransient) {
                    val prev = activePackage
                    activePackage = pkg
                    if (SelectionFilter.isLauncherPackage(pkg)) {
                        if (prev.isNotEmpty() && prev != pkg) {
                            cancelPendingDirectSelection()
                            dispatch(PopupEvent.BackOrHomePressed)
                            return
                        }
                    } else if (!SelectionFilter.isSystemUiOrFramework(pkg) && prev.isNotEmpty() && prev != pkg) {
                        cancelPendingDirectSelection()
                        dispatch(PopupEvent.AppSwitched(pkg, now()))
                    }
                }
            }
        } else if (activePackage.isEmpty() && pkg.isNotEmpty() && pkg != packageName &&
            !SelectionFilter.isSystemUiOrFramework(pkg) && !SelectionFilter.isImePackage(pkg)
        ) {
            activePackage = pkg
        }

        if (pkg.isNotEmpty() && PrefsStore.isAppMuted(this, pkg)) return

        when (type) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SELECTED -> handleDirectSelectionEvent(event, pkg)

            // Long-press fires WHILE the user's finger is still down (Magnifier active).
            // Reset memory and remember source node ONLY — never spawn blindly before finger-up!
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                if (pkg != packageName && !SelectionFilter.isSystemUiOrFramework(pkg) && !SelectionFilter.isImePackage(pkg)) {
                    stateMachine.resetToolbarMemory()
                    lastFallbackCopySig = ""
                    pendingProbeSource = event.source
                    pendingProbePkg = activePackage.ifEmpty { pkg }
                    pendingProbeSig = ""
                }
            }

            // Ignore self-triggered window add/update echoes (~16-50ms) so Frame 1 NEVER stalls!
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (clipboardBridge.isProbeOrSettling) return
                if (overlayController.isRecentSelfWindowMutation(90L)) return
                if (activePackage.isNotEmpty() && PrefsStore.isAppMuted(this, activePackage)) return
                syncWithNativeToolbarNow(scheduleFollowUpIfMissing = true)
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (clipboardBridge.isProbeOrSettling) return
                if (overlayController.isRecentSelfWindowMutation(90L)) return
                if (pkg != packageName &&
                    !SelectionFilter.isImePackage(pkg) &&
                    SelectionFilter.isTransientPopupClass(cls)
                ) {
                    syncWithNativeToolbarNow(scheduleFollowUpIfMissing = true)
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (clipboardBridge.isProbeOrSettling) return
                if (overlayController.isRecentSelfWindowMutation(180L)) return
                val cur = stateMachine.state
                if (cur is PopupState.Visible && cur.mode != PopupMode.Bar) return
                if (pkg.isNotEmpty() &&
                    pkg != packageName &&
                    !SelectionFilter.isSystemUiOrFramework(pkg) &&
                    !SelectionFilter.isImePackage(pkg) &&
                    contentDebouncer.shouldAllow(now())
                ) {
                    syncWithNativeToolbarNow(scheduleFollowUpIfMissing = false)
                }
            }
        }
    }

    /**
     * Binds Sel directly to Android's native FloatingToolbarPopup in real time:
     * - Native toolbar appeared + buffered direct selection ready -> 0ms instant lockstep spawn!
     * - Native toolbar appeared (browser/WebView) -> async off-main-thread spatial tree scan!
     * - Native toolbar moved -> smoothly slides Sel underneath it (ignoring micro-wobble)!
     * - Native toolbar vanished -> instant lockstep dismissal!
     */
    private fun syncWithNativeToolbarNow(scheduleFollowUpIfMissing: Boolean) {
        val snap = selectionResolver.captureScreenSnapshot(force = true, allowStaleToolbar = false)
        val toolbar = snap.toolbar

        if (toolbar == null) {
            selectionResolver.clearCachedToolbar()
            lastFallbackCopySig = ""
            if (stateMachine.state is PopupState.Visible || stateMachine.state is PopupState.PendingProbe) {
                scanGeneration.incrementAndGet()
                dispatch(PopupEvent.ToolbarGone(now()))
            }
            if (scheduleFollowUpIfMissing && stateMachine.state !is PopupState.Visible) {
                scheduleShortTrailingCheck()
            }
            return
        }

        cancelWindowsCheck()

        // Lockstep Fast-Path: if a direct text selection arrived earlier while the user was holding
        // their finger down (Magnifier open), consume it in 0ms the instant Android's toolbar opens!
        val buffered = pendingDirectSelection
        if (buffered != null && (now() - buffered.capturedAtMs) < 2500L) {
            cancelPendingDirectSelection()
            currentNode = buffered.node
            lastSelectionBounds = buffered.bounds
            lastSelectionBoundsMs = now()
            dispatch(
                event = PopupEvent.SelectionDetected(
                    text = buffered.text,
                    sourcePackage = buffered.sourcePackage,
                    isEditable = buffered.isEditable,
                    toolbarSig = toolbar.signature,
                    nowMs = now()
                ),
                preCapturedSnap = snap
            )
            return
        }

        val cur = stateMachine.state
        if (cur is PopupState.Visible) {
            if (cur.mode == PopupMode.Bar) {
                val anchor = PopupPositioner.computeAnchorTarget(
                    selectionBounds = lastSelectionBounds?.takeIf { now() - lastSelectionBoundsMs < 2600L },
                    toolbarBounds = toolbar.bounds,
                    viewport = snap.viewport,
                    density = selectionResolver.density
                )
                overlayController.syncPositionToAnchor(anchor, snap.viewport)
            }
            return
        }

        if (toolbar.signature == lastFallbackCopySig) return

        // Browser / WebView Fast-Path: launch async spatial tree scan off the main UI thread!
        pendingProbeSource = null
        pendingProbePkg = activePackage
        pendingProbeSig = toolbar.signature
        launchAsyncSpatialProbe(snap = snap, allowFallbackScheduleOnMiss = true)
    }

    private fun scheduleShortTrailingCheck() {
        cancelWindowsCheck()
        val r = Runnable {
            pendingWindowsCheck = null
            if (clipboardBridge.isProbeOrSettling) return@Runnable
            if (overlayController.isRecentSelfWindowMutation(90L)) return@Runnable
            if (activePackage.isNotEmpty() && PrefsStore.isAppMuted(this, activePackage)) return@Runnable
            syncWithNativeToolbarNow(scheduleFollowUpIfMissing = false)
        }
        pendingWindowsCheck = r
        handler.postDelayed(r, 35L)
    }

    private fun cancelWindowsCheck() {
        pendingWindowsCheck?.let { handler.removeCallbacks(it) }
        pendingWindowsCheck = null
    }

    private fun cancelPendingDirectSelection() {
        pendingDirectFlush?.let { handler.removeCallbacks(it) }
        pendingDirectFlush = null
        pendingDirectSelection = null
    }

    private fun scheduleDirectFallbackFlush(delayMs: Long) {
        pendingDirectFlush?.let { handler.removeCallbacks(it) }
        val flush = Runnable {
            pendingDirectFlush = null
            val curPending = pendingDirectSelection ?: return@Runnable
            if (stateMachine.state is PopupState.Visible) {
                pendingDirectSelection = null
                return@Runnable
            }
            val freshSnap = selectionResolver.captureScreenSnapshot(force = true, allowStaleToolbar = false)
            // If Android's Text Magnifier loupe is still open, user's finger is still pressed down!
            if (freshSnap.toolbar == null &&
                freshSnap.hasMagnifierOrTransientFloating &&
                (now() - curPending.capturedAtMs) < 2200L
            ) {
                scheduleDirectFallbackFlush(DIRECT_MAGNIFIER_RECHECK_MS)
                return@Runnable
            }
            pendingDirectSelection = null
            dispatch(
                event = PopupEvent.SelectionDetected(
                    text = curPending.text,
                    sourcePackage = curPending.sourcePackage,
                    isEditable = curPending.isEditable,
                    toolbarSig = freshSnap.toolbar?.signature.orEmpty(),
                    nowMs = now()
                ),
                preCapturedSnap = freshSnap
            )
        }
        pendingDirectFlush = flush
        handler.postDelayed(flush, delayMs)
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

            val effPkg = activePackage.ifEmpty { pkg }
            val isEditable = resolved.node?.isEditable == true
            val snap = selectionResolver.captureScreenSnapshot(force = true, allowStaleToolbar = false)

            // If toolbar is ALREADY present OR popup is already visible (user dragging handles),
            // render IMMEDIATELY on this exact frame using the already-captured snapshot!
            if (snap.toolbar != null || stateMachine.state is PopupState.Visible) {
                cancelPendingDirectSelection()
                dispatch(
                    event = PopupEvent.SelectionDetected(
                        text = resolved.text,
                        sourcePackage = effPkg,
                        isEditable = isEditable,
                        toolbarSig = snap.toolbar?.signature.orEmpty(),
                        nowMs = now()
                    ),
                    preCapturedSnap = snap
                )
                return
            }

            // Otherwise, the user is almost certainly still holding their finger down (Magnifier
            // open) and Android's native FloatingToolbarPopup will open the instant they lift!
            // Buffer cleanly so Sel NEVER flashes prematurely over the toolbar zone, spawning in
            // 0ms lockstep when the native toolbar appears (or after 420ms if an app has none).
            cancelPendingDirectSelection()
            val pending = PendingDirectSelection(
                text = resolved.text,
                sourcePackage = effPkg,
                isEditable = isEditable,
                node = resolved.node,
                bounds = resolved.bounds,
                capturedAtMs = now()
            )
            pendingDirectSelection = pending
            scheduleDirectFallbackFlush(DIRECT_TOOLBAR_WAIT_MS)
            return
        }

        if (event.fromIndex >= 0 && event.toIndex >= 0 && event.fromIndex != event.toIndex) {
            captureCandidateNodeBounds(event.source)
            requestProbe(event.source, activePackage.ifEmpty { pkg }, toolbarSig = "")
            return
        }

        val src = event.source
        if (src != null && src == currentNode) {
            val s = src.textSelectionStart
            val e = src.textSelectionEnd
            if (s >= 0 && s == e) {
                cancelPendingDirectSelection()
                if (stateMachine.state is PopupState.Visible) {
                    dispatch(PopupEvent.SelectionCleared(now()))
                }
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
        if (stateMachine.state is PopupState.Visible) return
        if (clipboardBridge.isProbeOrSettling) return
        pendingProbeSource = source
        pendingProbePkg = pkg
        pendingProbeSig = toolbarSig
        dispatch(PopupEvent.ProbeRequested(pkg, toolbarSig, now()))
    }

    /**
     * Runs the potentially heavy multi-window spatial DOM scan on a single lazy background
     * daemon thread so the main UI thread & GPU animation loop NEVER stall for even 1 frame!
     */
    private fun launchAsyncSpatialProbe(
        snap: ScreenSnapshot,
        allowFallbackScheduleOnMiss: Boolean,
        allowClipboardFallbackOnMiss: Boolean = false
    ) {
        if (stateMachine.state is PopupState.Visible) return
        if (clipboardBridge.isProbeOrSettling) return

        val gen = scanGeneration.incrementAndGet()
        val preferredRoot = pendingProbeSource
        val pkgSnapshot = pendingProbePkg.ifEmpty { activePackage }
        val sigSnapshot = pendingProbeSig.ifEmpty { snap.toolbar?.signature.orEmpty() }
        val toolbar = snap.toolbar

        getOrCreateScanExecutor().execute {
            val treeHit = runCatching {
                selectionResolver.findSelectionAcrossAppWindows(
                    preferredRoot = preferredRoot,
                    toolbarBounds = toolbar?.bounds,
                    toolbarWindowId = toolbar?.windowId ?: -1
                )
            }.getOrNull()

            handler.post {
                if (gen != scanGeneration.get()) return@post
                if (stateMachine.state is PopupState.Visible) return@post
                if (clipboardBridge.isProbeOrSettling) return@post
                if (activePackage.isNotEmpty() && PrefsStore.isAppMuted(this, activePackage)) return@post

                if (treeHit != null) {
                    currentNode = treeHit.node
                    lastSelectionBounds = treeHit.bounds
                    lastSelectionBoundsMs = now()
                    dispatch(
                        event = PopupEvent.SelectionDetected(
                            text = treeHit.text,
                            sourcePackage = activePackage.ifEmpty { pkgSnapshot },
                            isEditable = treeHit.node?.isEditable == true,
                            toolbarSig = sigSnapshot,
                            nowMs = now()
                        ),
                        preCapturedSnap = snap
                    )
                    return@post
                }

                if (allowClipboardFallbackOnMiss && toolbar != null) {
                    executeClipboardFallback(toolbar)
                } else if (allowFallbackScheduleOnMiss) {
                    requestProbe(preferredRoot, pkgSnapshot, sigSnapshot)
                }
            }
        }
    }

    private fun executeClipboardFallback(activeToolbar: io.github.selclip.service.ToolbarHit): Boolean {
        val tNow = now()
        if (tNow - lastFallbackCopyMs < 1800L) return false

        val effSig = pendingProbeSig.ifEmpty { activeToolbar.signature }
        if (effSig.isNotEmpty() && effSig == lastFallbackCopySig) return true

        lastFallbackCopyMs = tNow
        lastFallbackCopySig = effSig
        pendingProbeSig = effSig
        captureCandidateNodeBounds(pendingProbeSource)
        overlayController.markSelfWindowMutation()

        // Tier 2 (Last Resort): Single-shot copy with immediate previous-clipboard restoration
        return clipboardBridge.captureFallbackSelection(
            performCopy = { selectionResolver.performBestCopyAction(pendingProbeSource, activeToolbar) },
            onCaptured = { text -> showFromExternalText(text) }
        )
    }

    private fun dispatch(event: PopupEvent, preCapturedSnap: ScreenSnapshot? = null) {
        val result = stateMachine.onEvent(event)
        for (effect in result.effects) {
            when (effect) {
                is PopupEffect.ShowOrUpdate -> renderVisibleState(effect.state, effect.isNewSession, preCapturedSnap)
                PopupEffect.HideOverlay -> {
                    scanGeneration.incrementAndGet()
                    cancelPendingDirectSelection()
                    cancelWindowsCheck()
                    lastFallbackCopySig = ""
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
            if (stateMachine.state is PopupState.Visible || clipboardBridge.isProbeOrSettling) return@Runnable
            val snap = selectionResolver.captureScreenSnapshot(force = true, allowStaleToolbar = false)
            // Never spawn blindly from a delayed probe while no native toolbar exists yet (finger still down!)
            if (snap.toolbar != null) {
                launchAsyncSpatialProbe(snap = snap, allowFallbackScheduleOnMiss = false, allowClipboardFallbackOnMiss = false)
            }

            val second = Runnable {
                if (stateMachine.state is PopupState.Visible || clipboardBridge.isProbeOrSettling) return@Runnable
                val snap2 = selectionResolver.captureScreenSnapshot(force = true, allowStaleToolbar = false)
                if (snap2.toolbar != null) {
                    launchAsyncSpatialProbe(snap = snap2, allowFallbackScheduleOnMiss = false, allowClipboardFallbackOnMiss = true)
                }
            }
            pendingProbe2 = second
            handler.postDelayed(second, secondDelayMs)
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

    private fun renderVisibleState(
        visible: PopupState.Visible,
        isNewSession: Boolean,
        preCapturedSnap: ScreenSnapshot?
    ) {
        val snap = preCapturedSnap ?: selectionResolver.captureScreenSnapshot(
            force = isNewSession,
            allowStaleToolbar = !isNewSession
        )
        val freshBounds = lastSelectionBounds?.takeIf { now() - lastSelectionBoundsMs < 2600L }
        val anchorTarget = PopupPositioner.computeAnchorTarget(
            selectionBounds = freshBounds,
            toolbarBounds = snap.toolbar?.bounds,
            viewport = snap.viewport,
            density = selectionResolver.density
        )

        val hiddenIds = PrefsStore.getHiddenToolIds(this)
        val pinnedTools = PrefsStore.getPinnedToolSpecs(this)
        val barOrder = PrefsStore.getBarSlotOrder(this)
        val defaultEngine = PrefsStore.resolveDefaultSearchEngine(this)
        val searchLabel = defaultEngine.glyph.take(7).ifEmpty { "Search" }
        val iconsOnly = PrefsStore.isIconsOnlyMode(this)

        val ctx = ContextActionResolver.analyze(
            rawText = visible.text,
            isEditable = visible.isEditable,
            hasUndo = actionExecutor.hasValidUndo(now())
        )
        val barActions = ContextActionResolver.resolveBarActions(
            ctx = ctx,
            hiddenToolIds = hiddenIds,
            pinnedTools = pinnedTools,
            searchLabel = searchLabel,
            defaultSearchUrl = defaultEngine.customTarget,
            barOrder = barOrder
        )

        val pal = PrefsStore.resolvePalette(this)

        overlayController.showOrUpdate(
            anchorTarget = anchorTarget,
            viewport = snap.viewport,
            mode = visible.mode,
            isNewSession = isNewSession,
            iconsOnly = iconsOnly,
            actions = barActions,
            onActionClick = { act -> actionExecutor.executePopAction(act, visible.text, currentNode) },
            onActionLongClick = { act -> actionExecutor.executePopActionLongPress(act, visible.text, currentNode) },
            onToggleMore = { dispatch(PopupEvent.ToggleMore(now())) },
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
                        override fun onDismiss() = dispatch(PopupEvent.Dismiss)
                        override fun onRerender() {
                            val st = stateMachine.state
                            if (st is PopupState.Visible) renderVisibleState(st, isNewSession = false, preCapturedSnap = null)
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
