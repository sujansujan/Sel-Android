package io.github.selclip.core

import io.github.selclip.data.PopAction
import io.github.selclip.data.ToolSpec

/**
 * Semantic classification of the currently selected text.
 */
data class SelectionContext(
    val rawText: String,
    val trimmed: String,
    val wordCount: Int,
    val isEditable: Boolean,
    val hasUndo: Boolean,
    val nepalPhone: NepalPhone?,
    val genericPhone: String?,
    val singleUrl: String?,
    val isDirtyUrl: Boolean,
    val singleEmail: String?,
    val torrentHash: String?,
    val isMagnetUri: Boolean,
    val mathAnswer: String?,
    val unitAnswer: String?,
    val timestampAnswer: String?,
    val base64Answer: String?,
    val isJson: Boolean,
    val isHtml: Boolean,
    val isMultiLine: Boolean,
    val hasMessyWhitespace: Boolean,
    val hasExtractableEntities: Boolean
)

/**
 * Pure PopClip context engine:
 * - Excludes Copy/Cut/Paste/Select All (redundant with Android's native toolbar).
 * - Injects only context-relevant smart actions first.
 * - Always appends customizable Default Search + YouTube + user-pinned actions.
 */
object ContextActionResolver {

    fun analyze(
        rawText: String,
        isEditable: Boolean = false,
        hasUndo: Boolean = false
    ): SelectionContext {
        val trimmed = rawText.trim()
        val wordCount = TextTransforms.countWords(trimmed)
        val isSingleToken = !trimmed.contains(' ') && !trimmed.contains('\n') && trimmed.isNotEmpty()

        val singleUrl = if (isSingleToken &&
            (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                TextTransforms.urlPattern.matches(trimmed))
        ) trimmed else null

        val isDirtyUrl = singleUrl != null &&
            (singleUrl.contains('?') || singleUrl.contains('&') || TextTransforms.trackingRegex.containsMatchIn(singleUrl))

        val singleEmail = if (isSingleToken && TextTransforms.emailFindRegex.matches(trimmed)) trimmed else null

        val nepalPhone = if (trimmed.length <= 32) TextTransforms.extractNepalPhone(trimmed) else null
        val genericPhone = if (nepalPhone == null && trimmed.length <= 24 &&
            TextTransforms.dialPhoneMatchRegex.matches(trimmed)
        ) trimmed else null

        val torrentHash = TextTransforms.extractTorrentHash(trimmed)
        val isMagnetUri = trimmed.startsWith("magnet:?", ignoreCase = true)

        val mathAnswer = if (trimmed.length <= 40) TextTransforms.evalSimpleMath(trimmed) else null
        val unitAnswer = if (trimmed.length <= 24) TextTransforms.evalUnitConversion(trimmed) else null
        val timestampAnswer = if (trimmed.length <= 16) TextTransforms.evalTimestamp(trimmed) else null
        val base64Answer = if (isSingleToken && torrentHash == null) TextTransforms.evalBase64Decode(trimmed) else null

        val isJson = (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
        val isHtml = trimmed.contains('<') && trimmed.contains('>') &&
            TextTransforms.htmlTagRegex.containsMatchIn(trimmed)

        val isMultiLine = trimmed.contains('\n') && trimmed.lines().count { it.isNotBlank() } >= 2
        val hasMessyWhitespace = rawText.contains("  ") || rawText.contains('\t') ||
            TextTransforms.invisibleCharsRegex.containsMatchIn(rawText) ||
            (rawText.contains('\n') && !isMultiLine)

        val hasExtractable = singleUrl == null && singleEmail == null &&
            nepalPhone == null && genericPhone == null &&
            TextTransforms.hasExtractableEntities(trimmed)

        return SelectionContext(
            rawText = rawText,
            trimmed = trimmed,
            wordCount = wordCount,
            isEditable = isEditable,
            hasUndo = hasUndo,
            nepalPhone = nepalPhone,
            genericPhone = genericPhone,
            singleUrl = singleUrl,
            isDirtyUrl = isDirtyUrl,
            singleEmail = singleEmail,
            torrentHash = torrentHash,
            isMagnetUri = isMagnetUri,
            mathAnswer = mathAnswer,
            unitAnswer = unitAnswer,
            timestampAnswer = timestampAnswer,
            base64Answer = base64Answer,
            isJson = isJson,
            isHtml = isHtml,
            isMultiLine = isMultiLine,
            hasMessyWhitespace = hasMessyWhitespace,
            hasExtractableEntities = hasExtractable
        )
    }

    fun shortLabelForPinnedTool(tool: ToolSpec): String = when (tool.id) {
        "define" -> "Define"
        "translate" -> "Translate"
        "qr" -> "QR"
        "markdown_folder" -> "MD"
        "regex_pipe" -> "Pipe"
        "clean_url" -> "Clean"
        "clean_text" -> "Tidy"
        "extract" -> "Links"
        "sort" -> "Sort"
        "case" -> "Aa"
        "speak" -> "Speak"
        "magnet" -> "Magnet"
        else -> tool.label.substringBefore(">").trim().take(8)
    }

    fun resolveBarActions(
        ctx: SelectionContext,
        hiddenToolIds: Set<String> = emptySet(),
        pinnedTools: List<ToolSpec> = emptyList(),
        searchLabel: String = "Search",
        defaultSearchUrl: String? = "https://www.google.com/search?q={text}"
    ): List<PopAction> {
        val actions = ArrayList<PopAction>(10)
        fun isAllowed(toolId: String): Boolean = toolId !in hiddenToolIds
        fun addUnique(action: PopAction) {
            if (actions.none { it.id == action.id }) actions.add(action)
        }

        if (ctx.hasUndo && ctx.isEditable) {
            addUnique(PopAction("undo", "Undo", accent = true))
        }

        when {
            ctx.nepalPhone != null -> {
                addUnique(PopAction("phone_call", "Call", accent = true, payload = ctx.nepalPhone.dialNumber))
                addUnique(PopAction("phone_sms", "SMS", payload = ctx.nepalPhone.dialNumber))
                addUnique(PopAction("phone_wa", "WA", accent = true, payload = ctx.nepalPhone.waDigits))
                addUnique(PopAction("phone_w4b", "W4B", payload = ctx.nepalPhone.waDigits))
            }
            ctx.genericPhone != null -> {
                addUnique(PopAction("phone_call", "Call", accent = true, payload = ctx.genericPhone))
                addUnique(PopAction("phone_sms", "SMS", payload = ctx.genericPhone))
            }
            ctx.singleUrl != null -> {
                addUnique(PopAction("open_url", "Open", accent = true, payload = ctx.singleUrl))
                if (ctx.isDirtyUrl && isAllowed("clean_url")) {
                    addUnique(PopAction("clean_url", "Clean", accent = true))
                }
                if (isAllowed("qr")) addUnique(PopAction("qr", "QR"))
            }
            ctx.singleEmail != null -> {
                addUnique(PopAction("email_send", "Email", accent = true, payload = ctx.singleEmail))
            }
            ctx.torrentHash != null || ctx.isMagnetUri -> {
                val magnetUri = ctx.torrentHash?.let { TextTransforms.buildMagnetUri(it) } ?: ctx.trimmed
                if (isAllowed("magnet")) addUnique(PopAction("magnet", "Magnet", accent = true))
                addUnique(PopAction("open_magnet", "Open", payload = magnetUri))
            }
            ctx.mathAnswer != null -> {
                addUnique(PopAction("apply_computed", "= ${ctx.mathAnswer}", accent = true, payload = ctx.mathAnswer))
            }
            ctx.unitAnswer != null -> {
                addUnique(PopAction("apply_computed", ctx.unitAnswer, accent = true, payload = ctx.unitAnswer.removePrefix("= ").trim()))
            }
            ctx.timestampAnswer != null -> {
                addUnique(PopAction("apply_computed", ctx.timestampAnswer.take(16), accent = true, payload = ctx.timestampAnswer))
            }
            ctx.base64Answer != null -> {
                addUnique(PopAction("apply_computed", "→ ${ctx.base64Answer.take(12)}", accent = true, payload = ctx.base64Answer))
            }
            ctx.isJson -> {
                addUnique(PopAction("pipe_json_pretty", "Format JSON", accent = true))
            }
            ctx.isHtml -> {
                addUnique(PopAction("pipe_strip_html", "Strip HTML", accent = true))
            }
            ctx.wordCount in 1..3 && ctx.trimmed.any { it.isLetter() } && ctx.trimmed.length <= 40 -> {
                if (isAllowed("define")) addUnique(PopAction("define", "Define", accent = true))
                if (isAllowed("translate")) addUnique(PopAction("translate", "Translate"))
            }
            else -> {
                if (ctx.hasExtractableEntities && isAllowed("extract")) {
                    addUnique(PopAction("extract", "Links", accent = true))
                }
                if (isAllowed("translate")) addUnique(PopAction("translate", "Translate", accent = true))
                if (ctx.isMultiLine && isAllowed("sort")) {
                    addUnique(PopAction("sort", "Sort"))
                } else if (ctx.hasMessyWhitespace && isAllowed("clean_text")) {
                    addUnique(PopAction("clean_text", "Clean"))
                }
            }
        }

        // Always-visible customizable Search + YouTube (tap = instant search, long-press = all options)
        addUnique(PopAction("default_search", searchLabel, accent = actions.isEmpty(), faviconUrl = defaultSearchUrl))
        addUnique(PopAction("youtube_direct", "YT", faviconUrl = "https://www.youtube.com"))

        // Always-visible user-pinned tools
        for (pinned in pinnedTools) {
            if (!isAllowed(pinned.id)) continue
            addUnique(
                PopAction(
                    id = pinned.id,
                    label = shortLabelForPinnedTool(pinned),
                    accent = false,
                    customTool = pinned,
                    faviconUrl = pinned.customTarget?.takeIf {
                        it.startsWith("http://", true) || it.startsWith("https://", true)
                    }
                )
            )
        }

        return actions
    }
}
