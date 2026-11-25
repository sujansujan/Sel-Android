package io.github.selclip.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import io.github.selclip.data.PopAction
import io.github.selclip.data.ThemePalette

/**
 * Renders the sleek, thumb-friendly native floating PopClip bar:
 * - Large 48dp touch targets, clean sans-serif typography, subtle pressed highlights.
 * - Wraps neatly into 1 row (<= 4 actions) or 2 tidy rows (no awkward horizontal scrolling).
 * - Drag handle (⋮⋮) to reposition, [+] / [–] drawer toggle, and [×] close button.
 */
object PopupBarRenderer {

    private fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
    private fun dpF(context: Context, v: Float): Float = v * context.resources.displayMetrics.density

    fun render(
        context: Context,
        container: LinearLayout,
        actions: List<PopAction>,
        pal: ThemePalette,
        isDrawerOpen: Boolean,
        onAttachDrag: (View) -> Unit,
        onActionClick: (PopAction) -> Unit,
        onActionLongClick: (PopAction) -> Unit,
        onToggleMore: () -> Unit,
        onClose: () -> Unit
    ) {
        container.removeAllViews()

        val rows: List<List<PopAction>> = if (actions.size <= 4) {
            listOf(actions)
        } else {
            val split = (actions.size + 1) / 2
            listOf(actions.subList(0, split), actions.subList(split, actions.size))
        }

        rows.forEachIndexed { rowIdx, rowActions ->
            if (rowIdx > 0) {
                container.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1))
                    setBackgroundColor(pal.dividerInt)
                })
            }

            val rowView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            if (rowIdx == 0) {
                val grip = TextView(context).apply {
                    text = "⋮⋮"
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(pal.fgMutedInt)
                    gravity = Gravity.CENTER
                    minHeight = dp(context, 48)
                    setPadding(dp(context, 10), dp(context, 10), dp(context, 8), dp(context, 10))
                }
                onAttachDrag(grip)
                rowView.addView(grip)
                rowView.addView(makeVertDivider(context, pal))
            }

            rowActions.forEachIndexed { colIdx, act ->
                if (colIdx > 0) rowView.addView(makeVertDivider(context, pal))
                rowView.addView(
                    makeBarButton(
                        context = context,
                        label = act.label,
                        fgColor = if (act.accent) pal.accentSuccessInt else pal.fgPrimaryInt,
                        pal = pal,
                        onLongClick = { onActionLongClick(act) }
                    ) {
                        onActionClick(act)
                    }
                )
            }

            if (rowIdx == 0) {
                rowView.addView(makeVertDivider(context, pal))
                rowView.addView(
                    makeBarButton(
                        context = context,
                        label = if (isDrawerOpen) "−" else "+",
                        fgColor = if (isDrawerOpen) pal.accentPrimaryInt else pal.accentInfoInt,
                        pal = pal,
                        minWidthDp = 44
                    ) {
                        onToggleMore()
                    }
                )
                rowView.addView(makeVertDivider(context, pal))
                rowView.addView(
                    makeBarButton(
                        context = context,
                        label = "×",
                        fgColor = pal.dangerInt,
                        pal = pal,
                        minWidthDp = 44
                    ) {
                        onClose()
                    }
                )
            }

            container.addView(rowView)
        }
    }

    private fun makeVertDivider(context: Context, pal: ThemePalette): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 1), dp(context, 22))
            setBackgroundColor(pal.dividerInt)
        }

    private fun makeBarButton(
        context: Context,
        label: String,
        fgColor: Int,
        pal: ThemePalette,
        minWidthDp: Int = 48,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        setTextColor(fgColor)
        gravity = Gravity.CENTER
        isSingleLine = true
        minHeight = dp(context, 48)
        minWidth = dp(context, minWidthDp)
        setPadding(dp(context, 14), dp(context, 11), dp(context, 14), dp(context, 11))
        background = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                GradientDrawable().apply {
                    setColor(pal.bgPressedInt)
                    cornerRadius = dpF(context, 8f)
                }
            )
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }
        setOnClickListener {
            runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
            onClick()
        }
        if (onLongClick != null) {
            setOnLongClickListener {
                runCatching { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
                onLongClick()
                true
            }
        }
    }
}
