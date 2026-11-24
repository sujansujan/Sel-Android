package io.github.selclip

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Minimal, cohesive ASCII aesthetic UI builders (Dracula Dark / Alucard Light).
 * Crisp monospace typography, 1dp hairline borders, generous touch padding, zero clutter.
 */
object UiKit {

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun pal(context: Context): ThemePalette = ToolPrefs.resolvePalette(context)

    fun label(
        context: Context,
        content: String,
        sizeSp: Float,
        colorInt: Int,
        bold: Boolean = false
    ): TextView {
        return TextView(context).apply {
            text = content
            textSize = sizeSp
            typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
            setTextColor(colorInt)
        }
    }

    fun sectionTitle(context: Context, title: String): TextView {
        val p = pal(context)
        return label(context, "-- $title --", 11f, p.accentPurpleInt, bold = true).apply {
            setPadding(0, dp(context, 12), 0, dp(context, 6))
        }
    }

    fun buttonPill(
        context: Context,
        content: String,
        colorInt: Int,
        onClick: () -> Unit
    ): TextView {
        val p = pal(context)
        return label(context, content, 11f, colorInt, bold = true).apply {
            gravity = Gravity.CENTER
            isSingleLine = true
            setPadding(dp(context, 12), dp(context, 9), dp(context, 12), dp(context, 9))
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    GradientDrawable().apply {
                        setColor(p.bgPressedInt)
                        setStroke(dp(context, 1), colorInt)
                        cornerRadius = dp(context, 2).toFloat()
                    }
                )
                addState(
                    intArrayOf(),
                    GradientDrawable().apply {
                        setColor(p.bgSurfaceInt)
                        setStroke(dp(context, 1), p.borderInt)
                        cornerRadius = dp(context, 2).toFloat()
                    }
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(context, 3)
                bottomMargin = dp(context, 3)
            }
            setOnClickListener { onClick() }
        }
    }

    fun equalButtonPill(
        context: Context,
        content: String,
        colorInt: Int,
        selected: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        val p = pal(context)
        return label(context, content, 10.5f, if (selected) p.onAccentInt else colorInt, bold = true).apply {
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(context, 6), dp(context, 9), dp(context, 6), dp(context, 9))
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    GradientDrawable().apply {
                        setColor(p.bgPressedInt)
                        cornerRadius = dp(context, 2).toFloat()
                    }
                )
                addState(
                    intArrayOf(),
                    GradientDrawable().apply {
                        setColor(if (selected) colorInt else p.bgSurfaceInt)
                        setStroke(dp(context, 1), if (selected) colorInt else p.borderInt)
                        cornerRadius = dp(context, 2).toFloat()
                    }
                )
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                topMargin = dp(context, 3)
                bottomMargin = dp(context, 3)
                marginStart = dp(context, 2)
                marginEnd = dp(context, 2)
            }
            setOnClickListener { onClick() }
        }
    }

    fun surfaceCard(context: Context, padH: Int = 10, padV: Int = 8): LinearLayout {
        val p = pal(context)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(p.bgSurfaceInt)
                setStroke(dp(context, 1), p.borderInt)
                cornerRadius = dp(context, 2).toFloat()
            }
            setPadding(dp(context, padH), dp(context, padV), dp(context, padH), dp(context, padV))
        }
    }

    fun rowDrawable(context: Context): StateListDrawable {
        val p = pal(context)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(p.bgPressedInt) })
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }
    }

    fun copyPlain(context: Context, text: String, toastMsg: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("sel", text))
        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
    }
}
