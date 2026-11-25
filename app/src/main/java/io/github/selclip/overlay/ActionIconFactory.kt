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

/**
 * Generates razor-sharp native Android vector drawables for built-in actions and
 * cleanly clipped rounded favicons for created search portals (zero external icon libraries).
 */
object ActionIconFactory {

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
        return VectorGlyphDrawable("custom_monogram", fallbackText.take(2).uppercase(), tintColor, sizePx)
    }

    fun createOverflowIcon(tintColor: Int, sizePx: Int): Drawable =
        VectorGlyphDrawable("overflow_dots", "⋮", tintColor, sizePx)

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

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class VectorGlyphDrawable(
        private val actionId: String,
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
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        override fun getIntrinsicWidth(): Int = sizePx
        override fun getIntrinsicHeight(): Int = sizePx

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat().takeIf { it > 0f } ?: sizePx.toFloat()
            val h = bounds.height().toFloat().takeIf { it > 0f } ?: sizePx.toFloat()
            val cx = bounds.left + w / 2f
            val cy = bounds.top + h / 2f
            val u = minOf(w, h) / 24f // 24x24 virtual Material icon grid
            strokePaint.strokeWidth = (2.0f * u).coerceAtLeast(1.5f)

            fun px(x: Float) = bounds.left + x * u
            fun py(y: Float) = bounds.top + y * u

            when (actionId) {
                "overflow_dots" -> {
                    val r = 1.9f * u
                    canvas.drawCircle(cx, py(6f), r, fillPaint)
                    canvas.drawCircle(cx, py(12f), r, fillPaint)
                    canvas.drawCircle(cx, py(18f), r, fillPaint)
                }

                "default_search", "web_search_folder" -> {
                    canvas.drawCircle(px(10.5f), py(10.5f), 6.2f * u, strokePaint)
                    canvas.drawLine(px(15.2f), py(15.2f), px(20.5f), py(20.5f), strokePaint)
                }

                "youtube_direct", "youtube_folder" -> {
                    val box = RectF(px(2.5f), py(5.5f), px(21.5f), py(18.5f))
                    canvas.drawRoundRect(box, 3.5f * u, 3.5f * u, strokePaint)
                    val tri = Path().apply {
                        moveTo(px(10f), py(9f))
                        lineTo(px(15.8f), py(12f))
                        lineTo(px(10f), py(15f))
                        close()
                    }
                    canvas.drawPath(tri, fillPaint)
                }

                "phone_call" -> {
                    val p = Path().apply {
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
                    canvas.drawPath(p, strokePaint)
                }

                "phone_sms" -> {
                    val box = RectF(px(3.5f), py(4.5f), px(20.5f), py(16.5f))
                    canvas.drawRoundRect(box, 3f * u, 3f * u, strokePaint)
                    val tail = Path().apply {
                        moveTo(px(7.5f), py(16.5f))
                        lineTo(px(6f), py(20.5f))
                        lineTo(px(11.5f), py(16.5f))
                    }
                    canvas.drawPath(tail, strokePaint)
                }

                "phone_wa", "phone_w4b" -> {
                    canvas.drawCircle(cx, py(11.5f), 8.2f * u, strokePaint)
                    drawCenteredText(canvas, if (actionId == "phone_w4b") "B" else "W", cx, cy, 10.5f * u)
                }

                "open_url", "open_magnet" -> {
                    val box = Path().apply {
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
                    canvas.drawPath(box, strokePaint)
                }

                "clean_url", "clean_text" -> {
                    val star = Path().apply {
                        moveTo(cx, py(3.5f))
                        quadTo(cx, cy, px(20.5f), cy)
                        quadTo(cx, cy, cx, py(20.5f))
                        quadTo(cx, cy, px(3.5f), cy)
                        quadTo(cx, cy, cx, py(3.5f))
                    }
                    canvas.drawPath(star, strokePaint)
                }

                "qr" -> {
                    canvas.drawRect(px(4f), py(4f), px(10f), py(10f), strokePaint)
                    canvas.drawRect(px(14f), py(4f), px(20f), py(10f), strokePaint)
                    canvas.drawRect(px(4f), py(14f), px(10f), py(20f), strokePaint)
                    canvas.drawRect(px(14.5f), py(14.5f), px(17.5f), py(17.5f), fillPaint)
                    canvas.drawRect(px(18f), py(18f), px(20.5f), py(20.5f), fillPaint)
                }

                "email_send" -> {
                    val box = RectF(px(3.5f), py(5.5f), px(20.5f), py(18.5f))
                    canvas.drawRoundRect(box, 2.2f * u, 2.2f * u, strokePaint)
                    val flap = Path().apply {
                        moveTo(px(4.5f), py(7f))
                        lineTo(cx, py(13f))
                        lineTo(px(19.5f), py(7f))
                    }
                    canvas.drawPath(flap, strokePaint)
                }

                "define" -> {
                    val book = Path().apply {
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
                    canvas.drawPath(book, strokePaint)
                }

                "translate" -> {
                    canvas.drawCircle(cx, cy, 8.2f * u, strokePaint)
                    canvas.drawOval(RectF(px(8.5f), py(3.8f), px(15.5f), py(20.2f)), strokePaint)
                    canvas.drawLine(px(4.2f), cy, px(19.8f), cy, strokePaint)
                }

                "magnet" -> {
                    val mag = Path().apply {
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
                    canvas.drawPath(mag, strokePaint)
                }

                "extract" -> {
                    canvas.drawRoundRect(RectF(px(3.5f), py(8.5f), px(12.5f), py(15.5f)), 3.5f * u, 3.5f * u, strokePaint)
                    canvas.drawRoundRect(RectF(px(11.5f), py(8.5f), px(20.5f), py(15.5f)), 3.5f * u, 3.5f * u, strokePaint)
                }

                "sort" -> {
                    canvas.drawLine(px(4.5f), py(7f), px(19.5f), py(7f), strokePaint)
                    canvas.drawLine(px(4.5f), py(12f), px(15f), py(12f), strokePaint)
                    canvas.drawLine(px(4.5f), py(17f), px(10.5f), py(17f), strokePaint)
                }

                "speak" -> {
                    val spk = Path().apply {
                        moveTo(px(4.5f), py(9.5f))
                        lineTo(px(8.5f), py(9.5f))
                        lineTo(px(13f), py(5.5f))
                        lineTo(px(13f), py(18.5f))
                        lineTo(px(8.5f), py(14.5f))
                        lineTo(px(4.5f), py(14.5f))
                        close()
                    }
                    canvas.drawPath(spk, strokePaint)
                    canvas.drawArc(RectF(px(13f), py(8.5f), px(17.5f), py(15.5f)), -50f, 100f, false, strokePaint)
                    canvas.drawArc(RectF(px(14.5f), py(6f), px(20.5f), py(18f)), -50f, 100f, false, strokePaint)
                }

                "undo" -> {
                    val p = Path().apply {
                        moveTo(px(9f), py(7f))
                        lineTo(px(4.5f), py(11f))
                        lineTo(px(9f), py(15f))
                        moveTo(px(5.5f), py(11f))
                        lineTo(px(14.5f), py(11f))
                        quadTo(px(19.5f), py(11f), px(19.5f), py(15.5f))
                        quadTo(px(19.5f), py(18.5f), px(15.5f), py(18.5f))
                    }
                    canvas.drawPath(p, strokePaint)
                }

                "pipe_json_pretty", "regex_pipe" -> drawCenteredText(canvas, "{ }", cx, cy, 12f * u)
                "pipe_strip_html" -> drawCenteredText(canvas, "</>", cx, cy, 10.5f * u)
                "case" -> drawCenteredText(canvas, "Aa", cx, cy, 12.5f * u)
                "markdown_folder" -> drawCenteredText(canvas, "MD", cx, cy, 11f * u)
                else -> {
                    val mono = label.trim().take(2).uppercase().ifEmpty { "•" }
                    drawCenteredText(canvas, mono, cx, cy, 11.5f * u)
                }
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
