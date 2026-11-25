package io.github.selclip.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.selclip.actions.ActionExecutor
import io.github.selclip.actions.QrCodeGenerator
import io.github.selclip.core.PopupMode
import io.github.selclip.core.TextTransforms
import io.github.selclip.data.PrefsStore
import io.github.selclip.data.ThemePalette
import io.github.selclip.data.ToolSpec

/**
 * Renders all expandable drawers inside the native Material dropdown card
 * (Clipboard History removed; Search Engine drawer displays auto-fetched favicons).
 */
object DrawerRenderer {

    private fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
    private fun dpF(context: Context, v: Float): Float = v * context.resources.displayMetrics.density

    interface Callbacks {
        fun onBackToBar()
        fun onRerender()
        fun onRelayout()
        fun onExecuteTool(tool: ToolSpec)
        fun onApplyResult(newText: String)
        fun onCopyPlain(text: String, toastMsg: String?)
        fun onOpenUrl(url: String)
        fun onMutePackage(pkg: String)
        fun isModeStillActive(mode: PopupMode): Boolean
    }

    fun render(
        context: Context,
        container: LinearLayout,
        mode: PopupMode,
        selectedText: String,
        activePackage: String,
        pal: ThemePalette,
        executor: ActionExecutor,
        cb: Callbacks
    ) {
        container.removeAllViews()
        when (mode) {
            PopupMode.Bar -> Unit
            PopupMode.MoreTools -> renderMoreTools(context, container, activePackage, pal, cb)
            PopupMode.WebSearch -> renderWebSearch(context, container, pal, cb)
            PopupMode.YouTube -> renderYouTube(context, container, pal, cb)
            PopupMode.Markdown -> renderMarkdown(context, container, selectedText, pal, cb)
            PopupMode.RegexPipe -> renderPipe(context, container, selectedText, pal, cb)
            is PopupMode.CustomFolder -> renderCustomFolder(context, container, mode.folderName, pal, cb)
            PopupMode.QrCode -> renderQr(context, container, selectedText, pal, cb)
            PopupMode.Magnet -> renderMagnet(context, container, selectedText, pal, cb)
            PopupMode.Define -> renderDefine(context, container, selectedText, pal, executor, cb)
            PopupMode.Translate -> renderTranslate(context, container, selectedText, pal, executor, cb)
            PopupMode.Extract -> renderExtract(context, container, selectedText, pal, cb)
        }
    }

    private fun renderMoreTools(
        context: Context,
        container: LinearLayout,
        activePackage: String,
        pal: ThemePalette,
        cb: Callbacks
    ) {
        val tools = PrefsStore.getVisibleToolsForDrawer(context)
        var i = 0
        while (i < tools.size) {
            val t1 = tools[i]
            val t2 = tools.getOrNull(i + 1)
            if (t2 != null) {
                container.addView(makeTwoColRow(context, t1, t2, pal, cb))
                i += 2
            } else {
                container.addView(makeRow(context, t1.label, pal) {
                    PrefsStore.recordUse(context, t1.id)
                    cb.onExecuteTool(t1)
                })
                i += 1
            }
        }

        if (activePackage.isNotBlank() && activePackage != context.packageName) {
            container.addView(makeDivider(context, pal))
            val shortPkg = activePackage.substringAfterLast('.')
            container.addView(makeRow(context, "Mute popup in $shortPkg", pal, pal.fgMutedInt) {
                cb.onMutePackage(activePackage)
            })
        }
    }

