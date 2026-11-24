package io.github.selclip

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Tab 2 ([SET]): Theme & popup behavior controls, 1-tap installed app blacklist picker, and JSON backup/restore.
 */
class SettingsTabController(
    private val activity: Activity,
    private val onRebuildUi: () -> Unit,
    private val onExportJsonFile: () -> Unit,
    private val onImportJsonFile: () -> Unit
) {
    private lateinit var blacklistInput: EditText
    private lateinit var blacklistContainer: LinearLayout
    private lateinit var backupPreviewBox: EditText

    fun buildView(): View {
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            addView(UiKit.label(activity, "01 // THEME & POPUP BEHAVIOR", 11.5f, p.fgSecondaryInt, bold = true))
            addView(
                UiKit.label(
                    activity,
                    "Customize theme palette, 1-column vs 2-column popup layout, screen edge side, and tool sorting:",
                    10.5f,
                    p.fgMutedInt
                ).apply { setPadding(0, dp(2), 0, dp(6)) }
            )
            addView(buildThemeAndBehaviorCard())

            addView(UiKit.label(activity, "\n02 // PER-APP BLACKLIST (⊘ MUTED APPS)", 11.5f, p.fgSecondaryInt, bold = true))
            addView(
                UiKit.label(
                    activity,
                    "Muted apps will not trigger automatic selection popups. Pick from installed apps or enter a package name:",
                    10.5f,
                    p.fgMutedInt
                ).apply { setPadding(0, dp(2), 0, dp(6)) }
            )

            val manualPkgRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
                setPadding(0, dp(4), 0, dp(6))
            }

            blacklistInput = EditText(activity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11.5f
                setTextColor(p.fgPrimaryInt)
                setBackgroundColor(p.bgSurfaceInt)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                hint = "com.example.app"
                setHintTextColor(p.fgMutedInt)
                isSingleLine = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val addBlkBtn = UiKit.buttonPill(activity, " [ + MUTE ]", p.accentGreenInt) {
                val pkg = blacklistInput.text.toString().trim()
                if (pkg.isNotEmpty()) {
                    ToolPrefs.setAppBlacklisted(activity, pkg, true)
                    blacklistInput.setText("")
                    refreshBlacklistView()
                }
            }
            manualPkgRow.addView(blacklistInput)
            manualPkgRow.addView(addBlkBtn)

            val pickerActionRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(6))
            }
            pickerActionRow.addView(
                UiKit.equalButtonPill(activity, "[ + PICK INSTALLED APP ]", p.accentGreenInt) {
                    showInstalledAppsBlacklistDialog()
                }
            )

            lateinit var manualPkgToggle: TextView
            manualPkgToggle = UiKit.equalButtonPill(activity, "[ + BY PACKAGE ID ▾ ]", p.fgMutedInt) {
                val show = manualPkgRow.visibility != View.VISIBLE
                manualPkgRow.visibility = if (show) View.VISIBLE else View.GONE
                manualPkgToggle.text = if (show) "[ + BY PACKAGE ID ▴ ]" else "[ + BY PACKAGE ID ▾ ]"
            }
            pickerActionRow.addView(manualPkgToggle)

            addView(pickerActionRow)
            addView(manualPkgRow)

            blacklistContainer = UiKit.surfaceCard(activity)
            addView(blacklistContainer)

            addView(UiKit.label(activity, "\n03 // BACKUP & RESTORE CONFIG (.JSON)", 11.5f, p.fgSecondaryInt, bold = true))
            addView(
                UiKit.label(
                    activity,
                    "Export or restore all settings, custom folders, top icon rows, tool order, muted apps, and rules:",
                    10.5f,
                    p.fgMutedInt
                ).apply { setPadding(0, dp(2), 0, dp(6)) }
            )

            val fileBtnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            fileBtnRow.addView(UiKit.equalButtonPill(activity, "[ ⇩ EXPORT .JSON ]", p.accentGreenInt) { onExportJsonFile() })
            fileBtnRow.addView(UiKit.equalButtonPill(activity, "[ ⇧ IMPORT .JSON ]", p.fgPrimaryInt) { onImportJsonFile() })
            addView(fileBtnRow)

            val clipBtnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            clipBtnRow.addView(UiKit.equalButtonPill(activity, "[ [^C] COPY JSON ]", p.accentCyanInt) {
                val json = ToolPrefs.exportBackupJson(activity)
                backupPreviewBox.setText(json)
                UiKit.copyPlain(activity, json, "Copied config JSON to clipboard")
            })
            clipBtnRow.addView(UiKit.equalButtonPill(activity, "[ [^V] RESTORE CLIP ]", p.fgPrimaryInt) {
                val candidate = backupPreviewBox.text.toString().takeIf { it.isNotBlank() } ?: run {
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString() ?: ""
                }
                if (ToolPrefs.importBackupJson(activity, candidate)) {
                    SelAccessibilityService.instance?.refreshOpenPopupTheme()
                    onRebuildUi()
                    Toast.makeText(activity, "✓ Settings & custom folders restored!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "Copy a valid backup JSON first", Toast.LENGTH_SHORT).show()
                }
            })
            addView(clipBtnRow)

            val shareAndRawRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(6))
            }
            shareAndRawRow.addView(UiKit.equalButtonPill(activity, "[ ↗ SHARE BUNDLE ]", p.accentPurpleInt) {
                val json = ToolPrefs.exportBackupJson(activity)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_TEXT, json)
                    putExtra(Intent.EXTRA_SUBJECT, "sel-android-backup.json")
                }
                activity.startActivity(Intent.createChooser(share, "Export [sel] Config"))
            })

            backupPreviewBox = EditText(activity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 10f
                setTextColor(p.fgPrimaryInt)
                setBackgroundColor(p.bgSurfaceInt)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                minLines = 5
                maxLines = 8
                visibility = View.GONE
                hint = "Paste backup JSON here…"
                setHintTextColor(p.fgMutedInt)
            }

            lateinit var rawJsonToggle: TextView
            rawJsonToggle = UiKit.equalButtonPill(activity, "[ RAW JSON ▾ ]", p.fgMutedInt) {
                val show = backupPreviewBox.visibility != View.VISIBLE
                if (show) backupPreviewBox.setText(ToolPrefs.exportBackupJson(activity))
                backupPreviewBox.visibility = if (show) View.VISIBLE else View.GONE
                rawJsonToggle.text = if (show) "[ RAW JSON ▴ ]" else "[ RAW JSON ▾ ]"
            }
            shareAndRawRow.addView(rawJsonToggle)

            addView(shareAndRawRow)
            addView(backupPreviewBox)
        }
    }

    private fun buildThemeAndBehaviorCard(): View {
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        val followSys = ToolPrefs.isFollowSystemTheme(activity)
        val sysDark = ToolPrefs.isSystemInDarkMode(activity)
        val twoCol = ToolPrefs.isTwoColumnGrid(activity)
        val snapLeft = ToolPrefs.isSnapLeftEdge(activity)
        val sortMostUsed = ToolPrefs.isSortMostUsed(activity)

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(p.bgSurfaceInt)
                setStroke(dp(1), p.borderInt)
                cornerRadius = dp(3).toFloat()
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))

            addView(
                UiKit.label(
                    activity,
                    "THEME PALETTE: ${p.name} (${if (p.isDark) "DARK" else "LIGHT"})" +
                        if (followSys) " · AUTO (${if (sysDark) "DARK" else "LIGHT"})" else " · LOCKED",
                    10.5f,
                    p.accentGreenInt,
                    bold = true
                ).apply { setPadding(0, 0, 0, dp(4)) }
            )

            val themeRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            themeRow.addView(
                UiKit.equalButtonPill(
                    activity,
                    if (followSys) "[ AUTO: ON ]" else "[ AUTO: OFF ]",
                    if (followSys) p.accentGreenInt else p.fgMutedInt
                ) {
                    ToolPrefs.setFollowSystemTheme(activity, !followSys)
                    SelAccessibilityService.instance?.refreshOpenPopupTheme()
                    onRebuildUi()
                }
            )
            themeRow.addView(
                UiKit.equalButtonPill(
                    activity,
                    if (p.isDark) "[ ALUCARD LIGHT ]" else "[ DRACULA DARK ]",
                    p.accentPurpleInt
                ) {
                    ToolPrefs.setFollowSystemTheme(activity, false)
                    ToolPrefs.setDarkThemeManual(activity, !p.isDark)
                    SelAccessibilityService.instance?.refreshOpenPopupTheme()
                    onRebuildUi()
                }
            )
            addView(themeRow)

            addView(
                UiKit.label(activity, "POPUP LAYOUT & SCREEN EDGE:", 10.5f, p.fgSecondaryInt, bold = true)
                    .apply { setPadding(0, dp(8), 0, dp(4)) }
            )

            val behaviorRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            behaviorRow.addView(
                UiKit.equalButtonPill(
                    activity,
                    if (twoCol) "[ GRID: 2-COL ]" else "[ LIST: 1-COL ]",
                    if (twoCol) p.accentCyanInt else p.fgPrimaryInt
                ) {
                    ToolPrefs.setTwoColumnGrid(activity, !twoCol)
                    SelAccessibilityService.instance?.refreshOpenPopupTheme()
                    onRebuildUi()
                }
            )
            behaviorRow.addView(
                UiKit.equalButtonPill(
                    activity,
                    if (snapLeft) "[ EDGE: LEFT ]" else "[ EDGE: RIGHT ]",
                    p.fgPrimaryInt
                ) {
                    ToolPrefs.setSnapLeftEdge(activity, !snapLeft)
                    SelAccessibilityService.instance?.refreshOpenPopupTheme()
                    onRebuildUi()
                }
            )
            addView(behaviorRow)

            val sortRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
            sortRow.addView(
                UiKit.equalButtonPill(
                    activity,
                    if (sortMostUsed) "[ SORT: MOST USED FIRST ]" else "[ SORT: MANUAL ORDER ]",
                    if (sortMostUsed) p.accentGreenInt else p.fgMutedInt
                ) {
                    ToolPrefs.setSortMostUsed(activity, !sortMostUsed)
                    onRebuildUi()
                }
            )
            addView(sortRow)
        }
    }

    fun refresh(isActiveTab: Boolean) {
        if (::blacklistContainer.isInitialized) refreshBlacklistView()
        if (::backupPreviewBox.isInitialized && isActiveTab && backupPreviewBox.visibility == View.VISIBLE) {
            backupPreviewBox.setText(ToolPrefs.exportBackupJson(activity))
        }
    }

    private fun refreshBlacklistView() {
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        blacklistContainer.removeAllViews()
        val apps = ToolPrefs.getBlacklistedApps(activity)
        if (apps.isEmpty()) {
            blacklistContainer.addView(
                UiKit.label(activity, "(No muted apps — tap [ + PICK INSTALLED APP ] above to mute any app)", 10.5f, p.fgMutedInt)
            )
            return
        }
        apps.forEach { pkg ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(10), dp(9))
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
            row.addView(UiKit.label(activity, "⊘ $pkg", 11.2f, p.fgPrimaryInt).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(UiKit.label(activity, "[ UNMUTE ]", 10.5f, p.accentGreenInt, bold = true).apply {
                setPadding(dp(10), dp(4), dp(4), dp(4))
                setOnClickListener {
                    ToolPrefs.setAppBlacklisted(activity, pkg, false)
                    refreshBlacklistView()
                }
            })
            blacklistContainer.addView(row)
        }
    }

    private fun showInstalledAppsBlacklistDialog() {
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        val pm = activity.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolved = runCatching { pm.queryIntentActivities(mainIntent, 0) }.getOrNull().orEmpty()
        val appEntries = resolved
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == activity.packageName) return@mapNotNull null
                val appLabel = runCatching { info.loadLabel(pm).toString().trim() }.getOrDefault(pkg)
                appLabel to pkg
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }

        val dialogRoot = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bgPrimaryInt)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        dialogRoot.addView(
            UiKit.label(activity, "MUTE APPS // TAP TO TOGGLE [⊘]", 11.5f, p.accentGreenInt, bold = true)
                .apply { setPadding(0, 0, 0, dp(6)) }
        )

        val searchBox = EditText(activity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setTextColor(p.fgPrimaryInt)
            setBackgroundColor(p.bgSurfaceInt)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            hint = "Filter apps by name or package…"
            setHintTextColor(p.fgMutedInt)
            isSingleLine = true
        }
        dialogRoot.addView(searchBox)

        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (activity.resources.displayMetrics.heightPixels * 0.48f).toInt()
            ).apply { topMargin = dp(6) }
        }
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        fun renderApps(filterQuery: String) {
            list.removeAllViews()
            val q = filterQuery.trim().lowercase()
            val filtered = if (q.isEmpty()) appEntries else appEntries.filter {
                it.first.lowercase().contains(q) || it.second.lowercase().contains(q)
            }
            if (filtered.isEmpty()) {
                list.addView(UiKit.label(activity, "No matching apps found.", 10.5f, p.fgMutedInt).apply {
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                })
                return
            }
            filtered.forEach { (appName, pkg) ->
                val muted = ToolPrefs.isAppBlacklisted(activity, pkg)
                val mark = if (muted) "[⊘ MUTED]" else "[  ALLOW]"
                val row = UiKit.label(
                    activity,
                    "$mark  $appName\n           $pkg",
                    10.5f,
                    if (muted) p.dangerRedInt else p.fgPrimaryInt
                ).apply {
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = GradientDrawable().apply {
                        setColor(p.bgSurfaceInt)
                        setStroke(dp(1), p.dividerInt)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(3) }
                    setOnClickListener {
                        ToolPrefs.setAppBlacklisted(activity, pkg, !muted)
                        renderApps(searchBox.text.toString())
                        refreshBlacklistView()
                    }
                }
                list.addView(row)
            }
        }

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderApps(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        renderApps("")
        scroll.addView(list)
        dialogRoot.addView(scroll)

        var alert: AlertDialog? = null
        dialogRoot.addView(UiKit.buttonPill(activity, "[ ✓ DONE ]", p.accentGreenInt) {
            refreshBlacklistView()
            alert?.dismiss()
        })

        alert = AlertDialog.Builder(activity).setView(dialogRoot).create()
        alert.show()
    }
}
