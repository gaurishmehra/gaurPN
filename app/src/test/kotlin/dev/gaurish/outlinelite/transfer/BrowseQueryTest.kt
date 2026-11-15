package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the listing query shape: the LIKE escape and the SQL LIMIT that bounds
 * cursor materialization.
 */
class BrowseQueryTest {

    @Test
    fun subtreeQueryCarriesTheLimitAndEscapeClause() {
        val query = buildBrowseQuery("Pictures/Screenshots")
        assertEquals("relative_path LIKE ? ESCAPE '\\'", query.selection)
        assertArrayEquals(arrayOf("Pictures/Screenshots/%"), query.selectionArgs)
        assertEquals(RequestCodec.MAX_LISTING_ENTRIES + 50, query.limit)
        // The LIMIT is above the entry cap (so grouping can still fill it)
        // but far below an unbounded cursor.
        assertTrue(query.limit > RequestCodec.MAX_LISTING_ENTRIES)
        assertEquals(1_050, BROWSE_QUERY_LIMIT)
    }

    @Test
    fun likeMetacharactersAreEscaped() {
        assertArrayEquals(
            arrayOf("Pic\\%\\_\\\\tures/%"),
            buildBrowseQuery("Pic%_\\tures").selectionArgs,
        )
    }
}
