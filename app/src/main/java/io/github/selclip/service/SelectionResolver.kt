package io.github.selclip.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.selclip.core.Box
import io.github.selclip.core.SelectionFilter
import io.github.selclip.core.Viewport
import java.util.ArrayDeque
import java.util.PriorityQueue
import kotlin.math.abs

data class ResolvedSelection(
    val text: String,
    val node: AccessibilityNodeInfo?,
    val bounds: Box
)

data class ToolbarHit(
    val bounds: Box,
    val windowId: Int,
    val signature: String,
    val copyButtonNode: AccessibilityNodeInfo? = null
)

data class ScreenSnapshot(
    val viewport: Viewport,
    val toolbar: ToolbarHit?,
    val hasMagnifierOrTransientFloating: Boolean = false
)

/**
 * Cross-app selection, native floating toolbar, and spatially-pruned browser WebView resolver.
 *
 * Key upgrades:
 * - Scans ALL visible TYPE_APPLICATION windows (never tricked when rootInActiveWindow points to
 *   Android's FloatingToolbarPopup window instead of Chrome's webpage window!).
 * - Spatially prioritizes DOM subtrees closest to the floating toolbar's Y position and skips
 *   off-screen header/nav/footer subtrees so large Chrome pages resolve directly from the
 *   accessibility tree with ZERO clipboard pollution.
 * - Wakes Chromium's lazy web accessibility tree on the very first selection.
 */
