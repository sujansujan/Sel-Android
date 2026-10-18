package io.github.selclip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Automatic Favicon Loader + Memory/Disk Cache for built-in and user-added Web Search Engines.
 * Works for Google, DuckDuckGo, Bing, Brave, YouTube, Wikipedia, GitHub, StackOverflow,
 * and ANY future web engine URL added via the app's Rules/Search Engine builder.
 */
object FaviconManager {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(3)
    private val inFlight = Collections.synchronizedSet(HashSet<String>())

    private val memoryCache = object : LruCache<String, Bitmap>(64) {}

    fun extractDomain(rawUrlOrTemplate: String?): String? {
        if (rawUrlOrTemplate.isNullOrBlank()) return null
        val trimmed = rawUrlOrTemplate.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        val sanitized = trimmed.replace("{text}", "test")
        val host = runCatching { Uri.parse(sanitized).host?.lowercase() }.getOrNull()
        if (!host.isNullOrBlank()) return host
        return runCatching {
            val withoutScheme = trimmed.substringAfter("://").substringBefore("/")
            withoutScheme.lowercase().trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun isWebEngineTool(tool: ToolSpec): Boolean {
        if (tool.id == "youtube_folder" || tool.id == "web_search_folder") return false
        return extractDomain(tool.customTarget) != null
    }

    fun bindFavicon(context: Context, imageView: ImageView, rawUrl: String, fallbackGlyph: String, sizePx: Int) {
        val domain = extractDomain(rawUrl)
        if (domain == null) {
            imageView.setImageBitmap(createMonogramBitmap(fallbackGlyph, "#141414", "#00FF66", sizePx))
            return
        }

        imageView.tag = domain

        // 1. Instant Memory Cache hit
        val memBmp = memoryCache.get(domain)
        if (memBmp != null) {
            imageView.setImageBitmap(memBmp)
            return
        }

        // 2. Instant Disk Cache hit
        val diskFile = getDiskCacheFile(context, domain)
        if (diskFile.exists() && diskFile.length() > 64) {
            val diskBmp = runCatching { BitmapFactory.decodeFile(diskFile.absolutePath) }.getOrNull()
            if (diskBmp != null) {
                val rounded = makeRoundedIcon(diskBmp, sizePx)
                memoryCache.put(domain, rounded)
                imageView.setImageBitmap(rounded)
                return
            }
        }

        // 3. Show crisp branded placeholder immediately while fetching real favicon in background
        imageView.setImageBitmap(createBrandFallbackBitmap(domain, fallbackGlyph, sizePx))

        // 4. Async download & cache
        fetchFaviconAsync(context.applicationContext, domain, sizePx) { fetched ->
            if (fetched != null && imageView.tag == domain) {
                imageView.setImageBitmap(fetched)
            }
        }
    }

    fun prefetchAllForTools(context: Context) {
        val appCtx = context.applicationContext
        val dp18 = (18 * appCtx.resources.displayMetrics.density).toInt().coerceAtLeast(36)
        val urls = mutableSetOf<String>()

        ToolPrefs.webSearchSubTools.forEach { it.customTarget?.let(urls::add) }
        ToolPrefs.youtubeSubTools.forEach { it.customTarget?.let(urls::add) }
        ToolPrefs.loadAllTools(appCtx).forEach { it.customTarget?.let(urls::add) }
        ToolPrefs.loadWebSearchTools(appCtx).forEach { it.customTarget?.let(urls::add) }
        ToolPrefs.loadYoutubeTools(appCtx).forEach { it.customTarget?.let(urls::add) }

        val rulesFile = ToolPrefs.ensureRulesFile(appCtx)
        if (rulesFile.exists()) {
            rulesFile.readLines().forEach { line ->
                val parts = line.split("|").map { it.trim() }
                if (parts.size >= 3 && (parts[2].startsWith("http://", true) || parts[2].startsWith("https://", true))) {
                    urls.add(parts[2])
                }
            }
        }

        for (u in urls) {
            val domain = extractDomain(u) ?: continue
            if (memoryCache.get(domain) != null) continue
            val f = getDiskCacheFile(appCtx, domain)
            if (f.exists() && f.length() > 64) continue
            fetchFaviconAsync(appCtx, domain, dp18) {}
        }
    }

    private fun fetchFaviconAsync(
        context: Context,
        domain: String,
        sizePx: Int,
        onComplete: (Bitmap?) -> Unit
    ) {
        if (!inFlight.add(domain)) return
        executor.execute {
            var resultBmp: Bitmap? = null
            try {
                val baseDomain = domain.removePrefix("www.")
                val candidates = listOf(
                    "https://www.google.com/s2/favicons?domain=$domain&sz=64",
                    "https://icons.duckduckgo.com/ip3/$domain.ico",
                    "https://www.google.com/s2/favicons?domain=$baseDomain&sz=64",
                    "https://$domain/favicon.ico"
                )
                for (endpoint in candidates) {
                    val bmp = downloadBitmap(endpoint)
                    if (bmp != null && bmp.width >= 8 && bmp.height >= 8) {
                        val rounded = makeRoundedIcon(bmp, sizePx)
                        resultBmp = rounded
                        memoryCache.put(domain, rounded)
                        saveToDiskCache(context, domain, rounded)
                        break
                    }
                }
            } finally {
                inFlight.remove(domain)
                val finalBmp = resultBmp
                mainHandler.post { onComplete(finalBmp) }
            }
        }
    }

    private fun downloadBitmap(urlStr: String): Bitmap? {
        return runCatching {
            var currentUrl = urlStr
            var redirects = 0
            while (redirects < 4) {
                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android; sel-android/1.9)")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: break
                    currentUrl = if (loc.startsWith("http")) loc else URL(URL(currentUrl), loc).toString()
                    redirects++
                    continue
                }
                if (code == 200) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (bytes.isNotEmpty()) {
                        return@runCatching BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
                break
            }
            null
        }.getOrNull()
    }

    private fun getDiskCacheFile(context: Context, domain: String): File {
        val dir = File(context.cacheDir, "favicons")
        if (!dir.exists()) dir.mkdirs()
        val safe = domain.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(dir, "$safe.png")
    }

    private fun saveToDiskCache(context: Context, domain: String, bitmap: Bitmap) {
        runCatching {
            val file = getDiskCacheFile(context, domain)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }

    private fun makeRoundedIcon(src: Bitmap, sizePx: Int): Bitmap {
        val target = sizePx.coerceAtLeast(32)
        val scaled = Bitmap.createScaledBitmap(src, target, target, true)
        val out = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, target, target)
        val rectF = RectF(rect)
        val radius = target * 0.20f

        canvas.drawRoundRect(rectF, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(scaled, rect, rect, paint)
        return out
    }

    private fun createBrandFallbackBitmap(domain: String, fallbackGlyph: String, sizePx: Int): Bitmap {
        val d = domain.lowercase()
        return when {
            d.contains("google.") -> createMonogramBitmap("G", "#4285F4", "#FFFFFF", sizePx)
            d.contains("duckduckgo.") -> createMonogramBitmap("D", "#DE5833", "#FFFFFF", sizePx)
            d.contains("bing.") -> createMonogramBitmap("b", "#008373", "#FFFFFF", sizePx)
            d.contains("brave.") -> createMonogramBitmap("B", "#FB542B", "#FFFFFF", sizePx)
            d.contains("youtube.") || d.contains("youtu.be") -> createMonogramBitmap("▶", "#FF0000", "#FFFFFF", sizePx)
            d.contains("wikipedia.") -> createMonogramBitmap("W", "#E8E8E8", "#111111", sizePx)
            d.contains("github.") -> createMonogramBitmap("GH", "#24292E", "#FFFFFF", sizePx)
            d.contains("stackoverflow.") -> createMonogramBitmap("SO", "#F48024", "#FFFFFF", sizePx)
            d.contains("reddit.") -> createMonogramBitmap("R", "#FF4500", "#FFFFFF", sizePx)
            d.contains("perplexity.") -> createMonogramBitmap("P", "#20808D", "#FFFFFF", sizePx)
            d.contains("kagi.") -> createMonogramBitmap("K", "#FFB319", "#111111", sizePx)
            d.contains("startpage.") -> createMonogramBitmap("S", "#6573FF", "#FFFFFF", sizePx)
            else -> {
                val letter = d.removePrefix("www.").removePrefix("search.").firstOrNull()?.uppercaseChar()?.toString()
                    ?: fallbackGlyph.take(1)
                createMonogramBitmap(letter, "#1F2922", "#00FF66", sizePx)
            }
        }
    }

    private fun createMonogramBitmap(letter: String, bgHex: String, fgHex: String, sizePx: Int): Bitmap {
        val target = sizePx.coerceAtLeast(32)
        val out = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(bgHex)
            style = Paint.Style.FILL
        }
        val radius = target * 0.22f
        canvas.drawRoundRect(RectF(0f, 0f, target.toFloat(), target.toFloat()), radius, radius, bgPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(fgHex)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = if (letter.length > 1) target * 0.46f else target * 0.58f
            textAlign = Paint.Align.CENTER
        }
        val y = target / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(letter.take(2), target / 2f, y, textPaint)
        return out
    }
}
