package io.github.selclip

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Tab 1 ([HOME]): Service status, 1-tap context-aware PopClip sandbox, and Screen Grab trigger.
 */
class HomeTabController(private val activity: Activity) {

    private lateinit var statusCard: LinearLayout
    private lateinit var sandboxInput: EditText

    fun buildView(): View {
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            statusCard = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            addView(statusCard)

            // 1. Live Context-Aware Popup Test
            addView(UiKit.sectionTitle(activity, "TRY CONTEXT POPUP"))
            addView(
                UiKit.label(
                    activity,
                    "Tap a sample or highlight text below — the bar adapts automatically:",
                    10.2f,
                    p.fgMutedInt
                ).apply { setPadding(0, 0, 0, dp(6)) }
            )

            val chipsRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(6))
            }
            val samples = listOf(
                "PHONE" to "9841234567",
                "WORD" to "serendipity",
                "MATH" to "145 * 12",
                "URL" to "https://example.com/item?utm_source=ads&fbclid=99",
                "HASH" to "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"
            )
            samples.forEach { (title, sampleVal) ->
                chipsRow.addView(UiKit.equalButtonPill(activity, "[$title]", p.accentCyanInt) {
                    triggerSample(sampleVal)
                })
            }
            addView(chipsRow)

            sandboxInput = EditText(activity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11.2f
                setTextColor(p.fgPrimaryInt)
                background = GradientDrawable().apply {
                    setColor(p.bgSurfaceInt)
                    setStroke(dp(1), p.borderInt)
                    cornerRadius = dp(2).toFloat()
                }
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setText(
                    "Phone: 9841234567   Word: serendipity\n" +
                        "Math:  145 * 12     Unit: 72 F\n" +
                        "URL:   https://example.com/item?utm_source=ads&fbclid=99\n" +
                        "Hash:  dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"
                )
            }
            addView(sandboxInput)

            // 2. Tap-to-Select Screen Grab
            addView(UiKit.sectionTitle(activity, "SCREEN TEXT GRAB"))
            addView(
                UiKit.label(
                    activity,
                    "Tap any unselectable text on screen to copy or act on it. Add [sel] Grab to your Quick Settings tile for 1-tap access anywhere.",
                    10.2f,
                    p.fgMutedInt
                ).apply { setPadding(0, 0, 0, dp(6)) }
            )
            addView(UiKit.buttonPill(activity, "[ + GRAB TEXT FROM SCREEN ]", p.accentGreenInt) {
                activity.startActivity(Intent(activity, ScreenGrabTriggerActivity::class.java))
            })
        }
    }

    fun refresh() {
        if (!::statusCard.isInitialized) return
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        val active = SelAccessibilityService.instance != null
        statusCard.removeAllViews()

        if (!active) {
            statusCard.background = GradientDrawable().apply {
                setColor(p.bgSurfaceInt)
                setStroke(dp(1), p.dangerRedInt)
                cornerRadius = dp(2).toFloat()
            }
            statusCard.setPadding(dp(10), dp(10), dp(10), dp(10))
            statusCard.addView(UiKit.label(activity, "! ACCESSIBILITY SERVICE OFF", 11.2f, p.dangerRedInt, bold = true))
            statusCard.addView(
                UiKit.label(activity, "Enable [sel] in Android Accessibility Settings to activate selection popups.", 10.2f, p.fgPrimaryInt)
                    .apply { setPadding(0, dp(3), 0, dp(6)) }
            )
            statusCard.addView(UiKit.buttonPill(activity, "[ OPEN ACCESSIBILITY SETTINGS ↗ ]", p.accentGreenInt) {
                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        } else {
            statusCard.background = GradientDrawable().apply {
                setColor(p.bgSurfaceInt)
                setStroke(dp(1), p.borderInt)
                cornerRadius = dp(2).toFloat()
            }
            statusCard.setPadding(dp(10), dp(8), dp(10), dp(8))
            statusCard.addView(UiKit.label(activity, "● ACTIVE · Select text in any app to open [sel]", 10.5f, p.accentGreenInt, bold = true))
        }
    }

    private fun triggerSample(sample: String) {
        val full = sandboxInput.text?.toString() ?: ""
        val idx = full.indexOf(sample)
        if (idx >= 0) {
            sandboxInput.requestFocus()
            sandboxInput.setSelection(idx, idx + sample.length)
        }
        val svc = SelAccessibilityService.instance
        if (svc != null) {
            svc.showFromExternalText(sample)
        } else {
            Toast.makeText(activity, "Enable [sel] in Accessibility Settings first", Toast.LENGTH_SHORT).show()
        }
    }
}
