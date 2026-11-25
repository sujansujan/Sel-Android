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
import io.github.selclip.core.Box
import io.github.selclip.core.PopupPositioner
import io.github.selclip.core.Viewport
import io.github.selclip.data.PopAction
import io.github.selclip.data.PrefsStore
import kotlin.math.abs

/**
 * Owns the single lightweight TYPE_ACCESSIBILITY_OVERLAY window.
 * Strictly leak-free: creates views lazily on show and tears down references completely on hide.
 */
class OverlayWindowController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onOutsideTouch: () -> Unit,
    private val onUserDragged: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())

    private var rootView: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var barContainer: LinearLayout? = null
    private var drawerDivider: View? = null
    private var drawerScroll: ScrollView? = null
    private var drawerContainer: LinearLayout? = null

    private var lastAnchorZone: Box = Box(0, 0, 0, 0)
    private var lastViewport: Viewport = Viewport(1080, 2200, 96, 120)
    private var maxDrawerHeightPx: Int = 540
    private var manuallyDragged: Boolean = false
    private var currentIsDrawerOpen: Boolean = false

    val isAttached: Boolean get() = rootView?.isAttachedToWindow == true

    private val density: Float get() = context.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()
    private fun dpF(v: Float): Float = v * density

    fun showOrUpdate(
        anchorZone: Box,
        viewport: Viewport,
        isDrawerOpen: Boolean,
        isNewSession: Boolean,
        actions: List<PopAction>,
        onActionClick: (PopAction) -> Unit,
        onActionLongClick: (PopAction) -> Unit,
        onToggleMore: () -> Unit,
        onClose: () -> Unit,
        renderDrawer: (LinearLayout) -> Unit
    ) {
        lastAnchorZone = anchorZone
        lastViewport = viewport
        currentIsDrawerOpen = isDrawerOpen
        if (isNewSession) manuallyDragged = false

        if (rootView != null && !isAttached) {
            hide()
        }

        val pal = PrefsStore.resolvePalette(context)
        val createdNow = rootView == null

        if (createdNow) {
            createWindow(anchorZone, viewport, isDrawerOpen)
        }

        val root = rootView ?: return
        val params = layoutParams ?: return
        val bar = barContainer ?: return
        val div = drawerDivider ?: return
        val scroll = drawerScroll ?: return
        val drawer = drawerContainer ?: return

        root.background = GradientDrawable().apply {
            setColor(pal.bgPrimaryInt)
            setStroke(dp(1), pal.borderInt)
            cornerRadius = dpF(14f)
        }
        bar.setBackgroundColor(pal.bgHeaderInt)
        div.setBackgroundColor(pal.dividerInt)

        PopupBarRenderer.render(
            context = context,
            container = bar,
            actions = actions,
            pal = pal,
            isDrawerOpen = isDrawerOpen,
            onAttachDrag = { grip -> attachDrag(grip, root) },
            onActionClick = onActionClick,
            onActionLongClick = onActionLongClick,
            onToggleMore = onToggleMore,
            onClose = onClose
        )

        drawer.removeAllViews()
        if (!isDrawerOpen) {
            div.visibility = View.GONE
            scroll.visibility = View.GONE
        } else {
            div.visibility = View.VISIBLE
            scroll.visibility = View.VISIBLE
            renderDrawer(drawer)
        }

        if (!manuallyDragged) {
            val placement = PopupPositioner.place(
                anchorZone = anchorZone,
                viewport = viewport,
                popupWidth = root.width,
                popupHeight = root.height,
                density = density,
                isDrawerOpen = isDrawerOpen
            )
            maxDrawerHeightPx = placement.maxDrawerHeight
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
                .setDuration(90L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            relayout()
        }
    }

    fun relayout() {
        val v = rootView ?: return
        val p = layoutParams ?: return
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
        layoutParams = null
        barContainer = null
        drawerDivider = null
        drawerScroll = null
        drawerContainer = null
        manuallyDragged = false
        currentIsDrawerOpen = false
    }

    private fun createWindow(anchorZone: Box, viewport: Viewport, isDrawerOpen: Boolean) {
        val pal = PrefsStore.resolvePalette(context)
        val placement = PopupPositioner.place(
            anchorZone = anchorZone,
            viewport = viewport,
            popupWidth = 0,
            popupHeight = 0,
            density = density,
            isDrawerOpen = isDrawerOpen
        )
        maxDrawerHeightPx = placement.maxDrawerHeight

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

        val maxBarW = (viewport.screenWidth - dp(20)).coerceAtLeast(dp(260))

        val card = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(MeasureSpec.makeMeasureSpec(maxBarW, MeasureSpec.AT_MOST), heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            elevation = dpF(12f)
            clipToOutline = true
            background = GradientDrawable().apply {
                setColor(pal.bgPrimaryInt)
                setStroke(dp(1), pal.borderInt)
                cornerRadius = dpF(14f)
            }
            addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                val actualW = right - left
                val actualH = bottom - top
                val p = layoutParams ?: return@addOnLayoutChangeListener
                if (actualW > 0 && actualH > 0 && !manuallyDragged && !lastAnchorZone.isEmpty) {
                    val refined = PopupPositioner.place(
                        anchorZone = lastAnchorZone,
                        viewport = lastViewport,
                        popupWidth = actualW,
                        popupHeight = actualH,
                        density = density,
                        isDrawerOpen = currentIsDrawerOpen
                    )
                    maxDrawerHeightPx = refined.maxDrawerHeight
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
            setBackgroundColor(pal.bgHeaderInt)
        }
        card.addView(bar)

        val div = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(pal.dividerInt)
            visibility = View.GONE
        }
        card.addView(div)

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
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        scroll.addView(drawer)
        card.addView(scroll)

        rootView = card
        layoutParams = params
        barContainer = bar
        drawerDivider = div
        drawerScroll = scroll
        drawerContainer = drawer
    }

    private fun attachDrag(handle: View, root: View) {
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f

        handle.setOnTouchListener { _, event ->
            val p = layoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x
                    startY = p.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) {
                        if (!manuallyDragged) {
                            manuallyDragged = true
                            onUserDragged()
                        }
                    }
                    val (clampedX, clampedY) = PopupPositioner.clampDragged(
                        targetX = startX + dx,
                        targetY = startY + dy,
                        popupWidth = root.width.coerceAtLeast(dp(160)),
                        popupHeight = root.height.coerceAtLeast(dp(48)),
                        viewport = lastViewport,
                        density = density
                    )
                    p.x = clampedX
                    p.y = clampedY
                    if (root.isAttachedToWindow) runCatching { windowManager.updateViewLayout(root, p) }
                    true
                }
                else -> false
            }
        }
    }
}
