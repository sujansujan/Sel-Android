package io.github.selclip.data

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
import kotlin.math.roundToInt

/**
 * Lean, cached preferences store with O(1) hot-path lookups and backward-compatible JSON backup.
 * Zero legacy dead keys.
 */
object PrefsStore {

    private const val PREFS_NAME = "sel_tool_prefs"
    private const val KEY_ORDER = "manual_order"
    private const val KEY_BLACKLIST = "blacklisted_pkgs"
    private const val KEY_CLIP_HISTORY = "clip_history_json"
    private const val KEY_FOLLOW_SYSTEM_THEME = "follow_system_theme"
    private const val KEY_DARK_THEME_MANUAL = "dark_theme_manual"
    private const val KEY_DEFAULT_SEARCH_ID = "default_search_engine_id"
    private const val KEY_PINNED_TOOLS = "pinned_bar_tool_ids"
    private const val PREFIX_COUNT = "use_count_"
    private const val PREFIX_HIDDEN = "hidden_"
    private const val MAX_CLIPS = 20

    @Volatile
    private var cachedBlacklist: Set<String>? = null

    @Volatile
    private var cachedClips: List<ClipEntry>? = null

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Theme ---

    fun getThemeMode(context: Context): ThemeMode {
        val p = prefs(context)
        if (p.getBoolean(KEY_FOLLOW_SYSTEM_THEME, true)) return ThemeMode.AUTO
        return if (p.getBoolean(KEY_DARK_THEME_MANUAL, true)) ThemeMode.DARK else ThemeMode.LIGHT
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        val editor = prefs(context).edit()
        when (mode) {
            ThemeMode.AUTO -> editor.putBoolean(KEY_FOLLOW_SYSTEM_THEME, true)
            ThemeMode.DARK -> editor.putBoolean(KEY_FOLLOW_SYSTEM_THEME, false).putBoolean(KEY_DARK_THEME_MANUAL, true)
            ThemeMode.LIGHT -> editor.putBoolean(KEY_FOLLOW_SYSTEM_THEME, false).putBoolean(KEY_DARK_THEME_MANUAL, false)
        }
        editor.apply()
    }

