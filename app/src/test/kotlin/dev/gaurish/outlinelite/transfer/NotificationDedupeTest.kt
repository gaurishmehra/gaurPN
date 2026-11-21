package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDedupeTest {

    @Test
    fun installedSourceAppIsSuppressed() {
        val installed = mutableListOf<String>()
        val suppress = NotificationDedupe.shouldSuppress(
            packageName = "org.telegram.messenger",
            selfPackage = "dev.gaurish.outlinelite",
        ) { pkg ->
            installed += pkg
            true
        }
        assertTrue(suppress)
        assertEquals(listOf("org.telegram.messenger"), installed)
    }

    @Test
    fun missingSourceAppIsShown() {
        assertFalse(
            NotificationDedupe.shouldSuppress(
                packageName = "org.telegram.messenger",
                selfPackage = "dev.gaurish.outlinelite",
            ) { false }
        )
    }

    @Test
    fun desktopPayloadWithoutPackageIsShown() {
        assertFalse(
            NotificationDedupe.shouldSuppress(
                packageName = "",
                selfPackage = "dev.gaurish.outlinelite",
            ) { error("should not probe the package manager") }
        )
    }

    @Test
    fun ownPackageIsNeverSuppressed() {
        assertFalse(
            NotificationDedupe.shouldSuppress(
                packageName = "dev.gaurish.outlinelite",
                selfPackage = "dev.gaurish.outlinelite",
            ) { error("should not probe the package manager") }
        )
    }
}
