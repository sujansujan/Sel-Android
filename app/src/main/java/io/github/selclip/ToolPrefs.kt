package io.github.selclip

import android.content.Context
import java.io.File

data class ToolSpec(
    val id: String,
    val glyph: String,
    val label: String,
    val isContextual: Boolean = false,
    val customTarget: String? = null
)

object ToolPrefs {
    private const val PREFS_NAME = "sel_tool_prefs"
    private const val KEY_ORDER = "manual_order"
    private const val KEY_SORT_MOST_USED = "sort_most_used"
    private const val PREFIX_COUNT = "use_count_"
    private const val PREFIX_HIDDEN = "hidden_"

    val builtInTools = listOf(
        ToolSpec("copy", "⎘", "COPY TEXT"),
        ToolSpec("define", "📖", "DEFINE WORD"),
        ToolSpec("qr", "▣", "QR CODE"),
        ToolSpec("incognito", "🕵", "INCOGNITO"),
        ToolSpec("search", "↗", "WEB SEARCH"),
        ToolSpec("yt_all", "▶", "YOUTUBE"),
        ToolSpec("yt_today", "▶d", "YT TODAY"),
        ToolSpec("yt_week", "▶w", "YT LAST WEEK"),
        ToolSpec("yt_month", "▶m", "YT MONTH"),
        ToolSpec("case", "Aa", "CYCLE CASE"),
        ToolSpec("quote", "❝", "MD QUOTE"),
        ToolSpec("code", "⟨⟩", "CODE BLOCK"),
        ToolSpec("sort", "⇅", "SORT LINES"),
        ToolSpec("log", "⊕", "APPEND LOG")
    )

    fun loadAllTools(context: Context): List<ToolSpec> {
        val list = mutableListOf<ToolSpec>()
        list.addAll(builtInTools)

        val rulesFile = File(context.filesDir, "rules.txt")
        if (rulesFile.exists()) {
            rulesFile.readLines().forEachIndexed { idx, line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split("|").map { it.trim() }
                    if (parts.size >= 3) {
                        val glyph = parts[0].take(2)
                        val label = parts[1].uppercase()
                        val target = parts[2]
                        list.add(
                            ToolSpec(
                                id = "custom_${idx}_$label",
                                glyph = glyph,
                                label = label,
                                customTarget = target
                            )
                        )
                    }
                }
            }
        }
        return list
    }

    fun isSortMostUsed(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SORT_MOST_USED, true)
    }

    fun setSortMostUsed(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SORT_MOST_USED, enabled).apply()
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

    fun getSortedToolsForPopup(context: Context): List<ToolSpec> {
        val manual = getManualOrderedTools(context)
        if (!isSortMostUsed(context)) return manual

        val manualIndex = manual.withIndex().associate { it.value.id to it.index }
        return manual.sortedWith(
            compareByDescending<ToolSpec> { getUseCount(context, it.id) }
                .thenBy { manualIndex[it.id] ?: 0 }
        )
    }

    fun moveTool(context: Context, toolId: String, direction: Int) {
        // Reorders within the currently active view (whether Most Used or Manual is active)
        val currentList = getSortedToolsForPopup(context).toMutableList()
        val idx = currentList.indexOfFirst { it.id == toolId }
        if (idx < 0) return
        val targetIdx = (idx + direction).coerceIn(0, currentList.size - 1)
        if (idx == targetIdx) return

        val item = currentList.removeAt(idx)
        currentList.add(targetIdx, item)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString(KEY_ORDER, currentList.joinToString(",") { it.id })

        // If moving an item up in Most-Used mode, ensure its usage count is at least equal to the item below it
        if (isSortMostUsed(context)) {
            if (direction < 0 && targetIdx + 1 < currentList.size) {
                val neighborId = currentList[targetIdx + 1].id
                val neighborCount = getUseCount(context, neighborId)
                val myCount = getUseCount(context, toolId)
                if (myCount < neighborCount) {
                    editor.putInt(PREFIX_COUNT + toolId, neighborCount)
                }
            } else if (direction > 0 && targetIdx - 1 >= 0) {
                val neighborId = currentList[targetIdx - 1].id
                val neighborCount = getUseCount(context, neighborId)
                val myCount = getUseCount(context, toolId)
                if (myCount > neighborCount) {
                    editor.putInt(PREFIX_COUNT + neighborId, myCount)
                }
            }
        }
        editor.apply()
    }
}
