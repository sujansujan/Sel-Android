package io.github.selclip

/**
 * A single crisp action displayed on the PopClip-style ASCII bar.
 */
data class PopAction(
    val id: String,
    val label: String,
    val accent: Boolean = false,
    val payload: String? = null
)

/**
 * Rich semantic classification of the currently selected text.
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
 * Pure, deterministic PopClip-style context engine:
 * Analyzes selected text and picks ONLY the 3-5 most relevant actions for the primary
 * horizontal ASCII bar (never dumping 20 irrelevant buttons on every selection).
 */
object PopupContextEngine {

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

    /**
     * Returns the exact compact list of primary actions for the horizontal PopClip ASCII bar.
     * Caller appends the `[+]` expander at the right end.
     */
    fun resolveBarActions(
        ctx: SelectionContext,
        hiddenToolIds: Set<String> = emptySet(),
        maxItems: Int = 5
    ): List<PopAction> {
        val actions = ArrayList<PopAction>(maxItems + 1)
        fun isAllowed(toolId: String): Boolean = toolId !in hiddenToolIds
        fun addIfRoom(action: PopAction) {
            if (actions.size < maxItems && actions.none { it.id == action.id }) {
                actions.add(action)
            }
        }

        if (ctx.hasUndo && ctx.isEditable) {
            addIfRoom(PopAction("undo", "UNDO", accent = true))
        }

        // 1. Nepal Phone -> Call / SMS / WhatsApp / W4B / Copy
        if (ctx.nepalPhone != null) {
            addIfRoom(PopAction("phone_call", "CALL", accent = true, payload = ctx.nepalPhone.dialNumber))
            addIfRoom(PopAction("phone_sms", "SMS", payload = ctx.nepalPhone.dialNumber))
            addIfRoom(PopAction("phone_wa", "WA", accent = true, payload = ctx.nepalPhone.waDigits))
            addIfRoom(PopAction("phone_w4b", "W4B", payload = ctx.nepalPhone.waDigits))
            addIfRoom(PopAction("copy", "^C"))
            return actions
        }

        // 2. Generic International Phone -> Call / SMS / Copy
        if (ctx.genericPhone != null) {
            addIfRoom(PopAction("phone_call", "CALL", accent = true, payload = ctx.genericPhone))
            addIfRoom(PopAction("phone_sms", "SMS", payload = ctx.genericPhone))
            addIfRoom(PopAction("copy", "^C"))
            if (ctx.isEditable) {
                addIfRoom(PopAction("cut", "^X"))
                addIfRoom(PopAction("paste", "^V"))
            }
            return actions
        }

        // 3. URL (Dirty -> Open + Clean + Copy + QR; Clean -> Open + Copy + QR)
        if (ctx.singleUrl != null) {
            addIfRoom(PopAction("open_url", "OPEN", accent = true, payload = ctx.singleUrl))
            if (ctx.isDirtyUrl && isAllowed("clean_url")) {
                addIfRoom(PopAction("clean_url", "CLEAN", accent = true))
            }
            addIfRoom(PopAction("copy", "^C"))
            if (isAllowed("qr")) addIfRoom(PopAction("qr", "QR"))
            if (ctx.isEditable) addIfRoom(PopAction("cut", "^X"))
            return actions
        }

        // 4. Email Address -> Mail / Copy / Web
        if (ctx.singleEmail != null) {
            addIfRoom(PopAction("email_send", "MAIL", accent = true, payload = ctx.singleEmail))
            addIfRoom(PopAction("copy", "^C"))
            if (ctx.isEditable) {
                addIfRoom(PopAction("cut", "^X"))
                addIfRoom(PopAction("paste", "^V"))
            } else if (isAllowed("web_search_folder")) {
                addIfRoom(PopAction("web_search_folder", "WEB"))
            }
            return actions
        }

        // 5. Torrent Hash / Magnet -> Magnet / Open / Copy
        if (ctx.torrentHash != null || ctx.isMagnetUri) {
            val magnetUri = when {
                ctx.torrentHash != null -> TextTransforms.buildMagnetUri(ctx.torrentHash)
                else -> ctx.trimmed
            }
            if (isAllowed("magnet")) {
                addIfRoom(PopAction("magnet", "MAGNET", accent = true))
            }
            addIfRoom(PopAction("open_magnet", "OPEN", payload = magnetUri))
            addIfRoom(PopAction("copy", "^C"))
            return actions
        }

        // 6. Instant Computed Evaluators (Math / Unit / Timestamp / Base64)
        val computed = when {
            ctx.mathAnswer != null -> PopAction("apply_computed", "= ${ctx.mathAnswer}", accent = true, payload = ctx.mathAnswer)
            ctx.unitAnswer != null -> PopAction("apply_computed", ctx.unitAnswer, accent = true, payload = ctx.unitAnswer.removePrefix("= ").trim())
            ctx.timestampAnswer != null -> PopAction("apply_computed", ctx.timestampAnswer.take(16), accent = true, payload = ctx.timestampAnswer)
            ctx.base64Answer != null -> PopAction("apply_computed", "-> ${ctx.base64Answer.take(12)}", accent = true, payload = ctx.base64Answer)
            else -> null
        }
        if (computed != null) {
            addIfRoom(computed)
            addIfRoom(PopAction("copy", "^C"))
            if (ctx.isEditable) {
                addIfRoom(PopAction("cut", "^X"))
                addIfRoom(PopAction("paste", "^V"))
            } else if (isAllowed("web_search_folder")) {
                addIfRoom(PopAction("web_search_folder", "WEB"))
            }
            return actions
        }

        // 7. JSON or HTML Code
        if (ctx.isJson || ctx.isHtml) {
            addIfRoom(PopAction("copy", "^C", accent = true))
            if (ctx.isJson) {
                addIfRoom(PopAction("pipe_json_pretty", "JSON", accent = true))
            } else {
                addIfRoom(PopAction("pipe_strip_html", "STRIP", accent = true))
            }
            if (ctx.isEditable) {
                addIfRoom(PopAction("cut", "^X"))
                addIfRoom(PopAction("paste", "^V"))
            } else if (isAllowed("regex_pipe")) {
                addIfRoom(PopAction("regex_pipe", "PIPE"))
            }
            return actions
        }

        // 8. Standard Text (Smartly distinguishes Editable vs Read-Only, and Short Word vs Paragraph)
        addIfRoom(PopAction("copy", "^C", accent = true))

        if (ctx.isEditable) {
            addIfRoom(PopAction("cut", "^X"))
            addIfRoom(PopAction("paste", "^V"))
            if (ctx.wordCount in 1..3 && ctx.trimmed.any { it.isLetter() } && isAllowed("define")) {
                addIfRoom(PopAction("define", "DEF"))
            } else if (ctx.hasMessyWhitespace && isAllowed("clean_text")) {
                addIfRoom(PopAction("clean_text", "CLEAN"))
            } else if (ctx.isMultiLine && isAllowed("sort")) {
                addIfRoom(PopAction("sort", "SORT"))
            } else if (isAllowed("case")) {
                addIfRoom(PopAction("case", "Aa"))
            }
            if (isAllowed("translate")) {
                addIfRoom(PopAction("translate", "TR"))
            }
            return actions
        }

        // Read-only text (Web pages, articles, chat messages, PDFs, etc.)
        if (ctx.wordCount in 1..3 && ctx.trimmed.any { it.isLetter() } && ctx.trimmed.length <= 40) {
            if (isAllowed("define")) addIfRoom(PopAction("define", "DEF"))
            if (isAllowed("translate")) addIfRoom(PopAction("translate", "TR"))
            if (isAllowed("web_search_folder")) addIfRoom(PopAction("web_search_folder", "WEB"))
            if (isAllowed("youtube_folder")) addIfRoom(PopAction("youtube_folder", "YT"))
        } else {
            if (ctx.hasExtractableEntities && isAllowed("extract")) {
                addIfRoom(PopAction("extract", "LINKS", accent = true))
            }
            if (isAllowed("translate")) addIfRoom(PopAction("translate", "TR"))
            if (isAllowed("web_search_folder")) addIfRoom(PopAction("web_search_folder", "WEB"))
            if (ctx.isMultiLine && isAllowed("sort")) {
                addIfRoom(PopAction("sort", "SORT"))
            } else if (ctx.hasMessyWhitespace && isAllowed("clean_text")) {
                addIfRoom(PopAction("clean_text", "CLEAN"))
            } else if (isAllowed("speak")) {
                addIfRoom(PopAction("speak", "SPEAK"))
            }
            if (isAllowed("log")) addIfRoom(PopAction("log", "LOG"))
        }

        return actions
    }
}
