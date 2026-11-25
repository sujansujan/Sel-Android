package io.github.selclip.data

import android.graphics.Color

data class ToolSpec(
    val id: String,
    val glyph: String,
    val label: String,
    val customTarget: String? = null,
    val folderName: String? = null
)

data class PopAction(
    val id: String,
    val label: String,
    val accent: Boolean = false,
    val payload: String? = null,
    val customTool: ToolSpec? = null
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

data class SearchPreset(
    val buttonLabel: String,
    val glyph: String,
    val ruleName: String,
    val url: String
)

enum class ThemeMode {
    AUTO,
    DARK,
    LIGHT
}

/**
 * High-contrast mobile-optimized Dark and Light themes with pre-parsed color ints
 * so popup rendering performs zero Color.parseColor() calls on hot paths.
 */
data class ThemePalette(
    val name: String,
    val isDark: Boolean,
    val bgPrimaryInt: Int,
    val bgHeaderInt: Int,
    val bgSurfaceInt: Int,
    val bgInputInt: Int,
    val bgPressedInt: Int,
    val borderInt: Int,
    val dividerInt: Int,
    val fgPrimaryInt: Int,
    val fgSecondaryInt: Int,
    val fgMutedInt: Int,
    val accentPrimaryInt: Int,
    val accentSuccessInt: Int,
    val accentInfoInt: Int,
    val accentWarnInt: Int,
    val dangerInt: Int,
    val onAccentInt: Int
)

object AppPalettes {

    val DARK = ThemePalette(
        name = "Dark",
        isDark = true,
        bgPrimaryInt = Color.parseColor("#1E202B"),
        bgHeaderInt = Color.parseColor("#181A24"),
        bgSurfaceInt = Color.parseColor("#282B3A"),
        bgInputInt = Color.parseColor("#14161F"),
        bgPressedInt = Color.parseColor("#383C52"),
        borderInt = Color.parseColor("#444963"),
        dividerInt = Color.parseColor("#34384D"),
        fgPrimaryInt = Color.parseColor("#F5F6FA"),
        fgSecondaryInt = Color.parseColor("#C7A6FF"),
        fgMutedInt = Color.parseColor("#8D95B3"),
        accentPrimaryInt = Color.parseColor("#A78BFA"),
        accentSuccessInt = Color.parseColor("#4ADE80"),
        accentInfoInt = Color.parseColor("#38BDF8"),
        accentWarnInt = Color.parseColor("#FBBF24"),
        dangerInt = Color.parseColor("#F87171"),
        onAccentInt = Color.parseColor("#11131B")
    )

    val LIGHT = ThemePalette(
        name = "Light",
        isDark = false,
        bgPrimaryInt = Color.parseColor("#FAF9F5"),
        bgHeaderInt = Color.parseColor("#F2EFE6"),
        bgSurfaceInt = Color.parseColor("#FFFFFF"),
        bgInputInt = Color.parseColor("#EFECE3"),
        bgPressedInt = Color.parseColor("#E3DFD3"),
        borderInt = Color.parseColor("#D5D0C3"),
        dividerInt = Color.parseColor("#E5E1D6"),
        fgPrimaryInt = Color.parseColor("#1A1C23"),
        fgSecondaryInt = Color.parseColor("#5B42D6"),
        fgMutedInt = Color.parseColor("#6B7280"),
        accentPrimaryInt = Color.parseColor("#5B42D6"),
        accentSuccessInt = Color.parseColor("#15803D"),
        accentInfoInt = Color.parseColor("#0369A1"),
        accentWarnInt = Color.parseColor("#B45309"),
        dangerInt = Color.parseColor("#DC2626"),
        onAccentInt = Color.parseColor("#FFFFFF")
    )
}

object BuiltInRegistry {

    val builtInTools = listOf(
        ToolSpec("web_search_folder", "WEB", "Web Search"),
        ToolSpec("youtube_folder", "YT", "YouTube"),
        ToolSpec("clip_history", "CLP", "Clip History"),
        ToolSpec("define", "DEF", "Define Word"),
        ToolSpec("translate", "TR", "Translate"),
        ToolSpec("markdown_folder", "MD", "Markdown"),
        ToolSpec("regex_pipe", "s/", "Regex / Pipe"),
        ToolSpec("qr", "QR", "QR Code"),
        ToolSpec("magnet", "MAG", "Hash → Magnet"),
        ToolSpec("clean_url", "URL", "Clean URL"),
        ToolSpec("extract", "LNK", "Extract Links"),
        ToolSpec("clean_text", "TDY", "Clean Text"),
        ToolSpec("sort", "A↓", "Sort Lines"),
        ToolSpec("case", "Aa", "Cycle Case"),
        ToolSpec("speak", "TTS", "Speak Aloud"),
        ToolSpec("log", "LOG", "Append Note")
    )

    val webSearchEngines = listOf(
        ToolSpec("web_google", "Google", "Google", customTarget = "https://www.google.com/search?q={text}"),
        ToolSpec("web_ddg", "DDG", "DuckDuckGo", customTarget = "https://duckduckgo.com/?q={text}"),
        ToolSpec("web_bing", "Bing", "Bing", customTarget = "https://www.bing.com/search?q={text}"),
        ToolSpec("web_brave", "Brave", "Brave Search", customTarget = "https://search.brave.com/search?q={text}")
    )

    val youtubeFilters = listOf(
        ToolSpec("yt_all", "ALL", "YouTube (All)", customTarget = "https://www.youtube.com/results?search_query={text}"),
        ToolSpec("yt_hour", "1H", "Last Hour", customTarget = "https://www.youtube.com/results?search_query={text}&sp=EgIIAQ%3D%3D"),
        ToolSpec("yt_today", "24H", "Today", customTarget = "https://www.youtube.com/results?search_query={text}&sp=EgIIAg%3D%3D"),
        ToolSpec("yt_week", "7D", "This Week", customTarget = "https://www.youtube.com/results?search_query={text}&sp=EgIIAw%3D%3D"),
        ToolSpec("yt_month", "30D", "This Month", customTarget = "https://www.youtube.com/results?search_query={text}&sp=EgIIBA%3D%3D"),
        ToolSpec("yt_year", "1Y", "This Year", customTarget = "https://www.youtube.com/results?search_query={text}&sp=EgIIBQ%3D%3D")
    )

    val markdownTools = listOf(
        ToolSpec("md_bold", "**", "Bold (**text**)"),
        ToolSpec("md_italic", "*", "Italic (*text*)"),
        ToolSpec("md_code", "`", "Code (` / ```)"),
        ToolSpec("md_quote", ">", "Quote (>)"),
        ToolSpec("md_link", "[]", "Link ([text](url))"),
        ToolSpec("md_strike", "~~", "Strikethrough (~~)"),
        ToolSpec("md_bullet", "-", "Bullet List (-)"),
        ToolSpec("md_task", "[ ]", "Task List (- [ ])"),
        ToolSpec("md_h2", "##", "Heading (##)"),
        ToolSpec("md_spoiler", "||", "Spoiler (||)"),
        ToolSpec("md_table", "TBL", "Table (2×2)")
    )

    val pipeTools = listOf(
        ToolSpec("pipe_json_pretty", "{+}", "JSON Pretty"),
        ToolSpec("pipe_json_min", "{-}", "JSON Minify"),
        ToolSpec("pipe_url_enc", "%E", "URL Encode"),
        ToolSpec("pipe_url_dec", "%D", "URL Decode"),
        ToolSpec("pipe_b64_enc", "B64", "Base64 Encode"),
        ToolSpec("pipe_sha256", "SHA", "SHA-256 Hash"),
        ToolSpec("pipe_rot13", "R13", "ROT13 Cipher"),
        ToolSpec("pipe_num_lines", "1.", "Number Lines"),
        ToolSpec("pipe_strip_html", "</>", "Strip HTML Tags")
    )

    val quickSearchPresets = listOf(
        SearchPreset("+Reddit", "RDT", "SEARCH/REDDIT", "https://www.reddit.com/search/?q={text}"),
        SearchPreset("+Maps", "MAP", "SEARCH/MAPS", "https://www.google.com/maps/search/?api=1&query={text}"),
        SearchPreset("+GitHub", "GH", "SEARCH/GITHUB", "https://github.com/search?q={text}"),
        SearchPreset("+Perplexity", "PPLX", "SEARCH/PERPLEXITY", "https://www.perplexity.ai/search?q={text}"),
        SearchPreset("+ChatGPT", "GPT", "SEARCH/CHATGPT", "https://chatgpt.com/?q={text}")
    )
}
