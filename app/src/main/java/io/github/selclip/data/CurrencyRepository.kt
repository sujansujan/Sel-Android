package io.github.selclip.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Strictly lazy, 24-hour disk-cached FX rate provider with built-in offline fallback rates.
 *
 * - Allocates ZERO threads at idle until a currency amount is actually selected.
 * - Never blocks the UI thread: returns cached/fallback rates in O(1) immediately and triggers
 *   a silent background refresh only when the disk cache is older than 24 hours.
 */
object CurrencyRepository {

    private const val REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private const val RATE_API_URL = "https://open.er-api.com/v6/latest/USD"

    val supportedHomeCurrencies = listOf(
        "NPR", "USD", "INR", "EUR", "GBP", "AUD", "CAD", "JPY", "AED", "SGD"
    )

    // Reasonable offline fallback rates (units per 1 USD) so conversions work immediately offline
    val defaultRatesPerUsd: Map<String, Double> = mapOf(
        "USD" to 1.0,
        "NPR" to 135.5,
        "INR" to 84.7,
        "EUR" to 0.92,
        "GBP" to 0.79,
        "AUD" to 1.53,
        "CAD" to 1.39,
        "JPY" to 153.0,
        "AED" to 3.6725,
        "SGD" to 1.34,
        "CNY" to 7.24,
        "KRW" to 1390.0,
        "CHF" to 0.88,
        "QAR" to 3.64,
        "SAR" to 3.75,
        "MYR" to 4.45
    )

    @Volatile
    private var cachedRates: Map<String, Double>? = null

    @Volatile
    private var cachedTimestampMs: Long = 0L

    private val refreshing = AtomicBoolean(false)

    @Volatile
    private var executor: ExecutorService? = null

    private fun getOrCreateExecutor(): ExecutorService {
        executor?.let { if (!it.isShutdown) return it }
        synchronized(this) {
            executor?.let { if (!it.isShutdown) return it }
            return Executors.newSingleThreadExecutor { r ->
                Thread(r, "sel-fx").apply { isDaemon = true }
            }.also { executor = it }
        }
    }

    private fun cacheFile(context: Context): File =
        File(context.applicationContext.cacheDir, "fx_rates_v1.json")

    /**
     * Non-blocking O(1) rate lookup for the popup hot path.
     * Loads disk cache lazily once and schedules a background refresh if stale (> 24h).
     */
    fun getRatesOrTriggerRefresh(context: Context, onRefreshed: (() -> Unit)? = null): Map<String, Double> {
        val now = System.currentTimeMillis()
        val mem = cachedRates
        if (mem != null) {
            if (now - cachedTimestampMs > REFRESH_INTERVAL_MS) {
                refreshAsync(context, onRefreshed)
            }
            return mem
        }

        // First call after process start: return fallback immediately, hydrate disk + network off-thread
        val appCtx = context.applicationContext
        if (refreshing.compareAndSet(false, true)) {
            getOrCreateExecutor().execute {
                try {
                    val file = cacheFile(appCtx)
                    var loadedFromDisk = false
                    if (file.exists() && file.length() > 16L) {
                        val parsed = parseDiskCache(file)
                        if (parsed != null) {
                            cachedRates = parsed.first
                            cachedTimestampMs = parsed.second
                            loadedFromDisk = true
                            if (onRefreshed != null) runCatching { onRefreshed() }
                        }
                    }
                    if (!loadedFromDisk || (System.currentTimeMillis() - cachedTimestampMs) > REFRESH_INTERVAL_MS) {
                        val fresh = fetchRemoteRates()
                        if (fresh != null) {
                            val ts = System.currentTimeMillis()
                            cachedRates = fresh
                            cachedTimestampMs = ts
                            writeDiskCache(file, fresh, ts)
                            if (onRefreshed != null) runCatching { onRefreshed() }
                        }
                    }
                } finally {
                    refreshing.set(false)
                }
            }
        }
        return defaultRatesPerUsd
    }

    private fun refreshAsync(context: Context, onRefreshed: (() -> Unit)?) {
        if (!refreshing.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        getOrCreateExecutor().execute {
            try {
                val fresh = fetchRemoteRates()
                if (fresh != null) {
                    val ts = System.currentTimeMillis()
                    cachedRates = fresh
                    cachedTimestampMs = ts
                    writeDiskCache(cacheFile(appCtx), fresh, ts)
                    if (onRefreshed != null) runCatching { onRefreshed() }
                }
            } finally {
                refreshing.set(false)
            }
        }
    }

    private fun parseDiskCache(file: File): Pair<Map<String, Double>, Long>? = runCatching {
        val obj = JSONObject(file.readText())
        val ts = obj.optLong("ts", 0L)
        val ratesObj = obj.optJSONObject("rates") ?: return null
        val map = LinkedHashMap<String, Double>(defaultRatesPerUsd)
        val keys = ratesObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = ratesObj.optDouble(k, -1.0)
            if (v > 0.0 && !v.isNaN() && !v.isInfinite()) {
                map[k.uppercase()] = v
            }
        }
        map.toMap() to ts
    }.getOrNull()

    private fun writeDiskCache(file: File, rates: Map<String, Double>, ts: Long) {
        runCatching {
            val ratesObj = JSONObject()
            rates.forEach { (k, v) -> ratesObj.put(k, v) }
            val root = JSONObject().apply {
                put("ts", ts)
                put("rates", ratesObj)
            }
            file.writeText(root.toString())
        }
    }

    private fun fetchRemoteRates(): Map<String, Double>? = runCatching {
        val conn = (URL(RATE_API_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4500
            readTimeout = 4500
            requestMethod = "GET"
            setRequestProperty("User-Agent", "sel-android/5.0")
        }
        try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val ratesObj = JSONObject(body).optJSONObject("rates") ?: return null
            val map = LinkedHashMap<String, Double>(defaultRatesPerUsd)
            for (code in defaultRatesPerUsd.keys) {
                val v = ratesObj.optDouble(code, -1.0)
                if (v > 0.0 && !v.isNaN() && !v.isInfinite()) {
                    map[code] = v
                }
            }
            map.toMap()
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
