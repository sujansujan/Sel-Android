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
import android.widget.TextView
import android.widget.Toast

/**
 * Tab 1 ([HOME]): Smart status/onboarding banner, 1-tap sandbox chips, and Screen Grab + OCR launcher.
 */
class HomeTabController(
    private val activity: Activity,
    private val onOpenSettingsTab: () -> Unit
) {
    private lateinit var onboardingCard: LinearLayout
    private lateinit var sandboxInput: EditText

    fun buildView(): View {
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            onboardingCard = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            addView(onboardingCard)

            addView(UiKit.label(activity, "01 // INTERACTIVE TEST SANDBOX", 11.5f, p.fgSecondaryInt, bold = true))
            addView(
                UiKit.label(
                    activity,
                    "Tap any sample chip below to spawn the popup in 1 tap, or highlight any word/number in the box:",
                    10.5f,
                    p.fgMutedInt
                ).apply { setPadding(0, dp(2), 0, dp(6)) }
            )

            val sandboxText =
                "Nepal Phone (Call/SMS/WA/W4B): 9841234567  or  +977 9801234567\n" +
                    "Word (Define [?=]): serendipity\n" +
                    "Torrent Hash (Magnet [0x]): dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c\n" +
                    "Math / Unit: 145 * 12   or   72 F   or   0xFF\n" +
                    "Unix Timestamp: 1730000000\n" +
                    "Base64: aGVsbG8gd29ybGQ=\n" +
                    "Dirty URL ([?x]): https://example.com/item?utm_source=ads&fbclid=123"

            val sampleChipsRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(6))
            }

            val samples = listOf(
                "[PHONE]" to "9841234567",
                "[WORD]" to "serendipity",
                "[MATH]" to "145 * 12",
                "[URL]" to "https://example.com/item?utm_source=ads&fbclid=123",
                "[HASH]" to "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"
            )

            samples.forEach { (chipTitle, sampleVal) ->
                val chip = TextView(activity).apply {
                    text = chipTitle
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    textSize = 10f
                    setTextColor(p.accentCyanInt)
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    background = GradientDrawable().apply {
                        setColor(p.bgSurfaceInt)
                        setStroke(dp(1), p.borderInt)
                        cornerRadius = dp(2).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(3)
                    }
                    setOnClickListener { triggerSandboxSample(sampleVal) }
                }
                sampleChipsRow.addView(chip)
            }
            addView(sampleChipsRow)

            sandboxInput = EditText(activity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11.8f
                setTextColor(p.fgPrimaryInt)
                background = GradientDrawable().apply {
                    setColor(p.bgSurfaceInt)
                    setStroke(dp(1), p.borderInt)
                    cornerRadius = dp(2).toFloat()
                }
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setText(sandboxText)
            }
            addView(sandboxInput)

            addView(UiKit.label(activity, "\nLens Screen Text Grab", 13f, p.fgSecondaryInt, bold = true))
            addView(
                UiKit.label(
                    activity,
                    "Works just like Google Lens: freezes your screen, detects every word via on-device OCR, and lets you tap, sweep-select, or drag teardrop handles to copy, search, translate, or listen.",
                    11f,
                    p.fgMutedInt
                ).apply { setPadding(0, dp(3), 0, dp(8)) }
            )

            val automationBox = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                background = GradientDrawable().apply {
                    setColor(p.bgSurfaceInt)
                    setStroke(dp(1), p.dividerInt)
                    cornerRadius = dp(2).toFloat()
                }
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }

                addView(
                    UiKit.label(
                        activity,
                        "3RD-PARTY GESTURE / TASKER / MACRODROID TRIGGERS:\n" +
                            "• Action:    io.github.selclip.ACTION_GRAB_SCREEN\n" +
                            "• Activity:  io.github.selclip.ScreenGrabTriggerActivity\n" +
                            "• Deep Link: sel://grab",
                        10f,
                        p.fgPrimaryInt
                    )
                )
                addView(
                    UiKit.buttonPill(activity, "[ [^C] COPY INTENT ACTION ]", p.accentCyanInt) {
                        UiKit.copyPlain(activity, "io.github.selclip.ACTION_GRAB_SCREEN", "Copied intent action")
                    }
                )
            }

            val rowBtns = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            rowBtns.addView(
                UiKit.equalButtonPill(activity, "[ [+] LAUNCH LENS GRAB ]", p.accentGreenInt) {
                    activity.startActivity(Intent(activity, ScreenGrabTriggerActivity::class.java))
                }
            )
            lateinit var toggleAutoBtn: TextView
            toggleAutoBtn = UiKit.equalButtonPill(activity, "[ ? AUTOMATION ▾ ]", p.fgMutedInt) {
                val show = automationBox.visibility != View.VISIBLE
                automationBox.visibility = if (show) View.VISIBLE else View.GONE
                toggleAutoBtn.text = if (show) "[ ? AUTOMATION ▴ ]" else "[ ? AUTOMATION ▾ ]"
            }
            rowBtns.addView(toggleAutoBtn)

            addView(rowBtns)
            addView(automationBox)
        }
    }

    fun refresh() {
        if (!::onboardingCard.isInitialized) return
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        val active = SelAccessibilityService.instance != null
        onboardingCard.removeAllViews()

        if (!active) {
            onboardingCard.background = GradientDrawable().apply {
                setColor(p.bgSurfaceInt)
                setStroke(dp(1), p.dangerRedInt)
                cornerRadius = dp(3).toFloat()
            }
            onboardingCard.setPadding(dp(12), dp(10), dp(12), dp(10))
            onboardingCard.addView(UiKit.label(activity, "[ ! ] STEP 1: ENABLE ACCESSIBILITY SERVICE", 11.5f, p.dangerRedInt, bold = true))
            onboardingCard.addView(
                UiKit.label(
                    activity,
                    "Turn on [sel] Plain-Text Palette in Android Accessibility Settings so the popup appears whenever you select text.",
                    10.2f,
                    p.fgPrimaryInt
                ).apply { setPadding(0, dp(3), 0, dp(6)) }
            )
            onboardingCard.addView(
                UiKit.buttonPill(activity, "[ ↗ ENABLE IN ACCESSIBILITY SETTINGS ]", p.accentGreenInt) {
                    activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            )
        } else {
            onboardingCard.background = GradientDrawable().apply {
                setColor(p.bgSurfaceInt)
                setStroke(dp(1), p.borderInt)
                cornerRadius = dp(3).toFloat()
            }
            onboardingCard.setPadding(dp(10), dp(7), dp(10), dp(7))
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                UiKit.label(activity, "● [sel_] is active. Select text anywhere or tap a chip below:", 10.2f, p.accentGreenInt).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            row.addView(
                UiKit.label(activity, "[SET ↗]", 10f, p.accentPurpleInt, bold = true).apply {
                    setPadding(dp(6), dp(2), dp(2), dp(2))
                    setOnClickListener { onOpenSettingsTab() }
                }
            )
            onboardingCard.addView(row)
        }
    }

    private fun triggerSandboxSample(sample: String) {
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
            Toast.makeText(
                activity,
                "Enable [sel] in Accessibility Settings first (tap [ ○ ENABLE ↗ ] at top right)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
