package io.github.selclip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupContextEngineTest {

    @Test
    fun nepalPhone_showsCallSmsWaW4bAndCopyOnly() {
        val ctx = PopupContextEngine.analyze("9841234567", isEditable = false)
        val ids = PopupContextEngine.resolveBarActions(ctx).map { it.id }
        assertEquals(listOf("phone_call", "phone_sms", "phone_wa", "phone_w4b", "copy"), ids)
    }

    @Test
    fun dirtyUrl_showsOpenCleanCopyQr() {
        val ctx = PopupContextEngine.analyze("https://example.com/item?utm_source=ads&fbclid=99", isEditable = false)
        val ids = PopupContextEngine.resolveBarActions(ctx).map { it.id }
        assertEquals(listOf("open_url", "clean_url", "copy", "qr"), ids)
    }

    @Test
    fun cleanUrl_omitsCleanAction() {
        val ctx = PopupContextEngine.analyze("https://example.com/docs", isEditable = false)
        val ids = PopupContextEngine.resolveBarActions(ctx).map { it.id }
        assertEquals(listOf("open_url", "copy", "qr"), ids)
    }

    @Test
    fun singleWordReadOnly_omitsCutAndPaste() {
        val ctx = PopupContextEngine.analyze("serendipity", isEditable = false)
        val ids = PopupContextEngine.resolveBarActions(ctx).map { it.id }
        assertEquals(listOf("copy", "define", "translate", "web_search_folder", "youtube_folder"), ids)
        assertFalse("cut" in ids)
        assertFalse("paste" in ids)
    }

    @Test
    fun editableField_includesCutAndPaste() {
        val ctx = PopupContextEngine.analyze("serendipity", isEditable = true)
        val ids = PopupContextEngine.resolveBarActions(ctx).map { it.id }
        assertTrue("copy" in ids)
        assertTrue("cut" in ids)
        assertTrue("paste" in ids)
        assertTrue("define" in ids)
    }

    @Test
    fun mathExpression_prioritizesComputedResult() {
        val ctx = PopupContextEngine.analyze("145 * 12", isEditable = false)
        val actions = PopupContextEngine.resolveBarActions(ctx)
        assertEquals("apply_computed", actions.first().id)
        assertEquals("= 1740", actions.first().label)
        assertEquals("1740", actions.first().payload)
    }

    @Test
    fun paragraphWithLinks_surfacesLinksAndOmitsDefine() {
        val ctx = PopupContextEngine.analyze("Check out https://example.com for more details about this release", isEditable = false)
        val ids = PopupContextEngine.resolveBarActions(ctx).map { it.id }
        assertTrue("extract" in ids)
        assertFalse("define" in ids)
    }

    @Test
    fun jsonSnippet_surfacesJsonPretty() {
        val ctx = PopupContextEngine.analyze("{\"name\":\"sel\"}", isEditable = false)
        val ids = PopupContextEngine.resolveBarActions(ctx).map { it.id }
        assertTrue("pipe_json_pretty" in ids)
    }
}
