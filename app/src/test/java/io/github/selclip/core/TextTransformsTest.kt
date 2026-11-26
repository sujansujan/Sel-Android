package io.github.selclip.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class TextTransformsTest {

    @Test
    fun extractNepalPhone_handlesMobileAndLandlines() {
        val mobile = TextTransforms.extractNepalPhone("9841234567")
        assertNotNull(mobile)
        assertEquals("+9779841234567", mobile?.dialNumber)
        assertEquals("9779841234567", mobile?.waDigits)

        val landline = TextTransforms.extractNepalPhone("+977-1-4412345")
        assertNotNull(landline)
        assertEquals("+97714412345", landline?.dialNumber)
    }

    @Test
    fun cleanUrl_stripsTrackingParameters() {
        val dirty = "https://example.com/p?id=42&utm_source=twitter&fbclid=xyz&si=123"
        assertEquals("https://example.com/p?id=42", TextTransforms.cleanUrl(dirty))

        // First param stripped must preserve '?' before remaining query params
        val firstDirty = "https://example.com/p?utm_source=ads&id=42&srsltid=abc&igsh=xyz#section"
        assertEquals("https://example.com/p?id=42#section", TextTransforms.cleanUrl(firstDirty))

        // All params stripped removes '?' cleanly
        assertEquals("https://example.com/p", TextTransforms.cleanUrl("https://example.com/p?fbclid=1&ref_src=tw"))
    }

    @Test
    fun evalSimpleMath_andUnits_andHex() {
        assertEquals("1740", TextTransforms.evalSimpleMath("145 * 12"))
        assertEquals("3.50", TextTransforms.evalSimpleMath("7 / 2"))
        assertEquals("226", TextTransforms.evalSimpleMath("(120 + 80) * 1.13"))
        assertEquals("37500", TextTransforms.evalSimpleMath("12,500 × 3"))
        assertEquals("360", TextTransforms.evalSimpleMath("15% of 2,400"))
        assertEquals("2712", TextTransforms.evalSimpleMath("2400 + 13%"))
        assertEquals("2040", TextTransforms.evalSimpleMath("2400 - 15%"))
        assertNull(TextTransforms.evalSimpleMath("10 / 0"))
        assertNull(TextTransforms.evalSimpleMath("2026-11-26"))
        assertNull(TextTransforms.evalSimpleMath("-42"))
        assertEquals("= 255 DEC", TextTransforms.evalUnitConversion("0xFF"))
        assertEquals("= rgb(26, 115, 232)", TextTransforms.evalUnitConversion("#1A73E8"))
        assertEquals("= rgb(255, 255, 255)", TextTransforms.evalUnitConversion("#FFF"))
        assertEquals("= 22.2 °C", TextTransforms.evalUnitConversion("72 F"))
        assertEquals("= 62.1 mph", TextTransforms.evalUnitConversion("100 km/h"))
        assertEquals("= 2048 MB", TextTransforms.evalUnitConversion("2 GB"))
    }

    @Test
    fun evalTimestamp_andBase64Decode() {
        val prev = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("2023-11-14 22:13:20", TextTransforms.evalTimestamp("1700000000"))
        } finally {
            TimeZone.setDefault(prev)
        }
        assertEquals("Hello World", TextTransforms.evalBase64Decode("SGVsbG8gV29ybGQ="))
    }

    @Test
    fun torrentHash_andMagnetUri() {
        val hash = "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"
        assertEquals(hash, TextTransforms.extractTorrentHash("Infohash: $hash"))
        val magnet = TextTransforms.buildMagnetUri(hash)
        assertTrue(magnet.startsWith("magnet:?xt=urn:btih:$hash"))
    }

    @Test
    fun markdown_pipe_andRegexTransform() {
        assertEquals("**sel**", TextTransforms.applyMarkdownTool("md_bold", "sel"))
        assertEquals("uryyb", TextTransforms.applyPipeTool("pipe_rot13", "hello"))
        assertEquals("Hello", TextTransforms.applyPipeTool("pipe_strip_html", "<b>Hello</b>"))
        assertEquals("foo_bar_baz", TextTransforms.executeCustomTargetTransform("s/\\s+/_/g", "foo  bar\tbaz"))
        assertEquals("HELLO", TextTransforms.cycleCase("Hello"))
        assertEquals("hello", TextTransforms.cycleCase("HELLO"))
        assertEquals("Hello World", TextTransforms.cycleCase("hello world"))
    }
}
