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
 * Lean, cached preferences store (clipboard history & notes completely removed).
 */
object PrefsStore {

    private const val PREFS_NAME = "sel_tool_prefs"
    private const val KEY_ORDER = "manual_order"
    private const val KEY_BLACKLIST = "blacklisted_pkgs"
    private const val KEY_FOLLOW_SYSTEM_THEME = "follow_system_theme"
    private const val KEY_DARK_THEME_MANUAL = "dark_theme_manual"
    private const val KEY_ICONS_ONLY = "icons_only_mode"
    private const val KEY_DEFAULT_SEARCH_ID = "default_search_engine_id"
    private const val KEY_PINNED_TOOLS = "pinned_bar_tool_ids"
    private const val PREFIX_COUNT = "use_count_"
    private const val PREFIX_HIDDEN = "hidden_"

    @Volatile
    private var cachedBlacklist: Set<String>? = null

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Theme & Bar Display Style ---

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
        val mode = getThemeMode(context)
        val dark = when (mode) {
            ThemeMode.AUTO -> isSystemInDarkMode(context)
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
        }
        return if (mode == ThemeMode.AUTO) {
            AppPalettes.resolveDynamic(context, dark)
        } else {
            if (dark) AppPalettes.DARK else AppPalettes.LIGHT
        }
    }

    fun isIconsOnlyMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ICONS_ONLY, false)

    fun setIconsOnlyMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ICONS_ONLY, enabled).apply()
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

    private val excludedPinIds = setOf("copy", "screen_grab", "clip_history", "log", "web_search_folder", "youtube_folder")

    fun getPinnedToolIds(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_PINNED_TOOLS, "") ?: ""
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it !in excludedPinIds }
            .distinct()
    }

    fun isToolPinned(context: Context, toolId: String): Boolean =
        toolId in getPinnedToolIds(context)

    fun toggleToolPinned(context: Context, toolId: String): Boolean {
        if (toolId in excludedPinIds) return false
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

    // --- JSON Backup Export / Import ---

    fun exportBackupJson(context: Context): String {
        val p = prefs(context)
        val root = JSONObject().apply {
            put("version", 5)
            put("follow_system_theme", p.getBoolean(KEY_FOLLOW_SYSTEM_THEME, true))
            put("dark_theme_manual", p.getBoolean(KEY_DARK_THEME_MANUAL, true))
            put("icons_only_mode", isIconsOnlyMode(context))
            put("default_search_engine_id", getDefaultSearchEngineId(context))
            put("pinned_bar_tool_ids", getPinnedToolIds(context).joinToString(","))
            put("manual_order", p.getString(KEY_ORDER, ""))
            put("blacklisted_pkgs", p.getString(KEY_BLACKLIST, ""))
            put("rules_txt", RulesRepository.ensureRulesFile(context).readText())
            put("hidden_tools", JSONArray(getHiddenToolIds(context).toList()))
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
        if (root.has("icons_only_mode")) {
            editor.putBoolean(KEY_ICONS_ONLY, root.optBoolean("icons_only_mode", false))
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
