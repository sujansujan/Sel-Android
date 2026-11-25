package io.github.selclip.core

/**
 * Pure selection validation, package classification, and debouncing logic (zero Android deps).
 */
object SelectionFilter {

    private val imeTokens = listOf(
        "inputmethod", "keyboard", "honeyboard", "latin", "gboard",
        "swiftkey", "fleksy", "openboard", "florisboard", "helioboard", "anysoftkeyboard"
    )

    private val transientClassTokens = listOf(
        "popup", "floating", "actionmode", "menu", "toast", "dialog", "tooltip", "snackbar"
    )

    private val launcherTokens = listOf(
        "launcher", "nexuslauncher", "trebuchet", "lawnchair", "nova", "oneui.home", "miui.home"
    )

    fun isImePackage(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        val p = pkg.lowercase()
        return imeTokens.any { it in p }
    }

    fun isTransientPopupClass(className: String): Boolean {
        if (className.isEmpty()) return false
        val c = className.lowercase()
        return transientClassTokens.any { it in c }
    }

    fun isLauncherPackage(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        val p = pkg.lowercase()
        return launcherTokens.any { it in p }
    }

    private val geckoBrowserTokens = listOf(
        "firefox", "mozilla", "weblibre", "gecko", "fennec", "mull", "waterfox", "ironfox", "iceraven"
    )

    fun isGeckoBrowserPackage(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        val p = pkg.lowercase()
        return geckoBrowserTokens.any { it in p }
    }

    fun isSystemUiOrFramework(pkg: String): Boolean {
        return pkg == "com.android.systemui" || pkg == "android" || pkg.contains("systemui", ignoreCase = true)
    }

    /**
     * Validates and normalizes candidate selected text from a node or event.
     * Rejects password fields, empty/whitespace text, hint-only text, and absurdly huge dumps.
     * Strips GeckoView's inline object-replacement characters (\uFFFC).
     */
    fun sanitizeCandidate(
        rawSelection: String?,
        isPassword: Boolean = false,
        hintText: String? = null,
        maxLength: Int = 8000
    ): String? {
        if (isPassword) return null
        val raw = rawSelection ?: return null
        val text = if (raw.indexOf('\uFFFC') >= 0) raw.replace("\uFFFC", "").trim() else raw
        if (text.isBlank()) return null
        if (!hintText.isNullOrEmpty() && text == hintText) return null
        return if (text.length > maxLength) text.substring(0, maxLength) else text
    }

    /**
     * Extracts a safe substring using possibly reversed [fromIndex, toIndex] indices.
     */
    fun extractRange(
        fullText: String?,
        fromIndex: Int,
        toIndex: Int,
        isPassword: Boolean = false,
        hintText: String? = null
    ): String? {
        if (isPassword || fullText.isNullOrEmpty()) return null
        if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return null
        val start = minOf(fromIndex, toIndex)
        val end = maxOf(fromIndex, toIndex)
        if (end > fullText.length) return null
        val sub = fullText.substring(start, end)
        return sanitizeCandidate(sub, isPassword = false, hintText = hintText)
    }
}

/**
 * Simple timestamp-based event throttler & duplicate filter for noisy accessibility streams.
 */
class EventDebouncer(
    private val minIntervalMs: Long
) {
    private var lastAllowedMs: Long = 0L

    fun shouldAllow(nowMs: Long): Boolean {
        if (nowMs - lastAllowedMs < minIntervalMs) return false
        lastAllowedMs = nowMs
        return true
    }

    fun reset() {
        lastAllowedMs = 0L
    }
}
