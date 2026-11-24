package io.github.selclip

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
import java.util.ArrayDeque
import kotlin.math.abs

data class SelectionResult(
    val text: String,
    val node: AccessibilityNodeInfo?,
    val bounds: Rect
)

data class ToolbarInfo(
    val bounds: Rect,
    val windowId: Int,
    val isFloatingWindow: Boolean,
    val signature: String
)

data class ScreenMetricsSnapshot(
    val screenWidth: Int,
    val screenHeight: Int,
    val topSafeInset: Int,
    val visibleBottom: Int,
    val toolbar: ToolbarInfo?
)

/**
 * Universal cross-app text selection, floating ActionMode toolbar, and screen metrics detector.
 *
 * Works across native Android Views, Chromium/WebView, Firefox GeckoView, Jetpack Compose,
 * React Native, Flutter, and third-party messaging/email/reader apps without hardcoding
 * a small browser allowlist.
 */
class SelectionDetector(
    private val service: AccessibilityService,
    private val windowManager: WindowManager
) {

    private var cachedSnapshot: ScreenMetricsSnapshot? = null
    private var lastSnapshotTime: Long = 0L

    private var lastToolbar: ToolbarInfo? = null
    private var lastToolbarTime: Long = 0L

    private fun dp(v: Int): Int = (v * service.resources.displayMetrics.density).toInt()

    private fun Int.safeCoerceIn(minVal: Int, maxVal: Int): Int =
        if (minVal <= maxVal) this.coerceIn(minVal, maxVal) else minVal

    private fun Float.safeCoerceIn(minVal: Float, maxVal: Float): Float =
        if (minVal <= maxVal) this.coerceIn(minVal, maxVal) else minVal

    fun isImePackage(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        val p = pkg.lowercase()
        return p.contains("inputmethod") ||
            p.contains("keyboard") ||
            p.contains("honeyboard") ||
            p.contains("latin") ||
            p.contains("gboard") ||
            p.contains("swiftkey") ||
            p.contains("fleksy") ||
            p.contains("openboard") ||
            p.contains("florisboard") ||
            p.contains("helioboard") ||
            p.contains("anysoftkeyboard")
    }

    fun isTransientSystemOrPopupClass(className: String): Boolean {
        if (className.isEmpty()) return false
        return className.contains("Popup", ignoreCase = true) ||
            className.contains("Floating", ignoreCase = true) ||
            className.contains("ActionMode", ignoreCase = true) ||
            className.contains("Menu", ignoreCase = true) ||
            className.contains("Toast", ignoreCase = true) ||
            className.contains("Dialog", ignoreCase = true) ||
            className.contains("Tooltip", ignoreCase = true) ||
            className.contains("Snackbar", ignoreCase = true)
    }

    fun getRealScreenBounds(): Rect {
        return runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                Rect(windowManager.currentWindowMetrics.bounds)
            } else {
                val outMetrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(outMetrics)
                Rect(0, 0, outMetrics.widthPixels, outMetrics.heightPixels)
            }
        }.getOrElse {
            val dm = service.resources.displayMetrics
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }

    fun getScreenMetrics(force: Boolean = false): ScreenMetricsSnapshot {
        val now = System.currentTimeMillis()
        val cached = cachedSnapshot
        if (!force && cached != null && now - lastSnapshotTime < 350L) {
            return cached
        }
        lastSnapshotTime = now

        val bounds = getRealScreenBounds()
        val realW = bounds.width().coerceAtLeast(320)
        val realH = bounds.height().coerceAtLeast(480)

        var topInset = dp(44)
        var bottomInset = dp(56)
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val insets = windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                topInset = maxOf(insets.top + dp(6), dp(32))
                bottomInset = maxOf(insets.bottom + dp(12), dp(40))
            }
        }

        var visBottom = realH - bottomInset
        var detectedToolbar: ToolbarInfo? = null

        val winList = runCatching { service.windows }.getOrNull()
        if (!winList.isNullOrEmpty()) {
            // Pass 1: Detect visible IME height so popup never overlaps keyboard
            for (w in winList) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    val imeRect = Rect()
                    w.getBoundsInScreen(imeRect)
                    if (imeRect.height() > dp(80) && imeRect.top in (realH / 5)..(realH - dp(60))) {
                        visBottom = minOf(visBottom, imeRect.top - dp(8))
                    }
                }
            }

            // Pass 2: Scan floating / active windows for an active selection toolbar (Copy/Cut/Select All)
            // Prioritize small floating popup windows first (Android FloatingToolbar / Compose / WebView)
            val orderedWindows = winList.sortedWith(
                compareByDescending<AccessibilityWindowInfo> {
                    val r = Rect()
                    it.getBoundsInScreen(r)
                    r.height() in dp(24)..dp(150)
                }.thenByDescending { it.layer }
            )

            for (w in orderedWindows) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    w.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                    w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                ) {
                    continue
                }
                val root = runCatching { w.root }.getOrNull() ?: continue
                val pkg = root.packageName?.toString() ?: ""
                if (pkg == service.packageName || pkg == "com.android.systemui" || isImePackage(pkg)) continue

                val wRect = Rect()
                w.getBoundsInScreen(wRect)
                val isFloating = wRect.height() in dp(24)..dp(150) && wRect.width() < (realW * 0.96f).toInt()
                if (isFloating || w.isActive || w.isFocused) {
                    val hit = detectSelectionToolbarInRoot(root, w.id, isFloating, realH, visBottom)
                    if (hit != null) {
                        detectedToolbar = hit
                        break
                    }
                }
            }
        }

        val safeVisBottom = visBottom.coerceAtLeast(realH / 3)
        if (detectedToolbar == null) {
            val activeRoot = runCatching { service.rootInActiveWindow }.getOrNull()
            if (activeRoot != null) {
                val pkg = activeRoot.packageName?.toString() ?: ""
                if (pkg != service.packageName && !isImePackage(pkg) && pkg != "com.android.systemui") {
                    detectedToolbar = detectSelectionToolbarInRoot(activeRoot, -1, false, realH, safeVisBottom)
                }
            }
        }

        if (detectedToolbar != null) {
            lastToolbar = detectedToolbar
            lastToolbarTime = now
        }

        val effectiveToolbar = detectedToolbar ?: lastToolbar?.takeIf { now - lastToolbarTime < 1500L }
        val snap = ScreenMetricsSnapshot(
            screenWidth = realW,
            screenHeight = realH,
            topSafeInset = topInset,
            visibleBottom = safeVisBottom,
            toolbar = effectiveToolbar
        )
        cachedSnapshot = snap
        return snap
    }

    /**
     * Universal, fast detection of Android's floating/action-mode text selection toolbar
     * across ANY app (native Views, WebView, Chrome, Firefox, Compose SelectionContainer, etc.).
     */
    fun findActiveSelectionToolbarNow(): ToolbarInfo? {
        val snap = getScreenMetrics(force = true)
        return snap.toolbar?.takeIf { System.currentTimeMillis() - lastToolbarTime < 500L }
    }

    private val toolbarKeywords = listOf("Copy", "Cut", "Select all")
    private val toolbarIdHints = listOf(
        "android:id/copy",
        "android:id/cut",
        "android:id/selectAll",
        "floating_toolbar_menu_item"
    )

    private fun detectSelectionToolbarInRoot(
        root: AccessibilityNodeInfo,
        windowId: Int,
        isFloatingWindow: Boolean,
        realH: Int,
        maxBottom: Int
    ): ToolbarInfo? {
        val candidateButtons = mutableListOf<Rect>()

        // Fast check 1: Standard Android floating toolbar view IDs (works in ANY language/locale!)
        for (idName in toolbarIdHints) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(idName) }.getOrNull()
            if (!nodes.isNullOrEmpty()) {
                for (n in nodes) {
                    if (!n.isVisibleToUser) continue
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    if (isPlausibleToolbarButtonRect(r, realH, maxBottom)) {
                        candidateButtons.add(r)
                    }
                }
            }
        }

        // Fast check 2: Localized/English text & contentDescription match for Copy / Cut / Select all
        if (candidateButtons.isEmpty()) {
            for (kw in toolbarKeywords) {
                val matches = runCatching { root.findAccessibilityNodeInfosByText(kw) }.getOrNull()
                if (matches.isNullOrEmpty()) continue
                for (m in matches) {
                    if (!m.isVisibleToUser) continue
                    val t1 = m.text?.toString()?.trim().orEmpty()
                    val t2 = m.contentDescription?.toString()?.trim().orEmpty()
                    val txt = t1.ifEmpty { t2 }
                    if (!txt.equals(kw, ignoreCase = true)) continue
                    if (!m.isClickable && m.parent?.isClickable != true) continue

                    val r = Rect()
                    m.getBoundsInScreen(r)
                    if (isPlausibleToolbarButtonRect(r, realH, maxBottom)) {
                        candidateButtons.add(r)
                    }
                }
                if (candidateButtons.isNotEmpty()) break
            }
        }

        // Fast check 3: Small floating popup window containing clickable buttons (localized Compose/WebView toolbar)
        if (candidateButtons.isEmpty() && isFloatingWindow) {
            val wRect = Rect()
            root.getBoundsInScreen(wRect)
            if (wRect.height() in dp(28)..dp(110) && wRect.width() in dp(80)..dp(360) &&
                wRect.top >= dp(24) && wRect.bottom <= maxBottom
            ) {
                val clickableCount = countShallowClickableButtons(root)
                if (clickableCount in 2..7) {
                    candidateButtons.add(wRect)
                }
            }
        }

        if (candidateButtons.isEmpty()) return null

        val anchor = candidateButtons.first()
        val rowUnion = Rect(anchor)
        for (other in candidateButtons) {
            if (abs(other.centerY() - anchor.centerY()) <= dp(24)) {
                rowUnion.union(other)
            }
        }
        if (rowUnion.height() > dp(104)) return null

        val sig = "${windowId}_${rowUnion.left / 16}_${rowUnion.top / 16}_${rowUnion.right / 16}_${rowUnion.bottom / 16}"
        return ToolbarInfo(rowUnion, windowId, isFloatingWindow, sig)
    }

    private fun isPlausibleToolbarButtonRect(r: Rect, realH: Int, maxBottom: Int): Boolean {
        return r.width() in dp(18)..dp(240) &&
            r.height() in dp(16)..dp(88) &&
            r.top >= dp(20) &&
            r.bottom <= maxBottom - dp(4) &&
            r.top <= realH - dp(80)
    }

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
                    val ch = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    queue.add(ch to depth + 1)
                }
            }
        }
        return found
    }

    /**
     * Extracts text selection from an AccessibilityEvent with multi-stage fallbacks:
     * 1. Direct event.source node (with exact character location bounds on API 26+)
     * 2. event.text + fromIndex/toIndex
     * 3. Input/Accessibility focused node
     * 4. Bounded BFS across source / active window
     */
    fun extractSelection(event: AccessibilityEvent): SelectionResult? {
        val pkg = event.packageName?.toString() ?: ""
        val source = event.source
        if (pkg.contains("launcher", ignoreCase = true) && source?.isFocused != true) {
            return null
        }

        if (source != null) {
            if (source.isPassword) return null
            extractFromNode(source, event.fromIndex, event.toIndex)?.let { return it }
        }

        val eventText = event.text.joinToString("")
        if (eventText.isNotBlank() && event.fromIndex >= 0 && event.toIndex >= 0 && event.fromIndex != event.toIndex) {
            val start = minOf(event.fromIndex, event.toIndex)
            val end = maxOf(event.fromIndex, event.toIndex)
            if (end <= eventText.length) {
                val sub = eventText.substring(start, end)
                if (sub.isNotBlank()) {
                    val rect = if (source != null) {
                        resolveExactSelectionBounds(source, eventText, start, end)
                    } else {
                        val b = getRealScreenBounds()
                        Rect(dp(24), b.height() / 3, b.width() - dp(24), b.height() / 3 + dp(48))
                    }
                    return SelectionResult(sub, source, rect)
                }
            }
        }

        val focused = findFocusedEditableOrSelectableNode()
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
    ): SelectionResult? {
        if (node.isPassword) return null
        val raw = node.text?.toString() ?: ""
        val hint = node.hintText?.toString() ?: ""
        if (raw.isEmpty() || (hint.isNotEmpty() && raw == hint)) return null

        val ns = node.textSelectionStart
        val ne = node.textSelectionEnd
        if (ns >= 0 && ne >= 0 && ns != ne) {
            val start = minOf(ns, ne)
            val end = maxOf(ns, ne)
            if (end <= raw.length) {
                val sub = raw.substring(start, end)
                if (sub.isNotBlank()) {
                    val rawBounds = resolveExactSelectionBounds(node, raw, start, end)
                    return SelectionResult(sub, node, rawBounds)
                }
            }
        }

        if (evFrom >= 0 && evTo >= 0 && evFrom != evTo) {
            val start = minOf(evFrom, evTo)
            val end = maxOf(evFrom, evTo)
            if (end <= raw.length) {
                val sub = raw.substring(start, end)
                if (sub.isNotBlank()) {
                    val rawBounds = resolveExactSelectionBounds(node, raw, start, end)
                    return SelectionResult(sub, node, rawBounds)
                }
            }
        }

        return null
    }

    /**
     * Breadth-first bounded tree search (much better than shallow DFS on Chrome/Compose/Gmail/WhatsApp).
     */
    fun findSelectionInTreeBfs(
        root: AccessibilityNodeInfo?,
        maxNodes: Int = 160,
        maxDepth: Int = 18
    ): SelectionResult? {
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
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    if (child.isVisibleToUser || child.childCount > 0) {
                        queue.add(child to depth + 1)
                    }
                }
            }
        }
        return null
    }

    fun findFocusedEditableOrSelectableNode(): AccessibilityNodeInfo? {
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

    fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null
        val inputFocused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (inputFocused != null && inputFocused.isEditable) return inputFocused
        val a11yFocused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) }.getOrNull()
        if (a11yFocused != null && a11yFocused.isEditable) return a11yFocused
        return null
    }

    /**
     * Class-agnostic ACTION_COPY trigger: works on Chrome, WebView, Firefox GeckoView,
     * Jetpack Compose, React Native, and custom text containers by inspecting node.actionList.
     */
    fun performBestCopyAction(sourceNode: AccessibilityNodeInfo?): Boolean {
        if (sourceNode != null && supportsAction(sourceNode, AccessibilityNodeInfo.ACTION_COPY)) {
            if (runCatching { sourceNode.performAction(AccessibilityNodeInfo.ACTION_COPY) }.getOrDefault(false)) {
                return true
            }
        }

        val root = runCatching { service.rootInActiveWindow }.getOrNull()
        val focused = runCatching { root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            ?: runCatching { root?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) }.getOrNull()
        if (focused != null && supportsAction(focused, AccessibilityNodeInfo.ACTION_COPY)) {
            if (runCatching { focused.performAction(AccessibilityNodeInfo.ACTION_COPY) }.getOrDefault(false)) {
                return true
            }
        }

        // BFS for any visible node explicitly advertising ACTION_COPY (WebView, GeckoView, Compose, etc.)
        if (root != null) {
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            queue.add(root to 0)
            var visited = 0
            while (queue.isNotEmpty() && visited < 120) {
                val (node, depth) = queue.removeFirst()
                visited++
                if (node.isVisibleToUser && supportsAction(node, AccessibilityNodeInfo.ACTION_COPY)) {
                    if (runCatching { node.performAction(AccessibilityNodeInfo.ACTION_COPY) }.getOrDefault(false)) {
                        return true
                    }
                }
                if (depth < 14) {
                    val count = minOf(node.childCount, 18)
                    for (i in 0 until count) {
                        val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                        queue.add(child to depth + 1)
                    }
                }
            }
        }
        return false
    }

    private fun supportsAction(node: AccessibilityNodeInfo, actionId: Int): Boolean {
        val actions = runCatching { node.actionList }.getOrNull() ?: return false
        for (a in actions) {
            if (a.id == actionId) return true
        }
        return false
    }

    /**
     * Resolves exact pixel selection bounds:
     * 1) Requests OS character rects via EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY (API 26+).
     * 2) Falls back to clean line-band estimation if the view doesn't report extra data.
     */
    fun resolveExactSelectionBounds(
        node: AccessibilityNodeInfo,
        raw: String,
        start: Int,
        end: Int
    ): Rect {
        val screen = getRealScreenBounds()
        val realW = screen.width()
        val realH = screen.height()

        // Try exact character bounding boxes from Android Accessibility Extra Data (API 26+)
        val exactRect = queryCharacterRangeRect(node, start, (end - start).coerceIn(1, 200))
        if (exactRect != null && exactRect.width() > 2 && exactRect.height() > 2 &&
            exactRect.bottom > 0 && exactRect.top < realH
        ) {
            return exactRect
        }

        val nodeRect = Rect()
        node.getBoundsInScreen(nodeRect)
        if (nodeRect.width() <= 0 || nodeRect.height() <= 0) {
            nodeRect.set(dp(24), realH / 4, realW - dp(24), realH / 4 + dp(44))
        }

        if (nodeRect.height() in dp(84)..(realH * 0.45f).toInt() && raw.isNotEmpty()) {
            val avgCharsPerLine = (nodeRect.width() / dp(7).coerceAtLeast(1)).safeCoerceIn(22, 72)
            var currentLine = 0
            var startLine = 0
            var endLine = 0
            var col = 0
            val limit = minOf(raw.length, 4000)
            for (i in 0 until limit) {
                if (i == start) startLine = currentLine
                if (i == end) endLine = currentLine
                val ch = raw[i]
                if (ch == '\n') {
                    currentLine++
                    col = 0
                } else {
                    col++
                    if (col >= avgCharsPerLine) {
                        currentLine++
                        col = 0
                    }
                }
            }
            if (end >= limit) endLine = currentLine
            val totalLines = (currentLine + 1).coerceAtLeast(1)
            val estLineH = (nodeRect.height().toFloat() / totalLines.toFloat())
                .safeCoerceIn(dp(16).toFloat(), dp(25).toFloat())
            val bandTop = (nodeRect.top + startLine * estLineH).toInt()
                .safeCoerceIn(nodeRect.top, (nodeRect.bottom - dp(28)).coerceAtLeast(nodeRect.top))
            val bandBottom = (nodeRect.top + (endLine + 1) * estLineH).toInt()
                .safeCoerceIn(bandTop + dp(24), maxOf(bandTop + dp(24), nodeRect.bottom))
            return Rect(nodeRect.left, bandTop, nodeRect.right, bandBottom)
        }

        return Rect(nodeRect.left, nodeRect.top, nodeRect.right, nodeRect.bottom)
    }

    fun queryCharacterRangeRect(node: AccessibilityNodeInfo, startIndex: Int, length: Int): Rect? {
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

    fun resolveBestSafeZone(rawCandidate: Rect?, forceRefresh: Boolean = false): Rect {
        val metrics = getScreenMetrics(force = forceRefresh)
        val realW = metrics.screenWidth
        val realH = metrics.screenHeight
        val topSafe = metrics.topSafeInset
        val visibleBottom = metrics.visibleBottom
        val toolbarRect = metrics.toolbar?.bounds

        val isCompactCandidate = rawCandidate != null &&
            rawCandidate.width() > 0 &&
            rawCandidate.height() in 1..dp(105) &&
            rawCandidate.top < visibleBottom

        val isMediumCandidate = rawCandidate != null &&
            rawCandidate.width() > 0 &&
            rawCandidate.height() in 1..(realH * 0.36f).toInt() &&
            rawCandidate.top < visibleBottom

        if (isCompactCandidate && rawCandidate != null) {
            var zoneTop = rawCandidate.top - dp(10)
            var zoneBottom = rawCandidate.bottom + dp(18)
            if (toolbarRect != null && abs(toolbarRect.centerY() - rawCandidate.centerY()) < dp(150)) {
                zoneTop = minOf(zoneTop, toolbarRect.top - dp(10))
                zoneBottom = maxOf(zoneBottom, toolbarRect.bottom + dp(14))
            }
            val clampedTop = zoneTop.safeCoerceIn(topSafe, (visibleBottom - dp(64)).coerceAtLeast(topSafe))
            val clampedBottom = zoneBottom.safeCoerceIn(
                clampedTop + dp(28),
                (visibleBottom - dp(8)).coerceAtLeast(clampedTop + dp(28))
            )
            val left = rawCandidate.left.coerceAtLeast(dp(12))
            val right = rawCandidate.right.coerceAtMost(realW - dp(12)).coerceAtLeast(left + dp(24))
            return Rect(left, clampedTop, right, clampedBottom)
        }

        if (toolbarRect != null) {
            val clampedTop = (toolbarRect.top - dp(14)).safeCoerceIn(topSafe, (visibleBottom - dp(80)).coerceAtLeast(topSafe))
            val clampedBottom = (toolbarRect.bottom + dp(60)).safeCoerceIn(
                clampedTop + dp(36),
                (visibleBottom - dp(8)).coerceAtLeast(clampedTop + dp(36))
            )
            val left = toolbarRect.left.coerceAtLeast(dp(12))
            val right = toolbarRect.right.coerceAtMost(realW - dp(12)).coerceAtLeast(left + dp(24))
            return Rect(left, clampedTop, right, clampedBottom)
        }

        if (isMediumCandidate && rawCandidate != null) {
            val clampedTop = (rawCandidate.top - dp(10)).safeCoerceIn(topSafe, (visibleBottom - dp(80)).coerceAtLeast(topSafe))
            val clampedBottom = (rawCandidate.bottom + dp(20)).safeCoerceIn(
                clampedTop + dp(32),
                (visibleBottom - dp(8)).coerceAtLeast(clampedTop + dp(32))
            )
            val left = rawCandidate.left.coerceAtLeast(dp(12))
            val right = rawCandidate.right.coerceAtMost(realW - dp(12)).coerceAtLeast(left + dp(24))
            return Rect(left, clampedTop, right, clampedBottom)
        }

        val fallbackMidY = (visibleBottom * 0.36f).toInt().safeCoerceIn(dp(96), (visibleBottom - dp(96)).coerceAtLeast(dp(96)))
        return Rect(dp(16), fallbackMidY - dp(28), realW - dp(16), fallbackMidY + dp(36))
    }
}
