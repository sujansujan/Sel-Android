package io.github.selclip

import android.app.Activity
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Tab 2 ([ACTIONS]): Unified, clutter-free management for custom search rules and built-in actions.
 * Replaces the old cluttered Tools + Rules tabs with zero duplicate settings.
 */
class ActionsTabController(private val activity: Activity) {

    private data class Preset(val label: String, val glyph: String, val ruleName: String, val url: String)

    private val presets = listOf(
        Preset("+REDDIT", "RDT", "SEARCH/REDDIT", "https://www.reddit.com/search/?q={text}"),
        Preset("+MAPS", "MAP", "SEARCH/MAPS", "https://www.google.com/maps/search/?api=1&query={text}"),
        Preset("+GITHUB", "GH", "SEARCH/GITHUB", "https://github.com/search?q={text}"),
        Preset("+PPLX", "PPL", "SEARCH/PERPLEXITY", "https://www.perplexity.ai/search?q={text}"),
        Preset("+GPT", "GPT", "SEARCH/CHATGPT", "https://chatgpt.com/?q={text}")
    )

    private lateinit var customListContainer: LinearLayout
    private lateinit var builtInListContainer: LinearLayout
    private lateinit var rawRulesInput: EditText

    fun buildView(): View {
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        val rulesFile = ToolPrefs.ensureRulesFile(activity)

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            // 1. Quick-Add Custom Web Search / Action
            addView(UiKit.sectionTitle(activity, "CUSTOM WEB SEARCH"))

            val presetRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(6))
            }
            presets.forEach { pr ->
                presetRow.addView(UiKit.equalButtonPill(activity, pr.label, p.accentCyanInt) {
                    val added = ToolPrefs.appendCustomRuleLine(activity, pr.glyph, pr.ruleName, pr.url)
                    refresh()
                    Toast.makeText(
                        activity,
                        if (added) "Added ${pr.ruleName.substringAfter("/")}" else "Already added",
                        Toast.LENGTH_SHORT
                    ).show()
                })
            }
            addView(presetRow)

            val addCard = UiKit.surfaceCard(activity)
            val nameIn = EditText(activity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11.2f
                setTextColor(p.fgPrimaryInt)
                setBackgroundColor(p.bgInputInt)
                setPadding(dp(8), dp(7), dp(8), dp(7))
                hint = "Name (e.g. KAGI, WIKI)"
                setHintTextColor(p.fgMutedInt)
                isSingleLine = true
            }
            val urlIn = EditText(activity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11.2f
                setTextColor(p.fgPrimaryInt)
                setBackgroundColor(p.bgInputInt)
                setPadding(dp(8), dp(7), dp(8), dp(7))
                hint = "https://kagi.com/search?q={text}"
                setHintTextColor(p.fgMutedInt)
                isSingleLine = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            addCard.addView(nameIn)
            addCard.addView(urlIn)
            addCard.addView(UiKit.buttonPill(activity, "[ + ADD TO WEB SEARCH ]", p.accentGreenInt) {
                val name = nameIn.text.toString().trim().uppercase()
                var url = urlIn.text.toString().trim()
                if (name.isEmpty() || url.isEmpty()) {
                    Toast.makeText(activity, "Enter both name and URL", Toast.LENGTH_SHORT).show()
                    return@buttonPill
                }
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true) && !url.startsWith("s/")) {
                    url = "https://$url"
                }
                if ((url.startsWith("http://", true) || url.startsWith("https://", true)) && !url.contains("{text}")) {
                    url = if (url.contains("?")) "$url&q={text}" else "$url?q={text}"
                }
                val label = if (name.contains("/")) name else "SEARCH/$name"
                val glyph = ToolPrefs.toPlainAsciiCode("", label, url)
                ToolPrefs.appendCustomRuleLine(activity, glyph, label, url)
                nameIn.setText("")
                urlIn.setText("")
                refresh()
                Toast.makeText(activity, "Added $name", Toast.LENGTH_SHORT).show()
            })
            addView(addCard)

            customListContainer = UiKit.surfaceCard(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            addView(customListContainer)

            // Collapsible power-user rules.txt editor
            val rawBox = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(0, dp(4), 0, 0)
            }
            rawRulesInput = EditText(activity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextColor(p.fgPrimaryInt)
                setBackgroundColor(p.bgSurfaceInt)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                minLines = 5
                setText(rulesFile.readText())
            }
            rawBox.addView(rawRulesInput)
            rawBox.addView(UiKit.buttonPill(activity, "[ SAVE rules.txt ]", p.accentGreenInt) {
                rulesFile.writeText(rawRulesInput.text.toString())
                ToolPrefs.invalidateRulesCache()
                refresh()
                Toast.makeText(activity, "Saved rules.txt", Toast.LENGTH_SHORT).show()
            })

            lateinit var rawToggle: TextView
            rawToggle = UiKit.label(activity, "[ ADVANCED: EDIT rules.txt ▾ ]", 10.2f, p.fgMutedInt, bold = true).apply {
                setPadding(dp(2), dp(8), dp(2), dp(4))
                setOnClickListener {
                    val show = rawBox.visibility != View.VISIBLE
                    if (show) rawRulesInput.setText(ToolPrefs.ensureRulesFile(activity).readText())
                    rawBox.visibility = if (show) View.VISIBLE else View.GONE
                    rawToggle.text = if (show) "[ ADVANCED: EDIT rules.txt ▴ ]" else "[ ADVANCED: EDIT rules.txt ▾ ]"
                }
            }
            addView(rawToggle)
            addView(rawBox)

            // 2. Built-in Context Actions (clean toggle & drag reorder)
            addView(UiKit.sectionTitle(activity, "BUILT-IN ACTIONS (DRAG :: TO ORDER)"))
            addView(
                UiKit.label(
                    activity,
                    "Toggle which actions are allowed in the context bar and [+] drawer:",
                    10.2f,
                    p.fgMutedInt
                ).apply { setPadding(0, 0, 0, dp(6)) }
            )
            builtInListContainer = UiKit.surfaceCard(activity, padH = 8, padV = 6)
            addView(builtInListContainer)
        }
    }

    fun refresh() {
        if (!::customListContainer.isInitialized) return
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }

        // Refresh Custom Rules
        customListContainer.removeAllViews()
        val rules = ToolPrefs.getCustomRuleEntries(activity)
        if (rules.isEmpty()) {
            customListContainer.addView(UiKit.label(activity, "(No custom actions yet)", 10.2f, p.fgMutedInt))
        } else {
            rules.forEach { entry ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                }
                row.addView(UiKit.label(activity, "[${entry.glyph}] ${entry.rawLabel}", 10.8f, p.fgPrimaryInt).apply {
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(UiKit.label(activity, "[x]", 11f, p.dangerRedInt, bold = true).apply {
                    setPadding(dp(8), dp(2), dp(2), dp(2))
                    setOnClickListener {
                        ToolPrefs.deleteCustomRuleAtLine(activity, entry.lineIndex)
                        refresh()
                    }
                })
                customListContainer.addView(row)
            }
        }

        // Refresh Built-in Tools
        builtInListContainer.removeAllViews()
        val tools = ToolPrefs.getManualOrderedTools(activity).filter { it.id != "copy" }
        tools.forEach { tool ->
            val hidden = ToolPrefs.isToolHidden(activity, tool.id)
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(7), dp(4), dp(7))
                tag = tool.id
            }
            val handle = UiKit.label(activity, "::", 13f, p.accentGreenInt, bold = true).apply {
                setPadding(dp(2), dp(2), dp(10), dp(2))
            }
            val name = UiKit.label(
                activity,
                "[${tool.glyph}] ${tool.label}",
                10.8f,
                if (hidden) p.fgMutedInt else p.fgPrimaryInt
            ).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val toggle = UiKit.label(
                activity,
                if (hidden) "[OFF]" else "[ON]",
                10.5f,
                if (hidden) p.fgMutedInt else p.accentGreenInt,
                bold = true
            ).apply {
                setPadding(dp(8), dp(2), dp(2), dp(2))
                setOnClickListener {
                    ToolPrefs.setToolHidden(activity, tool.id, !hidden)
                    refresh()
                }
            }
            row.addView(handle)
            row.addView(name)
            row.addView(toggle)
            ToolPrefs.attachDragHandle(handle, row, builtInListContainer) { refresh() }
            builtInListContainer.addView(row)
        }
    }
}
