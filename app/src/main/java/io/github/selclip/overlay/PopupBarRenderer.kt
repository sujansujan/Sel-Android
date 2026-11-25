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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.selclip.data.PopAction
import io.github.selclip.data.ThemePalette

/**
 * Renders the native Android FloatingToolbar-style pill.
 *
 * Updates downloaded favicons IN-PLACE on their ImageView without rebuilding the bar or
 * triggering WindowManager.updateViewLayout — completely eliminating post-appear stutter!
 */
object PopupBarRenderer {

    private val mediumTypeface: Typeface by lazy {
        Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
    private fun dpF(context: Context, v: Float): Float = v * context.resources.displayMetrics.density

    /**
     * HorizontalScrollView that wraps tightly to its content width up to [maxWidthPx],
     * avoiding `width = 0, weight = 1f` measurement discrepancies between offscreen pre-measure
     * and attached WindowManager layout (eliminates Frame-1 horizontal jump!).
     */
    private class CappedHorizontalScrollView(
        context: Context,
        private val maxWidthPx: Int
    ) : HorizontalScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val capped = MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.AT_MOST)
            super.onMeasure(capped, heightMeasureSpec)
        }
    }

    fun render(
        context: Context,
        container: LinearLayout,
        actions: List<PopAction>,
        pal: ThemePalette,
        iconsOnly: Boolean,
        isDrawerOpen: Boolean,
        maxBarWidthPx: Int,
        onActionClick: (PopAction) -> Unit,
        onActionLongClick: (PopAction) -> Unit,
        onToggleMore: () -> Unit
    ) {
        container.removeAllViews()

        val outerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 4), 0, dp(context, 4), 0)
        }

        val itemsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Reserve exact chrome width for outer padding (8dp) + divider (5dp) + overflow button (44dp)
        val overflowChromePx = dp(context, 57)
        val maxScrollWidthPx = (maxBarWidthPx - overflowChromePx).coerceAtLeast(dp(context, 120))

        val scroll = CappedHorizontalScrollView(context, maxScrollWidthPx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(itemsRow)
        }

        val iconSize = dp(context, 20)

        actions.forEach { act ->
            val fg = if (act.id == "apply_computed" || act.id == "undo") pal.accentPrimaryInt else pal.fgPrimaryInt
            val showAsIconOnly = iconsOnly && act.id != "apply_computed"

            if (showAsIconOnly) {
                itemsRow.addView(
                    makeIconCell(
                        context = context,
                        action = act,
                        tintColor = fg,
                        pal = pal,
                        iconSize = iconSize,
                        onLongClick = { onActionLongClick(act) }
                    ) { onActionClick(act) }
                )
            } else {
                itemsRow.addView(
                    makeTextCell(
                        context = context,
                        action = act,
                        fgColor = fg,
                        pal = pal,
                        iconSize = dp(context, 16),
                        onLongClick = { onActionLongClick(act) }
                    ) { onActionClick(act) }
                )
            }
        }

        outerRow.addView(scroll)

        outerRow.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 1), dp(context, 20)).apply {
                marginStart = dp(context, 2)
                marginEnd = dp(context, 2)
            }
            setBackgroundColor(pal.dividerInt)
        })

        val overflowTint = if (isDrawerOpen) pal.accentPrimaryInt else pal.fgPrimaryInt
        outerRow.addView(ImageView(context).apply {
            setImageDrawable(ActionIconFactory.createOverflowIcon(overflowTint, iconSize))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "More actions"
            layoutParams = LinearLayout.LayoutParams(dp(context, 44), dp(context, 48))
            background = pillItemBackground(context, pal)
            setOnClickListener {
                runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                onToggleMore()
            }
        })

        container.addView(outerRow)
    }

    private fun makeIconCell(
        context: Context,
        action: PopAction,
        tintColor: Int,
        pal: ThemePalette,
        iconSize: Int,
        onLongClick: () -> Unit,
        onClick: () -> Unit
    ): ImageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = action.label
        layoutParams = LinearLayout.LayoutParams(dp(context, 46), dp(context, 48))
        background = pillItemBackground(context, pal)
        // Update image drawable directly in place when favicon arrives — zero window relayout!
        setImageDrawable(
            ActionIconFactory.createForAction(context, action, tintColor, iconSize) {
                if (isAttachedToWindow) {
                    setImageDrawable(ActionIconFactory.createForAction(context, action, tintColor, iconSize))
                }
            }
        )
        setOnClickListener {
            runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
            onClick()
        }
        setOnLongClickListener {
            runCatching { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
            onLongClick()
            true
        }
    }

    private fun makeTextCell(
        context: Context,
        action: PopAction,
        fgColor: Int,
        pal: ThemePalette,
        iconSize: Int,
        onLongClick: () -> Unit,
        onClick: () -> Unit
    ): View {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(context, 48)
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            background = pillItemBackground(context, pal)
            setOnClickListener {
                runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                onClick()
            }
            setOnLongClickListener {
                runCatching { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
                onLongClick()
                true
            }
        }

        if (action.id != "apply_computed") {
            val iconView = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    marginEnd = dp(context, 6)
                }
            }
            iconView.setImageDrawable(
                ActionIconFactory.createForAction(context, action, fgColor, iconSize) {
                    if (iconView.isAttachedToWindow) {
                        iconView.setImageDrawable(ActionIconFactory.createForAction(context, action, fgColor, iconSize))
                    }
                }
            )
            cell.addView(iconView)
        }

        cell.addView(TextView(context).apply {
            text = action.label
            typeface = mediumTypeface
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(fgColor)
            isSingleLine = true
            gravity = Gravity.CENTER
        })
        return cell
    }

    private fun pillItemBackground(context: Context, pal: ThemePalette): StateListDrawable =
        StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                GradientDrawable().apply {
                    setColor(pal.bgPressedInt)
                    cornerRadius = dpF(context, 20f)
                }
            )
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }
}
