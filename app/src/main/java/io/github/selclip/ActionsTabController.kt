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
 * Tab 2 ([ACTIONS]): Unified, clutter-free management for:
 * 1. Default Search Engine (always on the popup bar; 1-tap search, long-press for all engines).
 * 2. Custom Web Search / Rules (with 1-tap quick presets).
 * 3. Built-in & Pinned Actions (tap [*] to always pin onto the popup bar, drag :: to order).
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

    private lateinit var defaultSearchContainer: LinearLayout
    private lateinit var customListContainer: LinearLayout
    private lateinit var builtInListContainer: LinearLayout
    private lateinit var rawRulesInput: EditText

    fun buildView(): View {
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        val rulesFile = ToolPrefs.ensureRulesFile(activity)

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            // 1. Default Search Engine Picker (Always visible on PopClip bar)
            addView(UiKit.sectionTitle(activity, "DEFAULT SEARCH ENGINE"))
            addView(
                UiKit.label(
                    activity,
                    "Always on the popup bar · Tap to search, long-press for all engines:",
                    10.2f,
                    p.fgMutedInt
                ).apply { setPadding(0, 0, 0, dp(6)) }
            )
            defaultSearchContainer = UiKit.surfaceCard(activity, padH = 8, padV = 8)
            addView(defaultSearchContainer)

            // 2. Quick-Add Custom Web Search / Action
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

            // 3. Built-in & Pinned Actions (clean [*] pin + [ON]/[OFF] + drag reorder)
            addView(UiKit.sectionTitle(activity, "ACTIONS & BAR PINS (DRAG :: TO ORDER)"))
            addView(
                UiKit.label(
                    activity,
                    "Tap [*] to always pin an action on the popup bar (Search & YT are always shown):",
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

        // 1. Refresh Default Search Engine Picker
        defaultSearchContainer.removeAllViews()
        val searchEngines = ToolPrefs.loadWebSearchTools(activity)
        val currentDefaultId = ToolPrefs.resolveDefaultSearchEngine(activity).id
        var idx = 0
        while (idx < searchEngines.size) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
            }
            for (col in 0 until 2) {
                val eng = searchEngines.getOrNull(idx + col) ?: continue
                val isSelected = eng.id == currentDefaultId
                val btnText = "${if (isSelected) "● " else ""}[${eng.glyph}] ${eng.label.take(10)}"
                row.addView(
                    UiKit.equalButtonPill(
                        activity,
                        btnText,
                        if (isSelected) p.accentGreenInt else p.fgPrimaryInt,
                        selected = isSelected
                    ) {
                        ToolPrefs.setDefaultSearchEngineId(activity, eng.id)
                        refresh()
                        SelAccessibilityService.instance?.refreshOpenPopupTheme()
                        Toast.makeText(activity, "Default search: ${eng.label}", Toast.LENGTH_SHORT).show()
                    }
                )
            }
            defaultSearchContainer.addView(row)
            idx += 2
        }

        // 2. Refresh Custom Rules (with Pin toggle for SEARCH/* engines too!)
        customListContainer.removeAllViews()
        val rules = ToolPrefs.getCustomRuleEntries(activity)
        val webSearchByTarget = ToolPrefs.loadWebSearchTools(activity).associateBy { it.customTarget }
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

                val matchedEngine = webSearchByTarget[entry.target]
                if (matchedEngine != null) {
                    val pinned = ToolPrefs.isToolPinned(activity, matchedEngine.id)
                    row.addView(
                        UiKit.label(
                            activity,
                            if (pinned) "[*]" else "[·]",
                            11f,
                            if (pinned) p.accentGreenInt else p.fgMutedInt,
                            bold = true
                        ).apply {
                            setPadding(dp(8), dp(2), dp(4), dp(2))
                            setOnClickListener {
                                val nowPinned = ToolPrefs.toggleToolPinned(activity, matchedEngine.id)
                                refresh()
                                Toast.makeText(
                                    activity,
                                    if (nowPinned) "Pinned ${matchedEngine.label} to popup bar" else "Unpinned ${matchedEngine.label}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }

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

        // 3. Refresh Built-in & Top-Level Tools with [*] PIN and [ON]/[OFF]
        builtInListContainer.removeAllViews()
        val tools = ToolPrefs.getManualOrderedTools(activity).filter { it.id != "copy" }
        tools.forEach { tool ->
            val hidden = ToolPrefs.isToolHidden(activity, tool.id)
            val isAlwaysOn = tool.id == "web_search_folder" || tool.id == "youtube_folder"
            val pinned = ToolPrefs.isToolPinned(activity, tool.id)

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

            row.addView(handle)
            row.addView(name)

            if (isAlwaysOn) {
                row.addView(
                    UiKit.label(activity, "[BAR]", 9.8f, p.accentCyanInt, bold = true).apply {
                        setPadding(dp(6), dp(2), dp(4), dp(2))
                    }
                )
            } else {
                row.addView(
                    UiKit.label(
                        activity,
                        if (pinned) "[*]" else "[·]",
                        11f,
                        if (pinned) p.accentGreenInt else p.fgMutedInt,
                        bold = true
                    ).apply {
                        setPadding(dp(6), dp(2), dp(4), dp(2))
                        setOnClickListener {
                            val nowPinned = ToolPrefs.toggleToolPinned(activity, tool.id)
                            if (nowPinned && hidden) ToolPrefs.setToolHidden(activity, tool.id, false)
                            refresh()
                            Toast.makeText(
                                activity,
                                if (nowPinned) "Pinned ${tool.label} to popup bar" else "Unpinned ${tool.label}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }

            val toggle = UiKit.label(
                activity,
                if (hidden) "[OFF]" else "[ON]",
                10.5f,
                if (hidden) p.fgMutedInt else p.accentGreenInt,
                bold = true
            ).apply {
                setPadding(dp(6), dp(2), dp(2), dp(2))
                setOnClickListener {
                    ToolPrefs.setToolHidden(activity, tool.id, !hidden)
                    refresh()
                }
            }
            row.addView(toggle)
            ToolPrefs.attachDragHandle(handle, row, builtInListContainer) { refresh() }
            builtInListContainer.addView(row)
        }
    }
}
