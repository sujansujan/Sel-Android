package io.github.selclip.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
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
 * Renders compact, tight-fitting native Android overflow menus (WRAP_CONTENT width, zero full-screen
 * stretching!) — using only as much screen area as the items actually need.
 */
object DrawerRenderer {

    private fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
    private fun dpF(context: Context, v: Float): Float = v * context.resources.displayMetrics.density

    interface Callbacks {
        fun onBackToBar()
        fun onDismiss()
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
        container.addView(makeBackHeader(context, "More", pal, cb))
        val tools = PrefsStore.getVisibleToolsForDrawer(context)
        val iconPx = dp(context, 18)

        for (tool in tools) {
            val icon = ActionIconFactory.createForTool(context, tool, pal.fgPrimaryInt, iconPx) { cb.onRerender() }
            container.addView(makeCompactIconRow(context, tool.label, icon, pal) {
                PrefsStore.recordUse(context, tool.id)
                cb.onExecuteTool(tool)
            })
        }

        if (activePackage.isNotBlank() && activePackage != context.packageName) {
            container.addView(makeDivider(context, pal))
            val shortPkg = activePackage.substringAfterLast('.')
            val muteIcon = ActionIconFactory.createMuteIcon(pal.fgMutedInt, iconPx)
            container.addView(makeCompactIconRow(context, "Mute in $shortPkg", muteIcon, pal, pal.fgMutedInt) {
                cb.onMutePackage(activePackage)
            })
        }
    }

