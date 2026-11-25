package io.github.selclip

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.selclip.data.PrefsStore
import io.github.selclip.data.RulesRepository
import io.github.selclip.data.ThemeMode
import io.github.selclip.ui.ActionsTab
import io.github.selclip.ui.HomeTab
import io.github.selclip.ui.SettingsTab
import io.github.selclip.ui.UiKit

/**
 * Decoupled 3-tab mobile companion UI (Home | Actions | Settings).
 * Never loaded into RAM by the background AccessibilityService until the user opens the app.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_EXPORT_JSON = 1001
        private const val REQ_IMPORT_JSON = 1002
    }

    private enum class Tab(val title: String) {
        HOME("Home"),
        ACTIONS("Actions"),
        SETTINGS("Settings")
    }

    private var activeTab: Tab = Tab.HOME
    private var lastRenderedDark: Boolean? = null
    private val tabButtons = mutableMapOf<Tab, TextView>()
    private val tabPanels = mutableMapOf<Tab, View>()

    private lateinit var serviceBadge: TextView
    private lateinit var homeTab: HomeTab
    private lateinit var actionsTab: ActionsTab
    private lateinit var settingsTab: SettingsTab

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RulesRepository.ensureRulesFile(this)
        rebuildUi()
    }

    override fun onResume() {
        super.onResume()
        refreshAllTabs()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (PrefsStore.getThemeMode(this) == ThemeMode.AUTO) {
            val nowDark = PrefsStore.resolvePalette(this).isDark
            if (lastRenderedDark != null && lastRenderedDark != nowDark) {
                rebuildUi()
                SelAccessibilityService.instance?.refreshThemeIfVisible()
            }
        }
    }

    private fun rebuildUi() {
        val p = UiKit.pal(this)
        val dp = { v: Int -> UiKit.dp(this, v) }
        lastRenderedDark = p.isDark

        runCatching {
            window.statusBarColor = p.bgHeaderInt
            window.navigationBarColor = p.bgPrimaryInt
        }

        tabButtons.clear()
        tabPanels.clear()

        homeTab = HomeTab(this)
        actionsTab = ActionsTab(this)
        settingsTab = SettingsTab(
            activity = this,
            onRebuildUi = { rebuildUi() },
            onExportJson = { launchExportJson() },
            onImportJson = { launchImportJson() }
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bgPrimaryInt)
        }

        // Top App Bar
        val topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(p.bgHeaderInt)
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        topHeader.addView(UiKit.text(this, "Sel", 20f, p.fgPrimaryInt, bold = true))
        topHeader.addView(UiKit.text(this, "  v4.3", 12.5f, p.fgMutedInt).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        serviceBadge = UiKit.text(this, "Enable ↗", 12f, p.dangerInt, bold = true).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(p.bgSurfaceInt)
                setStroke(dp(1), p.borderInt)
                cornerRadius = UiKit.dpF(this@MainActivity, 16f)
            }
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        topHeader.addView(serviceBadge)
        root.addView(topHeader)

        // Segmented 3-Tab Pill Bar
        val tabBarWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(p.bgHeaderInt)
            setPadding(dp(14), dp(2), dp(14), dp(12))
        }
        val segmented = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                setColor(p.bgSurfaceInt)
                setStroke(dp(1), p.borderInt)
                cornerRadius = UiKit.dpF(this@MainActivity, 12f)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        Tab.values().forEach { tab ->
            val btn = UiKit.text(this, tab.title, 13f, p.fgMutedInt, bold = true).apply {
                gravity = Gravity.CENTER
                minHeight = dp(38)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { switchTab(tab) }
            }
            tabButtons[tab] = btn
            segmented.addView(btn)
        }
        tabBarWrap.addView(segmented)
        root.addView(tabBarWrap)

        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(p.dividerInt)
        })

        // Scrollable Body
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(28))
        }

        tabPanels[Tab.HOME] = homeTab.buildView()
        tabPanels[Tab.ACTIONS] = actionsTab.buildView()
        tabPanels[Tab.SETTINGS] = settingsTab.buildView()
        Tab.values().forEach { t -> tabPanels[t]?.let { host.addView(it) } }

        scroll.addView(host)
        root.addView(scroll)

        setContentView(root)
        switchTab(activeTab)
    }

    private fun switchTab(target: Tab) {
        val p = UiKit.pal(this)
        activeTab = target
        Tab.values().forEach { tab ->
            val selected = tab == target
            tabPanels[tab]?.visibility = if (selected) View.VISIBLE else View.GONE
            tabButtons[tab]?.apply {
                if (selected) {
                    setTextColor(p.onAccentInt)
                    background = GradientDrawable().apply {
                        setColor(p.accentPrimaryInt)
                        cornerRadius = UiKit.dpF(this@MainActivity, 9f)
                    }
                } else {
                    setTextColor(p.fgMutedInt)
                    background = null
                }
            }
        }
        refreshAllTabs()
    }

    private fun refreshAllTabs() {
        val p = UiKit.pal(this)
        val active = SelAccessibilityService.instance != null
        serviceBadge.text = if (active) "● Active" else "Enable ↗"
        serviceBadge.setTextColor(if (active) p.accentSuccessInt else p.dangerInt)

        homeTab.refresh()
        actionsTab.refresh()
        settingsTab.refresh()
    }

    private fun launchExportJson() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "sel-android-backup.json")
        }
        @Suppress("DEPRECATION")
        runCatching { startActivityForResult(intent, REQ_EXPORT_JSON) }
    }

    private fun launchImportJson() {
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
                    val json = PrefsStore.exportBackupJson(this)
                    contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    true
                }.getOrDefault(false)
                Toast.makeText(this, if (ok) "Exported backup JSON" else "Export failed", Toast.LENGTH_SHORT).show()
            }
            REQ_IMPORT_JSON -> {
                val ok = runCatching {
                    val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    PrefsStore.importBackupJson(this, json)
                }.getOrDefault(false)
                if (ok) {
                    SelAccessibilityService.instance?.refreshThemeIfVisible()
                    rebuildUi()
                    Toast.makeText(this, "Settings restored", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Invalid backup file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
