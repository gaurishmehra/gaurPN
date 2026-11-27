package dev.gaurish.outlinelite.ui

import dev.gaurish.outlinelite.transfer.BrowseEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseSortTest {

    private fun file(name: String, size: Long = 0, modified: Long = 0) =
        BrowseEntry(name = name, size = size, modified = modified, dir = false)

    private fun dir(name: String, size: Long = 0, modified: Long = 0) =
        BrowseEntry(name = name, size = size, modified = modified, dir = true)

    @Test
    fun nameSortPinsDirectories() {
        val entries = listOf(file("a.txt"), dir("z-folder"), file("b.txt"))
        val ascending = sortBrowseEntries(entries, BrowseSort.NAME, ascending = true)
        val descending = sortBrowseEntries(entries, BrowseSort.NAME, ascending = false)
        assertEquals("z-folder", ascending.first().name)
        assertEquals("z-folder", descending.first().name)
    }

    /**
     * Sorting by date asks "what changed last". Pinning directories first
     * would answer that with "folders first", which is a different question and
     * buries a folder that has not been touched in a year.
     */
    @Test
    fun modifiedSortDoesNotPinDirectories() {
        val entries = listOf(
            dir("stale-folder", modified = 10),
            file("fresh", modified = 900),
            file("recent", modified = 500),
        )
        assertEquals(
            listOf("fresh", "recent", "stale-folder"),
            sortBrowseEntries(entries, BrowseSort.MODIFIED, ascending = false).map { it.name },
        )
        assertEquals(
            listOf("stale-folder", "recent", "fresh"),
            sortBrowseEntries(entries, BrowseSort.MODIFIED, ascending = true).map { it.name },
        )
    }

    @Test
    fun nameSortIsCaseInsensitiveInBothDirections() {
        val entries = listOf(file("beta"), file("Alpha"), file("gamma"))
        assertEquals(
            listOf("Alpha", "beta", "gamma"),
            sortBrowseEntries(entries, BrowseSort.NAME, ascending = true).map { it.name },
        )
        assertEquals(
            listOf("gamma", "beta", "Alpha"),
            sortBrowseEntries(entries, BrowseSort.NAME, ascending = false).map { it.name },
        )
    }

    @Test
    fun modifiedSortPutsUnknownTimestampsLast() {
        val entries = listOf(
            file("new", modified = 300),
            file("unknown", modified = 0),
            file("old", modified = 100),
        )
        assertEquals(
            listOf("new", "old", "unknown"),
            sortBrowseEntries(entries, BrowseSort.MODIFIED, ascending = false).map { it.name },
        )
        assertEquals(
            listOf("old", "new", "unknown"),
            sortBrowseEntries(entries, BrowseSort.MODIFIED, ascending = true).map { it.name },
        )
    }

    @Test
    fun sizeSortOrdersBySizeAcrossTypes() {
        val entries = listOf(dir("folder"), file("small", 10), file("large", 1000))
        assertEquals(
            listOf("large", "small", "folder"),
            sortBrowseEntries(entries, BrowseSort.SIZE, ascending = false).map { it.name },
        )
        assertEquals(
            listOf("folder", "small", "large"),
            sortBrowseEntries(entries, BrowseSort.SIZE, ascending = true).map { it.name },
        )
    }

    @Test
    fun tiesFallBackToName() {
        val entries = listOf(file("b", modified = 5), file("a", modified = 5))
        assertEquals(
            listOf("a", "b"),
            sortBrowseEntries(entries, BrowseSort.MODIFIED, ascending = true).map { it.name },
        )
    }
}
