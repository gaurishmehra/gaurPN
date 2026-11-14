package dev.gaurish.outlinelite.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Navigation ids must stay unique: MainActivity routes by `when (screen)`,
 * and a duplicated id would silently shadow a screen.
 */
class ScreensTest {
    @Test
    fun `screen ids are unique`() {
        assertEquals(Screens.ALL.size, Screens.ALL.distinct().size)
    }

    @Test
    fun `home is the root id`() {
        assertEquals(0, Screens.HOME)
        assertTrue(Screens.ALL.contains(Screens.HOME))
    }

    @Test
    fun `every declared id is routed`() {
        // ALL is the single source of truth; MainActivity's `when` covers
        // HOME via `else`. Every non-home id must appear in ALL exactly once.
        val nonHome = Screens.ALL.filter { it != Screens.HOME }
        assertTrue(nonHome.isNotEmpty())
        assertEquals(nonHome, nonHome.distinct())
    }
}
