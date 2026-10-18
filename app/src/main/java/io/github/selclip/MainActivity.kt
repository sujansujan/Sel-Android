package io.github.selclip

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private lateinit var logView: EditText
    private lateinit var rulesView: EditText

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
        container.addView(label("v1.1 · vertical TUI overlay", 11.5f, "#666666"))

        container.addView(label("\n01 // PERMISSION", 12f, "#888888"))
        container.addView(label("[ ENABLE IN ACCESSIBILITY SETTINGS ↗ ]", 13.5f, "#00FF66").apply {
            setPadding(0, dp(8), 0, dp(14))
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        container.addView(label("02 // SANDBOX (SELECT TEXT BELOW)", 12f, "#888888"))
        val sandbox = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(Color.parseColor("#E0E0E0"))
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setText("Try selecting: 125 * 8 or https://example.com/page?utm_source=ads&fbclid=123 or plain text")
        }
        container.addView(sandbox)

        container.addView(label("\n03 // CUSTOM ACTIONS (GLYPH | LABEL | TARGET)", 12f, "#888888"))
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
                Toast.makeText(this@MainActivity, "Rules saved", Toast.LENGTH_SHORT).show()
            }
        })

        container.addView(label("04 // SCRATCHPAD LOG (⊕ APPEND LOG)", 12f, "#888888"))
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

    override fun onResume() {
        super.onResume()
        val file = File(filesDir, "scratchpad.txt")
        logView.setText(if (file.exists()) file.readText() else "(Empty)")
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
