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
    private const val KEY_BAR_ORDER = "popup_bar_slot_order"
    private const val KEY_SERVICE_PAUSED = "service_paused"
    private const val KEY_TRANSLATE_LANG = "translate_target_lang"
    private const val PREFIX_COUNT = "use_count_"
    private const val PREFIX_HIDDEN = "hidden_"

    @Volatile
    private var cachedBlacklist: Set<String>? = null

    @Volatile
    private var cachedPaletteKey: Long = Long.MIN_VALUE

    @Volatile
    private var cachedPalette: ThemePalette? = null

    @Volatile
    private var cachedIconsOnly: Boolean? = null

    @Volatile
    private var cachedDefaultSearchId: String? = null

    @Volatile
    private var cachedPinnedIds: List<String>? = null

    @Volatile
    private var cachedBarOrder: List<String>? = null

    @Volatile
    private var cachedHiddenIds: Set<String>? = null

    @Volatile
    private var cachedServicePaused: Boolean? = null

    @Volatile
    private var cachedTranslateLang: String? = null

    fun invalidatePaletteCache() {
        cachedPaletteKey = Long.MIN_VALUE
        cachedPalette = null
    }

    fun invalidateToolCaches() {
        cachedPinnedIds = null
        cachedBarOrder = null
        cachedHiddenIds = null
    }

    private fun invalidateAllCaches() {
        cachedBlacklist = null
        cachedPaletteKey = Long.MIN_VALUE
        cachedPalette = null
        cachedIconsOnly = null
        cachedDefaultSearchId = null
        cachedPinnedIds = null
        cachedBarOrder = null
        cachedHiddenIds = null
        cachedServicePaused = null
        cachedTranslateLang = null
    }

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
        invalidatePaletteCache()
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
        val key = (mode.ordinal.toLong() shl 2) or (if (dark) 1L else 0L)
        if (cachedPaletteKey == key) {
            cachedPalette?.let { return it }
        }
        val resolved = if (mode == ThemeMode.AUTO) {
            AppPalettes.resolveDynamic(context, dark)
        } else {
            if (dark) AppPalettes.DARK else AppPalettes.LIGHT
        }
        cachedPalette = resolved
        cachedPaletteKey = key
        return resolved
    }

    fun isIconsOnlyMode(context: Context): Boolean =
        cachedIconsOnly ?: prefs(context).getBoolean(KEY_ICONS_ONLY, false).also { cachedIconsOnly = it }

    fun setIconsOnlyMode(context: Context, enabled: Boolean) {
        cachedIconsOnly = enabled
        prefs(context).edit().putBoolean(KEY_ICONS_ONLY, enabled).apply()
    }

    // --- Quick Pause Toggle (O(1) cached check on accessibility hot path) ---

    fun isServicePaused(context: Context): Boolean =
        cachedServicePaused ?: prefs(context).getBoolean(KEY_SERVICE_PAUSED, false).also { cachedServicePaused = it }

    fun setServicePaused(context: Context, paused: Boolean) {
        cachedServicePaused = paused
        prefs(context).edit().putBoolean(KEY_SERVICE_PAUSED, paused).apply()
    }

    // --- Translate Target Language ---

    fun getTranslateTargetLang(context: Context): String =
        cachedTranslateLang ?: (prefs(context).getString(KEY_TRANSLATE_LANG, "en")
            ?.trim()?.takeIf { it.isNotEmpty() } ?: "en").also { cachedTranslateLang = it }

    fun setTranslateTargetLang(context: Context, langCode: String) {
        val clean = langCode.trim().ifEmpty { "en" }
        cachedTranslateLang = clean
        prefs(context).edit().putString(KEY_TRANSLATE_LANG, clean).apply()
    }

    fun resolveTranslateLanguage(context: Context): TranslateLanguage {
        val code = getTranslateTargetLang(context)
        return BuiltInRegistry.translateLanguages.firstOrNull { it.code.equals(code, ignoreCase = true) }
            ?: TranslateLanguage(code, code.uppercase().take(4), code.uppercase())
    }

    // --- Default Search Engine ---

    fun getDefaultSearchEngineId(context: Context): String =
        cachedDefaultSearchId ?: (prefs(context).getString(KEY_DEFAULT_SEARCH_ID, "web_google")
            ?.takeIf { it.isNotBlank() } ?: "web_google").also { cachedDefaultSearchId = it }

    fun setDefaultSearchEngineId(context: Context, engineId: String) {
        val clean = engineId.trim()
        cachedDefaultSearchId = clean.ifEmpty { "web_google" }
        prefs(context).edit().putString(KEY_DEFAULT_SEARCH_ID, clean).apply()
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
        cachedPinnedIds?.let { return it }
        val raw = prefs(context).getString(KEY_PINNED_TOOLS, "") ?: ""
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it !in excludedPinIds }
            .distinct()
            .also { cachedPinnedIds = it }
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
        cachedPinnedIds = current.toList()
        cachedBarOrder = null
        prefs(context).edit().putString(KEY_PINNED_TOOLS, current.joinToString(",")).apply()
        return nowPinned
    }

    fun getPinnedToolSpecs(context: Context): List<ToolSpec> {
        val ids = getPinnedToolIds(context)
        if (ids.isEmpty()) return emptyList()
        val hidden = getHiddenToolIds(context)
        val allMap = (loadAllTools(context) + loadWebSearchTools(context)).associateBy { it.id }
        return ids.mapNotNull { id ->
            allMap[id]?.takeIf { id !in hidden }
        }
    }

    // --- Customizable Left-to-Right Popup Bar Slot Order ---

    fun getBarSlotOrder(context: Context): List<String> {
        cachedBarOrder?.let { return it }
        val pinnedIds = getPinnedToolSpecs(context).map { it.id }.toSet()
        val validCore = setOf(
            io.github.selclip.core.ContextActionResolver.SLOT_CONTEXT,
            io.github.selclip.core.ContextActionResolver.SLOT_DEFAULT_SEARCH,
            io.github.selclip.core.ContextActionResolver.SLOT_YOUTUBE
        )
        val saved = prefs(context).getString(KEY_BAR_ORDER, "")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && (it in validCore || it in pinnedIds) }
            ?: emptyList()

        val result = LinkedHashSet<String>()
        result.addAll(saved)
        result.add(io.github.selclip.core.ContextActionResolver.SLOT_CONTEXT)
        result.add(io.github.selclip.core.ContextActionResolver.SLOT_DEFAULT_SEARCH)
        result.add(io.github.selclip.core.ContextActionResolver.SLOT_YOUTUBE)
        result.addAll(pinnedIds)
        return result.toList().also { cachedBarOrder = it }
    }

    fun saveBarSlotOrder(context: Context, orderedSlotIds: List<String>) {
        val dedup = orderedSlotIds.distinct()
        cachedBarOrder = null
        prefs(context).edit().putString(KEY_BAR_ORDER, dedup.joinToString(",")).apply()
    }

    fun moveBarSlot(context: Context, slotId: String, delta: Int): List<String> {
        val list = getBarSlotOrder(context).toMutableList()
        val idx = list.indexOf(slotId)
        if (idx < 0) return list
        val target = (idx + delta).coerceIn(0, list.lastIndex)
        if (target != idx) {
            val item = list.removeAt(idx)
            list.add(target, item)
            saveBarSlotOrder(context, list)
        }
        return list
    }

    // --- Tool Visibility & Ordering ---

    fun isToolHidden(context: Context, id: String): Boolean =
        id in getHiddenToolIds(context)

    fun setToolHidden(context: Context, id: String, hidden: Boolean) {
        prefs(context).edit().putBoolean(PREFIX_HIDDEN + id, hidden).apply()
        cachedHiddenIds = null
        cachedBarOrder = null
    }

    fun getHiddenToolIds(context: Context): Set<String> {
        cachedHiddenIds?.let { return it }
        val p = prefs(context)
        return loadAllTools(context).map { it.id }.filter { p.getBoolean(PREFIX_HIDDEN + it, false) }.toHashSet().also {
            cachedHiddenIds = it
        }
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
            put("version", 6)
            put("follow_system_theme", p.getBoolean(KEY_FOLLOW_SYSTEM_THEME, true))
            put("dark_theme_manual", p.getBoolean(KEY_DARK_THEME_MANUAL, true))
            put("icons_only_mode", isIconsOnlyMode(context))
            put("translate_target_lang", getTranslateTargetLang(context))
            put("default_search_engine_id", getDefaultSearchEngineId(context))
            put("pinned_bar_tool_ids", getPinnedToolIds(context).joinToString(","))
            put("popup_bar_slot_order", getBarSlotOrder(context).joinToString(","))
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
        if (root.has("translate_target_lang")) {
            editor.putString(KEY_TRANSLATE_LANG, root.optString("translate_target_lang", "en"))
        }
        if (root.has("default_search_engine_id")) {
            editor.putString(KEY_DEFAULT_SEARCH_ID, root.optString("default_search_engine_id", "web_google"))
        }
        if (root.has("pinned_bar_tool_ids")) {
            editor.putString(KEY_PINNED_TOOLS, root.optString("pinned_bar_tool_ids", ""))
        }
        if (root.has("popup_bar_slot_order")) {
            editor.putString(KEY_BAR_ORDER, root.optString("popup_bar_slot_order", ""))
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
        invalidateAllCaches()
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
