package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextShareActionsTest {

    @Test
    fun validUrlGetsTheCanonicalOpenTarget() {
        assertEquals(
            "https://example.com/Path",
            TextShareActions.openTarget("HTTPS://EXAMPLE.COM/Path", "url"),
        )
        assertEquals(
            "https://example.com/path?q=1",
            TextShareActions.openTarget(" https://example.com/path?q=1 ", "url"),
        )
    }

    @Test
    fun invalidUrlHidesOpen() {
        val rejected = listOf(
            "javascript:alert(1)",
            "file:///etc/passwd",
            "steam://store",
            "https://example.com/a b",
            "example.com",
            "",
        )
        for (raw in rejected) {
            assertNull("expected no open action: $raw", TextShareActions.openTarget(raw, "url"))
        }
    }

    @Test
    fun plainTextNeverGetsAnOpenAction() {
        assertNull(TextShareActions.openTarget("https://example.com", "text"))
        assertNull(TextShareActions.openTarget("just some words", "text"))
    }
}
