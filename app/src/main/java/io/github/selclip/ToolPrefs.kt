package io.github.selclip

import android.content.Context
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

data class ClipEntry(
    val text: String,
    val pinned: Boolean,
    val timestamp: Long
)

object ToolPrefs {
    private const val PREFS_NAME = "sel_tool_prefs"
    private const val KEY_ORDER = "manual_order"
    private const val KEY_SORT_MOST_USED = "sort_most_used"
    private const val KEY_EDGE_LEFT = "snap_edge_left"
    private const val KEY_BLACKLIST = "blacklisted_pkgs"
    private const val KEY_CLIP_HISTORY = "clip_history_json"
    private const val PREFIX_COUNT = "use_count_"
    private const val PREFIX_HIDDEN = "hidden_"
    private const val MAX_CLIPS = 20

    // Top-level tools in the main popup (filtered dynamically by context in SelAccessibilityService)
    val builtInTools = listOf(
        ToolSpec("copy", "⎘", "COPY TEXT"),
        ToolSpec("screen_grab", "⊞", "LENS GRAB"),
        ToolSpec("clip_history", "📋", "CLIP HISTORY ▸"),
        ToolSpec("markdown_folder", "M↓", "MARKDOWN ▸"),
        ToolSpec("regex_pipe", "λ", "REGEX / PIPE ▸"),
        ToolSpec("define", "📖", "DEFINE WORD"),
        ToolSpec("translate", "🌐", "TRANSLATE"),
        ToolSpec("qr", "▣", "QR CODE"),
        ToolSpec("magnet", "🧲", "HASH → MAGNET"),
        ToolSpec("clean_url", "⌫", "CLEAN URL"),
        ToolSpec("extract", "＠", "EXTRACT LINKS"),
        ToolSpec("clean_text", "🧹", "CLEAN TEXT"),
        ToolSpec("sort", "⇅", "SORT LINES"),
        ToolSpec("case", "Aa", "CYCLE CASE"),
        ToolSpec("incognito", "🕵", "INCOGNITO"),
        ToolSpec("search", "↗", "WEB SEARCH"),
        ToolSpec("yt_all", "▶", "YOUTUBE"),
        ToolSpec("yt_today", "▶d", "YT TODAY"),
        ToolSpec("yt_week", "▶w", "YT LAST WEEK"),
        ToolSpec("yt_month", "▶m", "YT MONTH"),
        ToolSpec("speak", "🔊", "SPEAK ALOUD"),
        ToolSpec("log", "⊕", "APPEND LOG")
    )

    // Dedicated Markdown Folder Submenu items
    val markdownSubTools = listOf(
        ToolSpec("md_bold", "B", "MD BOLD (**)"),
        ToolSpec("md_italic", "I", "MD ITALIC (*)"),
        ToolSpec("md_code", "⟨⟩", "MD CODE (` / ```)"),
        ToolSpec("md_quote", "❝", "MD QUOTE (>)"),
        ToolSpec("md_link", "🔗", "MD LINK ([txt](url))"),
        ToolSpec("md_strike", "S̶", "MD STRIKE (~~)"),
        ToolSpec("md_bullet", "•", "MD BULLET LIST (-)"),
        ToolSpec("md_task", "☐", "MD TASK LIST (- [ ])"),
        ToolSpec("md_h2", "H2", "MD HEADING (##)"),
        ToolSpec("md_spoiler", "▒▒", "MD SPOILER (||)"),
        ToolSpec("md_table", "⊞", "MD TABLE (2×2)")
    )

    // Dedicated Regex & Developer Pipe Submenu items
    val regexPipeSubTools = listOf(
        ToolSpec("pipe_json_pretty", "{}", "JSON PRETTY"),
        ToolSpec("pipe_json_min", "{}", "JSON MINIFY"),
        ToolSpec("pipe_url_enc", "%", "URL ENCODE"),
        ToolSpec("pipe_url_dec", "%", "URL DECODE"),
        ToolSpec("pipe_b64_enc", "64", "BASE64 ENCODE"),
        ToolSpec("pipe_sha256", "#", "SHA-256 HASH"),
        ToolSpec("pipe_rot13", "13", "ROT13 CIPHER"),
        ToolSpec("pipe_num_lines", "1.", "NUMBER LINES"),
        ToolSpec("pipe_strip_html", "⟨/⟩", "STRIP HTML TAGS")
    )

