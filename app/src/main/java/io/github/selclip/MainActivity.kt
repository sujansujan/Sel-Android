package io.github.selclip

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private lateinit var logView: EditText
    private lateinit var rulesView: EditText
    private lateinit var reorderListContainer: LinearLayout
    private lateinit var sortModeButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        val defaultRules = """
            # Format: GLYPH | LABEL | TARGET ({text} = selection)
            W | WIKIPEDIA | https://en.wikipedia.org/w/index.php?search={text}
            ★ | MD BOLD | **{text}**
        """.trimIndent()

        val rulesFile = File(filesDir, "rules.txt")
        if (!rulesFile.exists()) rulesFile.writeText(defaultRules)

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0A"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(32))
        }

        container.addView(label("[sel_] // PLAIN-TEXT PALETTE", 16f, "#F0F0F0"))
        container.addView(label("v1.2 · dynamic TUI overlay · QR · Define · YT · Incognito", 11f, "#666666"))

        container.addView(label("\n01 // PERMISSION", 12f, "#888888"))
        container.addView(label("[ ENABLE IN ACCESSIBILITY SETTINGS ↗ ]", 13.5f, "#00FF66").apply {
            setPadding(0, dp(8), 0, dp(14))
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        container.addView(label("02 // SANDBOX (SELECT WORD, MATH, OR URL BELOW)", 12f, "#888888"))
        val sandbox = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(Color.parseColor("#E0E0E0"))
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setText("Try selecting: ephemeral or 125 * 8 or https://example.com/page?utm_source=ads&fbclid=123")
        }
        container.addView(sandbox)

        container.addView(label("\n03 // TOOL ORDER & MOST-USED SORTING", 12f, "#888888"))
        val sortControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }

        sortModeButton = label("", 12f, "#00FF66").apply {
            setPadding(0, dp(4), dp(16), dp(4))
            setOnClickListener {
                val next = !ToolPrefs.isSortMostUsed(this@MainActivity)
                ToolPrefs.setSortMostUsed(this@MainActivity, next)
                refreshReorderList()
            }
        }

        val resetCountsBtn = label("[ RESET USAGE COUNTS ]", 12f, "#FF5555").apply {
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener {
                ToolPrefs.resetUseCounts(this@MainActivity)
                refreshReorderList()
                Toast.makeText(this@MainActivity, "Usage counts reset", Toast.LENGTH_SHORT).show()
            }
        }

        sortControls.addView(sortModeButton)
        sortControls.addView(resetCountsBtn)
        container.addView(sortControls)

        reorderListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        container.addView(reorderListContainer)

        container.addView(label("\n04 // CUSTOM ACTIONS (GLYPH | LABEL | TARGET)", 12f, "#888888"))
        rulesView = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.parseColor("#CCCCCC"))
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setText(rulesFile.readText())
        }
        container.addView(rulesView)

        container.addView(label("[ SAVE CUSTOM RULES ]", 12.5f, "#00FF66").apply {
            setPadding(0, dp(8), 0, dp(14))
            setOnClickListener {
                rulesFile.writeText(rulesView.text.toString())
                refreshReorderList()
                Toast.makeText(this@MainActivity, "Rules saved", Toast.LENGTH_SHORT).show()
            }
        })

        container.addView(label("05 // SCRATCHPAD LOG (⊕ APPEND LOG)", 12f, "#888888"))
        container.addView(label("[ CLEAR LOG ]", 12f, "#FF5555").apply {
            setPadding(0, dp(6), 0, dp(8))
            setOnClickListener {
                File(filesDir, "scratchpad.txt").writeText("")
                logView.setText("")
            }
        })

        logView = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.parseColor("#CCCCCC"))
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minLines = 8
        }
        container.addView(logView)

        root.addView(container)
        setContentView(root)
    }

    private fun refreshReorderList() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val mostUsed = ToolPrefs.isSortMostUsed(this)
        sortModeButton.text = if (mostUsed) "[ ★ MOST USED ON TOP: ON ]" else "[ ★ MOST USED ON TOP: OFF ]"

        reorderListContainer.removeAllViews()
        val tools = ToolPrefs.getSortedToolsForPopup(this)
        tools.forEachIndexed { idx, tool ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(6), dp(4), dp(6))
            }

            val count = ToolPrefs.getUseCount(this, tool.id)
            val nameView = TextView(this).apply {
                text = "${idx + 1}. ${tool.glyph}  ${tool.label}" + if (count > 0) "  (used $count×)" else ""
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextColor(Color.parseColor("#E0E0E0"))
                this.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val upBtn = TextView(this).apply {
                text = "[↑]"
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextColor(Color.parseColor(if (idx > 0) "#00FF66" else "#444444"))
                setPadding(dp(8), dp(2), dp(8), dp(2))
                setOnClickListener {
                    if (idx > 0) {
                        ToolPrefs.moveTool(this@MainActivity, tool.id, -1)
                        refreshReorderList()
                    }
                }
            }

            val downBtn = TextView(this).apply {
                text = "[↓]"
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextColor(Color.parseColor(if (idx < tools.size - 1) "#00FF66" else "#444444"))
                setPadding(dp(4), dp(2), dp(4), dp(2))
                setOnClickListener {
                    if (idx < tools.size - 1) {
                        ToolPrefs.moveTool(this@MainActivity, tool.id, 1)
                        refreshReorderList()
                    }
                }
            }

            row.addView(nameView)
            row.addView(upBtn)
            row.addView(downBtn)
            reorderListContainer.addView(row)
        }
    }

    override fun onResume() {
        super.onResume()
        val file = File(filesDir, "scratchpad.txt")
        logView.setText(if (file.exists()) file.readText() else "(Empty)")
        refreshReorderList()
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
