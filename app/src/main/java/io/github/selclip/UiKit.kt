package io.github.selclip

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Shared plain-text Dracula / Alucard native view builders used across all MainActivity tab controllers.
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

    fun buttonPill(
        context: Context,
        content: String,
        colorInt: Int,
        onClick: () -> Unit
    ): TextView {
        val p = pal(context)
        return label(context, content, 10.8f, colorInt, bold = true).apply {
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
            isSingleLine = true
            background = GradientDrawable().apply {
                setColor(p.bgPrimaryInt)
                setStroke(dp(context, 1), p.borderInt)
                cornerRadius = dp(context, 2).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(context, 3)
                bottomMargin = dp(context, 3)
                marginEnd = dp(context, 6)
            }
            setOnClickListener { onClick() }
        }
    }

    fun equalButtonPill(
        context: Context,
        content: String,
        colorInt: Int,
        onClick: () -> Unit
    ): TextView {
        val p = pal(context)
        return label(context, content, 10.2f, colorInt, bold = true).apply {
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(context, 4), dp(context, 8), dp(context, 4), dp(context, 8))
            background = GradientDrawable().apply {
                setColor(p.bgPrimaryInt)
                setStroke(dp(context, 1), p.borderInt)
                cornerRadius = dp(context, 2).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
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

    fun copyPlain(context: Context, text: String, toastMsg: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("sel", text))
        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
    }

    fun showPlainChoiceDialog(
        activity: Activity,
        title: String,
        items: List<String>,
        onPick: (Int) -> Unit
    ) {
        val p = pal(activity)
        val dialogRoot = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bgPrimaryInt)
            setPadding(dp(activity, 14), dp(activity, 14), dp(activity, 14), dp(activity, 14))
        }
        dialogRoot.addView(label(activity, title, 11.5f, p.accentPurpleInt, bold = true).apply {
            setPadding(0, 0, 0, dp(activity, 8))
        })

        var alert: AlertDialog? = null
        items.forEachIndexed { idx, item ->
            val row = label(activity, "• $item", 11.2f, p.fgPrimaryInt).apply {
                setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 10))
                background = GradientDrawable().apply {
                    setColor(p.bgSurfaceInt)
                    setStroke(dp(activity, 1), p.dividerInt)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(activity, 4)
                }
                setOnClickListener {
                    onPick(idx)
                    alert?.dismiss()
                }
            }
            dialogRoot.addView(row)
        }
        dialogRoot.addView(buttonPill(activity, "[ CANCEL ]", p.fgMutedInt) { alert?.dismiss() })

        alert = AlertDialog.Builder(activity)
            .setView(dialogRoot)
            .create()
        alert.show()
    }
}
