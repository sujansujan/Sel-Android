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
 * Renders the floating bar to look and feel indistinguishable from Android's native
 * Material 3 FloatingToolbarPopup:
 * - Clean single-row 48dp native pill with smooth horizontal scrolling when needed.
 * - Supports both Native Text Mode (crisp sans-serif-medium 14sp + portal favicons) and
 *   Icons-Only Mode (razor-sharp 20dp native vector icons & auto-fetched search favicons).
 * - Right-aligned native ⋮ overflow button matching Android's system popup.
 */
object PopupBarRenderer {

    private fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
    private fun dpF(context: Context, v: Float): Float = v * context.resources.displayMetrics.density

    fun render(
        context: Context,
        container: LinearLayout,
        actions: List<PopAction>,
        pal: ThemePalette,
        iconsOnly: Boolean,
        isDrawerOpen: Boolean,
        onActionClick: (PopAction) -> Unit,
        onActionLongClick: (PopAction) -> Unit,
        onToggleMore: () -> Unit,
        onFaviconLoaded: () -> Unit
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

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(itemsRow)
        }

        val iconSize = dp(context, 20)

        actions.forEach { act ->
            val fg = if (act.accent) pal.accentPrimaryInt else pal.fgPrimaryInt
            // Computed values (e.g., "= 1740") always show their text so the user can read the answer!
            val showAsIconOnly = iconsOnly && act.id != "apply_computed"

            if (showAsIconOnly) {
                itemsRow.addView(
                    makeIconCell(
                        context = context,
                        action = act,
                        tintColor = fg,
                        pal = pal,
                        iconSize = iconSize,
                        onFaviconLoaded = onFaviconLoaded,
                        onLongClick = { onActionLongClick(act) }
                    ) {
                        onActionClick(act)
                    }
                )
            } else {
                itemsRow.addView(
                    makeTextCell(
                        context = context,
                        action = act,
                        fgColor = fg,
                        pal = pal,
                        iconSize = dp(context, 16),
                        onFaviconLoaded = onFaviconLoaded,
                        onLongClick = { onActionLongClick(act) }
                    ) {
                        onActionClick(act)
                    }
                )
            }
        }

        outerRow.addView(scroll)

        // Subtle hairline divider + native Android ⋮ overflow button
        outerRow.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 1), dp(context, 22)).apply {
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
        onFaviconLoaded: () -> Unit,
        onLongClick: () -> Unit,
        onClick: () -> Unit
    ): ImageView = ImageView(context).apply {
        setImageDrawable(ActionIconFactory.createForAction(context, action, tintColor, iconSize, onFaviconLoaded))
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = action.label
        layoutParams = LinearLayout.LayoutParams(dp(context, 46), dp(context, 48))
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

    private fun makeTextCell(
        context: Context,
        action: PopAction,
        fgColor: Int,
        pal: ThemePalette,
        iconSize: Int,
        onFaviconLoaded: () -> Unit,
        onLongClick: () -> Unit,
        onClick: () -> Unit
    ): View {
        val hasPortalFavicon = !action.faviconUrl.isNullOrBlank()
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(context, 48)
            setPadding(dp(context, 13), dp(context, 10), dp(context, 13), dp(context, 10))
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

        if (hasPortalFavicon) {
            val icon = ActionIconFactory.createForAction(context, action, fgColor, iconSize, onFaviconLoaded)
            cell.addView(ImageView(context).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    marginEnd = dp(context, 6)
                }
            })
        }

        cell.addView(TextView(context).apply {
            text = action.label
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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
