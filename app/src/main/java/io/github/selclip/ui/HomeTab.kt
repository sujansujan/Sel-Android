package io.github.selclip.ui

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import io.github.selclip.SelAccessibilityService

/**
 * Tab 1 (Home): Service status card, interactive context-bar sandbox, and quick tips.
 */
class HomeTab(private val activity: Activity) {

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
                ).apply { bottomMargin = dp(4) }
            }
            addView(statusCard)

            addView(UiKit.sectionTitle(activity, "Try Context Bar"))
            addView(
                UiKit.text(
                    activity,
                    "Tap a sample or highlight text below to preview how the floating bar adapts automatically:",
                    12.5f,
                    p.fgMutedInt
                ).apply { setPadding(dp(2), 0, dp(2), dp(8)) }
            )

            val chipRow1 = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val chipRow2 = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(8))
            }

            listOf(
                "Phone" to "9841234567",
                "Word" to "serendipity",
                "Math" to "145 * 12"
            ).forEach { (title, sample) ->
                chipRow1.addView(UiKit.equalPill(activity, title, p.accentInfoInt) { triggerSample(sample) })
            }
            listOf(
                "Dirty URL" to "https://example.com/item?utm_source=ads&fbclid=99",
                "Torrent Hash" to "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"
            ).forEach { (title, sample) ->
                chipRow2.addView(UiKit.equalPill(activity, title, p.accentPrimaryInt) { triggerSample(sample) })
            }

            addView(chipRow1)
            addView(chipRow2)

            sandboxInput = UiKit.inputField(activity, "Select any text here…", singleLine = false, mono = false).apply {
                setText(
                    "Phone: 9841234567   Word: serendipity\n" +
                        "Math: 145 * 12      Unit: 72 F\n" +
                        "URL: https://example.com/item?utm_source=ads&fbclid=99\n" +
                        "Hash: dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"
                )
                setLineSpacing(0f, 1.25f)
            }
            addView(sandboxInput)

            addView(UiKit.sectionTitle(activity, "Quick Tips"))
            val tipsCard = UiKit.card(activity).apply {
                addView(UiKit.text(activity, "• Docks directly below Android's native selection popup in browsers and apps.", 12.5f, p.fgPrimaryInt))
                addView(UiKit.text(activity, "• Tap Search or YT for instant lookup; long-press either for all engines or time filters.", 12.5f, p.fgPrimaryInt).apply {
                    setPadding(0, dp(6), 0, 0)
                })
                addView(UiKit.text(activity, "• Switch to Icons Only in Settings to use crisp icons and auto-fetched site favicons.", 12.5f, p.fgPrimaryInt).apply {
                    setPadding(0, dp(6), 0, 0)
                })
            }
            addView(tipsCard)
        }
    }

    fun refresh() {
        if (!::statusCard.isInitialized) return
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        val active = SelAccessibilityService.instance != null
        statusCard.removeAllViews()

        statusCard.background = GradientDrawable().apply {
            setColor(p.bgSurfaceInt)
            setStroke(dp(1), if (active) p.accentSuccessInt else p.dangerInt)
            cornerRadius = UiKit.dpF(activity, 12f)
        }
        statusCard.setPadding(dp(14), dp(12), dp(14), dp(12))

        if (active) {
            statusCard.addView(UiKit.text(activity, "●  Service Active", 14f, p.accentSuccessInt, bold = true))
            statusCard.addView(
                UiKit.text(activity, "Select text in any app to open the floating action bar.", 12.5f, p.fgMutedInt)
                    .apply { setPadding(0, dp(3), 0, 0) }
            )
        } else {
            statusCard.addView(UiKit.text(activity, "Accessibility Service Off", 14f, p.dangerInt, bold = true))
            statusCard.addView(
                UiKit.text(activity, "Enable Sel in Android Accessibility Settings to activate floating selection actions.", 12.5f, p.fgPrimaryInt)
                    .apply { setPadding(0, dp(4), 0, dp(6)) }
            )
            statusCard.addView(UiKit.fullButton(activity, "Open Accessibility Settings", p.accentSuccessInt, filled = true) {
                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        }
    }

    private fun triggerSample(sample: String) {
        val full = sandboxInput.text?.toString().orEmpty()
        val idx = full.indexOf(sample)
        if (idx >= 0) {
            sandboxInput.requestFocus()
            sandboxInput.setSelection(idx, idx + sample.length)
        }
        val svc = SelAccessibilityService.instance
        if (svc != null) {
            svc.showFromExternalText(sample)
        } else {
            Toast.makeText(activity, "Enable Sel in Accessibility Settings first", Toast.LENGTH_SHORT).show()
        }
    }
}
