package io.github.selclip.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

data class NepalPhone(
    val display: String,
    val dialNumber: String,
    val waDigits: String
)

data class ExtractedEntity(
    val type: String,
    val value: String
)

/**
 * Pure, stateless text analysis and transformation utilities with pre-compiled Regexes
 * so hot paths allocate zero regex objects.
 */
object TextTransforms {

    private val trackingParamNames = setOf(
        "fbclid", "gclid", "gbraid", "wbraid", "dclid", "msclkid", "yclid",
        "igshid", "igsh", "mc_cid", "mc_eid", "si", "srsltid",
        "ref", "ref_src", "ref_url", "feature", "pp", "s"
    )

    val trackingRegex = Regex(
        "([?&])(utm_[a-z0-9_]+|fbclid|gclid|gbraid|wbraid|dclid|msclkid|yclid|igshid|igsh|mc_cid|mc_eid|si|srsltid|ref|ref_src|ref_url|feature|pp|s)=[^&#]*",
        RegexOption.IGNORE_CASE
    )

    private fun isTrackingParam(keyLower: String): Boolean =
        keyLower.startsWith("utm_") || keyLower in trackingParamNames

    val urlPattern = Regex(
        "^(https?://|www\\.|[a-zA-Z0-9-]+\\.(com|org|net|io|edu|gov|me|co|app|dev|xyz|info)(/|$)).*",
        RegexOption.IGNORE_CASE
    )

    val whitespaceRegex = Regex("\\s+")
    val invisibleCharsRegex = Regex("[\\u200B-\\u200D\\uFEFF]")
    val singleNewlineRegex = Regex("(?<!\\n)\\n(?!\\n)")
    val horizontalSpaceRegex = Regex("[ \\t]+")
    val htmlTagRegex = Regex("<[^>]+>")

