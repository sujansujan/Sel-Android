package io.github.selclip.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
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
 * Bulletproof outside-touch dismissal + zero-stutter guarantees:
 * - Docked Bar mode: `WRAP_CONTENT` with `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL |
 *   FLAG_WATCH_OUTSIDE_TOUCH | FLAG_HARDWARE_ACCELERATED` + `dispatchTouchEvent` catching
 *   `MotionEvent.ACTION_OUTSIDE` so native toolbar buttons & handles remain touchable while
 *   outside touches dismiss Sel immediately.
 * - Standalone (ProcessText in browsers) & Expanded Drawer mode: full-screen transparent
 *   dismiss backdrop catching ANY `ACTION_DOWN` outside the card bounds on every Android OEM
 *   (HyperOS, OneUI, Pixel) with 100% reliability!
 * - `windowAnimations = 0` + one-shot `OnPreDrawListener` hardware fade-in (zero skipped frames).
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

    private var windowRootView: View? = null
    private var cardView: LinearLayout? = null
    private var cardBgDrawable: GradientDrawable? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var barContainer: LinearLayout? = null
    private var drawerScroll: ScrollView? = null
    private var drawerContainer: LinearLayout? = null
    private var pendingPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var useBackdropMode: Boolean = false

    private var lastAnchorTarget: AnchorTarget = AnchorTarget(Box(0, 0, 0, 0), false)
    private var lastViewport: Viewport = Viewport(1080, 2200, 96, 120)
    private var maxDrawerHeightPx: Int = 480
    private var activeMaxWidthPx: Int = 640
    private var currentMode: PopupMode = PopupMode.Bar
    private var currentCardX: Int = 0
    private var currentCardY: Int = 0

    private var lastSelfMutationUptimeMs: Long = 0L
    private var attachedAtUptimeMs: Long = 0L

    private val safetyTimeoutRunnable = Runnable { onSafetyTimeout() }

    val isAttached: Boolean get() = windowRootView?.isAttachedToWindow == true

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
        isStandalone: Boolean,
        iconsOnly: Boolean,
        actions: List<PopAction>,
        onActionClick: (PopAction) -> Unit,
        onActionLongClick: (PopAction) -> Unit,
        onToggleMore: () -> Unit,
        renderDrawer: (LinearLayout) -> Unit
    ) {
        val isDrawerOpen = mode != PopupMode.Bar
        val wantBackdrop = isDrawerOpen || isStandalone

        lastAnchorTarget = anchorTarget
        lastViewport = viewport
        currentMode = mode
        activeMaxWidthPx = maxWidthForMode(mode, viewport.screenWidth)

        // Cleanly rebuild window if detached or transitioning between docked WRAP_CONTENT and backdrop mode
        if (windowRootView != null && (!isAttached || useBackdropMode != wantBackdrop)) {
            hide()
        }

        val pal = PrefsStore.resolvePalette(context)
        val createdNow = windowRootView == null

        if (createdNow) createWindowViews(viewport, mode, wantBackdrop)

        val winRoot = windowRootView ?: return
        val card = cardView ?: return
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

        // Pre-measure exact card pixel dimensions BEFORE addView / updateViewLayout -> ZERO jump/stutter!
        card.measure(
            View.MeasureSpec.makeMeasureSpec(activeMaxWidthPx, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(viewport.screenHeight, View.MeasureSpec.AT_MOST)
        )
        val exactW = card.measuredWidth
        val exactH = card.measuredHeight

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
            applyCardPosition(card, params, placement.x, placement.y, viewport)
            card.alpha = 0f
            markSelfWindowMutation()
            val added = runCatching { windowManager.addView(winRoot, params) }.isSuccess
            if (!added) {
                hide()
                return
            }
            attachedAtUptimeMs = SystemClock.uptimeMillis()

            // Start hardware fade-in strictly on the first real VSync frame after surface attach!
            val animateNow = isNewSession || !isDrawerOpen
            if (!animateNow) {
                card.alpha = 1f
            } else {
                val listener = object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (winRoot.viewTreeObserver.isAlive) {
                            winRoot.viewTreeObserver.removeOnPreDrawListener(this)
                        }
                        if (pendingPreDrawListener === this) pendingPreDrawListener = null
                        if (windowRootView === winRoot && winRoot.isAttachedToWindow) {
                            card.animate()
                                .alpha(1f)
                                .setDuration(115L)
                                .setInterpolator(enterInterpolator)
                                .start()
                        }
                        return true
                    }
                }
                pendingPreDrawListener = listener
                winRoot.viewTreeObserver.addOnPreDrawListener(listener)
            }
        } else {
            val posChanged = currentCardX != placement.x || currentCardY != placement.y
            val backdropResized = useBackdropMode &&
                (params.width != viewport.screenWidth || params.height != viewport.screenHeight)
            applyCardPosition(card, params, placement.x, placement.y, viewport)
            card.requestLayout()
            if (backdropResized || (!useBackdropMode && (posChanged || isDrawerOpen))) {
                markSelfWindowMutation()
                runCatching { windowManager.updateViewLayout(winRoot, params) }
            }
        }
    }

    /**
     * Smoothly re-docks the already-visible popup when Android's native selection toolbar moves
     * (e.g., user drags a selection handle) without rebuilding any views.
     */
    fun syncPositionToAnchor(anchorTarget: AnchorTarget, viewport: Viewport) {
        val winRoot = windowRootView ?: return
        val card = cardView ?: return
        val params = windowParams ?: return
        if (!winRoot.isAttachedToWindow || useBackdropMode) return

        lastAnchorTarget = anchorTarget
        lastViewport = viewport
        val w = card.width.takeIf { it > 0 } ?: card.measuredWidth
        val h = card.height.takeIf { it > 0 } ?: card.measuredHeight
        val placement = PopupPositioner.place(
            target = anchorTarget,
            viewport = viewport,
            popupWidth = w,
            popupHeight = h,
            density = density,
            isDrawerOpen = currentMode != PopupMode.Bar
        )

        val inEnterFade = (SystemClock.uptimeMillis() - attachedAtUptimeMs) < 160L
        val deadzone = if (inEnterFade) dp(24) else dp(8)
        if (abs(placement.x - currentCardX) > deadzone || abs(placement.y - currentCardY) > deadzone) {
            applyCardPosition(card, params, placement.x, placement.y, viewport)
            markSelfWindowMutation()
            runCatching { windowManager.updateViewLayout(winRoot, params) }
        }
    }

    fun relayout() {
        val winRoot = windowRootView ?: return
        val card = cardView ?: return
        val p = windowParams ?: return
        if (winRoot.isAttachedToWindow) {
            card.measure(
                View.MeasureSpec.makeMeasureSpec(activeMaxWidthPx, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(lastViewport.screenHeight, View.MeasureSpec.AT_MOST)
            )
            val placement = PopupPositioner.place(
                target = lastAnchorTarget,
                viewport = lastViewport,
                popupWidth = card.measuredWidth,
                popupHeight = card.measuredHeight,
                density = density,
                isDrawerOpen = currentMode != PopupMode.Bar
            )
            val backdropResized = useBackdropMode &&
                (p.width != lastViewport.screenWidth || p.height != lastViewport.screenHeight)
            applyCardPosition(card, p, placement.x, placement.y, lastViewport)
            card.requestLayout()
            if (backdropResized || !useBackdropMode) {
                markSelfWindowMutation()
                runCatching { windowManager.updateViewLayout(winRoot, p) }
            }
        }
    }

    fun hide() {
        handler.removeCallbacks(safetyTimeoutRunnable)
        windowRootView?.let { v ->
            pendingPreDrawListener?.let { l ->
                if (v.viewTreeObserver.isAlive) v.viewTreeObserver.removeOnPreDrawListener(l)
            }
            pendingPreDrawListener = null
            cardView?.animate()?.cancel()
            v.animate().cancel()
            v.visibility = View.GONE
            runCatching { windowManager.removeView(v) }
                .onFailure { runCatching { windowManager.removeViewImmediate(v) } }
        }
        windowRootView = null
        cardView = null
        cardBgDrawable = null
        windowParams = null
        barContainer = null
        drawerScroll = null
        drawerContainer = null
        useBackdropMode = false
        currentMode = PopupMode.Bar
    }

    private fun applyCardPosition(
        card: LinearLayout,
        params: WindowManager.LayoutParams,
        x: Int,
        y: Int,
        viewport: Viewport
    ) {
        currentCardX = x
        currentCardY = y
        if (useBackdropMode) {
            params.x = 0
            params.y = 0
            params.width = viewport.screenWidth
            params.height = viewport.screenHeight
            val lp = (card.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                )
            lp.leftMargin = x
            lp.topMargin = y
            card.layoutParams = lp
        } else {
            params.x = x
            params.y = y
        }
    }

    private fun resetSafetyTimer(isDrawerOpen: Boolean) {
        handler.removeCallbacks(safetyTimeoutRunnable)
        val timeoutMs = if (isDrawerOpen) 90_000L else 30_000L
        handler.postDelayed(safetyTimeoutRunnable, timeoutMs)
    }

    private fun createWindowViews(viewport: Viewport, mode: PopupMode, backdrop: Boolean) {
        val isDrawerOpen = mode != PopupMode.Bar
        val pal = PrefsStore.resolvePalette(context)
        activeMaxWidthPx = maxWidthForMode(mode, viewport.screenWidth)
        useBackdropMode = backdrop

        val params = if (backdrop) {
            WindowManager.LayoutParams(
                viewport.screenWidth,
                viewport.screenHeight,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                windowAnimations = 0
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        } else {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                windowAnimations = 0
            }
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

            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.action == MotionEvent.ACTION_OUTSIDE || ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    onOutsideTouch()
                    return true
                }
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            elevation = dpF(6f)
            clipToOutline = true
            background = bg
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

        val rootForWindow: View = if (backdrop) {
            object : FrameLayout(context) {
                override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                    if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                        val x = ev.x
                        val y = ev.y
                        if (x < card.left || x > card.right || y < card.top || y > card.bottom) {
                            onOutsideTouch()
                            return true
                        }
                    } else if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                        onOutsideTouch()
                        return true
                    }
                    return super.dispatchTouchEvent(ev)
                }
            }.apply {
                addView(
                    card,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.START
                    )
                )
            }
        } else {
            card
        }

        windowRootView = rootForWindow
        cardView = card
        cardBgDrawable = bg
        windowParams = params
        barContainer = bar
        drawerScroll = scroll
        drawerContainer = drawer
    }
}