    fun isSystemInDarkMode(context: Context): Boolean {
        val mask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

    fun resolvePalette(context: Context): ThemePalette {
        val dark = when (getThemeMode(context)) {
            ThemeMode.AUTO -> isSystemInDarkMode(context)
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
        }
        return if (dark) AppPalettes.DARK else AppPalettes.LIGHT
    }

    // --- Default Search Engine ---

    fun getDefaultSearchEngineId(context: Context): String =
        prefs(context).getString(KEY_DEFAULT_SEARCH_ID, "web_google")
            ?.takeIf { it.isNotBlank() } ?: "web_google"

    fun setDefaultSearchEngineId(context: Context, engineId: String) {
        prefs(context).edit().putString(KEY_DEFAULT_SEARCH_ID, engineId.trim()).apply()
    }

    fun loadWebSearchTools(context: Context): List<ToolSpec> =
        BuiltInRegistry.webSearchEngines + RulesRepository.parseSnapshot(context).webSearchCustomTools

    fun resolveDefaultSearchEngine(context: Context): ToolSpec {
        val id = getDefaultSearchEngineId(context)
        return loadWebSearchTools(context).firstOrNull { it.id == id }
            ?: BuiltInRegistry.webSearchEngines.first()
    }

    fun loadYouTubeTools(context: Context): List<ToolSpec> =
        BuiltInRegistry.youtubeFilters + RulesRepository.parseSnapshot(context).youtubeCustomTools

    fun loadMarkdownTools(context: Context): List<ToolSpec> =
        BuiltInRegistry.markdownTools + RulesRepository.parseSnapshot(context).markdownCustomTools

    fun loadPipeTools(context: Context): List<ToolSpec> =
        BuiltInRegistry.pipeTools + RulesRepository.parseSnapshot(context).regexPipeCustomTools

    fun loadCustomFolderTools(context: Context, folderName: String): List<ToolSpec> =
        RulesRepository.parseSnapshot(context).folderRuleTools[folderName].orEmpty()

    fun loadAllTools(context: Context): List<ToolSpec> {
        val snap = RulesRepository.parseSnapshot(context)
        val folders = snap.ruleFolderNames.map { folder ->
            ToolSpec("folder_custom_$folder", "DIR", "$folder >", folderName = folder)
        }
        return BuiltInRegistry.builtInTools + folders + snap.topLevelCustomTools
    }

    // --- Pinned Bar Tools ---

    fun getPinnedToolIds(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_PINNED_TOOLS, "") ?: ""
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "copy" && it != "screen_grab" && it != "web_search_folder" && it != "youtube_folder" }
            .distinct()
    }

    fun isToolPinned(context: Context, toolId: String): Boolean =
        toolId in getPinnedToolIds(context)

    fun toggleToolPinned(context: Context, toolId: String): Boolean {
        if (toolId == "web_search_folder" || toolId == "youtube_folder") return false
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
            allMap[id]?.takeIf { !isToolHidden(context, id) }
        }
    }

    // --- Tool Visibility & Ordering ---

    fun isToolHidden(context: Context, id: String): Boolean =
        prefs(context).getBoolean(PREFIX_HIDDEN + id, false)

    fun setToolHidden(context: Context, id: String, hidden: Boolean) {
        prefs(context).edit().putBoolean(PREFIX_HIDDEN + id, hidden).apply()
    }

    fun getHiddenToolIds(context: Context): Set<String> {
        val p = prefs(context)
        return loadAllTools(context).map { it.id }.filter { p.getBoolean(PREFIX_HIDDEN + it, false) }.toSet()
    }

    fun recordUse(context: Context, id: String) {
        val p = prefs(context)
        p.edit().putInt(PREFIX_COUNT + id, p.getInt(PREFIX_COUNT + id, 0) + 1).apply()
    }

    fun getManualOrderedTools(context: Context): List<ToolSpec> {
        val all = loadAllTools(context)
        val savedOrder = prefs(context).getString(KEY_ORDER, "")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val orderMap = savedOrder.withIndex().associate { it.value to it.index }
        val fallbackIdx = all.withIndex().associate { it.value.id to it.index }
        return all.sortedWith(compareBy { orderMap[it.id] ?: (1000 + (fallbackIdx[it.id] ?: 0)) })
    }

    fun getVisibleToolsForDrawer(context: Context): List<ToolSpec> {
        val p = prefs(context)
        return getManualOrderedTools(context).filter { !p.getBoolean(PREFIX_HIDDEN + it.id, false) }
    }

    fun saveManualOrder(context: Context, orderedIds: List<String>) {
        prefs(context).edit().putString(KEY_ORDER, orderedIds.joinToString(",")).apply()
    }

    // --- Muted Apps (O(1) cached lookup on hot path) ---

    fun getMutedApps(context: Context): Set<String> {
        cachedBlacklist?.let { return it }
        val raw = prefs(context).getString(KEY_BLACKLIST, "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toHashSet().also {
            cachedBlacklist = it
        }
    }

    fun isAppMuted(context: Context, pkg: String): Boolean =
        pkg.isNotBlank() && pkg.trim() in getMutedApps(context)

    fun setAppMuted(context: Context, pkg: String, muted: Boolean) {
        val clean = pkg.trim()
        if (clean.isEmpty()) return
        val next = getMutedApps(context).toMutableSet()
        if (muted) next.add(clean) else next.remove(clean)
        cachedBlacklist = next.toHashSet()
        prefs(context).edit().putString(KEY_BLACKLIST, next.sorted().joinToString(",")).apply()
    }

    // --- Clipboard History ---

    fun getClipHistory(context: Context): List<ClipEntry> {
        cachedClips?.let { return it }
        val raw = prefs(context).getString(KEY_CLIP_HISTORY, "[]") ?: "[]"
        val list = mutableListOf<ClipEntry>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val txt = obj.optString("t", "")
                if (txt.isNotBlank()) {
                    list.add(ClipEntry(txt, obj.optBoolean("p", false), obj.optLong("ts", 0L)))
                }
            }
        }
        return list.sortedWith(compareByDescending<ClipEntry> { it.pinned }.thenByDescending { it.timestamp })
            .also { cachedClips = it }
    }

    private fun saveClips(context: Context, list: List<ClipEntry>) {
        val sorted = list.sortedWith(compareByDescending<ClipEntry> { it.pinned }.thenByDescending { it.timestamp })
        val arr = JSONArray()
        for (c in sorted) {
            arr.put(JSONObject().apply {
                put("t", c.text)
                put("p", c.pinned)
                put("ts", c.timestamp)
            })
        }
        cachedClips = sorted
        prefs(context).edit().putString(KEY_CLIP_HISTORY, arr.toString()).apply()
    }

    fun addClip(context: Context, text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || clean.length > 4000) return
        val current = getClipHistory(context).toMutableList()
        val existing = current.firstOrNull { it.text == clean }
        val wasPinned = existing?.pinned == true
        current.removeAll { it.text == clean }
        current.add(0, ClipEntry(clean, wasPinned, System.currentTimeMillis()))

        val pinned = current.filter { it.pinned }
        val unpinned = current.filter { !it.pinned }.take((MAX_CLIPS - pinned.size).coerceAtLeast(4))
        saveClips(context, pinned + unpinned)
    }

    fun togglePinClip(context: Context, text: String) {
        val updated = getClipHistory(context).map {
            if (it.text == text) it.copy(pinned = !it.pinned) else it
        }
        saveClips(context, updated)
    }

    fun deleteClip(context: Context, text: String) {
        saveClips(context, getClipHistory(context).filterNot { it.text == text })
    }

    fun clearUnpinnedClips(context: Context) {
        saveClips(context, getClipHistory(context).filter { it.pinned })
    }

    // --- JSON Backup Export / Import (Backward Compatible) ---

    fun exportBackupJson(context: Context): String {
        val p = prefs(context)
        val root = JSONObject().apply {
            put("version", 4)
            put("follow_system_theme", p.getBoolean(KEY_FOLLOW_SYSTEM_THEME, true))
            put("dark_theme_manual", p.getBoolean(KEY_DARK_THEME_MANUAL, true))
            put("default_search_engine_id", getDefaultSearchEngineId(context))
            put("pinned_bar_tool_ids", getPinnedToolIds(context).joinToString(","))
            put("manual_order", p.getString(KEY_ORDER, ""))
            put("blacklisted_pkgs", p.getString(KEY_BLACKLIST, ""))
            put("rules_txt", RulesRepository.ensureRulesFile(context).readText())
            put("hidden_tools", JSONArray(getHiddenToolIds(context).toList()))
            put("pinned_clips", JSONArray(getClipHistory(context).filter { it.pinned }.map { it.text }))
        }
        return root.toString(2)
    }

    fun importBackupJson(context: Context, jsonStr: String): Boolean = runCatching {
        val root = JSONObject(jsonStr)
        val p = prefs(context)
        val editor = p.edit()

        if (root.has("follow_system_theme")) {
            editor.putBoolean(KEY_FOLLOW_SYSTEM_THEME, root.optBoolean("follow_system_theme", true))
        }
        if (root.has("dark_theme_manual")) {
            editor.putBoolean(KEY_DARK_THEME_MANUAL, root.optBoolean("dark_theme_manual", true))
        }
        if (root.has("default_search_engine_id")) {
            editor.putString(KEY_DEFAULT_SEARCH_ID, root.optString("default_search_engine_id", "web_google"))
        }
        if (root.has("pinned_bar_tool_ids")) {
            editor.putString(KEY_PINNED_TOOLS, root.optString("pinned_bar_tool_ids", ""))
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
        editor.apply()

        if (root.has("rules_txt")) {
            val rulesTxt = root.optString("rules_txt", "")
            if (rulesTxt.isNotBlank()) {
                RulesRepository.writeRawRules(context, rulesTxt)
            }
        }
        if (root.has("pinned_clips")) {
            val arr = root.getJSONArray("pinned_clips")
            for (i in 0 until arr.length()) {
                val clip = arr.optString(i, "")
                if (clip.isNotBlank()) {
                    addClip(context, clip)
                    val updated = getClipHistory(context).map { if (it.text == clip) it.copy(pinned = true) else it }
                    saveClips(context, updated)
                }
            }
        }
        true
    }.getOrDefault(false)

    // --- Smooth Vertical Drag-to-Reorder Helper for Actions List ---

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
                val pal = resolvePalette(rowView.context)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        container.requestDisallowInterceptTouchEvent(true)
                        container.parent?.requestDisallowInterceptTouchEvent(true)
                        downRawY = event.rawY
                        origIndex = container.indexOfChild(rowView)
                        targetIndex = origIndex
                        rowHeight = rowView.height.coerceAtLeast(1)
                        rowView.setBackgroundColor(pal.bgPressedInt)
                        rowView.alpha = 0.94f
                        rowView.translationZ = 10f
                        runCatching { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (origIndex !in 0 until container.childCount) return@runCatching false
                        container.requestDisallowInterceptTouchEvent(true)
                        container.parent?.requestDisallowInterceptTouchEvent(true)
                        val dy = event.rawY - downRawY
                        rowView.translationY = dy
                        val shift = (dy / rowHeight.toFloat()).roundToInt()
                        val nextTarget = (origIndex + shift).coerceIn(0, (container.childCount - 1).coerceAtLeast(0))
                        if (nextTarget != targetIndex) {
                            targetIndex = nextTarget
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
                            child.alpha = 1f
                            child.translationZ = 0f
                            child.setBackgroundColor(Color.TRANSPARENT)
                        }
                        val count = container.childCount
                        if (origIndex in 0 until count && targetIndex in 0 until count && origIndex != targetIndex) {
                            val ids = (0 until count).mapNotNull { container.getChildAt(it)?.tag as? String }.toMutableList()
                            if (ids.size == count) {
                                val moved = ids.removeAt(origIndex)
                                ids.add(targetIndex, moved)
                                saveManualOrder(rowView.context, ids)
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
