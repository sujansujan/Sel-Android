package io.github.selclip.core

import io.github.selclip.data.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextActionResolverTest {

    @Test
    fun nepalPhone_showsCallSmsWaW4bSearchAndYoutubeWithoutCopy() {
        val ctx = ContextActionResolver.analyze("9841234567", isEditable = false)
        val ids = ContextActionResolver.resolveBarActions(ctx, searchLabel = "Google").map { it.id }
        assertEquals(
            listOf("phone_call", "phone_sms", "phone_wa", "phone_w4b", "default_search", "youtube_direct"),
            ids
        )
        assertFalse("copy" in ids)
        assertFalse("cut" in ids)
        assertFalse("paste" in ids)
    }

    @Test
    fun dirtyUrl_showsOpenCleanQrSearchYoutube_cleanUrlOmitsClean() {
        val dirtyCtx = ContextActionResolver.analyze("https://example.com/item?utm_source=ads&fbclid=99")
        assertEquals(
            listOf("open_url", "clean_url", "qr", "default_search", "youtube_direct"),
            ContextActionResolver.resolveBarActions(dirtyCtx).map { it.id }
        )

        val cleanCtx = ContextActionResolver.analyze("https://example.com/docs")
        assertEquals(
            listOf("open_url", "qr", "default_search", "youtube_direct"),
            ContextActionResolver.resolveBarActions(cleanCtx).map { it.id }
        )
    }

    @Test
    fun singleWord_showsDefineTranslateCustomSearchAndYoutube() {
        val ctx = ContextActionResolver.analyze("serendipity")
        val actions = ContextActionResolver.resolveBarActions(ctx, searchLabel = "Brave")
        assertEquals(listOf("define", "translate", "default_search", "youtube_direct"), actions.map { it.id })
        assertEquals("Brave", actions.first { it.id == "default_search" }.label)
        assertEquals("YT", actions.first { it.id == "youtube_direct" }.label)
    }

    @Test
    fun editableWithUndo_includesUndoAndNeverIncludesCopyCutPaste() {
        val ctx = ContextActionResolver.analyze("serendipity", isEditable = true, hasUndo = true)
        val ids = ContextActionResolver.resolveBarActions(ctx).map { it.id }
        assertTrue("undo" in ids)
        assertFalse("copy" in ids)
        assertFalse("cut" in ids)
        assertFalse("paste" in ids)
    }

    @Test
    fun pinnedTools_alwaysAppearAndDeduplicateContextMatches() {
        val pinned = listOf(
            ToolSpec("qr", "QR", "QR Code"),
            ToolSpec("translate", "TR", "Translate"),
            ToolSpec("custom_gpt", "GPT", "ChatGPT", customTarget = "https://chatgpt.com/?q={text}")
        )
        val ctx = ContextActionResolver.analyze("serendipity")
        val actions = ContextActionResolver.resolveBarActions(
            ctx,
            pinnedTools = pinned,
            searchLabel = "DDG",
            defaultSearchUrl = "https://duckduckgo.com/?q={text}"
        )
        val ids = actions.map { it.id }
        assertEquals(
            listOf("define", "translate", "default_search", "youtube_direct", "qr", "custom_gpt"),
            ids
        )
        assertEquals(1, ids.count { it == "translate" })
        assertEquals("https://duckduckgo.com/?q={text}", actions.first { it.id == "default_search" }.faviconUrl)
        assertEquals("https://chatgpt.com/?q={text}", actions.first { it.id == "custom_gpt" }.faviconUrl)
    }

    @Test
    fun mathJsonAndParagraphLinks_surfaceRightContextActions() {
        val mathActions = ContextActionResolver.resolveBarActions(ContextActionResolver.analyze("145 * 12"))
        assertEquals("apply_computed", mathActions.first().id)
        assertEquals("= 1740", mathActions.first().label)
        assertEquals("1740", mathActions.first().payload)

        val jsonIds = ContextActionResolver.resolveBarActions(ContextActionResolver.analyze("{\"a\":1}")).map { it.id }
        assertTrue("pipe_json_pretty" in jsonIds)

        val paraIds = ContextActionResolver.resolveBarActions(
            ContextActionResolver.analyze("Check out https://example.com for more details about this release")
        ).map { it.id }
        assertTrue("extract" in paraIds)
        assertFalse("define" in paraIds)
    }

    @Test
    fun customBarOrder_controlsExactPlacementOfSearchYoutubePinnedAndContextSlots() {
        val pinned = listOf(ToolSpec("qr", "QR", "QR Code"))
        val ctx = ContextActionResolver.analyze("serendipity")
        val customOrder = listOf(
            ContextActionResolver.SLOT_YOUTUBE,
            "qr",
            ContextActionResolver.SLOT_DEFAULT_SEARCH,
            ContextActionResolver.SLOT_CONTEXT
        )
        val ids = ContextActionResolver.resolveBarActions(
            ctx = ctx,
            pinnedTools = pinned,
            barOrder = customOrder
        ).map { it.id }

        assertEquals(
            listOf("youtube_direct", "qr", "default_search", "define", "translate"),
            ids
        )
    }
}
