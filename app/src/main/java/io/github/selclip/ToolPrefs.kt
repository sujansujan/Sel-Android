package io.github.selclip

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.roundToInt

data class ToolSpec(
    val id: String,
    val glyph: String,
    val label: String,
    val customTarget: String? = null,
    val folderName: String? = null,
    val faviconUrl: String? = null
)

data class CustomRuleEntry(
    val lineIndex: Int,
    val glyph: String,
    val rawLabel: String,
    val target: String
)

data class ClipEntry(
    val text: String,
    val pinned: Boolean,
    val timestamp: Long
)

/**
 * Official Dracula Classic (Dark) and Alucard Classic (Light) palettes
 * based on https://draculatheme.com/spec
 */
data class ThemePalette(
    val name: String,
    val isDark: Boolean,
    val bgPrimary: String,
    val bgHeader: String,
    val bgSurface: String,
    val bgInput: String,
    val bgPressed: String,
    val border: String,
    val divider: String,
    val fgPrimary: String,
    val fgSecondary: String,
    val fgMuted: String,
    val accentGreen: String,
    val accentCyan: String,
    val accentPurple: String,
    val accentPink: String,
    val accentOrange: String,
    val accentYellow: String,
    val dangerRed: String,
    val onAccent: String
)

object ToolPrefs {
    private const val PREFS_NAME = "sel_tool_prefs"
    private const val KEY_ORDER = "manual_order"
    private const val KEY_SORT_MOST_USED = "sort_most_used"
    private const val KEY_EDGE_LEFT = "snap_edge_left"
    private const val KEY_GRID_COLUMNS = "grid_two_columns"
    private const val KEY_ICON_ROW_1 = "icon_row_1_ids"
    private const val KEY_ICON_ROW_2 = "icon_row_2_ids"
    private const val KEY_ICON_ROWS_INIT = "icon_rows_init_v25"
    private const val KEY_MIGRATED_V25 = "migrated_v25_no_dev"
    private const val KEY_BLACKLIST = "blacklisted_pkgs"
    private const val KEY_CLIP_HISTORY = "clip_history_json"
    private const val KEY_FOLLOW_SYSTEM_THEME = "follow_system_theme"
    private const val KEY_DARK_THEME_MANUAL = "dark_theme_manual"
    private const val KEY_CUSTOM_FOLDERS = "user_custom_folders"
    private const val PREFIX_TOOL_FOLDER = "tool_folder_"
    private const val PREFIX_COUNT = "use_count_"
    private const val PREFIX_HIDDEN = "hidden_"
    private const val MAX_CLIPS = 20
    const val MAX_TOOLS_PER_ICON_ROW = 4

    // Official Dracula Classic (Dark) Specification
    val DRACULA_PALETTE = ThemePalette(
        name = "DRACULA",
        isDark = true,
        bgPrimary = "#282A36",
        bgHeader = "#21222C",
        bgSurface = "#343746",
        bgInput = "#191A21",
        bgPressed = "#44475A",
        border = "#6272A4",
        divider = "#44475A",
        fgPrimary = "#F8F8F2",
        fgSecondary = "#BD93F9",
        fgMuted = "#6272A4",
        accentGreen = "#50FA7B",
        accentCyan = "#8BE9FD",
        accentPurple = "#BD93F9",
        accentPink = "#FF79C6",
        accentOrange = "#FFB86C",
        accentYellow = "#F1FA8C",
        dangerRed = "#FF5555",
        onAccent = "#282A36"
    )

    // Official Alucard Classic (Light) Specification
    val ALUCARD_PALETTE = ThemePalette(
        name = "ALUCARD",
        isDark = false,
        bgPrimary = "#FFFBEB",
        bgHeader = "#ECE9DF",
        bgSurface = "#EFEDDC",
        bgInput = "#DEDCCF",
        bgPressed = "#CFCFDE",
        border = "#6C664B",
        divider = "#CECCC0",
        fgPrimary = "#1F1F1F",
        fgSecondary = "#644AC9",
        fgMuted = "#6C664B",
        accentGreen = "#14710A",
        accentCyan = "#036A96",
        accentPurple = "#644AC9",
        accentPink = "#A3144D",
        accentOrange = "#A34D14",
        accentYellow = "#846E15",
        dangerRed = "#CB3A2A",
        onAccent = "#FFFBEB"
    )

    // Computer-shortcut inspired bracketed icons (shown as [glyph] ONLY when placed on top rows)
    val builtInTools = listOf(
        ToolSpec("copy", "^C", "COPY TEXT"),
        ToolSpec("screen_grab", "+", "LENS GRAB"),
        ToolSpec("web_search_folder", "/", "WEB SEARCH >"),
        ToolSpec("youtube_folder", "|>", "YOUTUBE >", faviconUrl = "https://www.youtube.com"),
        ToolSpec("clip_history", "≡", "CLIP HISTORY >"),
        ToolSpec("markdown_folder", "**", "MARKDOWN >"),
        ToolSpec("regex_pipe", "s/", "REGEX / PIPE >"),
        ToolSpec("define", "?=", "DEFINE WORD"),
        ToolSpec("translate", "<>", "TRANSLATE"),
        ToolSpec("qr", "::", "QR CODE"),
        ToolSpec("magnet", "0x", "HASH -> MAGNET"),
        ToolSpec("clean_url", "?x", "CLEAN URL"),
        ToolSpec("extract", "@#", "EXTRACT LINKS"),
        ToolSpec("clean_text", "~", "CLEAN TEXT"),
        ToolSpec("sort", "A↓", "SORT LINES"),
        ToolSpec("case", "Aa", "CYCLE CASE"),
        ToolSpec("speak", "~)", "SPEAK ALOUD"),
        ToolSpec("log", ">>", "APPEND LOG")
    )

    // Dedicated Web Search Subgroup with Top 4 Search Engines
    val webSearchSubTools = listOf(
        ToolSpec("web_google", "GGL", "GOOGLE", customTarget = "https://www.google.com/search?q={text}", faviconUrl = "https://www.google.com"),
        ToolSpec("web_ddg", "DDG", "DUCKDUCKGO", customTarget = "https://duckduckgo.com/?q={text}", faviconUrl = "https://duckduckgo.com"),
        ToolSpec("web_bing", "BNG", "BING", customTarget = "https://www.bing.com/search?q={text}", faviconUrl = "https://www.bing.com"),
        ToolSpec("web_brave", "BRV", "BRAVE SEARCH", customTarget = "https://search.brave.com/search?q={text}", faviconUrl = "https://search.brave.com")
    )

