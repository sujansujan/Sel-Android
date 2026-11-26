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
    private val inFlightCallbacks = HashMap<String, MutableList<(Bitmap) -> Unit>>()

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
     * Strictly non-blocking on the main thread (O(1) RAM lookup only — ZERO disk I/O or PNG
     * decoding on the UI thread!). If not in RAM, checks disk cache then network on a single
     * lazy daemon thread and invokes [onLoaded] on the main thread when ready.
     */
    fun getOrFetch(
        context: Context,
        urlTemplate: String?,
        onLoaded: ((Bitmap) -> Unit)? = null
    ): Bitmap? {
        val host = extractHost(urlTemplate) ?: return null
        memCache.get(host)?.takeIf { !it.isRecycled }?.let { return it }

        val shouldStart = synchronized(inFlightCallbacks) {
            // Re-check memCache inside lock to prevent races with just-finished downloads
            memCache.get(host)?.takeIf { !it.isRecycled }?.let { return it }
            val existing = inFlightCallbacks[host]
            if (existing != null) {
                if (onLoaded != null) existing.add(onLoaded)
                false
            } else {
                val waiters = mutableListOf<(Bitmap) -> Unit>()
                if (onLoaded != null) waiters.add(onLoaded)
                inFlightCallbacks[host] = waiters
                true
            }
        }
        if (!shouldStart) return null

        val appCtx = context.applicationContext
        getOrCreateExecutor().execute {
            var resolved: Bitmap? = null
            try {
                // 1. Check disk cache off the main thread first
                val file = cacheFile(appCtx, host)
                val fromDisk = if (file.exists() && file.length() > 32L) {
                    runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                } else null

                val bmp = if (fromDisk != null && fromDisk.width > 0 && fromDisk.height > 0) {
                    fromDisk
                } else {
                    downloadFavicon(host)?.also { downloaded ->
                        runCatching {
                            FileOutputStream(file).use { out ->
                                downloaded.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                        }
                    }
                }

                if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                    memCache.put(host, bmp)
                    resolved = bmp
                }
            } finally {
                val waiters = synchronized(inFlightCallbacks) {
                    inFlightCallbacks.remove(host)
                }
                val finalBmp = resolved
                if (finalBmp != null && !waiters.isNullOrEmpty()) {
                    mainHandler.post {
                        waiters.forEach { cb -> runCatching { cb(finalBmp) } }
                    }
                }
            }
        }
        return null
    }

    /**
     * Pre-warms the in-memory favicon cache off the main thread for the active bar portals
     * so the very first popup renders with crisp favicons immediately at 0ms main-thread cost.
     */
    fun warmMemoryCacheAsync(context: Context, urls: List<String>) {
        val hosts = urls.mapNotNull { extractHost(it) }.distinct()
        if (hosts.isEmpty()) return
        val appCtx = context.applicationContext
        getOrCreateExecutor().execute {
            for (host in hosts) {
                if (memCache.get(host)?.takeIf { !it.isRecycled } != null) continue
                val file = cacheFile(appCtx, host)
                val fromDisk = if (file.exists() && file.length() > 32L) {
                    runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                } else null
                if (fromDisk != null && fromDisk.width > 0 && fromDisk.height > 0) {
                    memCache.put(host, fromDisk)
                }
            }
        }
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
