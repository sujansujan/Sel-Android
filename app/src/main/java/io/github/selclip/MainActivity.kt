package io.github.selclip

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
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
        BACKUP("05:BACKUP")
    }

    private var activeTab: Tab = Tab.HOME
    private val tabButtons = mutableMapOf<Tab, TextView>()
    private val tabPanels = mutableMapOf<Tab, View>()

    private lateinit var serviceStatusBadge: TextView
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
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        ToolPrefs.ensureRulesFile(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0A0A"))
        }

        // 1. Fixed Top Terminal Header
        val topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
            setPadding(dp(18), dp(22), dp(18), dp(12))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleLabel = label("[sel_] // PLAIN-TEXT PALETTE", 15.5f, "#F0F0F0").apply {
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        serviceStatusBadge = label("○ INACTIVE", 11f, "#FF5555")
        titleRow.addView(titleLabel)
        titleRow.addView(serviceStatusBadge)
        topHeader.addView(titleRow)
        topHeader.addView(label("+── v1.8 · strict above/below · dynamic size · plaintextsports UI ──+", 10.5f, "#777777"))
        root.addView(topHeader)

        // 2. Horizontal Terminal Tab Bar
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#141414"))
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
            setBackgroundColor(Color.parseColor("#2A2A2A"))
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
        switchTab(Tab.HOME)
    }

    private fun switchTab(target: Tab) {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        activeTab = target
        Tab.values().forEach { tab ->
            val active = (tab == target)
            tabPanels[tab]?.visibility = if (active) View.VISIBLE else View.GONE
            tabButtons[tab]?.apply {
                if (active) {
                    setTextColor(Color.parseColor("#080808"))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#00FF66"))
                        cornerRadius = dp(3).toFloat()
                    }
                } else {
                    setTextColor(Color.parseColor("#999999"))
                    background = null
                }
            }
        }
        refreshAllTabs()
    }

    // --- TAB 1: HOME (Permissions, Lens Grabber Trigger, & Interactive Context Sandbox) ---
    private fun buildHomeTab(): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(label("01 // ACCESSIBILITY PERMISSION", 12f, "#888888"))
            addView(buttonPill("[ ENABLE / MANAGE IN ACCESSIBILITY SETTINGS ↗ ]", "#00FF66") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })

            addView(label("\n02 // GOOGLE-LENS PLAIN-TEXT SCREEN GRABBER", 12f, "#888888"))
            addView(
                label(
                    "Extract unselectable text anywhere on screen with word chips, [+ADD] multi-select, and '[' / ']' handles.",
                    11f,
                    "#777777"
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
                setTextColor(Color.parseColor("#CCCCCC"))
                setBackgroundColor(Color.parseColor("#141414"))
                setPadding(dp(12), dp(10), dp(12), dp(10))
            })

            val rowBtns = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, dp(14))
            }

            rowBtns.addView(buttonPill("[ ⊞ LAUNCH LENS GRAB ]", "#00FF66") {
                startActivity(Intent(this@MainActivity, ScreenGrabTriggerActivity::class.java))
            }.apply { setPadding(0, dp(4), dp(14), dp(4)) })

            rowBtns.addView(buttonPill("[ ⎘ COPY INTENT ]", "#CCCCCC") {
                copyPlain("io.github.selclip.ACTION_GRAB_SCREEN", "Copied intent action")
            }.apply { setPadding(0, dp(4), 0, dp(4)) })

            addView(rowBtns)

            addView(label("03 // CONTEXT-AWARE TEST SANDBOX", 12f, "#888888"))
            addView(
                label(
                    "Highlight any item below to test context-aware tools, instant math/unit/epoch/Base64 rows, and ↶ Undo:",
                    11f,
                    "#777777"
                ).apply { setPadding(0, dp(2), 0, dp(8)) }
            )

            val sandbox = EditText(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12.5f
                setTextColor(Color.parseColor("#E0E0E0"))
                setBackgroundColor(Color.parseColor("#141414"))
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setText(
                    "Word (Define): serendipity\n" +
                        "Torrent Hash (Magnet): dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c\n" +
                        "Math / Unit: 145 * 12   or   72 F   or   0xFF\n" +
                        "Unix Timestamp: 1730000000\n" +
                        "Base64: aGVsbG8gd29ybGQ=\n" +
                        "Dirty URL: https://example.com/item?utm_source=ads&fbclid=123"
                )
            }
            addView(sandbox)
        }
    }

    // --- TAB 2: TOOLS (Drag-and-Drop Reorder, Most-Used Toggle, & Show/Hide) ---
    private fun buildToolsTab(): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(label("01 // POPUP TOOL ORDER & VISIBILITY", 12f, "#888888"))
            addView(
                label(
                    "Drag the green ≡ handle to reorder tools. Tap [✓ SHOW] to hide/unhide tools.",
                    11f,
                    "#777777"
                ).apply { setPadding(0, dp(2), 0, dp(8)) }
            )

            val controls = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(8))
            }

            sortModeButton = label("", 11.5f, "#00FF66").apply {
                setPadding(0, dp(4), dp(16), dp(4))
                setOnClickListener {
                    val next = !ToolPrefs.isSortMostUsed(this@MainActivity)
                    ToolPrefs.setSortMostUsed(this@MainActivity, next)
                    refreshReorderList()
                }
            }

            val resetBtn = label("[ RESET USE COUNTS ]", 11.5f, "#FF5555").apply {
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
                setBackgroundColor(Color.parseColor("#141414"))
                setPadding(dp(8), dp(8), dp(10), dp(8))
            }
            addView(reorderListContainer)
        }
    }

    // --- TAB 3: RULES (Custom Commands, Custom Folders, & Regex Pipes) ---
    private fun buildRulesTab(): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val rulesFile = ToolPrefs.ensureRulesFile(this)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(label("01 // CUSTOM COMMANDS, FOLDERS & REGEX PIPES", 12f, "#888888"))
            addView(
                label(
                    "Syntax: GLYPH | LABEL | TARGET\n" +
                        "• Use FOLDER/LABEL (e.g. DEV/GITHUB) to group inside a collapsible folder.\n" +
                        "• Use MD/LABEL to add to M↓ MARKDOWN ▸.\n" +
                        "• Use PIPE/LABEL with s/pattern/replace/g for custom regex transforms.",
                    10.5f,
                    "#777777"
                ).apply { setPadding(0, dp(4), 0, dp(8)) }
            )

            // Quick template insertion bar
            val tplRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(8))
            }

            tplRow.addView(buttonPill("[+ URL]", "#00FF66") {
                rulesView.append("\n🔎 | SEARCH/REDDIT | https://www.reddit.com/search/?q={text}")
            }.apply { setPadding(0, dp(2), dp(10), dp(2)) })

            tplRow.addView(buttonPill("[+ FOLDER]", "#00FF66") {
                rulesView.append("\n⚡ | AI/PERPLEXITY | https://www.perplexity.ai/search?q={text}")
            }.apply { setPadding(0, dp(2), dp(10), dp(2)) })

            tplRow.addView(buttonPill("[+ REGEX]", "#00FF66") {
                rulesView.append("\nλ | PIPE/TRIM SPACES | s/\\s+/ /g")
            }.apply { setPadding(0, dp(2), dp(10), dp(2)) })

            tplRow.addView(buttonPill("[+ MD]", "#00FF66") {
                rulesView.append("\n🔑 | MD CALLOUT | > [!NOTE]\\n> {text}")
            }.apply { setPadding(0, dp(2), 0, dp(2)) })

            addView(tplRow)

            rulesView = EditText(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextColor(Color.parseColor("#CCCCCC"))
                setBackgroundColor(Color.parseColor("#141414"))
                setPadding(dp(12), dp(12), dp(12), dp(12))
                minLines = 10
                setText(rulesFile.readText())
            }
            addView(rulesView)

            addView(buttonPill("[ ✓ SAVE CUSTOM COMMANDS ]", "#00FF66") {
                rulesFile.writeText(rulesView.text.toString())
                refreshReorderList()
                Toast.makeText(this@MainActivity, "Custom commands saved", Toast.LENGTH_SHORT).show()
            }.apply { setPadding(0, dp(10), 0, dp(10)) })
        }
    }

    // --- TAB 4: CLIPS & SCRATCHPAD LOG ---
    private fun buildClipsAndLogTab(): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            val clipHeaderRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            clipHeaderRow.addView(label("01 // CLIPBOARD HISTORY (RING BUFFER)", 12f, "#888888").apply {
                this.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            })
            clipHeaderRow.addView(buttonPill("[ CLEAR UNPINNED ]", "#FF5555") {
                ToolPrefs.clearUnpinnedClips(this@MainActivity)
                refreshClipHistory()
            })
            addView(clipHeaderRow)

            clipListContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#141414"))
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            addView(clipListContainer)

            addView(label("\n02 // SCRATCHPAD LOG (⊕ APPEND LOG)", 12f, "#888888"))
            val logBtnRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(8))
            }
            logBtnRow.addView(buttonPill("[ ⎘ COPY ALL LOG ]", "#00FF66") {
                copyPlain(logView.text.toString(), "Copied scratchpad log")
            }.apply { setPadding(0, dp(2), dp(14), dp(2)) })

            logBtnRow.addView(buttonPill("[ × CLEAR LOG ]", "#FF5555") {
                File(filesDir, "scratchpad.txt").writeText("")
                logView.setText("")
            }.apply { setPadding(0, dp(2), 0, dp(2)) })
            addView(logBtnRow)

            logView = EditText(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextColor(Color.parseColor("#CCCCCC"))
                setBackgroundColor(Color.parseColor("#141414"))
                setPadding(dp(12), dp(12), dp(12), dp(12))
                minLines = 8
            }
            addView(logView)
        }
    }

    // --- TAB 5: BACKUP / EXPORT / IMPORT & PER-APP BLACKLIST ---
    private fun buildBackupAndSettingsTab(): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(label("01 // EXPORT & IMPORT SETTINGS + CUSTOM COMMANDS", 12f, "#888888"))
            addView(
                label(
                    "Exports tool order, hidden tools, usage counts, pinned clips, blacklist, and all custom commands in rules.txt as a single portable JSON file.",
                    10.5f,
                    "#777777"
                ).apply { setPadding(0, dp(4), 0, dp(10)) }
            )

            val fileBtnRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(8))
            }
            fileBtnRow.addView(buttonPill("[ ⇩ EXPORT TO .JSON FILE ]", "#00FF66") {
                launchExportJsonFile()
            }.apply { setPadding(0, dp(4), dp(14), dp(4)) })

            fileBtnRow.addView(buttonPill("[ ⇧ IMPORT .JSON FILE ]", "#F0F0F0") {
                launchImportJsonFile()
            }.apply { setPadding(0, dp(4), 0, dp(4)) })
            addView(fileBtnRow)

            val clipBtnRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(8))
            }
            clipBtnRow.addView(buttonPill("[ ⎘ COPY CONFIG JSON ]", "#00FF66") {
                val json = ToolPrefs.exportBackupJson(this@MainActivity)
                backupPreviewBox.setText(json)
                copyPlain(json, "Exported config JSON to clipboard")
            }.apply { setPadding(0, dp(4), dp(14), dp(4)) })

            clipBtnRow.addView(buttonPill("[ 📋 RESTORE FROM BOX / CLIP ]", "#CCCCCC") {
                val candidate = backupPreviewBox.text.toString().ifBlank {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
                }
                if (ToolPrefs.importBackupJson(this@MainActivity, candidate)) {
                    rulesView.setText(ToolPrefs.ensureRulesFile(this@MainActivity).readText())
                    refreshAllTabs()
                    Toast.makeText(this@MainActivity, "✓ Settings & custom commands restored!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Invalid backup JSON", Toast.LENGTH_SHORT).show()
                }
            }.apply { setPadding(0, dp(4), 0, dp(4)) })
            addView(clipBtnRow)

            addView(buttonPill("[ ↗ SHARE CONFIG BUNDLE ]", "#CCCCCC") {
                val json = ToolPrefs.exportBackupJson(this@MainActivity)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_TEXT, json)
                    putExtra(Intent.EXTRA_SUBJECT, "sel-android-backup.json")
                }
                startActivity(Intent.createChooser(share, "Export [sel] Config"))
            }.apply { setPadding(0, 0, 0, dp(8)) })

            backupPreviewBox = EditText(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 10.5f
                setTextColor(Color.parseColor("#BBBBBB"))
                setBackgroundColor(Color.parseColor("#141414"))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                minLines = 5
                maxLines = 8
                hint = "Config JSON preview (or paste backup JSON here to restore)…"
                setHintTextColor(Color.parseColor("#555555"))
            }
            addView(backupPreviewBox)

            addView(label("\n02 // PER-APP BLACKLIST (⊘ MUTED APPS)", 12f, "#888888"))
            addView(
                label(
                    "Muted apps will not trigger automatic selection popups (manual Lens Grab still works).",
                    10.5f,
                    "#777777"
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
                setTextColor(Color.parseColor("#E0E0E0"))
                setBackgroundColor(Color.parseColor("#141414"))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                hint = "com.example.app"
                setHintTextColor(Color.parseColor("#555555"))
                this.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val addBlkBtn = buttonPill("  [ + MUTE ]", "#00FF66") {
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
                setBackgroundColor(Color.parseColor("#141414"))
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
                    rulesView.setText(ToolPrefs.ensureRulesFile(this).readText())
                    refreshAllTabs()
                    Toast.makeText(this, "✓ Restored settings & custom commands", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Invalid backup file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun refreshAllTabs() {
        val active = SelAccessibilityService.instance != null
        serviceStatusBadge.text = if (active) "● SERVICE ACTIVE" else "○ SERVICE INACTIVE"
        serviceStatusBadge.setTextColor(Color.parseColor(if (active) "#00FF66" else "#FF5555"))

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
        clipListContainer.removeAllViews()
        val clips = ToolPrefs.getClipHistory(this)
        if (clips.isEmpty()) {
            clipListContainer.addView(label("(No recent clips yet)", 11.5f, "#666666"))
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
                    setColor(Color.parseColor("#101010"))
                    setStroke(dp(1), Color.parseColor("#282828"))
                    cornerRadius = dp(2).toFloat()
                }
            }

            val pinBtn = label(if (entry.pinned) "[📌]" else "[ · ]", 11.5f, if (entry.pinned) "#00FF66" else "#777777").apply {
                setPadding(dp(4), dp(4), dp(12), dp(4))
                setOnClickListener {
                    ToolPrefs.togglePinClip(this@MainActivity, entry.text)
                    refreshClipHistory()
                }
            }

            val txtView = label("│ " + entry.text.replace("\n", " ↵ "), 12f, "#E6E6E6").apply {
                isSingleLine = true
                this.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    copyPlain(entry.text, "Copied clip")
                }
            }

            val delBtn = label("[ × ]", 13f, "#FF6666").apply {
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
        blacklistContainer.removeAllViews()
        val apps = ToolPrefs.getBlacklistedApps(this)
        if (apps.isEmpty()) {
            blacklistContainer.addView(label("(No muted apps)", 11.5f, "#666666"))
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
                    setColor(Color.parseColor("#101010"))
                    setStroke(dp(1), Color.parseColor("#282828"))
                    cornerRadius = dp(2).toFloat()
                }
            }
            val pkgLabel = label("⊘ │ $pkg", 12f, "#E0E0E0").apply {
                this.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val removeBtn = label("[ UNMUTE ]", 11.5f, "#00FF66").apply {
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

    private fun refreshReorderList() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val mostUsed = ToolPrefs.isSortMostUsed(this)
        sortModeButton.text = if (mostUsed) "[ ★ MOST USED TOP: ON ]" else "[ ★ MOST USED TOP: OFF ]"

        reorderListContainer.removeAllViews()
        val tools = ToolPrefs.getAllSortedTools(this)
        tools.forEachIndexed { idx, tool ->
            val hidden = ToolPrefs.isToolHidden(this, tool.id)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(12), dp(12), dp(12))
                this.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(3)
                    bottomMargin = dp(3)
                }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#101010"))
                    setStroke(dp(1), Color.parseColor("#282828"))
                    cornerRadius = dp(2).toFloat()
                }
                tag = tool.id
            }

            val dragHandle = TextView(this).apply {
                text = "≡"
                typeface = Typeface.MONOSPACE
                textSize = 17f
                setTextColor(Color.parseColor("#00FF66"))
                setPadding(dp(8), dp(4), dp(14), dp(4))
            }

            val count = ToolPrefs.getUseCount(this, tool.id)
            val numStr = "%02d".format(idx + 1)
            val nameView = TextView(this).apply {
                text = "$numStr │ ${tool.glyph}  ${tool.label}" + if (count > 0) "  [$count×]" else ""
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextColor(Color.parseColor(if (hidden) "#555555" else "#F0F0F0"))
                this.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val visToggle = TextView(this).apply {
                text = if (hidden) "[ HIDDEN ]" else "[ ✓ SHOW ]"
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textSize = 11.5f
                setTextColor(Color.parseColor(if (hidden) "#666666" else "#00FF66"))
                setPadding(dp(10), dp(4), dp(6), dp(4))
                setOnClickListener {
                    ToolPrefs.setToolHidden(this@MainActivity, tool.id, !hidden)
                    refreshReorderList()
                }
            }

            row.addView(dragHandle)
            row.addView(nameView)
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
        return label(content, 12f, hexColor).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#141414"))
                setStroke(dp(1), Color.parseColor("#343434"))
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
