package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScreenshotQueryTest {

    @Test
    fun modernQueryUsesAnExactRelativePath() {
        val query = buildScreenshotQuery(29)

        assertEquals("relative_path = ?", query.selection)
        assertArrayEquals(arrayOf("Pictures/Screenshots/"), query.selectionArgs)
        // A prefix match would also select Pictures/ScreenshotsBackup.
        assertFalse(query.selection.contains("LIKE"))
        assertFalse(query.selectionArgs[0].contains("%"))
    }

    @Test
    fun modernQueryIsUsedForEveryApiFromQUp() {
        for (api in listOf(29, 30, 33, 35)) {
            assertEquals("relative_path = ?", buildScreenshotQuery(api).selection)
        }
    }

    @Test
    fun legacyQueryRequiresTheScreenshotsSegment() {
        val query = buildScreenshotQuery(28)

        assertEquals("_data LIKE ?", query.selection)
        assertArrayEquals(arrayOf("%/Pictures/Screenshots/%"), query.selectionArgs)
        // The trailing slash keeps ScreenshotsBackup out of the result.
        assertFalse(query.selectionArgs[0].contains("ScreenshotsBackup"))
    }
}
