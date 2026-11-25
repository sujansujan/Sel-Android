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
    val toolbar: ToolbarHit?
)

/**
 * Cross-app selection, native floating toolbar, and browser WebView/GeckoView resolver.
 *
 * Key browser fixes:
 * - Inspects ALL floating windows (including TYPE_SYSTEM and pkg="android", filtering out status/nav
 *   bars by geometry) because Android's FloatingToolbarPopup in Chrome/Brave/Edge/Firefox often
 *   reports TYPE_SYSTEM or pkg="android".
 * - Deep WebView/Chromium/GeckoView ACTION_COPY trigger (copies without closing the native toolbar!).
 * - Safe single-shot Copy-button click fallback only for browsers that reject ACTION_COPY entirely.
 */
class SelectionResolver(
    private val service: AccessibilityService,
    private val windowManager: WindowManager
) {

    private var cachedSnapshot: ScreenSnapshot? = null
    private var lastSnapshotTimeMs: Long = 0L
    private var lastToolbar: ToolbarHit? = null
    private var lastToolbarTimeMs: Long = 0L

    private val toolbarKeywords = listOf("Copy", "Cut", "Select all", "Share", "Web search", "Translate")
    private val toolbarIdHints = listOf(
        "android:id/copy",
        "android:id/cut",
        "android:id/selectAll",
        "android:id/shareText",
        "floating_toolbar_menu_item",
        "android:id/floating_toolbar_menu_item_text"
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

    fun captureScreenSnapshot(force: Boolean = false): ScreenSnapshot {
        val now = System.currentTimeMillis()
        cachedSnapshot?.let { snap ->
            if (!force && (now - lastSnapshotTimeMs) < 260L) return snap
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
            // Pass 1: Detect live IME height
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    val r = Rect()
                    w.getBoundsInScreen(r)
                    if (r.height() > dp(80) && r.top in (realH / 5)..(realH - dp(60))) {
                        imeTop = imeTop?.let { minOf(it, r.top - dp(6)) } ?: (r.top - dp(6))
                    }
                }
            }

            val viewport = Viewport(realW, realH, topInset, bottomInset, imeTop)
            val visBottom = viewport.visibleBottom

            // Pass 2: Prioritize small floating windows (Android FloatingToolbarPopup in Chrome/WebViews/Compose!)
            val ordered = windows.sortedWith(
                compareByDescending<AccessibilityWindowInfo> {
                    val r = Rect()
                    it.getBoundsInScreen(r)
                    r.height() in dp(24)..dp(160) && r.width() < (realW * 0.97f).toInt()
                }.thenByDescending { it.layer }
            )

            for (w in ordered) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                ) continue

                val wr = Rect()
                w.getBoundsInScreen(wr)

                // Skip status bar & navigation bar strictly by geometry (never skip system FloatingToolbar!)
                val isFullWidth = wr.width() >= (realW * 0.96f).toInt()
                val isStatusBar = isFullWidth && wr.top <= dp(8) && wr.height() <= dp(72)
                val isNavBar = isFullWidth && wr.bottom >= realH - dp(8) && wr.height() <= dp(96)
                if (isStatusBar || isNavBar) continue

                val root = runCatching { w.root }.getOrNull() ?: continue
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg == service.packageName || SelectionFilter.isImePackage(pkg)) continue

                // Skip SystemUI notification shade / status bar, but ALLOW pkg == "android" & browser windows!
                if (pkg == "com.android.systemui" && isFullWidth) continue

                val isFloating = wr.height() in dp(24)..dp(180) && wr.width() < (realW * 0.97f).toInt()
                if (isFloating || w.isActive || w.isFocused) {
                    val hit = detectToolbarInRoot(root, w.id, isFloating, realW, realH, topInset, visBottom)
                    if (hit != null) {
                        detectedToolbar = hit
                        break
                    }
                }
            }

            if (detectedToolbar == null) {
                val activeRoot = runCatching { service.rootInActiveWindow }.getOrNull()
                if (activeRoot != null) {
                    val pkg = activeRoot.packageName?.toString().orEmpty()
                    if (pkg != service.packageName && !SelectionFilter.isImePackage(pkg) && pkg != "com.android.systemui") {
                        detectedToolbar = detectToolbarInRoot(activeRoot, -1, false, realW, realH, topInset, visBottom)
                    }
                }
            }

            if (detectedToolbar != null) {
                lastToolbar = detectedToolbar
                lastToolbarTimeMs = now
            }

            val effectiveToolbar = detectedToolbar ?: lastToolbar?.takeIf { now - lastToolbarTimeMs < 1100L }
            return ScreenSnapshot(viewport, effectiveToolbar).also { cachedSnapshot = it }
        }

        val viewport = Viewport(realW, realH, topInset, bottomInset, null)
        return ScreenSnapshot(viewport, null).also { cachedSnapshot = it }
    }

    fun findActiveToolbarNow(): ToolbarHit? {
        val snap = captureScreenSnapshot(force = true)
        return snap.toolbar?.takeIf { System.currentTimeMillis() - lastToolbarTimeMs < 450L }
    }

    fun clearCachedToolbar() {
        lastToolbar = null
        lastToolbarTimeMs = 0L
    }

    private fun detectToolbarInRoot(
        root: AccessibilityNodeInfo,
        windowId: Int,
        isFloatingWindow: Boolean,
        realW: Int,
        realH: Int,
        topInset: Int,
        visBottom: Int
    ): ToolbarHit? {
        val buttons = mutableListOf<Rect>()
        var copyBtn: AccessibilityNodeInfo? = null

        // Check 1: Standard Android FloatingToolbar view IDs (works in every locale!)
        for (idName in toolbarIdHints) {
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
        }

        // Check 2: Keyword match for Copy / Cut / Select all / Share / Web search / Translate
        if (buttons.isEmpty()) {
            for (kw in toolbarKeywords) {
                val matches = runCatching { root.findAccessibilityNodeInfosByText(kw) }.getOrNull() ?: continue
                for (m in matches) {
                    if (!m.isVisibleToUser) continue
                    val label = m.text?.toString()?.trim().orEmpty()
                        .ifEmpty { m.contentDescription?.toString()?.trim().orEmpty() }
                    if (!label.equals(kw, ignoreCase = true)) continue
                    val clickable = clickableAncestor(m) ?: continue
                    val r = Rect()
                    clickable.getBoundsInScreen(r)
                    if (isPlausibleButton(r, realH, visBottom)) {
                        buttons.add(r)
                        if (copyBtn == null && kw.equals("Copy", ignoreCase = true)) {
                            copyBtn = clickable
                        }
                    }
                }
                if (buttons.isNotEmpty()) break
            }
        }

        // Check 3: Small floating popup window heuristic (non-English locales, custom Chrome/OEM toolbars)
        if (buttons.isEmpty() && isFloatingWindow) {
            val wr = Rect()
            root.getBoundsInScreen(wr)
            if (wr.height() in dp(28)..dp(132) &&
                wr.width() in dp(80)..(realW * 0.96f).toInt() &&
                wr.top >= (topInset - dp(16)).coerceAtLeast(dp(16)) &&
                wr.bottom <= visBottom + dp(12)
            ) {
                val (clickableCount, foundCopy) = scanFloatingToolbarWindow(root)
                if (clickableCount in 2..8) {
                    buttons.add(wr)
                    if (copyBtn == null) copyBtn = foundCopy
                }
            }
        }

        if (buttons.isEmpty()) return null

        // If this is a dedicated small floating window, use its crisp outer bounds so Sel docks flush below it!
        val finalBounds = if (isFloatingWindow) {
            val wr = Rect()
            root.getBoundsInScreen(wr)
            if (wr.height() in dp(28)..dp(120) && wr.width() in dp(80)..(realW * 0.96f).toInt()) {
                wr
            } else {
                unionToolbarRow(buttons) ?: return null
            }
        } else {
            unionToolbarRow(buttons) ?: return null
        }

        val sig = "${windowId}_${finalBounds.left / 16}_${finalBounds.top / 16}_${finalBounds.right / 16}_${finalBounds.bottom / 16}"
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

    private fun scanFloatingToolbarWindow(root: AccessibilityNodeInfo): Pair<Int, AccessibilityNodeInfo?> {
        var found = 0
        var copyNode: AccessibilityNodeInfo? = null
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < 36) {
            val (node, depth) = queue.removeFirst()
            visited++
            if (node.isVisibleToUser && node.isClickable) {
                found++
                if (copyNode == null && isCopyLabel(node)) copyNode = node
            } else if (copyNode == null && node.isVisibleToUser && isCopyLabel(node)) {
                copyNode = clickableAncestor(node)
            }
            if (depth < 5) {
                val c = minOf(node.childCount, 10)
                for (i in 0 until c) {
                    runCatching { node.getChild(i) }.getOrNull()?.let { queue.add(it to depth + 1) }
                }
            }
        }
        return found to copyNode
    }

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

        // Chromium / WebView quirk: event.text carries the selected snippet with fromIndex/toIndex = -1
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
            findSelectionInTreeBfs(source, maxNodes = 100, maxDepth = 16)?.let { return it }
        }
        return null
    }

    fun extractFromNode(
        node: AccessibilityNodeInfo,
        evFrom: Int = -1,
        evTo: Int = -1
    ): ResolvedSelection? {
        if (node.isPassword) return null
        val raw = node.text?.toString().orEmpty()
        val hint = node.hintText?.toString()
        if (raw.isEmpty()) return null

        val ns = node.textSelectionStart
        val ne = node.textSelectionEnd
        val nodeSub = SelectionFilter.extractRange(raw, ns, ne, isPassword = false, hintText = hint)
        if (nodeSub != null) {
            val bounds = resolveExactBounds(node, raw, minOf(ns, ne), maxOf(ns, ne))
            return ResolvedSelection(nodeSub, node, bounds)
        }

        val evSub = SelectionFilter.extractRange(raw, evFrom, evTo, isPassword = false, hintText = hint)
        if (evSub != null) {
            val bounds = resolveExactBounds(node, raw, minOf(evFrom, evTo), maxOf(evFrom, evTo))
            return ResolvedSelection(evSub, node, bounds)
        }
        return null
    }

    fun findSelectionInTreeBfs(
        root: AccessibilityNodeInfo?,
        maxNodes: Int = 220,
        maxDepth: Int = 22
    ): ResolvedSelection? {
        if (root == null) return null
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val (node, depth) = queue.removeFirst()
            visited++
            if (node.isVisibleToUser) {
                extractFromNode(node)?.let { return it }
            }
            if (depth < maxDepth) {
                val count = minOf(node.childCount, 28)
                for (i in 0 until count) {
                    val ch = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    if (ch.isVisibleToUser || ch.childCount > 0) queue.add(ch to depth + 1)
                }
            }
        }
        return null
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
     * Multi-tier universal copy extraction for browsers (Chrome, Brave, Edge, Firefox, Samsung
     * Internet, WebViews) and Jetpack Compose:
     * 1. ACTION_COPY on source / focused node (silent — keeps native toolbar open!).
     * 2. Deep BFS for any node advertising ACTION_COPY OR any WebView/GeckoView/Chromium container
     *    (many browsers accept ACTION_COPY on their WebView even when omitted from actionList!).
     * 3. Last-resort click on the detected toolbar's Copy button if ACTION_COPY is unsupported.
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
        if (root != null) roots.add(root)
        runCatching { service.windows }.getOrNull()?.forEach { w ->
            if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION && (w.isActive || w.isFocused)) {
                runCatching { w.root }.getOrNull()?.let { wr ->
                    if (wr != root && wr.packageName?.toString() != service.packageName) roots.add(wr)
                }
            }
        }

        val webViewFallbacks = mutableListOf<AccessibilityNodeInfo>()
        for (scanRoot in roots) {
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            queue.add(scanRoot to 0)
            var visited = 0
            while (queue.isNotEmpty() && visited < 240) {
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
                    val count = minOf(node.childCount, 24)
                    for (i in 0 until count) {
                        runCatching { node.getChild(i) }.getOrNull()?.let { queue.add(it to depth + 1) }
                    }
                }
            }
        }

        // Try direct ACTION_COPY on discovered WebView / GeckoView / Chromium containers
        for (wv in webViewFallbacks) {
            if (runCatching { wv.performAction(AccessibilityNodeInfo.ACTION_COPY) }.getOrDefault(false)) {
                return true
            }
        }

        // Last resort (e.g., Firefox GeckoView / hardened browsers): tap detected Copy toolbar button once
        val copyBtn = toolbarHit?.copyButtonNode
        if (copyBtn != null) {
            if (runCatching { copyBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
                return true
            }
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