    private fun renderWebSearch(context: Context, container: LinearLayout, pal: ThemePalette, cb: Callbacks) {
        val defaultId = PrefsStore.getDefaultSearchEngineId(context)
        container.addView(makeBackHeader(context, "Search Engines (hold to set default)", pal, cb))
        val iconPx = dp(context, 18)

        for (engine in PrefsStore.loadWebSearchTools(context)) {
            val isDefault = engine.id == defaultId
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(context, 44)
                setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
                background = pressedBg(context, pal)
                setOnClickListener {
                    runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    PrefsStore.recordUse(context, "web_search_folder")
                    cb.onExecuteTool(engine)
                }
                setOnLongClickListener {
                    runCatching { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
                    PrefsStore.setDefaultSearchEngineId(context, engine.id)
                    Toast.makeText(context, "Default search: ${engine.label}", Toast.LENGTH_SHORT).show()
                    cb.onRerender()
                    true
                }
            }

            val icon = ActionIconFactory.createFaviconOrBadge(
                context = context,
                urlTemplate = engine.customTarget,
                fallbackText = engine.glyph,
                tintColor = if (isDefault) pal.accentPrimaryInt else pal.fgMutedInt,
                sizePx = iconPx,
                onFaviconLoaded = { cb.onRerender() }
            )
            row.addView(ImageView(context).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(iconPx, iconPx).apply { marginEnd = dp(context, 10) }
            })

            row.addView(TextView(context).apply {
                text = if (isDefault) "${engine.label}  ★" else engine.label
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTextColor(if (isDefault) pal.accentPrimaryInt else pal.fgPrimaryInt)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            container.addView(row)
        }
    }

    private fun renderYouTube(context: Context, container: LinearLayout, pal: ThemePalette, cb: Callbacks) {
        container.addView(makeBackHeader(context, "YouTube Filters", pal, cb))
        for (yt in PrefsStore.loadYouTubeTools(context)) {
            container.addView(makeRow(context, "${yt.glyph}  ·  ${yt.label}", pal) {
                PrefsStore.recordUse(context, "youtube_folder")
                cb.onExecuteTool(yt)
            })
        }
    }

    private fun renderMarkdown(
        context: Context,
        container: LinearLayout,
        selectedText: String,
        pal: ThemePalette,
        cb: Callbacks
    ) {
        container.addView(makeBackHeader(context, "Markdown", pal, cb))
        for (md in PrefsStore.loadMarkdownTools(context)) {
            container.addView(makeRow(context, md.label, pal) {
                val out = if (md.customTarget != null) {
                    TextTransforms.executeCustomTargetTransform(md.customTarget, selectedText)
                } else {
                    TextTransforms.applyMarkdownTool(md.id, selectedText)
                }
                cb.onApplyResult(out)
            })
        }
    }

    private fun renderPipe(
        context: Context,
        container: LinearLayout,
        selectedText: String,
        pal: ThemePalette,
        cb: Callbacks
    ) {
        container.addView(makeBackHeader(context, "Regex & Pipe", pal, cb))
        for (pt in PrefsStore.loadPipeTools(context)) {
            container.addView(makeRow(context, pt.label, pal) {
                val out = if (pt.customTarget != null) {
                    TextTransforms.executeCustomTargetTransform(pt.customTarget, selectedText)
                } else {
                    TextTransforms.applyPipeTool(pt.id, selectedText)
                }
                cb.onApplyResult(out)
            })
        }
    }

    private fun renderCustomFolder(
        context: Context,
        container: LinearLayout,
        folderName: String,
        pal: ThemePalette,
        cb: Callbacks
    ) {
        container.addView(makeBackHeader(context, folderName, pal, cb))
        val items = PrefsStore.loadCustomFolderTools(context, folderName)
        if (items.isEmpty()) {
            container.addView(makeNote(context, "Folder is empty.", pal))
            return
        }
        items.forEach { item ->
            container.addView(makeRow(context, item.label, pal) { cb.onExecuteTool(item) })
        }
    }

    private fun renderDefine(
        context: Context,
        container: LinearLayout,
        selectedText: String,
        pal: ThemePalette,
        executor: ActionExecutor,
        cb: Callbacks
    ) {
        container.addView(makeBackHeader(context, "Definition", pal, cb))
        val raw = selectedText.trim()
        val firstWord = raw.split(TextTransforms.whitespaceRegex).firstOrNull()
            ?.trim { !it.isLetterOrDigit() && it != '-' }
            ?.ifBlank { raw.take(32) } ?: raw.take(32)

        val defView = TextView(context).apply {
            text = "Looking up \"$firstWord\"…"
            typeface = Typeface.SANS_SERIF
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(pal.fgPrimaryInt)
            setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 8))
            setLineSpacing(0f, 1.25f)
        }
        val copyBtn = makeRow(context, "Copy definition", pal, pal.accentPrimaryInt) {
            cb.onCopyPlain(defView.text.toString(), "Definition copied")
        }.apply { visibility = View.GONE }

        container.addView(defView)
        container.addView(copyBtn)

