package io.github.selclip.data

import android.content.Context
import java.io.File

/**
 * Cached parser & editor for `rules.txt` (`ICON | LABEL | TARGET`).
 * Supports SEARCH/, YT/, MD/, PIPE/, REGEX/, custom FOLDER/ITEM, and top-level custom tools.
 */
object RulesRepository {

    private val reservedPrefixes = setOf("SEARCH", "WEB", "YT", "YOUTUBE", "MD", "MARKDOWN", "PIPE", "REGEX")

    data class ParsedSnapshot(
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
    private var cached: ParsedSnapshot? = null

    fun invalidateCache() {
        cached = null
        PrefsStore.invalidateToolCaches()
    }

    fun rulesFile(context: Context): File =
        File(context.applicationContext.filesDir, "rules.txt")

    fun ensureRulesFile(context: Context): File {
        val file = rulesFile(context)
        if (!file.exists()) {
            file.writeText(defaultRulesText())
            cached = null
        }
        return file
    }

    fun defaultRulesText(): String = """
        # Format: CODE | LABEL | TARGET ({text} = selection)
        # Prefix with SEARCH/, YT/, MD/, PIPE/, or CUSTOM_FOLDER/
        W | SEARCH/WIKIPEDIA | https://en.wikipedia.org/wiki/Special:Search?search={text}
    """.trimIndent() + "\n"

    fun sanitizeFolderName(raw: String): String =
        raw.uppercase().replace("/", "").replace("|", "").replace(">", "").trim().take(14)

    fun inferShortCode(rawGlyph: String, rawLabel: String, target: String? = null): String {
        val cleanLabel = rawLabel.substringAfter("/").trim().uppercase()
        val lowerTarget = target?.lowercase().orEmpty()
        if (cleanLabel.contains("WIKIPEDIA") || lowerTarget.contains("wikipedia.org")) return "Wiki"
        val stripped = rawGlyph.trim().removePrefix("[").removeSuffix("]").trim()
        if (stripped.isNotEmpty()) return stripped.take(6)

        return when {
            lowerTarget.contains("youtube.") || lowerTarget.contains("youtu.be") -> "YT"
            lowerTarget.contains("google.") -> "Google"
            lowerTarget.contains("duckduckgo.") -> "DDG"
            lowerTarget.contains("bing.") -> "Bing"
            lowerTarget.contains("brave.") -> "Brave"
            lowerTarget.contains("github.") -> "GitHub"
            lowerTarget.contains("reddit.") -> "Reddit"
            lowerTarget.contains("perplexity.") -> "PPLX"
            lowerTarget.contains("chatgpt.") -> "GPT"
            lowerTarget.contains("kagi.") -> "Kagi"
            cleanLabel.isNotEmpty() -> cleanLabel.filter { it.isLetterOrDigit() }.take(5).ifEmpty { "CMD" }
            else -> "CMD"
        }
    }

    fun parseSnapshot(context: Context): ParsedSnapshot {
        // O(1) RAM fast-path: zero filesystem syscalls on hot popup open!
        cached?.let { return it }

        val file = ensureRulesFile(context)
        val mod = file.lastModified()
        val len = file.length()

        val entries = mutableListOf<CustomRuleEntry>()
        val topLevel = mutableListOf<ToolSpec>()
        val webSearch = mutableListOf<ToolSpec>()
        val youtube = mutableListOf<ToolSpec>()
        val markdown = mutableListOf<ToolSpec>()
        val regexPipe = mutableListOf<ToolSpec>()
        val folderTools = mutableMapOf<String, MutableList<ToolSpec>>()
        val ruleFolders = linkedSetOf<String>()

        runCatching {
            file.readLines().forEachIndexed { idx, rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val parts = line.split("|").map { it.trim() }
                if (parts.size < 2) return@forEachIndexed

                val rawGlyph = if (parts.size >= 3) parts[0] else ""
                val rawLabel = if (parts.size >= 3) parts[1].uppercase() else parts[0].uppercase()
                val target = if (parts.size >= 3) parts.subList(2, parts.size).joinToString("|").trim() else parts[1]
                if (rawLabel.isEmpty() || target.isEmpty()) return@forEachIndexed

                val glyph = inferShortCode(rawGlyph, rawLabel, target)
                entries.add(CustomRuleEntry(idx, glyph, rawLabel, target))

                when {
                    rawLabel.startsWith("SEARCH/") || rawLabel.startsWith("WEB/") -> {
                        val name = rawLabel.removePrefix("SEARCH/").removePrefix("WEB/").trim()
                        if (name.isNotEmpty()) {
                            webSearch.add(ToolSpec("custom_web_${idx}_$name", glyph, name, customTarget = target))
                        }
                    }
                    rawLabel.startsWith("YT/") || rawLabel.startsWith("YOUTUBE/") -> {
                        val name = rawLabel.removePrefix("YT/").removePrefix("YOUTUBE/").trim()
                        if (name.isNotEmpty()) {
                            youtube.add(ToolSpec("custom_yt_${idx}_$name", glyph, name, customTarget = target))
                        }
                    }
                    rawLabel.startsWith("MD/") || rawLabel.startsWith("MARKDOWN/") -> {
                        val name = rawLabel.removePrefix("MD/").removePrefix("MARKDOWN/").trim()
                        if (name.isNotEmpty()) {
                            markdown.add(ToolSpec("custom_md_${idx}_$name", glyph, name, customTarget = target))
                        }
                    }
                    rawLabel.startsWith("PIPE/") || rawLabel.startsWith("REGEX/") || target.startsWith("s/") -> {
                        val name = rawLabel.removePrefix("PIPE/").removePrefix("REGEX/").trim()
                        regexPipe.add(ToolSpec("custom_pipe_${idx}_$name", glyph, name, customTarget = target))
                    }
                    rawLabel.contains("/") -> {
                        val folder = sanitizeFolderName(rawLabel.substringBefore("/"))
                        val subLabel = rawLabel.substringAfter("/").trim()
                        if (folder.isNotEmpty() && folder !in reservedPrefixes) {
                            ruleFolders.add(folder)
                            if (subLabel.isNotEmpty()) {
                                folderTools.getOrPut(folder) { mutableListOf() }.add(
                                    ToolSpec("folder_item_${idx}_$subLabel", glyph, subLabel, customTarget = target, folderName = folder)
                                )
                            }
                        }
                    }
                    else -> {
                        topLevel.add(ToolSpec("custom_${idx}_$rawLabel", glyph, rawLabel, customTarget = target))
                    }
                }
            }
        }

        return ParsedSnapshot(
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
        ).also { cached = it }
    }

    fun getEntries(context: Context): List<CustomRuleEntry> = parseSnapshot(context).entries

    fun appendRule(context: Context, glyph: String, rawLabel: String, target: String): Boolean {
        val file = ensureRulesFile(context)
        val cleanLabel = rawLabel.trim().uppercase()
        val cleanTarget = target.trim()
        val cleanGlyph = inferShortCode(glyph, cleanLabel, cleanTarget)
        val newLine = "$cleanGlyph | $cleanLabel | $cleanTarget"

        val existing = runCatching { file.readLines() }.getOrDefault(emptyList())
        if (existing.any { it.trim().equals(newLine, ignoreCase = true) || it.contains(cleanTarget, ignoreCase = true) }) {
            return false
        }
        val text = file.readText()
        val sep = if (text.isNotEmpty() && !text.endsWith("\n")) "\n" else ""
        file.appendText("$sep$newLine\n")
        invalidateCache()
        return true
    }

    fun deleteRuleAtLine(context: Context, lineIndex: Int): Boolean {
        val file = ensureRulesFile(context)
        val lines = runCatching { file.readLines().toMutableList() }.getOrNull() ?: return false
        if (lineIndex !in lines.indices) return false
        lines.removeAt(lineIndex)
        file.writeText(lines.joinToString("\n") + "\n")
        invalidateCache()
        return true
    }

    fun writeRawRules(context: Context, rawText: String) {
        ensureRulesFile(context).writeText(rawText)
        invalidateCache()
    }
}
