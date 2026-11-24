package io.github.selclip

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Tab 3 ([TOOLS]): Interactive 4-slot Top Row 1/2 matrix, Custom Folder manager + checklist, and drag-to-reorder list.
 */
class ToolsTabController(
    private val activity: Activity,
    private val onRulesChanged: () -> Unit
) {
    private lateinit var iconRowsSummaryContainer: LinearLayout
    private lateinit var customFoldersContainer: LinearLayout
    private lateinit var customFolderInput: EditText
    private lateinit var reorderListContainer: LinearLayout
    private lateinit var sortModeButton: TextView

    fun buildView(): View {
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            addView(UiKit.label(activity, "01 // TOP ICON-ONLY ROWS (TAP ANY SLOT TO EDIT)", 11.5f, p.fgSecondaryInt, bold = true))
            addView(
                UiKit.label(
                    activity,
                    "Top rows display bracketed icons only (max 4 per row). Tap any [ + ] or icon slot below to assign/remove:",
                    10.5f,
                    p.fgMutedInt
                ).apply { setPadding(0, dp(2), 0, dp(6)) }
            )

            iconRowsSummaryContainer = UiKit.surfaceCard(activity)
            addView(iconRowsSummaryContainer)

            val presetRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(12))
            }
            presetRow.addView(UiKit.equalButtonPill(activity, "[ ↺ 4x2 PRESET ]", p.accentGreenInt) {
                ToolPrefs.applyDefaultTwoIconRowsPreset(activity)
                refresh()
                Toast.makeText(activity, "Loaded 4 icons each in Row 1 & Row 2", Toast.LENGTH_SHORT).show()
            })
            presetRow.addView(UiKit.equalButtonPill(activity, "[ × CLEAR R1 ]", p.fgPrimaryInt) {
                ToolPrefs.clearIconRow(activity, 1)
                refresh()
            })
            presetRow.addView(UiKit.equalButtonPill(activity, "[ × CLEAR R2 ]", p.fgPrimaryInt) {
                ToolPrefs.clearIconRow(activity, 2)
                refresh()
            })
            addView(presetRow)

            addView(UiKit.label(activity, "02 // CUSTOM FOLDERS (GROUP TOOLS INTO SUBMENUS)", 11.5f, p.fgSecondaryInt, bold = true))
            addView(
                UiKit.label(
                    activity,
                    "Create a folder (e.g. READ, SOCIAL, AI), then tap [ + ITEMS ] on the folder to check which tools belong inside:",
                    10.5f,
                    p.fgMutedInt
                ).apply { setPadding(0, dp(2), 0, dp(6)) }
            )

            val createFolderRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(6))
            }
            customFolderInput = EditText(activity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11.5f
                setTextColor(p.fgPrimaryInt)
                setBackgroundColor(p.bgSurfaceInt)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                hint = "Folder Name (e.g. READ, STUDY)"
                setHintTextColor(p.fgMutedInt)
                isSingleLine = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val addFolderBtn = UiKit.buttonPill(activity, " [ + CREATE ]", p.accentGreenInt) {
                val raw = customFolderInput.text.toString().trim()
                if (ToolPrefs.createCustomFolder(activity, raw)) {
                    val clean = ToolPrefs.sanitizeFolderName(raw)
                    customFolderInput.setText("")
                    refresh()
                    showFolderChecklistDialog(clean)
                } else {
                    Toast.makeText(activity, "Enter a valid folder name (e.g. READ)", Toast.LENGTH_SHORT).show()
                }
            }
            createFolderRow.addView(customFolderInput)
            createFolderRow.addView(addFolderBtn)
            addView(createFolderRow)

            customFoldersContainer = UiKit.surfaceCard(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            }
            addView(customFoldersContainer)

            addView(UiKit.label(activity, "03 // ALL TOOLS (DRAG ≡ TO REORDER)", 11.5f, p.fgSecondaryInt, bold = true))
            addView(
                UiKit.label(
                    activity,
                    "Tools below top rows display clean text labels only. Tap [LIST]/[★R1]/[★R2] to pin icon to top rows:",
                    10.5f,
                    p.fgMutedInt
                ).apply { setPadding(0, dp(2), 0, dp(6)) }
            )

            val controls = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(6))
            }
            sortModeButton = UiKit.equalButtonPill(activity, "", p.accentGreenInt) {
                ToolPrefs.setSortMostUsed(activity, !ToolPrefs.isSortMostUsed(activity))
                refresh()
            }
            val resetBtn = UiKit.equalButtonPill(activity, "[ × RESET COUNTS ]", p.dangerRedInt) {
                ToolPrefs.resetUseCounts(activity)
                refresh()
                Toast.makeText(activity, "Usage counts reset", Toast.LENGTH_SHORT).show()
            }
            controls.addView(sortModeButton)
            controls.addView(resetBtn)
            addView(controls)

            reorderListContainer = UiKit.surfaceCard(activity, padH = 8, padV = 8)
            addView(reorderListContainer)
        }
    }

    fun refresh() {
        if (!::reorderListContainer.isInitialized) return
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        val mostUsed = ToolPrefs.isSortMostUsed(activity)
        sortModeButton.text = if (mostUsed) "[ ★ MOST USED: ON ]" else "[ ★ MOST USED: OFF ]"

        refreshCustomFoldersView()
        refreshIconRowsMatrix()

        reorderListContainer.removeAllViews()
        val tools = ToolPrefs.getAllSortedTools(activity)
        val customFolders = ToolPrefs.getCustomFolders(activity)

        tools.forEachIndexed { idx, tool ->
            val hidden = ToolPrefs.isToolHidden(activity, tool.id)
            val assignedRow = ToolPrefs.getToolIconRow(activity, tool.id)
            val assignedFolder = ToolPrefs.getToolFolderAssignment(activity, tool.id)

            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(9), dp(8), dp(9))
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
                tag = tool.id
            }

            val dragHandle = TextView(activity).apply {
                text = "≡"
                typeface = Typeface.MONOSPACE
                textSize = 16f
                setTextColor(p.accentGreenInt)
                setPadding(dp(4), dp(2), dp(8), dp(2))
            }

            val count = ToolPrefs.getUseCount(activity, tool.id)
            val numStr = "%02d".format(idx + 1)
            val cleanGlyph = tool.glyph.trim().removePrefix("[").removeSuffix("]").trim()
            val bracketIcon = "[$cleanGlyph]"
            val nameView = TextView(activity).apply {
                text = "$numStr | ${bracketIcon.padEnd(5, ' ')} ${tool.label}" + if (count > 0) " ($count)" else ""
                typeface = Typeface.MONOSPACE
                textSize = 10.8f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (hidden) p.fgMutedInt else p.fgPrimaryInt)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            row.addView(dragHandle)
            row.addView(nameView)

            if (customFolders.isNotEmpty() && tool.id != "copy" && !tool.id.startsWith("folder_custom_")) {
                row.addView(TextView(activity).apply {
                    text = if (assignedFolder != null) "[$assignedFolder/]" else "[+DIR]"
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    textSize = 9.8f
                    setTextColor(if (assignedFolder != null) p.accentOrangeInt else p.fgMutedInt)
                    setPadding(dp(5), dp(4), dp(5), dp(4))
                    setOnClickListener {
                        val nextFolder = ToolPrefs.cycleToolFolderAssignment(activity, tool.id)
                        refresh()
                        val msg = if (nextFolder != null) "Moved ${tool.label} inside [$nextFolder >]"
                        else "Returned ${tool.label} to root popup list"
                        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                    }
                })
            }

            if (tool.id != "copy") {
                row.addView(TextView(activity).apply {
                    text = when (assignedRow) {
                        1 -> "[★R1]"
                        2 -> "[★R2]"
                        else -> "[LIST]"
                    }
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    textSize = 10f
                    setTextColor(
                        when (assignedRow) {
                            1 -> p.accentPurpleInt
                            2 -> p.accentCyanInt
                            else -> p.fgMutedInt
                        }
                    )
                    setPadding(dp(5), dp(4), dp(5), dp(4))
                    setOnClickListener {
                        val next = ToolPrefs.cycleToolIconRow(activity, tool.id)
                        if (next == 0 && assignedRow == 0) {
                            Toast.makeText(
                                activity,
                                "Top rows R1 & R2 already have 4 items max — tap a slot above to swap!",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        refresh()
                    }
                })
            }

            row.addView(TextView(activity).apply {
                text = if (hidden) "[OFF]" else "[ON]"
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textSize = 10f
                setTextColor(if (hidden) p.fgMutedInt else p.accentGreenInt)
                setPadding(dp(5), dp(4), dp(4), dp(4))
                setOnClickListener {
                    ToolPrefs.setToolHidden(activity, tool.id, !hidden)
                    refresh()
                }
            })

            ToolPrefs.attachDragHandle(dragHandle, row, reorderListContainer) { refresh() }
            reorderListContainer.addView(row)
        }
    }

    private fun refreshIconRowsMatrix() {
        if (!::iconRowsSummaryContainer.isInitialized) return
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        iconRowsSummaryContainer.removeAllViews()

        val editRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(4))
        }
        editRow.addView(UiKit.label(activity, "EDIT  ", 10.5f, p.fgMutedInt, bold = true))
        listOf("[^C]", "[^V]", "[^X]", "[^A]").forEach { g ->
            editRow.addView(
                UiKit.label(activity, g, 11f, p.accentGreenInt, bold = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(2), dp(5), dp(2), dp(5))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
        }
        iconRowsSummaryContainer.addView(editRow)

        for (rNum in 1..2) {
            val rTools = ToolPrefs.getIconRowTools(activity, rNum)
            val rowColor = if (rNum == 1) p.accentPurpleInt else p.accentCyanInt
            val slotRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            slotRow.addView(UiKit.label(activity, "ROW $rNum ", 10.5f, rowColor, bold = true))

            for (slotIdx in 0 until ToolPrefs.MAX_TOOLS_PER_ICON_ROW) {
                val tool = rTools.getOrNull(slotIdx)
                val slotText = if (tool != null) {
                    "[${tool.glyph.trim().removePrefix("[").removeSuffix("]")}]"
                } else {
                    "[ + ]"
                }
                val cell = UiKit.label(activity, slotText, 11f, if (tool != null) rowColor else p.fgMutedInt, bold = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(2), dp(6), dp(2), dp(6))
                    background = GradientDrawable().apply {
                        setColor(p.bgPrimaryInt)
                        setStroke(dp(1), if (tool != null) rowColor else p.dividerInt)
                        cornerRadius = dp(2).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(2)
                        marginEnd = dp(2)
                    }
                    setOnClickListener { showTopRowSlotPickerDialog(rNum, tool) }
                }
                slotRow.addView(cell)
            }
            iconRowsSummaryContainer.addView(slotRow)
        }
    }

    private fun refreshCustomFoldersView() {
        if (!::customFoldersContainer.isInitialized) return
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        customFoldersContainer.removeAllViews()

        val folders = ToolPrefs.getCustomFolders(activity)
        if (folders.isEmpty()) {
            customFoldersContainer.addView(
                UiKit.label(activity, "(No custom folders yet — enter a name above and tap [ + CREATE ] to group tools)", 10.5f, p.fgMutedInt)
            )
            return
        }

        folders.forEach { folderName ->
            val itemsInside = ToolPrefs.loadCustomFolderTools(activity, folderName)
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

            val summary = if (itemsInside.isEmpty()) {
                "[D/] $folderName > (0 items)"
            } else {
                "[D/] $folderName > (${itemsInside.size}: ${itemsInside.joinToString(", ") { it.label.replace(" >", "") }})"
            }

            row.addView(UiKit.label(activity, summary, 10.8f, p.accentOrangeInt, bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { showFolderChecklistDialog(folderName) }
            })
            row.addView(UiKit.label(activity, "[ + ITEMS ]", 10.5f, p.accentGreenInt, bold = true).apply {
                setPadding(dp(8), dp(4), dp(6), dp(4))
                setOnClickListener { showFolderChecklistDialog(folderName) }
            })
            row.addView(UiKit.label(activity, "[ × ]", 11.5f, p.dangerRedInt, bold = true).apply {
                setPadding(dp(6), dp(4), dp(4), dp(4))
                setOnClickListener {
                    ToolPrefs.deleteCustomFolder(activity, folderName)
                    onRulesChanged()
                    refresh()
                    Toast.makeText(activity, "Removed folder $folderName", Toast.LENGTH_SHORT).show()
                }
            })
            customFoldersContainer.addView(row)
        }
    }

    private fun showTopRowSlotPickerDialog(rowNumber: Int, clickedTool: ToolSpec?) {
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        val allCandidates = ToolPrefs.loadAllTools(activity).filter { it.id != "copy" }

        val dialogRoot = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bgPrimaryInt)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        val titleStr = if (clickedTool != null) {
            "ROW $rowNumber // [${clickedTool.glyph}] ${clickedTool.label}"
        } else {
            "ASSIGN ICON TO TOP ROW $rowNumber (MAX 4)"
        }
        dialogRoot.addView(UiKit.label(activity, titleStr, 12f, p.accentPurpleInt, bold = true).apply {
            setPadding(0, 0, 0, dp(8))
        })

        var alert: AlertDialog? = null
        if (clickedTool != null) {
            dialogRoot.addView(UiKit.buttonPill(activity, "[ × REMOVE [${clickedTool.glyph}] FROM ROW $rowNumber ]", p.dangerRedInt) {
                ToolPrefs.setToolIconRow(activity, clickedTool.id, 0)
                refresh()
                alert?.dismiss()
            })
            dialogRoot.addView(UiKit.label(activity, "Or replace with another tool below:", 10.2f, p.fgMutedInt).apply {
                setPadding(0, dp(6), 0, dp(6))
            })
        }

        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (activity.resources.displayMetrics.heightPixels * 0.48f).toInt()
            )
        }
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        allCandidates.forEach { tool ->
            val currentRow = ToolPrefs.getToolIconRow(activity, tool.id)
            val tok = "[${tool.glyph.trim().removePrefix("[").removeSuffix("]")}]"
            val statusBadge = when (currentRow) {
                1 -> " (in R1)"
                2 -> " (in R2)"
                else -> ""
            }
            val itemView = UiKit.label(
                activity,
                "${tok.padEnd(6, ' ')} ${tool.label}$statusBadge",
                11.2f,
                if (currentRow == rowNumber) p.accentGreenInt else p.fgPrimaryInt
            ).apply {
                setPadding(dp(10), dp(9), dp(10), dp(9))
                background = GradientDrawable().apply {
                    setColor(p.bgSurfaceInt)
                    setStroke(dp(1), p.dividerInt)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(3) }
                setOnClickListener {
                    if (clickedTool != null && clickedTool.id != tool.id) {
                        val currentIds = ToolPrefs.getIconRowIds(activity, rowNumber).toMutableList()
                        val otherRow = if (rowNumber == 1) 2 else 1
                        val otherIds = ToolPrefs.getIconRowIds(activity, otherRow).toMutableList()
                        otherIds.remove(tool.id)
                        ToolPrefs.setIconRowIds(activity, otherRow, otherIds)

                        val replaceIdx = currentIds.indexOf(clickedTool.id)
                        if (replaceIdx >= 0 && tool.id !in currentIds) {
                            currentIds[replaceIdx] = tool.id
                            ToolPrefs.setIconRowIds(activity, rowNumber, currentIds)
                        } else {
                            ToolPrefs.setToolIconRow(activity, tool.id, rowNumber)
                        }
                    } else {
                        if (!ToolPrefs.setToolIconRow(activity, tool.id, rowNumber)) {
                            Toast.makeText(
                                activity,
                                "Row $rowNumber already has 4 icons — tap an existing slot to replace it",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    refresh()
                    alert?.dismiss()
                }
            }
            list.addView(itemView)
        }

        scroll.addView(list)
        dialogRoot.addView(scroll)
        dialogRoot.addView(UiKit.buttonPill(activity, "[ CLOSE ]", p.fgMutedInt) { alert?.dismiss() })

        alert = AlertDialog.Builder(activity).setView(dialogRoot).create()
        alert.show()
    }

    private fun showFolderChecklistDialog(folderName: String) {
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        val tools = ToolPrefs.loadAssignableToolsForFolder(activity)

        val dialogRoot = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bgPrimaryInt)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        dialogRoot.addView(
            UiKit.label(activity, "FOLDER // [$folderName >] — CHECK ITEMS TO KEEP INSIDE", 11.5f, p.accentOrangeInt, bold = true)
                .apply { setPadding(0, 0, 0, dp(4)) }
        )
        dialogRoot.addView(
            UiKit.label(activity, "Checked tools move from the root list into [$folderName >]:", 10f, p.fgMutedInt)
                .apply { setPadding(0, 0, 0, dp(8)) }
        )

        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (activity.resources.displayMetrics.heightPixels * 0.50f).toInt()
            )
        }
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        fun renderChecklistRows() {
            list.removeAllViews()
            tools.forEach { tool ->
                val assigned = ToolPrefs.getToolFolderAssignment(activity, tool.id)
                val inThisFolder = assigned == folderName
                val otherSuffix = if (assigned != null && !inThisFolder) " (in $assigned)" else ""
                val boxGlyph = if (inThisFolder) "[x]" else "[ ]"
                val row = UiKit.label(
                    activity,
                    "$boxGlyph  ${tool.label}$otherSuffix",
                    11.2f,
                    if (inThisFolder) p.accentGreenInt else p.fgPrimaryInt
                ).apply {
                    setPadding(dp(10), dp(9), dp(10), dp(9))
                    background = GradientDrawable().apply {
                        setColor(p.bgSurfaceInt)
                        setStroke(dp(1), p.dividerInt)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(3) }
                    setOnClickListener {
                        ToolPrefs.setToolFolderAssignment(activity, tool.id, if (inThisFolder) null else folderName)
                        renderChecklistRows()
                        refresh()
                    }
                }
                list.addView(row)
            }
        }

        renderChecklistRows()
        scroll.addView(list)
        dialogRoot.addView(scroll)

        var alert: AlertDialog? = null
        dialogRoot.addView(UiKit.buttonPill(activity, "[ ✓ DONE ]", p.accentGreenInt) {
            refresh()
            alert?.dismiss()
        })

        alert = AlertDialog.Builder(activity).setView(dialogRoot).create()
        alert.show()
    }
}
