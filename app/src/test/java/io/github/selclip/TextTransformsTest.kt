package io.github.selclip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class TextTransformsTest {

    @Test
    fun countWords_handlesWhitespaceAndNewlines() {
        assertEquals(0, TextTransforms.countWords("   \n\t  "))
        assertEquals(1, TextTransforms.countWords("serendipity"))
        assertEquals(4, TextTransforms.countWords("  hello   world\nfrom\tsel  "))
    }

    @Test
    fun extractNepalPhone_parsesMobileAndExplicitCountryCode() {
        val mobile = TextTransforms.extractNepalPhone("Call 9841234567 today")
        assertNotNull(mobile)
        assertEquals("+9779841234567", mobile?.dialNumber)
        assertEquals("9779841234567", mobile?.waDigits)

        val prefixed = TextTransforms.extractNepalPhone("+977 980-123-4567")
        assertNotNull(prefixed)
        assertEquals("+9779801234567", prefixed?.dialNumber)

        val landline = TextTransforms.extractNepalPhone("+977 14221234")
        assertNotNull(landline)
        assertEquals("+97714221234", landline?.dialNumber)

        assertNull(TextTransforms.extractNepalPhone("Order #12345"))
    }

    @Test
    fun extractTorrentHash_andBuildMagnetUri() {
        val sha1 = "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"
        assertEquals(sha1, TextTransforms.extractTorrentHash("hash: $sha1"))
        val magnet = TextTransforms.buildMagnetUri(sha1)
        assertTrue(magnet.startsWith("magnet:?xt=urn:btih:$sha1"))
        assertTrue(magnet.contains("&tr=udp%3A%2F%2F"))

        val sha256 = "a".repeat(64)
        assertEquals(sha256, TextTransforms.extractTorrentHash(sha256))
        assertTrue(TextTransforms.buildMagnetUri(sha256).startsWith("magnet:?xt=urn:btmh:1220$sha256"))
    }

    @Test
    fun cleanUrl_stripsTrackingParamsPreservingContentParams() {
        val dirty = "https://example.com/item?id=42&utm_source=ads&fbclid=123&gclid=abc"
        assertEquals("https://example.com/item?id=42", TextTransforms.cleanUrl(dirty))

        val onlyTracking = "https://example.com/page?utm_medium=social&si=xyz"
        assertEquals("https://example.com/page", TextTransforms.cleanUrl(onlyTracking))
    }

    @Test
    fun cleanText_removesZeroWidthAndUnwrapsSingleNewlines() {
        val raw = "Hello\u200B  world\nnext line\n\nNew paragraph"
        assertEquals("Hello world next line\n\nNew paragraph", TextTransforms.cleanText(raw))
    }

    @Test
    fun cycleCase_rotatesLowerTitleUpper() {
        assertEquals("Hello World", TextTransforms.cycleCase("hello world"))
        assertEquals("HELLO WORLD", TextTransforms.cycleCase("Hello World"))
        assertEquals("hello world", TextTransforms.cycleCase("HELLO WORLD"))
    }

    @Test
    fun evalSimpleMath_handlesOperatorsAndDivideByZero() {
        assertEquals("1740", TextTransforms.evalSimpleMath("145 * 12"))
        assertEquals("12.50", TextTransforms.evalSimpleMath("25 / 2"))
        assertEquals("-3", TextTransforms.evalSimpleMath("5 - 8"))
        assertNull(TextTransforms.evalSimpleMath("10 / 0"))
        assertNull(TextTransforms.evalSimpleMath("hello"))
    }

    @Test
    fun evalUnitConversion_handlesHexAndUnits() {
        assertEquals("= 255 DEC", TextTransforms.evalUnitConversion("0xFF"))
        assertEquals("= 22.2 °C", TextTransforms.evalUnitConversion("72 F"))
        assertEquals("= 6.21 mi", TextTransforms.evalUnitConversion("10 km"))
        assertNull(TextTransforms.evalUnitConversion("100 apples"))
    }

    @Test
    fun evalTimestamp_decodesSecondsAndMillis() {
        val prevTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("2024-10-27 03:33:20", TextTransforms.evalTimestamp("1730000000"))
            assertEquals("2024-10-27 03:33:20", TextTransforms.evalTimestamp("1730000000000"))
            assertNull(TextTransforms.evalTimestamp("12345"))
        } finally {
            TimeZone.setDefault(prevTz)
        }
    }

    @Test
    fun evalBase64Decode_decodesPrintableAndRejectsNonBase64() {
        assertEquals("hello world", TextTransforms.evalBase64Decode("aGVsbG8gd29ybGQ="))
        assertNull(TextTransforms.evalBase64Decode("not base64!"))
        assertNull(TextTransforms.evalBase64Decode("dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"))
    }

    @Test
    fun executeCustomTargetTransform_supportsSedRegexAndTemplates() {
        assertEquals("12345", TextTransforms.executeCustomTargetTransform("s/[^0-9]+//g", "ID: 12-345!"))
        assertEquals("hello-world", TextTransforms.executeCustomTargetTransform("s/\\s+/-/g", "hello   world"))
        assertEquals("# Title", TextTransforms.executeCustomTargetTransform("# {text}", "Title"))
    }

    @Test
    fun applyMarkdownAndPipeTools_transformAccurately() {
        assertEquals("**bold**", TextTransforms.applyMarkdownTool("md_bold", " bold "))
        assertEquals("`code`", TextTransforms.applyMarkdownTool("md_code", "code"))
        assertEquals("[link](https://example.com)", TextTransforms.applyMarkdownTool("md_link", "https://example.com"))

        assertEquals("{\"a\":1}", TextTransforms.applyPipeTool("pipe_json_min", "{ \"a\" : 1 }"))
        assertEquals("hello%20world", TextTransforms.applyPipeTool("pipe_url_enc", "hello world"))
        assertEquals("hello world", TextTransforms.applyPipeTool("pipe_url_dec", "hello%20world"))
        assertEquals("aGVsbG8=", TextTransforms.applyPipeTool("pipe_b64_enc", "hello"))
        assertEquals("uryyb", TextTransforms.applyPipeTool("pipe_rot13", "hello"))
        assertEquals("plain", TextTransforms.applyPipeTool("pipe_strip_html", "<b>plain</b>"))
        assertEquals(64, TextTransforms.applyPipeTool("pipe_sha256", "abc").length)
    }

    @Test
    fun extractEntities_andResolveSmartAction() {
        val sample = "Visit https://example.com or email dev@example.com #android"
        assertTrue(TextTransforms.hasExtractableEntities(sample))
        val entities = TextTransforms.extractEntities(sample)
        assertTrue(entities.any { it.type == "URL" && it.value == "https://example.com" })
        assertTrue(entities.any { it.type == "MAIL" && it.value == "dev@example.com" })
        assertTrue(entities.any { it.type == "TAG" && it.value == "#android" })

        assertFalse(TextTransforms.hasExtractableEntities("just plain text"))

        assertEquals("magnet", TextTransforms.resolveSmartAction("dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c").first)
        assertEquals("clean_url", TextTransforms.resolveSmartAction("https://example.com/?utm_source=x").first)
        assertEquals("open_url", TextTransforms.resolveSmartAction("https://example.com/docs").first)
        assertEquals("dial", TextTransforms.resolveSmartAction("9841234567").first)
        assertEquals("math", TextTransforms.resolveSmartAction("12 * 12").first)
        assertEquals("define", TextTransforms.resolveSmartAction("serendipity").first)
    }
}