    private fun renderWebSearch(context: Context, container: LinearLayout, pal: ThemePalette, cb: Callbacks) {
        val defaultId = PrefsStore.getDefaultSearchEngineId(context)
        container.addView(makeBackHeader(context, "Search (hold = default)", pal, cb))
        val iconPx = dp(context, 18)

        for (engine in PrefsStore.loadWebSearchTools(context)) {
            val isDefault = engine.id == defaultId
            val icon = ActionIconFactory.createFaviconOrBadge(
                context = context,
                urlTemplate = engine.customTarget,
                fallbackText = engine.glyph,
                tintColor = if (isDefault) pal.accentPrimaryInt else pal.fgMutedInt,
                sizePx = iconPx,
                onFaviconLoaded = { cb.onRerender() }
            )
            val row = makeCompactIconRow(
                context = context,
                label = if (isDefault) "${engine.label} ★" else engine.label,
                icon = icon,
                pal = pal,
                colorInt = if (isDefault) pal.accentPrimaryInt else pal.fgPrimaryInt
            ) {
                PrefsStore.recordUse(context, "web_search_folder")
                cb.onExecuteTool(engine)
            }
            row.setOnLongClickListener {
                runCatching { row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
                PrefsStore.setDefaultSearchEngineId(context, engine.id)
                Toast.makeText(context, "Default search: ${engine.label}", Toast.LENGTH_SHORT).show()
                cb.onRerender()
                true
            }
            container.addView(row)
        }
    }

    private fun renderYouTube(context: Context, container: LinearLayout, pal: ThemePalette, cb: Callbacks) {
        container.addView(makeBackHeader(context, "YouTube", pal, cb))
        val iconPx = dp(context, 18)
        for (yt in PrefsStore.loadYouTubeTools(context)) {
            val icon = ActionIconFactory.createForTool(context, yt, pal.fgPrimaryInt, iconPx) { cb.onRerender() }
            container.addView(makeCompactIconRow(context, "${yt.glyph} · ${yt.label}", icon, pal) {
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
        val iconPx = dp(context, 18)
        for (md in PrefsStore.loadMarkdownTools(context)) {
            val icon = ActionIconFactory.createForTool(context, md, pal.fgPrimaryInt, iconPx)
            container.addView(makeCompactIconRow(context, md.label, icon, pal) {
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
        val iconPx = dp(context, 18)
        for (pt in PrefsStore.loadPipeTools(context)) {
            val icon = ActionIconFactory.createForTool(context, pt, pal.fgPrimaryInt, iconPx)
            container.addView(makeCompactIconRow(context, pt.label, icon, pal) {
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
        val iconPx = dp(context, 18)
        items.forEach { item ->
            val icon = ActionIconFactory.createForTool(context, item, pal.fgPrimaryInt, iconPx) { cb.onRerender() }
            container.addView(makeCompactIconRow(context, item.label, icon, pal) { cb.onExecuteTool(item) })
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
        container.addView(makeBackHeader(context, "Define", pal, cb))
        val raw = selectedText.trim()
        val firstWord = raw.split(TextTransforms.whitespaceRegex).firstOrNull()
            ?.trim { !it.isLetterOrDigit() && it != '-' }
            ?.ifBlank { raw.take(32) } ?: raw.take(32)

        val defView = TextView(context).apply {
            text = "Looking up \"$firstWord\"…"
            typeface = Typeface.SANS_SERIF
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(pal.fgPrimaryInt)
            setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 6))
            setLineSpacing(0f, 1.22f)
        }
        val copyBtn = makeTextRow(context, "Copy definition", pal, pal.accentPrimaryInt) {
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
        container.addView(makeBackHeader(context, "Translate → EN", pal, cb))
        val trView = TextView(context).apply {
            text = "Translating…"
            typeface = Typeface.SANS_SERIF
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(pal.fgPrimaryInt)
            setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 6))
            setLineSpacing(0f, 1.22f)
        }
        val applyBtn = makeTextRow(context, "Copy / Replace", pal, pal.accentPrimaryInt) {
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
        val bmp = QrCodeGenerator.generate(selectedText.trim().take(1200), dp(context, 164))
        if (bmp != null) {
            container.addView(ImageView(context).apply {
                setImageBitmap(bmp)
                layoutParams = LinearLayout.LayoutParams(dp(context, 164), dp(context, 164)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(context, 6)
                    bottomMargin = dp(context, 6)
                    marginStart = dp(context, 8)
                    marginEnd = dp(context, 8)
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
        container.addView(makeBackHeader(context, "Magnet", pal, cb))
        val hash = TextTransforms.extractTorrentHash(selectedText)
        val magnetUri = when {
            hash != null -> TextTransforms.buildMagnetUri(hash)
            selectedText.trim().startsWith("magnet:?", true) -> selectedText.trim()
            else -> "magnet:?xt=urn:btih:${selectedText.trim().replace(TextTransforms.whitespaceRegex, "")}"
        }
        container.addView(TextView(context).apply {
            text = magnetUri
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.8f)
            setTextColor(pal.fgPrimaryInt)
            background = GradientDrawable().apply {
                setColor(pal.bgSurfaceInt)
                cornerRadius = dpF(context, 8f)
            }
            setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6))
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        })
        container.addView(makeTextRow(context, "Copy magnet URI", pal, pal.accentPrimaryInt) {
            cb.onCopyPlain(magnetUri, "Magnet copied")
            cb.onBackToBar()
        })
        container.addView(makeTextRow(context, "Open in torrent app", pal, pal.accentSuccessInt) {
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
        container.addView(makeBackHeader(context, "Entities", pal, cb))
        val items = TextTransforms.extractEntities(selectedText, limit = 12)
        if (items.isEmpty()) {
            container.addView(makeNote(context, "No links or contacts found.", pal))
            return
        }
        items.forEach { entity ->
            container.addView(makeTextRow(context, "${entity.type} · ${entity.value.take(26)}", pal) {
                cb.onApplyResult(entity.value)
            })
        }
    }

    /**
     * Compact native Android menu row (WRAP_CONTENT width — NEVER stretches full-screen!).
     */
    private fun makeCompactIconRow(
        context: Context,
        label: String,
        icon: Drawable,
        pal: ThemePalette,
        colorInt: Int = pal.fgPrimaryInt,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(context, 40)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        setPadding(dp(context, 10), dp(context, 7), dp(context, 12), dp(context, 7))
        background = pressedBg(context, pal)
        setOnClickListener {
            runCatching { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
            onClick()
        }
        val iconPx = dp(context, 18)
        addView(ImageView(context).apply {
            setImageDrawable(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(iconPx, iconPx).apply { marginEnd = dp(context, 10) }
        })
        addView(TextView(context).apply {
            text = label
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(colorInt)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        })
    }

    private fun makeBackHeader(context: Context, title: String, pal: ThemePalette, cb: Callbacks): View {
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(
            makeTextRow(context, "←  $title", pal, pal.accentPrimaryInt, bold = true) {
                cb.onBackToBar()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        topRow.addView(
            TextView(context).apply {
                text = "×"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(pal.fgMutedInt)
                gravity = Gravity.CENTER
                minWidth = dp(context, 36)
                minimumHeight = dp(context, 36)
                background = pressedBg(context, pal)
                setOnClickListener { cb.onDismiss() }
            }
        )
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(topRow)
            addView(makeDivider(context, pal))
        }
    }

    private fun makeTextRow(
        context: Context,
        label: String,
        pal: ThemePalette,
        colorInt: Int = pal.fgPrimaryInt,
        bold: Boolean = false,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.2f)
        setTextColor(colorInt)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        minimumHeight = dp(context, 38)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 10), dp(context, 6), dp(context, 12), dp(context, 6))
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
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
        }

    private fun makeDivider(context: Context, pal: ThemePalette): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1)).apply {
                topMargin = dp(context, 2)
                bottomMargin = dp(context, 2)
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
