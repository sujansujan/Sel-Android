package io.github.selclip.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import io.github.selclip.core.AnchorTarget
import io.github.selclip.core.Box
import io.github.selclip.core.PopupMode
import io.github.selclip.core.PopupPositioner
import io.github.selclip.core.Viewport
import io.github.selclip.data.PopAction
import io.github.selclip.data.PrefsStore
import kotlin.math.abs

/**
 * Owns the single lightweight, GPU-accelerated TYPE_ACCESSIBILITY_OVERLAY window.
 *
 * Zero-stutter guarantees:
 * - Explicitly sets `FLAG_HARDWARE_ACCELERATED` so ViewRootImpl uses GPU ThreadedRenderer
 *   instead of slow CPU software Canvas rendering (critical for Service overlays!).
 * - Explicitly sets `params.windowAnimations = 0` to eliminate conflicting OEM window transitions.
 * - Starts the 115ms alpha fade-in strictly inside a one-shot `OnPreDrawListener` AFTER the
 *   SurfaceFlinger surface is created & laid out (60/120fps from 0.0 -> 1.0, ZERO skipped frames!).
 * - No redundant `.withLayer()` offscreen FBO allocation on an already-composited overlay window.
 * - Tracks self window mutations (`addView`/`updateViewLayout`/`removeView`) so the service
 *   ignores its own `TYPE_WINDOWS_CHANGED` echoes during Frame 1.
 */
