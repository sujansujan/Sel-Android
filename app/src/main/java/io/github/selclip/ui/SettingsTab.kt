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
import android.widget.LinearLayout
import android.widget.ScrollView
import io.github.selclip.SelAccessibilityService
import io.github.selclip.data.PrefsStore
import io.github.selclip.data.ThemeMode

/**
 * Tab 3 (Settings): Popup Style (Labels vs Icons Only), Appearance, Muted Apps, and JSON Backup.
 * (Clipboard History & Notes completely removed.)
 */
class SettingsTab(
    private val activity: Activity,
    private val onRebuildUi: () -> Unit,
    private val onExportJson: () -> Unit,
    private val onImportJson: () -> Unit
) {

    private lateinit var mutedAppsContainer: LinearLayout

    fun buildView(): View {
        val p = UiKit.pal(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        val themeMode = PrefsStore.getThemeMode(activity)
        val iconsOnly = PrefsStore.isIconsOnlyMode(activity)

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            // 1. Popup Bar Style (Labels vs Icons Only with Auto-Fetched Favicons)
            addView(UiKit.sectionTitle(activity, "Popup Bar Style"))
            addView(
                UiKit.text(
                    activity,
                    "Icons Only shows crisp native icons and auto-fetched favicons for your search portals.",
                    12.5f,
                    p.fgMutedInt
                ).apply { setPadding(dp(2), 0, dp(2), dp(8)) }
            )
            val styleRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            styleRow.addView(UiKit.equalPill(activity, "Labels", p.accentPrimaryInt, selected = !iconsOnly) {
                PrefsStore.setIconsOnlyMode(activity, false)
                SelAccessibilityService.instance?.refreshThemeIfVisible()
                onRebuildUi()
            })
            styleRow.addView(UiKit.equalPill(activity, "Icons Only", p.accentPrimaryInt, selected = iconsOnly) {
                PrefsStore.setIconsOnlyMode(activity, true)
                SelAccessibilityService.instance?.refreshThemeIfVisible()
                onRebuildUi()
            })
            addView(styleRow)

            // 2. Theme (Auto follows Android 12+ Material You dynamic colors)
            addView(UiKit.sectionTitle(activity, "Appearance"))
            val themeRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            themeRow.addView(UiKit.equalPill(activity, "Auto (Native)", p.accentPrimaryInt, selected = themeMode == ThemeMode.AUTO) {
                PrefsStore.setThemeMode(activity, ThemeMode.AUTO)
                SelAccessibilityService.instance?.refreshThemeIfVisible()
                onRebuildUi()
            })
            themeRow.addView(UiKit.equalPill(activity, "Dark", p.accentPrimaryInt, selected = themeMode == ThemeMode.DARK) {
                PrefsStore.setThemeMode(activity, ThemeMode.DARK)
                SelAccessibilityService.instance?.refreshThemeIfVisible()
                onRebuildUi()
            })
            themeRow.addView(UiKit.equalPill(activity, "Light", p.accentPrimaryInt, selected = themeMode == ThemeMode.LIGHT) {
                PrefsStore.setThemeMode(activity, ThemeMode.LIGHT)
                SelAccessibilityService.instance?.refreshThemeIfVisible()
                onRebuildUi()
            })
            addView(themeRow)

            // 3. Muted Apps
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
                minimumHeight = dp(40)
            }
            row.addView(UiKit.text(activity, pkg, 13f, p.fgPrimaryInt).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(UiKit.text(activity, "×", 17f, p.dangerInt, bold = true).apply {
                minWidth = dp(38)
                minimumHeight = dp(38)
                gravity = Gravity.CENTER
                setOnClickListener {
                    PrefsStore.setAppMuted(activity, pkg, false)
                    refreshMutedApps()
                }
            })
            mutedAppsContainer.addView(row)
        }
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
                    minimumHeight = dp(44)
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
        dialogRoot.addView(UiKit.fullButton(activity, "Done", p.accentPrimaryInt, filled = true) {
            refreshMutedApps()
            dialog?.dismiss()
        })

        dialog = AlertDialog.Builder(activity).setView(dialogRoot).create()
        dialog.show()
    }
}
