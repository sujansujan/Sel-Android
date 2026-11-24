package io.github.selclip

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded, cached, cancellable network lookup helper for inline Define Word & Translate.
 * Eliminates raw unbounded Thread creation, leaks, and duplicate network fetches.
 */
class NetworkTools {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val requestSeq = AtomicLong(0L)

    private val defineCache = LruCache<String, String>(64)
    private val translateCache = LruCache<String, String>(64)

    fun cancelPending() {
        requestSeq.incrementAndGet()
    }

    fun shutdown() {
        cancelPending()
        runCatching { executor.shutdownNow() }
    }

    fun lookupDefinition(word: String, fullSelection: String, onResult: (String) -> Unit) {
        val cacheKey = "${word.lowercase()}|${fullSelection.trim().take(60).lowercase()}"
        val cached = defineCache.get(cacheKey)
        if (cached != null) {
            onResult(cached)
            return
        }
        val token = requestSeq.incrementAndGet()
        executor.execute {
            val result = fetchDefinition(word, fullSelection)
            if (result != null) {
                defineCache.put(cacheKey, result)
            }
            val finalText = result ?: "No definition found for \"$word\"."
            mainHandler.post {
                if (requestSeq.get() == token) {
                    onResult(finalText)
                }
            }
        }
    }

    fun lookupTranslation(text: String, onResult: (String) -> Unit) {
        val key = text.trim().take(400)
        val cached = translateCache.get(key)
        if (cached != null) {
            onResult(cached)
            return
        }
        val token = requestSeq.incrementAndGet()
        executor.execute {
            val result = fetchTranslation(key)
            if (result != null) {
                translateCache.put(key, result)
            }
            val finalText = result ?: "Translation unavailable."
            mainHandler.post {
                if (requestSeq.get() == token) {
                    onResult(finalText)
                }
            }
        }
    }

    private fun fetchDefinition(word: String, fullSelection: String): String? {
        runCatching {
            val url = URL("https://api.dictionaryapi.dev/api/v2/entries/en/${Uri.encode(word.lowercase())}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                requestMethod = "GET"
            }
            try {
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val arr = JSONArray(body)
                    if (arr.length() > 0) {
                        val entry = arr.getJSONObject(0)
                        val phonetic = entry.optString("phonetic", "")
                        val meanings = entry.optJSONArray("meanings")
                        val sb = StringBuilder()
                        if (phonetic.isNotBlank()) {
                            sb.append(phonetic).append("\n\n")
                        }
                        if (meanings != null) {
                            val maxMeanings = minOf(meanings.length(), 3)
                            for (i in 0 until maxMeanings) {
                                val m = meanings.getJSONObject(i)
                                val pos = m.optString("partOfSpeech", "")
                                val defs = m.optJSONArray("definitions")
                                if (defs != null && defs.length() > 0) {
                                    val dObj = defs.getJSONObject(0)
                                    val defStr = dObj.optString("definition", "")
                                    val exStr = dObj.optString("example", "")
                                    sb.append("• [").append(pos).append("] ").append(defStr).append("\n")
                                    if (exStr.isNotBlank()) {
                                        sb.append("  \"").append(exStr).append("\"\n")
                                    }
                                }
                            }
                        }
                        val built = sb.toString().trim()
                        if (built.isNotEmpty()) return built
                    }
                }
            } finally {
                conn.disconnect()
            }
        }

        return runCatching {
            val query = fullSelection.trim().take(60)
            val url = URL("https://en.wikipedia.org/api/rest_v1/page/summary/${Uri.encode(query.replace(' ', '_'))}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                requestMethod = "GET"
                setRequestProperty("User-Agent", "sel-android/2.7")
            }
            try {
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val obj = JSONObject(body)
                    val extract = obj.optString("extract", "")
                    if (extract.isNotBlank()) return@runCatching extract.take(360)
                }
                null
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun fetchTranslation(text: String): String? {
        return runCatching {
            val url = URL(
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=en&dt=t&q=${Uri.encode(text)}"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                requestMethod = "GET"
            }
            try {
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val outer = JSONArray(body)
                    val sentences = outer.getJSONArray(0)
                    val sb = StringBuilder()
                    for (i in 0 until sentences.length()) {
                        val seg = sentences.getJSONArray(i)
                        sb.append(seg.optString(0, ""))
                    }
                    val res = sb.toString().trim()
                    if (res.isNotEmpty()) return@runCatching res
                }
                null
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}