class OverlayWindowController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onOutsideTouch: () -> Unit,
    private val onSafetyTimeout: () -> Unit
) {

    companion object {
        private val enterInterpolator = DecelerateInterpolator()
    }

    private val handler = Handler(Looper.getMainLooper())

    private var rootView: LinearLayout? = null
    private var cardBgDrawable: GradientDrawable? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var barContainer: LinearLayout? = null
    private var drawerScroll: ScrollView? = null
    private var drawerContainer: LinearLayout? = null
    private var pendingPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null

    private var lastAnchorTarget: AnchorTarget = AnchorTarget(Box(0, 0, 0, 0), false)
    private var lastViewport: Viewport = Viewport(1080, 2200, 96, 120)
    private var maxDrawerHeightPx: Int = 480
    private var activeMaxWidthPx: Int = 640
    private var currentMode: PopupMode = PopupMode.Bar

    private var lastSelfMutationUptimeMs: Long = 0L
    private var attachedAtUptimeMs: Long = 0L

    private val safetyTimeoutRunnable = Runnable { onSafetyTimeout() }

    val isAttached: Boolean get() = rootView?.isAttachedToWindow == true

    fun markSelfWindowMutation() {
        lastSelfMutationUptimeMs = SystemClock.uptimeMillis()
    }

    fun isRecentSelfWindowMutation(withinMs: Long = 90L): Boolean =
        (SystemClock.uptimeMillis() - lastSelfMutationUptimeMs) < withinMs

    private val density: Float get() = context.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()
    private fun dpF(v: Float): Float = v * density

    private fun maxWidthForMode(mode: PopupMode, screenWidth: Int): Int = when (mode) {
        PopupMode.Bar -> (screenWidth - dp(20)).coerceAtLeast(dp(240))
        PopupMode.QrCode -> minOf(dp(208), screenWidth - dp(24))
        PopupMode.Define, PopupMode.Translate, PopupMode.Magnet -> minOf(dp(284), screenWidth - dp(28))
        else -> minOf(dp(224), screenWidth - dp(28))
    }

    fun showOrUpdate(
        anchorTarget: AnchorTarget,
        viewport: Viewport,
        mode: PopupMode,
        isNewSession: Boolean,
        iconsOnly: Boolean,
        actions: List<PopAction>,
        onActionClick: (PopAction) -> Unit,
        onActionLongClick: (PopAction) -> Unit,
        onToggleMore: () -> Unit,
        renderDrawer: (LinearLayout) -> Unit
    ) {
        val isDrawerOpen = mode != PopupMode.Bar
        lastAnchorTarget = anchorTarget
        lastViewport = viewport
        currentMode = mode
        activeMaxWidthPx = maxWidthForMode(mode, viewport.screenWidth)

        if (rootView != null && !isAttached) hide()

        val pal = PrefsStore.resolvePalette(context)
        val createdNow = rootView == null

        if (createdNow) createWindowViews(viewport, mode)

        val root = rootView ?: return
        val params = windowParams ?: return
        val bar = barContainer ?: return
        val scroll = drawerScroll ?: return
        val drawer = drawerContainer ?: return

        val cornerDp = if (isDrawerOpen) 16f else 24f
        cardBgDrawable?.apply {
            setColor(pal.popupSurfaceInt)
            setStroke(dp(1), pal.popupBorderInt)
            cornerRadius = dpF(cornerDp)
        }
        bar.setBackgroundColor(pal.popupSurfaceInt)

        // Compute drawer height budget first so ScrollView measures cleanly before addView
        val preliminaryPlacement = PopupPositioner.place(
            target = anchorTarget,
            viewport = viewport,
            popupWidth = 0,
            popupHeight = 0,
            density = density,
            isDrawerOpen = isDrawerOpen
        )
        maxDrawerHeightPx = minOf(preliminaryPlacement.maxDrawerHeight, dp(260))

        drawer.removeAllViews()
        if (!isDrawerOpen) {
            bar.visibility = View.VISIBLE
            scroll.visibility = View.GONE
            PopupBarRenderer.render(
                context = context,
                container = bar,
                actions = actions,
                pal = pal,
                iconsOnly = iconsOnly,
                isDrawerOpen = false,
                maxBarWidthPx = activeMaxWidthPx,
                onActionClick = onActionClick,
                onActionLongClick = onActionLongClick,
                onToggleMore = onToggleMore
            )
        } else {
            bar.visibility = View.GONE
            scroll.visibility = View.VISIBLE
            renderDrawer(drawer)
        }

        // Pre-measure exact pixel dimensions BEFORE addView / updateViewLayout -> ZERO jump/stutter!
        root.measure(
            View.MeasureSpec.makeMeasureSpec(activeMaxWidthPx, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(viewport.screenHeight, View.MeasureSpec.AT_MOST)
        )
        val exactW = root.measuredWidth
        val exactH = root.measuredHeight

        val placement = PopupPositioner.place(
            target = anchorTarget,
            viewport = viewport,
            popupWidth = exactW,
            popupHeight = exactH,
            density = density,
            isDrawerOpen = isDrawerOpen
        )
        maxDrawerHeightPx = minOf(placement.maxDrawerHeight, dp(260))

        resetSafetyTimer(isDrawerOpen)

        if (createdNow) {
            params.x = placement.x
            params.y = placement.y
            root.alpha = 0f
            markSelfWindowMutation()
            val added = runCatching { windowManager.addView(root, params) }.isSuccess
            if (!added) {
                hide()
                return
            }
            attachedAtUptimeMs = SystemClock.uptimeMillis()

            // Start hardware fade-in strictly on the first real VSync frame after surface attach!
            val listener = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (root.viewTreeObserver.isAlive) {
                        root.viewTreeObserver.removeOnPreDrawListener(this)
                    }
                    if (pendingPreDrawListener === this) pendingPreDrawListener = null
                    if (rootView === root && root.isAttachedToWindow) {
                        root.animate()
                            .alpha(1f)
                            .setDuration(115L)
                            .setInterpolator(enterInterpolator)
                            .start()
                    }
                    return true
                }
            }
            pendingPreDrawListener = listener
            root.viewTreeObserver.addOnPreDrawListener(listener)
        } else {
            val posChanged = params.x != placement.x || params.y != placement.y
            params.x = placement.x
            params.y = placement.y
            root.requestLayout()
            if (posChanged || isDrawerOpen) {
                markSelfWindowMutation()
                runCatching { windowManager.updateViewLayout(root, params) }
            }
        }
    }

    /**
     * Smoothly re-docks the already-visible popup when Android's native selection toolbar moves
     * (e.g., user drags a selection handle) without rebuilding any views.
     */
    fun syncPositionToAnchor(anchorTarget: AnchorTarget, viewport: Viewport) {
        val root = rootView ?: return
        val params = windowParams ?: return
        if (!root.isAttachedToWindow) return

        lastAnchorTarget = anchorTarget
        lastViewport = viewport
        val w = root.width.takeIf { it > 0 } ?: root.measuredWidth
        val h = root.height.takeIf { it > 0 } ?: root.measuredHeight
        val placement = PopupPositioner.place(
            target = anchorTarget,
            viewport = viewport,
            popupWidth = w,
            popupHeight = h,
            density = density,
            isDrawerOpen = currentMode != PopupMode.Bar
        )

        // Ignore small native-toolbar settle wobble during our 160ms enter fade-in!
        val inEnterFade = (SystemClock.uptimeMillis() - attachedAtUptimeMs) < 160L
        val deadzone = if (inEnterFade) dp(24) else dp(8)
        if (abs(placement.x - params.x) > deadzone || abs(placement.y - params.y) > deadzone) {
            params.x = placement.x
            params.y = placement.y
            markSelfWindowMutation()
            runCatching { windowManager.updateViewLayout(root, params) }
        }
    }

    fun relayout() {
        val v = rootView ?: return
        val p = windowParams ?: return
        if (v.isAttachedToWindow) {
            v.measure(
                View.MeasureSpec.makeMeasureSpec(activeMaxWidthPx, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(lastViewport.screenHeight, View.MeasureSpec.AT_MOST)
            )
            val placement = PopupPositioner.place(
                target = lastAnchorTarget,
                viewport = lastViewport,
                popupWidth = v.measuredWidth,
                popupHeight = v.measuredHeight,
                density = density,
                isDrawerOpen = currentMode != PopupMode.Bar
            )
            p.x = placement.x
            p.y = placement.y
            v.requestLayout()
            markSelfWindowMutation()
            runCatching { windowManager.updateViewLayout(v, p) }
        }
    }

    fun hide() {
        handler.removeCallbacks(safetyTimeoutRunnable)
        rootView?.let { v ->
            pendingPreDrawListener?.let { l ->
                if (v.viewTreeObserver.isAlive) v.viewTreeObserver.removeOnPreDrawListener(l)
            }
            pendingPreDrawListener = null
            v.animate().cancel()
            v.visibility = View.GONE
            runCatching { windowManager.removeView(v) }
                .onFailure { runCatching { windowManager.removeViewImmediate(v) } }
        }
        rootView = null
        cardBgDrawable = null
        windowParams = null
        barContainer = null
        drawerScroll = null
        drawerContainer = null
        currentMode = PopupMode.Bar
    }

    private fun resetSafetyTimer(isDrawerOpen: Boolean) {
        handler.removeCallbacks(safetyTimeoutRunnable)
        val timeoutMs = if (isDrawerOpen) 90_000L else 30_000L
        handler.postDelayed(safetyTimeoutRunnable, timeoutMs)
    }

    private fun createWindowViews(viewport: Viewport, mode: PopupMode) {
        val isDrawerOpen = mode != PopupMode.Bar
        val pal = PrefsStore.resolvePalette(context)
        activeMaxWidthPx = maxWidthForMode(mode, viewport.screenWidth)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0
        }

        val bg = GradientDrawable().apply {
            setColor(pal.popupSurfaceInt)
            setStroke(dp(1), pal.popupBorderInt)
            cornerRadius = dpF(if (isDrawerOpen) 16f else 24f)
        }

        val card = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(MeasureSpec.makeMeasureSpec(activeMaxWidthPx, MeasureSpec.AT_MOST), heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            elevation = dpF(6f)
            clipToOutline = true
            background = bg
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE || event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    onOutsideTouch()
                    true
                } else {
                    false
                }
            }
        }

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.popupSurfaceInt)
        }
        card.addView(bar)

        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxDrawerHeightPx, MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = true
            visibility = View.GONE
        }
        val drawer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(6))
        }
        scroll.addView(drawer)
        card.addView(scroll)

        rootView = card
        cardBgDrawable = bg
        windowParams = params
        barContainer = bar
        drawerScroll = scroll
        drawerContainer = drawer
    }
}