    fun defaultRulesText(): String = """
        # Format: GLYPH | LABEL | TARGET ({text} = selection)
        # Submenu tips:
        # - Prefix with 'MD ' to place inside M↓ MARKDOWN ▸
        # - Prefix with 'PIPE/' or use s/find/replace/g to place inside λ REGEX / PIPE ▸
        # - Use 'FOLDER/LABEL' (e.g. DEV/GITHUB) to create your own collapsible folder!
        W | WIKIPEDIA | https://en.wikipedia.org/w/index.php?search={text}
        GH | DEV/GITHUB | https://github.com/search?q={text}
        SO | DEV/STACKOVERFLOW | https://stackoverflow.com/search?q={text}
        λ | PIPE/DIGITS ONLY | s/[^0-9]+//g
    """.trimIndent()

    fun ensureRulesFile(context: Context): File {
        val rulesFile = File(context.filesDir, "rules.txt")
        if (!rulesFile.exists()) {
            rulesFile.writeText(defaultRulesText())
        }
        return rulesFile
    }

    fun loadAllTools(context: Context): List<ToolSpec> {
        val list = mutableListOf<ToolSpec>()
        list.addAll(builtInTools)

        val customFoldersSeen = LinkedHashSet<String>()
        val rulesFile = ensureRulesFile(context)
        if (rulesFile.exists()) {
            rulesFile.readLines().forEachIndexed { idx, line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split("|").map { it.trim() }
                    if (parts.size >= 3) {
                        val glyph = parts[0].take(2)
                        val rawLabel = parts[1].uppercase()
                        val target = parts[2]

                        when {
                            rawLabel.startsWith("MD ") || rawLabel.startsWith("MARKDOWN/") || rawLabel.startsWith("MD/") -> {
                                // Handled in loadMarkdownTools
                            }
                            rawLabel.startsWith("PIPE/") || rawLabel.startsWith("REGEX/") || target.startsWith("s/") -> {
                                // Handled in loadRegexPipeTools
                            }
                            rawLabel.contains("/") -> {
                                val folder = rawLabel.substringBefore("/").trim()
                                if (folder.isNotEmpty() && customFoldersSeen.add(folder)) {
                                    list.add(
                                        ToolSpec(
                                            id = "folder_custom_$folder",
                                            glyph = "📁",
                                            label = "$folder ▸",
                                            folderName = folder
                                        )
                                    )
                                }
                            }
                            else -> {
                                list.add(
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
            }
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
                        val glyph = parts[0].take(2)
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
                        val glyph = parts[0].take(2)
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
        val targetFolder = folderName.uppercase().trim()
        val rulesFile = ensureRulesFile(context)
        if (rulesFile.exists()) {
            rulesFile.readLines().forEachIndexed { idx, line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split("|").map { it.trim() }
                    if (parts.size >= 3) {
                        val glyph = parts[0].take(2)
                        val rawLabel = parts[1].uppercase()
                        val target = parts[2]
                        if (rawLabel.contains("/")) {
                            val folder = rawLabel.substringBefore("/").trim()
                            val subLabel = rawLabel.substringAfter("/").trim()
                            if (folder == targetFolder && subLabel.isNotEmpty()) {
                                list.add(
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
        return getAllSortedTools(context).filter { !isToolHidden(context, it.id) }
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
        root.put("version", "1.7.0")
        root.put("timestamp", System.currentTimeMillis())
        root.put("sort_most_used", isSortMostUsed(context))
        root.put("snap_edge_left", isSnapLeftEdge(context))
        root.put("manual_order", prefs.getString(KEY_ORDER, "") ?: "")
        root.put("blacklisted_pkgs", prefs.getString(KEY_BLACKLIST, "") ?: "")
        root.put("rules_txt", if (rulesFile.exists()) rulesFile.readText() else "")

        val hiddenArr = JSONArray()
        val countsObj = JSONObject()
        for ((k, v) in prefs.all) {
            if (k.startsWith(PREFIX_HIDDEN) && v == true) {
                hiddenArr.put(k.removePrefix(PREFIX_HIDDEN))
            } else if (k.startsWith(PREFIX_COUNT) && v is Int && v > 0) {
                countsObj.put(k.removePrefix(PREFIX_COUNT), v)
            }
        }
        root.put("hidden_tools", hiddenArr)
        root.put("use_counts", countsObj)

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

            if (root.has("sort_most_used")) {
                editor.putBoolean(KEY_SORT_MOST_USED, root.optBoolean("sort_most_used", true))
            }
            if (root.has("snap_edge_left")) {
                editor.putBoolean(KEY_EDGE_LEFT, root.optBoolean("snap_edge_left", false))
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
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        container.requestDisallowInterceptTouchEvent(true)
                        container.parent?.requestDisallowInterceptTouchEvent(true)
                        downRawY = event.rawY
                        origIndex = container.indexOfChild(rowView)
                        targetIndex = origIndex
                        rowHeight = rowView.height.coerceAtLeast(1)
                        rowView.setBackgroundColor(Color.parseColor("#1F3324"))
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
                        val newTarget = (origIndex + shiftSlots).coerceIn(0, container.childCount - 1)
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