    val httpUrlFindRegex = Regex("https?://[^\\s\"<>]+")
    val emailFindRegex = Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}")
    val phoneFindRegex = Regex("(?:\\+?\\d[\\d\\- ()]{6,}\\d)")
    val tagFindRegex = Regex("[#@][a-zA-Z0-9_]+")
    val dialPhoneMatchRegex = Regex("^\\+?[0-9][0-9\\- ()]{6,16}[0-9]$")

    val sha1HexRegex = Regex("\\b([0-9a-fA-F]{40})\\b")
    val sha256HexRegex = Regex("\\b([0-9a-fA-F]{64})\\b")
    val base32HashRegex = Regex("\\b([A-Za-z2-7]{32})\\b")

    private val nepalPhoneRegex = Regex(
        "(?:\\+977|00977|977)?[\\s\\-.()]*((?:98|97)[0-9\\s\\-.()]{8,13}|[1-9][0-9\\s\\-.()]{6,10})"
    )

    private val dateLikeRegex = Regex("^\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}$")
    private val phoneDashRegex = Regex("^\\d{2,4}-\\d{3,4}-\\d{3,4}$")
    private val percentOfRegex = Regex("^\\s*(-?[\\d,]+(?:\\.\\d+)?)\\s*%\\s+of\\s+(-?[\\d,]+(?:\\.\\d+)?)\\s*$", RegexOption.IGNORE_CASE)
    private val addSubPercentRegex = Regex("^\\s*(-?[\\d,]+(?:\\.\\d+)?)\\s*([+\\-−])\\s*([\\d,]+(?:\\.\\d+)?)\\s*%\\s*$")
    private val mathValidCharsRegex = Regex("^[\\d.,\\s()+\\-*/×÷−]+$")
    private val mathHasOpRegex = Regex("[+\\-*/×÷−]")
    private val thousandsCommaRegex = Regex("(?<=\\d),(?=\\d{3}(?:\\D|$))")
    private val hexColorRegex = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})$")
    private val unitConvRegex = Regex(
        "^(-?\\d+(?:\\.\\d+)?)\\s*°?\\s*(km/h|kph|mph|fl\\s*oz|km|mi|kg|lbs?|cm|in|ft|m|c|f|oz|g|gal|l|ml|gb|mb|tb)$",
        RegexOption.IGNORE_CASE
    )
    private val timestampRegex = Regex("^\\d{10}(\\d{3})?$")
    private val base64CharsRegex = Regex("^[A-Za-z0-9+/]+={0,2}$")

    fun countWords(text: String): Int {
        var count = 0
        var inWord = false
        for (i in 0 until text.length) {
            if (text[i].isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                count++
            }
        }
        return count
    }

    fun hasExtractableEntities(text: String): Boolean {
        if (httpUrlFindRegex.containsMatchIn(text)) return true
        if (emailFindRegex.containsMatchIn(text)) return true
        if (phoneFindRegex.containsMatchIn(text)) return true
        if (tagFindRegex.containsMatchIn(text)) return true
        return false
    }

    fun extractEntities(text: String, limit: Int = 12): List<ExtractedEntity> {
        val matches = LinkedHashMap<String, ExtractedEntity>()
        httpUrlFindRegex.findAll(text).forEach { m ->
            matches.putIfAbsent(m.value, ExtractedEntity("URL", m.value))
        }
        emailFindRegex.findAll(text).forEach { m ->
            matches.putIfAbsent(m.value, ExtractedEntity("MAIL", m.value))
        }
        phoneFindRegex.findAll(text).forEach { m ->
            val v = m.value.trim()
            matches.putIfAbsent(v, ExtractedEntity("TEL", v))
        }
        tagFindRegex.findAll(text).forEach { m ->
            matches.putIfAbsent(m.value, ExtractedEntity("TAG", m.value))
        }
        return matches.values.take(limit)
    }

    fun extractNepalPhone(input: String): NepalPhone? {
        for (m in nepalPhoneRegex.findAll(input)) {
            val fullMatch = m.value.trim()
            val rawDigits = fullMatch.filter { it.isDigit() }
            val hasExplicit977 = fullMatch.startsWith("+977") || fullMatch.startsWith("00977") ||
                (fullMatch.startsWith("977") && rawDigits.length >= 12)

            val localDigits = when {
                rawDigits.startsWith("00977") -> rawDigits.removePrefix("00977")
                rawDigits.startsWith("977") && ((hasExplicit977 && rawDigits.length >= 10) || rawDigits.length >= 12) ->
                    rawDigits.removePrefix("977")
                rawDigits.startsWith("098") && rawDigits.length == 11 -> rawDigits.removePrefix("0")
                rawDigits.startsWith("097") && rawDigits.length == 11 -> rawDigits.removePrefix("0")
                else -> rawDigits
            }

            val isValidMobile = (localDigits.startsWith("98") || localDigits.startsWith("97")) && localDigits.length == 10
            val isValidLandlineWith977 = hasExplicit977 && localDigits.length in 7..10

            if (isValidMobile || isValidLandlineWith977) {
                return NepalPhone(
                    display = "+977 $localDigits",
                    dialNumber = "+977$localDigits",
                    waDigits = "977$localDigits"
                )
            }
        }
        return null
    }

    fun extractTorrentHash(input: String): String? {
        sha1HexRegex.find(input)?.let { return it.groupValues[1].lowercase() }
        sha256HexRegex.find(input)?.let { return it.groupValues[1].lowercase() }
        base32HashRegex.find(input)?.let {
            val candidate = it.groupValues[1]
            if (candidate.any { ch -> ch.isDigit() }) return candidate.uppercase()
        }
        return null
    }

    fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    fun urlDecode(value: String): String =
        runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)

    fun buildMagnetUri(hash: String): String {
        val trackers = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://open.demonii.com:1337/announce"
        ).joinToString("") { "&tr=${urlEncode(it)}" }

        return if (hash.length == 64) {
            "magnet:?xt=urn:btmh:1220$hash$trackers"
        } else {
            "magnet:?xt=urn:btih:$hash$trackers"
        }
    }

    fun cleanUrl(input: String): String {
        val hashIdx = input.indexOf('#')
        val beforeHash = if (hashIdx >= 0) input.substring(0, hashIdx) else input
        val fragment = if (hashIdx >= 0) input.substring(hashIdx) else ""
        val qIdx = beforeHash.indexOf('?')
        if (qIdx < 0) return input
        val base = beforeHash.substring(0, qIdx)
        val query = beforeHash.substring(qIdx + 1)
        val kept = query.split('&').filter { part ->
            if (part.isBlank()) return@filter false
            val key = part.substringBefore('=').lowercase()
            !isTrackingParam(key)
        }
        return if (kept.isEmpty()) base + fragment else "$base?${kept.joinToString("&")}$fragment"
    }

    fun cleanText(input: String): String = input
        .replace(invisibleCharsRegex, "")
        .replace(singleNewlineRegex, " ")
        .replace(horizontalSpaceRegex, " ")
        .trim()

    fun cycleCase(input: String): String = when {
        input == input.uppercase() -> input.lowercase()
        input == input.lowercase() -> input.split(" ").joinToString(" ") { w ->
            w.replaceFirstChar { it.uppercase() }
        }
        else -> input.uppercase()
    }

    fun sortUniqueLines(input: String): String =
        input.lines().filter { it.isNotBlank() }.distinct().sorted().joinToString("\n")

    fun sha256Hex(input: String): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        digest.joinToString("") { "%02x".format(it) }
    }.getOrDefault(input)

    fun rot13(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            val out = when (c) {
                in 'a'..'m', in 'A'..'M' -> c + 13
                in 'n'..'z', in 'N'..'Z' -> c - 13
                else -> c
            }
            sb.append(out)
        }
        return sb.toString()
    }

    private fun formatMathResult(res: Double): String? {
        if (res.isNaN() || res.isInfinite()) return null
        val rounded = kotlin.math.round(res)
        return if (kotlin.math.abs(res - rounded) < 1e-9 && kotlin.math.abs(rounded) < 1e15) {
            rounded.toLong().toString()
        } else {
            "%.2f".format(Locale.US, res)
        }
    }

    fun evalSimpleMath(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.length > 64) return null
        if (dateLikeRegex.matches(trimmed) || phoneDashRegex.matches(trimmed)) return null

        percentOfRegex.matchEntire(trimmed)?.let { m ->
            val pct = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
            val base = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: return null
            return formatMathResult(base * pct / 100.0)
        }
        addSubPercentRegex.matchEntire(trimmed)?.let { m ->
            val base = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
            val op = m.groupValues[2]
            val pct = m.groupValues[3].replace(",", "").toDoubleOrNull() ?: return null
            val delta = base * pct / 100.0
            return formatMathResult(if (op == "+") base + delta else base - delta)
        }

        if (!mathValidCharsRegex.matches(trimmed) || !mathHasOpRegex.containsMatchIn(trimmed)) return null
        val normalized = trimmed
            .replace(thousandsCommaRegex, "")
            .replace('×', '*')
            .replace('÷', '/')
            .replace('−', '-')
            .replace(" ", "")
        if (normalized.contains(',')) return null

        // Require at least one binary operator (ignore plain negative numbers like "-42")
        val hasBinaryOp = normalized.drop(1).any { it == '+' || it == '-' || it == '*' || it == '/' }
        if (!hasBinaryOp) return null

        val value = MathParser(normalized).parse() ?: return null
        return formatMathResult(value)
    }

    private class MathParser(private val s: String) {
        private var pos = 0

        fun parse(): Double? {
            val res = parseExpression() ?: return null
            return if (pos == s.length) res else null
        }

        private fun parseExpression(): Double? {
            var left = parseTerm() ?: return null
            while (pos < s.length && (s[pos] == '+' || s[pos] == '-')) {
                val op = s[pos++]
                val right = parseTerm() ?: return null
                left = if (op == '+') left + right else left - right
            }
            return left
        }

        private fun parseTerm(): Double? {
            var left = parseFactor() ?: return null
            while (pos < s.length && (s[pos] == '*' || s[pos] == '/')) {
                val op = s[pos++]
                val right = parseFactor() ?: return null
                if (op == '/') {
                    if (right == 0.0) return null
                    left /= right
                } else {
                    left *= right
                }
            }
            return left
        }

        private fun parseFactor(): Double? {
            if (pos >= s.length) return null
            if (s[pos] == '+') {
                pos++
                return parseFactor()
            }
            if (s[pos] == '-') {
                pos++
                return parseFactor()?.let { -it }
            }
            if (s[pos] == '(') {
                pos++
                val v = parseExpression() ?: return null
                if (pos >= s.length || s[pos] != ')') return null
                pos++
                return v
            }
            val start = pos
            var sawDot = false
            while (pos < s.length && (s[pos].isDigit() || (!sawDot && s[pos] == '.'))) {
                if (s[pos] == '.') sawDot = true
                pos++
            }
            if (pos == start) return null
            return s.substring(start, pos).toDoubleOrNull()
        }
    }

    fun evalUnitConversion(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.startsWith("0x", ignoreCase = true)) {
            val dec = trimmed.substring(2).toLongOrNull(16) ?: return null
            return "= $dec DEC"
        }
        hexColorRegex.matchEntire(trimmed)?.let { m ->
            val hex = m.groupValues[1]
            val full = if (hex.length == 3) "${hex[0]}${hex[0]}${hex[1]}${hex[1]}${hex[2]}${hex[2]}" else hex
            val r = full.substring(0, 2).toInt(16)
            val g = full.substring(2, 4).toInt(16)
            val b = full.substring(4, 6).toInt(16)
            return "= rgb($r, $g, $b)"
        }
        val m = unitConvRegex.find(trimmed) ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].lowercase().replace(whitespaceRegex, " ")
        return when (unit) {
            "km" -> "= %.2f mi".format(Locale.US, v * 0.621371)
            "mi" -> "= %.2f km".format(Locale.US, v * 1.60934)
            "kg" -> "= %.2f lb".format(Locale.US, v * 2.20462)
            "lb", "lbs" -> "= %.2f kg".format(Locale.US, v * 0.453592)
            "oz" -> "= %.2f g".format(Locale.US, v * 28.3495)
            "g" -> "= %.2f oz".format(Locale.US, v * 0.035274)
            "cm" -> "= %.2f in".format(Locale.US, v * 0.393701)
            "in" -> "= %.2f cm".format(Locale.US, v * 2.54)
            "ft" -> "= %.2f m".format(Locale.US, v * 0.3048)
            "m" -> "= %.2f ft".format(Locale.US, v * 3.28084)
            "gal" -> "= %.2f L".format(Locale.US, v * 3.78541)
            "l" -> "= %.2f gal".format(Locale.US, v * 0.264172)
            "ml" -> "= %.2f fl oz".format(Locale.US, v * 0.033814)
            "fl oz" -> "= %.1f mL".format(Locale.US, v * 29.5735)
            "km/h", "kph" -> "= %.1f mph".format(Locale.US, v * 0.621371)
            "mph" -> "= %.1f km/h".format(Locale.US, v * 1.60934)
            "mb" -> "= %.2f GB".format(Locale.US, v / 1024.0)
            "gb" -> "= %.0f MB".format(Locale.US, v * 1024.0)
            "tb" -> "= %.0f GB".format(Locale.US, v * 1024.0)
            "f" -> "= %.1f °C".format(Locale.US, (v - 32.0) * 5.0 / 9.0)
            "c" -> "= %.1f °F".format(Locale.US, v * 9.0 / 5.0 + 32.0)
            else -> null
        }
    }

    fun evalTimestamp(input: String): String? {
        val trimmed = input.trim()
        if (!timestampRegex.matches(trimmed)) return null
        val raw = trimmed.toLongOrNull() ?: return null
        val ms = if (trimmed.length == 10) raw * 1000L else raw
        if (ms < 946684800000L || ms > 4102444800000L) return null
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
    }

    fun evalBase64Decode(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.length < 8 || trimmed.length % 4 != 0 || trimmed.contains(' ')) return null
        if (!base64CharsRegex.matches(trimmed)) return null
        if (extractTorrentHash(trimmed) != null) return null
        return runCatching {
            val bytes = Base64.getDecoder().decode(trimmed)
            if (bytes.isEmpty() || bytes.size > 4096) return null
            val str = String(bytes, Charsets.UTF_8)
            val printable = str.all { it == '\n' || it == '\r' || it == '\t' || it.code in 32..126 }
            if (printable && str.isNotBlank()) str.trim() else null
        }.getOrNull()
    }

    fun executeCustomTargetTransform(target: String, input: String): String {
        val trimmedTarget = target.trim()
        if (trimmedTarget.startsWith("s/") && trimmedTarget.count { it == '/' } >= 3) {
            return runCatching {
                val firstSlash = 1
                val secondSlash = trimmedTarget.indexOf('/', firstSlash + 1)
                val lastSlash = trimmedTarget.lastIndexOf('/')
                if (secondSlash > firstSlash && lastSlash > secondSlash) {
                    val pattern = trimmedTarget.substring(firstSlash + 1, secondSlash)
                    val replacement = trimmedTarget.substring(secondSlash + 1, lastSlash)
                        .replace("\\n", "\n")
                        .replace("\\t", "\t")
                    val flags = trimmedTarget.substring(lastSlash + 1)
                    val opts = mutableSetOf<RegexOption>()
                    if (flags.contains('i', ignoreCase = true)) opts.add(RegexOption.IGNORE_CASE)
                    if (flags.contains('m', ignoreCase = true)) opts.add(RegexOption.MULTILINE)
                    val regex = Regex(pattern, opts)
                    if (flags.contains('g', ignoreCase = true)) {
                        regex.replace(input, replacement)
                    } else {
                        regex.replaceFirst(input, replacement)
                    }
                } else {
                    input
                }
            }.getOrDefault(input)
        }
        return trimmedTarget.replace("{text}", input)
    }

    fun applyMarkdownTool(toolId: String, selectedText: String): String {
        val trimmed = selectedText.trim()
        val looksLikeUrl = trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            urlPattern.matches(trimmed)

        return when (toolId) {
            "md_bold" -> "**$trimmed**"
            "md_italic" -> "*$trimmed*"
            "md_code" -> if (selectedText.contains("\n")) "```\n$selectedText\n```" else "`$selectedText`"
            "md_quote" -> selectedText.lines().joinToString("\n") { "> $it" }
            "md_link" -> if (looksLikeUrl) "[link]($trimmed)" else "[$selectedText](https://)"
            "md_strike" -> "~~$trimmed~~"
            "md_bullet" -> selectedText.lines().filter { it.isNotBlank() }.joinToString("\n") { "- ${it.trim()}" }
            "md_task" -> selectedText.lines().filter { it.isNotBlank() }.joinToString("\n") { "- [ ] ${it.trim()}" }
            "md_h2" -> selectedText.lines().joinToString("\n") { "## ${it.trim()}" }
            "md_spoiler" -> "||$trimmed||"
            "md_table" -> "| Col 1 | Col 2 |\n| --- | --- |\n| $trimmed |  |"
            else -> selectedText
        }
    }

    fun applyPipeTool(toolId: String, selectedText: String): String = when (toolId) {
        "pipe_json_pretty" -> runCatching {
            val t = selectedText.trim()
            if (t.startsWith("[")) JSONArray(t).toString(2) else JSONObject(t).toString(2)
        }.getOrElse { selectedText }
        "pipe_json_min" -> runCatching {
            val t = selectedText.trim()
            if (t.startsWith("[")) JSONArray(t).toString() else JSONObject(t).toString()
        }.getOrElse { selectedText.lines().joinToString("") { it.trim() } }
        "pipe_url_enc" -> urlEncode(selectedText)
        "pipe_url_dec" -> urlDecode(selectedText)
        "pipe_b64_enc" -> Base64.getEncoder().encodeToString(selectedText.toByteArray(Charsets.UTF_8))
        "pipe_sha256" -> sha256Hex(selectedText)
        "pipe_rot13" -> rot13(selectedText)
        "pipe_num_lines" -> selectedText.lines().mapIndexed { i, l -> "${i + 1}. $l" }.joinToString("\n")
        "pipe_strip_html" -> selectedText.replace(htmlTagRegex, "").trim()
        else -> selectedText
    }
}
