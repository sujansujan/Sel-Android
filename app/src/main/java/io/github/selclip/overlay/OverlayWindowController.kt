package io.github.selclip.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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
 * Owns the single lightweight TYPE_ACCESSIBILITY_OVERLAY window.
 *
 * Mirrors Android's native FloatingToolbarPopup morphing behavior:
 * - In [PopupMode.Bar]: sleek 48dp native pill (`24dp` corners, WRAP_CONTENT width).
 * - When expanded (overflow / sub-drawer): hides the horizontal bar and morphs into a compact,
 *   tight-fitting Material 3 menu card (`16dp` corners) sized strictly to its content (216–224dp
 *   max for action lists, 204dp for QR, 284dp for Define/Translate) — zero wasted screen area!
 */
class OverlayWindowController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onOutsideTouch: () -> Unit,
    private val onUserDragged: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())

    private var rootView: LinearLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var barContainer: LinearLayout? = null
    private var drawerScroll: ScrollView? = null
    private var drawerContainer: LinearLayout? = null

    private var lastAnchorTarget: AnchorTarget = AnchorTarget(Box(0, 0, 0, 0), false)
    private var lastViewport: Viewport = Viewport(1080, 2200, 96, 120)
    private var maxDrawerHeightPx: Int = 480
    private var activeMaxWidthPx: Int = 640
    private var manuallyDragged: Boolean = false
    private var currentMode: PopupMode = PopupMode.Bar

    val isAttached: Boolean get() = rootView?.isAttachedToWindow == true

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
        onFaviconReady: () -> Unit,
        renderDrawer: (LinearLayout) -> Unit
    ) {
        val isDrawerOpen = mode != PopupMode.Bar
        lastAnchorTarget = anchorTarget
        lastViewport = viewport
        currentMode = mode
        activeMaxWidthPx = maxWidthForMode(mode, viewport.screenWidth)
        if (isNewSession) manuallyDragged = false

        if (rootView != null && !isAttached) hide()

        val pal = PrefsStore.resolvePalette(context)
        val createdNow = rootView == null

        if (createdNow) createWindow(anchorTarget, viewport, mode)

        val root = rootView ?: return
        val params = windowParams ?: return
        val bar = barContainer ?: return
        val scroll = drawerScroll ?: return
        val drawer = drawerContainer ?: return

        val cornerDp = if (isDrawerOpen) 16f else 24f
        root.background = GradientDrawable().apply {
            setColor(pal.popupSurfaceInt)
            setStroke(dp(1), pal.popupBorderInt)
            cornerRadius = dpF(cornerDp)
        }
        bar.setBackgroundColor(pal.popupSurfaceInt)

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
                onActionClick = onActionClick,
                onActionLongClick = onActionLongClick,
                onToggleMore = onToggleMore,
                onFaviconLoaded = onFaviconReady
            )
        } else {
            // Morph seamlessly from pill into compact native overflow menu (no redundant bar on top!)
            bar.visibility = View.GONE
            scroll.visibility = View.VISIBLE
            renderDrawer(drawer)
        }

        if (!manuallyDragged) {
            val placement = PopupPositioner.place(
                target = anchorTarget,
                viewport = viewport,
                popupWidth = if (createdNow) 0 else root.width.coerceAtMost(activeMaxWidthPx),
                popupHeight = if (createdNow) 0 else root.height,
                density = density,
                isDrawerOpen = isDrawerOpen
            )
            maxDrawerHeightPx = minOf(placement.maxDrawerHeight, dp(260))
            params.x = placement.x
            params.y = placement.y
        }

        if (createdNow) {
            root.alpha = 0f
            root.scaleX = 0.96f
            root.scaleY = 0.96f
            val added = runCatching { windowManager.addView(root, params) }.isSuccess
            if (!added) {
                hide()
                return
            }
            root.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(85L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            relayout()
        }
    }

    fun relayout() {
        val v = rootView ?: return
        val p = windowParams ?: return
        if (v.isAttachedToWindow) {
            v.requestLayout()
            runCatching { windowManager.updateViewLayout(v, p) }
        }
    }

    fun hide() {
        rootView?.let { v ->
            v.animate().cancel()
            runCatching { windowManager.removeView(v) }
        }
        rootView = null
        windowParams = null
        barContainer = null
        drawerScroll = null
        drawerContainer = null
        manuallyDragged = false
        currentMode = PopupMode.Bar
    }

    private fun createWindow(anchorTarget: AnchorTarget, viewport: Viewport, mode: PopupMode) {
        val isDrawerOpen = mode != PopupMode.Bar
        val pal = PrefsStore.resolvePalette(context)
        val placement = PopupPositioner.place(
            target = anchorTarget,
            viewport = viewport,
            popupWidth = 0,
            popupHeight = 0,
            density = density,
            isDrawerOpen = isDrawerOpen
        )
        maxDrawerHeightPx = minOf(placement.maxDrawerHeight, dp(260))
        activeMaxWidthPx = maxWidthForMode(mode, viewport.screenWidth)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = placement.x
            y = placement.y
        }

        val card = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(MeasureSpec.makeMeasureSpec(activeMaxWidthPx, MeasureSpec.AT_MOST), heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            elevation = dpF(6f)
            clipToOutline = true
            background = GradientDrawable().apply {
                setColor(pal.popupSurfaceInt)
                setStroke(dp(1), pal.popupBorderInt)
                cornerRadius = dpF(if (isDrawerOpen) 16f else 24f)
            }
            addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                val actualW = right - left
                val actualH = bottom - top
                val p = windowParams ?: return@addOnLayoutChangeListener
                if (actualW > 0 && actualH > 0 && !manuallyDragged && !lastAnchorTarget.zone.isEmpty) {
                    val refined = PopupPositioner.place(
                        target = lastAnchorTarget,
                        viewport = lastViewport,
                        popupWidth = actualW,
                        popupHeight = actualH,
                        density = density,
                        isDrawerOpen = currentMode != PopupMode.Bar
                    )
                    maxDrawerHeightPx = minOf(refined.maxDrawerHeight, dp(260))
                    if (abs(refined.x - p.x) > dp(2) || abs(refined.y - p.y) > dp(2)) {
                        p.x = refined.x
                        p.y = refined.y
                        if (v.isAttachedToWindow) {
                            handler.post {
                                if (v.isAttachedToWindow) runCatching { windowManager.updateViewLayout(v, p) }
                            }
                        }
                    }
                }
            }
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
        windowParams = params
        barContainer = bar
        drawerScroll = scroll
        drawerContainer = drawer
    }
}
