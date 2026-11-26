package io.github.selclip.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import io.github.selclip.SelAccessibilityService
import io.github.selclip.data.PrefsStore

/**
 * Tab 1 (Home): Service status & battery health card, interactive context-bar sandbox, and tips.
 */
class HomeTab(
    private val activity: Activity,
    private val onStatusChanged: () -> Unit = {}
) {

    private lateinit var statusCard: LinearLayout
    private lateinit var batteryCard: LinearLayout
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
                ).apply { bottomMargin = dp(6) }
            }
            addView(statusCard)

            batteryCard = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }
            addView(batteryCard)

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
                "Price" to "$49.99",
                "OTP" to "Your verification code is 482910"
            ).forEach { (title, sample) ->
                chipRow1.addView(UiKit.equalPill(activity, title, p.accentInfoInt) { triggerSample(sample) })
            }
            listOf(
                "Math" to "15% of 2,400",
                "Dirty URL" to "https://example.com/item?utm_source=ads&fbclid=99",
                "Word" to "serendipity"
            ).forEach { (title, sample) ->
                chipRow2.addView(UiKit.equalPill(activity, title, p.accentPrimaryInt) { triggerSample(sample) })
            }

            addView(chipRow1)
            addView(chipRow2)

            sandboxInput = UiKit.inputField(activity, "Select any text here…", singleLine = false, mono = false).apply {
                setText(
                    "Phone: 9841234567   Price: $49.99\n" +
                        "Your verification code is 482910\n" +
                        "Math: 15% of 2,400   Word: serendipity\n" +
                        "URL: https://example.com/item?utm_source=ads&fbclid=99"
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
                addView(UiKit.text(activity, "• Add the Sel Quick Settings tile in your notification shade to pause or resume in 1 tap.", 12.5f, p.fgPrimaryInt).apply {
                    setPadding(0, dp(6), 0, 0)
                })
                addView(UiKit.text(activity, "• In Icons Only mode, long-press any tool icon to preview its name safely without running it.", 12.5f, p.fgPrimaryInt).apply {
                    setPadding(0, dp(6), 0, 0)
                })
            }
            addView(tipsCard)

            addView(UiKit.sectionTitle(activity, "Browser Auto-Popup Tips"))
            val browserCard = UiKit.card(activity).apply {
                addView(UiKit.text(activity, "• Firefox: Works automatically on the first selection.", 12.5f, p.fgPrimaryInt))
                addView(UiKit.text(activity, "• Vivaldi (Android 16.0): Disable vivaldi://flags/#enable-accessibility-extended-selection for automatic popups.", 12.5f, p.fgPrimaryInt).apply {
                    setPadding(0, dp(6), 0, 0)
                })
                addView(UiKit.text(activity, "• WebLibre: Set accessibility.force_disabled to 0 in about:config.", 12.5f, p.fgPrimaryInt).apply {
                    setPadding(0, dp(6), 0, 0)
                })
                addView(UiKit.text(activity, "• Chrome: Tap \"Sel\" in Android's selection menu for the instant floating bar.", 12.5f, p.fgPrimaryInt).apply {
                    setPadding(0, dp(6), 0, 0)
                })
            }
            addView(browserCard)
        }
    }

    fun refresh() {
        if (!::statusCard.isInitialized) return
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        val active = SelAccessibilityService.instance != null
        val paused = PrefsStore.isServicePaused(activity)
        val warnColor = if (p.isDark) Color.parseColor("#F5B041") else Color.parseColor("#C47F00")

        statusCard.removeAllViews()
        val borderStroke = when {
            !active -> p.dangerInt
            paused -> warnColor
            else -> p.accentSuccessInt
        }
        statusCard.background = GradientDrawable().apply {
            setColor(p.bgSurfaceInt)
            setStroke(dp(1), borderStroke)
            cornerRadius = UiKit.dpF(activity, 12f)
        }
        statusCard.setPadding(dp(14), dp(12), dp(14), dp(12))

        when {
            active && !paused -> {
                statusCard.addView(UiKit.text(activity, "●  Service Active", 14f, p.accentSuccessInt, bold = true))
                statusCard.addView(
                    UiKit.text(activity, "Select text in any app to open the floating action bar.", 12.5f, p.fgMutedInt)
                        .apply { setPadding(0, dp(3), 0, 0) }
                )
            }
            active && paused -> {
                statusCard.addView(UiKit.text(activity, "⏸  Paused via Quick Settings", 14f, warnColor, bold = true))
                statusCard.addView(
                    UiKit.text(activity, "Auto-popup is temporarily paused (tapping \"Sel\" in the selection menu still works).", 12.5f, p.fgPrimaryInt)
                        .apply { setPadding(0, dp(4), 0, dp(6)) }
                )
                statusCard.addView(UiKit.fullButton(activity, "Resume Sel", p.accentSuccessInt, filled = true) {
                    PrefsStore.setServicePaused(activity, false)
                    SelAccessibilityService.instance?.onPausedStateChanged(false)
                    onStatusChanged()
                    refresh()
                })
            }
            else -> {
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

        // OEM Battery Optimization health check (catches HyperOS / OneUI PowerKeeper freezes)
        val pm = activity.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryUnrestricted = runCatching {
            pm?.isIgnoringBatteryOptimizations(activity.packageName) == true
        }.getOrDefault(true)

        batteryCard.removeAllViews()
        if (!batteryUnrestricted) {
            batteryCard.visibility = View.VISIBLE
            batteryCard.background = GradientDrawable().apply {
                setColor(p.bgSurfaceInt)
                setStroke(dp(1), warnColor)
                cornerRadius = UiKit.dpF(activity, 12f)
            }
            batteryCard.setPadding(dp(14), dp(12), dp(14), dp(12))
            batteryCard.addView(UiKit.text(activity, "⚠  Battery Optimization Enabled", 13.5f, warnColor, bold = true))
            batteryCard.addView(
                UiKit.text(
                    activity,
                    "HyperOS / OEM battery savers may freeze Sel in the background. Set Battery to \"No restrictions\" / \"Unrestricted\".",
                    12.2f,
                    p.fgMutedInt
                ).apply { setPadding(0, dp(4), 0, dp(6)) }
            )
            batteryCard.addView(UiKit.fullButton(activity, "Open App Battery Settings", warnColor, filled = false) {
                val appInfoIntent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.packageName, null)
                )
                runCatching { activity.startActivity(appInfoIntent) }
                    .onFailure {
                        runCatching { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }
            })
        } else {
            batteryCard.visibility = View.GONE
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
