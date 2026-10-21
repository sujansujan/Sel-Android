package io.github.selclip

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    companion object {
        private const val REQ_EXPORT_JSON = 1001
        private const val REQ_IMPORT_JSON = 1002
    }

    // All 5 tabs fit on a single screen width (zero horizontal scrolling), with SET right next to HOME
    private enum class Tab(val title: String) {
        HOME("HOME"),
        BACKUP("SET"),
        TOOLS("TOOLS"),
        RULES("RULES"),
        CLIPS("CLIPS")
    }

    private data class QuickPreset(
        val chipLabel: String,
        val glyph: String,
        val ruleLabel: String,
        val targetUrl: String
    )

    private val popularSearchPresets = listOf(
        QuickPreset("+REDDIT", "RDT", "SEARCH/REDDIT", "https://www.reddit.com/search/?q={text}"),
        QuickPreset("+MAPS", "MAP", "SEARCH/GOOGLE MAPS", "https://www.google.com/maps/search/?api=1&query={text}"),
        QuickPreset("+PERPLEXITY", "PPL", "SEARCH/PERPLEXITY", "https://www.perplexity.ai/search?q={text}"),
        QuickPreset("+GITHUB", "GH", "SEARCH/GITHUB", "https://github.com/search?q={text}"),
        QuickPreset("+IMDB", "IMD", "SEARCH/IMDB", "https://www.imdb.com/find/?q={text}"),
        QuickPreset("+CHATGPT", "GPT", "SEARCH/CHATGPT", "https://chatgpt.com/?q={text}")
    )

    private var activeTab: Tab = Tab.HOME
    private var lastRenderedIsDark: Boolean? = null
    private var selectedRuleDestination: String = "SEARCH/"
    private val tabButtons = mutableMapOf<Tab, TextView>()
    private val tabPanels = mutableMapOf<Tab, View>()

    private lateinit var serviceStatusBadge: TextView
    private lateinit var homeOnboardingCard: LinearLayout
    private lateinit var homeSandboxInput: EditText
    private lateinit var iconRowsSummaryContainer: LinearLayout
    private lateinit var customFoldersContainer: LinearLayout
    private lateinit var customFolderInput: EditText
    private lateinit var reorderListContainer: LinearLayout
    private lateinit var sortModeButton: TextView
    private lateinit var visualRulesContainer: LinearLayout
    private lateinit var rulesView: EditText
    private lateinit var clipListContainer: LinearLayout
    private lateinit var logView: EditText
    private lateinit var blacklistInput: EditText
    private lateinit var blacklistContainer: LinearLayout
    private lateinit var backupPreviewBox: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ToolPrefs.ensureRulesFile(this)
        FaviconManager.prefetchAllForTools(this)
        rebuildUi()
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

    private fun pal(): ThemePalette = ToolPrefs.resolvePalette(this)

    private fun rebuildUi() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        lastRenderedIsDark = p.isDark

        runCatching {
            window.statusBarColor = Color.parseColor(p.bgHeader)
            window.navigationBarColor = Color.parseColor(p.bgPrimary)
        }

        tabButtons.clear()
        tabPanels.clear()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(p.bgPrimary))
        }

        // 1. Fixed Top Terminal Header with Clickable Status Pill
        val topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(p.bgHeader))
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleLabel = label("[sel_] // PLAIN-TEXT PALETTE", 14f, p.fgPrimary, bold = true).apply {
            this.layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        serviceStatusBadge = label("[ ○ ENABLE ↗ ]", 10.5f, p.dangerRed, bold = true).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(p.bgSurface))
                setStroke(dp(1), Color.parseColor(p.border))
                cornerRadius = dp(2).toFloat()
            }
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        titleRow.addView(titleLabel)
        titleRow.addView(serviceStatusBadge)
        topHeader.addView(titleRow)
        topHeader.addView(
            label(
                "+-- v2.6.0 · ${p.name} theme · 4-icon top rows · custom folders --+",
                9.8f,
                p.fgMuted
            ).apply { setPadding(0, dp(3), 0, 0) }
        )
        root.addView(topHeader)

        // 2. Full-Width Non-Scrolling 5-Column Tab Bar (HOME | SET | TOOLS | RULES | CLIPS)
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor(p.bgSurface))
            setPadding(dp(6), dp(5), dp(6), dp(5))
            this.layoutParams = LinearLayout.LayoutParams(
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
                this.layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
                setOnClickListener { switchTab(tab) }
            }
            tabButtons[tab] = btn
            tabRow.addView(btn)
        }
        root.addView(tabRow)

        // 1px Divider under tabs
        root.addView(View(this).apply {
            this.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(Color.parseColor(p.divider))
        })

        // 3. Scrollable Tab Content Area
        val bodyScroll = ScrollView(this).apply {
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val bodyHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(24))
        }

        tabPanels[Tab.HOME] = buildHomeTab()
        tabPanels[Tab.BACKUP] = buildBackupAndSettingsTab()
        tabPanels[Tab.TOOLS] = buildToolsTab()
        tabPanels[Tab.RULES] = buildRulesTab()
        tabPanels[Tab.CLIPS] = buildClipsAndLogTab()

        Tab.values().forEach { t ->
            tabPanels[t]?.let { bodyHost.addView(it) }
        }

        bodyScroll.addView(bodyHost)
        root.addView(bodyScroll)

        setContentView(root)
        switchTab(activeTab)
    }

    private fun switchTab(target: Tab) {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        activeTab = target
        Tab.values().forEach { tab ->
            val active = (tab == target)
            tabPanels[tab]?.visibility = if (active) View.VISIBLE else View.GONE
            tabButtons[tab]?.apply {
                if (active) {
                    setTextColor(Color.parseColor(p.onAccent))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(p.accentPurple))
                        cornerRadius = dp(3).toFloat()
                    }
                } else {
                    setTextColor(Color.parseColor(p.fgPrimary))
                    background = null
                }
            }
        }
        refreshAllTabs()
    }

    private fun buildThemeAndBehaviorCard(): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        val followSys = ToolPrefs.isFollowSystemTheme(this)
        val sysDark = ToolPrefs.isSystemInDarkMode(this)
        val twoCol = ToolPrefs.isTwoColumnGrid(this)
        val snapLeft = ToolPrefs.isSnapLeftEdge(this)
        val sortMostUsed = ToolPrefs.isSortMostUsed(this)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor(p.bgSurface))
                setStroke(dp(1), Color.parseColor(p.border))
                cornerRadius = dp(3).toFloat()
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))

            // 1. Theme Palette Row
            addView(
                label(
                    "THEME PALETTE: ${p.name} (${if (p.isDark) "DARK" else "LIGHT"})" +
                        if (followSys) " · AUTO (${if (sysDark) "DARK" else "LIGHT"})" else " · LOCKED",
                    10.5f,
                    p.accentGreen,
                    bold = true
                ).apply { setPadding(0, 0, 0, dp(4)) }
            )

            val themeRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            themeRow.addView(
                equalButtonPill(
                    if (followSys) "[ AUTO: ON ]" else "[ AUTO: OFF ]",
                    if (followSys) p.accentGreen else p.fgMuted
                ) {
                    ToolPrefs.setFollowSystemTheme(this@MainActivity, !followSys)
                    SelAccessibilityService.instance?.refreshOpenPopupTheme()
                    rebuildUi()
                }
            )

            themeRow.addView(
                equalButtonPill(
                    if (p.isDark) "[ ALUCARD LIGHT ]" else "[ DRACULA DARK ]",
                    p.accentPurple
                ) {
                    val targetDark = !p.isDark
                    ToolPrefs.setFollowSystemTheme(this@MainActivity, false)
                    ToolPrefs.setDarkThemeManual(this@MainActivity, targetDark)
                    SelAccessibilityService.instance?.refreshOpenPopupTheme()
                    rebuildUi()
                }
            )
            addView(themeRow)

            // 2. Popup Layout & Edge Snap Row
            addView(
                label(
                    "POPUP LAYOUT & SCREEN EDGE:",
                    10.5f,
                    p.fgSecondary,
                    bold = true
                ).apply { setPadding(0, dp(8), 0, dp(4)) }
            )

            val behaviorRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            behaviorRow.addView(
                equalButtonPill(
                    if (twoCol) "[ GRID: 2-COL ]" else "[ LIST: 1-COL ]",
                    if (twoCol) p.accentCyan else p.fgPrimary
                ) {
                    ToolPrefs.setTwoColumnGrid(this@MainActivity, !twoCol)
                    SelAccessibilityService.instance?.refreshOpenPopupTheme()
                    rebuildUi()
                }
            )

            behaviorRow.addView(
                equalButtonPill(
                    if (snapLeft) "[ EDGE: LEFT ]" else "[ EDGE: RIGHT ]",
                    p.fgPrimary
                ) {
                    ToolPrefs.setSnapLeftEdge(this@MainActivity, !snapLeft)
                    SelAccessibilityService.instance?.refreshOpenPopupTheme()
                    rebuildUi()
                }
            )
            addView(behaviorRow)

            // 3. Tool Sort Order Row
            val sortRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }

            sortRow.addView(
                equalButtonPill(
                    if (sortMostUsed) "[ SORT: MOST USED FIRST ]" else "[ SORT: MANUAL ORDER ]",
                    if (sortMostUsed) p.accentGreen else p.fgMuted
                ) {
                    ToolPrefs.setSortMostUsed(this@MainActivity, !sortMostUsed)
                    rebuildUi()
                }
            )
            addView(sortRow)
        }
    }

    // --- TAB 1: HOME (Smart Status Banner, 1-Tap Quick-Test Chips + Sandbox, & Clean Lens Grab) ---
    private fun buildHomeTab(): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            // Dynamic Onboarding / Status Card (prominent when inactive, compact 1-liner when active)
            homeOnboardingCard = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                this.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10)
                }
            }
            addView(homeOnboardingCard)

            // Section 01: Interactive Test Sandbox with 1-Tap Quick-Test Sample Chips
            addView(label("01 // INTERACTIVE TEST SANDBOX", 11.5f, p.fgSecondary, bold = true))
            addView(
                label(
                    "Tap any sample chip below to spawn the popup in 1 tap, or highlight any word/number in the box:",
                    10.5f,
                    p.fgMuted
                ).apply { setPadding(0, dp(2), 0, dp(6)) }
            )

            val sandboxText =
                "Nepal Phone (Call/SMS/WA/W4B): 9841234567  or  +977 9801234567\n" +
                    "Word (Define [?=]): serendipity\n" +
                    "Torrent Hash (Magnet [0x]): dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c\n" +
                    "Math / Unit: 145 * 12   or   72 F   or   0xFF\n" +
                    "Unix Timestamp: 1730000000\n" +
                    "Base64: aGVsbG8gd29ybGQ=\n" +
                    "Dirty URL ([?x]): https://example.com/item?utm_source=ads&fbclid=123"

            // 1-Tap Sample Chips Row
            val sampleChipsRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(6))
            }

            val samples = listOf(
                "[PHONE]" to "9841234567",
                "[WORD]" to "serendipity",
                "[MATH]" to "145 * 12",
                "[URL]" to "https://example.com/item?utm_source=ads&fbclid=123",
                "[HASH]" to "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"
            )

            samples.forEach { (chipTitle, sampleVal) ->
                val chip = TextView(this@MainActivity).apply {
                    text = chipTitle
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    textSize = 10f
                    setTextColor(Color.parseColor(p.accentCyan))
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(p.bgSurface))
                        setStroke(dp(1), Color.parseColor(p.border))
                        cornerRadius = dp(2).toFloat()
                    }
                    this.layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        marginEnd = dp(3)
                    }
                    setOnClickListener {
                        triggerSandboxSample(sampleVal)
                    }
                }
                sampleChipsRow.addView(chip)
            }
            addView(sampleChipsRow)

            homeSandboxInput = EditText(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11.8f
                setTextColor(Color.parseColor(p.fgPrimary))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(p.bgSurface))
                    setStroke(dp(1), Color.parseColor(p.border))
                    cornerRadius = dp(2).toFloat()
                }
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setText(sandboxText)
            }
            addView(homeSandboxInput)

            // Section 02: Lens Screen Text Grabber with collapsible Automation Info
            addView(label("\n02 // [+] LENS SCREEN TEXT GRABBER", 11.5f, p.fgSecondary, bold = true))
            addView(
                label(
                    "Extract unselectable text from images, videos, or any app with interactive word chips and [+ADD] multi-select:",
                    10.5f,
                    p.fgMuted
                ).apply { setPadding(0, dp(2), 0, dp(6)) }
            )

            val automationBox = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(p.bgSurface))
                    setStroke(dp(1), Color.parseColor(p.divider))
                    cornerRadius = dp(2).toFloat()
                }
                setPadding(dp(10), dp(8), dp(10), dp(8))
                this.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(4)
                }

                addView(
                    label(
                        "3RD-PARTY GESTURE / TASKER / MACRODROID TRIGGERS:\n" +
                            "• Action:    io.github.selclip.ACTION_GRAB_SCREEN\n" +
                            "• Activity:  io.github.selclip.ScreenGrabTriggerActivity\n" +
                            "• Deep Link: sel://grab",
                        10f,
                        p.fgPrimary
                    )
                )
                addView(
                    buttonPill("[ [^C] COPY INTENT ACTION ]", p.accentCyan) {
                        copyPlain("io.github.selclip.ACTION_GRAB_SCREEN", "Copied intent action")
                    }
                )
            }

            val rowBtns = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            rowBtns.addView(
                equalButtonPill("[ [+] LAUNCH LENS GRAB ]", p.accentGreen) {
                    startActivity(Intent(this@MainActivity, ScreenGrabTriggerActivity::class.java))
                }
            )

            lateinit var toggleAutoBtn: TextView
            toggleAutoBtn = equalButtonPill("[ ? AUTOMATION ▾ ]", p.fgMuted) {
                val show = automationBox.visibility != View.VISIBLE
                automationBox.visibility = if (show) View.VISIBLE else View.GONE
                toggleAutoBtn.text = if (show) "[ ? AUTOMATION ▴ ]" else "[ ? AUTOMATION ▾ ]"
            }
            rowBtns.addView(toggleAutoBtn)

            addView(rowBtns)
            addView(automationBox)
        }
    }

    private fun triggerSandboxSample(sample: String) {
        val full = homeSandboxInput.text?.toString() ?: ""
        val idx = full.indexOf(sample)
        if (idx >= 0) {
            homeSandboxInput.requestFocus()
            homeSandboxInput.setSelection(idx, idx + sample.length)
        }
        val svc = SelAccessibilityService.instance
        if (svc != null) {
            svc.showFromExternalText(sample)
        } else {
            Toast.makeText(
                this,
                "Enable [sel] in Accessibility Settings first (tap [ ○ ENABLE ↗ ] at top right)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun refreshHomeOnboardingCard() {
        if (!::homeOnboardingCard.isInitialized) return
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        val active = SelAccessibilityService.instance != null
        homeOnboardingCard.removeAllViews()

        if (!active) {
            homeOnboardingCard.background = GradientDrawable().apply {
                setColor(Color.parseColor(p.bgSurface))
                setStroke(dp(1), Color.parseColor(p.dangerRed))
                cornerRadius = dp(3).toFloat()
            }
            homeOnboardingCard.setPadding(dp(12), dp(10), dp(12), dp(10))
            homeOnboardingCard.addView(
                label("[ ! ] STEP 1: ENABLE ACCESSIBILITY SERVICE", 11.5f, p.dangerRed, bold = true)
            )
            homeOnboardingCard.addView(
                label(
                    "Turn on [sel] Plain-Text Palette in Android Accessibility Settings so the popup appears whenever you select text.",
                    10.2f,
                    p.fgPrimary
                ).apply { setPadding(0, dp(3), 0, dp(6)) }
            )
            homeOnboardingCard.addView(
                buttonPill("[ ↗ ENABLE IN ACCESSIBILITY SETTINGS ]", p.accentGreen) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            )
        } else {
            homeOnboardingCard.background = GradientDrawable().apply {
                setColor(Color.parseColor(p.bgSurface))
                setStroke(dp(1), Color.parseColor(p.border))
                cornerRadius = dp(3).toFloat()
            }
            homeOnboardingCard.setPadding(dp(10), dp(7), dp(10), dp(7))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                label("● [sel_] is active. Select text anywhere or tap a chip below:", 10.2f, p.accentGreen).apply {
                    this.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            row.addView(
                label("[SET ↗]", 10f, p.accentPurple, bold = true).apply {
                    setPadding(dp(6), dp(2), dp(2), dp(2))
                    setOnClickListener { switchTab(Tab.BACKUP) }
                }
            )
            homeOnboardingCard.addView(row)
        }
    }

    // --- TAB 2 (SET): THEME & BEHAVIOR, 1-TAP APP PICKER BLACKLIST, & CLEAN BACKUP ---
    private fun buildBackupAndSettingsTab(): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(label("01 // THEME & POPUP BEHAVIOR", 11.5f, p.fgSecondary, bold = true))
            addView(
                label(
                    "Customize theme palette, 1-column vs 2-column popup layout, screen edge side, and tool sorting:",
                    10.5f,
                    p.fgMuted
                ).apply { setPadding(0, dp(2), 0, dp(6)) }
            )
            addView(buildThemeAndBehaviorCard())

            addView(label("\n02 // PER-APP BLACKLIST (⊘ MUTED APPS)", 11.5f, p.fgSecondary, bold = true))
            addView(
                label(
                    "Muted apps will not trigger automatic selection popups. Pick from installed apps or enter a package name:",
                    10.5f,
                    p.fgMuted
                ).apply { setPadding(0, dp(2), 0, dp(6)) }
            )

            // 1-Tap Installed App Picker Button + Collapsible Manual Package Input
            val manualPkgRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
                setPadding(0, dp(4), 0, dp(6))
            }

            blacklistInput = EditText(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11.5f
                setTextColor(Color.parseColor(p.fgPrimary))
                setBackgroundColor(Color.parseColor(p.bgSurface))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                hint = "com.example.app"
                setHintTextColor(Color.parseColor(p.fgMuted))
                isSingleLine = true
                this.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val addBlkBtn = buttonPill(" [ + MUTE ]", p.accentGreen) {
                val pkg = blacklistInput.text.toString().trim()
                if (pkg.isNotEmpty()) {
                    ToolPrefs.setAppBlacklisted(this@MainActivity, pkg, true)
                    blacklistInput.setText("")
                    refreshBlacklistView()
                }
            }
            manualPkgRow.addView(blacklistInput)
            manualPkgRow.addView(addBlkBtn)

            val pickerActionRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(6))
            }

            pickerActionRow.addView(
                equalButtonPill("[ + PICK INSTALLED APP ]", p.accentGreen) {
                    showInstalledAppsBlacklistDialog()
                }
            )

            lateinit var manualPkgToggle: TextView
            manualPkgToggle = equalButtonPill("[ + BY PACKAGE ID ▾ ]", p.fgMuted) {
                val show = manualPkgRow.visibility != View.VISIBLE
                manualPkgRow.visibility = if (show) View.VISIBLE else View.GONE
                manualPkgToggle.text = if (show) "[ + BY PACKAGE ID ▴ ]" else "[ + BY PACKAGE ID ▾ ]"
            }
            pickerActionRow.addView(manualPkgToggle)

            addView(pickerActionRow)
            addView(manualPkgRow)

            blacklistContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(p.bgSurface))
                    setStroke(dp(1), Color.parseColor(p.border))
                    cornerRadius = dp(2).toFloat()
                }
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            addView(blacklistContainer)

            addView(label("\n03 // BACKUP & RESTORE CONFIG (.JSON)", 11.5f, p.fgSecondary, bold = true))
            addView(
                label(
                    "Export or restore all settings, custom folders, top icon rows, tool order, muted apps, and rules:",
                    10.5f,
                    p.fgMuted
                ).apply { setPadding(0, dp(2), 0, dp(6)) }
            )

            val fileBtnRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            fileBtnRow.addView(equalButtonPill("[ ⇩ EXPORT .JSON ]", p.accentGreen) {
                launchExportJsonFile()
            })
            fileBtnRow.addView(equalButtonPill("[ ⇧ IMPORT .JSON ]", p.fgPrimary) {
                launchImportJsonFile()
            })
            addView(fileBtnRow)

            val clipBtnRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            clipBtnRow.addView(equalButtonPill("[ [^C] COPY JSON ]", p.accentCyan) {
                val json = ToolPrefs.exportBackupJson(this@MainActivity)
                backupPreviewBox.setText(json)
                copyPlain(json, "Copied config JSON to clipboard")
            })
            clipBtnRow.addView(equalButtonPill("[ [^V] RESTORE CLIP ]", p.fgPrimary) {
                val candidate = backupPreviewBox.text.toString().takeIf { it.isNotBlank() } ?: run {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
                }
                if (ToolPrefs.importBackupJson(this@MainActivity, candidate)) {
                    SelAccessibilityService.instance?.refreshOpenPopupTheme()
                    rebuildUi()
                    Toast.makeText(this@MainActivity, "✓ Settings & custom folders restored!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Copy a valid backup JSON first", Toast.LENGTH_SHORT).show()
                }
            })
            addView(clipBtnRow)

            val shareAndRawRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(6))
            }

            shareAndRawRow.addView(equalButtonPill("[ ↗ SHARE BUNDLE ]", p.accentPurple) {
                val json = ToolPrefs.exportBackupJson(this@MainActivity)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_TEXT, json)
                    putExtra(Intent.EXTRA_SUBJECT, "sel-android-backup.json")
                }
                startActivity(Intent.createChooser(share, "Export [sel] Config"))
            })

            backupPreviewBox = EditText(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 10f
                setTextColor(Color.parseColor(p.fgPrimary))
                setBackgroundColor(Color.parseColor(p.bgSurface))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                minLines = 5
                maxLines = 8
                visibility = View.GONE
                hint = "Paste backup JSON here…"
                setHintTextColor(Color.parseColor(p.fgMuted))
            }

            lateinit var rawJsonToggle: TextView
            rawJsonToggle = equalButtonPill("[ RAW JSON ▾ ]", p.fgMuted) {
                val show = backupPreviewBox.visibility != View.VISIBLE
                if (show) {
                    backupPreviewBox.setText(ToolPrefs.exportBackupJson(this@MainActivity))
                }
                backupPreviewBox.visibility = if (show) View.VISIBLE else View.GONE
                rawJsonToggle.text = if (show) "[ RAW JSON ▴ ]" else "[ RAW JSON ▾ ]"
            }
            shareAndRawRow.addView(rawJsonToggle)

            addView(shareAndRawRow)
            addView(backupPreviewBox)
        }
    }

    // --- TAB 3: TOOLS (Interactive 4-Slot Top Row Matrix, Checklist Folder Manager, & Clean Tool List) ---
    private fun buildToolsTab(): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(label("01 // TOP ICON-ONLY ROWS (TAP ANY SLOT TO EDIT)", 11.5f, p.fgSecondary, bold = true))
            addView(
                label(
                    "Top rows display bracketed icons only (max 4 per row). Tap any [ + ] or icon slot below to assign/remove:",
                    10.5f,
                    p.fgMuted
                ).apply { setPadding(0, dp(2), 0, dp(6)) }
            )

            iconRowsSummaryContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(p.bgSurface))
                    setStroke(dp(1), Color.parseColor(p.border))
                    cornerRadius = dp(2).toFloat()
                }
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            addView(iconRowsSummaryContainer)

            // Equal-width non-wrapping action bar for presets
            val presetRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(12))
            }
            presetRow.addView(equalButtonPill("[ ↺ 4x2 PRESET ]", p.accentGreen) {
                ToolPrefs.applyDefaultTwoIconRowsPreset(this@MainActivity)
                refreshReorderList()
                Toast.makeText(this@MainActivity, "Loaded 4 icons each in Row 1 & Row 2", Toast.LENGTH_SHORT).show()
            })
            presetRow.addView(equalButtonPill("[ × CLEAR R1 ]", p.fgPrimary) {
                ToolPrefs.clearIconRow(this@MainActivity, 1)
                refreshReorderList()
            })
            presetRow.addView(equalButtonPill("[ × CLEAR R2 ]", p.fgPrimary) {
                ToolPrefs.clearIconRow(this@MainActivity, 2)
                refreshReorderList()
            })
            addView(presetRow)

            // Section 2: Interactive Custom Folder Builder with 1-Tap [+ ITEMS] Checklist Dialog
            addView(label("02 // CUSTOM FOLDERS (GROUP TOOLS INTO SUBMENUS)", 11.5f, p.fgSecondary, bold = true))
            addView(
                label(
                    "Create a folder (e.g. READ, SOCIAL, AI), then tap [ + ITEMS ] on the folder to check which tools belong inside:",
                    10.5f,
                    p.fgMuted
                ).apply { setPadding(0, dp(2), 0, dp(6)) }
            )

            val createFolderRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(6))
            }

            customFolderInput = EditText(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11.5f
                setTextColor(Color.parseColor(p.fgPrimary))
                setBackgroundColor(Color.parseColor(p.bgSurface))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                hint = "Folder Name (e.g. READ, STUDY)"
                setHintTextColor(Color.parseColor(p.fgMuted))
                isSingleLine = true
                this.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val addFolderBtn = buttonPill(" [ + CREATE ]", p.accentGreen) {
                val raw = customFolderInput.text.toString().trim()
                if (ToolPrefs.createCustomFolder(this@MainActivity, raw)) {
                    val clean = ToolPrefs.sanitizeFolderName(raw)
                    customFolderInput.setText("")
                    refreshReorderList()
                    showFolderChecklistDialog(clean)
                } else {
                    Toast.makeText(this@MainActivity, "Enter a valid folder name (e.g. READ)", Toast.LENGTH_SHORT).show()
                }
            }

            createFolderRow.addView(customFolderInput)
            createFolderRow.addView(addFolderBtn)
            addView(createFolderRow)

            customFoldersContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(p.bgSurface))
                    setStroke(dp(1), Color.parseColor(p.border))
                    cornerRadius = dp(2).toFloat()
                }
                setPadding(dp(10), dp(8), dp(10), dp(8))
                this.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(12)
                }
            }
            addView(customFoldersContainer)

            // Section 3: Tool Reorder, Top Row Slot (R1/R2), & Visibility
            addView(label("03 // ALL TOOLS (DRAG ≡ TO REORDER)", 11.5f, p.fgSecondary, bold = true))
            addView(
                label(
                    "Tools below top rows display clean text labels only. Tap [LIST]/[★R1]/[★R2] to pin icon to top rows:",
                    10.5f,
                    p.fgMuted
                ).apply { setPadding(0, dp(2), 0, dp(6)) }
            )

            val controls = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(6))
            }

            sortModeButton = equalButtonPill("", p.accentGreen) {
                val next = !ToolPrefs.isSortMostUsed(this@MainActivity)
                ToolPrefs.setSortMostUsed(this@MainActivity, next)
                refreshReorderList()
            }

            val resetBtn = equalButtonPill("[ × RESET COUNTS ]", p.dangerRed) {
                ToolPrefs.resetUseCounts(this@MainActivity)
                refreshReorderList()
                Toast.makeText(this@MainActivity, "Usage counts reset", Toast.LENGTH_SHORT).show()
            }

            controls.addView(sortModeButton)
            controls.addView(resetBtn)
            addView(controls)

            reorderListContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(p.bgSurface))
                    setStroke(dp(1), Color.parseColor(p.border))
                    cornerRadius = dp(2).toFloat()
                }
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            addView(reorderListContainer)
        }
    }

    // --- TAB 4: RULES (1-Tap Popular Search Presets, Visual Destination Picker, & Visual Rule Manager) ---
    private fun buildRulesTab(): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        val rulesFile = ToolPrefs.ensureRulesFile(this)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(label("01 // 1-TAP POPULAR SEARCH PRESETS", 11.5f, p.fgSecondary, bold = true))
            addView(
                label(
                    "Tap any preset below to add it to WEB SEARCH > with automatic favicon:",
                    10.5f,
                    p.fgMuted
                ).apply { setPadding(0, dp(2), 0, dp(6)) }
            )

            // 2 rows of 3 equal-width preset chips
            for (rowIdx in 0..1) {
                val chipRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val slice = popularSearchPresets.subList(rowIdx * 3, (rowIdx + 1) * 3)
                slice.forEach { preset ->
                    chipRow.addView(
                        equalButtonPill("[${preset.chipLabel}]", p.accentCyan) {
                            val added = ToolPrefs.appendCustomRuleLine(
                                this@MainActivity,
                                preset.glyph,
                                preset.ruleLabel,
                                preset.targetUrl
                            )
                            if (added) {
                                FaviconManager.prefetchAllForTools(this@MainActivity)
                                refreshRulesTabViews()
                                refreshReorderList()
                                Toast.makeText(
                                    this@MainActivity,
                                    "✓ Added ${preset.ruleLabel.substringAfter("/")} to WEB SEARCH >",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    "${preset.ruleLabel.substringAfter("/")} is already added",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }
                addView(chipRow)
            }

            addView(label("\n02 // ADD CUSTOM SEARCH ENGINE OR URL TOOL", 11.5f, p.fgSecondary, bold = true))
            val engineCard = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(p.bgSurface))
                    setStroke(dp(1), Color.parseColor(p.border))
                    cornerRadius = dp(2).toFloat()
                }
                setPadding(dp(10), dp(10), dp(10), dp(10))
                this.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(4)
                }
            }

            val engineNameInput = EditText(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11.5f
                setTextColor(Color.parseColor(p.fgPrimary))
                setBackgroundColor(Color.parseColor(p.bgInput))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                hint = "Tool Name (e.g. KAGI, STARTPAGE)"
                setHintTextColor(Color.parseColor(p.fgMuted))
                isSingleLine = true
            }

            val engineUrlInput = EditText(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11.5f
                setTextColor(Color.parseColor(p.fgPrimary))
                setBackgroundColor(Color.parseColor(p.bgInput))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                hint = "https://kagi.com/search?q={text}"
                setHintTextColor(Color.parseColor(p.fgMuted))
                isSingleLine = true
                this.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(6)
                }
            }

            val destAndAddRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
            }

            lateinit var destBtn: TextView
            fun formatDestLabel(prefix: String): String {
                return when (prefix) {
                    "SEARCH/" -> "[ IN: WEB SEARCH > ]"
                    "" -> "[ IN: MAIN LIST ]"
                    "YT/" -> "[ IN: YOUTUBE > ]"
                    else -> "[ IN: ${prefix.removeSuffix("/")} > ]"
                }
            }

            destBtn = equalButtonPill(formatDestLabel(selectedRuleDestination), p.accentPurple) {
                val options = mutableListOf(
                    "SEARCH/" to "WEB SEARCH > (Submenu)",
                    "" to "MAIN POPUP LIST (Top-Level)",
                    "YT/" to "YOUTUBE > (Submenu)"
                )
                ToolPrefs.getCustomFolders(this@MainActivity).forEach { f ->
                    options.add("$f/" to "Custom Folder: $f >")
                }
                showPlainChoiceDialog(
                    title = "CHOOSE WHERE TO PLACE TOOL",
                    items = options.map { it.second }
                ) { chosenIdx ->
                    selectedRuleDestination = options[chosenIdx].first
                    destBtn.text = formatDestLabel(selectedRuleDestination)
                }
            }

            val submitEngineBtn = equalButtonPill("[ + ADD TOOL ]", p.accentGreen) {
                val rawName = engineNameInput.text.toString().trim().uppercase()
                var rawUrl = engineUrlInput.text.toString().trim()
                if (rawName.isNotEmpty() && rawUrl.isNotEmpty()) {
                    if (!rawUrl.startsWith("http://", true) &&
                        !rawUrl.startsWith("https://", true) &&
                        !rawUrl.startsWith("s/")
                    ) {
                        rawUrl = "https://$rawUrl"
                    }
                    if ((rawUrl.startsWith("http://", true) || rawUrl.startsWith("https://", true)) &&
                        !rawUrl.contains("{text}")
                    ) {
                        rawUrl = if (rawUrl.contains("?")) "$rawUrl&q={text}" else "$rawUrl?q={text}"
                    }
                    val fullLabel = if (rawName.contains("/")) rawName else "$selectedRuleDestination$rawName"
                    val g = ToolPrefs.toPlainAsciiCode("", fullLabel, rawUrl)
                    ToolPrefs.appendCustomRuleLine(this@MainActivity, g, fullLabel, rawUrl)
                    FaviconManager.prefetchAllForTools(this@MainActivity)
                    engineNameInput.setText("")
                    engineUrlInput.setText("")
                    refreshRulesTabViews()
                    refreshReorderList()
                    Toast.makeText(this@MainActivity, "✓ Added $fullLabel", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Enter both tool name and URL", Toast.LENGTH_SHORT).show()
                }
            }

            destAndAddRow.addView(destBtn)
            destAndAddRow.addView(submitEngineBtn)

            engineCard.addView(engineNameInput)
            engineCard.addView(engineUrlInput)
            engineCard.addView(destAndAddRow)
            addView(engineCard)

            // Section 3: Visual List of Active Custom Commands & Regex Pipes
            addView(label("\n03 // ACTIVE CUSTOM TOOLS & REGEX PIPES", 11.5f, p.fgSecondary, bold = true))
            addView(
                label(
                    "Tap [ × ] to remove any custom rule, or open the raw rules.txt editor below for regex pipes:",
                    10.5f,
                    p.fgMuted
                ).apply { setPadding(0, dp(2), 0, dp(6)) }
            )

            visualRulesContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(p.bgSurface))
                    setStroke(dp(1), Color.parseColor(p.border))
                    cornerRadius = dp(2).toFloat()
                }
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            addView(visualRulesContainer)

            // Collapsible Raw rules.txt Editor for Power Users
            val rawEditorContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(0, dp(6), 0, 0)
            }

            val tplRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(6))
            }
            tplRow.addView(equalButtonPill("[+ REGEX PIPE]", p.accentCyan) {
                rulesView.append("\ns/ | PIPE/TRIM SPACES | s/\\s+/ /g")
            })
            tplRow.addView(equalButtonPill("[+ MD RULE]", p.accentCyan) {
                rulesView.append("\nH1 | MD/HEADING 1 | # {text}")
            })
            rawEditorContainer.addView(tplRow)

            rulesView = EditText(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11.5f
                setTextColor(Color.parseColor(p.fgPrimary))
                setBackgroundColor(Color.parseColor(p.bgSurface))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                minLines = 6
                setText(rulesFile.readText())
            }
            rawEditorContainer.addView(rulesView)

            rawEditorContainer.addView(buttonPill("[ ✓ SAVE RAW rules.txt ]", p.accentGreen) {
                rulesFile.writeText(rulesView.text.toString())
                FaviconManager.prefetchAllForTools(this@MainActivity)
                refreshRulesTabViews()
                refreshReorderList()
                Toast.makeText(this@MainActivity, "✓ Saved rules.txt", Toast.LENGTH_SHORT).show()
            })

            lateinit var rawRulesToggleBtn: TextView
            rawRulesToggleBtn = buttonPill("[ ADVANCED: RAW rules.txt EDITOR ▾ ]", p.fgMuted) {
                val show = rawEditorContainer.visibility != View.VISIBLE
                if (show) {
                    rulesView.setText(ToolPrefs.ensureRulesFile(this@MainActivity).readText())
                }
                rawEditorContainer.visibility = if (show) View.VISIBLE else View.GONE
                rawRulesToggleBtn.text =
                    if (show) "[ ADVANCED: RAW rules.txt EDITOR ▴ ]" else "[ ADVANCED: RAW rules.txt EDITOR ▾ ]"
            }.apply {
                this.layoutParams = (this.layoutParams as LinearLayout.LayoutParams).apply {
                    topMargin = dp(8)
                }
            }

            addView(rawRulesToggleBtn)
            addView(rawEditorContainer)
        }
    }

    // --- TAB 5: CLIPS & SCRATCHPAD LOG (Clean Non-Wrapping Header & Real Hint on Scratchpad) ---
    private fun buildClipsAndLogTab(): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(label("01 // [≡] CLIPBOARD HISTORY (MAX 20)", 11.5f, p.fgSecondary, bold = true))
            addView(
                label(
                    "Tap any clip to copy it, tap [*] to pin it forever, or save your current clipboard below:",
                    10.5f,
                    p.fgMuted
                ).apply { setPadding(0, dp(2), 0, dp(6)) }
            )

            val clipBtnRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(6))
            }
            clipBtnRow.addView(equalButtonPill("[ + SAVE CURRENT CLIP ]", p.accentGreen) {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()?.trim().orEmpty()
                if (clip.isNotEmpty()) {
                    ToolPrefs.addClipToHistory(this@MainActivity, clip)
                    refreshClipHistory()
                    Toast.makeText(this@MainActivity, "✓ Saved clipboard to history", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Clipboard is currently empty", Toast.LENGTH_SHORT).show()
                }
            })
            clipBtnRow.addView(equalButtonPill("[ × CLEAR UNPINNED ]", p.dangerRed) {
                ToolPrefs.clearUnpinnedClips(this@MainActivity)
                refreshClipHistory()
            })
            addView(clipBtnRow)

            clipListContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(p.bgSurface))
                    setStroke(dp(1), Color.parseColor(p.border))
                    cornerRadius = dp(2).toFloat()
                }
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            addView(clipListContainer)

            addView(label("\n02 // [>>] SCRATCHPAD LOG (NOTES)", 11.5f, p.fgSecondary, bold = true))
            val logBtnRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(6))
            }
            logBtnRow.addView(equalButtonPill("[ ✓ SAVE NOTES ]", p.accentGreen) {
                File(filesDir, "scratchpad.txt").writeText(logView.text.toString())
                Toast.makeText(this@MainActivity, "✓ Scratchpad saved", Toast.LENGTH_SHORT).show()
            })
            logBtnRow.addView(equalButtonPill("[ [^C] COPY ALL ]", p.accentCyan) {
                copyPlain(logView.text.toString(), "Copied scratchpad log")
            })
            logBtnRow.addView(equalButtonPill("[ × CLEAR ]", p.dangerRed) {
                File(filesDir, "scratchpad.txt").writeText("")
                logView.setText("")
            })
            addView(logBtnRow)

            logView = EditText(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11.8f
                setTextColor(Color.parseColor(p.fgPrimary))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(p.bgSurface))
                    setStroke(dp(1), Color.parseColor(p.border))
                    cornerRadius = dp(2).toFloat()
                }
                setPadding(dp(12), dp(12), dp(12), dp(12))
                minLines = 7
                gravity = Gravity.TOP or Gravity.START
                hint = "Scratchpad is empty — type notes here or use APPEND LOG in the popup…"
                setHintTextColor(Color.parseColor(p.fgMuted))
            }
            addView(logView)
        }
    }

    // --- Plain-Text Terminal Dialogs for Seamless UX ---

    /**
     * Opens a plain-text terminal dialog to pick which tool occupies a slot in Top Row 1 or Row 2.
     */
    private fun showTopRowSlotPickerDialog(rowNumber: Int, clickedTool: ToolSpec?) {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        val allCandidates = ToolPrefs.loadAllTools(this).filter { it.id != "copy" }

        val dialogRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(p.bgPrimary))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        val titleStr = if (clickedTool != null) {
            "ROW $rowNumber // [${clickedTool.glyph}] ${clickedTool.label}"
        } else {
            "ASSIGN ICON TO TOP ROW $rowNumber (MAX 4)"
        }
        dialogRoot.addView(label(titleStr, 12f, p.accentPurple, bold = true).apply {
            setPadding(0, 0, 0, dp(8))
        })

        var alert: AlertDialog? = null

        if (clickedTool != null) {
            dialogRoot.addView(
                buttonPill("[ × REMOVE [${clickedTool.glyph}] FROM ROW $rowNumber ]", p.dangerRed) {
                    ToolPrefs.setToolIconRow(this, clickedTool.id, 0)
                    refreshReorderList()
                    alert?.dismiss()
                }
            )
            dialogRoot.addView(
                label("Or replace with another tool below:", 10.2f, p.fgMuted).apply {
                    setPadding(0, dp(6), 0, dp(6))
                }
            )
        }

        val scroll = ScrollView(this).apply {
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.48f).toInt()
            )
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        allCandidates.forEach { tool ->
            val currentRow = ToolPrefs.getToolIconRow(this, tool.id)
            val tok = "[${tool.glyph.trim().removePrefix("[").removeSuffix("]")}]"
            val statusBadge = when (currentRow) {
                1 -> " (in R1)"
                2 -> " (in R2)"
                else -> ""
            }
            val itemView = label(
                "${tok.padEnd(6, ' ')} ${tool.label}$statusBadge",
                11.2f,
                if (currentRow == rowNumber) p.accentGreen else p.fgPrimary
            ).apply {
                setPadding(dp(10), dp(9), dp(10), dp(9))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(p.bgSurface))
                    setStroke(dp(1), Color.parseColor(p.divider))
                }
                this.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(3)
                }
                setOnClickListener {
                    if (clickedTool != null && clickedTool.id != tool.id) {
                        val currentIds = ToolPrefs.getIconRowIds(this@MainActivity, rowNumber).toMutableList()
                        val otherRow = if (rowNumber == 1) 2 else 1
                        val otherIds = ToolPrefs.getIconRowIds(this@MainActivity, otherRow).toMutableList()
                        otherIds.remove(tool.id)
                        ToolPrefs.setIconRowIds(this@MainActivity, otherRow, otherIds)

                        val replaceIdx = currentIds.indexOf(clickedTool.id)
                        if (replaceIdx >= 0 && tool.id !in currentIds) {
                            currentIds[replaceIdx] = tool.id
                            ToolPrefs.setIconRowIds(this@MainActivity, rowNumber, currentIds)
                        } else {
                            ToolPrefs.setToolIconRow(this@MainActivity, tool.id, rowNumber)
                        }
                    } else {
                        val ok = ToolPrefs.setToolIconRow(this@MainActivity, tool.id, rowNumber)
                        if (!ok) {
                            Toast.makeText(
                                this@MainActivity,
                                "Row $rowNumber already has 4 icons — tap an existing slot to replace it",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    refreshReorderList()
                    alert?.dismiss()
                }
            }
            list.addView(itemView)
        }

        scroll.addView(list)
        dialogRoot.addView(scroll)
        dialogRoot.addView(buttonPill("[ CLOSE ]", p.fgMuted) { alert?.dismiss() })

        alert = AlertDialog.Builder(this)
            .setView(dialogRoot)
            .create()
        alert.show()
    }

    /**
     * Opens a multi-select checklist dialog so users can check/uncheck which tools belong inside [folderName >].
     */
    private fun showFolderChecklistDialog(folderName: String) {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        val tools = ToolPrefs.loadAssignableToolsForFolder(this)

        val dialogRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(p.bgPrimary))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        dialogRoot.addView(
            label("FOLDER // [$folderName >] — CHECK ITEMS TO KEEP INSIDE", 11.5f, p.accentOrange, bold = true).apply {
                setPadding(0, 0, 0, dp(4))
            }
        )
        dialogRoot.addView(
            label("Checked tools move from the root list into [$folderName >]:", 10f, p.fgMuted).apply {
                setPadding(0, 0, 0, dp(8))
            }
        )

        val scroll = ScrollView(this).apply {
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.50f).toInt()
            )
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun renderChecklistRows() {
            list.removeAllViews()
            tools.forEach { tool ->
                val assigned = ToolPrefs.getToolFolderAssignment(this, tool.id)
                val inThisFolder = assigned == folderName
                val otherSuffix = if (assigned != null && !inThisFolder) " (in $assigned)" else ""
                val boxGlyph = if (inThisFolder) "[x]" else "[ ]"
                val row = label(
                    "$boxGlyph  ${tool.label}$otherSuffix",
                    11.2f,
                    if (inThisFolder) p.accentGreen else p.fgPrimary
                ).apply {
                    setPadding(dp(10), dp(9), dp(10), dp(9))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(p.bgSurface))
                        setStroke(dp(1), Color.parseColor(p.divider))
                    }
                    this.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(3)
                    }
                    setOnClickListener {
                        ToolPrefs.setToolFolderAssignment(
                            this@MainActivity,
                            tool.id,
                            if (inThisFolder) null else folderName
                        )
                        renderChecklistRows()
                        refreshReorderList()
                    }
                }
                list.addView(row)
            }
        }

        renderChecklistRows()
        scroll.addView(list)
        dialogRoot.addView(scroll)

        var alert: AlertDialog? = null
        dialogRoot.addView(buttonPill("[ ✓ DONE ]", p.accentGreen) {
            refreshReorderList()
            alert?.dismiss()
        })

        alert = AlertDialog.Builder(this)
            .setView(dialogRoot)
            .create()
        alert.show()
    }

    /**
     * Opens a searchable plain-text checklist of installed launcher apps so users can mute/unmute apps without typing package IDs.
     */
    private fun showInstalledAppsBlacklistDialog() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()

        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolved = runCatching { pm.queryIntentActivities(mainIntent, 0) }.getOrNull().orEmpty()
        val appEntries = resolved
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == packageName) return@mapNotNull null
                val appLabel = runCatching { info.loadLabel(pm).toString().trim() }.getOrDefault(pkg)
                appLabel to pkg
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }

        val dialogRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(p.bgPrimary))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        dialogRoot.addView(
            label("MUTE APPS // TAP TO TOGGLE [⊘]", 11.5f, p.accentGreen, bold = true).apply {
                setPadding(0, 0, 0, dp(6))
            }
        )

        val searchBox = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setTextColor(Color.parseColor(p.fgPrimary))
            setBackgroundColor(Color.parseColor(p.bgSurface))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            hint = "Filter apps by name or package…"
            setHintTextColor(Color.parseColor(p.fgMuted))
            isSingleLine = true
        }
        dialogRoot.addView(searchBox)

        val scroll = ScrollView(this).apply {
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.48f).toInt()
            ).apply {
                topMargin = dp(6)
            }
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun renderApps(filterQuery: String) {
            list.removeAllViews()
            val q = filterQuery.trim().lowercase()
            val filtered = if (q.isEmpty()) {
                appEntries
            } else {
                appEntries.filter { it.first.lowercase().contains(q) || it.second.lowercase().contains(q) }
            }

            if (filtered.isEmpty()) {
                list.addView(label("No matching apps found.", 10.5f, p.fgMuted).apply {
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                })
                return
            }

            filtered.forEach { (appName, pkg) ->
                val muted = ToolPrefs.isAppBlacklisted(this, pkg)
                val mark = if (muted) "[⊘ MUTED]" else "[  ALLOW]"
                val row = label(
                    "$mark  $appName\n           $pkg",
                    10.5f,
                    if (muted) p.dangerRed else p.fgPrimary
                ).apply {
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(p.bgSurface))
                        setStroke(dp(1), Color.parseColor(p.divider))
                    }
                    this.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(3)
                    }
                    setOnClickListener {
                        ToolPrefs.setAppBlacklisted(this@MainActivity, pkg, !muted)
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
        dialogRoot.addView(buttonPill("[ ✓ DONE ]", p.accentGreen) {
            refreshBlacklistView()
            alert?.dismiss()
        })

        alert = AlertDialog.Builder(this)
            .setView(dialogRoot)
            .create()
        alert.show()
    }

    private fun showPlainChoiceDialog(title: String, items: List<String>, onPick: (Int) -> Unit) {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        val dialogRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(p.bgPrimary))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        dialogRoot.addView(label(title, 11.5f, p.accentPurple, bold = true).apply {
            setPadding(0, 0, 0, dp(8))
        })

        var alert: AlertDialog? = null
        items.forEachIndexed { idx, item ->
            val row = label("• $item", 11.2f, p.fgPrimary).apply {
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(p.bgSurface))
                    setStroke(dp(1), Color.parseColor(p.divider))
                }
                this.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(4)
                }
                setOnClickListener {
                    onPick(idx)
                    alert?.dismiss()
                }
            }
            dialogRoot.addView(row)
        }
        dialogRoot.addView(buttonPill("[ CANCEL ]", p.fgMuted) { alert?.dismiss() })

        alert = AlertDialog.Builder(this)
            .setView(dialogRoot)
            .create()
        alert.show()
    }

    private fun launchExportJsonFile() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "sel-android-backup.json")
        }
        runCatching { startActivityForResult(intent, REQ_EXPORT_JSON) }
    }

    private fun launchImportJsonFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, REQ_IMPORT_JSON) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
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
                    FaviconManager.prefetchAllForTools(this)
                    SelAccessibilityService.instance?.refreshOpenPopupTheme()
                    rebuildUi()
                    Toast.makeText(this, "✓ Restored settings & custom folders", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Invalid backup file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun refreshAllTabs() {
        val p = pal()
        val active = SelAccessibilityService.instance != null
        serviceStatusBadge.text = if (active) "[ ● ACTIVE ↗ ]" else "[ ○ ENABLE ↗ ]"
        serviceStatusBadge.setTextColor(Color.parseColor(if (active) p.accentGreen else p.dangerRed))

        refreshHomeOnboardingCard()

        val logFile = File(filesDir, "scratchpad.txt")
        if (::logView.isInitialized) {
            val currentTxt = if (logFile.exists()) logFile.readText() else ""
            logView.setText(if (currentTxt == "(Empty)") "" else currentTxt)
        }
        if (::reorderListContainer.isInitialized) refreshReorderList()
        if (::visualRulesContainer.isInitialized) refreshRulesTabViews()
        if (::clipListContainer.isInitialized) refreshClipHistory()
        if (::blacklistContainer.isInitialized) refreshBlacklistView()
        if (::backupPreviewBox.isInitialized && activeTab == Tab.BACKUP && backupPreviewBox.visibility == View.VISIBLE) {
            backupPreviewBox.setText(ToolPrefs.exportBackupJson(this))
        }
    }

    private fun refreshRulesTabViews() {
        if (!::visualRulesContainer.isInitialized) return
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        visualRulesContainer.removeAllViews()

        val entries = ToolPrefs.getCustomRuleEntries(this)
        if (::rulesView.isInitialized) {
            rulesView.setText(ToolPrefs.ensureRulesFile(this).readText())
        }

        if (entries.isEmpty()) {
            visualRulesContainer.addView(
                label("(No custom rules yet — tap a preset or add a URL above)", 10.5f, p.fgMuted)
            )
            return
        }

        entries.forEach { entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                this.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(2)
                    bottomMargin = dp(2)
                }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(p.bgPrimary))
                    setStroke(dp(1), Color.parseColor(p.divider))
                    cornerRadius = dp(2).toFloat()
                }
            }

            val leftCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                this.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            leftCol.addView(
                label("[${entry.glyph}] ${entry.rawLabel}", 11f, p.accentGreen, bold = true)
            )
            leftCol.addView(
                label(entry.target, 9.8f, p.fgMuted).apply {
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                }
            )

            val delBtn = label("[ × ]", 12f, p.dangerRed, bold = true).apply {
                setPadding(dp(10), dp(4), dp(4), dp(4))
                setOnClickListener {
                    ToolPrefs.deleteCustomRuleAtLine(this@MainActivity, entry.lineIndex)
                    refreshRulesTabViews()
                    refreshReorderList()
                    Toast.makeText(this@MainActivity, "Removed ${entry.rawLabel}", Toast.LENGTH_SHORT).show()
                }
            }

            row.addView(leftCol)
            row.addView(delBtn)
            visualRulesContainer.addView(row)
        }
    }

    private fun refreshClipHistory() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        clipListContainer.removeAllViews()
        val clips = ToolPrefs.getClipHistory(this)
        if (clips.isEmpty()) {
            clipListContainer.addView(
                label("(No recent clips yet — copy text anywhere or tap [ + SAVE CURRENT CLIP ])", 10.5f, p.fgMuted)
            )
            return
        }
        clips.forEach { entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                this.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(2)
                    bottomMargin = dp(2)
                }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(p.bgPrimary))
                    setStroke(dp(1), Color.parseColor(p.divider))
                    cornerRadius = dp(2).toFloat()
                }
            }

            val pinBtn = label(
                if (entry.pinned) "[★]" else "[·]",
                11.5f,
                if (entry.pinned) p.accentGreen else p.fgMuted,
                bold = true
            ).apply {
                setPadding(dp(4), dp(4), dp(10), dp(4))
                setOnClickListener {
                    ToolPrefs.togglePinClip(this@MainActivity, entry.text)
                    refreshClipHistory()
                }
            }

            val txtView = label(entry.text.replace("\n", " ↵ "), 11.5f, p.fgPrimary).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                this.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    copyPlain(entry.text, "[^C] Copied clip")
                }
            }

            val delBtn = label("[ × ]", 12f, p.dangerRed, bold = true).apply {
                setPadding(dp(10), dp(4), dp(4), dp(4))
                setOnClickListener {
                    ToolPrefs.deleteClip(this@MainActivity, entry.text)
                    refreshClipHistory()
                }
            }

            row.addView(pinBtn)
            row.addView(txtView)
            row.addView(delBtn)
            clipListContainer.addView(row)
        }
    }

    private fun refreshBlacklistView() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        blacklistContainer.removeAllViews()
        val apps = ToolPrefs.getBlacklistedApps(this)
        if (apps.isEmpty()) {
            blacklistContainer.addView(
                label("(No muted apps — tap [ + PICK INSTALLED APP ] above to mute any app)", 10.5f, p.fgMuted)
            )
            return
        }
        apps.forEach { pkg ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(10), dp(9))
                this.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(2)
                    bottomMargin = dp(2)
                }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(p.bgPrimary))
                    setStroke(dp(1), Color.parseColor(p.divider))
                    cornerRadius = dp(2).toFloat()
                }
            }
            val pkgLabel = label("⊘ $pkg", 11.2f, p.fgPrimary).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                this.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val removeBtn = label("[ UNMUTE ]", 10.5f, p.accentGreen, bold = true).apply {
                setPadding(dp(10), dp(4), dp(4), dp(4))
                setOnClickListener {
                    ToolPrefs.setAppBlacklisted(this@MainActivity, pkg, false)
                    refreshBlacklistView()
                }
            }
            row.addView(pkgLabel)
            row.addView(removeBtn)
            blacklistContainer.addView(row)
        }
    }

    private fun refreshCustomFoldersView() {
        if (!::customFoldersContainer.isInitialized) return
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        customFoldersContainer.removeAllViews()

        val folders = ToolPrefs.getCustomFolders(this)
        if (folders.isEmpty()) {
            customFoldersContainer.addView(
                label(
                    "(No custom folders yet — enter a name above and tap [ + CREATE ] to group tools)",
                    10.5f,
                    p.fgMuted
                )
            )
            return
        }

        folders.forEach { folderName ->
            val itemsInside = ToolPrefs.loadCustomFolderTools(this, folderName)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                this.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(2)
                    bottomMargin = dp(2)
                }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(p.bgPrimary))
                    setStroke(dp(1), Color.parseColor(p.divider))
                    cornerRadius = dp(2).toFloat()
                }
            }

            val summary = if (itemsInside.isEmpty()) {
                "[D/] $folderName > (0 items)"
            } else {
                "[D/] $folderName > (${itemsInside.size}: ${itemsInside.joinToString(", ") { it.label.replace(" >", "") }})"
            }

            val folderLabel = label(summary, 10.8f, p.accentOrange, bold = true).apply {
                this.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    showFolderChecklistDialog(folderName)
                }
            }

            val pickItemsBtn = label("[ + ITEMS ]", 10.5f, p.accentGreen, bold = true).apply {
                setPadding(dp(8), dp(4), dp(6), dp(4))
                setOnClickListener {
                    showFolderChecklistDialog(folderName)
                }
            }

            val delBtn = label("[ × ]", 11.5f, p.dangerRed, bold = true).apply {
                setPadding(dp(6), dp(4), dp(4), dp(4))
                setOnClickListener {
                    ToolPrefs.deleteCustomFolder(this@MainActivity, folderName)
                    if (::rulesView.isInitialized) {
                        rulesView.setText(ToolPrefs.ensureRulesFile(this@MainActivity).readText())
                    }
                    refreshReorderList()
                    Toast.makeText(this@MainActivity, "Removed folder $folderName", Toast.LENGTH_SHORT).show()
                }
            }

            row.addView(folderLabel)
            row.addView(pickItemsBtn)
            row.addView(delBtn)
            customFoldersContainer.addView(row)
        }
    }

    private fun refreshReorderList() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        val mostUsed = ToolPrefs.isSortMostUsed(this)
        sortModeButton.text = if (mostUsed) "[ ★ MOST USED: ON ]" else "[ ★ MOST USED: OFF ]"

        refreshCustomFoldersView()

        // Interactive 4-Slot Top Row Matrix
        if (::iconRowsSummaryContainer.isInitialized) {
            iconRowsSummaryContainer.removeAllViews()

            // Fixed Edit Row
            val editRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(4))
            }
            editRow.addView(label("EDIT  ", 10.5f, p.fgMuted, bold = true))
            listOf("[^C]", "[^V]", "[^X]", "[^A]").forEach { g ->
                editRow.addView(
                    label(g, 11f, p.accentGreen, bold = true).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(2), dp(5), dp(2), dp(5))
                        this.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                )
            }
            iconRowsSummaryContainer.addView(editRow)

            // Interactive Row 1 & Row 2 (4 clickable slots each)
            for (rNum in 1..2) {
                val rTools = ToolPrefs.getIconRowTools(this, rNum)
                val rowColor = if (rNum == 1) p.accentPurple else p.accentCyan
                val slotRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(3), 0, dp(3))
                }
                slotRow.addView(label("ROW $rNum ", 10.5f, rowColor, bold = true))

                for (slotIdx in 0 until ToolPrefs.MAX_TOOLS_PER_ICON_ROW) {
                    val tool = rTools.getOrNull(slotIdx)
                    val slotText = if (tool != null) {
                        "[${tool.glyph.trim().removePrefix("[").removeSuffix("]")}]"
                    } else {
                        "[ + ]"
                    }
                    val cell = label(
                        slotText,
                        11f,
                        if (tool != null) rowColor else p.fgMuted,
                        bold = true
                    ).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(2), dp(6), dp(2), dp(6))
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor(p.bgPrimary))
                            setStroke(dp(1), Color.parseColor(if (tool != null) rowColor else p.divider))
                            cornerRadius = dp(2).toFloat()
                        }
                        this.layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        ).apply {
                            marginStart = dp(2)
                            marginEnd = dp(2)
                        }
                        setOnClickListener {
                            showTopRowSlotPickerDialog(rNum, tool)
                        }
                    }
                    slotRow.addView(cell)
                }
                iconRowsSummaryContainer.addView(slotRow)
            }
        }

        reorderListContainer.removeAllViews()
        val tools = ToolPrefs.getAllSortedTools(this)
        val customFolders = ToolPrefs.getCustomFolders(this)
        tools.forEachIndexed { idx, tool ->
            val hidden = ToolPrefs.isToolHidden(this, tool.id)
            val assignedRow = ToolPrefs.getToolIconRow(this, tool.id)
            val assignedFolder = ToolPrefs.getToolFolderAssignment(this, tool.id)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(9), dp(8), dp(9))
                this.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(2)
                    bottomMargin = dp(2)
                }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(p.bgPrimary))
                    setStroke(dp(1), Color.parseColor(p.divider))
                    cornerRadius = dp(2).toFloat()
                }
                tag = tool.id
            }

            val dragHandle = TextView(this).apply {
                text = "≡"
                typeface = Typeface.MONOSPACE
                textSize = 16f
                setTextColor(Color.parseColor(p.accentGreen))
                setPadding(dp(4), dp(2), dp(8), dp(2))
            }

            val count = ToolPrefs.getUseCount(this, tool.id)
            val numStr = "%02d".format(idx + 1)
            val cleanGlyph = tool.glyph.trim().removePrefix("[").removeSuffix("]").trim()
            val bracketIcon = "[$cleanGlyph]"
            val nameView = TextView(this).apply {
                text = "$numStr | ${bracketIcon.padEnd(5, ' ')} ${tool.label}" + if (count > 0) " ($count)" else ""
                typeface = Typeface.MONOSPACE
                textSize = 10.8f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(Color.parseColor(if (hidden) p.fgMuted else p.fgPrimary))
                this.layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            row.addView(dragHandle)
            row.addView(nameView)

            // Only show folder assignment pill when custom folders exist (keeps tool rows ultra-clean!)
            if (customFolders.isNotEmpty() && tool.id != "copy" && !tool.id.startsWith("folder_custom_")) {
                val dirBtn = TextView(this).apply {
                    text = if (assignedFolder != null) "[$assignedFolder/]" else "[+DIR]"
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    textSize = 9.8f
                    setTextColor(Color.parseColor(if (assignedFolder != null) p.accentOrange else p.fgMuted))
                    setPadding(dp(5), dp(4), dp(5), dp(4))
                    setOnClickListener {
                        val nextFolder = ToolPrefs.cycleToolFolderAssignment(this@MainActivity, tool.id)
                        refreshReorderList()
                        val msg = if (nextFolder != null) {
                            "Moved ${tool.label} inside [$nextFolder >]"
                        } else {
                            "Returned ${tool.label} to root popup list"
                        }
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }
                row.addView(dirBtn)
            }

            if (tool.id != "copy") {
                val rowSlotBtn = TextView(this).apply {
                    text = when (assignedRow) {
                        1 -> "[★R1]"
                        2 -> "[★R2]"
                        else -> "[LIST]"
                    }
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    textSize = 10f
                    setTextColor(
                        Color.parseColor(
                            when (assignedRow) {
                                1 -> p.accentPurple
                                2 -> p.accentCyan
                                else -> p.fgMuted
                            }
                        )
                    )
                    setPadding(dp(5), dp(4), dp(5), dp(4))
                    setOnClickListener {
                        val next = ToolPrefs.cycleToolIconRow(this@MainActivity, tool.id)
                        if (next == 0 && assignedRow == 0) {
                            Toast.makeText(
                                this@MainActivity,
                                "Top rows R1 & R2 already have 4 items max — tap a slot above to swap!",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        refreshReorderList()
                    }
                }
                row.addView(rowSlotBtn)
            }

            val visToggle = TextView(this).apply {
                text = if (hidden) "[OFF]" else "[ON]"
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textSize = 10f
                setTextColor(Color.parseColor(if (hidden) p.fgMuted else p.accentGreen))
                setPadding(dp(5), dp(4), dp(4), dp(4))
                setOnClickListener {
                    ToolPrefs.setToolHidden(this@MainActivity, tool.id, !hidden)
                    refreshReorderList()
                }
            }

            row.addView(visToggle)
            ToolPrefs.attachDragHandle(dragHandle, row, reorderListContainer) {
                refreshReorderList()
            }
            reorderListContainer.addView(row)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAllTabs()
    }

    private fun copyPlain(text: String, toastMsg: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("sel", text))
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
    }

    private fun buttonPill(content: String, hexColor: String, onClick: () -> Unit): TextView {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        return label(content, 10.8f, hexColor, bold = true).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isSingleLine = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor(p.bgPrimary))
                setStroke(dp(1), Color.parseColor(p.border))
                cornerRadius = dp(2).toFloat()
            }
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
                bottomMargin = dp(3)
                marginEnd = dp(6)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun equalButtonPill(content: String, hexColor: String, onClick: () -> Unit): TextView {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        return label(content, 10.2f, hexColor, bold = true).apply {
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), dp(8), dp(4), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(p.bgPrimary))
                setStroke(dp(1), Color.parseColor(p.border))
                cornerRadius = dp(2).toFloat()
            }
            this.layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                topMargin = dp(3)
                bottomMargin = dp(3)
                marginStart = dp(2)
                marginEnd = dp(2)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun label(content: String, sizeSp: Float, hexColor: String, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = content
            textSize = sizeSp
            typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
            setTextColor(Color.parseColor(hexColor))
        }
    }
}
