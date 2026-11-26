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
 * Pure PopClip context engine with user-configurable popup bar slot ordering.
 *
 * Special bar order slot IDs:
 * - [SLOT_CONTEXT]: expands into whichever smart context actions match the selection
 * - [SLOT_DEFAULT_SEARCH]: user's customizable default search engine
 * - [SLOT_YOUTUBE]: direct YouTube search (long-press for time filters)
 * - Any pinned tool ID (built-in or custom search portal)
 */
object ContextActionResolver {

    const val SLOT_CONTEXT = "context_slot"
    const val SLOT_DEFAULT_SEARCH = "default_search"
    const val SLOT_YOUTUBE = "youtube_direct"

    val defaultBarOrder = listOf(SLOT_CONTEXT, SLOT_DEFAULT_SEARCH, SLOT_YOUTUBE)

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

        val mathAnswer = if (trimmed.length <= 64) TextTransforms.evalSimpleMath(trimmed) else null
        val unitAnswer = if (trimmed.length <= 28) TextTransforms.evalUnitConversion(trimmed) else null
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
        defaultSearchUrl: String? = "https://www.google.com/search?q={text}",
        barOrder: List<String> = defaultBarOrder
    ): List<PopAction> {
        fun isAllowed(toolId: String): Boolean = toolId !in hiddenToolIds

        val contextActions = buildContextActions(ctx, ::isAllowed)
        val searchAction = PopAction(SLOT_DEFAULT_SEARCH, searchLabel, accent = contextActions.isEmpty(), faviconUrl = defaultSearchUrl)
        val youtubeAction = PopAction(SLOT_YOUTUBE, "YT", faviconUrl = "https://www.youtube.com")
        val pinnedById = pinnedTools.filter { isAllowed(it.id) }.associateBy { it.id }

        // Build effective slot sequence: user's saved order + any missing core/pinned slots appended cleanly
        val effectiveOrder = LinkedHashSet<String>()
        barOrder.forEach { if (it.isNotBlank()) effectiveOrder.add(it) }
        effectiveOrder.add(SLOT_CONTEXT)
        effectiveOrder.add(SLOT_DEFAULT_SEARCH)
        effectiveOrder.add(SLOT_YOUTUBE)
        pinnedTools.forEach { if (isAllowed(it.id)) effectiveOrder.add(it.id) }

        val result = ArrayList<PopAction>(10)
        fun addUnique(action: PopAction) {
            if (result.none { it.id == action.id }) result.add(action)
        }

        // Undo always takes priority first when actively available in an editable field
        if (ctx.hasUndo && ctx.isEditable) {
            addUnique(PopAction("undo", "Undo", accent = true))
        }

        for (slot in effectiveOrder) {
            when (slot) {
                SLOT_CONTEXT -> contextActions.forEach(::addUnique)
                SLOT_DEFAULT_SEARCH -> addUnique(searchAction)
                SLOT_YOUTUBE -> addUnique(youtubeAction)
                else -> {
                    val pinned = pinnedById[slot] ?: continue
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
            }
        }

        return result
    }

    private fun buildContextActions(
        ctx: SelectionContext,
        isAllowed: (String) -> Boolean
    ): List<PopAction> {
        val list = ArrayList<PopAction>(5)
        when {
            ctx.nepalPhone != null -> {
                list.add(PopAction("phone_call", "Call", accent = true, payload = ctx.nepalPhone.dialNumber))
                list.add(PopAction("phone_sms", "SMS", payload = ctx.nepalPhone.dialNumber))
                list.add(PopAction("phone_wa", "WA", accent = true, payload = ctx.nepalPhone.waDigits))
                list.add(PopAction("phone_w4b", "W4B", payload = ctx.nepalPhone.waDigits))
            }
            ctx.genericPhone != null -> {
                list.add(PopAction("phone_call", "Call", accent = true, payload = ctx.genericPhone))
                list.add(PopAction("phone_sms", "SMS", payload = ctx.genericPhone))
            }
            ctx.singleUrl != null -> {
                list.add(PopAction("open_url", "Open", accent = true, payload = ctx.singleUrl))
                if (ctx.isDirtyUrl && isAllowed("clean_url")) {
                    list.add(PopAction("clean_url", "Clean", accent = true))
                }
                if (isAllowed("qr")) list.add(PopAction("qr", "QR"))
            }
            ctx.singleEmail != null -> {
                list.add(PopAction("email_send", "Email", accent = true, payload = ctx.singleEmail))
            }
            ctx.torrentHash != null || ctx.isMagnetUri -> {
                val magnetUri = ctx.torrentHash?.let { TextTransforms.buildMagnetUri(it) } ?: ctx.trimmed
                if (isAllowed("magnet")) list.add(PopAction("magnet", "Magnet", accent = true))
                list.add(PopAction("open_magnet", "Open", payload = magnetUri))
            }
            ctx.mathAnswer != null -> {
                list.add(PopAction("apply_computed", "= ${ctx.mathAnswer}", accent = true, payload = ctx.mathAnswer))
            }
            ctx.unitAnswer != null -> {
                list.add(PopAction("apply_computed", ctx.unitAnswer, accent = true, payload = ctx.unitAnswer.removePrefix("= ").trim()))
            }
            ctx.timestampAnswer != null -> {
                list.add(PopAction("apply_computed", ctx.timestampAnswer.take(16), accent = true, payload = ctx.timestampAnswer))
            }
            ctx.base64Answer != null -> {
                list.add(PopAction("apply_computed", "→ ${ctx.base64Answer.take(12)}", accent = true, payload = ctx.base64Answer))
            }
            ctx.isJson -> {
                list.add(PopAction("pipe_json_pretty", "Format JSON", accent = true))
            }
            ctx.isHtml -> {
                list.add(PopAction("pipe_strip_html", "Strip HTML", accent = true))
            }
            ctx.wordCount in 1..3 && ctx.trimmed.any { it.isLetter() } && ctx.trimmed.length <= 40 -> {
                if (isAllowed("define")) list.add(PopAction("define", "Define", accent = true))
                if (isAllowed("translate")) list.add(PopAction("translate", "Translate"))
            }
            else -> {
                if (ctx.hasExtractableEntities && isAllowed("extract")) {
                    list.add(PopAction("extract", "Links", accent = true))
                }
                if (isAllowed("translate")) list.add(PopAction("translate", "Translate", accent = true))
                if (ctx.isMultiLine && isAllowed("sort")) {
                    list.add(PopAction("sort", "Sort"))
                } else if (ctx.hasMessyWhitespace && isAllowed("clean_text")) {
                    list.add(PopAction("clean_text", "Clean"))
                }
            }
        }
        return list
    }
}
