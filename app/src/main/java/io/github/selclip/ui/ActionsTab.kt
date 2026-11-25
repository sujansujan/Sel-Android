package io.github.selclip.ui

import android.app.Activity
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.selclip.SelAccessibilityService
import io.github.selclip.data.BuiltInRegistry
import io.github.selclip.data.PrefsStore
import io.github.selclip.data.RulesRepository

/**
 * Tab 2 (Actions): Default Search Engine picker, Custom Web Search & Rules, and Actions/Pins/Ordering.
 */
class ActionsTab(private val activity: Activity) {

    private lateinit var defaultSearchContainer: LinearLayout
    private lateinit var customListContainer: LinearLayout
    private lateinit var builtInListContainer: LinearLayout
    private lateinit var rawRulesInput: EditText

    fun buildView(): View {
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        val rulesFile = RulesRepository.ensureRulesFile(activity)

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            // 1. Default Search Engine Picker
            addView(UiKit.sectionTitle(activity, "Default Search Engine"))
            addView(
                UiKit.text(
                    activity,
                    "Always visible on the popup bar. Tap to search immediately; long-press for all engines.",
                    12.5f,
                    p.fgMutedInt
                ).apply { setPadding(dp(2), 0, dp(2), dp(8)) }
            )
            defaultSearchContainer = UiKit.card(activity, padH = 10, padV = 10)
            addView(defaultSearchContainer)

            // 2. Custom Web Search & Presets
            addView(UiKit.sectionTitle(activity, "Custom Web Search"))
            val presetRow1 = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val presetRow2 = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(6))
            }
            BuiltInRegistry.quickSearchPresets.forEachIndexed { idx, preset ->
                val targetRow = if (idx < 3) presetRow1 else presetRow2
                targetRow.addView(UiKit.equalPill(activity, preset.buttonLabel, p.accentInfoInt) {
                    val added = RulesRepository.appendRule(activity, preset.glyph, preset.ruleName, preset.url)
                    refresh()
                    Toast.makeText(
                        activity,
                        if (added) "Added ${preset.ruleName.substringAfter("/")}" else "Already added",
                        Toast.LENGTH_SHORT
                    ).show()
                })
            }
            addView(presetRow1)
            addView(presetRow2)

            val addCard = UiKit.card(activity)
            val nameIn = UiKit.inputField(activity, "Name (e.g. Kagi, Wiki)")
            val urlIn = UiKit.inputField(activity, "https://kagi.com/search?q={text}").apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            addCard.addView(nameIn)
            addCard.addView(urlIn)
            addCard.addView(UiKit.fullButton(activity, "Add to Web Search", p.accentSuccessInt, filled = true) {
                val name = nameIn.text.toString().trim().uppercase()
                var url = urlIn.text.toString().trim()
                if (name.isEmpty() || url.isEmpty()) {
                    Toast.makeText(activity, "Enter both name and URL", Toast.LENGTH_SHORT).show()
                    return@fullButton
                }
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true) && !url.startsWith("s/")) {
                    url = "https://$url"
                }
                if ((url.startsWith("http://", true) || url.startsWith("https://", true)) && !url.contains("{text}")) {
                    url = if (url.contains("?")) "$url&q={text}" else "$url?q={text}"
                }
                val label = if (name.contains("/")) name else "SEARCH/$name"
                val glyph = RulesRepository.inferShortCode("", label, url)
                RulesRepository.appendRule(activity, glyph, label, url)
                nameIn.setText("")
                urlIn.setText("")
                refresh()
                Toast.makeText(activity, "Added $name", Toast.LENGTH_SHORT).show()
            })
            addView(addCard)

            customListContainer = UiKit.card(activity, padH = 12, padV = 8).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            addView(customListContainer)

            // Collapsible raw rules.txt editor for power users
            val rawBox = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(0, dp(6), 0, 0)
            }
            rawRulesInput = UiKit.inputField(activity, "CODE | LABEL | TARGET", singleLine = false, mono = true).apply {
                minLines = 5
                setText(rulesFile.readText())
            }
            rawBox.addView(rawRulesInput)
            rawBox.addView(UiKit.fullButton(activity, "Save rules.txt", p.accentSuccessInt) {
                RulesRepository.writeRawRules(activity, rawRulesInput.text.toString())
                refresh()
                Toast.makeText(activity, "Saved rules.txt", Toast.LENGTH_SHORT).show()
            })

            lateinit var rawToggle: TextView
            rawToggle = UiKit.text(activity, "Advanced: Edit rules.txt ▾", 12.5f, p.fgMutedInt, bold = true).apply {
                setPadding(dp(4), dp(12), dp(4), dp(4))
                setOnClickListener {
                    val show = rawBox.visibility != View.VISIBLE
                    if (show) rawRulesInput.setText(RulesRepository.ensureRulesFile(activity).readText())
                    rawBox.visibility = if (show) View.VISIBLE else View.GONE
                    rawToggle.text = if (show) "Advanced: Edit rules.txt ▴" else "Advanced: Edit rules.txt ▾"
                }
            }
            addView(rawToggle)
            addView(rawBox)

            // 3. Actions, Bar Pins & Drag Ordering
            addView(UiKit.sectionTitle(activity, "Actions & Bar Pins"))
            addView(
                UiKit.text(
                    activity,
                    "Tap ★ to pin an action permanently to the bar. Drag ⋮⋮ to reorder the drawer.",
                    12.5f,
                    p.fgMutedInt
                ).apply { setPadding(dp(2), 0, dp(2), dp(8)) }
            )
            builtInListContainer = UiKit.card(activity, padH = 10, padV = 6)
            addView(builtInListContainer)
        }
    }

    fun refresh() {
        if (!::defaultSearchContainer.isInitialized) return
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }

        // 1. Default Search Engine Grid
        defaultSearchContainer.removeAllViews()
        val engines = PrefsStore.loadWebSearchTools(activity)
        val defaultId = PrefsStore.resolveDefaultSearchEngine(activity).id
        var idx = 0
        while (idx < engines.size) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            for (col in 0 until 2) {
                val eng = engines.getOrNull(idx + col) ?: continue
                val selected = eng.id == defaultId
                val label = if (selected) "✓ ${eng.label}" else eng.label
                row.addView(UiKit.equalPill(activity, label, if (selected) p.accentSuccessInt else p.fgPrimaryInt, selected) {
                    PrefsStore.setDefaultSearchEngineId(activity, eng.id)
                    refresh()
                    SelAccessibilityService.instance?.refreshThemeIfVisible()
                    Toast.makeText(activity, "Default search: ${eng.label}", Toast.LENGTH_SHORT).show()
                })
            }
            defaultSearchContainer.addView(row)
            idx += 2
        }

        // 2. Custom Rules
        customListContainer.removeAllViews()
        val entries = RulesRepository.getEntries(activity)
        val searchByTarget = PrefsStore.loadWebSearchTools(activity).associateBy { it.customTarget }
        if (entries.isEmpty()) {
            customListContainer.addView(UiKit.text(activity, "No custom actions yet.", 12.5f, p.fgMutedInt))
        } else {
            entries.forEach { entry ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(44)
                    setPadding(dp(2), dp(4), dp(2), dp(4))
                }
                row.addView(UiKit.text(activity, entry.rawLabel, 13f, p.fgPrimaryInt).apply {
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                val matchedEngine = searchByTarget[entry.target]
                if (matchedEngine != null) {
                    val pinned = PrefsStore.isToolPinned(activity, matchedEngine.id)
                    row.addView(UiKit.text(activity, if (pinned) "★" else "☆", 16f, if (pinned) p.accentSuccessInt else p.fgMutedInt, bold = true).apply {
                        minWidth = dp(40)
                        minHeight = dp(40)
                        gravity = Gravity.CENTER
                        setOnClickListener {
                            val nowPinned = PrefsStore.toggleToolPinned(activity, matchedEngine.id)
                            refresh()
                            Toast.makeText(
                                activity,
                                if (nowPinned) "Pinned ${matchedEngine.label} to bar" else "Unpinned ${matchedEngine.label}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    })
                }
                row.addView(UiKit.text(activity, "×", 17f, p.dangerInt, bold = true).apply {
                    minWidth = dp(38)
                    minHeight = dp(40)
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        RulesRepository.deleteRuleAtLine(activity, entry.lineIndex)
                        refresh()
                    }
                })
                customListContainer.addView(row)
            }
        }

        // 3. Built-in & Top-Level Tools
        builtInListContainer.removeAllViews()
        PrefsStore.getManualOrderedTools(activity).forEach { tool ->
            val hidden = PrefsStore.isToolHidden(activity, tool.id)
            val alwaysOnBar = tool.id == "web_search_folder" || tool.id == "youtube_folder"
            val pinned = PrefsStore.isToolPinned(activity, tool.id)

            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(46)
                setPadding(dp(2), dp(4), dp(2), dp(4))
                tag = tool.id
            }

            val handle = UiKit.text(activity, "⋮⋮", 14f, p.fgMutedInt, bold = true).apply {
                minWidth = dp(34)
                minHeight = dp(40)
                gravity = Gravity.CENTER
            }
            val title = UiKit.text(activity, tool.label, 13.5f, if (hidden) p.fgMutedInt else p.fgPrimaryInt).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            row.addView(handle)
            row.addView(title)

            if (alwaysOnBar) {
                row.addView(UiKit.text(activity, "Always", 11.5f, p.accentInfoInt, bold = true).apply {
                    setPadding(dp(6), dp(4), dp(8), dp(4))
                })
            } else {
                row.addView(UiKit.text(activity, if (pinned) "★" else "☆", 16f, if (pinned) p.accentSuccessInt else p.fgMutedInt, bold = true).apply {
                    minWidth = dp(40)
                    minHeight = dp(40)
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        val nowPinned = PrefsStore.toggleToolPinned(activity, tool.id)
                        if (nowPinned && hidden) PrefsStore.setToolHidden(activity, tool.id, false)
                        refresh()
                        Toast.makeText(
                            activity,
                            if (nowPinned) "Pinned ${tool.label} to bar" else "Unpinned ${tool.label}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                })
            }

            row.addView(UiKit.text(activity, if (hidden) "Off" else "On", 12.5f, if (hidden) p.fgMutedInt else p.accentSuccessInt, bold = true).apply {
                minWidth = dp(42)
                minHeight = dp(40)
                gravity = Gravity.CENTER
                setOnClickListener {
                    PrefsStore.setToolHidden(activity, tool.id, !hidden)
                    refresh()
                }
            })

            PrefsStore.attachDragHandle(handle, row, builtInListContainer) { refresh() }
            builtInListContainer.addView(row)
        }
    }
}
