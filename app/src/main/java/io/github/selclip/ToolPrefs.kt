package io.github.selclip

import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import java.io.File
import kotlin.math.roundToInt

data class ToolSpec(
    val id: String,
    val glyph: String,
    val label: String,
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
        ToolSpec("screen_grab", "⊞", "GRAB SCREEN"),
        ToolSpec("define", "📖", "DEFINE WORD"),
        ToolSpec("translate", "🌐", "TRANSLATE"),
        ToolSpec("qr", "▣", "QR CODE"),
        ToolSpec("magnet", "🧲", "HASH → MAGNET"),
        ToolSpec("incognito", "🕵", "INCOGNITO"),
        ToolSpec("search", "↗", "WEB SEARCH"),
        ToolSpec("yt_all", "▶", "YOUTUBE"),
        ToolSpec("yt_today", "▶d", "YT TODAY"),
        ToolSpec("yt_week", "▶w", "YT LAST WEEK"),
        ToolSpec("yt_month", "▶m", "YT MONTH"),
        ToolSpec("speak", "🔊", "SPEAK ALOUD"),
        ToolSpec("extract", "＠", "EXTRACT LINKS"),
        ToolSpec("clean_text", "🧹", "CLEAN TEXT"),
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

    /**
     * Crash-proof drag-and-drop:
     * Never detaches or removes views during MotionEvent.ACTION_MOVE (which corrupts Android ViewGroup TouchTargets).
     * Instead, shifts sibling rows via translationY during the drag and commits the new order on ACTION_UP via post{}.
     */
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

        handleView.setOnTouchListener { _, event ->
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
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (origIndex < 0 || origIndex >= container.childCount) return@runCatching false
                        container.requestDisallowInterceptTouchEvent(true)
                        container.parent?.requestDisallowInterceptTouchEvent(true)

                        val dy = event.rawY - downRawY
                        rowView.translationY = dy

                        val shiftSlots = (dy / rowHeight.toFloat()).roundToInt()
                        targetIndex = (origIndex + shiftSlots).coerceIn(0, container.childCount - 1)

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
