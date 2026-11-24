package io.github.selclip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupContextEngineTest {

    @Test
    fun nepalPhone_showsCallSmsWaW4bPlusSearchAndYoutubeWithoutCopy() {
        val ctx = PopupContextEngine.analyze("9841234567", isEditable = false)
        val ids = PopupContextEngine.resolveBarActions(ctx, searchLabel = "GGL").map { it.id }
        assertEquals(
            listOf("phone_call", "phone_sms", "phone_wa", "phone_w4b", "default_search", "youtube_direct"),
            ids
        )
        assertFalse("copy" in ids)
        assertFalse("cut" in ids)
        assertFalse("paste" in ids)
    }

    @Test
    fun dirtyUrl_showsOpenCleanQrPlusSearchAndYoutube() {
        val ctx = PopupContextEngine.analyze("https://example.com/item?utm_source=ads&fbclid=99", isEditable = false)
        val ids = PopupContextEngine.resolveBarActions(ctx, searchLabel = "DDG").map { it.id }
        assertEquals(
            listOf("open_url", "clean_url", "qr", "default_search", "youtube_direct"),
            ids
        )
    }

    @Test
    fun cleanUrl_omitsCleanAction() {
        val ctx = PopupContextEngine.analyze("https://example.com/docs", isEditable = false)
        val ids = PopupContextEngine.resolveBarActions(ctx).map { it.id }
        assertEquals(listOf("open_url", "qr", "default_search", "youtube_direct"), ids)
    }

    @Test
    fun singleWord_showsDefineTranslateCustomSearchAndYoutube() {
        val ctx = PopupContextEngine.analyze("serendipity", isEditable = false)
        val actions = PopupContextEngine.resolveBarActions(ctx, searchLabel = "BRV")
        assertEquals(listOf("define", "translate", "default_search", "youtube_direct"), actions.map { it.id })
        assertEquals("BRV", actions.first { it.id == "default_search" }.label)
        assertEquals("YT", actions.first { it.id == "youtube_direct" }.label)
    }

    @Test
    fun editableField_neverIncludesRedundantCopyCutPasteButIncludesUndoWhenAvailable() {
        val ctx = PopupContextEngine.analyze("serendipity", isEditable = true, hasUndo = true)
        val ids = PopupContextEngine.resolveBarActions(ctx).map { it.id }
        assertTrue("undo" in ids)
        assertFalse("copy" in ids)
        assertFalse("cut" in ids)
        assertFalse("paste" in ids)
        assertTrue("default_search" in ids)
        assertTrue("youtube_direct" in ids)
    }

    @Test
    fun pinnedTools_alwaysAppearAndDeduplicateContextMatches() {
        val pinned = listOf(
            ToolSpec("qr", "::", "QR CODE"),
            ToolSpec("translate", "<>", "TRANSLATE"),
            ToolSpec("custom_gpt", "GPT", "CHATGPT", customTarget = "https://chatgpt.com/?q={text}")
        )
        val ctx = PopupContextEngine.analyze("serendipity", isEditable = false)
        val actions = PopupContextEngine.resolveBarActions(ctx, pinnedTools = pinned, searchLabel = "GGL")
        val ids = actions.map { it.id }
        assertEquals(
            listOf("define", "translate", "default_search", "youtube_direct", "qr", "custom_gpt"),
            ids
        )
        // translate should not be duplicated
        assertEquals(1, ids.count { it == "translate" })
    }

    @Test
    fun textGrabMode_includeCopyPutsCopyFirst() {
        val ctx = PopupContextEngine.analyze("145 * 12", isEditable = false)
        val actions = PopupContextEngine.resolveBarActions(ctx, includeCopy = true, searchLabel = "GGL")
        assertEquals("copy", actions.first().id)
        assertEquals("apply_computed", actions[1].id)
        assertEquals("= 1740", actions[1].label)
        assertEquals("1740", actions[1].payload)
        assertTrue(actions.any { it.id == "default_search" })
        assertTrue(actions.any { it.id == "youtube_direct" })
    }

    @Test
    fun paragraphWithLinks_surfacesLinksAndOmitsDefine() {
        val ctx = PopupContextEngine.analyze("Check out https://example.com for more details about this release", isEditable = false)
        val ids = PopupContextEngine.resolveBarActions(ctx).map { it.id }
        assertTrue("extract" in ids)
        assertFalse("define" in ids)
        assertTrue("default_search" in ids)
        assertTrue("youtube_direct" in ids)
    }

    @Test
    fun jsonSnippet_surfacesJsonPretty() {
        val ctx = PopupContextEngine.analyze("{\"name\":\"sel\"}", isEditable = false)
        val ids = PopupContextEngine.resolveBarActions(ctx).map { it.id }
        assertTrue("pipe_json_pretty" in ids)
        assertTrue("default_search" in ids)
        assertTrue("youtube_direct" in ids)
    }
}
