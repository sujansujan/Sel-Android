package io.github.selclip

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Tab 4 ([RULES]): 1-tap popular search presets, visual custom rule builder, and collapsible raw rules.txt editor.
 */
class RulesTabController(
    private val activity: Activity,
    private val onToolsChanged: () -> Unit
) {
    private data class QuickPreset(
        val chipLabel: String,
        val glyph: String,
        val ruleLabel: String,
        val targetUrl: String
    )

    private val popularSearchPresets = listOf(
        QuickPreset("+REDDIT", "RDT", "SEARCH/REDDIT", "https://www.reddit.com/search/?q={text}"),
        QuickPreset("+MAPS", "MAP", "SEARCH/GOOGLE MAPS", "https://www.google.com/maps/search/?api=1&query={text}"),
        QuickPreset("+PERPLEXITY", "PPL", "SEARCH/PERPLEXITY", "https://www.perplexity.ai/search?q={text}"),
        QuickPreset("+GITHUB", "GH", "SEARCH/GITHUB", "https://github.com/search?q={text}"),
        QuickPreset("+IMDB", "IMD", "SEARCH/IMDB", "https://www.imdb.com/find/?q={text}"),
        QuickPreset("+CHATGPT", "GPT", "SEARCH/CHATGPT", "https://chatgpt.com/?q={text}")
    )

    private var selectedRuleDestination: String = "SEARCH/"
    private lateinit var visualRulesContainer: LinearLayout
    private lateinit var rulesView: EditText

    fun buildView(): View {
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        val rulesFile = ToolPrefs.ensureRulesFile(activity)

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            addView(UiKit.label(activity, "01 // 1-TAP POPULAR SEARCH PRESETS", 11.5f, p.fgSecondaryInt, bold = true))
            addView(
                UiKit.label(activity, "Tap any preset below to add it to WEB SEARCH >:", 10.5f, p.fgMutedInt)
                    .apply { setPadding(0, dp(2), 0, dp(6)) }
            )

            for (rowIdx in 0..1) {
                val chipRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                popularSearchPresets.subList(rowIdx * 3, (rowIdx + 1) * 3).forEach { preset ->
                    chipRow.addView(
                        UiKit.equalButtonPill(activity, "[${preset.chipLabel}]", p.accentCyanInt) {
                            val added = ToolPrefs.appendCustomRuleLine(
                                activity, preset.glyph, preset.ruleLabel, preset.targetUrl
                            )
                            if (added) {
                                refresh()
                                onToolsChanged()
                                Toast.makeText(
                                    activity,
                                    "✓ Added ${preset.ruleLabel.substringAfter("/")} to WEB SEARCH >",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    activity,
                                    "${preset.ruleLabel.substringAfter("/")} is already added",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }
                addView(chipRow)
            }

            addView(UiKit.label(activity, "\n02 // ADD CUSTOM SEARCH ENGINE OR URL TOOL", 11.5f, p.fgSecondaryInt, bold = true))
            val engineCard = UiKit.surfaceCard(activity, padH = 10, padV = 10).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            }

            val engineNameInput = EditText(activity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11.5f
                setTextColor(p.fgPrimaryInt)
                setBackgroundColor(p.bgInputInt)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                hint = "Tool Name (e.g. KAGI, STARTPAGE)"
                setHintTextColor(p.fgMutedInt)
                isSingleLine = true
            }

            val engineUrlInput = EditText(activity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11.5f
                setTextColor(p.fgPrimaryInt)
                setBackgroundColor(p.bgInputInt)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                hint = "https://kagi.com/search?q={text}"
                setHintTextColor(p.fgMutedInt)
                isSingleLine = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }

            val destAndAddRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
            }

            fun formatDestLabel(prefix: String): String = when (prefix) {
                "SEARCH/" -> "[ IN: WEB SEARCH > ]"
                "" -> "[ IN: MAIN LIST ]"
                "YT/" -> "[ IN: YOUTUBE > ]"
                else -> "[ IN: ${prefix.removeSuffix("/")} > ]"
            }

            lateinit var destBtn: TextView
            destBtn = UiKit.equalButtonPill(activity, formatDestLabel(selectedRuleDestination), p.accentPurpleInt) {
                val options = mutableListOf(
                    "SEARCH/" to "WEB SEARCH > (Submenu)",
                    "" to "MAIN POPUP LIST (Top-Level)",
                    "YT/" to "YOUTUBE > (Submenu)"
                )
                ToolPrefs.getCustomFolders(activity).forEach { f ->
                    options.add("$f/" to "Custom Folder: $f >")
                }
                UiKit.showPlainChoiceDialog(activity, "CHOOSE WHERE TO PLACE TOOL", options.map { it.second }) { chosenIdx ->
                    selectedRuleDestination = options[chosenIdx].first
                    destBtn.text = formatDestLabel(selectedRuleDestination)
                }
            }

            val submitEngineBtn = UiKit.equalButtonPill(activity, "[ + ADD TOOL ]", p.accentGreenInt) {
                val rawName = engineNameInput.text.toString().trim().uppercase()
                var rawUrl = engineUrlInput.text.toString().trim()
                if (rawName.isNotEmpty() && rawUrl.isNotEmpty()) {
                    if (!rawUrl.startsWith("http://", true) &&
                        !rawUrl.startsWith("https://", true) &&
                        !rawUrl.startsWith("s/")
                    ) {
                        rawUrl = "https://$rawUrl"
                    }
                    if ((rawUrl.startsWith("http://", true) || rawUrl.startsWith("https://", true)) &&
                        !rawUrl.contains("{text}")
                    ) {
                        rawUrl = if (rawUrl.contains("?")) "$rawUrl&q={text}" else "$rawUrl?q={text}"
                    }
                    val fullLabel = if (rawName.contains("/")) rawName else "$selectedRuleDestination$rawName"
                    val g = ToolPrefs.toPlainAsciiCode("", fullLabel, rawUrl)
                    ToolPrefs.appendCustomRuleLine(activity, g, fullLabel, rawUrl)
                    engineNameInput.setText("")
                    engineUrlInput.setText("")
                    refresh()
                    onToolsChanged()
                    Toast.makeText(activity, "✓ Added $fullLabel", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "Enter both tool name and URL", Toast.LENGTH_SHORT).show()
                }
            }

            destAndAddRow.addView(destBtn)
            destAndAddRow.addView(submitEngineBtn)
            engineCard.addView(engineNameInput)
            engineCard.addView(engineUrlInput)
            engineCard.addView(destAndAddRow)
            addView(engineCard)

            addView(UiKit.label(activity, "\n03 // ACTIVE CUSTOM TOOLS & REGEX PIPES", 11.5f, p.fgSecondaryInt, bold = true))
            addView(
                UiKit.label(
                    activity,
                    "Tap [ × ] to remove any custom rule, or open the raw rules.txt editor below for regex pipes:",
                    10.5f,
                    p.fgMutedInt
                ).apply { setPadding(0, dp(2), 0, dp(6)) }
            )

            visualRulesContainer = UiKit.surfaceCard(activity)
            addView(visualRulesContainer)

            val rawEditorContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(0, dp(6), 0, 0)
            }

            val tplRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(6))
            }
            tplRow.addView(UiKit.equalButtonPill(activity, "[+ REGEX PIPE]", p.accentCyanInt) {
                rulesView.append("\ns/ | PIPE/TRIM SPACES | s/\\s+/ /g")
            })
            tplRow.addView(UiKit.equalButtonPill(activity, "[+ MD RULE]", p.accentCyanInt) {
                rulesView.append("\nH1 | MD/HEADING 1 | # {text}")
            })
            rawEditorContainer.addView(tplRow)

            rulesView = EditText(activity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11.5f
                setTextColor(p.fgPrimaryInt)
                setBackgroundColor(p.bgSurfaceInt)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                minLines = 6
                setText(rulesFile.readText())
            }
            rawEditorContainer.addView(rulesView)

            rawEditorContainer.addView(UiKit.buttonPill(activity, "[ ✓ SAVE RAW rules.txt ]", p.accentGreenInt) {
                rulesFile.writeText(rulesView.text.toString())
                ToolPrefs.invalidateRulesCache()
                refresh()
                onToolsChanged()
                Toast.makeText(activity, "✓ Saved rules.txt", Toast.LENGTH_SHORT).show()
            })

            lateinit var rawRulesToggleBtn: TextView
            rawRulesToggleBtn = UiKit.buttonPill(activity, "[ ADVANCED: RAW rules.txt EDITOR ▾ ]", p.fgMutedInt) {
                val show = rawEditorContainer.visibility != View.VISIBLE
                if (show) rulesView.setText(ToolPrefs.ensureRulesFile(activity).readText())
                rawEditorContainer.visibility = if (show) View.VISIBLE else View.GONE
                rawRulesToggleBtn.text =
                    if (show) "[ ADVANCED: RAW rules.txt EDITOR ▴ ]" else "[ ADVANCED: RAW rules.txt EDITOR ▾ ]"
            }.apply {
                layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(8) }
            }

            addView(rawRulesToggleBtn)
            addView(rawEditorContainer)
        }
    }

    fun refresh() {
        if (!::visualRulesContainer.isInitialized) return
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        visualRulesContainer.removeAllViews()

        val entries = ToolPrefs.getCustomRuleEntries(activity)
        if (::rulesView.isInitialized) {
            rulesView.setText(ToolPrefs.ensureRulesFile(activity).readText())
        }

        if (entries.isEmpty()) {
            visualRulesContainer.addView(
                UiKit.label(activity, "(No custom rules yet — tap a preset or add a URL above)", 10.5f, p.fgMutedInt)
            )
            return
        }

        entries.forEach { entry ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
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

            val leftCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            leftCol.addView(UiKit.label(activity, "[${entry.glyph}] ${entry.rawLabel}", 11f, p.accentGreenInt, bold = true))
            leftCol.addView(UiKit.label(activity, entry.target, 9.8f, p.fgMutedInt).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            })

            val delBtn = UiKit.label(activity, "[ × ]", 12f, p.dangerRedInt, bold = true).apply {
                setPadding(dp(10), dp(4), dp(4), dp(4))
                setOnClickListener {
                    ToolPrefs.deleteCustomRuleAtLine(activity, entry.lineIndex)
                    refresh()
                    onToolsChanged()
                    Toast.makeText(activity, "Removed ${entry.rawLabel}", Toast.LENGTH_SHORT).show()
                }
            }

            row.addView(leftCol)
            row.addView(delBtn)
            visualRulesContainer.addView(row)
        }
    }
}
