package io.github.selclip.actions

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
 * Strictly lazy, cached, cancellable network client for on-demand Define Word & Translate.
 * Allocates ZERO threads at idle until the user actually taps Define or Translate.
 */
class LazyNetworkClient {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestSeq = AtomicLong(0L)
    private val defineCache = LruCache<String, String>(64)
    private val translateCache = LruCache<String, String>(64)

    @Volatile
    private var executor: ExecutorService? = null

    private fun getOrCreateExecutor(): ExecutorService {
        executor?.let { if (!it.isShutdown) return it }
        synchronized(this) {
            executor?.let { if (!it.isShutdown) return it }
            return Executors.newSingleThreadExecutor { r ->
                Thread(r, "sel-net").apply { isDaemon = true }
            }.also { executor = it }
        }
    }

    fun cancelPending() {
        requestSeq.incrementAndGet()
    }

    fun shutdown() {
        cancelPending()
        synchronized(this) {
            runCatching { executor?.shutdownNow() }
            executor = null
        }
    }

    fun lookupDefinition(word: String, fullSelection: String, onResult: (String) -> Unit) {
        val cacheKey = "${word.lowercase()}|${fullSelection.trim().take(60).lowercase()}"
        defineCache.get(cacheKey)?.let {
            onResult(it)
            return
        }
        val token = requestSeq.incrementAndGet()
        getOrCreateExecutor().execute {
            val res = fetchDefinition(word, fullSelection)
            if (res != null) defineCache.put(cacheKey, res)
            val text = res ?: "No definition found for \"$word\"."
            mainHandler.post {
                if (requestSeq.get() == token) onResult(text)
            }
        }
    }

    fun lookupTranslation(text: String, targetLang: String = "en", onResult: (String) -> Unit) {
        val cleanLang = targetLang.trim().ifEmpty { "en" }
        val key = text.trim().take(400)
        val cacheKey = "$cleanLang|$key"
        translateCache.get(cacheKey)?.let {
            onResult(it)
            return
        }
        val token = requestSeq.incrementAndGet()
        getOrCreateExecutor().execute {
            val res = fetchTranslation(key, cleanLang)
            if (res != null) translateCache.put(cacheKey, res)
            val out = res ?: "Translation unavailable."
            mainHandler.post {
                if (requestSeq.get() == token) onResult(out)
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
                        if (phonetic.isNotBlank()) sb.append(phonetic).append("\n\n")
                        if (meanings != null) {
                            for (i in 0 until minOf(meanings.length(), 3)) {
                                val m = meanings.getJSONObject(i)
                                val pos = m.optString("partOfSpeech", "")
                                val defs = m.optJSONArray("definitions")
                                if (defs != null && defs.length() > 0) {
                                    val d = defs.getJSONObject(0)
                                    val defStr = d.optString("definition", "")
                                    val exStr = d.optString("example", "")
                                    sb.append("• (").append(pos).append(") ").append(defStr).append("\n")
                                    if (exStr.isNotBlank()) sb.append("  \"").append(exStr).append("\"\n")
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
                setRequestProperty("User-Agent", "sel-android/4.0")
            }
            try {
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val extract = JSONObject(body).optString("extract", "")
                    if (extract.isNotBlank()) return@runCatching extract.take(360)
                }
                null
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun fetchTranslation(text: String, targetLang: String): String? = runCatching {
        val url = URL(
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=${Uri.encode(targetLang)}&dt=t&q=${Uri.encode(text)}"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 4500
            readTimeout = 4500
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val sentences = JSONArray(body).getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until sentences.length()) {
                    sb.append(sentences.getJSONArray(i).optString(0, ""))
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
