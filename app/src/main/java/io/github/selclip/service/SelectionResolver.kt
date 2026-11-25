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
    val signature: String
)

data class ScreenSnapshot(
    val viewport: Viewport,
    val toolbar: ToolbarHit?
)

/**
 * Lean cross-app selection, floating toolbar, and viewport/IME resolver.
 * Uses bounded BFS and fast-path view IDs to keep traversal cost minimal.
 */
class SelectionResolver(
    private val service: AccessibilityService,
    private val windowManager: WindowManager
) {

    private var cachedSnapshot: ScreenSnapshot? = null
    private var lastSnapshotTimeMs: Long = 0L
    private var lastToolbar: ToolbarHit? = null
    private var lastToolbarTimeMs: Long = 0L

    private val toolbarKeywords = listOf("Copy", "Cut", "Select all", "Share")
    private val toolbarIdHints = listOf(
        "android:id/copy",
        "android:id/cut",
        "android:id/selectAll",
        "floating_toolbar_menu_item"
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
            if (!force && (now - lastSnapshotTimeMs) < 300L) return snap
        }
        lastSnapshotTimeMs = now

        val (realW, realH) = readRealScreenSize()
        var topInset = dp(40)
        var bottomInset = dp(48)
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val insets = windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                topInset = maxOf(insets.top + dp(6), dp(28))
                bottomInset = maxOf(insets.bottom + dp(10), dp(32))
            }
        }

        var imeTop: Int? = null
        var detectedToolbar: ToolbarHit? = null

        val windows = runCatching { service.windows }.getOrNull()
        if (!windows.isNullOrEmpty()) {
            // Pass 1: Detect live IME top so popup never collides with keyboard
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    val r = Rect()
                    w.getBoundsInScreen(r)
                    if (r.height() > dp(80) && r.top in (realH / 5)..(realH - dp(60))) {
                        imeTop = imeTop?.let { minOf(it, r.top - dp(8)) } ?: (r.top - dp(8))
                    }
                }
            }

            val viewport = Viewport(realW, realH, topInset, bottomInset, imeTop)
            val visBottom = viewport.visibleBottom

            // Pass 2: Check small floating windows first (ActionMode / FloatingToolbar / Compose)
            val ordered = windows.sortedWith(
                compareByDescending<AccessibilityWindowInfo> {
                    val r = Rect()
                    it.getBoundsInScreen(r)
                    r.height() in dp(24)..dp(160)
                }.thenByDescending { it.layer }
            )

            for (w in ordered) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    w.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                    w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                ) continue

                val root = runCatching { w.root }.getOrNull() ?: continue
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg == service.packageName || SelectionFilter.isSystemUiOrFramework(pkg) || SelectionFilter.isImePackage(pkg)) {
                    continue
                }

                val wr = Rect()
                w.getBoundsInScreen(wr)
                val isFloating = wr.height() in dp(24)..dp(180) && wr.width() < (realW * 0.98f).toInt()
                if (isFloating || w.isActive || w.isFocused) {
                    val hit = detectToolbarInRoot(root, w.id, isFloating, realH, visBottom)
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
                    if (pkg != service.packageName && !SelectionFilter.isImePackage(pkg) && !SelectionFilter.isSystemUiOrFramework(pkg)) {
                        detectedToolbar = detectToolbarInRoot(activeRoot, -1, false, realH, visBottom)
                    }
                }
            }

            if (detectedToolbar != null) {
                lastToolbar = detectedToolbar
                lastToolbarTimeMs = now
            }

            val effectiveToolbar = detectedToolbar ?: lastToolbar?.takeIf { now - lastToolbarTimeMs < 1200L }
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
        realH: Int,
        visBottom: Int
    ): ToolbarHit? {
        val buttons = mutableListOf<Rect>()

        // Fast check 1: Standard Android floating toolbar view IDs (locale-independent)
        for (idName in toolbarIdHints) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(idName) }.getOrNull() ?: continue
            for (n in nodes) {
                if (!n.isVisibleToUser) continue
                val r = Rect()
                n.getBoundsInScreen(r)
                if (isPlausibleButton(r, realH, visBottom)) buttons.add(r)
            }
        }

        // Fast check 2: Text/contentDescription match for Copy/Cut/Select all/Share
        if (buttons.isEmpty()) {
            for (kw in toolbarKeywords) {
                val matches = runCatching { root.findAccessibilityNodeInfosByText(kw) }.getOrNull() ?: continue
                for (m in matches) {
                    if (!m.isVisibleToUser) continue
                    val label = m.text?.toString()?.trim().orEmpty().ifEmpty { m.contentDescription?.toString()?.trim().orEmpty() }
                    if (!label.equals(kw, ignoreCase = true)) continue
                    if (!m.isClickable && m.parent?.isClickable != true) continue
                    val r = Rect()
                    m.getBoundsInScreen(r)
                    if (isPlausibleButton(r, realH, visBottom)) buttons.add(r)
                }
                if (buttons.isNotEmpty()) break
            }
        }

        // Fast check 3: Small floating window with 2..7 clickable buttons (localized Compose/WebView toolbar)
        if (buttons.isEmpty() && isFloatingWindow) {
            val wr = Rect()
            root.getBoundsInScreen(wr)
            if (wr.height() in dp(28)..dp(112) && wr.width() in dp(80)..dp(360) &&
                wr.top >= dp(24) && wr.bottom <= visBottom
            ) {
                if (countShallowClickableButtons(root) in 2..7) buttons.add(wr)
            }
        }

        if (buttons.isEmpty()) return null

        val anchor = buttons.first()
        val union = Rect(anchor)
        for (other in buttons) {
            if (abs(other.centerY() - anchor.centerY()) <= dp(24)) union.union(other)
        }
        if (union.height() > dp(104)) return null

        val sig = "${windowId}_${union.left / 16}_${union.top / 16}_${union.right / 16}_${union.bottom / 16}"
        return ToolbarHit(union.toBox(), windowId, sig)
    }

    private fun isPlausibleButton(r: Rect, realH: Int, visBottom: Int): Boolean =
        r.width() in dp(18)..dp(240) &&
            r.height() in dp(16)..dp(88) &&
            r.top >= dp(20) &&
            r.bottom <= visBottom - dp(4) &&
            r.top <= realH - dp(80)

    private fun countShallowClickableButtons(root: AccessibilityNodeInfo): Int {
        var found = 0
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < 24) {
            val (node, depth) = queue.removeFirst()
            visited++
            if (node.isVisibleToUser && node.isClickable &&
                (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank())
            ) {
                found++
            }
            if (depth < 4) {
                val c = minOf(node.childCount, 8)
                for (i in 0 until c) {
                    runCatching { node.getChild(i) }.getOrNull()?.let { queue.add(it to depth + 1) }
                }
            }
        }
        return found
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
            val bounds = if (source != null) {
                resolveExactBounds(source, eventText, start, end)
            } else {
                fallbackCenterBox()
            }
            return ResolvedSelection(rangeSub, source, bounds)
        }

        // Chromium / WebView quirk: TYPE_VIEW_TEXT_SELECTION_CHANGED may pass only the selected
        // snippet in event.text while leaving fromIndex/toIndex = -1.
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
            findSelectionInTreeBfs(source, maxNodes = 80, maxDepth = 12)?.let { return it }
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
        maxNodes: Int = 140,
        maxDepth: Int = 16
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
                val count = minOf(node.childCount, 24)
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

    fun performBestCopyAction(sourceNode: AccessibilityNodeInfo?): Boolean {
        if (sourceNode != null && supportsAction(sourceNode, AccessibilityNodeInfo.ACTION_COPY)) {
            if (runCatching { sourceNode.performAction(AccessibilityNodeInfo.ACTION_COPY) }.getOrDefault(false)) return true
        }

        val root = runCatching { service.rootInActiveWindow }.getOrNull()
        val focused = runCatching { root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            ?: runCatching { root?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) }.getOrNull()
        if (focused != null && supportsAction(focused, AccessibilityNodeInfo.ACTION_COPY)) {
            if (runCatching { focused.performAction(AccessibilityNodeInfo.ACTION_COPY) }.getOrDefault(false)) return true
        }

        val roots = mutableListOf<AccessibilityNodeInfo>()
        if (root != null) roots.add(root)
        runCatching { service.windows }.getOrNull()?.forEach { w ->
            if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION && (w.isActive || w.isFocused)) {
                runCatching { w.root }.getOrNull()?.let { wr -> if (wr != root) roots.add(wr) }
            }
        }

        for (scanRoot in roots) {
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            queue.add(scanRoot to 0)
            var visited = 0
            while (queue.isNotEmpty() && visited < 140) {
                val (node, depth) = queue.removeFirst()
                visited++
                if (node.isVisibleToUser && supportsAction(node, AccessibilityNodeInfo.ACTION_COPY)) {
                    if (runCatching { node.performAction(AccessibilityNodeInfo.ACTION_COPY) }.getOrDefault(false)) return true
                }
                if (depth < 16) {
                    val count = minOf(node.childCount, 20)
                    for (i in 0 until count) {
                        runCatching { node.getChild(i) }.getOrNull()?.let { queue.add(it to depth + 1) }
                    }
                }
            }
        }
        return false
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
        if (nodeRect.width() <= 0 || nodeRect.height() <= 0) {
            return fallbackCenterBox()
        }

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
