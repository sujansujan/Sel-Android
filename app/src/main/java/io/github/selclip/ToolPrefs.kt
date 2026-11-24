package io.github.selclip

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

data class ToolSpec(
    val id: String,
    val glyph: String,
    val label: String,
    val customTarget: String? = null,
    val folderName: String? = null
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
 *
 * Pre-parses all hex colors into Ints once at construction so hot paths (popup build,
 * LensOverlayView.onDraw) never allocate Strings or invoke Color.parseColor().
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
) {
    val bgPrimaryInt: Int = Color.parseColor(bgPrimary)
    val bgHeaderInt: Int = Color.parseColor(bgHeader)
    val bgSurfaceInt: Int = Color.parseColor(bgSurface)
    val bgInputInt: Int = Color.parseColor(bgInput)
    val bgPressedInt: Int = Color.parseColor(bgPressed)
    val borderInt: Int = Color.parseColor(border)
    val dividerInt: Int = Color.parseColor(divider)
    val fgPrimaryInt: Int = Color.parseColor(fgPrimary)
    val fgSecondaryInt: Int = Color.parseColor(fgSecondary)
    val fgMutedInt: Int = Color.parseColor(fgMuted)
    val accentGreenInt: Int = Color.parseColor(accentGreen)
    val accentCyanInt: Int = Color.parseColor(accentCyan)
    val accentPurpleInt: Int = Color.parseColor(accentPurple)
    val accentPinkInt: Int = Color.parseColor(accentPink)
    val accentOrangeInt: Int = Color.parseColor(accentOrange)
    val accentYellowInt: Int = Color.parseColor(accentYellow)
    val dangerRedInt: Int = Color.parseColor(dangerRed)
    val onAccentInt: Int = Color.parseColor(onAccent)
}

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
    private const val KEY_DEFAULT_SEARCH_ID = "default_search_engine_id"
    private const val KEY_PINNED_TOOLS = "pinned_bar_tool_ids"
    private const val PREFIX_TOOL_FOLDER = "tool_folder_"
    private const val PREFIX_COUNT = "use_count_"
    private const val PREFIX_HIDDEN = "hidden_"
    private const val MAX_CLIPS = 20
    const val MAX_TOOLS_PER_ICON_ROW = 4

    // In-memory caches to keep AccessibilityEvent hot paths & popup rendering disk-free and allocation-free
    private data class ParsedRulesSnapshot(
        val lastModified: Long,
        val fileLength: Long,
        val entries: List<CustomRuleEntry>,
        val topLevelCustomTools: List<ToolSpec>,
        val webSearchCustomTools: List<ToolSpec>,
        val youtubeCustomTools: List<ToolSpec>,
        val markdownCustomTools: List<ToolSpec>,
        val regexPipeCustomTools: List<ToolSpec>,
        val folderRuleTools: Map<String, List<ToolSpec>>,
        val ruleFolderNames: Set<String>
    )

    @Volatile
    private var cachedRules: ParsedRulesSnapshot? = null

    @Volatile
    private var cachedBlacklist: Set<String>? = null

    @Volatile
    private var cachedClipHistory: List<ClipEntry>? = null

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun invalidateRulesCache() {
        cachedRules = null
    }

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
        ToolSpec("youtube_folder", "|>", "YOUTUBE >"),
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

    val webSearchSubTools = listOf(
        ToolSpec("web_google", "GGL", "GOOGLE", customTarget = "https://www.google.com/search?q={text}"),
        ToolSpec("web_ddg", "DDG", "DUCKDUCKGO", customTarget = "https://duckduckgo.com/?q={text}"),
        ToolSpec("web_bing", "BNG", "BING", customTarget = "https://www.bing.com/search?q={text}"),
        ToolSpec("web_brave", "BRV", "BRAVE SEARCH", customTarget = "https://search.brave.com/search?q={text}")
    )

    val youtubeSubTools = listOf(
        ToolSpec("yt_all", "|>", "YOUTUBE (ALL)", customTarget = "https://www.youtube.com/results?search_query={text}"),
        ToolSpec("yt_hour", "1H", "YT LAST HOUR", customTarget = "https://www.youtube.com/results?search_query={text}&sp=EgIIAQ%3D%3D"),
        ToolSpec("yt_today", "24H", "YT TODAY", customTarget = "https://www.youtube.com/results?search_query={text}&sp=EgIIAg%3D%3D"),
        ToolSpec("yt_week", "7D", "YT THIS WEEK", customTarget = "https://www.youtube.com/results?search_query={text}&sp=EgIIAw%3D%3D"),
        ToolSpec("yt_month", "30D", "YT THIS MONTH", customTarget = "https://www.youtube.com/results?search_query={text}&sp=EgIIBA%3D%3D"),
        ToolSpec("yt_year", "1Y", "YT THIS YEAR", customTarget = "https://www.youtube.com/results?search_query={text}&sp=EgIIBQ%3D%3D")
    )

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

    // --- Theme Preferences ---
    fun isFollowSystemTheme(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FOLLOW_SYSTEM_THEME, true)

    fun setFollowSystemTheme(context: Context, follow: Boolean) {
        prefs(context).edit().putBoolean(KEY_FOLLOW_SYSTEM_THEME, follow).apply()
    }

    fun isDarkThemeManual(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DARK_THEME_MANUAL, true)

    fun setDarkThemeManual(context: Context, dark: Boolean) {
        prefs(context).edit().putBoolean(KEY_DARK_THEME_MANUAL, dark).apply()
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
        val rulesFile = File(context.applicationContext.filesDir, "rules.txt")
        if (!rulesFile.exists()) {
            rulesFile.writeText(defaultRulesText())
            invalidateRulesCache()
        } else {
            val p = prefs(context)
            if (!p.getBoolean(KEY_MIGRATED_V25, false)) {
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
                p.edit().putBoolean(KEY_MIGRATED_V25, true).apply()
                invalidateRulesCache()
            }
        }
        return rulesFile
    }

    private fun getParsedRules(context: Context): ParsedRulesSnapshot {
        val rulesFile = ensureRulesFile(context)
        val mod = rulesFile.lastModified()
        val len = rulesFile.length()
        val existing = cachedRules
        if (existing != null && existing.lastModified == mod && existing.fileLength == len) {
            return existing
        }

        val entries = mutableListOf<CustomRuleEntry>()
        val topLevel = mutableListOf<ToolSpec>()
        val webSearch = mutableListOf<ToolSpec>()
        val youtube = mutableListOf<ToolSpec>()
        val markdown = mutableListOf<ToolSpec>()
        val regexPipe = mutableListOf<ToolSpec>()
        val folderTools = LinkedHashMap<String, MutableList<ToolSpec>>()
        val ruleFolders = LinkedHashSet<String>()

        if (rulesFile.exists()) {
            rulesFile.readLines().forEachIndexed { idx, line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachIndexed
                val parts = trimmed.split("|").map { it.trim() }
                if (parts.size < 3) return@forEachIndexed

                val rawLabel = parts[1].uppercase()
                val target = parts[2]
                val glyph = toPlainAsciiCode(parts[0], parts[1], target)
                entries.add(CustomRuleEntry(idx, glyph, rawLabel, target))

                when {
                    rawLabel.startsWith("SEARCH/") || rawLabel.startsWith("WEB/") -> {
                        val cleanLabel = rawLabel.removePrefix("SEARCH/").removePrefix("WEB/").trim()
                        if (cleanLabel.isNotEmpty()) {
                            webSearch.add(
                                ToolSpec("custom_search_${idx}_$cleanLabel", glyph, cleanLabel, customTarget = target)
                            )
                        }
                    }
                    rawLabel.startsWith("YT/") || rawLabel.startsWith("YOUTUBE/") -> {
                        val cleanLabel = rawLabel.removePrefix("YOUTUBE/").removePrefix("YT/").trim()
                        if (cleanLabel.isNotEmpty()) {
                            youtube.add(
                                ToolSpec("custom_yt_${idx}_$cleanLabel", glyph, cleanLabel, customTarget = target)
                            )
                        }
                    }
                    rawLabel.startsWith("MD ") || rawLabel.startsWith("MARKDOWN/") || rawLabel.startsWith("MD/") -> {
                        val cleanLabel = rawLabel.removePrefix("MARKDOWN/").removePrefix("MD/").trim()
                        val alreadyBuiltIn = markdownSubTools.any {
                            it.label.startsWith(cleanLabel) && (target == "**{text}**" || target == "*{text}*")
                        }
                        if (!alreadyBuiltIn) {
                            markdown.add(
                                ToolSpec("custom_md_${idx}_$cleanLabel", glyph, cleanLabel, customTarget = target)
                            )
                        }
                    }
                    rawLabel.startsWith("PIPE/") || rawLabel.startsWith("REGEX/") || target.startsWith("s/") -> {
                        val cleanLabel = rawLabel.removePrefix("PIPE/").removePrefix("REGEX/").trim()
                        regexPipe.add(
                            ToolSpec("custom_pipe_${idx}_$cleanLabel", glyph, cleanLabel, customTarget = target)
                        )
                    }
                    rawLabel.contains("/") -> {
                        val folder = sanitizeFolderName(rawLabel.substringBefore("/"))
                        val subLabel = rawLabel.substringAfter("/").trim()
                        if (folder.isNotEmpty() && folder !in reservedFolderPrefixes) {
                            ruleFolders.add(folder)
                            if (subLabel.isNotEmpty()) {
                                folderTools.getOrPut(folder) { mutableListOf() }.add(
                                    ToolSpec(
                                        id = "folder_item_${idx}_$subLabel",
                                        glyph = glyph,
                                        label = subLabel,
                                        customTarget = target,
                                        folderName = folder
                                    )
                                )
                            }
                        }
                    }
                    else -> {
                        topLevel.add(
                            ToolSpec(
                                id = "custom_${idx}_$rawLabel",
                                glyph = glyph,
                                label = rawLabel,
                                customTarget = target
                            )
                        )
                    }
                }
            }
        }

        val snap = ParsedRulesSnapshot(
            lastModified = mod,
            fileLength = len,
            entries = entries,
            topLevelCustomTools = topLevel,
            webSearchCustomTools = webSearch,
            youtubeCustomTools = youtube,
            markdownCustomTools = markdown,
            regexPipeCustomTools = regexPipe,
            folderRuleTools = folderTools,
            ruleFolderNames = ruleFolders
        )
        cachedRules = snap
        return snap
    }

    // --- User-Created Custom Folders & Tool Assignment ---
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
        val saved = prefs(context).getString(KEY_CUSTOM_FOLDERS, "")
            ?.split(",")
            ?.map { sanitizeFolderName(it) }
            ?.filter { it.isNotEmpty() && it !in reservedFolderPrefixes }
            ?: emptyList()

        val combined = LinkedHashSet<String>()
        combined.addAll(saved)
        combined.addAll(getParsedRules(context).ruleFolderNames)
        return combined.toList()
    }

    fun createCustomFolder(context: Context, rawName: String): Boolean {
        val clean = sanitizeFolderName(rawName)
        if (clean.isEmpty() || clean in reservedFolderPrefixes) return false
        val current = getCustomFolders(context).toMutableSet()
        current.add(clean)
        prefs(context).edit().putString(KEY_CUSTOM_FOLDERS, current.joinToString(",")).apply()
        return true
    }

    fun deleteCustomFolder(context: Context, folderName: String) {
        val target = sanitizeFolderName(folderName)
        if (target.isEmpty()) return
        val p = prefs(context)
        val remaining = getCustomFolders(context).filterNot { it == target }
        val editor = p.edit()
        editor.putString(KEY_CUSTOM_FOLDERS, remaining.joinToString(","))

        p.all.forEach { (k, v) ->
            if (k.startsWith(PREFIX_TOOL_FOLDER) && (v as? String)?.equals(target, ignoreCase = true) == true) {
                editor.remove(k)
            }
        }
        editor.apply()

        setToolIconRow(context, "folder_custom_$target", 0)

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
            invalidateRulesCache()
        }
    }

    fun getToolFolderAssignment(context: Context, toolId: String): String? {
        if (toolId == "copy" || toolId.startsWith("folder_custom_")) return null
        val raw = prefs(context).getString(PREFIX_TOOL_FOLDER + toolId, "")?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val validFolders = getCustomFolders(context)
        return raw.takeIf { it in validFolders }
    }

    fun setToolFolderAssignment(context: Context, toolId: String, folderName: String?) {
        if (toolId == "copy" || toolId.startsWith("folder_custom_")) return
        val clean = folderName?.let { sanitizeFolderName(it) }.orEmpty()
        val editor = prefs(context).edit()
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

    fun loadAssignableToolsForFolder(context: Context): List<ToolSpec> =
        loadBaseToolsWithoutFolderEntries(context).filter { it.id != "copy" }

    fun getCustomRuleEntries(context: Context): List<CustomRuleEntry> =
        getParsedRules(context).entries

    fun deleteCustomRuleAtLine(context: Context, targetLineIndex: Int) {
        val rulesFile = ensureRulesFile(context)
        if (!rulesFile.exists()) return
        val lines = rulesFile.readLines().toMutableList()
        if (targetLineIndex in lines.indices) {
            lines.removeAt(targetLineIndex)
            rulesFile.writeText(lines.joinToString("\n"))
            invalidateRulesCache()
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
        invalidateRulesCache()
        return true
    }

    private fun loadBaseToolsWithoutFolderEntries(context: Context): List<ToolSpec> {
        val snap = getParsedRules(context)
        return builtInTools + snap.topLevelCustomTools
    }

    fun loadAllTools(context: Context): List<ToolSpec> {
        val list = ArrayList<ToolSpec>(builtInTools.size + 8)
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

    fun loadMarkdownTools(context: Context): List<ToolSpec> =
        markdownSubTools + getParsedRules(context).markdownCustomTools

    fun loadRegexPipeTools(context: Context): List<ToolSpec> =
        regexPipeSubTools + getParsedRules(context).regexPipeCustomTools

    fun loadWebSearchTools(context: Context): List<ToolSpec> =
        webSearchSubTools + getParsedRules(context).webSearchCustomTools

    fun loadYoutubeTools(context: Context): List<ToolSpec> =
        youtubeSubTools + getParsedRules(context).youtubeCustomTools

    fun loadCustomFolderTools(context: Context, folderName: String): List<ToolSpec> {
        val targetFolder = sanitizeFolderName(folderName)
        if (targetFolder.isEmpty()) return emptyList()
        val p = prefs(context)
        val validFolders = getCustomFolders(context).toHashSet()
        if (targetFolder !in validFolders) return emptyList()

        val list = mutableListOf<ToolSpec>()
        loadBaseToolsWithoutFolderEntries(context).forEach { tool ->
            if (!p.getBoolean(PREFIX_HIDDEN + tool.id, false)) {
                val assigned = p.getString(PREFIX_TOOL_FOLDER + tool.id, "")?.trim()
                if (assigned == targetFolder) {
                    list.add(tool)
                }
            }
        }
        getParsedRules(context).folderRuleTools[targetFolder]?.let { list.addAll(it) }
        return list
    }

    // --- Ring-Buffer Clipboard History (20 items max, cached in memory) ---
    fun getClipHistory(context: Context): List<ClipEntry> {
        cachedClipHistory?.let { return it }
        val raw = prefs(context).getString(KEY_CLIP_HISTORY, "[]") ?: "[]"
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
        val sorted = out.sortedWith(compareByDescending<ClipEntry> { it.pinned }.thenByDescending { it.timestamp })
        cachedClipHistory = sorted
        return sorted
    }

    private fun saveClipHistory(context: Context, entries: List<ClipEntry>) {
        val sorted = entries
            .sortedWith(compareByDescending<ClipEntry> { it.pinned }.thenByDescending { it.timestamp })
            .take(MAX_CLIPS)
        cachedClipHistory = sorted
        val arr = JSONArray()
        for (e in sorted) {
            arr.put(JSONObject().apply {
                put("t", e.text.take(2000))
                put("p", e.pinned)
                put("ts", e.timestamp)
            })
        }
        prefs(context).edit().putString(KEY_CLIP_HISTORY, arr.toString()).apply()
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

    fun setPinClip(context: Context, text: String, pinned: Boolean) {
        val current = getClipHistory(context).map {
            if (it.text == text) it.copy(pinned = pinned) else it
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

    // --- Per-App Blacklist (O(1) cached lookup for AccessibilityEvent hot path) ---
    fun getBlacklistedApps(context: Context): Set<String> {
        cachedBlacklist?.let { return it }
        val raw = prefs(context).getString(KEY_BLACKLIST, "") ?: ""
        val parsed = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
        cachedBlacklist = parsed
        return parsed
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
        cachedBlacklist = current.toHashSet()
        prefs(context).edit().putString(KEY_BLACKLIST, current.sorted().joinToString(",")).apply()
    }

    // --- Edge-Snap & Layout Preferences ---
    fun isSnapLeftEdge(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EDGE_LEFT, false)

    fun setSnapLeftEdge(context: Context, leftEdge: Boolean) {
        prefs(context).edit().putBoolean(KEY_EDGE_LEFT, leftEdge).apply()
    }

    fun isTwoColumnGrid(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GRID_COLUMNS, false)

    fun setTwoColumnGrid(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GRID_COLUMNS, enabled).apply()
    }

    // --- Customizable Default Search Engine (1-tap [WEB] action; long-press shows all engines) ---
    fun getDefaultSearchEngineId(context: Context): String {
        return prefs(context).getString(KEY_DEFAULT_SEARCH_ID, "web_google")
            ?.takeIf { it.isNotBlank() } ?: "web_google"
    }

    fun setDefaultSearchEngineId(context: Context, engineId: String) {
        prefs(context).edit().putString(KEY_DEFAULT_SEARCH_ID, engineId.trim()).apply()
    }

    fun resolveDefaultSearchEngine(context: Context): ToolSpec {
        val targetId = getDefaultSearchEngineId(context)
        val engines = loadWebSearchTools(context)
        return engines.firstOrNull { it.id == targetId } ?: webSearchSubTools.first()
    }

    // --- Always-Visible Pinned Actions on the PopClip Bar ---
    fun getPinnedToolIds(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_PINNED_TOOLS, "") ?: ""
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "copy" && it != "web_search_folder" && it != "youtube_folder" }
            .distinct()
    }

    fun isToolPinned(context: Context, toolId: String): Boolean {
        return toolId in getPinnedToolIds(context)
    }

    fun toggleToolPinned(context: Context, toolId: String): Boolean {
        if (toolId == "copy" || toolId == "web_search_folder" || toolId == "youtube_folder") return false
        val current = getPinnedToolIds(context).toMutableList()
        val nowPinned = if (toolId in current) {
            current.remove(toolId)
            false
        } else {
            current.add(toolId)
            true
        }
        prefs(context).edit().putString(KEY_PINNED_TOOLS, current.joinToString(",")).apply()
        return nowPinned
    }

    fun getPinnedToolSpecs(context: Context): List<ToolSpec> {
        val ids = getPinnedToolIds(context)
        if (ids.isEmpty()) return emptyList()
        val allMap = (loadAllTools(context) + loadWebSearchTools(context)).associateBy { it.id }
        return ids.mapNotNull { id ->
            val spec = allMap[id]
            if (spec != null && !isToolHidden(context, id)) spec else null
        }
    }

    // --- Up to 2 Custom Icon-Only Tool Rows (Row 1 & Row 2, max 4 items per row) ---
    private fun ensureDefaultIconRows(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean(KEY_ICON_ROWS_INIT, false)) {
            p.edit()
                .putString(KEY_ICON_ROW_1, "web_search_folder,youtube_folder,translate,define")
                .putString(KEY_ICON_ROW_2, "")
                .putBoolean(KEY_ICON_ROWS_INIT, true)
                .apply()
        }
    }

    fun getIconRowIds(context: Context, rowNumber: Int): List<String> {
        ensureDefaultIconRows(context)
        val key = if (rowNumber == 1) KEY_ICON_ROW_1 else KEY_ICON_ROW_2
        val raw = prefs(context).getString(key, "") ?: ""
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
        prefs(context).edit().putString(key, clean.joinToString(",")).apply()
    }

    fun getIconRowTools(context: Context, rowNumber: Int): List<ToolSpec> {
        val ids = getIconRowIds(context, rowNumber)
        if (ids.isEmpty()) return emptyList()
        val allMap = loadAllTools(context).associateBy { it.id }
        val p = prefs(context)
        return ids.mapNotNull { id ->
            val spec = allMap[id]
            if (spec != null && !p.getBoolean(PREFIX_HIDDEN + id, false)) spec else null
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

    fun isSortMostUsed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SORT_MOST_USED, true)

    fun setSortMostUsed(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SORT_MOST_USED, enabled).apply()
    }

    fun isToolHidden(context: Context, id: String): Boolean =
        prefs(context).getBoolean(PREFIX_HIDDEN + id, false)

    fun setToolHidden(context: Context, id: String, hidden: Boolean) {
        prefs(context).edit().putBoolean(PREFIX_HIDDEN + id, hidden).apply()
    }

    fun getUseCount(context: Context, id: String): Int =
        prefs(context).getInt(PREFIX_COUNT + id, 0)

    fun recordUse(context: Context, id: String) {
        val p = prefs(context)
        val current = p.getInt(PREFIX_COUNT + id, 0)
        p.edit().putInt(PREFIX_COUNT + id, current + 1).apply()
    }

    fun resetUseCounts(context: Context) {
        val p = prefs(context)
        val editor = p.edit()
        p.all.keys.filter { it.startsWith(PREFIX_COUNT) }.forEach { editor.remove(it) }
        editor.apply()
    }

    fun getManualOrderedTools(context: Context): List<ToolSpec> {
        val all = loadAllTools(context)
        val savedOrder = prefs(context).getString(KEY_ORDER, "")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val orderMap = savedOrder.withIndex().associate { it.value to it.index }
        val fallbackIndex = all.withIndex().associate { it.value.id to it.index }
        return all.sortedWith(
            compareBy { orderMap[it.id] ?: (1000 + (fallbackIndex[it.id] ?: 0)) }
        )
    }

    fun getAllSortedTools(context: Context): List<ToolSpec> {
        val manual = getManualOrderedTools(context)
        if (!isSortMostUsed(context)) return manual

        val p = prefs(context)
        val manualIndex = manual.withIndex().associate { it.value.id to it.index }
        return manual.sortedWith(
            compareByDescending<ToolSpec> { p.getInt(PREFIX_COUNT + it.id, 0) }
                .thenBy { manualIndex[it.id] ?: 0 }
        )
    }

    fun getSortedToolsForPopup(context: Context): List<ToolSpec> {
        val p = prefs(context)
        val validFolders = getCustomFolders(context).toHashSet()
        return getAllSortedTools(context).filter { tool ->
            if (p.getBoolean(PREFIX_HIDDEN + tool.id, false)) return@filter false
            if (tool.id != "copy" && !tool.id.startsWith("folder_custom_")) {
                val assigned = p.getString(PREFIX_TOOL_FOLDER + tool.id, "")?.trim().orEmpty()
                if (assigned.isNotEmpty() && assigned in validFolders) return@filter false
            }
            if (tool.folderName != null && tool.customTarget == null) {
                return@filter loadCustomFolderTools(context, tool.folderName).isNotEmpty()
            }
            true
        }
    }

    fun saveExactOrder(context: Context, orderedIds: List<String>) {
        val p = prefs(context)
        val editor = p.edit()
        editor.putString(KEY_ORDER, orderedIds.joinToString(","))

        if (isSortMostUsed(context) && orderedIds.isNotEmpty()) {
            var runningMax = 0
            for (i in orderedIds.indices.reversed()) {
                val id = orderedIds[i]
                val c = p.getInt(PREFIX_COUNT + id, 0)
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
        val p = prefs(context)
        val rulesFile = ensureRulesFile(context)
        val root = JSONObject()
        root.put("app", "sel-android")
        root.put("version", "2.7.0")
        root.put("timestamp", System.currentTimeMillis())
        root.put("follow_system_theme", isFollowSystemTheme(context))
        root.put("dark_theme_manual", isDarkThemeManual(context))
        root.put("sort_most_used", isSortMostUsed(context))
        root.put("snap_edge_left", isSnapLeftEdge(context))
        root.put("grid_two_columns", isTwoColumnGrid(context))
        root.put("default_search_id", getDefaultSearchEngineId(context))
        root.put("pinned_tools", getPinnedToolIds(context).joinToString(","))
        root.put("icon_row_1", getIconRowIds(context, 1).joinToString(","))
        root.put("icon_row_2", getIconRowIds(context, 2).joinToString(","))
        root.put("custom_folders", getCustomFolders(context).joinToString(","))
        root.put("manual_order", p.getString(KEY_ORDER, "") ?: "")
        root.put("blacklisted_pkgs", p.getString(KEY_BLACKLIST, "") ?: "")
        root.put("rules_txt", if (rulesFile.exists()) rulesFile.readText() else "")

        val hiddenArr = JSONArray()
        val countsObj = JSONObject()
        val folderAssignObj = JSONObject()
        for ((k, v) in p.all) {
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
            val p = prefs(context)
            val editor = p.edit()

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
            if (root.has("default_search_id")) {
                editor.putString(KEY_DEFAULT_SEARCH_ID, root.optString("default_search_id", "web_google"))
            }
            if (root.has("pinned_tools")) {
                editor.putString(KEY_PINNED_TOOLS, root.optString("pinned_tools", ""))
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
                cachedBlacklist = null
            }

            if (root.has("hidden_tools")) {
                p.all.keys.filter { it.startsWith(PREFIX_HIDDEN) }.forEach { editor.remove(it) }
                val arr = root.getJSONArray("hidden_tools")
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i, "")
                    if (id.isNotEmpty()) editor.putBoolean(PREFIX_HIDDEN + id, true)
                }
            }

            if (root.has("tool_folders")) {
                p.all.keys.filter { it.startsWith(PREFIX_TOOL_FOLDER) }.forEach { editor.remove(it) }
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
                    File(context.applicationContext.filesDir, "rules.txt").writeText(rulesTxt)
                    invalidateRulesCache()
                }
            }

            if (root.has("pinned_clips")) {
                val pArr = root.getJSONArray("pinned_clips")
                for (i in 0 until pArr.length()) {
                    val clip = pArr.optString(i, "")
                    if (clip.isNotBlank()) {
                        addClipToHistory(context, clip)
                        setPinClip(context, clip, true)
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
                        rowView.setBackgroundColor(palette.bgPressedInt)
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
