package io.github.selclip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * Mobile-first, block-first Screen Text Grab overlay with crisp ASCII PopClip aesthetics.
 *
 * Design principles (Universal Copy + Fitts's Law thumb-zone ergonomics):
 * - Tappable text blocks with generous +6dp hitboxes & exact getLocationOnScreen() alignment.
 * - Single-tap any block to select it; tap multiple blocks to combine in tap order ([1], [2]).
 * - Double-tap any block (or tap [EDIT]) to open a native Android EditText with real OS
 *   word/substring selection handles — zero janky custom drag handles.
 * - [LIST] toggle switches to a clean scrollable reader of every block on screen (great for
 *   tiny or overlapping text) with 1-tap [COPY].
 * - [OCR] triggers on-device ML Kit OCR for text inside images, videos, and canvases.
 * - Bottom thumb-zone ASCII action dock with 48dp buttons powered by PopupContextEngine.
 */
class LensOverlayView(
    context: Context,
    private val backgroundBitmap: Bitmap?,
    initialSnapshot: LensSnapshot?,
    private val onRunAction: (PopAction, String) -> Unit,
    private val onOpenFullPalette: (String) -> Unit,
    private val onRequestOcr: () -> Unit,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private data class GrabBlock(
        val id: Int,
        val text: String,
        val bounds: RectF,
        val lineRects: List<RectF>
    )

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density
    private fun dpInt(v: Int): Int = (v * density).toInt()
    private val pal = ToolPrefs.resolvePalette(context)

    private var blocks: List<GrabBlock> = emptyList()
    private var quickEntities: List<ExtractedEntity> = emptyList()
    private val selectedBlockIds = ArrayList<Int>()
    private var customEditedText: String? = null

    private var isOcrResult: Boolean = initialSnapshot?.isFromOcr == true
    private var isScanning: Boolean = initialSnapshot == null
    private var isListMode: Boolean = false
    private var isEditingInline: Boolean = false

    private var topInsetPx: Int = dpInt(34)
    private var bottomInsetPx: Int = dpInt(20)
    private val locOnScreen = IntArray(2)

    private lateinit var grabCanvas: BlockCanvasView
    private lateinit var listOverlayScroll: ScrollView
    private lateinit var listOverlayContent: LinearLayout

    private lateinit var topBar: LinearLayout
    private lateinit var topStatusText: TextView
    private lateinit var listToggleBtn: TextView

    private lateinit var bottomDock: LinearLayout
    private lateinit var idlePanel: LinearLayout
    private lateinit var idleHintText: TextView
    private lateinit var entitiesRow: LinearLayout
    private lateinit var entitiesScroll: HorizontalScrollView

    private lateinit var selectionPanel: LinearLayout
    private lateinit var selectionPreviewText: TextView
    private lateinit var dockActionsRow: LinearLayout

    private lateinit var inlineEditBackdrop: FrameLayout
    private lateinit var inlineEditInput: EditText

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        buildHierarchy()
        if (initialSnapshot != null) {
            applySnapshot(initialSnapshot)
        }
    }

    fun setSnapshot(snapshot: LensSnapshot) {
        post { applySnapshot(snapshot) }
    }

    private fun applySnapshot(snapshot: LensSnapshot) {
        isScanning = false
        isOcrResult = snapshot.isFromOcr
        quickEntities = snapshot.quickEntities
        blocks = buildGrabBlocks(snapshot)
        selectedBlockIds.clear()
        customEditedText = null

        updateTopStatus()
        rebuildListView()
        refreshBottomDock()
        grabCanvas.invalidate()
    }

    private fun buildGrabBlocks(snapshot: LensSnapshot): List<GrabBlock> {
        if (snapshot.words.isEmpty()) return emptyList()

        val wordsByBlock = snapshot.words.groupBy { it.blockIndex }
        val linesByBlock = snapshot.lines.groupBy { it.blockIndex }
        val blockKeys = wordsByBlock.keys.sorted()

        val out = ArrayList<GrabBlock>(blockKeys.size)
        var nextId = 0

        for (bKey in blockKeys) {
            val bWords = wordsByBlock[bKey].orEmpty()
            if (bWords.isEmpty()) continue

            val bLines = linesByBlock[bKey].orEmpty().sortedBy { it.lineIndex }
            val textBuilder = StringBuilder()
            val lineRects = ArrayList<RectF>()
            var unionBounds: RectF? = null

            if (bLines.isNotEmpty()) {
                for (ln in bLines) {
                    val start = ln.wordStartIndex.coerceIn(0, snapshot.words.lastIndex)
                    val end = ln.wordEndIndex.coerceIn(start, snapshot.words.lastIndex)
                    val lineWords = (start..end)
                        .map { snapshot.words[it] }
                        .filter { it.blockIndex == bKey }
                    val lineText = lineWords.joinToString(" ") { it.text }.trim()
                    if (lineText.isEmpty()) continue
                    if (textBuilder.isNotEmpty()) textBuilder.append('\n')
                    textBuilder.append(lineText)

                    val lr = RectF(ln.bounds)
                    lineRects.add(lr)
                    if (unionBounds == null) unionBounds = RectF(lr) else unionBounds.union(lr)
                }
            }

            if (textBuilder.isEmpty()) {
                val sortedW = bWords.sortedBy { it.index }
                textBuilder.append(sortedW.joinToString(" ") { it.text }.trim())
                for (w in sortedW) {
                    if (unionBounds == null) unionBounds = RectF(w.rect) else unionBounds.union(w.rect)
                }
                if (unionBounds != null) lineRects.add(RectF(unionBounds))
            }

            val finalText = textBuilder.toString().trim()
            val finalBounds = unionBounds
            if (finalText.isNotEmpty() && finalBounds != null && finalBounds.width() >= 4f && finalBounds.height() >= 4f) {
                out.add(
                    GrabBlock(
                        id = nextId++,
                        text = finalText,
                        bounds = finalBounds,
                        lineRects = lineRects
                    )
                )
            }
        }

        val bandPx = dp(24f).coerceAtLeast(20f)
        return out.sortedWith(
            compareBy<GrabBlock> { (it.bounds.top / bandPx).toInt() }
                .thenBy { it.bounds.left }
                .thenBy { it.bounds.top }
        )
    }

    private fun getActiveText(): String {
        customEditedText?.let { if (it.isNotBlank()) return it.trim() }
        if (selectedBlockIds.isEmpty()) return ""
        val byId = blocks.associateBy { it.id }
        return selectedBlockIds.mapNotNull { byId[it]?.text }.joinToString("\n").trim()
    }

    private fun toggleBlockSelection(blockId: Int) {
        customEditedText = null
        if (blockId in selectedBlockIds) {
            selectedBlockIds.remove(blockId)
        } else {
            selectedBlockIds.add(blockId)
        }
        runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
        grabCanvas.invalidate()
        if (isListMode) rebuildListView()
        refreshBottomDock()
    }

    private fun clearSelection() {
        selectedBlockIds.clear()
        customEditedText = null
        grabCanvas.invalidate()
        if (isListMode) rebuildListView()
        refreshBottomDock()
    }

    private fun buildHierarchy() {
        // 1. Full-screen visual block overlay canvas
        grabCanvas = BlockCanvasView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(grabCanvas)

        // 2. Scrollable List Mode reader (hidden by default)
        listOverlayContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(12), dpInt(8), dpInt(12), dpInt(120))
        }
        listOverlayScroll = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.argb(238, Color.red(pal.bgPrimaryInt), Color.green(pal.bgPrimaryInt), Color.blue(pal.bgPrimaryInt)))
            visibility = View.GONE
            addView(listOverlayContent)
        }
        addView(listOverlayScroll)

        // 3. Minimal ASCII Top Bar
        topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(pal.bgHeaderInt)
                setStroke(dpInt(1), pal.borderInt)
                cornerRadius = dp(2f)
            }
            elevation = dp(10f)
            setPadding(dpInt(8), dpInt(4), dpInt(6), dpInt(4))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = topInsetPx + dpInt(6)
                marginStart = dpInt(10)
                marginEnd = dpInt(10)
            }
        }

        topStatusText = TextView(context).apply {
            text = if (isScanning) "[sel:ocr] scanning..." else "[sel:grab]"
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(pal.accentPurpleInt)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dpInt(4), dpInt(6), dpInt(6), dpInt(6))
        }
        topBar.addView(topStatusText)

        listToggleBtn = makeTopChip("LIST", pal.accentCyanInt) {
            toggleListMode()
        }
        topBar.addView(listToggleBtn)
        topBar.addView(makeVertPipe(18))

        topBar.addView(makeTopChip("OCR", pal.accentOrangeInt) {
            onRequestOcr()
        })
        topBar.addView(makeVertPipe(18))

        topBar.addView(makeTopChip("X", pal.dangerRedInt) {
            onDismiss()
        })
        addView(topBar)

        // 4. Thumb-Zone Bottom ASCII Action Dock (Fitts's Law)
        bottomDock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(pal.bgPrimaryInt)
                setStroke(dpInt(1), pal.borderInt)
                cornerRadius = dp(2f)
            }
            elevation = dp(14f)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = bottomInsetPx + dpInt(8)
                marginStart = dpInt(8)
                marginEnd = dpInt(8)
            }
        }

        // 4a. Idle hint + quick detected entities
        idlePanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(10), dpInt(8), dpInt(10), dpInt(8))
        }
        idleHintText = TextView(context).apply {
            text = "Tap any text block · Double-tap to pick words"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.2f)
            setTextColor(pal.fgMutedInt)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        idlePanel.addView(idleHintText)

        entitiesRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        entitiesScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            setPadding(0, dpInt(6), 0, 0)
            addView(entitiesRow)
        }
        idlePanel.addView(entitiesScroll)
        bottomDock.addView(idlePanel)

        // 4b. Active Selection Preview + 48dp Context Action Row
        selectionPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val previewRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(pal.bgHeaderInt)
            setPadding(dpInt(10), dpInt(4), dpInt(6), dpInt(4))
        }
        selectionPreviewText = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.2f)
            setTextColor(pal.fgPrimaryInt)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, dpInt(2), dpInt(6), dpInt(2))
        }
        previewRow.addView(selectionPreviewText)
        previewRow.addView(makeTopChip("EDIT", pal.accentPurpleInt) {
            openInlineEditor(getActiveText())
        })
        previewRow.addView(makeVertPipe(16))
        previewRow.addView(makeTopChip("CLR", pal.fgMutedInt) {
            clearSelection()
        })
        selectionPanel.addView(previewRow)

        selectionPanel.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpInt(1))
            setBackgroundColor(pal.dividerInt)
        })

        dockActionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dockActionsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(dockActionsRow)
        }
        selectionPanel.addView(dockActionsScroll)
        bottomDock.addView(selectionPanel)

        addView(bottomDock)

        // 5. Native EditText Modal for Effortless Word / Substring Selection
        inlineEditBackdrop = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            visibility = View.GONE
            setOnClickListener { closeInlineEditor() }
        }

        val editCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(pal.bgPrimaryInt)
                setStroke(dpInt(1), pal.accentPurpleInt)
                cornerRadius = dp(2f)
            }
            elevation = dp(18f)
            setPadding(dpInt(12), dpInt(10), dpInt(12), dpInt(10))
            setOnClickListener { /* consume click */ }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
                marginStart = dpInt(14)
                marginEnd = dpInt(14)
            }
        }

        editCard.addView(TextView(context).apply {
            text = "[ EDIT / HIGHLIGHT SUBSTRING ]"
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.2f)
            setTextColor(pal.accentCyanInt)
            setPadding(0, 0, 0, dpInt(6))
        })

        inlineEditInput = EditText(context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(pal.fgPrimaryInt)
            setBackgroundColor(pal.bgInputInt)
            setPadding(dpInt(10), dpInt(10), dpInt(10), dpInt(10))
            setTextIsSelectable(true)
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        editCard.addView(inlineEditInput)

        editCard.addView(TextView(context).apply {
            text = "Tip: Long-press any word above to pick an exact substring."
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(pal.fgMutedInt)
            setPadding(0, dpInt(6), 0, dpInt(8))
        })

        val editBtnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        editBtnRow.addView(makeEqualDockBtn("COPY", pal.accentGreenInt) {
            val chosen = extractEditedOrHighlightedText()
            closeInlineEditor()
            if (chosen.isNotBlank()) onRunAction(PopAction("copy", "COPY", accent = true), chosen)
        })
        editBtnRow.addView(makeVertPipe(22))
        editBtnRow.addView(makeEqualDockBtn("USE TEXT", pal.accentCyanInt) {
            val chosen = extractEditedOrHighlightedText()
            closeInlineEditor()
            if (chosen.isNotBlank()) {
                customEditedText = chosen
                refreshBottomDock()
            }
        })
        editBtnRow.addView(makeVertPipe(22))
        editBtnRow.addView(makeEqualDockBtn("CANCEL", pal.fgMutedInt) {
            closeInlineEditor()
        })
        editCard.addView(editBtnRow)

        inlineEditBackdrop.addView(editCard)
        addView(inlineEditBackdrop)
    }

    private fun extractEditedOrHighlightedText(): String {
        val full = inlineEditInput.text?.toString().orEmpty()
        val s = inlineEditInput.selectionStart
        val e = inlineEditInput.selectionEnd
        if (s >= 0 && e > s && e <= full.length) {
            val sub = full.substring(s, e).trim()
            if (sub.isNotEmpty()) return sub
        }
        return full.trim()
    }

    private fun toggleListMode() {
        isListMode = !isListMode
        listOverlayScroll.visibility = if (isListMode) View.VISIBLE else View.GONE
        listToggleBtn.text = if (isListMode) "SCREEN" else "LIST"
        listToggleBtn.setTextColor(if (isListMode) pal.accentGreenInt else pal.accentCyanInt)
        if (isListMode) {
            rebuildListView()
        }
    }

    private fun updateTopStatus() {
        val tag = if (isOcrResult) "ocr" else "grab"
        topStatusText.text = when {
            isScanning -> "[sel:ocr] scanning..."
            blocks.isEmpty() -> "[sel:$tag] 0 blocks — try [OCR]"
            else -> "[sel:$tag] ${blocks.size} blocks"
        }
    }

    private fun rebuildListView() {
        listOverlayContent.removeAllViews()
        listOverlayScroll.setPadding(0, topInsetPx + dpInt(58), 0, bottomInsetPx + dpInt(12))

        if (blocks.isEmpty()) {
            listOverlayContent.addView(TextView(context).apply {
                text = "No readable text blocks found.\nTap [OCR] at the top to scan pixels on screen."
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(pal.fgMutedInt)
                setPadding(dpInt(8), dpInt(20), dpInt(8), dpInt(12))
            })
            return
        }

        blocks.forEach { block ->
            val orderIdx = selectedBlockIds.indexOf(block.id)
            val isSel = orderIdx >= 0

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(if (isSel) pal.bgPressedInt else pal.bgSurfaceInt)
                    setStroke(dpInt(1), if (isSel) pal.accentGreenInt else pal.borderInt)
                    cornerRadius = dp(2f)
                }
                setPadding(dpInt(10), dpInt(8), dpInt(6), dpInt(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpInt(6) }
                setOnClickListener { toggleBlockSelection(block.id) }
            }

            val prefix = if (isSel) "[${orderIdx + 1}] " else ""
            card.addView(TextView(context).apply {
                text = prefix + block.text
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(if (isSel) pal.accentGreenInt else pal.fgPrimaryInt)
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            card.addView(makeTopChip("COPY", pal.accentGreenInt) {
                onRunAction(PopAction("copy", "COPY", accent = true), block.text)
            })
            listOverlayContent.addView(card)
        }
    }

    private fun refreshBottomDock() {
        val active = getActiveText()
        if (active.isEmpty()) {
            idlePanel.visibility = View.VISIBLE
            selectionPanel.visibility = View.GONE
            idleHintText.text = when {
                isScanning -> "Scanning screen pixels with on-device OCR..."
                blocks.isEmpty() -> "No text found — tap [OCR] above to scan images/videos"
                else -> "Tap any text block · Double-tap to pick exact words"
            }

            entitiesRow.removeAllViews()
            if (quickEntities.isNotEmpty()) {
                entitiesScroll.visibility = View.VISIBLE
                quickEntities.forEachIndexed { idx, entity ->
                    if (idx > 0) entitiesRow.addView(makeVertPipe(16))
                    val shortVal = entity.value.replace("\n", " ").let { if (it.length > 18) it.take(17) + "…" else it }
                    entitiesRow.addView(makeDockChip("[${entity.kind}] $shortVal", pal.accentCyanInt) {
                        customEditedText = entity.value
                        refreshBottomDock()
                    })
                }
            } else {
                entitiesScroll.visibility = View.GONE
            }
            return
        }

        idlePanel.visibility = View.GONE
        selectionPanel.visibility = View.VISIBLE

        val countPrefix = when {
            customEditedText != null -> "[EDITED] "
            selectedBlockIds.size > 1 -> "[${selectedBlockIds.size} BLOCKS] "
            else -> ""
        }
        selectionPreviewText.text = countPrefix + active.replace("\n", " ")

        dockActionsRow.removeAllViews()
        val hiddenIds = ToolPrefs.loadAllTools(context)
            .filter { ToolPrefs.isToolHidden(context, it.id) }
            .map { it.id }
            .toSet()
        val pinnedTools = ToolPrefs.getPinnedToolSpecs(context)
        val defaultEngine = ToolPrefs.resolveDefaultSearchEngine(context)
        val searchLabel = defaultEngine.glyph.take(4).ifEmpty { "WEB" }

        val ctx = PopupContextEngine.analyze(active, isEditable = false, hasUndo = false)
        val actions = PopupContextEngine.resolveBarActions(
            ctx = ctx,
            hiddenToolIds = hiddenIds,
            pinnedTools = pinnedTools,
            searchLabel = searchLabel,
            includeCopy = true
        )

        actions.forEachIndexed { idx, act ->
            if (idx > 0) dockActionsRow.addView(makeVertPipe(24))
            dockActionsRow.addView(
                makeDockActionButton(
                    label = act.label,
                    fgColor = if (act.accent) pal.accentGreenInt else pal.fgPrimaryInt,
                    onLongClick = {
                        when (act.id) {
                            "default_search" -> onRunAction(PopAction("web_search_folder", act.label), active)
                            "youtube_direct" -> onRunAction(PopAction("youtube_folder", act.label), active)
                            else -> onRunAction(act, active)
                        }
                    }
                ) {
                    onRunAction(act, active)
                }
            )
        }

        dockActionsRow.addView(makeVertPipe(24))
        dockActionsRow.addView(makeDockActionButton("+", pal.accentCyanInt) {
            onOpenFullPalette(active)
        })
    }

    private fun openInlineEditor(initialText: String) {
        if (initialText.isBlank()) return
        isEditingInline = true
        inlineEditInput.setText(initialText)
        inlineEditInput.setSelection(0, initialText.length)
        inlineEditBackdrop.visibility = View.VISIBLE
        inlineEditInput.requestFocus()
        postDelayed({
            runCatching {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(inlineEditInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 80L)
    }

    private fun closeInlineEditor() {
        isEditingInline = false
        inlineEditBackdrop.visibility = View.GONE
        runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(inlineEditInput.windowToken, 0)
        }
    }

    private fun makeTopChip(label: String, fgColor: Int, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(fgColor)
            gravity = Gravity.CENTER
            minHeight = dpInt(38)
            minWidth = dpInt(42)
            setPadding(dpInt(10), dpInt(6), dpInt(10), dpInt(6))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(pal.bgPressedInt) })
                addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            }
            setOnClickListener {
                runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                onClick()
            }
        }
    }

    private fun makeDockChip(label: String, fgColor: Int, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(fgColor)
            gravity = Gravity.CENTER
            minHeight = dpInt(36)
            setPadding(dpInt(9), dpInt(6), dpInt(9), dpInt(6))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(pal.bgPressedInt) })
                addState(intArrayOf(), GradientDrawable().apply {
                    setColor(pal.bgSurfaceInt)
                    setStroke(dpInt(1), pal.borderInt)
                    cornerRadius = dp(2f)
                })
            }
            setOnClickListener {
                runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                onClick()
            }
        }
    }

    private fun makeDockActionButton(
        label: String,
        fgColor: Int,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            text = label
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(fgColor)
            gravity = Gravity.CENTER
            isSingleLine = true
            minHeight = dpInt(48)
            minWidth = dpInt(52)
            setPadding(dpInt(14), dpInt(12), dpInt(14), dpInt(12))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(pal.bgPressedInt) })
                addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            }
            setOnClickListener {
                runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                onClick()
            }
            if (onLongClick != null) {
                setOnLongClickListener {
                    runCatching { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
                    onLongClick()
                    true
                }
            }
        }
    }

    private fun makeEqualDockBtn(label: String, fgColor: Int, onClick: () -> Unit): TextView {
        return makeDockActionButton(label, fgColor, onClick = onClick).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
    }

    private fun makeVertPipe(heightDp: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpInt(1), dpInt(heightDp))
            setBackgroundColor(pal.dividerInt)
        }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= 30) {
            val sys = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            topInsetPx = sys.top.coerceAtLeast(dpInt(24))
            bottomInsetPx = sys.bottom.coerceAtLeast(dpInt(14))
        } else {
            @Suppress("DEPRECATION")
            topInsetPx = insets.systemWindowInsetTop.coerceAtLeast(dpInt(24))
            @Suppress("DEPRECATION")
            bottomInsetPx = insets.systemWindowInsetBottom.coerceAtLeast(dpInt(14))
        }

        (topBar.layoutParams as? LayoutParams)?.let { lp ->
            lp.topMargin = topInsetPx + dpInt(6)
            topBar.layoutParams = lp
        }
        (bottomDock.layoutParams as? LayoutParams)?.let { lp ->
            lp.bottomMargin = bottomInsetPx + dpInt(8)
            bottomDock.layoutParams = lp
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            when {
                isEditingInline -> closeInlineEditor()
                isListMode -> toggleListMode()
                selectedBlockIds.isNotEmpty() || customEditedText != null -> clearSelection()
                else -> onDismiss()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (backgroundBitmap != null && !backgroundBitmap.isRecycled) {
            runCatching { backgroundBitmap.recycle() }
        }
    }

    /**
     * Full-screen block overlay canvas with exact screen-coordinate compensation via
     * getLocationOnScreen() and generous +6dp tap hitboxes.
     */
    private inner class BlockCanvasView(ctx: Context) : View(ctx) {

        private val dimPaint = Paint().apply {
            color = Color.argb(46, 0, 0, 0)
            style = Paint.Style.FILL
        }
        private val blockFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(30, Color.red(pal.accentCyanInt), Color.green(pal.accentCyanInt), Color.blue(pal.accentCyanInt))
            style = Paint.Style.FILL
        }
        private val blockStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(140, Color.red(pal.accentCyanInt), Color.green(pal.accentCyanInt), Color.blue(pal.accentCyanInt))
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
        }
        private val selectedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(92, Color.red(pal.accentGreenInt), Color.green(pal.accentGreenInt), Color.blue(pal.accentGreenInt))
            style = Paint.Style.FILL
        }
        private val selectedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.accentGreenInt
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
        }
        private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.accentGreenInt
            style = Paint.Style.FILL
        }
        private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.onAccentInt
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = dp(10.5f)
        }

        private val dstRect = Rect()
        private val drawRect = RectF()
        private val padPx = dp(3f)
        private val hitPadPx = dp(6f)

        private var downX = 0f
        private var downY = 0f
        private var lastTapBlockId = -1
        private var lastTapTime = 0L

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            getLocationOnScreen(locOnScreen)
            val offX = locOnScreen[0].toFloat()
            val offY = locOnScreen[1].toFloat()

            // Optional frozen bitmap when in OCR mode
            if (backgroundBitmap != null && !backgroundBitmap.isRecycled) {
                dstRect.set(
                    (-offX).toInt(),
                    (-offY).toInt(),
                    (backgroundBitmap.width - offX).toInt(),
                    (backgroundBitmap.height - offY).toInt()
                )
                canvas.drawBitmap(backgroundBitmap, null, dstRect, null)
            }

            // Subtle dim so the real screen stays crystal clear
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

            if (blocks.isEmpty()) return

            for (block in blocks) {
                val orderIdx = selectedBlockIds.indexOf(block.id)
                val isSelected = orderIdx >= 0

                drawRect.set(
                    block.bounds.left - offX - padPx,
                    block.bounds.top - offY - padPx,
                    block.bounds.right - offX + padPx,
                    block.bounds.bottom - offY + padPx
                )

                canvas.drawRoundRect(
                    drawRect,
                    dp(2f),
                    dp(2f),
                    if (isSelected) selectedFillPaint else blockFillPaint
                )
                canvas.drawRoundRect(
                    drawRect,
                    dp(2f),
                    dp(2f),
                    if (isSelected) selectedStrokePaint else blockStrokePaint
                )

                if (isSelected) {
                    val tag = "[${orderIdx + 1}]"
                    val tw = badgeTextPaint.measureText(tag)
                    val bh = dp(16f)
                    val bx = drawRect.left.coerceAtLeast(dp(4f))
                    val by = (drawRect.top - bh).coerceAtLeast(topInsetPx + dp(52f))
                    canvas.drawRect(bx, by, bx + tw + dp(6f), by + bh, badgeBgPaint)
                    canvas.drawText(tag, bx + dp(3f), by + bh - dp(3.5f), badgeTextPaint)
                }
            }
        }

        private fun findBestBlockAt(screenX: Float, screenY: Float): GrabBlock? {
            var best: GrabBlock? = null
            var bestArea = Float.MAX_VALUE
            for (b in blocks) {
                if (screenX in (b.bounds.left - hitPadPx)..(b.bounds.right + hitPadPx) &&
                    screenY in (b.bounds.top - hitPadPx)..(b.bounds.bottom + hitPadPx)
                ) {
                    val area = b.bounds.width() * b.bounds.height()
                    if (area < bestArea) {
                        bestArea = area
                        best = b
                    }
                }
            }
            return best
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (isEditingInline || isListMode) return false
            getLocationOnScreen(locOnScreen)
            val screenX = event.x + locOnScreen[0]
            val screenY = event.y + locOnScreen[1]

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val moveDist = abs(event.x - downX) + abs(event.y - downY)
                    if (moveDist > dp(24f)) return true

                    val hit = findBestBlockAt(screenX, screenY)
                    if (hit == null) {
                        if (selectedBlockIds.isNotEmpty() || customEditedText != null) {
                            clearSelection()
                        }
                        return true
                    }

                    val now = System.currentTimeMillis()
                    if (hit.id == lastTapBlockId && now - lastTapTime < 340L) {
                        // Double-tap -> immediately open native EditText word/substring picker!
                        lastTapBlockId = -1
                        if (hit.id !in selectedBlockIds) {
                            selectedBlockIds.add(hit.id)
                            invalidate()
                            refreshBottomDock()
                        }
                        openInlineEditor(hit.text)
                    } else {
                        lastTapBlockId = hit.id
                        lastTapTime = now
                        toggleBlockSelection(hit.id)
                    }
                    return true
                }
            }
            return true
        }
    }
}
