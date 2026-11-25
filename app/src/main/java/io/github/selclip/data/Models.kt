package io.github.selclip.data

import android.content.Context
import android.graphics.Color
import android.os.Build

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
    val customTool: ToolSpec? = null,
    val faviconUrl: String? = null
)

data class CustomRuleEntry(
    val lineIndex: Int,
    val glyph: String,
    val rawLabel: String,
    val target: String
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
 * Native Android Material-style theme palette (with Android 12+ Material You system colors in Auto mode).
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
    val dangerInt: Int,
    val onAccentInt: Int
)

object AppPalettes {

    // Matches native Android 13-15 dark FloatingToolbarPopup surface & ink
    val DARK = ThemePalette(
        name = "Dark",
        isDark = true,
        bgPrimaryInt = Color.parseColor("#282A2E"),
        bgHeaderInt = Color.parseColor("#282A2E"),
        bgSurfaceInt = Color.parseColor("#1F2124"),
        bgInputInt = Color.parseColor("#17191C"),
        bgPressedInt = Color.parseColor("#3C4043"),
        borderInt = Color.parseColor("#3F4247"),
        dividerInt = Color.parseColor("#3C4043"),
        fgPrimaryInt = Color.parseColor("#E8EAED"),
        fgSecondaryInt = Color.parseColor("#A8C7FA"),
        fgMutedInt = Color.parseColor("#9AA0A6"),
        accentPrimaryInt = Color.parseColor("#A8C7FA"),
        accentSuccessInt = Color.parseColor("#81C995"),
        accentInfoInt = Color.parseColor("#7FCFFF"),
        dangerInt = Color.parseColor("#F28B82"),
        onAccentInt = Color.parseColor("#1F1F1F")
    )

    // Matches native Android 13-15 light FloatingToolbarPopup surface & ink
    val LIGHT = ThemePalette(
        name = "Light",
        isDark = false,
        bgPrimaryInt = Color.parseColor("#FFFFFF"),
        bgHeaderInt = Color.parseColor("#FFFFFF"),
        bgSurfaceInt = Color.parseColor("#F6F7F9"),
        bgInputInt = Color.parseColor("#EFF1F5"),
        bgPressedInt = Color.parseColor("#E8EAED"),
        borderInt = Color.parseColor("#DADCE0"),
        dividerInt = Color.parseColor("#E0E3E7"),
        fgPrimaryInt = Color.parseColor("#1F1F1F"),
        fgSecondaryInt = Color.parseColor("#1A73E8"),
        fgMutedInt = Color.parseColor("#5F6368"),
        accentPrimaryInt = Color.parseColor("#1A73E8"),
        accentSuccessInt = Color.parseColor("#188038"),
        accentInfoInt = Color.parseColor("#0B57D0"),
        dangerInt = Color.parseColor("#D93025"),
        onAccentInt = Color.parseColor("#FFFFFF")
    )

    /**
     * Resolves Android 12+ Material You dynamic system surface & accent colors so the popup
     * blends seamlessly with the device's native FloatingToolbarPopup.
     */
    fun resolveDynamic(context: Context, dark: Boolean): ThemePalette {
        val base = if (dark) DARK else LIGHT
        if (Build.VERSION.SDK_INT < 31) return base
        return runCatching {
            if (dark) {
                val surface = context.getColor(android.R.color.system_neutral1_800)
                val bg = context.getColor(android.R.color.system_neutral1_900)
                val pressed = context.getColor(android.R.color.system_neutral1_700)
                val accent = context.getColor(android.R.color.system_accent1_200)
                val onAcc = context.getColor(android.R.color.system_accent1_900)
                base.copy(
                    bgPrimaryInt = surface,
                    bgHeaderInt = surface,
                    bgSurfaceInt = bg,
                    bgPressedInt = pressed,
                    borderInt = pressed,
                    dividerInt = pressed,
                    fgSecondaryInt = accent,
                    accentPrimaryInt = accent,
                    onAccentInt = onAcc
                )
            } else {
                val surface = context.getColor(android.R.color.system_neutral1_10)
                val bg = context.getColor(android.R.color.system_neutral1_50)
                val pressed = context.getColor(android.R.color.system_neutral1_100)
                val border = context.getColor(android.R.color.system_neutral1_200)
                val accent = context.getColor(android.R.color.system_accent1_600)
                base.copy(
                    bgPrimaryInt = surface,
                    bgHeaderInt = surface,
                    bgSurfaceInt = bg,
                    bgPressedInt = pressed,
                    borderInt = border,
                    dividerInt = border,
                    fgSecondaryInt = accent,
                    accentPrimaryInt = accent
                )
            }
        }.getOrDefault(base)
    }
}

object BuiltInRegistry {

    // Clipboard History and Scratchpad Notes completely removed per user request.
    val builtInTools = listOf(
        ToolSpec("web_search_folder", "WEB", "Web Search"),
        ToolSpec("youtube_folder", "YT", "YouTube"),
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
        ToolSpec("speak", "TTS", "Speak Aloud")
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
