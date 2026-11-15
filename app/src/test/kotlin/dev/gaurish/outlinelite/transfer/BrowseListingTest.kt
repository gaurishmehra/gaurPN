package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the virtual path scheme: root listing, next-segment grouping, files
 * directly in a collection, the entry cap, and the listing-entry -> fetch
 * path -> resolver round trip.
 */
class BrowseListingTest {

    private fun row(
        collection: MediaCollection,
        relativePath: String,
        displayName: String,
        size: Long = 1L,
        modified: Long = 1L,
    ) = MediaRow(collection, relativePath, displayName, size, modified)

    @Test
    fun rootListsTheSixCollectionsAsDirectories() {
        val listing = BrowseListingBuilder.build(emptyList(), "")
        assertEquals(
            listOf("DCIM", "Documents", "Download", "Movies", "Music", "Pictures"),
            listing.entries.map { it.name },
        )
        assertTrue(listing.entries.all { it.dir })
        assertFalse(listing.truncated)
    }

    @Test
    fun rootIgnoresRows() {
        val listing = BrowseListingBuilder.build(
            listOf(row(MediaCollection.PICTURES, "Pictures/", "a.jpg")),
            "",
        )
        assertEquals(6, listing.entries.size)
    }

    @Test
    fun oneLevelGroupsTheNextSegmentAndListsDirectFiles() {
        val rows = listOf(
            row(MediaCollection.PICTURES, "Pictures/Camera/", "IMG_1.jpg"),
            row(MediaCollection.PICTURES, "Pictures/Screenshots/", "shot.png"),
            row(MediaCollection.PICTURES, "Pictures/", "cover.jpg"),
        )
        val listing = BrowseListingBuilder.build(rows, "Pictures")
        assertEquals(listOf("Camera", "Screenshots", "cover.jpg"), listing.entries.map { it.name })
        assertEquals(listOf(true, true, false), listing.entries.map { it.dir })
    }

    @Test
    fun twoLevelsGroupOnlyTheNextSegment() {
        val rows = listOf(
            row(MediaCollection.PICTURES, "Pictures/Screenshots/2024/", "a.png"),
            row(MediaCollection.PICTURES, "Pictures/Screenshots/", "b.png"),
        )
        val listing = BrowseListingBuilder.build(rows, "Pictures/Screenshots")
        assertEquals(listOf("2024", "b.png"), listing.entries.map { it.name })
        assertEquals(listOf(true, false), listing.entries.map { it.dir })
    }

    @Test
    fun deepRowsCollapseIntoTheFirstNextSegment() {
        val rows = listOf(
            row(MediaCollection.PICTURES, "Pictures/Camera/2024/07/", "a.jpg"),
        )
        val listing = BrowseListingBuilder.build(rows, "Pictures")
        assertEquals(listOf("Camera"), listing.entries.map { it.name })
    }

    @Test
    fun rowsFromOtherCollectionsAreIgnored() {
        val rows = listOf(
            row(MediaCollection.MOVIES, "Movies/", "film.mp4"),
            row(MediaCollection.PICTURES, "Pictures/", "photo.jpg"),
        )
        val listing = BrowseListingBuilder.build(rows, "Pictures")
        assertEquals(listOf("photo.jpg"), listing.entries.map { it.name })
    }

    @Test
    fun unknownRootIsAnEmptyListing() {
        val listing = BrowseListingBuilder.build(
            listOf(row(MediaCollection.PICTURES, "Pictures/", "a.jpg")),
            "Bogus",
        )
        assertTrue(listing.entries.isEmpty())
        assertFalse(listing.truncated)
    }

    @Test
    fun entriesSortCaseInsensitivelyWithDirectoriesFirst() {
        val rows = listOf(
            row(MediaCollection.DOCUMENTS, "Documents/", "gamma.txt"),
            row(MediaCollection.DOCUMENTS, "Documents/", "Alpha.txt"),
            row(MediaCollection.DOCUMENTS, "Documents/zeta/", "z.txt"),
            row(MediaCollection.DOCUMENTS, "Documents/Beta/", "b.txt"),
        )
        val listing = BrowseListingBuilder.build(rows, "Documents")
        assertEquals(
            listOf("Beta", "zeta", "Alpha.txt", "gamma.txt"),
            listing.entries.map { it.name },
        )
    }

    @Test
    fun entryCapCutsAtOneThousandAndSetsTruncated() {
        val atCap = (0 until BrowseListingBuilder.MAX_ENTRIES).map {
            row(MediaCollection.DOCUMENTS, "Documents/", "f-$it.txt")
        }
        val exact = BrowseListingBuilder.build(atCap, "Documents")
        assertEquals(BrowseListingBuilder.MAX_ENTRIES, exact.entries.size)
        assertFalse(exact.truncated)

        val over = atCap + row(MediaCollection.DOCUMENTS, "Documents/", "one-more.txt")
        val cut = BrowseListingBuilder.build(over, "Documents")
        assertEquals(BrowseListingBuilder.MAX_ENTRIES, cut.entries.size)
        assertTrue(cut.truncated)
    }