        executor.networkClient.lookupDefinition(firstWord, raw) { res ->
            if (cb.isModeStillActive(PopupMode.Define)) {
                defView.text = res
                copyBtn.visibility = View.VISIBLE
                cb.onRelayout()
            }
        }
    }

    private fun renderTranslate(
        context: Context,
        container: LinearLayout,
        selectedText: String,
        pal: ThemePalette,
        executor: ActionExecutor,
        cb: Callbacks
    ) {
        container.addView(makeBackHeader(context, "Translate → English", pal, cb))
        val trView = TextView(context).apply {
            text = "Translating…"
            typeface = Typeface.SANS_SERIF
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(pal.fgPrimaryInt)
            setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 8))
            setLineSpacing(0f, 1.25f)
        }
        val applyBtn = makeRow(context, "Copy / Replace text", pal, pal.accentPrimaryInt) {
            cb.onApplyResult(trView.text.toString())
        }.apply { visibility = View.GONE }

        container.addView(trView)
        container.addView(applyBtn)

        executor.networkClient.lookupTranslation(selectedText.trim().take(400)) { res ->
            if (cb.isModeStillActive(PopupMode.Translate)) {
                trView.text = res
                applyBtn.visibility = View.VISIBLE
                cb.onRelayout()
            }
        }
    }

    private fun renderQr(
        context: Context,
        container: LinearLayout,
        selectedText: String,
        pal: ThemePalette,
        cb: Callbacks
    ) {
        container.addView(makeBackHeader(context, "QR Code", pal, cb))
        val bmp = QrCodeGenerator.generate(selectedText.trim().take(1200), dp(context, 176))
        if (bmp != null) {
            container.addView(ImageView(context).apply {
                setImageBitmap(bmp)
                layoutParams = LinearLayout.LayoutParams(dp(context, 176), dp(context, 176)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(context, 8)
                    bottomMargin = dp(context, 8)
                }
            })
        } else {
            container.addView(makeNote(context, "Could not encode QR.", pal))
        }
    }

    private fun renderMagnet(
        context: Context,
        container: LinearLayout,
        selectedText: String,
        pal: ThemePalette,
        cb: Callbacks
    ) {
        container.addView(makeBackHeader(context, "Magnet Link", pal, cb))
        val hash = TextTransforms.extractTorrentHash(selectedText)
        val magnetUri = when {
            hash != null -> TextTransforms.buildMagnetUri(hash)
            selectedText.trim().startsWith("magnet:?", true) -> selectedText.trim()
            else -> "magnet:?xt=urn:btih:${selectedText.trim().replace(TextTransforms.whitespaceRegex, "")}"
        }
        container.addView(TextView(context).apply {
            text = magnetUri
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(pal.fgPrimaryInt)
            background = GradientDrawable().apply {
                setColor(pal.bgSurfaceInt)
                cornerRadius = dpF(context, 8f)
            }
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        })
        container.addView(makeRow(context, "Copy magnet URI", pal, pal.accentPrimaryInt) {
            cb.onCopyPlain(magnetUri, "Magnet copied")
            cb.onBackToBar()
        })
        container.addView(makeRow(context, "Open in torrent app", pal, pal.accentSuccessInt) {
            cb.onCopyPlain(magnetUri, "Opening magnet…")
            cb.onOpenUrl(magnetUri)
        })
    }

    private fun renderExtract(
        context: Context,
        container: LinearLayout,
        selectedText: String,
        pal: ThemePalette,
        cb: Callbacks
    ) {
        container.addView(makeBackHeader(context, "Extracted Entities", pal, cb))
        val items = TextTransforms.extractEntities(selectedText, limit = 12)
        if (items.isEmpty()) {
            container.addView(makeNote(context, "No URLs, emails, phones, or tags found.", pal))
            return
        }
        items.forEach { entity ->
            container.addView(makeRow(context, "${entity.type}  ·  ${entity.value.take(32)}", pal) {
                cb.onApplyResult(entity.value)
            })
        }
    }

    private fun makeTwoColRow(
        context: Context,
        t1: ToolSpec,
        t2: ToolSpec,
        pal: ThemePalette,
        cb: Callbacks
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun cell(tool: ToolSpec): TextView = TextView(context).apply {
            text = tool.label
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.2f)
            setTextColor(pal.fgPrimaryInt)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            minimumHeight = dp(context, 44)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            background = pressedBg(context, pal)
            setOnClickListener {
                runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                PrefsStore.recordUse(context, tool.id)
                cb.onExecuteTool(tool)
            }
        }
        row.addView(cell(t1))
        row.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 1), dp(context, 22))
            setBackgroundColor(pal.dividerInt)
        })
        row.addView(cell(t2))
        return row
    }

    private fun makeBackHeader(context: Context, title: String, pal: ThemePalette, cb: Callbacks): View =
        makeRow(context, "←  $title", pal, pal.accentPrimaryInt, bold = true) {
            cb.onBackToBar()
        }

    private fun makeRow(
        context: Context,
        label: String,
        pal: ThemePalette,
        colorInt: Int = pal.fgPrimaryInt,
        bold: Boolean = false,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        setTextColor(colorInt)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        minimumHeight = dp(context, 44)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
        background = pressedBg(context, pal)
        setOnClickListener {
            runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
            onClick()
        }
    }

    private fun makeNote(context: Context, msg: String, pal: ThemePalette): TextView =
        TextView(context).apply {
            text = msg
            typeface = Typeface.SANS_SERIF
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(pal.fgMutedInt)
            setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
        }

    private fun makeDivider(context: Context, pal: ThemePalette): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1)).apply {
                topMargin = dp(context, 4)
                bottomMargin = dp(context, 4)
            }
            setBackgroundColor(pal.dividerInt)
        }

    private fun pressedBg(context: Context, pal: ThemePalette): StateListDrawable =
        StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                GradientDrawable().apply {
                    setColor(pal.bgPressedInt)
                    cornerRadius = dpF(context, 10f)
                }
            )
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }
}
