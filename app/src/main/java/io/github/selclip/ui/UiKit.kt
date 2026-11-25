package io.github.selclip.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.selclip.data.PrefsStore
import io.github.selclip.data.ThemePalette

/**
 * Lightweight native Android View UI kit designed for clean, modern mobile ergonomics:
 * smooth rounded surfaces, clear typographic hierarchy, and generous 44–48dp touch targets.
 */
object UiKit {

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
    fun dpF(context: Context, v: Float): Float = v * context.resources.displayMetrics.density
    fun pal(context: Context): ThemePalette = PrefsStore.resolvePalette(context)

    fun text(
        context: Context,
        content: String,
        sizeSp: Float,
        colorInt: Int,
        bold: Boolean = false,
        mono: Boolean = false
    ): TextView = TextView(context).apply {
        text = content
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = when {
            mono && bold -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            mono -> Typeface.MONOSPACE
            bold -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            else -> Typeface.SANS_SERIF
        }
        setTextColor(colorInt)
    }

    fun sectionTitle(context: Context, title: String): TextView {
        val p = pal(context)
        return text(context, title, 13.5f, p.fgSecondaryInt, bold = true).apply {
            setPadding(dp(context, 2), dp(context, 16), dp(context, 2), dp(context, 6))
        }
    }

    fun card(context: Context, padH: Int = 14, padV: Int = 12): LinearLayout {
        val p = pal(context)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(p.bgSurfaceInt)
                setStroke(dp(context, 1), p.borderInt)
                cornerRadius = dpF(context, 12f)
            }
            setPadding(dp(context, padH), dp(context, padV), dp(context, padH), dp(context, padV))
        }
    }

    fun inputField(
        context: Context,
        hintText: String,
        singleLine: Boolean = true,
        mono: Boolean = false
    ): EditText {
        val p = pal(context)
        return EditText(context).apply {
            typeface = if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (mono) 12f else 13.5f)
            setTextColor(p.fgPrimaryInt)
            setHintTextColor(p.fgMutedInt)
            hint = hintText
            isSingleLine = singleLine
            background = GradientDrawable().apply {
                setColor(p.bgInputInt)
                setStroke(dp(context, 1), p.borderInt)
                cornerRadius = dpF(context, 10f)
            }
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        }
    }

    fun fullButton(
        context: Context,
        label: String,
        accentColor: Int,
        filled: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        val p = pal(context)
        return text(context, label, 13.5f, if (filled) p.onAccentInt else accentColor, bold = true).apply {
            gravity = Gravity.CENTER
            isSingleLine = true
            minHeight = dp(context, 46)
            setPadding(dp(context, 14), dp(context, 11), dp(context, 14), dp(context, 11))
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    GradientDrawable().apply {
                        setColor(p.bgPressedInt)
                        setStroke(dp(context, 1), accentColor)
                        cornerRadius = dpF(context, 10f)
                    }
                )
                addState(
                    intArrayOf(),
                    GradientDrawable().apply {
                        setColor(if (filled) accentColor else p.bgSurfaceInt)
                        setStroke(dp(context, 1), if (filled) accentColor else p.borderInt)
                        cornerRadius = dpF(context, 10f)
                    }
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(context, 6)
                bottomMargin = dp(context, 2)
            }
            setOnClickListener { onClick() }
        }
    }

    fun equalPill(
        context: Context,
        label: String,
        accentColor: Int,
        selected: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        val p = pal(context)
        return text(context, label, 12.5f, if (selected) p.onAccentInt else accentColor, bold = true).apply {
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            minHeight = dp(context, 42)
            setPadding(dp(context, 8), dp(context, 9), dp(context, 8), dp(context, 9))
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    GradientDrawable().apply {
                        setColor(p.bgPressedInt)
                        cornerRadius = dpF(context, 10f)
                    }
                )
                addState(
                    intArrayOf(),
                    GradientDrawable().apply {
                        setColor(if (selected) accentColor else p.bgSurfaceInt)
                        setStroke(dp(context, 1), if (selected) accentColor else p.borderInt)
                        cornerRadius = dpF(context, 10f)
                    }
                )
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                topMargin = dp(context, 3)
                bottomMargin = dp(context, 3)
                marginStart = dp(context, 3)
                marginEnd = dp(context, 3)
            }
            setOnClickListener { onClick() }
        }
    }

    fun copyPlain(context: Context, text: String, toastMsg: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("sel", text))
        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
    }
}