    // Dedicated YouTube Subfolder items
    val youtubeSubTools = listOf(
        ToolSpec("yt_all", "|>", "YOUTUBE (ALL)", customTarget = "https://www.youtube.com/results?search_query={text}", faviconUrl = "https://www.youtube.com"),
        ToolSpec("yt_hour", "1H", "YT LAST HOUR", customTarget = "https://www.youtube.com/results?search_query={text}&sp=EgIIAQ%3D%3D", faviconUrl = "https://www.youtube.com"),
        ToolSpec("yt_today", "24H", "YT TODAY", customTarget = "https://www.youtube.com/results?search_query={text}&sp=EgIIAg%3D%3D", faviconUrl = "https://www.youtube.com"),
        ToolSpec("yt_week", "7D", "YT THIS WEEK", customTarget = "https://www.youtube.com/results?search_query={text}&sp=EgIIAw%3D%3D", faviconUrl = "https://www.youtube.com"),
        ToolSpec("yt_month", "30D", "YT THIS MONTH", customTarget = "https://www.youtube.com/results?search_query={text}&sp=EgIIBA%3D%3D", faviconUrl = "https://www.youtube.com"),
        ToolSpec("yt_year", "1Y", "YT THIS YEAR", customTarget = "https://www.youtube.com/results?search_query={text}&sp=EgIIBQ%3D%3D", faviconUrl = "https://www.youtube.com")
    )

    // Dedicated Markdown Folder Submenu items
    val markdownSubTools = listOf(
        ToolSpec("md_bold", "**", "MD BOLD (**)"),
        ToolSpec("md_italic", "*", "MD ITALIC (*)"),
        ToolSpec("md_code", "``", "MD CODE (` / ```)"),
        ToolSpec("md_quote", ">", "MD QUOTE (>)"),
        ToolSpec("md_link", "[]", "MD LINK ([txt](url))"),
        ToolSpec("md_strike", "~~", "MD STRIKE (~~)"),
        ToolSpec("md_bullet", "-", "MD BULLET LIST (-)"),
        ToolSpec("md_task", "[ ]", "MD TASK LIST (- [ ])"),
        ToolSpec("md_h2", "##", "MD HEADING (##)"),
        ToolSpec("md_spoiler", "||", "MD SPOILER (||)"),
        ToolSpec("md_table", "|T|", "MD TABLE (2x2)")
    )

    // Dedicated Regex & Developer Pipe Submenu items
    val regexPipeSubTools = listOf(
        ToolSpec("pipe_json_pretty", "{+}", "JSON PRETTY"),
        ToolSpec("pipe_json_min", "{-}", "JSON MINIFY"),
        ToolSpec("pipe_url_enc", "%E", "URL ENCODE"),
        ToolSpec("pipe_url_dec", "%D", "URL DECODE"),
        ToolSpec("pipe_b64_enc", "B64", "BASE64 ENCODE"),
        ToolSpec("pipe_sha256", "SHA", "SHA-256 HASH"),
        ToolSpec("pipe_rot13", "R13", "ROT13 CIPHER"),
        ToolSpec("pipe_num_lines", "1.", "NUMBER LINES"),
        ToolSpec("pipe_strip_html", "</>", "STRIP HTML TAGS")
    )

