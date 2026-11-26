package io.github.selclip.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import io.github.selclip.data.FaviconRepository
import io.github.selclip.data.PopAction
import io.github.selclip.data.ToolSpec

/**
 * Generates razor-sharp dedicated Android vector drawables for EVERY built-in action/tool
 * (including a clear A/文 Translate glyph — no confusing globe!) plus cleanly rounded
 * auto-fetched site favicons for every search portal.
 */
object ActionIconFactory {

    private val boldMediumTypeface: Typeface by lazy {
        Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    fun createForAction(
        context: Context,
        action: PopAction,
        tintColor: Int,
        sizePx: Int,
        onFaviconLoaded: (() -> Unit)? = null
    ): Drawable {
        if (!action.faviconUrl.isNullOrBlank()) {
            val bmp = FaviconRepository.getOrFetch(context, action.faviconUrl) {
                onFaviconLoaded?.invoke()
            }
            if (bmp != null && !bmp.isRecycled) {
                return RoundedBitmapDrawable(bmp, sizePx)
            }
        }
        return VectorGlyphDrawable(action.id, action.label, tintColor, sizePx)
    }

    fun createForTool(
        context: Context,
        tool: ToolSpec,
        tintColor: Int,
        sizePx: Int,
        onFaviconLoaded: (() -> Unit)? = null
    ): Drawable {
        val url = tool.customTarget?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        if (url != null) {
            val bmp = FaviconRepository.getOrFetch(context, url) { onFaviconLoaded?.invoke() }
            if (bmp != null && !bmp.isRecycled) {
                return RoundedBitmapDrawable(bmp, sizePx)
            }
        }
        val iconKey = when {
            tool.folderName != null && tool.customTarget == null -> "folder_custom"
            tool.id == "web_search_folder" -> "default_search"
            tool.id == "youtube_folder" -> "youtube_direct"
            tool.id.startsWith("yt_") -> "youtube_direct"
            else -> tool.id
        }
        return VectorGlyphDrawable(iconKey, tool.glyph, tintColor, sizePx)
    }

    fun createFaviconOrBadge(
        context: Context,
        urlTemplate: String?,
        fallbackText: String,
        tintColor: Int,
        sizePx: Int,
        onFaviconLoaded: (() -> Unit)? = null
    ): Drawable {
        val bmp = FaviconRepository.getOrFetch(context, urlTemplate) {
            onFaviconLoaded?.invoke()
        }
        if (bmp != null && !bmp.isRecycled) {
            return RoundedBitmapDrawable(bmp, sizePx)
        }
        return VectorGlyphDrawable("default_search", fallbackText.take(2).uppercase(), tintColor, sizePx)
    }

    fun createOverflowIcon(tintColor: Int, sizePx: Int): Drawable =
        VectorGlyphDrawable("overflow_dots", "⋮", tintColor, sizePx)

    fun createMuteIcon(tintColor: Int, sizePx: Int): Drawable =
        VectorGlyphDrawable("mute_app", "×", tintColor, sizePx)

    fun createShareIcon(tintColor: Int, sizePx: Int): Drawable =
        VectorGlyphDrawable("share_text", "↗", tintColor, sizePx)

    private class RoundedBitmapDrawable(
        private val bitmap: Bitmap,
        private val sizePx: Int
    ) : Drawable() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val clipPath = Path()
        private val rectF = RectF()
        private val srcRect = Rect(0, 0, bitmap.width, bitmap.height)

        override fun getIntrinsicWidth(): Int = sizePx
        override fun getIntrinsicHeight(): Int = sizePx

        override fun draw(canvas: Canvas) {
            if (bitmap.isRecycled) return
            rectF.set(bounds)
            val radius = rectF.width() * 0.22f
            clipPath.reset()
            clipPath.addRoundRect(rectF, radius, radius, Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawBitmap(bitmap, srcRect, rectF, paint)
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class VectorGlyphDrawable(
        private val iconKey: String,
        private val label: String,
        private val colorInt: Int,
        private val sizePx: Int
    ) : Drawable() {

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorInt
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorInt
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorInt
            typeface = boldMediumTypeface
            textAlign = Paint.Align.CENTER
        }
        // Reusable scratch geometry: zero per-frame allocations during hardware fade-in!
        private val tmpPath = Path()
        private val tmpRectF = RectF()

        override fun getIntrinsicWidth(): Int = sizePx
        override fun getIntrinsicHeight(): Int = sizePx

        private fun resetRect(l: Float, t: Float, r: Float, b: Float): RectF {
            tmpRectF.set(l, t, r, b)
            return tmpRectF
        }

        private inline fun drawPathReuse(canvas: Canvas, paint: Paint, build: Path.() -> Unit) {
            tmpPath.reset()
            tmpPath.build()
            canvas.drawPath(tmpPath, paint)
        }

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat().takeIf { it > 0f } ?: sizePx.toFloat()
            val h = bounds.height().toFloat().takeIf { it > 0f } ?: sizePx.toFloat()
            val cx = bounds.left + w / 2f
            val cy = bounds.top + h / 2f
            val u = minOf(w, h) / 24f // 24x24 Material icon grid
            strokePaint.strokeWidth = (1.9f * u).coerceAtLeast(1.4f)

            fun px(x: Float) = bounds.left + x * u
            fun py(y: Float) = bounds.top + y * u

            when (iconKey) {
                "overflow_dots" -> {
                    val r = 1.85f * u
                    canvas.drawCircle(cx, py(6f), r, fillPaint)
                    canvas.drawCircle(cx, py(12f), r, fillPaint)
                    canvas.drawCircle(cx, py(18f), r, fillPaint)
                }

                "default_search", "web_search_folder" -> {
                    canvas.drawCircle(px(10.5f), py(10.5f), 6.2f * u, strokePaint)
                    canvas.drawLine(px(15.2f), py(15.2f), px(20.5f), py(20.5f), strokePaint)
                }

                "youtube_direct", "youtube_folder" -> {
                    canvas.drawRoundRect(resetRect(px(2.5f), py(5.5f), px(21.5f), py(18.5f)), 3.6f * u, 3.6f * u, strokePaint)
                    drawPathReuse(canvas, fillPaint) {
                        moveTo(px(10f), py(9f))
                        lineTo(px(15.8f), py(12f))
                        lineTo(px(10f), py(15f))
                        close()
                    }
                }

                // Crisp A / 文 Translate icon (immediately recognizable, never confused with internet globe!)
                "translate" -> {
                    // Geometric 文 (top-left)
                    canvas.drawLine(px(7.5f), py(3.5f), px(7.5f), py(5.5f), strokePaint)
                    canvas.drawLine(px(3.2f), py(6f), px(11.8f), py(6f), strokePaint)
                    // Left-to-right curves of 文 + crisp capital A (bottom-right)
                    drawPathReuse(canvas, strokePaint) {
                        moveTo(px(10.2f), py(6.2f))
                        quadTo(px(8.5f), py(11.5f), px(3.8f), py(14f))
                        moveTo(px(5.2f), py(7.8f))
                        quadTo(px(7.5f), py(11.5f), px(11.4f), py(13.6f))
                        moveTo(px(12.2f), py(20.5f))
                        lineTo(px(16.2f), py(10.2f))
                        lineTo(px(20.2f), py(20.5f))
                        moveTo(px(13.6f), py(17.2f))
                        lineTo(px(18.8f), py(17.2f))
                    }
                }

                "define" -> {
                    drawPathReuse(canvas, strokePaint) {
                        moveTo(cx, py(6.5f))
                        quadTo(px(8f), py(4.5f), px(4f), py(6f))
                        lineTo(px(4f), py(18f))
                        quadTo(px(8f), py(16.5f), cx, py(18.5f))
                        quadTo(px(16f), py(16.5f), px(20f), py(18f))
                        lineTo(px(20f), py(6f))
                        quadTo(px(16f), py(4.5f), cx, py(6.5f))
                        moveTo(cx, py(6.5f))
                        lineTo(cx, py(18.5f))
                    }
                }

                "phone_call" -> {
                    drawPathReuse(canvas, strokePaint) {
                        moveTo(px(6.5f), py(4.5f))
                        lineTo(px(9.5f), py(4.5f))
                        lineTo(px(10.5f), py(8.5f))
                        lineTo(px(8.5f), py(10f))
                        quadTo(px(10.5f), py(13.8f), px(14f), py(15.5f))
                        lineTo(px(15.5f), py(13.5f))
                        lineTo(px(19.5f), py(14.5f))
                        lineTo(px(19.5f), py(17.5f))
                        quadTo(px(19.5f), py(20f), px(16.5f), py(19.5f))
                        quadTo(px(5.5f), py(16.5f), px(4.5f), py(7.5f))
                        quadTo(px(4.5f), py(4.5f), px(6.5f), py(4.5f))
                    }
                }

                "phone_sms" -> {
                    canvas.drawRoundRect(resetRect(px(3.5f), py(4.5f), px(20.5f), py(16.2f)), 3f * u, 3f * u, strokePaint)
                    drawPathReuse(canvas, strokePaint) {
                        moveTo(px(7.5f), py(16.2f))
                        lineTo(px(6f), py(20.2f))
                        lineTo(px(11.5f), py(16.2f))
                    }
                    canvas.drawLine(px(7.5f), py(9f), px(16.5f), py(9f), strokePaint)
                    canvas.drawLine(px(7.5f), py(12.2f), px(13.5f), py(12.2f), strokePaint)
                }

                "phone_wa", "phone_w4b" -> {
                    canvas.drawCircle(cx, py(11.5f), 8.2f * u, strokePaint)
                    drawPathReuse(canvas, strokePaint) {
                        moveTo(px(6.2f), py(17.2f))
                        lineTo(px(4.5f), py(20.5f))
                        lineTo(px(8.8f), py(19f))
                    }
                    drawCenteredText(canvas, if (iconKey == "phone_w4b") "B" else "W", cx, py(11.5f), 9.5f * u)
                }

                "open_url", "open_magnet" -> {
                    drawPathReuse(canvas, strokePaint) {
                        moveTo(px(13f), py(5f))
                        lineTo(px(19f), py(5f))
                        lineTo(px(19f), py(11f))
                        moveTo(px(19f), py(5f))
                        lineTo(px(10.5f), py(13.5f))
                        moveTo(px(11f), py(7f))
                        lineTo(px(5.5f), py(7f))
                        lineTo(px(5.5f), py(18.5f))
                        lineTo(px(17f), py(18.5f))
                        lineTo(px(17f), py(13f))
                    }
                }

                "clean_url" -> {
                    canvas.drawRoundRect(resetRect(px(3.5f), py(8.5f), px(12f), py(15.5f)), 3.2f * u, 3.2f * u, strokePaint)
                    canvas.drawRoundRect(resetRect(px(12f), py(8.5f), px(20.5f), py(15.5f)), 3.2f * u, 3.2f * u, strokePaint)
                    canvas.drawLine(px(6f), py(19f), px(18f), py(5f), strokePaint)
                }

                "clean_text" -> {
                    drawSparkle(canvas, px(10.5f), py(12.5f), 6.2f * u)
                    drawSparkle(canvas, px(17.5f), py(6.5f), 2.8f * u)
                    drawSparkle(canvas, px(18f), py(17.5f), 2.2f * u)
                }

                "qr" -> {
                    canvas.drawRect(px(4f), py(4f), px(10f), py(10f), strokePaint)
                    canvas.drawRect(px(14f), py(4f), px(20f), py(10f), strokePaint)
                    canvas.drawRect(px(4f), py(14f), px(10f), py(20f), strokePaint)
                    canvas.drawRect(px(14.2f), py(14.2f), px(17.2f), py(17.2f), fillPaint)
                    canvas.drawRect(px(17.8f), py(17.8f), px(20.5f), py(20.5f), fillPaint)
                }

                "email_send" -> {
                    canvas.drawRoundRect(resetRect(px(3.5f), py(5.5f), px(20.5f), py(18.5f)), 2.2f * u, 2.2f * u, strokePaint)
                    drawPathReuse(canvas, strokePaint) {
                        moveTo(px(4.5f), py(7f))
                        lineTo(cx, py(13f))
                        lineTo(px(19.5f), py(7f))
                    }
                }

                "magnet" -> {
                    drawPathReuse(canvas, strokePaint) {
                        moveTo(px(6f), py(5f))
                        lineTo(px(6f), py(13f))
                        quadTo(px(6f), py(19.5f), cx, py(19.5f))
                        quadTo(px(18f), py(19.5f), px(18f), py(13f))
                        lineTo(px(18f), py(5f))
                        moveTo(px(4.5f), py(8.5f))
                        lineTo(px(7.5f), py(8.5f))
                        moveTo(px(16.5f), py(8.5f))
                        lineTo(px(19.5f), py(8.5f))
                    }
                }

                "extract", "md_link" -> {
                    canvas.drawRoundRect(resetRect(px(3.5f), py(8.5f), px(13f), py(15.5f)), 3.5f * u, 3.5f * u, strokePaint)
                    canvas.drawRoundRect(resetRect(px(11f), py(8.5f), px(20.5f), py(15.5f)), 3.5f * u, 3.5f * u, strokePaint)
                }

                "sort" -> {
                    canvas.drawLine(px(4f), py(7f), px(13.5f), py(7f), strokePaint)
                    canvas.drawLine(px(4f), py(12f), px(11.5f), py(12f), strokePaint)
                    canvas.drawLine(px(4f), py(17f), px(9f), py(17f), strokePaint)
                    canvas.drawLine(px(17.5f), py(6.5f), px(17.5f), py(18f), strokePaint)
                    canvas.drawLine(px(14.8f), py(15.2f), px(17.5f), py(18f), strokePaint)
                    canvas.drawLine(px(20.2f), py(15.2f), px(17.5f), py(18f), strokePaint)
                }

                "speak" -> {
                    drawPathReuse(canvas, strokePaint) {
                        moveTo(px(4f), py(9.5f))
                        lineTo(px(8f), py(9.5f))
                        lineTo(px(12.5f), py(5.5f))
                        lineTo(px(12.5f), py(18.5f))
                        lineTo(px(8f), py(14.5f))
                        lineTo(px(4f), py(14.5f))
                        close()
                    }
                    canvas.drawArc(resetRect(px(13f), py(8.5f), px(17.2f), py(15.5f)), -50f, 100f, false, strokePaint)
                    canvas.drawArc(resetRect(px(14.5f), py(6f), px(20.5f), py(18f)), -50f, 100f, false, strokePaint)
                }

                "markdown_folder" -> {
                    canvas.drawRoundRect(resetRect(px(2.5f), py(5.5f), px(21.5f), py(18.5f)), 2.8f * u, 2.8f * u, strokePaint)
                    drawPathReuse(canvas, strokePaint) {
                        moveTo(px(5.5f), py(15f))
                        lineTo(px(5.5f), py(9f))
                        lineTo(px(8.2f), py(12.2f))
                        lineTo(px(11f), py(9f))
                        lineTo(px(11f), py(15f))
                    }
                    canvas.drawLine(px(16.5f), py(8.8f), px(16.5f), py(15f), strokePaint)
                    canvas.drawLine(px(14.2f), py(12.8f), px(16.5f), py(15f), strokePaint)
                    canvas.drawLine(px(18.8f), py(12.8f), px(16.5f), py(15f), strokePaint)
                }

                "regex_pipe" -> {
                    canvas.drawRoundRect(resetRect(px(3f), py(4.5f), px(21f), py(19.5f)), 3f * u, 3f * u, strokePaint)
                    drawPathReuse(canvas, strokePaint) {
                        moveTo(px(6.8f), py(9f))
                        lineTo(px(10.5f), py(12f))
                        lineTo(px(6.8f), py(15f))
                    }
                    canvas.drawLine(px(12.5f), py(15f), px(17.2f), py(15f), strokePaint)
                }

                "folder_custom" -> {
                    drawPathReuse(canvas, strokePaint) {
                        moveTo(px(3.5f), py(7f))
                        lineTo(px(3.5f), py(18.5f))
                        lineTo(px(20.5f), py(18.5f))
                        lineTo(px(20.5f), py(8.5f))
                        lineTo(px(11.5f), py(8.5f))
                        lineTo(px(9.5f), py(6f))
                        lineTo(px(3.5f), py(6f))
                        close()
                    }
                }

                "mute_app" -> {
                    canvas.drawCircle(cx, cy, 8.2f * u, strokePaint)
                    canvas.drawLine(px(6.2f), py(6.2f), px(17.8f), py(17.8f), strokePaint)
                }

                "undo" -> {
                    drawPathReuse(canvas, strokePaint) {
                        moveTo(px(9f), py(7f))
                        lineTo(px(4.5f), py(11f))
                        lineTo(px(9f), py(15f))
                        moveTo(px(5.5f), py(11f))
                        lineTo(px(14.5f), py(11f))
                        quadTo(px(19.5f), py(11f), px(19.5f), py(15.5f))
                        quadTo(px(19.5f), py(18.5f), px(15.5f), py(18.5f))
                    }
                }

                "case" -> drawCenteredText(canvas, "Aa", cx, cy, 13f * u)
                "pipe_json_pretty", "pipe_json_min" -> drawCenteredText(canvas, "{ }", cx, cy, 12.5f * u)
                "pipe_strip_html", "md_code" -> drawCenteredText(canvas, "</>", cx, cy, 10.5f * u)
                "pipe_url_enc", "pipe_url_dec" -> drawCenteredText(canvas, "%", cx, cy, 14f * u)
                "pipe_b64_enc" -> drawCenteredText(canvas, "64", cx, cy, 12.5f * u)
                "pipe_sha256" -> drawCenteredText(canvas, "#", cx, cy, 15f * u)
                "pipe_rot13" -> {
                    canvas.drawArc(resetRect(px(5f), py(5f), px(19f), py(19f)), 20f, 290f, false, strokePaint)
                    canvas.drawLine(px(18.5f), py(8f), px(18.5f), py(12.5f), strokePaint)
                    canvas.drawLine(px(14.5f), py(12.2f), px(18.5f), py(12.5f), strokePaint)
                }
                "pipe_num_lines", "md_bullet" -> {
                    canvas.drawCircle(px(5.5f), py(7.5f), 1.3f * u, fillPaint)
                    canvas.drawCircle(px(5.5f), py(12f), 1.3f * u, fillPaint)
                    canvas.drawCircle(px(5.5f), py(16.5f), 1.3f * u, fillPaint)
                    canvas.drawLine(px(9.5f), py(7.5f), px(19.5f), py(7.5f), strokePaint)
                    canvas.drawLine(px(9.5f), py(12f), px(19.5f), py(12f), strokePaint)
                    canvas.drawLine(px(9.5f), py(16.5f), px(19.5f), py(16.5f), strokePaint)
                }
                "md_task" -> {
                    canvas.drawRoundRect(resetRect(px(4f), py(4.5f), px(20f), py(19.5f)), 2.5f * u, 2.5f * u, strokePaint)
                    drawPathReuse(canvas, strokePaint) {
                        moveTo(px(7.8f), py(12.2f))
                        lineTo(px(10.8f), py(15.2f))
                        lineTo(px(16.5f), py(9.2f))
                    }
                }
                "md_bold" -> drawCenteredText(canvas, "B", cx, cy, 15f * u)
                "md_italic" -> drawCenteredText(canvas, "I", cx, cy, 15f * u)
                "md_quote" -> drawCenteredText(canvas, "”", cx, cy + 2f * u, 18f * u)
                "md_strike" -> {
                    drawCenteredText(canvas, "S", cx, cy, 14f * u)
                    canvas.drawLine(px(5f), cy, px(19f), cy, strokePaint)
                }
                "md_h2" -> drawCenteredText(canvas, "H2", cx, cy, 12f * u)
                "md_spoiler" -> drawCenteredText(canvas, "||", cx, cy, 13f * u)
                "md_table" -> {
                    canvas.drawRoundRect(resetRect(px(4f), py(5f), px(20f), py(19f)), 2f * u, 2f * u, strokePaint)
                    canvas.drawLine(px(4f), py(10.5f), px(20f), py(10.5f), strokePaint)
                    canvas.drawLine(cx, py(5f), cx, py(19f), strokePaint)
                }
                "share_text" -> {
                    canvas.drawCircle(px(17.5f), py(6f), 2.2f * u, strokePaint)
                    canvas.drawCircle(px(6.5f), py(12f), 2.2f * u, strokePaint)
                    canvas.drawCircle(px(17.5f), py(18f), 2.2f * u, strokePaint)
                    canvas.drawLine(px(8.5f), py(10.9f), px(15.5f), py(7.1f), strokePaint)
                    canvas.drawLine(px(8.5f), py(13.1f), px(15.5f), py(16.9f), strokePaint)
                }
                else -> {
                    val mono = label.trim().take(2).uppercase().ifEmpty { "•" }
                    drawCenteredText(canvas, mono, cx, cy, 11.5f * u)
                }
            }
        }

        private fun drawSparkle(canvas: Canvas, sx: Float, sy: Float, r: Float) {
            drawPathReuse(canvas, strokePaint) {
                moveTo(sx, sy - r)
                quadTo(sx, sy, sx + r, sy)
                quadTo(sx, sy, sx, sy + r)
                quadTo(sx, sy, sx - r, sy)
                quadTo(sx, sy, sx, sy - r)
            }
        }

        private fun drawCenteredText(canvas: Canvas, txt: String, cx: Float, cy: Float, textSizePx: Float) {
            textPaint.textSize = textSizePx
            val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(txt, cx, baseline, textPaint)
        }

        override fun setAlpha(alpha: Int) {
            strokePaint.alpha = alpha
            fillPaint.alpha = alpha
            textPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            strokePaint.colorFilter = colorFilter
            fillPaint.colorFilter = colorFilter
            textPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
