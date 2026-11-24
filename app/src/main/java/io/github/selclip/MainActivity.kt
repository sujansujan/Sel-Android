package io.github.selclip

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Slim shell Activity hosting the 5 plain-text terminal tabs via dedicated controllers:
 * HomeTabController, SettingsTabController, ToolsTabController, RulesTabController, and ClipsTabController.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_EXPORT_JSON = 1001
        private const val REQ_IMPORT_JSON = 1002
    }

    private enum class Tab(val title: String) {
        HOME("HOME"),
        BACKUP("SET"),
        TOOLS("TOOLS"),
        RULES("RULES"),
        CLIPS("CLIPS")
    }

    private var activeTab: Tab = Tab.HOME
    private var lastRenderedIsDark: Boolean? = null
    private val tabButtons = mutableMapOf<Tab, TextView>()
    private val tabPanels = mutableMapOf<Tab, View>()

    private lateinit var serviceStatusBadge: TextView
    private lateinit var homeTab: HomeTabController
    private lateinit var settingsTab: SettingsTabController
    private lateinit var toolsTab: ToolsTabController
    private lateinit var rulesTab: RulesTabController
    private lateinit var clipsTab: ClipsTabController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ToolPrefs.ensureRulesFile(this)
        rebuildUi()
    }

    override fun onResume() {
        super.onResume()
        refreshAllTabs()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (ToolPrefs.isFollowSystemTheme(this)) {
            val nowDark = ToolPrefs.resolvePalette(this).isDark
            if (lastRenderedIsDark != null && lastRenderedIsDark != nowDark) {
                rebuildUi()
                SelAccessibilityService.instance?.refreshOpenPopupTheme()
            }
        }
    }

    private fun rebuildUi() {
        val p = UiKit.pal(this)
        val dp = { v: Int -> UiKit.dp(this, v) }
        lastRenderedIsDark = p.isDark

        runCatching {
            window.statusBarColor = p.bgHeaderInt
            window.navigationBarColor = p.bgPrimaryInt
        }

        tabButtons.clear()
        tabPanels.clear()

        homeTab = HomeTabController(this) { switchTab(Tab.BACKUP) }
        settingsTab = SettingsTabController(
            activity = this,
            onRebuildUi = { rebuildUi() },
            onExportJsonFile = { launchExportJsonFile() },
            onImportJsonFile = { launchImportJsonFile() }
        )
        toolsTab = ToolsTabController(this) { rulesTab.refresh() }
        rulesTab = RulesTabController(this) { toolsTab.refresh() }
        clipsTab = ClipsTabController(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bgPrimaryInt)
        }

        // 1. Fixed Top Terminal Header with Clickable Status Pill
        val topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bgHeaderInt)
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleLabel = UiKit.label(this, "[sel_] // PLAIN-TEXT PALETTE", 14f, p.fgPrimaryInt, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        serviceStatusBadge = UiKit.label(this, "[ ○ ENABLE ↗ ]", 10.5f, p.dangerRedInt, bold = true).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(p.bgSurfaceInt)
                setStroke(dp(1), p.borderInt)
                cornerRadius = dp(2).toFloat()
            }
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        titleRow.addView(titleLabel)
        titleRow.addView(serviceStatusBadge)
        topHeader.addView(titleRow)
        topHeader.addView(
            UiKit.label(
                this,
                "+-- v2.7.0 · ${p.name} theme · universal popup · lasso + OCR --+",
                9.8f,
                p.fgMutedInt
            ).apply { setPadding(0, dp(3), 0, 0) }
        )
        root.addView(topHeader)

        // 2. Full-Width 5-Column Tab Bar (HOME | SET | TOOLS | RULES | CLIPS)
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(p.bgSurfaceInt)
            setPadding(dp(6), dp(5), dp(6), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        Tab.values().forEach { tab ->
            val btn = TextView(this).apply {
                text = "[${tab.title}]"
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = Gravity.CENTER
                isSingleLine = true
                includeFontPadding = false
                setPadding(dp(2), dp(8), dp(2), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
                setOnClickListener { switchTab(tab) }
            }
            tabButtons[tab] = btn
            tabRow.addView(btn)
        }
        root.addView(tabRow)

        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(p.dividerInt)
        })

        // 3. Scrollable Tab Content Area
        val bodyScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val bodyHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(24))
        }

        tabPanels[Tab.HOME] = homeTab.buildView()
        tabPanels[Tab.BACKUP] = settingsTab.buildView()
        tabPanels[Tab.TOOLS] = toolsTab.buildView()
        tabPanels[Tab.RULES] = rulesTab.buildView()
        tabPanels[Tab.CLIPS] = clipsTab.buildView()

        Tab.values().forEach { t -> tabPanels[t]?.let { bodyHost.addView(it) } }
        bodyScroll.addView(bodyHost)
        root.addView(bodyScroll)

        setContentView(root)
        switchTab(activeTab)
    }

    private fun switchTab(target: Tab) {
        val p = UiKit.pal(this)
        val dp = { v: Int -> UiKit.dp(this, v) }
        activeTab = target
        Tab.values().forEach { tab ->
            val active = (tab == target)
            tabPanels[tab]?.visibility = if (active) View.VISIBLE else View.GONE
            tabButtons[tab]?.apply {
                if (active) {
                    setTextColor(p.onAccentInt)
                    background = GradientDrawable().apply {
                        setColor(p.accentPurpleInt)
                        cornerRadius = dp(3).toFloat()
                    }
                } else {
                    setTextColor(p.fgPrimaryInt)
                    background = null
                }
            }
        }
        refreshAllTabs()
    }

    private fun refreshAllTabs() {
        val p = UiKit.pal(this)
        val active = SelAccessibilityService.instance != null
        serviceStatusBadge.text = if (active) "[ ● ACTIVE ↗ ]" else "[ ○ ENABLE ↗ ]"
        serviceStatusBadge.setTextColor(if (active) p.accentGreenInt else p.dangerRedInt)

        homeTab.refresh()
        settingsTab.refresh(isActiveTab = activeTab == Tab.BACKUP)
        toolsTab.refresh()
        rulesTab.refresh()
        clipsTab.refresh()
    }

    private fun launchExportJsonFile() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "sel-android-backup.json")
        }
        @Suppress("DEPRECATION")
        runCatching { startActivityForResult(intent, REQ_EXPORT_JSON) }
    }

    private fun launchImportJsonFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        runCatching { startActivityForResult(intent, REQ_IMPORT_JSON) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri: Uri = data.data ?: return

        when (requestCode) {
            REQ_EXPORT_JSON -> {
                val ok = runCatching {
                    val json = ToolPrefs.exportBackupJson(this)
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    }
                    true
                }.getOrDefault(false)
                Toast.makeText(
                    this,
                    if (ok) "✓ Exported backup JSON" else "Failed to export file",
                    Toast.LENGTH_SHORT
                ).show()
            }
            REQ_IMPORT_JSON -> {
                val ok = runCatching {
                    val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    ToolPrefs.importBackupJson(this, json)
                }.getOrDefault(false)
                if (ok) {
                    SelAccessibilityService.instance?.refreshOpenPopupTheme()
                    rebuildUi()
                    Toast.makeText(this, "✓ Restored settings & custom folders", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Invalid backup file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
