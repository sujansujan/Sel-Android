package io.github.selclip

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File

/**
 * Tab 5 ([CLIPS]): Ring-buffer clipboard history (max 20, supports pinning) & scratchpad notes log.
 */
class ClipsTabController(private val activity: Activity) {

    private lateinit var clipListContainer: LinearLayout
    private lateinit var logView: EditText

    fun buildView(): View {
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            addView(UiKit.label(activity, "01 // [≡] CLIPBOARD HISTORY (MAX 20)", 11.5f, p.fgSecondaryInt, bold = true))
            addView(
                UiKit.label(
                    activity,
                    "Tap any clip to copy it, tap [*] to pin it forever, or save your current clipboard below:",
                    10.5f,
                    p.fgMutedInt
                ).apply { setPadding(0, dp(2), 0, dp(6)) }
            )

            val clipBtnRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(6))
            }
            clipBtnRow.addView(UiKit.equalButtonPill(activity, "[ + SAVE CURRENT CLIP ]", p.accentGreenInt) {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString()?.trim().orEmpty()
                if (clip.isNotEmpty()) {
                    ToolPrefs.addClipToHistory(activity, clip)
                    refreshClipHistory()
                    Toast.makeText(activity, "✓ Saved clipboard to history", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "Clipboard is currently empty", Toast.LENGTH_SHORT).show()
                }
            })
            clipBtnRow.addView(UiKit.equalButtonPill(activity, "[ × CLEAR UNPINNED ]", p.dangerRedInt) {
                ToolPrefs.clearUnpinnedClips(activity)
                refreshClipHistory()
            })
            addView(clipBtnRow)

            clipListContainer = UiKit.surfaceCard(activity)
            addView(clipListContainer)

            addView(UiKit.label(activity, "\n02 // [>>] SCRATCHPAD LOG (NOTES)", 11.5f, p.fgSecondaryInt, bold = true))
            val logBtnRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(6))
            }
            logBtnRow.addView(UiKit.equalButtonPill(activity, "[ ✓ SAVE NOTES ]", p.accentGreenInt) {
                File(activity.filesDir, "scratchpad.txt").writeText(logView.text.toString())
                Toast.makeText(activity, "✓ Scratchpad saved", Toast.LENGTH_SHORT).show()
            })
            logBtnRow.addView(UiKit.equalButtonPill(activity, "[ [^C] COPY ALL ]", p.accentCyanInt) {
                UiKit.copyPlain(activity, logView.text.toString(), "Copied scratchpad log")
            })
            logBtnRow.addView(UiKit.equalButtonPill(activity, "[ × CLEAR ]", p.dangerRedInt) {
                File(activity.filesDir, "scratchpad.txt").writeText("")
                logView.setText("")
            })
            addView(logBtnRow)

            logView = EditText(activity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11.8f
                setTextColor(p.fgPrimaryInt)
                background = GradientDrawable().apply {
                    setColor(p.bgSurfaceInt)
                    setStroke(dp(1), p.borderInt)
                    cornerRadius = dp(2).toFloat()
                }
                setPadding(dp(12), dp(12), dp(12), dp(12))
                minLines = 7
                gravity = Gravity.TOP or Gravity.START
                hint = "Scratchpad is empty — type notes here or use APPEND LOG in the popup…"
                setHintTextColor(p.fgMutedInt)
            }
            addView(logView)
        }
    }

    fun refresh() {
        val logFile = File(activity.filesDir, "scratchpad.txt")
        if (::logView.isInitialized) {
            val currentTxt = if (logFile.exists()) logFile.readText() else ""
            logView.setText(if (currentTxt == "(Empty)") "" else currentTxt)
        }
        if (::clipListContainer.isInitialized) refreshClipHistory()
    }

    private fun refreshClipHistory() {
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        clipListContainer.removeAllViews()
        val clips = ToolPrefs.getClipHistory(activity)
        if (clips.isEmpty()) {
            clipListContainer.addView(
                UiKit.label(
                    activity,
                    "(No recent clips yet — copy text anywhere or tap [ + SAVE CURRENT CLIP ])",
                    10.5f,
                    p.fgMutedInt
                )
            )
            return
        }
        clips.forEach { entry ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(2)
                    bottomMargin = dp(2)
                }
                background = GradientDrawable().apply {
                    setColor(p.bgPrimaryInt)
                    setStroke(dp(1), p.dividerInt)
                    cornerRadius = dp(2).toFloat()
                }
            }

            row.addView(UiKit.label(
                activity,
                if (entry.pinned) "[★]" else "[·]",
                11.5f,
                if (entry.pinned) p.accentGreenInt else p.fgMutedInt,
                bold = true
            ).apply {
                setPadding(dp(4), dp(4), dp(10), dp(4))
                setOnClickListener {
                    ToolPrefs.togglePinClip(activity, entry.text)
                    refreshClipHistory()
                }
            })

            row.addView(UiKit.label(activity, entry.text.replace("\n", " ↵ "), 11.5f, p.fgPrimaryInt).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { UiKit.copyPlain(activity, entry.text, "[^C] Copied clip") }
            })

            row.addView(UiKit.label(activity, "[ × ]", 12f, p.dangerRedInt, bold = true).apply {
                setPadding(dp(10), dp(4), dp(4), dp(4))
                setOnClickListener {
                    ToolPrefs.deleteClip(activity, entry.text)
                    refreshClipHistory()
                }
            })

            clipListContainer.addView(row)
        }
    }
}