    // --- Theme Preferences (Dracula Dark / Alucard Light + Follow System Toggle) ---
    fun isFollowSystemTheme(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FOLLOW_SYSTEM_THEME, true)
    }

    fun setFollowSystemTheme(context: Context, follow: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FOLLOW_SYSTEM_THEME, follow)
            .apply()
    }

    fun isDarkThemeManual(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DARK_THEME_MANUAL, true)
    }

    fun setDarkThemeManual(context: Context, dark: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK_THEME_MANUAL, dark)
            .apply()
    }

    fun isSystemInDarkMode(context: Context): Boolean {
        val nightModeFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    fun resolvePalette(context: Context): ThemePalette {
        val useDark = if (isFollowSystemTheme(context)) {
            isSystemInDarkMode(context)
        } else {
            isDarkThemeManual(context)
        }
        return if (useDark) DRACULA_PALETTE else ALUCARD_PALETTE
    }

    fun toPlainAsciiCode(rawGlyph: String, rawLabel: String, target: String? = null): String {
        val cleanLabel = rawLabel.substringAfter("/").trim().uppercase()
        val lowerTarget = target?.lowercase().orEmpty()
        if (cleanLabel.contains("WIKIPEDIA") || lowerTarget.contains("wikipedia.org")) {
            return "W"
        }
        val stripped = rawGlyph.trim().removePrefix("[").removeSuffix("]").trim()
        if (stripped.isNotEmpty()) {
            return stripped.take(4)
        }

        return when {
            lowerTarget.contains("youtube.") || lowerTarget.contains("youtu.be") -> "|>"
            lowerTarget.contains("wikipedia.") -> "W"
            lowerTarget.contains("google.") -> "GGL"
            lowerTarget.contains("duckduckgo.") -> "DDG"
            lowerTarget.contains("bing.") -> "BNG"
            lowerTarget.contains("brave.") -> "BRV"
            lowerTarget.contains("github.") -> "GH"
            lowerTarget.contains("stackoverflow.") -> "SO"
            lowerTarget.contains("reddit.") -> "RDT"
            lowerTarget.contains("perplexity.") -> "PPLX"
            lowerTarget.contains("kagi.") -> "KAGI"
            lowerTarget.contains("startpage.") -> "SP"
            cleanLabel.isNotEmpty() -> cleanLabel.filter { it.isLetterOrDigit() }.take(3).ifEmpty { "CMD" }
            else -> "CMD"
        }
    }

    fun defaultRulesText(): String = """
        # Format: ICON (1-4 chars) | LABEL | TARGET ({text} = selection)
        # Submenu tips:
        # - Prefix with 'SEARCH/' or 'WEB/' to place inside WEB SEARCH >
        # - Prefix with 'YT/' or 'YOUTUBE/' to place inside YOUTUBE >
        # - Prefix with 'MD ' to place inside MARKDOWN >
        # - Prefix with 'PIPE/' or use s/find/replace/g to place inside REGEX / PIPE >
        W | WIKIPEDIA | https://en.wikipedia.org/w/index.php?search={text}
        0-9 | PIPE/DIGITS ONLY | s/[^0-9]+//g
    """.trimIndent()

    fun ensureRulesFile(context: Context): File {
        val rulesFile = File(context.filesDir, "rules.txt")
        if (!rulesFile.exists()) {
            rulesFile.writeText(defaultRulesText())
        } else {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_MIGRATED_V25, false)) {
                val updatedLines = rulesFile.readLines().mapNotNull { line ->
                    val t = line.trim()
                    when {
                        t.contains("DEV/GITHUB", ignoreCase = true) -> null
                        t.contains("DEV/STACKOVERFLOW", ignoreCase = true) -> null
                        t.startsWith("WIKI | WIKIPEDIA", ignoreCase = true) ->
                            "W | WIKIPEDIA | https://en.wikipedia.org/w/index.php?search={text}"
                        else -> line
                    }
                }
                rulesFile.writeText(updatedLines.joinToString("\n"))
                prefs.edit().putBoolean(KEY_MIGRATED_V25, true).apply()
            }
        }
        return rulesFile
    }

    // --- User-Created Custom Folders & Tool Assignment in Settings ---
    private val reservedFolderPrefixes = setOf("SEARCH", "WEB", "YT", "YOUTUBE", "MD", "MARKDOWN", "PIPE", "REGEX")

    fun sanitizeFolderName(raw: String): String {
        return raw.uppercase()
            .replace("/", "")
            .replace("|", "")
            .replace(">", "")
            .trim()
            .take(14)
    }

    fun getCustomFolders(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_CUSTOM_FOLDERS, "")
            ?.split(",")
            ?.map { sanitizeFolderName(it) }
            ?.filter { it.isNotEmpty() && it !in reservedFolderPrefixes }
            ?: emptyList()

        val combined = LinkedHashSet<String>()
        combined.addAll(saved)

        val rulesFile = ensureRulesFile(context)
        if (rulesFile.exists()) {
            rulesFile.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split("|").map { it.trim() }
                    if (parts.size >= 3) {
                        val rawLabel = parts[1].uppercase()
                        if (rawLabel.contains("/")) {
                            val folder = sanitizeFolderName(rawLabel.substringBefore("/"))
                            if (folder.isNotEmpty() && folder !in reservedFolderPrefixes) {
                                combined.add(folder)
                            }
                        }
                    }
                }
            }
        }
        return combined.toList()
    }

    fun createCustomFolder(context: Context, rawName: String): Boolean {
        val clean = sanitizeFolderName(rawName)
        if (clean.isEmpty() || clean in reservedFolderPrefixes) return false
        val current = getCustomFolders(context).toMutableSet()
        current.add(clean)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_FOLDERS, current.joinToString(","))
            .apply()
        return true
    }

    fun deleteCustomFolder(context: Context, folderName: String) {
        val target = sanitizeFolderName(folderName)
        if (target.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val remaining = getCustomFolders(context).filterNot { it == target }
        val editor = prefs.edit()
        editor.putString(KEY_CUSTOM_FOLDERS, remaining.joinToString(","))

        // Unassign any tools assigned to this folder
        prefs.all.forEach { (k, v) ->
            if (k.startsWith(PREFIX_TOOL_FOLDER) && (v as? String)?.equals(target, ignoreCase = true) == true) {
                editor.remove(k)
            }
        }
        editor.apply()

        // Remove folder from icon rows if present
        setToolIconRow(context, "folder_custom_$target", 0)

        // Also unwrap any FOLDER/ITEM rules in rules.txt back to top-level ITEM
        val rulesFile = ensureRulesFile(context)
        if (rulesFile.exists()) {
            val updated = rulesFile.readLines().map { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split("|").map { it.trim() }
                    if (parts.size >= 3 && parts[1].uppercase().startsWith("$target/")) {
                        val subLabel = parts[1].substringAfter("/").trim()
                        "${parts[0]} | $subLabel | ${parts[2]}"
                    } else {
                        line
                    }
                } else {
                    line
                }
            }
            rulesFile.writeText(updated.joinToString("\n"))
        }
    }

    fun getToolFolderAssignment(context: Context, toolId: String): String? {
        if (toolId == "copy" || toolId.startsWith("folder_custom_")) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(PREFIX_TOOL_FOLDER + toolId, "")?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val validFolders = getCustomFolders(context)
        return raw.takeIf { it in validFolders }
    }

    fun setToolFolderAssignment(context: Context, toolId: String, folderName: String?) {
        if (toolId == "copy" || toolId.startsWith("folder_custom_")) return
        val clean = folderName?.let { sanitizeFolderName(it) }.orEmpty()
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (clean.isEmpty()) {
            editor.remove(PREFIX_TOOL_FOLDER + toolId)
        } else {
            editor.putString(PREFIX_TOOL_FOLDER + toolId, clean)
        }
        editor.apply()
    }

    fun cycleToolFolderAssignment(context: Context, toolId: String): String? {
        if (toolId == "copy" || toolId.startsWith("folder_custom_")) return null
        val folders = getCustomFolders(context)
        if (folders.isEmpty()) return null
        val current = getToolFolderAssignment(context, toolId)
        val next = when {
            current == null -> folders.first()
            else -> {
                val idx = folders.indexOf(current)
                if (idx in 0 until folders.lastIndex) folders[idx + 1] else null
            }
        }
        setToolFolderAssignment(context, toolId, next)
        return next
    }


    fun loadAssignableToolsForFolder(context: Context): List<ToolSpec> {
        return loadBaseToolsWithoutFolderEntries(context).filter { it.id != "copy" }
    }

    fun getCustomRuleEntries(context: Context): List<CustomRuleEntry> {
        val rulesFile = ensureRulesFile(context)
        if (!rulesFile.exists()) return emptyList()
        val out = mutableListOf<CustomRuleEntry>()
        rulesFile.readLines().forEachIndexed { idx, line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val parts = trimmed.split("|").map { it.trim() }
                if (parts.size >= 3) {
                    val g = toPlainAsciiCode(parts[0], parts[1], parts[2])
                    out.add(CustomRuleEntry(idx, g, parts[1].uppercase(), parts[2]))
                }
            }
        }
        return out
    }

    fun deleteCustomRuleAtLine(context: Context, targetLineIndex: Int) {
        val rulesFile = ensureRulesFile(context)
        if (!rulesFile.exists()) return
        val lines = rulesFile.readLines().toMutableList()
        if (targetLineIndex in lines.indices) {
            lines.removeAt(targetLineIndex)
            rulesFile.writeText(lines.joinToString("\n"))
        }
    }

    fun appendCustomRuleLine(context: Context, glyph: String, rawLabel: String, target: String): Boolean {
        val cleanGlyph = glyph.trim().take(4).ifEmpty { "CMD" }
        val cleanLabel = rawLabel.trim().uppercase()
        val cleanTarget = target.trim()
        if (cleanLabel.isEmpty() || cleanTarget.isEmpty()) return false
        val rulesFile = ensureRulesFile(context)
        val existing = if (rulesFile.exists()) rulesFile.readText() else ""
        val newLine = "$cleanGlyph | $cleanLabel | $cleanTarget"
        if (existing.lines().any { it.trim().equals(newLine, ignoreCase = true) }) {
            return false
        }
        val updated = if (existing.endsWith("\n") || existing.isEmpty()) {
            existing + newLine
        } else {
            "$existing\n$newLine"
        }
        rulesFile.writeText(updated)
        return true
    }

    private fun loadBaseToolsWithoutFolderEntries(context: Context): List<ToolSpec> {
        val list = mutableListOf<ToolSpec>()
        list.addAll(builtInTools)

        val rulesFile = ensureRulesFile(context)
        if (rulesFile.exists()) {
            rulesFile.readLines().forEachIndexed { idx, line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split("|").map { it.trim() }
                    if (parts.size >= 3) {
                        val glyph = toPlainAsciiCode(parts[0], parts[1], parts[2])
                        val rawLabel = parts[1].uppercase()
                        val target = parts[2]
                        val isHttp = target.startsWith("http://", true) || target.startsWith("https://", true)
                        when {
                            rawLabel.startsWith("SEARCH/") || rawLabel.startsWith("WEB/") -> {}
                            rawLabel.startsWith("YT/") || rawLabel.startsWith("YOUTUBE/") -> {}
                            rawLabel.startsWith("MD ") || rawLabel.startsWith("MARKDOWN/") || rawLabel.startsWith("MD/") -> {}
                            rawLabel.startsWith("PIPE/") || rawLabel.startsWith("REGEX/") || target.startsWith("s/") -> {}
                            rawLabel.contains("/") -> {}
                            else -> {
                                list.add(
                                    ToolSpec(
                                        id = "custom_${idx}_$rawLabel",
                                        glyph = glyph,
                                        label = rawLabel,
                                        customTarget = target,
                                        faviconUrl = if (isHttp) target else null
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        return list
    }

    fun loadAllTools(context: Context): List<ToolSpec> {
        val list = mutableListOf<ToolSpec>()
        list.addAll(loadBaseToolsWithoutFolderEntries(context))

        getCustomFolders(context).forEach { folder ->
            list.add(
                ToolSpec(
                    id = "folder_custom_$folder",
                    glyph = "D/",
                    label = "$folder >",
                    folderName = folder
                )
            )
        }
        return list
    }

    fun loadMarkdownTools(context: Context): List<ToolSpec> {
        val list = mutableListOf<ToolSpec>()
        list.addAll(markdownSubTools)

        val rulesFile = ensureRulesFile(context)
        if (rulesFile.exists()) {
            rulesFile.readLines().forEachIndexed { idx, line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split("|").map { it.trim() }
                    if (parts.size >= 3) {
                        val glyph = toPlainAsciiCode(parts[0], parts[1], parts[2])
                        val rawLabel = parts[1].uppercase()
                        val target = parts[2]
                        if (rawLabel.startsWith("MD ") || rawLabel.startsWith("MARKDOWN/") || rawLabel.startsWith("MD/")) {
                            val cleanLabel = rawLabel.removePrefix("MARKDOWN/").removePrefix("MD/").trim()
                            val alreadyBuiltIn = markdownSubTools.any {
                                it.label.startsWith(cleanLabel) && (target == "**{text}**" || target == "*{text}*")
                            }
                            if (!alreadyBuiltIn) {
                                list.add(
                                    ToolSpec(
                                        id = "custom_md_${idx}_$cleanLabel",
                                        glyph = glyph,
                                        label = cleanLabel,
                                        customTarget = target
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        return list
    }

    fun loadRegexPipeTools(context: Context): List<ToolSpec> {
        val list = mutableListOf<ToolSpec>()
        list.addAll(regexPipeSubTools)

        val rulesFile = ensureRulesFile(context)
        if (rulesFile.exists()) {
            rulesFile.readLines().forEachIndexed { idx, line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split("|").map { it.trim() }
                    if (parts.size >= 3) {
                        val glyph = toPlainAsciiCode(parts[0], parts[1], parts[2])
                        val rawLabel = parts[1].uppercase()
                        val target = parts[2]
                        if (rawLabel.startsWith("PIPE/") || rawLabel.startsWith("REGEX/") || target.startsWith("s/")) {
                            val cleanLabel = rawLabel.removePrefix("PIPE/").removePrefix("REGEX/").trim()
                            list.add(
                                ToolSpec(
                                    id = "custom_pipe_${idx}_$cleanLabel",
                                    glyph = glyph,
                                    label = cleanLabel,
                                    customTarget = target
                                )
                            )
                        }
                    }
                }
            }
        }
        return list
    }

    fun loadCustomFolderTools(context: Context, folderName: String): List<ToolSpec> {
        val list = mutableListOf<ToolSpec>()
        val targetFolder = sanitizeFolderName(folderName)
        if (targetFolder.isEmpty()) return list

        // 1. Any built-in or custom top-level tools assigned to this folder via Settings
        loadBaseToolsWithoutFolderEntries(context).forEach { tool ->
            if (!isToolHidden(context, tool.id) && getToolFolderAssignment(context, tool.id) == targetFolder) {
                list.add(tool)
            }
        }

        // 2. Any rules in rules.txt using FOLDER/SUBLABEL syntax
        val rulesFile = ensureRulesFile(context)
        if (rulesFile.exists()) {
            rulesFile.readLines().forEachIndexed { idx, line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split("|").map { it.trim() }
                    if (parts.size >= 3) {
                        val glyph = toPlainAsciiCode(parts[0], parts[1], parts[2])
                        val rawLabel = parts[1].uppercase()
                        val target = parts[2]
                        if (rawLabel.contains("/")) {
                            val folder = sanitizeFolderName(rawLabel.substringBefore("/"))
                            val subLabel = rawLabel.substringAfter("/").trim()
                            if (folder == targetFolder && subLabel.isNotEmpty()) {
                                val isHttp = target.startsWith("http://", true) || target.startsWith("https://", true)
                                list.add(
                                    ToolSpec(
                                        id = "folder_item_${idx}_$subLabel",
                                        glyph = glyph,
                                        label = subLabel,
                                        customTarget = target,
                                        folderName = folder,
                                        faviconUrl = if (isHttp) target else null
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        return list
    }

    fun loadWebSearchTools(context: Context): List<ToolSpec> {
        val list = mutableListOf<ToolSpec>()
        list.addAll(webSearchSubTools)

        val rulesFile = ensureRulesFile(context)
        if (rulesFile.exists()) {
            rulesFile.readLines().forEachIndexed { idx, line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split("|").map { it.trim() }
                    if (parts.size >= 3) {
                        val glyph = toPlainAsciiCode(parts[0], parts[1], parts[2])
                        val rawLabel = parts[1].uppercase()
                        val target = parts[2]
                        if (rawLabel.startsWith("SEARCH/") || rawLabel.startsWith("WEB/")) {
                            val cleanLabel = rawLabel.removePrefix("SEARCH/").removePrefix("WEB/").trim()
                            if (cleanLabel.isNotEmpty()) {
                                list.add(
                                    ToolSpec(
                                        id = "custom_search_${idx}_$cleanLabel",
                                        glyph = glyph,
                                        label = cleanLabel,
                                        customTarget = target,
                                        faviconUrl = target
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        return list
    }

    fun loadYoutubeTools(context: Context): List<ToolSpec> {
        val list = mutableListOf<ToolSpec>()
        list.addAll(youtubeSubTools)

        val rulesFile = ensureRulesFile(context)
        if (rulesFile.exists()) {
            rulesFile.readLines().forEachIndexed { idx, line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split("|").map { it.trim() }
                    if (parts.size >= 3) {
                        val glyph = toPlainAsciiCode(parts[0], parts[1], parts[2])
                        val rawLabel = parts[1].uppercase()
                        val target = parts[2]
                        if (rawLabel.startsWith("YT/") || rawLabel.startsWith("YOUTUBE/")) {
                            val cleanLabel = rawLabel.removePrefix("YOUTUBE/").removePrefix("YT/").trim()
                            if (cleanLabel.isNotEmpty()) {
                                list.add(
                                    ToolSpec(
                                        id = "custom_yt_${idx}_$cleanLabel",
                                        glyph = glyph,
                                        label = cleanLabel,
                                        customTarget = target,
                                        faviconUrl = target
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        return list
    }

    // --- Ring-Buffer Clipboard History (20 items max, supports pinning) ---
    fun getClipHistory(context: Context): List<ClipEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CLIP_HISTORY, "[]") ?: "[]"
        val out = mutableListOf<ClipEntry>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val txt = obj.optString("t", "")
                if (txt.isNotEmpty()) {
                    out.add(
                        ClipEntry(
                            text = txt,
                            pinned = obj.optBoolean("p", false),
                            timestamp = obj.optLong("ts", 0L)
                        )
                    )
                }
            }
        }
        return out.sortedWith(compareByDescending<ClipEntry> { it.pinned }.thenByDescending { it.timestamp })
    }

    private fun saveClipHistory(context: Context, entries: List<ClipEntry>) {
        val sorted = entries
            .sortedWith(compareByDescending<ClipEntry> { it.pinned }.thenByDescending { it.timestamp })
            .take(MAX_CLIPS)
        val arr = JSONArray()
        for (e in sorted) {
            arr.put(JSONObject().apply {
                put("t", e.text.take(2000))
                put("p", e.pinned)
                put("ts", e.timestamp)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CLIP_HISTORY, arr.toString())
            .apply()
    }

    fun addClipToHistory(context: Context, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val current = getClipHistory(context).toMutableList()
        val existingIdx = current.indexOfFirst { it.text == trimmed }
        val wasPinned = if (existingIdx >= 0) current.removeAt(existingIdx).pinned else false
        current.add(0, ClipEntry(trimmed, wasPinned, System.currentTimeMillis()))
        saveClipHistory(context, current)
    }

    fun togglePinClip(context: Context, text: String) {
        val current = getClipHistory(context).map {
            if (it.text == text) it.copy(pinned = !it.pinned) else it
        }
        saveClipHistory(context, current)
    }

    fun deleteClip(context: Context, text: String) {
        val current = getClipHistory(context).filterNot { it.text == text }
        saveClipHistory(context, current)
    }

    fun clearUnpinnedClips(context: Context) {
        val pinnedOnly = getClipHistory(context).filter { it.pinned }
        saveClipHistory(context, pinnedOnly)
    }

    // --- Per-App Blacklist ---
    fun getBlacklistedApps(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BLACKLIST, "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun isAppBlacklisted(context: Context, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return pkg.trim() in getBlacklistedApps(context)
    }

    fun setAppBlacklisted(context: Context, pkg: String, blacklisted: Boolean) {
        val clean = pkg.trim()
        if (clean.isEmpty()) return
        val current = getBlacklistedApps(context).toMutableSet()
        if (blacklisted) current.add(clean) else current.remove(clean)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BLACKLIST, current.sorted().joinToString(","))
            .apply()
    }

    // --- Edge-Snap Preference ---
    fun isSnapLeftEdge(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_EDGE_LEFT, false)
    }

    fun setSnapLeftEdge(context: Context, leftEdge: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_EDGE_LEFT, leftEdge)
            .apply()
    }

    fun isTwoColumnGrid(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_GRID_COLUMNS, false)
    }

    fun setTwoColumnGrid(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GRID_COLUMNS, enabled)
            .apply()
    }

    // --- Up to 2 Custom Icon-Only Tool Rows (Row 1 & Row 2, max 4 items per row) ---
    private fun ensureDefaultIconRows(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ICON_ROWS_INIT, false)) {
            prefs.edit()
                .putString(KEY_ICON_ROW_1, "web_search_folder,youtube_folder,translate,define")
                .putString(KEY_ICON_ROW_2, "")
                .putBoolean(KEY_ICON_ROWS_INIT, true)
                .apply()
        }
    }

    fun getIconRowIds(context: Context, rowNumber: Int): List<String> {
        ensureDefaultIconRows(context)
        val key = if (rowNumber == 1) KEY_ICON_ROW_1 else KEY_ICON_ROW_2
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(key, "") ?: ""
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "copy" && it != "incognito" }
            .distinct()
            .take(MAX_TOOLS_PER_ICON_ROW)
    }

    fun setIconRowIds(context: Context, rowNumber: Int, ids: List<String>) {
        ensureDefaultIconRows(context)
        val clean = ids.map { it.trim() }
            .filter { it.isNotEmpty() && it != "copy" && it != "incognito" }
            .distinct()
            .take(MAX_TOOLS_PER_ICON_ROW)
        val key = if (rowNumber == 1) KEY_ICON_ROW_1 else KEY_ICON_ROW_2
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key, clean.joinToString(","))
            .apply()
    }

    fun getIconRowTools(context: Context, rowNumber: Int): List<ToolSpec> {
        val ids = getIconRowIds(context, rowNumber)
        if (ids.isEmpty()) return emptyList()
        val allMap = loadAllTools(context).associateBy { it.id }
        return ids.mapNotNull { id ->
            val spec = allMap[id]
            if (spec != null && !isToolHidden(context, id)) spec else null
        }
    }

    fun getToolIconRow(context: Context, toolId: String): Int {
        if (toolId == "copy") return 0
        if (toolId in getIconRowIds(context, 1)) return 1
        if (toolId in getIconRowIds(context, 2)) return 2
        return 0
    }

    fun setToolIconRow(context: Context, toolId: String, targetRow: Int): Boolean {
        if (toolId == "copy") return false
        val r1 = getIconRowIds(context, 1).toMutableList()
        val r2 = getIconRowIds(context, 2).toMutableList()
        r1.remove(toolId)
        r2.remove(toolId)

        when (targetRow) {
            1 -> {
                if (r1.size >= MAX_TOOLS_PER_ICON_ROW) return false
                r1.add(toolId)
            }
            2 -> {
                if (r2.size >= MAX_TOOLS_PER_ICON_ROW) return false
                r2.add(toolId)
            }
        }
        setIconRowIds(context, 1, r1)
        setIconRowIds(context, 2, r2)
        return true
    }

    fun cycleToolIconRow(context: Context, toolId: String): Int {
        if (toolId == "copy") return 0
        val current = getToolIconRow(context, toolId)
        val r1Count = getIconRowIds(context, 1).count { it != toolId }
        val r2Count = getIconRowIds(context, 2).count { it != toolId }

        val candidates = when (current) {
            0 -> listOf(1, 2, 0)
            1 -> listOf(2, 0)
            else -> listOf(0, 1)
        }
        for (next in candidates) {
            if (next == 1 && r1Count >= MAX_TOOLS_PER_ICON_ROW) continue
            if (next == 2 && r2Count >= MAX_TOOLS_PER_ICON_ROW) continue
            setToolIconRow(context, toolId, next)
            return next
        }
        setToolIconRow(context, toolId, 0)
        return 0
    }

    fun clearIconRow(context: Context, rowNumber: Int) {
        setIconRowIds(context, rowNumber, emptyList())
    }

    fun applyDefaultTwoIconRowsPreset(context: Context) {
        ensureDefaultIconRows(context)
        setIconRowIds(context, 1, listOf("web_search_folder", "youtube_folder", "translate", "define"))
        setIconRowIds(context, 2, listOf("clip_history", "qr", "screen_grab", "markdown_folder"))
    }

    fun isSortMostUsed(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SORT_MOST_USED, true)
    }

    fun setSortMostUsed(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SORT_MOST_USED, enabled).apply()
    }

    fun isToolHidden(context: Context, id: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREFIX_HIDDEN + id, false)
    }

    fun setToolHidden(context: Context, id: String, hidden: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREFIX_HIDDEN + id, hidden).apply()
    }

    fun getUseCount(context: Context, id: String): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(PREFIX_COUNT + id, 0)
    }

    fun recordUse(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getInt(PREFIX_COUNT + id, 0)
        prefs.edit().putInt(PREFIX_COUNT + id, current + 1).apply()
    }

    fun resetUseCounts(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(PREFIX_COUNT) }.forEach { editor.remove(it) }
        editor.apply()
    }

    fun getManualOrderedTools(context: Context): List<ToolSpec> {
        val all = loadAllTools(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedOrder = prefs.getString(KEY_ORDER, "")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val orderMap = savedOrder.withIndex().associate { it.value to it.index }
        return all.sortedWith(
            compareBy { orderMap[it.id] ?: (1000 + all.indexOf(it)) }
        )
    }

    fun getAllSortedTools(context: Context): List<ToolSpec> {
        val manual = getManualOrderedTools(context)
        if (!isSortMostUsed(context)) return manual

        val manualIndex = manual.withIndex().associate { it.value.id to it.index }
        return manual.sortedWith(
            compareByDescending<ToolSpec> { getUseCount(context, it.id) }
                .thenBy { manualIndex[it.id] ?: 0 }
        )
    }

    fun getSortedToolsForPopup(context: Context): List<ToolSpec> {
        return getAllSortedTools(context).filter { tool ->
            if (isToolHidden(context, tool.id)) return@filter false
            // Hide tools from root popup if user placed them inside a custom folder
            if (getToolFolderAssignment(context, tool.id) != null) return@filter false
            // Only show a custom folder in the popup if it has at least 1 tool inside
            if (tool.folderName != null && tool.customTarget == null) {
                return@filter loadCustomFolderTools(context, tool.folderName).isNotEmpty()
            }
            true
        }
    }

    fun saveExactOrder(context: Context, orderedIds: List<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString(KEY_ORDER, orderedIds.joinToString(","))

        if (isSortMostUsed(context) && orderedIds.isNotEmpty()) {
            var runningMax = 0
            for (i in orderedIds.indices.reversed()) {
                val id = orderedIds[i]
                val c = getUseCount(context, id)
                if (c < runningMax) {
                    editor.putInt(PREFIX_COUNT + id, runningMax)
                } else {
                    runningMax = c
                }
            }
        }
        editor.apply()
    }

    // --- Full Settings + Custom Commands JSON Export & Import ---
    fun exportBackupJson(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rulesFile = ensureRulesFile(context)
        val root = JSONObject()
        root.put("app", "sel-android")
        root.put("version", "2.6.0")
        root.put("timestamp", System.currentTimeMillis())
        root.put("follow_system_theme", isFollowSystemTheme(context))
        root.put("dark_theme_manual", isDarkThemeManual(context))
        root.put("sort_most_used", isSortMostUsed(context))
        root.put("snap_edge_left", isSnapLeftEdge(context))
        root.put("grid_two_columns", isTwoColumnGrid(context))
        root.put("icon_row_1", getIconRowIds(context, 1).joinToString(","))
        root.put("icon_row_2", getIconRowIds(context, 2).joinToString(","))
        root.put("custom_folders", getCustomFolders(context).joinToString(","))
        root.put("manual_order", prefs.getString(KEY_ORDER, "") ?: "")
        root.put("blacklisted_pkgs", prefs.getString(KEY_BLACKLIST, "") ?: "")
        root.put("rules_txt", if (rulesFile.exists()) rulesFile.readText() else "")

        val hiddenArr = JSONArray()
        val countsObj = JSONObject()
        val folderAssignObj = JSONObject()
        for ((k, v) in prefs.all) {
            if (k.startsWith(PREFIX_HIDDEN) && v == true) {
                hiddenArr.put(k.removePrefix(PREFIX_HIDDEN))
            } else if (k.startsWith(PREFIX_COUNT) && v is Int && v > 0) {
                countsObj.put(k.removePrefix(PREFIX_COUNT), v)
            } else if (k.startsWith(PREFIX_TOOL_FOLDER) && v is String && v.isNotBlank()) {
                folderAssignObj.put(k.removePrefix(PREFIX_TOOL_FOLDER), v)
            }
        }
        root.put("hidden_tools", hiddenArr)
        root.put("use_counts", countsObj)
        root.put("tool_folders", folderAssignObj)

        val pinnedClips = JSONArray()
        getClipHistory(context).filter { it.pinned }.forEach {
            pinnedClips.put(it.text)
        }
        root.put("pinned_clips", pinnedClips)
        return root.toString(2)
    }

    fun importBackupJson(context: Context, rawJson: String): Boolean {
        return runCatching {
            val root = JSONObject(rawJson.trim())
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()

            if (root.has("follow_system_theme")) {
                editor.putBoolean(KEY_FOLLOW_SYSTEM_THEME, root.optBoolean("follow_system_theme", true))
            }
            if (root.has("dark_theme_manual")) {
                editor.putBoolean(KEY_DARK_THEME_MANUAL, root.optBoolean("dark_theme_manual", true))
            }
            if (root.has("sort_most_used")) {
                editor.putBoolean(KEY_SORT_MOST_USED, root.optBoolean("sort_most_used", true))
            }
            if (root.has("snap_edge_left")) {
                editor.putBoolean(KEY_EDGE_LEFT, root.optBoolean("snap_edge_left", false))
            }
            if (root.has("grid_two_columns")) {
                editor.putBoolean(KEY_GRID_COLUMNS, root.optBoolean("grid_two_columns", false))
            }
            if (root.has("icon_row_1")) {
                editor.putString(KEY_ICON_ROW_1, root.optString("icon_row_1", ""))
                editor.putBoolean(KEY_ICON_ROWS_INIT, true)
            }
            if (root.has("icon_row_2")) {
                editor.putString(KEY_ICON_ROW_2, root.optString("icon_row_2", ""))
                editor.putBoolean(KEY_ICON_ROWS_INIT, true)
            }
            if (root.has("custom_folders")) {
                editor.putString(KEY_CUSTOM_FOLDERS, root.optString("custom_folders", ""))
            }
            if (root.has("manual_order")) {
                editor.putString(KEY_ORDER, root.optString("manual_order", ""))
            }
            if (root.has("blacklisted_pkgs")) {
                editor.putString(KEY_BLACKLIST, root.optString("blacklisted_pkgs", ""))
            }

            if (root.has("hidden_tools")) {
                prefs.all.keys.filter { it.startsWith(PREFIX_HIDDEN) }.forEach { editor.remove(it) }
                val arr = root.getJSONArray("hidden_tools")
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i, "")
                    if (id.isNotEmpty()) editor.putBoolean(PREFIX_HIDDEN + id, true)
                }
            }

            if (root.has("tool_folders")) {
                prefs.all.keys.filter { it.startsWith(PREFIX_TOOL_FOLDER) }.forEach { editor.remove(it) }
                val fObj = root.getJSONObject("tool_folders")
                val keys = fObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val fName = sanitizeFolderName(fObj.optString(k, ""))
                    if (fName.isNotEmpty()) editor.putString(PREFIX_TOOL_FOLDER + k, fName)
                }
            }

            if (root.has("use_counts")) {
                val cObj = root.getJSONObject("use_counts")
                val keys = cObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    editor.putInt(PREFIX_COUNT + k, cObj.optInt(k, 0))
                }
            }
            editor.apply()

            if (root.has("rules_txt")) {
                val rulesTxt = root.optString("rules_txt", "")
                if (rulesTxt.isNotBlank()) {
                    File(context.filesDir, "rules.txt").writeText(rulesTxt)
                }
            }

            if (root.has("pinned_clips")) {
                val pArr = root.getJSONArray("pinned_clips")
                for (i in 0 until pArr.length()) {
                    val clip = pArr.optString(i, "")
                    if (clip.isNotBlank()) {
                        addClipToHistory(context, clip)
                        togglePinClip(context, clip)
                    }
                }
            }
            true
        }.getOrDefault(false)
    }

    fun attachDragHandle(
        handleView: View,
        rowView: View,
        container: LinearLayout,
        onOrderSaved: (List<String>) -> Unit
    ) {
        var downRawY = 0f
        var origIndex = -1
        var targetIndex = -1
        var rowHeight = 1

        handleView.setOnTouchListener { v, event ->
            runCatching {
                val palette = resolvePalette(rowView.context)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        container.requestDisallowInterceptTouchEvent(true)
                        container.parent?.requestDisallowInterceptTouchEvent(true)
                        downRawY = event.rawY
                        origIndex = container.indexOfChild(rowView)
                        targetIndex = origIndex
                        rowHeight = rowView.height.coerceAtLeast(1)
                        rowView.setBackgroundColor(Color.parseColor(palette.bgPressed))
                        rowView.alpha = 0.94f
                        rowView.translationZ = 10f
                        runCatching { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (origIndex < 0 || origIndex >= container.childCount) return@runCatching false
                        container.requestDisallowInterceptTouchEvent(true)
                        container.parent?.requestDisallowInterceptTouchEvent(true)

                        val dy = event.rawY - downRawY
                        rowView.translationY = dy

                        val shiftSlots = (dy / rowHeight.toFloat()).roundToInt()
                        val newTarget = (origIndex + shiftSlots).coerceIn(0, (container.childCount - 1).coerceAtLeast(0))
                        if (newTarget != targetIndex) {
                            targetIndex = newTarget
                            runCatching { v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                        }

                        for (i in 0 until container.childCount) {
                            if (i == origIndex) continue
                            val child = container.getChildAt(i) ?: continue
                            child.translationY = when {
                                origIndex < targetIndex && i in (origIndex + 1)..targetIndex -> -rowHeight.toFloat()
                                origIndex > targetIndex && i in targetIndex until origIndex -> rowHeight.toFloat()
                                else -> 0f
                            }
                        }
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        for (i in 0 until container.childCount) {
                            val child = container.getChildAt(i) ?: continue
                            child.translationY = 0f
                            child.alpha = 1.0f
                            child.translationZ = 0f
                            child.setBackgroundColor(Color.TRANSPARENT)
                        }

                        val count = container.childCount
                        if (origIndex in 0 until count && targetIndex in 0 until count) {
                            val ids = mutableListOf<String>()
                            for (i in 0 until count) {
                                val tagId = container.getChildAt(i)?.tag as? String
                                if (tagId != null) ids.add(tagId)
                            }
                            if (ids.size == count && origIndex != targetIndex) {
                                val movedId = ids.removeAt(origIndex)
                                ids.add(targetIndex, movedId)
                                saveExactOrder(rowView.context, ids)
                                container.post { onOrderSaved(ids) }
                            }
                        }
                        origIndex = -1
                        targetIndex = -1
                        true
                    }

                    else -> false
                }
            }.getOrDefault(false)
        }
    }
}

/**
 * Automatic Favicon Loader + Memory/Disk Cache for built-in and user-added Web Search Engines.
 */
object FaviconManager {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(3)
    private val inFlight = Collections.synchronizedSet(HashSet<String>())

    private val memoryCache = object : LruCache<String, Bitmap>(64) {}

    fun extractDomain(rawUrlOrTemplate: String?): String? {
        if (rawUrlOrTemplate.isNullOrBlank()) return null
        val trimmed = rawUrlOrTemplate.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        val sanitized = trimmed.replace("{text}", "test")
        val host = runCatching { Uri.parse(sanitized).host?.lowercase() }.getOrNull()
        if (!host.isNullOrBlank()) return host
        return runCatching {
            val withoutScheme = trimmed.substringAfter("://").substringBefore("/")
            withoutScheme.lowercase().trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun isWebEngineTool(tool: ToolSpec): Boolean {
        if (tool.id == "youtube_folder" || tool.id == "web_search_folder") return false
        return extractDomain(tool.customTarget) != null
    }

    private val fallbackCache = object : LruCache<String, Bitmap>(64) {}

    fun bindFavicon(context: Context, imageView: ImageView, rawUrl: String, fallbackGlyph: String, sizePx: Int) {
        val domain = extractDomain(rawUrl)
        if (domain == null) {
            val key = "glyph_${fallbackGlyph}_$sizePx"
            val cached = fallbackCache.get(key) ?: createMonogramBitmap(fallbackGlyph, "#282A36", "#50FA7B", sizePx).also {
                fallbackCache.put(key, it)
            }
            imageView.setImageBitmap(cached)
            return
        }

        imageView.tag = domain

        val memBmp = memoryCache.get(domain)
        if (memBmp != null) {
            imageView.setImageBitmap(memBmp)
            return
        }

        val fbKey = "${domain}_$sizePx"
        val fbBmp = fallbackCache.get(fbKey) ?: createBrandFallbackBitmap(domain, fallbackGlyph, sizePx).also {
            fallbackCache.put(fbKey, it)
        }
        imageView.setImageBitmap(fbBmp)

        fetchFaviconAsync(context.applicationContext, domain, sizePx) { fetched ->
            if (fetched != null && imageView.tag == domain) {
                imageView.setImageBitmap(fetched)
            }
        }
    }

    fun prefetchAllForTools(context: Context) {
        val appCtx = context.applicationContext
        val dp18 = (18 * appCtx.resources.displayMetrics.density).toInt().coerceAtLeast(36)
        val urls = mutableSetOf<String>()

        ToolPrefs.webSearchSubTools.forEach { it.customTarget?.let(urls::add) }
        ToolPrefs.youtubeSubTools.forEach { it.customTarget?.let(urls::add) }
        ToolPrefs.loadAllTools(appCtx).forEach { it.customTarget?.let(urls::add) }
        ToolPrefs.loadWebSearchTools(appCtx).forEach { it.customTarget?.let(urls::add) }
        ToolPrefs.loadYoutubeTools(appCtx).forEach { it.customTarget?.let(urls::add) }

        val rulesFile = ToolPrefs.ensureRulesFile(appCtx)
        if (rulesFile.exists()) {
            rulesFile.readLines().forEach { line ->
                val parts = line.split("|").map { it.trim() }
                if (parts.size >= 3 && (parts[2].startsWith("http://", true) || parts[2].startsWith("https://", true))) {
                    urls.add(parts[2])
                }
            }
        }

        for (u in urls) {
            val domain = extractDomain(u) ?: continue
            if (memoryCache.get(domain) != null) continue
            val f = getDiskCacheFile(appCtx, domain)
            if (f.exists() && f.length() > 64) continue
            fetchFaviconAsync(appCtx, domain, dp18) {}
        }
    }

    private fun fetchFaviconAsync(
        context: Context,
        domain: String,
        sizePx: Int,
        onComplete: (Bitmap?) -> Unit
    ) {
        if (!inFlight.add(domain)) return
        executor.execute {
            var resultBmp: Bitmap? = null
            try {
                val diskFile = getDiskCacheFile(context, domain)
                if (diskFile.exists() && diskFile.length() > 64) {
                    val diskBmp = runCatching { BitmapFactory.decodeFile(diskFile.absolutePath) }.getOrNull()
                    if (diskBmp != null) {
                        val rounded = makeRoundedIcon(diskBmp, sizePx)
                        resultBmp = rounded
                        memoryCache.put(domain, rounded)
                    }
                }
                val baseDomain = domain.removePrefix("www.")
                val candidates = listOf(
                    "https://www.google.com/s2/favicons?domain=$domain&sz=64",
                    "https://icons.duckduckgo.com/ip3/$domain.ico",
                    "https://www.google.com/s2/favicons?domain=$baseDomain&sz=64",
                    "https://$domain/favicon.ico"
                )
                if (resultBmp == null) for (endpoint in candidates) {
                    val bmp = downloadBitmap(endpoint)
                    if (bmp != null && bmp.width >= 8 && bmp.height >= 8) {
                        val rounded = makeRoundedIcon(bmp, sizePx)
                        resultBmp = rounded
                        memoryCache.put(domain, rounded)
                        saveToDiskCache(context, domain, rounded)
                        break
                    }
                }
            } finally {
                inFlight.remove(domain)
                val finalBmp = resultBmp
                mainHandler.post { onComplete(finalBmp) }
            }
        }
    }

    private fun downloadBitmap(urlStr: String): Bitmap? {
        return runCatching {
            var currentUrl = urlStr
            var redirects = 0
            while (redirects < 4) {
                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android; sel-android/2.5)")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: break
                    currentUrl = if (loc.startsWith("http")) loc else URL(URL(currentUrl), loc).toString()
                    redirects++
                    continue
                }
                if (code == 200) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (bytes.isNotEmpty()) {
                        return@runCatching BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
                break
            }
            null
        }.getOrNull()
    }

    private fun getDiskCacheFile(context: Context, domain: String): File {
        val dir = File(context.cacheDir, "favicons")
        if (!dir.exists()) dir.mkdirs()
        val safe = domain.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(dir, "$safe.png")
    }

    private fun saveToDiskCache(context: Context, domain: String, bitmap: Bitmap) {
        runCatching {
            val file = getDiskCacheFile(context, domain)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }

    private fun makeRoundedIcon(src: Bitmap, sizePx: Int): Bitmap {
        val target = sizePx.coerceAtLeast(32)
        val scaled = Bitmap.createScaledBitmap(src, target, target, true)
        val out = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, target, target)
        val rectF = RectF(rect)
        val radius = target * 0.20f

        canvas.drawRoundRect(rectF, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(scaled, rect, rect, paint)
        return out
    }

    private fun createBrandFallbackBitmap(domain: String, fallbackGlyph: String, sizePx: Int): Bitmap {
        val d = domain.lowercase()
        return when {
            d.contains("google.") -> createMonogramBitmap("G", "#4285F4", "#FFFFFF", sizePx)
            d.contains("duckduckgo.") -> createMonogramBitmap("D", "#DE5833", "#FFFFFF", sizePx)
            d.contains("bing.") -> createMonogramBitmap("b", "#008373", "#FFFFFF", sizePx)
            d.contains("brave.") -> createMonogramBitmap("B", "#FB542B", "#FFFFFF", sizePx)
            d.contains("youtube.") || d.contains("youtu.be") -> createMonogramBitmap("|>", "#FF0000", "#FFFFFF", sizePx)
            d.contains("wikipedia.") -> createMonogramBitmap("W", "#E8E8E8", "#111111", sizePx)
            else -> {
                val letter = d.removePrefix("www.").removePrefix("search.").firstOrNull()?.uppercaseChar()?.toString()
                    ?: fallbackGlyph.take(1)
                createMonogramBitmap(letter, "#282A36", "#50FA7B", sizePx)
            }
        }
    }

    private fun createMonogramBitmap(letter: String, bgHex: String, fgHex: String, sizePx: Int): Bitmap {
        val target = sizePx.coerceAtLeast(32)
        val out = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(bgHex)
            style = Paint.Style.FILL
        }
        val radius = target * 0.22f
        canvas.drawRoundRect(RectF(0f, 0f, target.toFloat(), target.toFloat()), radius, radius, bgPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(fgHex)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = if (letter.length > 1) target * 0.46f else target * 0.58f
            textAlign = Paint.Align.CENTER
        }
        val y = target / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(letter.take(2), target / 2f, y, textPaint)
        return out
    }
}
