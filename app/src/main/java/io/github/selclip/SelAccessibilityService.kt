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
import io.github.selclip.service.ScreenSnapshot
import io.github.selclip.service.SelectionResolver
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Lean, strictly event-driven AccessibilityService with ZERO background clipboard access:
 *
 * - NEVER reads or writes the system clipboard during selection detection -> 0% clipboard
 *   pollution, 0% "Sel pasted from clipboard" toasts, 0% Chrome toolbar blinking!
 * - Lockstep native-toolbar binding: buffers direct selections while the finger/Magnifier is
 *   down and spawns ONCE in 0ms the instant Android's native toolbar opens.
 * - Rock-solid standalone mode for `ACTION_PROCESS_TEXT` (e.g. static webpages in Chrome/Firefox):
 *   immune to `ToolbarGone` blinking and dismissed instantaneously by touching anywhere outside.
 */
class SelAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: SelAccessibilityService? = null

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

        actionExecutor = ActionExecutor(
            context = this,
            clipboardManager = clipboardManager,
            onSwitchMode = { mode -> dispatch(PopupEvent.SwitchMode(mode, now())) },
            onDismiss = { dispatch(PopupEvent.Dismiss) }
        )

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

    /**
     * Entry point from `ACTION_PROCESS_TEXT` ("Sel" in Chrome/Firefox/OS selection menu).
     * Uses a 30ms settle so `ProcessTextActivity.finish()` completes first, spawns in standalone
     * mode (immune to `ToolbarGone` blinking) at the cached toolbar location, and attaches a
     * full-screen transparent touch-dismiss layer so tapping anywhere outside dismisses it.
     */
    fun showFromExternalText(
        text: String,
        openExpanded: Boolean = false,
        isStandalone: Boolean = true
    ) {
        val delayMs = if (isStandalone) 30L else 0L
        handler.postDelayed({
            cancelPendingDirectSelection()
            cancelWindowsCheck()
            cancelProbes()
            val clean = SelectionFilter.sanitizeCandidate(text) ?: return@postDelayed
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
            // Reuse recently cached native toolbar coordinates so Sel spawns right where Chrome's popup was!
            val snap = selectionResolver.captureScreenSnapshot(force = true, allowStaleToolbar = true)
            dispatch(
                event = PopupEvent.SelectionDetected(
                    text = clean,
                    sourcePackage = activePackage,
                    isEditable = currentNode?.isEditable == true,
                    toolbarSig = snap.toolbar?.signature.orEmpty(),
                    openExpanded = openExpanded,
                    isStandalone = isStandalone,
                    nowMs = now()
                ),
                preCapturedSnap = snap
            )
        }, delayMs)
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
        // Never treat a transient PopupWindow (Cut/Copy/Share) inside a launcher search bar as Home!
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
                    // Pre-wake GeckoView's lazy accessibility tree on browser entry so selection #1 works!
                    if (prev != pkg && SelectionFilter.isGeckoBrowserPackage(pkg)) {
                        handler.postDelayed({
                            if (activePackage == pkg) {
                                getOrCreateScanExecutor().execute {
                                    runCatching { selectionResolver.warmGeckoAccessibilityTree() }
                                }
                            }
                        }, 320L)
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

            // Long-press fires while user's finger is still down (Magnifier active).
            // Reset memory and remember source node ONLY — never spawn blindly before finger-up!
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                if (pkg != packageName && !SelectionFilter.isSystemUiOrFramework(pkg) && !SelectionFilter.isImePackage(pkg)) {
                    stateMachine.resetToolbarMemory()
                    pendingProbeSource = event.source
                    pendingProbePkg = activePackage.ifEmpty { pkg }
                    pendingProbeSig = ""
                }
            }

            // Ignore self-triggered window add/update echoes (~16-50ms) so Frame 1 NEVER stalls!
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (overlayController.isRecentSelfWindowMutation(90L)) return
                if (activePackage.isNotEmpty() && PrefsStore.isAppMuted(this, activePackage)) return
                syncWithNativeToolbarNow(scheduleFollowUpIfMissing = true)
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (overlayController.isRecentSelfWindowMutation(90L)) return
                if (pkg != packageName &&
                    !SelectionFilter.isImePackage(pkg) &&
                    SelectionFilter.isTransientPopupClass(cls)
                ) {
                    syncWithNativeToolbarNow(scheduleFollowUpIfMissing = true)
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (overlayController.isRecentSelfWindowMutation(180L)) return
                val cur = stateMachine.state
                if (cur is PopupState.Visible && (cur.isStandalone || cur.mode != PopupMode.Bar)) return
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
     * - Native toolbar appeared (e.g. WebView/browser input) -> async off-main-thread tree scan!
     * - Native toolbar moved -> smoothly slides Sel underneath it (ignoring micro-wobble)!
     * - Native toolbar vanished -> instant lockstep dismissal (never kills standalone ProcessText)!
     */
    private fun syncWithNativeToolbarNow(scheduleFollowUpIfMissing: Boolean) {
        val cur = stateMachine.state
        if (cur is PopupState.Visible && cur.isStandalone) return

        val snap = selectionResolver.captureScreenSnapshot(force = true, allowStaleToolbar = false)
        val toolbar = snap.toolbar

        if (toolbar == null) {
            if (cur is PopupState.Visible || cur is PopupState.PendingProbe) {
                scanGeneration.incrementAndGet()
                dispatch(PopupEvent.ToolbarGone(now()))
            }
            if (scheduleFollowUpIfMissing && stateMachine.state !is PopupState.Visible) {
                scheduleShortTrailingCheck()
            }
            return
        }

        cancelWindowsCheck()

        // Lockstep Fast-Path: consume buffered direct selection in 0ms on the exact frame toolbar opens!
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
                    isStandalone = false,
                    nowMs = now()
                ),
                preCapturedSnap = snap
            )
            return
        }

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

        // Pure accessibility-only spatial scan off the main thread (ZERO clipboard reads/writes!)
        pendingProbeSource = null
        pendingProbePkg = activePackage
        pendingProbeSig = toolbar.signature
        launchAsyncSpatialProbe(snap = snap, allowRetryScheduleOnMiss = true)
    }

    private fun scheduleShortTrailingCheck() {
        cancelWindowsCheck()
        val r = Runnable {
            pendingWindowsCheck = null
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
                    isStandalone = false,
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

            if (snap.toolbar != null || stateMachine.state is PopupState.Visible) {
                cancelPendingDirectSelection()
                dispatch(
                    event = PopupEvent.SelectionDetected(
                        text = resolved.text,
                        sourcePackage = effPkg,
                        isEditable = isEditable,
                        toolbarSig = snap.toolbar?.signature.orEmpty(),
                        isStandalone = false,
                        nowMs = now()
                    ),
                    preCapturedSnap = snap
                )
                return
            }

            cancelPendingDirectSelection()
            pendingDirectSelection = PendingDirectSelection(
                text = resolved.text,
                sourcePackage = effPkg,
                isEditable = isEditable,
                node = resolved.node,
                bounds = resolved.bounds,
                capturedAtMs = now()
            )
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
        pendingProbeSource = source
        pendingProbePkg = pkg
        pendingProbeSig = toolbarSig
        dispatch(PopupEvent.ProbeRequested(pkg, toolbarSig, now()))
    }

    /**
     * Pure accessibility spatial DOM scan on a single lazy daemon thread.
     * NEVER touches the clipboard and NEVER clicks any button in the target app!
     */
    private fun launchAsyncSpatialProbe(
        snap: ScreenSnapshot,
        allowRetryScheduleOnMiss: Boolean
    ) {
        if (stateMachine.state is PopupState.Visible) return

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
                            isStandalone = false,
                            nowMs = now()
                        ),
                        preCapturedSnap = snap
                    )
                    return@post
                }

                if (allowRetryScheduleOnMiss) {
                    requestProbe(preferredRoot, pkgSnapshot, sigSnapshot)
                }
            }
        }
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
                    overlayController.hide()
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
            if (stateMachine.state is PopupState.Visible) return@Runnable
            val snap = selectionResolver.captureScreenSnapshot(force = true, allowStaleToolbar = false)
            if (snap.toolbar != null) {
                launchAsyncSpatialProbe(snap = snap, allowRetryScheduleOnMiss = false)
            }

            // One delayed retry for lazy WebView accessibility trees waking up (~160ms) — still ZERO clipboard!
            val second = Runnable {
                if (stateMachine.state is PopupState.Visible) return@Runnable
                val snap2 = selectionResolver.captureScreenSnapshot(force = true, allowStaleToolbar = false)
                if (snap2.toolbar != null) {
                    launchAsyncSpatialProbe(snap = snap2, allowRetryScheduleOnMiss = false)
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
            allowStaleToolbar = visible.isStandalone || !isNewSession
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
            isStandalone = visible.isStandalone,
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
