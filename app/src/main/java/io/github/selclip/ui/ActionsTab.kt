package io.github.selclip.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.selclip.SelAccessibilityService
import io.github.selclip.data.BuiltInRegistry
import io.github.selclip.data.FaviconRepository
import io.github.selclip.data.PrefsStore
import io.github.selclip.data.RulesRepository
import io.github.selclip.overlay.ActionIconFactory

/**
 * Tab 2 (Actions): Default Search Engine picker (with live favicons), Custom Web Search & Rules
 * (auto-fetching favicons on creation), and Actions/Pins/Ordering.
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

        // Prefetch favicons for existing search portals cleanly in background
        val existingUrls = PrefsStore.loadWebSearchTools(activity).mapNotNull { it.customTarget } + "https://www.youtube.com"
        FaviconRepository.prefetchAll(activity, existingUrls)

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            // 1. Default Search Engine Picker
            addView(UiKit.sectionTitle(activity, "Default Search Engine"))
            addView(
                UiKit.text(
                    activity,
                    "Always on the popup bar. Tap to search immediately; long-press for all engines.",
                    12.5f,
                    p.fgMutedInt
                ).apply { setPadding(dp(2), 0, dp(2), dp(8)) }
            )
            defaultSearchContainer = UiKit.card(activity, padH = 10, padV = 10)
            addView(defaultSearchContainer)

            // 2. Custom Web Search & Presets
            addView(UiKit.sectionTitle(activity, "Custom Search Portals"))
            val presetRow1 = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val presetRow2 = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(6))
            }
            BuiltInRegistry.quickSearchPresets.forEachIndexed { idx, preset ->
                val targetRow = if (idx < 3) presetRow1 else presetRow2
                targetRow.addView(UiKit.equalPill(activity, preset.buttonLabel, p.accentInfoInt) {
                    val added = RulesRepository.appendRule(activity, preset.glyph, preset.ruleName, preset.url)
                    FaviconRepository.getOrFetch(activity, preset.url) { refresh() }
                    refresh()
                    Toast.makeText(
                        activity,
                        if (added) "Added ${preset.ruleName.substringAfter("/")} (fetching icon…)" else "Already added",
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
            addCard.addView(UiKit.fullButton(activity, "Add Search Portal", p.accentPrimaryInt, filled = true) {
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
                // Auto-fetch favicon for the newly created search portal!
                FaviconRepository.getOrFetch(activity, url) { refresh() }
                nameIn.setText("")
                urlIn.setText("")
                refresh()
                Toast.makeText(activity, "Added $name (fetching favicon…)", Toast.LENGTH_SHORT).show()
            })
            addView(addCard)

            customListContainer = UiKit.card(activity, padH = 12, padV = 8).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            addView(customListContainer)

            // Collapsible raw rules.txt editor
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
                val urls = PrefsStore.loadWebSearchTools(activity).mapNotNull { it.customTarget }
                FaviconRepository.prefetchAll(activity, urls)
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
                    "Tap ★ to pin an action permanently onto the popup bar. Drag ⋮⋮ to reorder the overflow menu.",
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
        val iconPx = dp(18)

        // 1. Default Search Engine Grid (with favicons!)
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
                val pill = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    minimumHeight = dp(42)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = StateListDrawable().apply {
                        addState(
                            intArrayOf(android.R.attr.state_pressed),
                            GradientDrawable().apply {
                                setColor(p.bgPressedInt)
                                cornerRadius = UiKit.dpF(activity, 10f)
                            }
                        )
                        addState(
                            intArrayOf(),
                            GradientDrawable().apply {
                                setColor(if (selected) p.accentPrimaryInt else p.bgSurfaceInt)
                                setStroke(dp(1), if (selected) p.accentPrimaryInt else p.borderInt)
                                cornerRadius = UiKit.dpF(activity, 10f)
                            }
                        )
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        topMargin = dp(3)
                        bottomMargin = dp(3)
                        marginStart = dp(3)
                        marginEnd = dp(3)
                    }
                    setOnClickListener {
                        PrefsStore.setDefaultSearchEngineId(activity, eng.id)
                        refresh()
                        SelAccessibilityService.instance?.refreshThemeIfVisible()
                        Toast.makeText(activity, "Default search: ${eng.label}", Toast.LENGTH_SHORT).show()
                    }
                }
                val icon = ActionIconFactory.createFaviconOrBadge(
                    context = activity,
                    urlTemplate = eng.customTarget,
                    fallbackText = eng.glyph,
                    tintColor = if (selected) p.onAccentInt else p.fgMutedInt,
                    sizePx = iconPx,
                    onFaviconLoaded = { refresh() }
                )
                pill.addView(ImageView(activity).apply {
                    setImageDrawable(icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(iconPx, iconPx).apply { marginEnd = dp(6) }
                })
                pill.addView(
                    UiKit.text(activity, eng.label, 12.5f, if (selected) p.onAccentInt else p.fgPrimaryInt, bold = true).apply {
                        isSingleLine = true
                        ellipsize = TextUtils.TruncateAt.END
                    }
                )
                row.addView(pill)
            }
            defaultSearchContainer.addView(row)
            idx += 2
        }

        // 2. Custom Rules (with auto-fetched favicons + ★ pin toggle!)
        customListContainer.removeAllViews()
        val entries = RulesRepository.getEntries(activity)
        val searchByTarget = PrefsStore.loadWebSearchTools(activity).associateBy { it.customTarget }
        if (entries.isEmpty()) {
            customListContainer.addView(UiKit.text(activity, "No custom portals yet.", 12.5f, p.fgMutedInt))
        } else {
            entries.forEach { entry ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(44)
                    setPadding(dp(2), dp(4), dp(2), dp(4))
                }
                if (entry.target.startsWith("http://", true) || entry.target.startsWith("https://", true)) {
                    val icon = ActionIconFactory.createFaviconOrBadge(
                        context = activity,
                        urlTemplate = entry.target,
                        fallbackText = entry.glyph,
                        tintColor = p.fgMutedInt,
                        sizePx = iconPx,
                        onFaviconLoaded = { refresh() }
                    )
                    row.addView(ImageView(activity).apply {
                        setImageDrawable(icon)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        layoutParams = LinearLayout.LayoutParams(iconPx, iconPx).apply { marginEnd = dp(8) }
                    })
                }
                row.addView(UiKit.text(activity, entry.rawLabel, 13f, p.fgPrimaryInt).apply {
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                val matchedEngine = searchByTarget[entry.target]
                if (matchedEngine != null) {
                    val pinned = PrefsStore.isToolPinned(activity, matchedEngine.id)
                    row.addView(UiKit.text(activity, if (pinned) "★" else "☆", 16f, if (pinned) p.accentPrimaryInt else p.fgMutedInt, bold = true).apply {
                        minWidth = dp(40)
                        minimumHeight = dp(40)
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
                    minimumHeight = dp(40)
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
                minWidth = dp(32)
                minimumHeight = dp(40)
                gravity = Gravity.CENTER
            }
            val toolIcon = ActionIconFactory.createForTool(
                context = activity,
                tool = tool,
                tintColor = if (hidden) p.fgMutedInt else p.fgPrimaryInt,
                sizePx = iconPx,
                onFaviconLoaded = { refresh() }
            )
            val iconView = ImageView(activity).apply {
                setImageDrawable(toolIcon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(iconPx, iconPx).apply { marginEnd = dp(10) }
            }
            val title = UiKit.text(activity, tool.label, 13.5f, if (hidden) p.fgMutedInt else p.fgPrimaryInt).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            row.addView(handle)
            row.addView(iconView)
            row.addView(title)

            if (alwaysOnBar) {
                row.addView(UiKit.text(activity, "Always", 11.5f, p.accentInfoInt, bold = true).apply {
                    setPadding(dp(6), dp(4), dp(8), dp(4))
                })
            } else {
                row.addView(UiKit.text(activity, if (pinned) "★" else "☆", 16f, if (pinned) p.accentPrimaryInt else p.fgMutedInt, bold = true).apply {
                    minWidth = dp(40)
                    minimumHeight = dp(40)
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
                minimumHeight = dp(40)
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
