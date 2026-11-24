package io.github.selclip

import android.app.Activity
import android.app.AlertDialog
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
import java.io.File

/**
 * Tab 3 ([SETTINGS]): Clean, zero-duplication Preferences, Muted Apps, Clips & Notes, and JSON Backup.
 */
class SettingsTabController(
    private val activity: Activity,
    private val onRebuildUi: () -> Unit,
    private val onExportJsonFile: () -> Unit,
    private val onImportJsonFile: () -> Unit
) {
    private lateinit var blacklistContainer: LinearLayout
    private lateinit var clipsToggleLabel: TextView
    private lateinit var clipsBodyContainer: LinearLayout
    private lateinit var notesEditText: EditText

    fun buildView(): View {
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        val followSys = ToolPrefs.isFollowSystemTheme(activity)
        val manualDark = ToolPrefs.isDarkThemeManual(activity)

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            // 1. Theme (3-way clean toggle: AUTO | DRACULA | ALUCARD)
            addView(UiKit.sectionTitle(activity, "THEME"))
            val themeRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            themeRow.addView(UiKit.equalButtonPill(activity, "AUTO", p.accentGreenInt, selected = followSys) {
                ToolPrefs.setFollowSystemTheme(activity, true)
                SelAccessibilityService.instance?.refreshOpenPopupTheme()
                onRebuildUi()
            })
            themeRow.addView(UiKit.equalButtonPill(activity, "DRACULA", p.accentPurpleInt, selected = !followSys && manualDark) {
                ToolPrefs.setFollowSystemTheme(activity, false)
                ToolPrefs.setDarkThemeManual(activity, true)
                SelAccessibilityService.instance?.refreshOpenPopupTheme()
                onRebuildUi()
            })
            themeRow.addView(UiKit.equalButtonPill(activity, "ALUCARD", p.accentCyanInt, selected = !followSys && !manualDark) {
                ToolPrefs.setFollowSystemTheme(activity, false)
                ToolPrefs.setDarkThemeManual(activity, false)
                SelAccessibilityService.instance?.refreshOpenPopupTheme()
                onRebuildUi()
            })
            addView(themeRow)

            // 2. Muted Apps
            addView(UiKit.sectionTitle(activity, "MUTED APPS"))
            addView(UiKit.buttonPill(activity, "[ + PICK APP TO MUTE ]", p.accentGreenInt) {
                showInstalledAppsBlacklistDialog()
            })
            blacklistContainer = UiKit.surfaceCard(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            }
            addView(blacklistContainer)

            // 3. Clipboard History & Notes (Cleanly tucked into collapsible sections)
            addView(UiKit.sectionTitle(activity, "CLIPS & NOTES"))

            clipsBodyContainer = UiKit.surfaceCard(activity).apply { visibility = View.GONE }
            clipsToggleLabel = UiKit.buttonPill(activity, "[ CLIPS (0) ▾ ]", p.fgPrimaryInt) {
                val show = clipsBodyContainer.visibility != View.VISIBLE
                clipsBodyContainer.visibility = if (show) View.VISIBLE else View.GONE
                refreshClips()
            }
            addView(clipsToggleLabel)
            addView(clipsBodyContainer)

            val notesBox = UiKit.surfaceCard(activity).apply {
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            }
            notesEditText = EditText(activity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextColor(p.fgPrimaryInt)
                setBackgroundColor(p.bgInputInt)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                minLines = 5
                gravity = Gravity.TOP or Gravity.START
                hint = "Saved notes appear here…"
                setHintTextColor(p.fgMutedInt)
            }
            val notesBtns = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            notesBtns.addView(UiKit.equalButtonPill(activity, "SAVE", p.accentGreenInt) {
                File(activity.filesDir, "scratchpad.txt").writeText(notesEditText.text.toString())
                Toast.makeText(activity, "Saved notes", Toast.LENGTH_SHORT).show()
            })
            notesBtns.addView(UiKit.equalButtonPill(activity, "COPY", p.accentCyanInt) {
                UiKit.copyPlain(activity, notesEditText.text.toString(), "Copied notes")
            })
            notesBtns.addView(UiKit.equalButtonPill(activity, "CLEAR", p.dangerRedInt) {
                File(activity.filesDir, "scratchpad.txt").writeText("")
                notesEditText.setText("")
            })
            notesBox.addView(notesEditText)
            notesBox.addView(notesBtns)

            lateinit var notesToggle: TextView
            notesToggle = UiKit.buttonPill(activity, "[ SCRATCHPAD NOTES ▾ ]", p.fgPrimaryInt) {
                val show = notesBox.visibility != View.VISIBLE
                notesBox.visibility = if (show) View.VISIBLE else View.GONE
                notesToggle.text = if (show) "[ SCRATCHPAD NOTES ▴ ]" else "[ SCRATCHPAD NOTES ▾ ]"
            }
            addView(notesToggle)
            addView(notesBox)

            // 4. Backup (Just 2 clean buttons!)
            addView(UiKit.sectionTitle(activity, "BACKUP"))
            val backupRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            backupRow.addView(UiKit.equalButtonPill(activity, "[ EXPORT .JSON ]", p.accentGreenInt) { onExportJsonFile() })
            backupRow.addView(UiKit.equalButtonPill(activity, "[ IMPORT .JSON ]", p.fgPrimaryInt) { onImportJsonFile() })
            addView(backupRow)
        }
    }

    fun refresh() {
        if (::blacklistContainer.isInitialized) refreshBlacklist()
        if (::clipsBodyContainer.isInitialized) refreshClips()
        if (::notesEditText.isInitialized) {
            val f = File(activity.filesDir, "scratchpad.txt")
            val txt = if (f.exists()) f.readText() else ""
            notesEditText.setText(if (txt == "(Empty)") "" else txt)
        }
    }

    private fun refreshBlacklist() {
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        blacklistContainer.removeAllViews()
        val apps = ToolPrefs.getBlacklistedApps(activity)
        if (apps.isEmpty()) {
            blacklistContainer.addView(UiKit.label(activity, "(No muted apps)", 10.2f, p.fgMutedInt))
            return
        }
        apps.forEach { pkg ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(5), dp(4), dp(5))
            }
            row.addView(UiKit.label(activity, pkg, 10.6f, p.fgPrimaryInt).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(UiKit.label(activity, "[x]", 11f, p.dangerRedInt, bold = true).apply {
                setPadding(dp(8), dp(2), dp(2), dp(2))
                setOnClickListener {
                    ToolPrefs.setAppBlacklisted(activity, pkg, false)
                    refreshBlacklist()
                }
            })
            blacklistContainer.addView(row)
        }
    }

    private fun refreshClips() {
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        val clips = ToolPrefs.getClipHistory(activity)
        val open = clipsBodyContainer.visibility == View.VISIBLE
        clipsToggleLabel.text = "[ CLIPS (${clips.size}) ${if (open) "▴" else "▾"} ]"
        if (!open) return

        clipsBodyContainer.removeAllViews()
        if (clips.isEmpty()) {
            clipsBodyContainer.addView(UiKit.label(activity, "(No saved clips yet)", 10.2f, p.fgMutedInt))
            return
        }

        clips.forEach { entry ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(6), dp(2), dp(6))
            }
            row.addView(UiKit.label(activity, if (entry.pinned) "[*]" else "[.]", 10.8f, if (entry.pinned) p.accentGreenInt else p.fgMutedInt, bold = true).apply {
                setPadding(dp(2), dp(2), dp(8), dp(2))
                setOnClickListener {
                    ToolPrefs.togglePinClip(activity, entry.text)
                    refreshClips()
                }
            })
            row.addView(UiKit.label(activity, entry.text.replace("\n", " ↵ "), 10.8f, p.fgPrimaryInt).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { UiKit.copyPlain(activity, entry.text, "[^C] Copied") }
            })
            row.addView(UiKit.label(activity, "[x]", 11f, p.dangerRedInt, bold = true).apply {
                setPadding(dp(8), dp(2), dp(2), dp(2))
                setOnClickListener {
                    ToolPrefs.deleteClip(activity, entry.text)
                    refreshClips()
                }
            })
            clipsBodyContainer.addView(row)
        }

        clipsBodyContainer.addView(UiKit.buttonPill(activity, "[ CLEAR UNPINNED ]", p.dangerRedInt) {
            ToolPrefs.clearUnpinnedClips(activity)
            refreshClips()
        })
    }

    private fun showInstalledAppsBlacklistDialog() {
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        val pm = activity.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val appEntries = runCatching { pm.queryIntentActivities(mainIntent, 0) }.getOrNull().orEmpty()
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == activity.packageName) return@mapNotNull null
                runCatching { info.loadLabel(pm).toString().trim() }.getOrDefault(pkg) to pkg
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }

        val dialogRoot = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bgPrimaryInt)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        dialogRoot.addView(UiKit.label(activity, "MUTE APPS // TAP TO TOGGLE", 11.2f, p.accentGreenInt, bold = true)
            .apply { setPadding(0, 0, 0, dp(6)) })

        val searchBox = EditText(activity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11.2f
            setTextColor(p.fgPrimaryInt)
            setBackgroundColor(p.bgSurfaceInt)
            setPadding(dp(8), dp(7), dp(8), dp(7))
            hint = "Filter apps…"
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

        fun renderApps(q: String) {
            list.removeAllViews()
            val query = q.trim().lowercase()
            val filtered = if (query.isEmpty()) appEntries else appEntries.filter {
                it.first.lowercase().contains(query) || it.second.lowercase().contains(query)
            }
            filtered.forEach { (appName, pkg) ->
                val muted = ToolPrefs.isAppBlacklisted(activity, pkg)
                val row = UiKit.label(
                    activity,
                    "${if (muted) "[MUTE]" else "[ ON ]"}  $appName",
                    10.8f,
                    if (muted) p.dangerRedInt else p.fgPrimaryInt
                ).apply {
                    setPadding(dp(8), dp(8), dp(8), dp(8))
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
                        refreshBlacklist()
                    }
                }
                list.addView(row)
            }
        }

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderApps(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) {}
        })

        renderApps("")
        scroll.addView(list)
        dialogRoot.addView(scroll)

        var alert: AlertDialog? = null
        dialogRoot.addView(UiKit.buttonPill(activity, "[ DONE ]", p.accentGreenInt) {
            refreshBlacklist()
            alert?.dismiss()
        })

        alert = AlertDialog.Builder(activity).setView(dialogRoot).create()
        alert.show()
    }
}
