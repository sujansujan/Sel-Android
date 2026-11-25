package io.github.selclip.data

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import android.view.ContextThemeWrapper

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

data class TranslateLanguage(
    val code: String,
    val shortLabel: String,
    val displayName: String
)

enum class ThemeMode {
    AUTO,
    DARK,
    LIGHT
}

/**
 * Native Android Material theme palette matching the device's system FloatingToolbarPopup colors.
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
    val popupSurfaceInt: Int,
    val popupBorderInt: Int,
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

    val DARK = ThemePalette(
        name = "Dark",
        isDark = true,
        bgPrimaryInt = Color.parseColor("#1C1D20"),
        bgHeaderInt = Color.parseColor("#1C1D20"),
        bgSurfaceInt = Color.parseColor("#25272B"),
        bgInputInt = Color.parseColor("#17181B"),
        bgPressedInt = Color.parseColor("#3B3E44"),
        borderInt = Color.parseColor("#383B42"),
        popupSurfaceInt = Color.parseColor("#2B2D31"),
        popupBorderInt = Color.argb(32, 255, 255, 255),
        dividerInt = Color.argb(46, 255, 255, 255),
        fgPrimaryInt = Color.parseColor("#E8EAED"),
        fgSecondaryInt = Color.parseColor("#A8C7FA"),
        fgMutedInt = Color.parseColor("#9AA0A6"),
        accentPrimaryInt = Color.parseColor("#A8C7FA"),
        accentSuccessInt = Color.parseColor("#81C995"),
        accentInfoInt = Color.parseColor("#7FCFFF"),
        dangerInt = Color.parseColor("#F28B82"),
        onAccentInt = Color.parseColor("#1F1F1F")
    )

    val LIGHT = ThemePalette(
        name = "Light",
        isDark = false,
        bgPrimaryInt = Color.parseColor("#F7F8FA"),
        bgHeaderInt = Color.parseColor("#F7F8FA"),
        bgSurfaceInt = Color.parseColor("#FFFFFF"),
        bgInputInt = Color.parseColor("#EFF1F5"),
        bgPressedInt = Color.parseColor("#E4E7EC"),
        borderInt = Color.parseColor("#DADCE0"),
        popupSurfaceInt = Color.parseColor("#FFFFFF"),
        popupBorderInt = Color.argb(26, 0, 0, 0),
        dividerInt = Color.argb(28, 0, 0, 0),
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
     * Reads the device's real Android floating popup surface, text, and Material You accent colors
     * so Sel's floating pill blends seamlessly with Pixel / OneUI / MIUI / AOSP native popups.
     */
    fun resolveDynamic(context: Context, dark: Boolean): ThemePalette {
        val base = if (dark) DARK else LIGHT
        return runCatching {
            val themeRes = if (dark) android.R.style.Theme_DeviceDefault else android.R.style.Theme_DeviceDefault_Light
            val themed = ContextThemeWrapper(context, themeRes)
            val floatingBg = resolveAttrColor(themed, android.R.attr.colorBackgroundFloating)
            val primaryTxt = resolveAttrColor(themed, android.R.attr.textColorPrimary)
            val accentAttr = resolveAttrColor(themed, android.R.attr.colorAccent)

            if (Build.VERSION.SDK_INT >= 31) {
                if (dark) {
                    // Android 12-15 FloatingToolbarPopup uses Material You neutral1_800 (or DeviceDefault colorBackgroundFloating)
                    val sysSurface = floatingBg ?: context.getColor(android.R.color.system_neutral1_800)
                    val sysBg = context.getColor(android.R.color.system_neutral1_900)
                    val sysPressed = context.getColor(android.R.color.system_neutral1_700)
                    val sysFg = primaryTxt ?: context.getColor(android.R.color.system_neutral1_100)
                    val sysAccent = context.getColor(android.R.color.system_accent1_200)
                    val onAcc = context.getColor(android.R.color.system_accent1_900)
                    base.copy(
                        bgPrimaryInt = sysBg,
                        bgHeaderInt = sysBg,
                        bgSurfaceInt = sysSurface,
                        bgPressedInt = sysPressed,
                        borderInt = sysPressed,
                        popupSurfaceInt = sysSurface,
                        popupBorderInt = Color.argb(30, 255, 255, 255),
                        dividerInt = Color.argb(44, 255, 255, 255),
                        fgPrimaryInt = sysFg,
                        fgSecondaryInt = sysAccent,
                        accentPrimaryInt = sysAccent,
                        onAccentInt = onAcc
                    )
                } else {
                    val sysSurface = floatingBg ?: context.getColor(android.R.color.system_neutral1_10)
                    val sysBg = context.getColor(android.R.color.system_neutral1_50)
                    val sysPressed = context.getColor(android.R.color.system_neutral1_100)
                    val sysBorder = context.getColor(android.R.color.system_neutral1_200)
                    val sysFg = primaryTxt ?: context.getColor(android.R.color.system_neutral1_900)
                    val sysAccent = accentAttr ?: context.getColor(android.R.color.system_accent1_600)
                    base.copy(
                        bgPrimaryInt = sysBg,
                        bgHeaderInt = sysBg,
                        bgSurfaceInt = sysSurface,
                        bgPressedInt = sysPressed,
                        borderInt = sysBorder,
                        popupSurfaceInt = sysSurface,
                        popupBorderInt = Color.argb(24, 0, 0, 0),
                        dividerInt = Color.argb(26, 0, 0, 0),
                        fgPrimaryInt = sysFg,
                        fgSecondaryInt = sysAccent,
                        accentPrimaryInt = sysAccent
                    )
                }
            } else {
                base.copy(
                    popupSurfaceInt = floatingBg ?: base.popupSurfaceInt,
                    fgPrimaryInt = primaryTxt ?: base.fgPrimaryInt,
                    accentPrimaryInt = accentAttr ?: base.accentPrimaryInt,
                    fgSecondaryInt = accentAttr ?: base.fgSecondaryInt
                )
            }
        }.getOrDefault(base)
    }

    private fun resolveAttrColor(context: Context, attr: Int): Int? {
        val tv = TypedValue()
        if (!context.theme.resolveAttribute(attr, tv, true)) return null
        return when {
            tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> tv.data
            tv.resourceId != 0 -> runCatching { context.getColor(tv.resourceId) }.getOrNull()
            else -> null
        }
    }
}

object BuiltInRegistry {

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

    val translateLanguages = listOf(
        TranslateLanguage("en", "EN", "English"),
        TranslateLanguage("ne", "NE", "Nepali"),
        TranslateLanguage("hi", "HI", "Hindi"),
        TranslateLanguage("es", "ES", "Spanish"),
        TranslateLanguage("fr", "FR", "French"),
        TranslateLanguage("de", "DE", "German"),
        TranslateLanguage("ja", "JA", "Japanese"),
        TranslateLanguage("zh-CN", "ZH", "Chinese"),
        TranslateLanguage("ko", "KO", "Korean"),
        TranslateLanguage("ar", "AR", "Arabic")
    )
}
