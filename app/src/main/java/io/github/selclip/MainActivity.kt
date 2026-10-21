package io.github.selclip

import android.app.Activity
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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
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

    private enum class Tab(val title: String) {
        HOME("01:HOME"),
        TOOLS("02:TOOLS"),
        RULES("03:RULES"),
        CLIPS("04:CLIPS"),
        BACKUP("05:SETTINGS")
    }

    private var activeTab: Tab = Tab.HOME
    private val tabButtons = mutableMapOf<Tab, TextView>()
    private val tabPanels = mutableMapOf<Tab, View>()

    private lateinit var serviceStatusBadge: TextView
    private lateinit var iconRowsSummaryContainer: LinearLayout
    private lateinit var customFoldersContainer: LinearLayout
    private lateinit var customFolderInput: EditText
    private lateinit var reorderListContainer: LinearLayout
    private lateinit var sortModeButton: TextView
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
            rebuildUi()
            SelAccessibilityService.instance?.refreshOpenPopupTheme()
        }
    }

    private fun pal(): ThemePalette = ToolPrefs.resolvePalette(this)

    private fun rebuildUi() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()

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

        // 1. Fixed Top Terminal Header
        val topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(p.bgHeader))
            setPadding(dp(18), dp(20), dp(18), dp(12))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleLabel = label("[sel_] // PLAIN-TEXT PALETTE", 15.5f, p.fgPrimary).apply {
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        serviceStatusBadge = label("○ INACTIVE", 11f, p.dangerRed)
        titleRow.addView(titleLabel)
        titleRow.addView(serviceStatusBadge)
        topHeader.addView(titleRow)
        topHeader.addView(
            label(
                "+-- v2.5 · ${p.name} theme · max 4 top-row icons · custom folders --+",
                10.5f,
                p.fgMuted
            )
        )
        root.addView(topHeader)

        // 2. Horizontal Terminal Tab Bar
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor(p.bgSurface))
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        Tab.values().forEach { tab ->
            val btn = TextView(this).apply {
                text = "[ ${tab.title} ]"
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                this.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener { switchTab(tab) }
            }
            tabButtons[tab] = btn
            tabRow.addView(btn)
        }
        tabScroll.addView(tabRow)
        root.addView(tabScroll)

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
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }

        tabPanels[Tab.HOME] = buildHomeTab()
        tabPanels[Tab.TOOLS] = buildToolsTab()
        tabPanels[Tab.RULES] = buildRulesTab()
        tabPanels[Tab.CLIPS] = buildClipsAndLogTab()
        tabPanels[Tab.BACKUP] = buildBackupAndSettingsTab()

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
                    setTextColor(Color.parseColor(p.fgMuted))
                    background = null
                }
            }
        }
        refreshAllTabs()
    }

    private fun buildThemeToggleCard(): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        val followSys = ToolPrefs.isFollowSystemTheme(this)
        val sysDark = ToolPrefs.isSystemInDarkMode(this)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor(p.bgSurface))
                setStroke(dp(1), Color.parseColor(p.border))
                cornerRadius = dp(3).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))

            addView(
                label(
                    "ACTIVE THEME: ${p.name} (${if (p.isDark) "DARK #282A36" else "LIGHT #FFFBEB"})" +
                        if (followSys) " · FOLLOWING SYSTEM (${if (sysDark) "DARK" else "LIGHT"})" else " · MANUAL OVERRIDE",
                    11f,
                    p.accentGreen
                ).apply { setPadding(0, 0, 0, dp(6)) }
            )

            val btnRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            btnRow.addView(
                buttonPill(
                    if (followSys) "[ FOLLOW SYSTEM: ON ]" else "[ FOLLOW SYSTEM: OFF ]",
                    if (followSys) p.accentGreen else p.fgMuted
                ) {
                    ToolPrefs.setFollowSystemTheme(this@MainActivity, !followSys)
                    SelAccessibilityService.instance?.refreshOpenPopupTheme()
                    rebuildUi()
                }
            )

            btnRow.addView(
                buttonPill(
                    if (p.isDark) "[ SWITCH TO ALUCARD LIGHT ]" else "[ SWITCH TO DRACULA DARK ]",
                    p.accentPurple
                ) {
                    val targetDark = !p.isDark
                    ToolPrefs.setFollowSystemTheme(this@MainActivity, false)
                    ToolPrefs.setDarkThemeManual(this@MainActivity, targetDark)
                    SelAccessibilityService.instance?.refreshOpenPopupTheme()
                    rebuildUi()
                }
            )

            addView(btnRow)
        }
    }

    // --- TAB 1: HOME (Permissions, Theme Toggle, Lens Grabber Trigger, & Interactive Context Sandbox) ---
    private fun buildHomeTab(): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(label("01 // ACCESSIBILITY PERMISSION", 12f, p.fgSecondary))
            addView(buttonPill("[ ENABLE / MANAGE IN ACCESSIBILITY SETTINGS ↗ ]", p.accentGreen) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })

            addView(label("\n02 // THEME: DRACULA (DARK) & ALUCARD (LIGHT)", 12f, p.fgSecondary))
            addView(
                label(
                    "Popup and app colors follow system dark/light mode by default (Dracula in Dark mode, Alucard in Light mode).",
                    10.5f,
                    p.fgMuted
                ).apply { setPadding(0, dp(2), 0, dp(6)) }
            )
            addView(buildThemeToggleCard())

            addView(label("\n03 // [+] LENS SCREEN TEXT GRABBER", 12f, p.fgSecondary))
            addView(
                label(
                    "Extract unselectable text anywhere on screen with word chips, [+ADD] multi-select, and '[' / ']' handles.",
                    11f,
                    p.fgMuted
                ).apply { setPadding(0, dp(2), 0, dp(8)) }
            )

            val intentInfo = """
                Action:    io.github.selclip.ACTION_GRAB_SCREEN
                Package:   io.github.selclip
                Activity:  io.github.selclip.ScreenGrabTriggerActivity
                Deep Link: sel://grab
            """.trimIndent()

            addView(TextView(this@MainActivity).apply {
                text = intentInfo
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextColor(Color.parseColor(p.fgPrimary))
                setBackgroundColor(Color.parseColor(p.bgSurface))
                setPadding(dp(12), dp(10), dp(12), dp(10))
            })

            val rowBtns = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, dp(14))
            }

            rowBtns.addView(buttonPill("[ [+] LAUNCH LENS GRAB ]", p.accentGreen) {
                startActivity(Intent(this@MainActivity, ScreenGrabTriggerActivity::class.java))
            })

            rowBtns.addView(buttonPill("[ [^C] COPY INTENT ]", p.fgPrimary) {
                copyPlain("io.github.selclip.ACTION_GRAB_SCREEN", "Copied intent action")
            })

            addView(rowBtns)

            addView(label("04 // CONTEXT-AWARE TEST SANDBOX", 12f, p.fgSecondary))
            addView(
                label(
                    "Highlight any item below to test top-row computer-shortcut icons, clean text-only list rows, and instant math/unit rows:",
                    11f,
                    p.fgMuted
                ).apply { setPadding(0, dp(2), 0, dp(8)) }
            )

            val sandbox = EditText(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12.5f
                setTextColor(Color.parseColor(p.fgPrimary))
                setBackgroundColor(Color.parseColor(p.bgSurface))
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setText(
                    "Nepal Phone (Call/SMS/WA/W4B): 9841234567  or  +977 9801234567\n" +
                        "Word (Define [?=]): serendipity\n" +
                        "Torrent Hash (Magnet [0x]): dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c\n" +
                        "Math / Unit: 145 * 12   or   72 F   or   0xFF\n" +
                        "Unix Timestamp: 1730000000\n" +
                        "Base64: aGVsbG8gd29ybGQ=\n" +
                        "Dirty URL ([?x]): https://example.com/item?utm_source=ads&fbclid=123"
                )
            }
            addView(sandbox)
        }
    }

    // --- TAB 2: TOOLS (Top Icon Rows max 4, Custom Folder Builder, & Tool Order / Folder Assignment) ---
    private fun buildToolsTab(): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(label("01 // TOP ICON-ONLY ROWS (MAX 4 ITEMS PER ROW)", 12f, p.fgSecondary))
            addView(
                label(
                    "Top rows show ONLY bracketed icons (max 4 items per row). Tools below the top rows show clean text labels only (no icons). Tap [LIST] -> [★R1] -> [★R2] on any tool below.",
                    10.5f,
                    p.fgMuted
                ).apply { setPadding(0, dp(2), 0, dp(8)) }
            )

            iconRowsSummaryContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(p.bgSurface))
                    setStroke(dp(1), Color.parseColor(p.border))
                    cornerRadius = dp(2).toFloat()
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            addView(iconRowsSummaryContainer)

            val presetRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(14))
            }
            presetRow.addView(buttonPill("[ ↺ 2-ROW PRESET (4x2) ]", p.accentGreen) {
                ToolPrefs.applyDefaultTwoIconRowsPreset(this@MainActivity)
                refreshReorderList()
                Toast.makeText(this@MainActivity, "Loaded 4 icons each in Row 1 & Row 2", Toast.LENGTH_SHORT).show()
            })
            presetRow.addView(buttonPill("[ × CLEAR R1 ]", p.fgPrimary) {
                ToolPrefs.clearIconRow(this@MainActivity, 1)
                refreshReorderList()
            })
            presetRow.addView(buttonPill("[ × CLEAR R2 ]", p.fgPrimary) {
                ToolPrefs.clearIconRow(this@MainActivity, 2)
                refreshReorderList()
            })
            addView(presetRow)

            // Section 2: Interactive Custom Folder Builder
            addView(label("02 // CREATE CUSTOM FOLDERS & KEEP ITEMS IN THEM", 12f, p.fgSecondary))
            addView(
                label(
                    "Create your own folders below (e.g. READ, SOCIAL, TOOLS), then tap [DIR:ROOT] on any tool in the list below to move it inside your folder.",
                    10.5f,
                    p.fgMuted
                ).apply { setPadding(0, dp(2), 0, dp(8)) }
            )

            val createFolderRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(8))
            }

            customFolderInput = EditText(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11.5f
                setTextColor(Color.parseColor(p.fgPrimary))
                setBackgroundColor(Color.parseColor(p.bgSurface))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                hint = "New Folder Name (e.g. READ, STUDY)"
                setHintTextColor(Color.parseColor(p.fgMuted))
                isSingleLine = true
                this.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val addFolderBtn = buttonPill("  [ + CREATE FOLDER ]", p.accentGreen) {
                val raw = customFolderInput.text.toString().trim()
                if (ToolPrefs.createCustomFolder(this@MainActivity, raw)) {
                    val clean = ToolPrefs.sanitizeFolderName(raw)
                    customFolderInput.setText("")
                    refreshReorderList()
                    Toast.makeText(
                        this@MainActivity,
                        "Created folder [$clean >]. Tap [DIR:ROOT] on any tool below to add items!",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this@MainActivity, "Enter a valid folder name", Toast.LENGTH_SHORT).show()
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
                    bottomMargin = dp(14)
                }
            }
            addView(customFoldersContainer)

            // Section 3: Tool Reorder, Top Row Slot (R1/R2), Custom Folder Assignment, & Visibility
            addView(label("03 // TOOL ORDER, TOP ROW SLOT, FOLDER & VISIBILITY", 12f, p.fgSecondary))
            addView(
                label(
                    "Drag ≡ to reorder. Tap [DIR:ROOT] to move a tool into your custom folder. Tap [LIST] / [★R1] / [★R2] to place its bracketed icon on Top Row 1 or 2 (max 4).",
                    10.5f,
                    p.fgMuted
                ).apply { setPadding(0, dp(2), 0, dp(8)) }
            )

            val controls = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(8))
            }

            sortModeButton = label("", 11.5f, p.accentGreen).apply {
                setPadding(0, dp(4), dp(16), dp(4))
                setOnClickListener {
                    val next = !ToolPrefs.isSortMostUsed(this@MainActivity)
                    ToolPrefs.setSortMostUsed(this@MainActivity, next)
                    refreshReorderList()
                }
            }

            val resetBtn = label("[ RESET USE COUNTS ]", 11.5f, p.dangerRed).apply {
                setPadding(0, dp(4), 0, dp(4))
                setOnClickListener {
                    ToolPrefs.resetUseCounts(this@MainActivity)
                    refreshReorderList()
                    Toast.makeText(this@MainActivity, "Usage counts reset", Toast.LENGTH_SHORT).show()
                }
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
                setPadding(dp(8), dp(8), dp(10), dp(8))
            }
            addView(reorderListContainer)
        }
    }

    // --- TAB 3: RULES (Add Web Engines with Auto-Favicon, Custom Commands, & Regex Pipes) ---
    private fun buildRulesTab(): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        val rulesFile = ToolPrefs.ensureRulesFile(this)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(label("01 // ADD WEB SEARCH ENGINE (OR ADD TO CUSTOM FOLDER)", 12f, p.fgSecondary))
            addView(
                label(
                    "Add any search engine or URL tool to WEB SEARCH >, top-level, or into any custom folder.",
                    10.5f,
                    p.fgMuted
                ).apply { setPadding(0, dp(2), 0, dp(8)) }
            )

            val engineCard = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(p.bgSurface))
                    setStroke(dp(1), Color.parseColor(p.border))
                    cornerRadius = dp(2).toFloat()
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }

            val engineNameInput = EditText(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11.5f
                setTextColor(Color.parseColor(p.fgPrimary))
                setBackgroundColor(Color.parseColor(p.bgInput))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                hint = "Name (e.g. KAGI, STARTPAGE, or FOLDER/NAME)"
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

            val addEngineBtnsRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }

            addEngineBtnsRow.addView(buttonPill("[ + ADD TO WEB SEARCH > ]", p.accentGreen) {
                val rawName = engineNameInput.text.toString().trim().uppercase().removePrefix("SEARCH/").removePrefix("WEB/")
                var rawUrl = engineUrlInput.text.toString().trim()
                if (rawName.isNotEmpty() && rawUrl.isNotEmpty()) {
                    if (!rawUrl.startsWith("http://", true) && !rawUrl.startsWith("https://", true)) {
                        rawUrl = "https://$rawUrl"
                    }
                    if (!rawUrl.contains("{text}")) {
                        rawUrl = if (rawUrl.contains("?")) "$rawUrl&q={text}" else "$rawUrl?q={text}"
                    }
                    val g = rawName.take(2)
                    val newRule = "\n$g | SEARCH/$rawName | $rawUrl"
                    rulesView.append(newRule)
                    rulesFile.writeText(rulesView.text.toString())
                    FaviconManager.prefetchAllForTools(this@MainActivity)
                    engineNameInput.setText("")
                    engineUrlInput.setText("")
                    refreshReorderList()
                    Toast.makeText(this@MainActivity, "✓ Added $rawName to WEB SEARCH >", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Enter both engine name and search URL", Toast.LENGTH_SHORT).show()
                }
            })

            addEngineBtnsRow.addView(buttonPill("[ + TOP-LEVEL / FOLDER ]", p.fgPrimary) {
                val rawName = engineNameInput.text.toString().trim().uppercase()
                var rawUrl = engineUrlInput.text.toString().trim()
                if (rawName.isNotEmpty() && rawUrl.isNotEmpty()) {
                    if (!rawUrl.startsWith("http://", true) && !rawUrl.startsWith("https://", true)) {
                        rawUrl = "https://$rawUrl"
                    }
                    if (!rawUrl.contains("{text}")) {
                        rawUrl = if (rawUrl.contains("?")) "$rawUrl&q={text}" else "$rawUrl?q={text}"
                    }
                    val g = rawName.substringAfter("/").trim().take(2).ifEmpty { "W" }
                    val newRule = "\n$g | $rawName | $rawUrl"
                    rulesView.append(newRule)
                    rulesFile.writeText(rulesView.text.toString())
                    FaviconManager.prefetchAllForTools(this@MainActivity)
                    engineNameInput.setText("")
                    engineUrlInput.setText("")
                    refreshReorderList()
                    Toast.makeText(this@MainActivity, "✓ Added $rawName", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Enter both engine name and search URL", Toast.LENGTH_SHORT).show()
                }
            })

            engineCard.addView(engineNameInput)
            engineCard.addView(engineUrlInput)
            engineCard.addView(addEngineBtnsRow)
            addView(engineCard)

            addView(label("\n02 // ALL CUSTOM COMMANDS & REGEX PIPES (rules.txt)", 12f, p.fgSecondary))
            addView(
                label(
                    "Syntax: ICON | LABEL | TARGET\n" +
                        "• SEARCH/LABEL → adds to WEB SEARCH > subgroup.\n" +
                        "• YT/LABEL → adds to YOUTUBE > subfolder.\n" +
                        "• FOLDER/LABEL → places inside your custom folder.\n" +
                        "• MD/LABEL → adds to MARKDOWN >.  PIPE/LABEL → adds to REGEX / PIPE >.",
                    10.5f,
                    p.fgMuted
                ).apply { setPadding(0, dp(4), 0, dp(8)) }
            )

            val tplRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(8))
            }

            tplRow.addView(buttonPill("[+ SEARCH]", p.accentGreen) {
                rulesView.append("\nSP | SEARCH/STARTPAGE | https://www.startpage.com/sp/search?query={text}")
            })

            tplRow.addView(buttonPill("[+ YT]", p.accentGreen) {
                rulesView.append("\n|> | YT/CHANNELS | https://www.youtube.com/results?search_query={text}&sp=EgIQAg%3D%3D")
            })

            tplRow.addView(buttonPill("[+ FOLDER]", p.accentGreen) {
                rulesView.append("\nAI | AI/PERPLEXITY | https://www.perplexity.ai/search?q={text}")
            })

            tplRow.addView(buttonPill("[+ REGEX]", p.accentGreen) {
                rulesView.append("\ns/ | PIPE/TRIM SPACES | s/\\s+/ /g")
            })

            addView(tplRow)

            rulesView = EditText(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextColor(Color.parseColor(p.fgPrimary))
                setBackgroundColor(Color.parseColor(p.bgSurface))
                setPadding(dp(12), dp(12), dp(12), dp(12))
                minLines = 8
                setText(rulesFile.readText())
            }
            addView(rulesView)

            addView(buttonPill("[ ✓ SAVE COMMANDS ]", p.accentGreen) {
                rulesFile.writeText(rulesView.text.toString())
                FaviconManager.prefetchAllForTools(this@MainActivity)
                refreshReorderList()
                Toast.makeText(this@MainActivity, "Custom commands saved", Toast.LENGTH_SHORT).show()
            })
        }
    }

    // --- TAB 4: CLIPS & SCRATCHPAD LOG ---
    private fun buildClipsAndLogTab(): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            val clipHeaderRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            clipHeaderRow.addView(label("01 // [≡] CLIPBOARD HISTORY (RING BUFFER)", 12f, p.fgSecondary).apply {
                this.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            })
            clipHeaderRow.addView(buttonPill("[ CLEAR UNPINNED ]", p.dangerRed) {
                ToolPrefs.clearUnpinnedClips(this@MainActivity)
                refreshClipHistory()
            })
            addView(clipHeaderRow)

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

            addView(label("\n02 // [>>] SCRATCHPAD LOG (APPEND LOG)", 12f, p.fgSecondary))
            val logBtnRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(8))
            }
            logBtnRow.addView(buttonPill("[ [^C] COPY ALL LOG ]", p.accentGreen) {
                copyPlain(logView.text.toString(), "Copied scratchpad log")
            })

            logBtnRow.addView(buttonPill("[ × CLEAR LOG ]", p.dangerRed) {
                File(filesDir, "scratchpad.txt").writeText("")
                logView.setText("")
            })
            addView(logBtnRow)

            logView = EditText(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextColor(Color.parseColor(p.fgPrimary))
                setBackgroundColor(Color.parseColor(p.bgSurface))
                setPadding(dp(12), dp(12), dp(12), dp(12))
                minLines = 8
            }
            addView(logView)
        }
    }

    // --- TAB 5: THEME SETTINGS, BACKUP / EXPORT / IMPORT & PER-APP BLACKLIST ---
    private fun buildBackupAndSettingsTab(): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(label("01 // THEME PALETTE (DRACULA DARK / ALUCARD LIGHT)", 12f, p.fgSecondary))
            addView(
                label(
                    "Toggle automatic system dark/light mode tracking or lock the popup and app to Dracula (Dark) or Alucard (Light):",
                    10.5f,
                    p.fgMuted
                ).apply { setPadding(0, dp(2), 0, dp(8)) }
            )
            addView(buildThemeToggleCard())

            addView(label("\n02 // EXPORT & IMPORT SETTINGS + CUSTOM FOLDERS", 12f, p.fgSecondary))
            addView(
                label(
                    "Exports theme mode, custom folders, tool folder assignments, top icon rows, tool order, blacklist, and rules.txt as a single portable JSON file.",
                    10.5f,
                    p.fgMuted
                ).apply { setPadding(0, dp(4), 0, dp(10)) }
            )

            val fileBtnRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(8))
            }
            fileBtnRow.addView(buttonPill("[ ⇩ EXPORT TO .JSON FILE ]", p.accentGreen) {
                launchExportJsonFile()
            })

            fileBtnRow.addView(buttonPill("[ ⇧ IMPORT .JSON FILE ]", p.fgPrimary) {
                launchImportJsonFile()
            })
            addView(fileBtnRow)

            val clipBtnRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(8))
            }
            clipBtnRow.addView(buttonPill("[ [^C] COPY CONFIG JSON ]", p.accentGreen) {
                val json = ToolPrefs.exportBackupJson(this@MainActivity)
                backupPreviewBox.setText(json)
                copyPlain(json, "Exported config JSON to clipboard")
            })

            clipBtnRow.addView(buttonPill("[ [^V] RESTORE FROM BOX / CLIP ]", p.fgPrimary) {
                val candidate = backupPreviewBox.text.toString().ifBlank {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
                }
                if (ToolPrefs.importBackupJson(this@MainActivity, candidate)) {
                    SelAccessibilityService.instance?.refreshOpenPopupTheme()
                    rebuildUi()
                    Toast.makeText(this@MainActivity, "✓ Settings & custom folders restored!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Invalid backup JSON", Toast.LENGTH_SHORT).show()
                }
            })
            addView(clipBtnRow)

            addView(buttonPill("[ ↗ SHARE CONFIG BUNDLE ]", p.accentPurple) {
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
                textSize = 10.5f
                setTextColor(Color.parseColor(p.fgPrimary))
                setBackgroundColor(Color.parseColor(p.bgSurface))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                minLines = 5
                maxLines = 8
                hint = "Config JSON preview (or paste backup JSON here to restore)…"
                setHintTextColor(Color.parseColor(p.fgMuted))
            }
            addView(backupPreviewBox)

            addView(label("\n03 // PER-APP BLACKLIST (⊘ MUTED APPS)", 12f, p.fgSecondary))
            addView(
                label(
                    "Muted apps will not trigger automatic selection popups (manual Lens Grab still works).",
                    10.5f,
                    p.fgMuted
                ).apply { setPadding(0, dp(2), 0, dp(8)) }
            )

            val addBlkRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(8))
            }

            blacklistInput = EditText(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11.5f
                setTextColor(Color.parseColor(p.fgPrimary))
                setBackgroundColor(Color.parseColor(p.bgSurface))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                hint = "com.example.app"
                setHintTextColor(Color.parseColor(p.fgMuted))
                this.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val addBlkBtn = buttonPill("  [ + MUTE ]", p.accentGreen) {
                val pkg = blacklistInput.text.toString().trim()
                if (pkg.isNotEmpty()) {
                    ToolPrefs.setAppBlacklisted(this@MainActivity, pkg, true)
                    blacklistInput.setText("")
                    refreshBlacklistView()
                }
            }

            addBlkRow.addView(blacklistInput)
            addBlkRow.addView(addBlkBtn)
            addView(addBlkRow)

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
        }
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
        serviceStatusBadge.text = if (active) "● SERVICE ACTIVE" else "○ SERVICE INACTIVE"
        serviceStatusBadge.setTextColor(Color.parseColor(if (active) p.accentGreen else p.dangerRed))

        val logFile = File(filesDir, "scratchpad.txt")
        if (::logView.isInitialized) {
            logView.setText(if (logFile.exists()) logFile.readText() else "(Empty)")
        }
        if (::reorderListContainer.isInitialized) refreshReorderList()
        if (::clipListContainer.isInitialized) refreshClipHistory()
        if (::blacklistContainer.isInitialized) refreshBlacklistView()
        if (::backupPreviewBox.isInitialized && activeTab == Tab.BACKUP) {
            backupPreviewBox.setText(ToolPrefs.exportBackupJson(this))
        }
    }

    private fun refreshClipHistory() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        clipListContainer.removeAllViews()
        val clips = ToolPrefs.getClipHistory(this)
        if (clips.isEmpty()) {
            clipListContainer.addView(label("(No recent clips yet)", 11.5f, p.fgMuted))
            return
        }
        clips.forEach { entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                this.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(3)
                    bottomMargin = dp(3)
                }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(p.bgPrimary))
                    setStroke(dp(1), Color.parseColor(p.divider))
                    cornerRadius = dp(2).toFloat()
                }
            }

            val pinBtn = label(if (entry.pinned) "[*]" else "[·]", 11.5f, if (entry.pinned) p.accentGreen else p.fgMuted).apply {
                setPadding(dp(4), dp(4), dp(12), dp(4))
                setOnClickListener {
                    ToolPrefs.togglePinClip(this@MainActivity, entry.text)
                    refreshClipHistory()
                }
            }

            val txtView = label("│ " + entry.text.replace("\n", " ↵ "), 12f, p.fgPrimary).apply {
                isSingleLine = true
                this.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    copyPlain(entry.text, "Copied clip")
                }
            }

            val delBtn = label("[ × ]", 13f, p.dangerRed).apply {
                setPadding(dp(10), dp(4), dp(6), dp(4))
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
            blacklistContainer.addView(label("(No muted apps)", 11.5f, p.fgMuted))
            return
        }
        apps.forEach { pkg ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                this.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(3)
                    bottomMargin = dp(3)
                }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(p.bgPrimary))
                    setStroke(dp(1), Color.parseColor(p.divider))
                    cornerRadius = dp(2).toFloat()
                }
            }
            val pkgLabel = label("⊘ │ $pkg", 12f, p.fgPrimary).apply {
                this.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val removeBtn = label("[ UNMUTE ]", 11.5f, p.accentGreen).apply {
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
                    "(No custom folders created yet — enter a name above and tap [ + CREATE FOLDER ])",
                    11f,
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
                "[D/] $folderName >  (0 items — tap [DIR:ROOT] on tools below)"
            } else {
                "[D/] $folderName >  (${itemsInside.size}: ${itemsInside.joinToString(", ") { it.label.replace(" >", "") }})"
            }

            val folderLabel = label(summary, 11f, p.accentOrange).apply {
                this.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val delBtn = label("[ × DELETE ]", 11f, p.dangerRed).apply {
                setPadding(dp(8), dp(4), dp(4), dp(4))
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
            row.addView(delBtn)
            customFoldersContainer.addView(row)
        }
    }

    private fun refreshReorderList() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val p = pal()
        val mostUsed = ToolPrefs.isSortMostUsed(this)
        sortModeButton.text = if (mostUsed) "[ ★ MOST USED TOP: ON ]" else "[ ★ MOST USED TOP: OFF ]"

        refreshCustomFoldersView()

        if (::iconRowsSummaryContainer.isInitialized) {
            iconRowsSummaryContainer.removeAllViews()
            val r1Tools = ToolPrefs.getIconRowTools(this, 1)
            val r2Tools = ToolPrefs.getIconRowTools(this, 2)
            val tok = { g: String ->
                val clean = g.trim().removePrefix("[").removeSuffix("]").trim()
                "[$clean]"
            }

            iconRowsSummaryContainer.addView(
                label("EDIT ROW | [^C]  [^V]  [^X]  [^A]   (Copy · Paste · Cut · Select All — 4/4)", 11f, p.accentGreen).apply {
                    setPadding(0, dp(2), 0, dp(4))
                }
            )

            val r1Text = if (r1Tools.isEmpty()) {
                "ROW 1    | (Empty — tap [LIST] on up to 4 tools below to show their icons here)"
            } else {
                "ROW 1    | " + r1Tools.joinToString("  ") { tok(it.glyph) } +
                    "   (${r1Tools.size}/4: " + r1Tools.joinToString(" · ") { it.label.replace(" >", "") } + ")"
            }
            iconRowsSummaryContainer.addView(
                label(r1Text, 11.5f, if (r1Tools.isEmpty()) p.fgMuted else p.accentPurple).apply {
                    setPadding(0, dp(3), 0, dp(4))
                }
            )

            val r2Text = if (r2Tools.isEmpty()) {
                "ROW 2    | (Empty — tap [★R1] on up to 4 tools below to move to Row 2)"
            } else {
                "ROW 2    | " + r2Tools.joinToString("  ") { tok(it.glyph) } +
                    "   (${r2Tools.size}/4: " + r2Tools.joinToString(" · ") { it.label.replace(" >", "") } + ")"
            }
            iconRowsSummaryContainer.addView(
                label(r2Text, 11.5f, if (r2Tools.isEmpty()) p.fgMuted else p.accentCyan).apply {
                    setPadding(0, dp(3), 0, dp(2))
                }
            )
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
                setPadding(dp(8), dp(10), dp(8), dp(10))
                this.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(3)
                    bottomMargin = dp(3)
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
                textSize = 17f
                setTextColor(Color.parseColor(p.accentGreen))
                setPadding(dp(4), dp(4), dp(10), dp(4))
            }

            val count = ToolPrefs.getUseCount(this, tool.id)
            val numStr = "%02d".format(idx + 1)
            val cleanGlyph = tool.glyph.trim().removePrefix("[").removeSuffix("]").trim()
            val bracketIcon = "[$cleanGlyph]"
            val nameView = TextView(this).apply {
                text = "$numStr | ${bracketIcon.padEnd(5, ' ')} ${tool.label}" + if (count > 0) " ($count)" else ""
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextColor(Color.parseColor(if (hidden) p.fgMuted else p.fgPrimary))
                this.layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            row.addView(dragHandle)
            row.addView(nameView)

            // Folder Assignment Button ([DIR:ROOT] -> [DIR:<FOLDER>])
            if (tool.id != "copy" && !tool.id.startsWith("folder_custom_")) {
                val dirBtn = TextView(this).apply {
                    text = if (assignedFolder != null) "[DIR:$assignedFolder]" else "[DIR:ROOT]"
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    textSize = 10f
                    setTextColor(Color.parseColor(if (assignedFolder != null) p.accentOrange else p.fgMuted))
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                    setOnClickListener {
                        if (customFolders.isEmpty()) {
                            Toast.makeText(
                                this@MainActivity,
                                "Create a folder in Section 02 above first!",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
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
                    textSize = 10.5f
                    setTextColor(
                        Color.parseColor(
                            when (assignedRow) {
                                1 -> p.accentPurple
                                2 -> p.accentCyan
                                else -> p.fgMuted
                            }
                        )
                    )
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                    setOnClickListener {
                        val next = ToolPrefs.cycleToolIconRow(this@MainActivity, tool.id)
                        if (next == 0 && assignedRow == 0) {
                            Toast.makeText(
                                this@MainActivity,
                                "Top rows R1 & R2 already have 4 items max!",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        refreshReorderList()
                    }
                }
                row.addView(rowSlotBtn)
            }

            val visToggle = TextView(this).apply {
                text = if (hidden) "[HIDE]" else "[SHOW]"
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textSize = 10.5f
                setTextColor(Color.parseColor(if (hidden) p.fgMuted else p.accentGreen))
                setPadding(dp(6), dp(4), dp(4), dp(4))
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
        return label(content, 11.5f, hexColor).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(p.bgPrimary))
                setStroke(dp(1), Color.parseColor(p.border))
                cornerRadius = dp(2).toFloat()
            }
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
                marginEnd = dp(8)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun label(content: String, sizeSp: Float, hexColor: String): TextView {
        return TextView(this).apply {
            text = content
            textSize = sizeSp
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor(hexColor))
        }
    }
}
