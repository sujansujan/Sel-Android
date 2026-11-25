package io.github.selclip.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
import io.github.selclip.SelAccessibilityService
import io.github.selclip.data.PrefsStore
import io.github.selclip.data.ThemeMode
import java.io.File

/**
 * Tab 3 (Settings): Appearance, Muted Apps, Clips & Scratchpad Notes, and JSON Backup.
 */
class SettingsTab(
    private val activity: Activity,
    private val onRebuildUi: () -> Unit,
    private val onExportJson: () -> Unit,
    private val onImportJson: () -> Unit
) {

    private lateinit var mutedAppsContainer: LinearLayout
    private lateinit var clipsToggleBtn: TextView
    private lateinit var clipsBody: LinearLayout
    private lateinit var notesInput: EditText

    fun buildView(): View {
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        val themeMode = PrefsStore.getThemeMode(activity)

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            // 1. Theme
            addView(UiKit.sectionTitle(activity, "Appearance"))
            val themeRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            themeRow.addView(UiKit.equalPill(activity, "Auto", p.accentSuccessInt, selected = themeMode == ThemeMode.AUTO) {
                PrefsStore.setThemeMode(activity, ThemeMode.AUTO)
                SelAccessibilityService.instance?.refreshThemeIfVisible()
                onRebuildUi()
            })
            themeRow.addView(UiKit.equalPill(activity, "Dark", p.accentPrimaryInt, selected = themeMode == ThemeMode.DARK) {
                PrefsStore.setThemeMode(activity, ThemeMode.DARK)
                SelAccessibilityService.instance?.refreshThemeIfVisible()
                onRebuildUi()
            })
            themeRow.addView(UiKit.equalPill(activity, "Light", p.accentInfoInt, selected = themeMode == ThemeMode.LIGHT) {
                PrefsStore.setThemeMode(activity, ThemeMode.LIGHT)
                SelAccessibilityService.instance?.refreshThemeIfVisible()
                onRebuildUi()
            })
            addView(themeRow)

            // 2. Muted Apps
            addView(UiKit.sectionTitle(activity, "Muted Apps"))
            addView(UiKit.fullButton(activity, "Pick App to Mute", p.accentPrimaryInt) {
                showMuteAppPicker()
            })
            mutedAppsContainer = UiKit.card(activity, padH = 12, padV = 8).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            addView(mutedAppsContainer)

            // 3. Clips & Notes
            addView(UiKit.sectionTitle(activity, "Clips & Notes"))
            clipsBody = UiKit.card(activity, padH = 10, padV = 8).apply { visibility = View.GONE }
            clipsToggleBtn = UiKit.fullButton(activity, "Clipboard History (0) ▾", p.fgPrimaryInt) {
                val show = clipsBody.visibility != View.VISIBLE
                clipsBody.visibility = if (show) View.VISIBLE else View.GONE
                refreshClips()
            }
            addView(clipsToggleBtn)
            addView(clipsBody)

            val notesBox = UiKit.card(activity).apply {
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            notesInput = UiKit.inputField(activity, "Saved notes appear here…", singleLine = false).apply {
                minLines = 5
                gravity = Gravity.TOP or Gravity.START
            }
            val notesBtns = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            notesBtns.addView(UiKit.equalPill(activity, "Save", p.accentSuccessInt) {
                File(activity.filesDir, "scratchpad.txt").writeText(notesInput.text.toString())
                Toast.makeText(activity, "Saved notes", Toast.LENGTH_SHORT).show()
            })
            notesBtns.addView(UiKit.equalPill(activity, "Copy", p.accentInfoInt) {
                UiKit.copyPlain(activity, notesInput.text.toString(), "Copied notes")
            })
            notesBtns.addView(UiKit.equalPill(activity, "Clear", p.dangerInt) {
                File(activity.filesDir, "scratchpad.txt").writeText("")
                notesInput.setText("")
            })
            notesBox.addView(notesInput)
            notesBox.addView(notesBtns)

            lateinit var notesToggle: TextView
            notesToggle = UiKit.fullButton(activity, "Scratchpad Notes ▾", p.fgPrimaryInt) {
                val show = notesBox.visibility != View.VISIBLE
                notesBox.visibility = if (show) View.VISIBLE else View.GONE
                notesToggle.text = if (show) "Scratchpad Notes ▴" else "Scratchpad Notes ▾"
            }
            addView(notesToggle)
            addView(notesBox)

            // 4. Backup & Restore
            addView(UiKit.sectionTitle(activity, "Backup & Restore"))
            val backupRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            backupRow.addView(UiKit.equalPill(activity, "Export JSON", p.accentSuccessInt) { onExportJson() })
            backupRow.addView(UiKit.equalPill(activity, "Import JSON", p.fgPrimaryInt) { onImportJson() })
            addView(backupRow)
        }
    }

    fun refresh() {
        if (::mutedAppsContainer.isInitialized) refreshMutedApps()
        if (::clipsBody.isInitialized) refreshClips()
        if (::notesInput.isInitialized) {
            val f = File(activity.filesDir, "scratchpad.txt")
            val txt = if (f.exists()) f.readText() else ""
            notesInput.setText(if (txt == "(Empty)") "" else txt)
        }
    }

    private fun refreshMutedApps() {
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        mutedAppsContainer.removeAllViews()
        val apps = PrefsStore.getMutedApps(activity)
        if (apps.isEmpty()) {
            mutedAppsContainer.addView(UiKit.text(activity, "No muted apps.", 12.5f, p.fgMutedInt))
            return
        }
        apps.forEach { pkg ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(40)
            }
            row.addView(UiKit.text(activity, pkg, 13f, p.fgPrimaryInt).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(UiKit.text(activity, "×", 17f, p.dangerInt, bold = true).apply {
                minWidth = dp(38)
                minHeight = dp(38)
                gravity = Gravity.CENTER
                setOnClickListener {
                    PrefsStore.setAppMuted(activity, pkg, false)
                    refreshMutedApps()
                }
            })
            mutedAppsContainer.addView(row)
        }
    }

    private fun refreshClips() {
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        val clips = PrefsStore.getClipHistory(activity)
        val open = clipsBody.visibility == View.VISIBLE
        clipsToggleBtn.text = "Clipboard History (${clips.size}) ${if (open) "▴" else "▾"}"
        if (!open) return

        clipsBody.removeAllViews()
        if (clips.isEmpty()) {
            clipsBody.addView(UiKit.text(activity, "No saved clips yet.", 12.5f, p.fgMutedInt))
            return
        }
        clips.forEach { entry ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(42)
            }
            row.addView(UiKit.text(activity, if (entry.pinned) "★" else "☆", 15f, if (entry.pinned) p.accentSuccessInt else p.fgMutedInt, bold = true).apply {
                minWidth = dp(38)
                minHeight = dp(40)
                gravity = Gravity.CENTER
                setOnClickListener {
                    PrefsStore.togglePinClip(activity, entry.text)
                    refreshClips()
                }
            })
            row.addView(UiKit.text(activity, entry.text.replace("\n", " ↵ "), 13f, p.fgPrimaryInt).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { UiKit.copyPlain(activity, entry.text, "Copied clip") }
            })
            row.addView(UiKit.text(activity, "×", 17f, p.dangerInt, bold = true).apply {
                minWidth = dp(38)
                minHeight = dp(40)
                gravity = Gravity.CENTER
                setOnClickListener {
                    PrefsStore.deleteClip(activity, entry.text)
                    refreshClips()
                }
            })
            clipsBody.addView(row)
        }
        clipsBody.addView(UiKit.fullButton(activity, "Clear Unpinned Clips", p.dangerInt) {
            PrefsStore.clearUnpinnedClips(activity)
            refreshClips()
        })
    }

    private fun showMuteAppPicker() {
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        val pm = activity.packageManager
        val launcher = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val apps = runCatching { pm.queryIntentActivities(launcher, 0) }.getOrNull().orEmpty()
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
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        dialogRoot.addView(UiKit.text(activity, "Mute Popup Per App", 15f, p.fgPrimaryInt, bold = true).apply {
            setPadding(0, 0, 0, dp(8))
        })

        val filterBox = UiKit.inputField(activity, "Search installed apps…")
        dialogRoot.addView(filterBox)

        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (activity.resources.displayMetrics.heightPixels * 0.48f).toInt()
            ).apply { topMargin = dp(8) }
        }
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        fun renderList(q: String) {
            list.removeAllViews()
            val query = q.trim().lowercase()
            val filtered = if (query.isEmpty()) apps else apps.filter {
                it.first.lowercase().contains(query) || it.second.lowercase().contains(query)
            }
            filtered.forEach { (appName, pkg) ->
                val muted = PrefsStore.isAppMuted(activity, pkg)
                val row = UiKit.text(
                    activity,
                    "${if (muted) "Muted  ·  " else ""}$appName",
                    13.5f,
                    if (muted) p.dangerInt else p.fgPrimaryInt,
                    bold = muted
                ).apply {
                    minHeight = dp(44)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = GradientDrawable().apply {
                        setColor(p.bgSurfaceInt)
                        setStroke(dp(1), p.dividerInt)
                        cornerRadius = UiKit.dpF(activity, 8f)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(4) }
                    setOnClickListener {
                        PrefsStore.setAppMuted(activity, pkg, !muted)
                        renderList(filterBox.text.toString())
                        refreshMutedApps()
                    }
                }
                list.addView(row)
            }
        }

        filterBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderList(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) {}
        })

        renderList("")
        scroll.addView(list)
        dialogRoot.addView(scroll)

        var dialog: AlertDialog? = null
        dialogRoot.addView(UiKit.fullButton(activity, "Done", p.accentSuccessInt, filled = true) {
            refreshMutedApps()
            dialog?.dismiss()
        })

        dialog = AlertDialog.Builder(activity).setView(dialogRoot).create()
        dialog.show()
    }
}