    @Test
    fun directoryModifiedIsTheNewestChild() {
        val rows = listOf(
            row(MediaCollection.PICTURES, "Pictures/Camera/", "a.jpg", modified = 100L),
            row(MediaCollection.PICTURES, "Pictures/Camera/2024/", "b.jpg", modified = 500L),
        )
        val camera = BrowseListingBuilder.build(rows, "Pictures").entries.single { it.dir }
        assertEquals("Camera", camera.name)
        assertEquals(500L, camera.modified)
        assertEquals(0L, camera.size)
    }

    @Test
    fun fileMetadataSurvivesTheListing() {
        val rows = listOf(
            row(MediaCollection.MUSIC, "Music/Albums/", "track.flac", size = 42_000_000L, modified = 1_767_225_300_000L),
        )
        val entry = BrowseListingBuilder.build(rows, "Music/Albums").entries.single()
        assertEquals("track.flac", entry.name)
        assertEquals(42_000_000L, entry.size)
        assertEquals(1_767_225_300_000L, entry.modified)
        assertFalse(entry.dir)
    }

    @Test
    fun invalidNamesAreFilteredBeforeTheyReachTheWire() {
        val rows = listOf(
            row(MediaCollection.PICTURES, "Pictures/", "ok.jpg"),
            // A 300-char file name would be skipped by every requester.
            row(MediaCollection.PICTURES, "Pictures/", "x".repeat(300)),
            row(MediaCollection.PICTURES, "Pictures/", "bad\u0000name.jpg"),
            row(MediaCollection.PICTURES, "Pictures/Good/", "a.jpg"),
            // "." and ".." segments, and over-long directory names, are not
            // listable either.
            row(MediaCollection.PICTURES, "Pictures/../", "escape.jpg"),
            row(MediaCollection.PICTURES, "Pictures/./", "dot.jpg"),
            row(MediaCollection.PICTURES, "Pictures/" + "y".repeat(300) + "/", "b.jpg"),
        )
        val listing = BrowseListingBuilder.build(rows, "Pictures")
        assertEquals(listOf("Good", "ok.jpg"), listing.entries.map { it.name })
        assertFalse(listing.truncated)
    }

    @Test
    fun listingEntryToFetchPathToResolverRoundTrips() {
        val rows = listOf(
            row(MediaCollection.PICTURES, "Pictures/Screenshots/", "Screenshot_03.png"),
            row(MediaCollection.PICTURES, "Pictures/", "cover.jpg"),
            row(MediaCollection.MUSIC, "Music/", "song.mp3"),
            row(MediaCollection.DOWNLOAD, "Download/", "manual.pdf"),
        )
        for (expected in rows) {
            val dir = BrowseListingBuilder.normalizeDir(expected.relativePath)
            val entry = BrowseListingBuilder.build(rows, dir).entries
                .first { !it.dir && it.name == expected.displayName }
            val path = if (dir.isEmpty()) entry.name else "$dir/${entry.name}"
            assertEquals(expected, BrowseListingBuilder.resolve(rows, path))
            assertEquals(path, BrowseListingBuilder.fetchPath(expected))
        }
    }

    @Test
    fun fetchPathExampleIsPinned() {
        val row = row(MediaCollection.PICTURES, "Pictures/Screenshots/", "Screenshot_03.png")
        assertEquals("Pictures/Screenshots/Screenshot_03.png", BrowseListingBuilder.fetchPath(row))
    }

    @Test
    fun resolverIsCollectionScopedAndRejectsBadPaths() {
        val pictures = row(MediaCollection.PICTURES, "Pictures/", "same.jpg")
        val movies = row(MediaCollection.MOVIES, "Movies/", "same.jpg")
        val rows = listOf(pictures, movies)
        assertEquals(pictures, BrowseListingBuilder.resolve(rows, "Pictures/same.jpg"))
        assertEquals(movies, BrowseListingBuilder.resolve(rows, "Movies/same.jpg"))

        assertNull(BrowseListingBuilder.resolve(rows, "Bogus/same.jpg"))
        assertNull(BrowseListingBuilder.resolve(rows, "Pictures/missing.jpg"))
        assertNull(BrowseListingBuilder.resolve(rows, "Pictures/../same.jpg"))
        assertNull(BrowseListingBuilder.resolve(rows, ""))
        // A root name is a directory, never a file.
        assertNull(BrowseListingBuilder.resolve(rows, "Pictures"))
    }

    @Test
    fun trailingSlashIsNormalized() {
        assertEquals("Pictures/Screenshots", BrowseListingBuilder.normalizeDir("Pictures/Screenshots/"))
        assertEquals("Pictures", BrowseListingBuilder.normalizeDir("Pictures"))
        assertEquals("", BrowseListingBuilder.normalizeDir("/"))
    }
}