class SelectionResolver(
    private val service: AccessibilityService,
    private val windowManager: WindowManager
) {

    private var cachedSnapshot: ScreenSnapshot? = null
    private var lastSnapshotTimeMs: Long = 0L
    private var lastToolbar: ToolbarHit? = null
    private var lastToolbarTimeMs: Long = 0L

    private val toolbarKeywordsLower = setOf("copy", "cut", "select all", "share", "web search", "translate")
    private val toolbarIdHints = listOf(
        "android:id/copy",
        "android:id/cut",
        "android:id/selectAll",
        "android:id/shareText",
        "floating_toolbar_menu_item",
        "android:id/floating_toolbar_menu_item_text"
    )
    private val fullScreenToolbarIdHints = listOf(
        "android:id/copy",
        "android:id/cut",
        "android:id/floating_toolbar_menu_item_text",
        "android:id/action_mode_close_button"
    )

    val density: Float get() = service.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()
    private fun Rect.toBox(): Box = Box(left, top, right, bottom)

    fun readRealScreenSize(): Pair<Int, Int> = runCatching {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = windowManager.currentWindowMetrics.bounds
            b.width().coerceAtLeast(320) to b.height().coerceAtLeast(480)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels.coerceAtLeast(320) to dm.heightPixels.coerceAtLeast(480)
        }
    }.getOrElse {
        val dm = service.resources.displayMetrics
        dm.widthPixels.coerceAtLeast(320) to dm.heightPixels.coerceAtLeast(480)
    }

    /**
     * Ultra-lean live window snapshot:
     * - Scans small floating popup windows FIRST via a single-pass ~8-node BFS (~1ms) instead of
     *   12-24 cross-process full-DOM text searches that freeze both Sel and Chrome!
     * - Never scans the same root window twice.
     * - Returns exact live toolbar presence when [allowStaleToolbar] is false so Sel dismisses
     *   in instant lockstep with Android's native toolbar.
     */
    fun captureScreenSnapshot(
        force: Boolean = false,
        allowStaleToolbar: Boolean = false
    ): ScreenSnapshot {
        val now = System.currentTimeMillis()
        cachedSnapshot?.let { snap ->
            if (!force && (now - lastSnapshotTimeMs) < 120L) return snap
        }
        lastSnapshotTimeMs = now

        val (realW, realH) = readRealScreenSize()
        var topInset = dp(36)
        var bottomInset = dp(44)
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val insets = windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                topInset = maxOf(insets.top + dp(4), dp(24))
                bottomInset = maxOf(insets.bottom + dp(8), dp(28))
            }
        }

        var imeTop: Int? = null
        var detectedToolbar: ToolbarHit? = null

        val windows = runCatching { service.windows }.getOrNull()
        if (!windows.isNullOrEmpty()) {
            val floatingCandidates = mutableListOf<Pair<AccessibilityWindowInfo, Rect>>()
            val activeFullCandidates = mutableListOf<AccessibilityWindowInfo>()

            for (w in windows) {
                when (w.type) {
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> {
                        val r = Rect()
                        w.getBoundsInScreen(r)
                        if (r.height() > dp(80) && r.top in (realH / 5)..(realH - dp(60))) {
                            imeTop = imeTop?.let { minOf(it, r.top - dp(6)) } ?: (r.top - dp(6))
                        }
                    }
                    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> Unit
                    else -> {
                        val wr = Rect()
                        w.getBoundsInScreen(wr)
                        val isFullWidth = wr.width() >= (realW * 0.96f).toInt()
                        val isStatusBar = isFullWidth && wr.top <= dp(8) && wr.height() <= dp(72)
                        val isNavBar = isFullWidth && wr.bottom >= realH - dp(8) && wr.height() <= dp(96)
                        if (isStatusBar || isNavBar) continue

                        val isFloating = wr.height() in dp(24)..dp(180) && wr.width() < (realW * 0.97f).toInt()
                        if (isFloating) {
                            floatingCandidates.add(w to wr)
                        } else if (w.isActive || w.isFocused) {
                            activeFullCandidates.add(w)
                        }
                    }
                }
            }

            val viewport = Viewport(realW, realH, topInset, bottomInset, imeTop)
            val visBottom = viewport.visibleBottom
            val scannedWindowIds = HashSet<Int>(4)

            // Pass 1: Floating PopupWindows first (highest layer first) — single fast BFS (~1ms)!
            floatingCandidates.sortByDescending { it.first.layer }
            for ((w, wr) in floatingCandidates) {
                val root = runCatching { w.root }.getOrNull() ?: continue
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg == service.packageName || SelectionFilter.isImePackage(pkg)) continue
                scannedWindowIds.add(w.id)

                val hit = detectToolbarInFloatingWindow(root, w.id, wr, realW, realH, topInset, visBottom)
                if (hit != null) {
                    detectedToolbar = hit
                    break
                }
            }

            // Pass 2: Rare legacy CAB / in-app toolbar inside active full-screen window (viewId-only, ZERO full-DOM text scan!)
            if (detectedToolbar == null) {
                for (w in activeFullCandidates) {
                    if (!scannedWindowIds.add(w.id)) continue
                    val root = runCatching { w.root }.getOrNull() ?: continue
                    val pkg = root.packageName?.toString().orEmpty()
                    if (pkg == service.packageName || SelectionFilter.isImePackage(pkg) || pkg == "com.android.systemui") continue

                    val hit = detectToolbarByViewIdsOnly(root, w.id, realH, visBottom)
                    if (hit != null) {
                        detectedToolbar = hit
                        break
                    }
                }
            }

            if (detectedToolbar != null) {
                lastToolbar = detectedToolbar
                lastToolbarTimeMs = now
            }

            val effectiveToolbar = detectedToolbar
                ?: if (allowStaleToolbar) lastToolbar?.takeIf { now - lastToolbarTimeMs < 350L } else null
            // A small non-toolbar floating window (e.g., Android Text Magnifier loupe) signals
            // the user's finger is still held down selecting text — wait for finger-up!
            val hasMagnifier = detectedToolbar == null && floatingCandidates.isNotEmpty()
            return ScreenSnapshot(viewport, effectiveToolbar, hasMagnifier).also { cachedSnapshot = it }
        }

        val viewport = Viewport(realW, realH, topInset, bottomInset, null)
        return ScreenSnapshot(viewport, null, false).also { cachedSnapshot = it }
    }

    fun findActiveToolbarNow(): ToolbarHit? {
        val snap = captureScreenSnapshot(force = true, allowStaleToolbar = false)
        return snap.toolbar
    }

    fun clearCachedToolbar() {
        lastToolbar = null
        lastToolbarTimeMs = 0L
        cachedSnapshot = null
    }

    /**
     * Single-pass BFS over a small floating PopupWindow (typically 5-15 nodes total).
     * Checks view IDs, known selection menu labels, AND clickable pill geometry in ONE traversal.
     */
    private fun detectToolbarInFloatingWindow(
        root: AccessibilityNodeInfo,
        windowId: Int,
        windowRect: Rect,
        realW: Int,
        realH: Int,
        topInset: Int,
        visBottom: Int
    ): ToolbarHit? {
        val matchedButtons = mutableListOf<Rect>()
        var clickableCount = 0
        var copyBtn: AccessibilityNodeInfo? = null

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0

        while (queue.isNotEmpty() && visited < 40) {
            val (node, depth) = queue.removeFirst()
            visited++
            if (node.isVisibleToUser) {
                val idRes = node.viewIdResourceName.orEmpty()
                val rawLabel = node.text?.toString()?.trim().orEmpty()
                    .ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }
                val labelLower = rawLabel.lowercase()

                val isIdHit = idRes.isNotEmpty() && toolbarIdHints.any { hint ->
                    idRes == hint || idRes.endsWith("/$hint") || idRes.contains("floating_toolbar")
                }
                val isKeywordHit = labelLower.isNotEmpty() && labelLower in toolbarKeywordsLower

                if (isIdHit || isKeywordHit) {
                    val target = clickableAncestor(node) ?: node
                    val r = Rect()
                    target.getBoundsInScreen(r)
                    if (isPlausibleButton(r, realH, visBottom)) {
                        matchedButtons.add(r)
                        if (copyBtn == null && (idRes.endsWith("/copy") || labelLower == "copy")) {
                            copyBtn = target
                        }
                    }
                }
                if (node.isClickable) {
                    clickableCount++
                    if (copyBtn == null && labelLower == "copy") copyBtn = node
                }
            }

            if (depth < 6) {
                val c = minOf(node.childCount, 12)
                for (i in 0 until c) {
                    runCatching { node.getChild(i) }.getOrNull()?.let { queue.add(it to depth + 1) }
                }
            }
        }

        val isPlausibleFloatingPill = windowRect.height() in dp(28)..dp(132) &&
            windowRect.width() in dp(80)..(realW * 0.96f).toInt() &&
            windowRect.top >= (topInset - dp(16)).coerceAtLeast(dp(16)) &&
            windowRect.bottom <= visBottom + dp(12)

        if (matchedButtons.isEmpty() && !(isPlausibleFloatingPill && clickableCount in 2..8)) {
            return null
        }

        val finalBounds = if (windowRect.height() in dp(28)..dp(120) &&
            windowRect.width() in dp(80)..(realW * 0.96f).toInt()
        ) {
            windowRect
        } else if (matchedButtons.isNotEmpty()) {
            unionToolbarRow(matchedButtons) ?: return null
        } else {
            return null
        }

        val q = dp(18).coerceAtLeast(24)
        val sig = "${finalBounds.left / q}_${finalBounds.top / q}_${finalBounds.right / q}_${finalBounds.bottom / q}"
        return ToolbarHit(finalBounds.toBox(), windowId, sig, copyBtn)
    }

    /**
     * Lightweight view-ID-only check for non-floating full-screen windows (e.g. legacy top CAB bar).
     * Never calls findAccessibilityNodeInfosByText() on full webpages so target apps never lag!
     */
    private fun detectToolbarByViewIdsOnly(
        root: AccessibilityNodeInfo,
        windowId: Int,
        realH: Int,
        visBottom: Int
    ): ToolbarHit? {
        val buttons = mutableListOf<Rect>()
        var copyBtn: AccessibilityNodeInfo? = null

        for (idName in fullScreenToolbarIdHints) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(idName) }.getOrNull() ?: continue
            for (n in nodes) {
                if (!n.isVisibleToUser) continue
                val r = Rect()
                n.getBoundsInScreen(r)
                if (isPlausibleButton(r, realH, visBottom)) {
                    buttons.add(r)
                    if (copyBtn == null && (idName.endsWith("/copy") || isCopyLabel(n))) {
                        copyBtn = clickableAncestor(n) ?: n
                    }
                }
            }
            if (buttons.isNotEmpty()) break
        }

        if (buttons.isEmpty()) return null
        val finalBounds = unionToolbarRow(buttons) ?: return null
        val q = dp(18).coerceAtLeast(24)
        val sig = "${finalBounds.left / q}_${finalBounds.top / q}_${finalBounds.right / q}_${finalBounds.bottom / q}"
        return ToolbarHit(finalBounds.toBox(), windowId, sig, copyBtn)
    }

    private fun unionToolbarRow(buttons: List<Rect>): Rect? {
        val anchor = buttons.first()
        val union = Rect(anchor)
        for (other in buttons) {
            if (abs(other.centerY() - anchor.centerY()) <= dp(24)) union.union(other)
        }
        return if (union.height() > dp(112)) null else union
    }

    private fun isCopyLabel(node: AccessibilityNodeInfo): Boolean {
        val t = node.text?.toString()?.trim().orEmpty()
        val d = node.contentDescription?.toString()?.trim().orEmpty()
        return t.equals("Copy", ignoreCase = true) || d.equals("Copy", ignoreCase = true)
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        val p1 = node.parent ?: return null
        if (p1.isClickable) return p1
        val p2 = p1.parent ?: return null
        return if (p2.isClickable) p2 else null
    }

    private fun isPlausibleButton(r: Rect, realH: Int, visBottom: Int): Boolean =
        r.width() in dp(16)..dp(260) &&
            r.height() in dp(16)..dp(96) &&
            r.top >= dp(16) &&
            r.bottom <= visBottom + dp(8) &&
            r.top <= realH - dp(72)

    fun extractSelectionFromEvent(event: AccessibilityEvent): ResolvedSelection? {
        val pkg = event.packageName?.toString().orEmpty()
        val source = event.source
        if (SelectionFilter.isLauncherPackage(pkg) && source?.isFocused != true) return null

        if (source != null) {
            if (source.isPassword) return null
            extractFromNode(source, event.fromIndex, event.toIndex)?.let { return it }
        }

        val eventText = event.text.joinToString("")
        val rangeSub = SelectionFilter.extractRange(eventText, event.fromIndex, event.toIndex)
        if (rangeSub != null) {
            val start = minOf(event.fromIndex, event.toIndex)
            val end = maxOf(event.fromIndex, event.toIndex)
            val bounds = if (source != null) resolveExactBounds(source, eventText, start, end) else fallbackCenterBox()
            return ResolvedSelection(rangeSub, source, bounds)
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED &&
            eventText.isNotBlank() &&
            eventText.length <= 500 &&
            (event.fromIndex < 0 || event.toIndex < 0)
        ) {
            val srcFull = source?.text?.toString().orEmpty()
            if (srcFull.isEmpty() || srcFull != eventText) {
                val clean = SelectionFilter.sanitizeCandidate(eventText.trim(), isPassword = source?.isPassword == true)
                if (clean != null) {
                    val bounds = if (source != null) Rect().also { source.getBoundsInScreen(it) }.toBox() else fallbackCenterBox()
                    return ResolvedSelection(clean, source, bounds)
                }
            }
        }

        val focused = findFocusedNode()
        if (focused != null && focused != source) {
            extractFromNode(focused)?.let { return it }
        }

        if (source != null) {
            findSelectionSpatial(source, toolbarBounds = null, maxNodes = 140, maxDepth = 18)?.let { return it }
        }
        return null
    }

    fun extractFromNode(
        node: AccessibilityNodeInfo,
        evFrom: Int = -1,
        evTo: Int = -1,
        resolveCharBounds: Boolean = true
    ): ResolvedSelection? {
        if (node.isPassword) return null
        val raw = node.text?.toString().orEmpty()
        val hint = node.hintText?.toString()
        if (raw.isEmpty()) return null

        val ns = node.textSelectionStart
        val ne = node.textSelectionEnd
        val nodeSub = SelectionFilter.extractRange(raw, ns, ne, isPassword = false, hintText = hint)
        if (nodeSub != null) {
            val bounds = if (resolveCharBounds) {
                resolveExactBounds(node, raw, minOf(ns, ne), maxOf(ns, ne))
            } else {
                Rect().also { node.getBoundsInScreen(it) }.toBox().takeIf { !it.isEmpty } ?: fallbackCenterBox()
            }
            return ResolvedSelection(nodeSub, node, bounds)
        }

        val evSub = SelectionFilter.extractRange(raw, evFrom, evTo, isPassword = false, hintText = hint)
        if (evSub != null) {
            val bounds = if (resolveCharBounds) {
                resolveExactBounds(node, raw, minOf(evFrom, evTo), maxOf(evFrom, evTo))
            } else {
                Rect().also { node.getBoundsInScreen(it) }.toBox().takeIf { !it.isEmpty } ?: fallbackCenterBox()
            }
            return ResolvedSelection(evSub, node, bounds)
        }
        return null
    }

    /**
     * Scans ALL full-size visible TYPE_APPLICATION windows (never tricked when rootInActiveWindow
     * is the FloatingToolbar window!) using spatial priority around [toolbarBounds].
     *
     * Also wakes up Chromium's lazy WebView accessibility tree by probing its children.
     */
    fun findSelectionAcrossAppWindows(
        preferredRoot: AccessibilityNodeInfo?,
        toolbarBounds: Box?,
        toolbarWindowId: Int = -1
    ): ResolvedSelection? {
        val (_, realH) = readRealScreenSize()
        val roots = mutableListOf<AccessibilityNodeInfo>()

        if (preferredRoot != null && preferredRoot.packageName?.toString() != service.packageName) {
            roots.add(preferredRoot)
        }

        // Collect ALL visible application windows (largest first — Chrome webpage window is full screen!)
        val appWindows = runCatching { service.windows }.getOrNull().orEmpty()
            .filter { w ->
                w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    w.id != toolbarWindowId
            }
            .sortedByDescending { w ->
                val r = Rect()
                w.getBoundsInScreen(r)
                r.width() * r.height()
            }

        for (w in appWindows) {
            val wr = Rect()
            w.getBoundsInScreen(wr)
            // Skip tiny floating popup windows (toolbar itself)
            if (wr.height() < dp(190) && wr.height() < realH / 4) continue
            val rNode = runCatching { w.root }.getOrNull() ?: continue
            val pkg = rNode.packageName?.toString().orEmpty()
            if (pkg == service.packageName || SelectionFilter.isImePackage(pkg) || pkg == "com.android.systemui") continue
            if (roots.none { it == rNode }) roots.add(rNode)
        }

        val activeRoot = runCatching { service.rootInActiveWindow }.getOrNull()
        if (activeRoot != null &&
            activeRoot.packageName?.toString() != service.packageName &&
            roots.none { it == activeRoot }
        ) {
            roots.add(activeRoot)
        }

        for (scanRoot in roots) {
            findSelectionSpatial(scanRoot, toolbarBounds, maxNodes = 550, maxDepth = 28)?.let { return it }
        }
        return null
    }

    private data class QueuedNode(
        val node: AccessibilityNodeInfo,
        val depth: Int,
        val distToToolbar: Int
    )

    /**
     * Spatially-prioritized traversal:
     * - Skips completely off-screen DOM subtrees (`bottom <= 0` or `top >= screenH`).
     * - Visits subtrees closest to `toolbarBounds` first so 3,000-node Chrome pages hit the
     *   selected article paragraph in ~20 nodes instead of burning budget in `<nav>` / `<header>`.
     * - Handles selections spanning multiple sibling leaf nodes.
     */
    private fun findSelectionSpatial(
        root: AccessibilityNodeInfo,
        toolbarBounds: Box?,
        maxNodes: Int,
        maxDepth: Int
    ): ResolvedSelection? {
        val (_, realH) = readRealScreenSize()
        val targetY = toolbarBounds?.bottom?.plus(dp(36)) ?: (realH / 2)

        val pq = PriorityQueue<QueuedNode>(compareBy<QueuedNode> { it.distToToolbar }.thenBy { it.depth })
        pq.add(QueuedNode(root, 0, 0))

        val hits = mutableListOf<ResolvedSelection>()
        var visited = 0
        val tmpRect = Rect()

        while (pq.isNotEmpty() && visited < maxNodes) {
            val cur = pq.poll() ?: break
            val node = cur.node
            visited++

            if (isBrowserSurfaceNode(node)) {
                runCatching { node.refresh() }
            }

            if (node.isVisibleToUser) {
                // Skip expensive refreshWithExtraData char-bounds IPC when we're already docking to toolbarBounds!
                val hit = extractFromNode(node, resolveCharBounds = toolbarBounds == null)
                if (hit != null) {
                    hits.add(hit)
                    // Single non-browser selection returns immediately; browser might span 2-3 inline spans
                    if (hits.size >= 4) break
                }
            }

            if (cur.depth < maxDepth) {
                val childCount = minOf(node.childCount, 120)
                for (i in 0 until childCount) {
                    val ch = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    ch.getBoundsInScreen(tmpRect)
                    val hasBounds = tmpRect.width() > 0 && tmpRect.height() > 0
                    // Prune strictly off-screen DOM subtrees (huge speedup on Chrome/news sites!)
                    if (hasBounds && (tmpRect.bottom <= 0 || tmpRect.top >= realH)) continue
                    if (!ch.isVisibleToUser && ch.childCount == 0) continue

                    val dist = when {
                        !hasBounds -> dp(120)
                        targetY in tmpRect.top..tmpRect.bottom -> 0
                        else -> minOf(abs(tmpRect.top - targetY), abs(tmpRect.bottom - targetY))
                    }
                    // Boost WebView containers so Chromium's virtual tree is always explored early!
                    val priority = if (isBrowserSurfaceNode(ch)) 0 else dist
                    pq.add(QueuedNode(ch, cur.depth + 1, priority))
                }
            }
        }

        if (hits.isEmpty()) return null
        if (hits.size == 1) return hits.first()

        val sorted = hits.sortedWith(compareBy<ResolvedSelection> { it.bounds.top / dp(16).coerceAtLeast(1) }.thenBy { it.bounds.left })
        val combinedText = sorted.map { it.text }.distinct().joinToString(" ").trim()
        val union = Rect(sorted.first().bounds.left, sorted.first().bounds.top, sorted.first().bounds.right, sorted.first().bounds.bottom)
        sorted.drop(1).forEach { union.union(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom) }
        return ResolvedSelection(combinedText, sorted.first().node, union.toBox())
    }

    fun findFocusedNode(): AccessibilityNodeInfo? {
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null
        val inputFocused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (inputFocused != null && (inputFocused.isEditable || inputFocused.textSelectionStart != inputFocused.textSelectionEnd)) {
            return inputFocused
        }
        val a11yFocused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) }.getOrNull()
        if (a11yFocused != null && (a11yFocused.isEditable || a11yFocused.textSelectionStart != a11yFocused.textSelectionEnd)) {
            return a11yFocused
        }
        return null
    }

    /**
     * Last-resort copy trigger used ONLY when spatial accessibility traversal finds no selection
     * indices (e.g., Firefox GeckoView or non-accessible canvases).
     */
    fun performBestCopyAction(
        sourceNode: AccessibilityNodeInfo?,
        toolbarHit: ToolbarHit?
    ): Boolean {
        if (sourceNode != null && (supportsAction(sourceNode, AccessibilityNodeInfo.ACTION_COPY) || isBrowserSurfaceNode(sourceNode))) {
            if (runCatching { sourceNode.performAction(AccessibilityNodeInfo.ACTION_COPY) }.getOrDefault(false)) return true
        }

        val root = runCatching { service.rootInActiveWindow }.getOrNull()
        val focused = runCatching { root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            ?: runCatching { root?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) }.getOrNull()
        if (focused != null && (supportsAction(focused, AccessibilityNodeInfo.ACTION_COPY) || isBrowserSurfaceNode(focused))) {
            if (runCatching { focused.performAction(AccessibilityNodeInfo.ACTION_COPY) }.getOrDefault(false)) return true
        }

        val roots = mutableListOf<AccessibilityNodeInfo>()
        runCatching { service.windows }.getOrNull()?.forEach { w ->
            if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION && w.id != toolbarHit?.windowId) {
                runCatching { w.root }.getOrNull()?.let { wr ->
                    if (wr.packageName?.toString() != service.packageName && roots.none { it == wr }) roots.add(wr)
                }
            }
        }
        if (root != null && roots.none { it == root }) roots.add(root)

        val webViewFallbacks = mutableListOf<AccessibilityNodeInfo>()
        for (scanRoot in roots) {
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            queue.add(scanRoot to 0)
            var visited = 0
            while (queue.isNotEmpty() && visited < 260) {
                val (node, depth) = queue.removeFirst()
                visited++
                if (node.isVisibleToUser) {
                    if (supportsAction(node, AccessibilityNodeInfo.ACTION_COPY)) {
                        if (runCatching { node.performAction(AccessibilityNodeInfo.ACTION_COPY) }.getOrDefault(false)) {
                            return true
                        }
                    } else if (isBrowserSurfaceNode(node)) {
                        webViewFallbacks.add(node)
                    }
                }
                if (depth < 24) {
                    val count = minOf(node.childCount, 28)
                    for (i in 0 until count) {
                        runCatching { node.getChild(i) }.getOrNull()?.let { queue.add(it to depth + 1) }
                    }
                }
            }
        }

        for (wv in webViewFallbacks) {
            if (runCatching { wv.performAction(AccessibilityNodeInfo.ACTION_COPY) }.getOrDefault(false)) return true
        }

        val copyBtn = toolbarHit?.copyButtonNode
        if (copyBtn != null) {
            if (runCatching { copyBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) return true
        }
        return false
    }

    private fun isBrowserSurfaceNode(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString().orEmpty()
        return cls.contains("WebView", ignoreCase = true) ||
            cls.contains("GeckoView", ignoreCase = true) ||
            cls.contains("Chromium", ignoreCase = true) ||
            cls.contains("Compositor", ignoreCase = true)
    }

    private fun supportsAction(node: AccessibilityNodeInfo, actionId: Int): Boolean {
        val actions = runCatching { node.actionList }.getOrNull() ?: return false
        return actions.any { it.id == actionId }
    }

    private fun resolveExactBounds(node: AccessibilityNodeInfo, raw: String, start: Int, end: Int): Box {
        val (realW, realH) = readRealScreenSize()
        val exact = queryCharacterRangeRect(node, start, (end - start).coerceIn(1, 200))
        if (exact != null && exact.width() > 2 && exact.height() > 2 && exact.bottom > 0 && exact.top < realH) {
            return exact.toBox()
        }

        val nodeRect = Rect()
        node.getBoundsInScreen(nodeRect)
        if (nodeRect.width() <= 0 || nodeRect.height() <= 0) return fallbackCenterBox()

        if (nodeRect.height() in dp(84)..(realH * 0.45f).toInt() && raw.isNotEmpty()) {
            val avgChars = (nodeRect.width() / dp(7).coerceAtLeast(1)).coerceIn(22, 72)
            var curLine = 0
            var startLine = 0
            var endLine = 0
            var col = 0
            val limit = minOf(raw.length, 4000)
            for (i in 0 until limit) {
                if (i == start) startLine = curLine
                if (i == end) endLine = curLine
                if (raw[i] == '\n') {
                    curLine++
                    col = 0
                } else {
                    col++
                    if (col >= avgChars) {
                        curLine++
                        col = 0
                    }
                }
            }
            if (end >= limit) endLine = curLine
            val totalLines = (curLine + 1).coerceAtLeast(1)
            val estLineH = (nodeRect.height().toFloat() / totalLines).coerceIn(dp(16).toFloat(), dp(25).toFloat())
            val maxTop = (nodeRect.bottom - dp(28)).coerceAtLeast(nodeRect.top)
            val bandTop = (nodeRect.top + startLine * estLineH).toInt().coerceIn(nodeRect.top, maxTop)
            val maxBottom = maxOf(bandTop + dp(24), nodeRect.bottom)
            val bandBottom = (nodeRect.top + (endLine + 1) * estLineH).toInt().coerceIn(bandTop + dp(24), maxBottom)
            return Box(nodeRect.left, bandTop, nodeRect.right, bandBottom)
        }

        return nodeRect.toBox()
    }

    private fun queryCharacterRangeRect(node: AccessibilityNodeInfo, startIndex: Int, length: Int): Rect? {
        if (length <= 0) return null
        return runCatching {
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, startIndex)
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length)
            }
            if (!node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)) {
                return@runCatching null
            }
            @Suppress("DEPRECATION")
            val locs = node.extras?.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
                ?: return@runCatching null
            if (locs.isEmpty()) return@runCatching null

            var minL = Float.MAX_VALUE
            var minT = Float.MAX_VALUE
            var maxR = -Float.MAX_VALUE
            var maxB = -Float.MAX_VALUE
            var count = 0
            for (p in locs) {
                val rf = p as? RectF ?: continue
                if (rf.width() <= 0f || rf.height() <= 0f) continue
                if (rf.left < minL) minL = rf.left
                if (rf.top < minT) minT = rf.top
                if (rf.right > maxR) maxR = rf.right
                if (rf.bottom > maxB) maxB = rf.bottom
                count++
            }
            if (count > 0) Rect(minL.toInt(), minT.toInt(), maxR.toInt(), maxB.toInt()) else null
        }.getOrNull()
    }

    private fun fallbackCenterBox(): Box {
        val (realW, realH) = readRealScreenSize()
        return Box(dp(24), realH / 3, realW - dp(24), realH / 3 + dp(44))
    }
}
