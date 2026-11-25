package io.github.selclip.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Strictly lazy, disk-cached & memory-cached favicon repository for search portals.
 *
 * - Allocates ZERO background threads until an uncached host favicon is actually requested.
 * - Persists downloaded 64x64 PNGs to `cacheDir/favicons/<host>.png` for instant 0ms offline reuse.
 * - Automatically fetches favicons whenever a search portal is created or rendered.
 */
object FaviconRepository {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val memCache = LruCache<String, Bitmap>(48)
    private val inFlight = Collections.synchronizedSet(HashSet<String>())

    @Volatile
    private var executor: ExecutorService? = null

    private fun getOrCreateExecutor(): ExecutorService {
        executor?.let { if (!it.isShutdown) return it }
        synchronized(this) {
            executor?.let { if (!it.isShutdown) return it }
            return Executors.newSingleThreadExecutor { r ->
                Thread(r, "sel-favicon").apply { isDaemon = true }
            }.also { executor = it }
        }
    }

    fun extractHost(urlTemplate: String?): String? {
        if (urlTemplate.isNullOrBlank()) return null
        val normalized = urlTemplate.trim().replace("{text}", "q")
        val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.trim().orEmpty()
        return host.takeIf { it.contains('.') }
    }

    private fun cacheFile(context: Context, host: String): File {
        val dir = File(context.applicationContext.cacheDir, "favicons")
        if (!dir.exists()) dir.mkdirs()
        val safeName = host.replace(Regex("[^a-z0-9._-]"), "_")
        return File(dir, "$safeName.png")
    }

    /**
     * Returns a cached favicon immediately if available in memory or on disk; otherwise returns null
     * and lazily fetches it in the background, invoking [onLoaded] on the main thread when ready.
     */
    fun getOrFetch(
        context: Context,
        urlTemplate: String?,
        onLoaded: ((Bitmap) -> Unit)? = null
    ): Bitmap? {
        val host = extractHost(urlTemplate) ?: return null
        memCache.get(host)?.takeIf { !it.isRecycled }?.let { return it }

        val file = cacheFile(context, host)
        if (file.exists() && file.length() > 32L) {
            val decoded = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            if (decoded != null && decoded.width > 0 && decoded.height > 0) {
                memCache.put(host, decoded)
                return decoded
            }
        }

        if (!inFlight.add(host)) return null
        val appCtx = context.applicationContext
        getOrCreateExecutor().execute {
            try {
                val bmp = downloadFavicon(host)
                if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                    memCache.put(host, bmp)
                    runCatching {
                        FileOutputStream(cacheFile(appCtx, host)).use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                    }
                    if (onLoaded != null) {
                        mainHandler.post { onLoaded(bmp) }
                    }
                }
            } finally {
                inFlight.remove(host)
            }
        }
        return null
    }

    fun prefetchAll(context: Context, urls: List<String>) {
        urls.forEach { url -> getOrFetch(context, url, onLoaded = null) }
    }

    private fun downloadFavicon(host: String): Bitmap? {
        val candidates = listOf(
            "https://www.google.com/s2/favicons?domain=${Uri.encode(host)}&sz=64",
            "https://icons.duckduckgo.com/ip3/${Uri.encode(host)}.ico",
            "https://$host/favicon.ico"
        )
        for (rawUrl in candidates) {
            val bmp = runCatching {
                val conn = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Sel/4.1")
                }
                try {
                    if (conn.responseCode in 200..299) {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        if (bytes.size in 40..262_144) {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } else null
                    } else null
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            if (bmp != null && bmp.width >= 12 && bmp.height >= 12) return bmp
        }
        return null
    }
}
