package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Storage root: the all-files-access gate on the root listing, the
 * File-based listing rules (dotfiles, symlinks, ordering, cap), the path
 * scheme, and the containment check that keeps a fetch inside shared storage.
 */
class StorageBrowseTest {

    private fun row(
        name: String,
        size: Long = 1L,
        modified: Long = 1L,
        dir: Boolean = false,
        symlink: Boolean = false,
    ) = StorageRow(name, size, modified, dir, symlink)

    @Test
    fun rootHidesStorageWithoutAllFilesAccess() {
        val listing = BrowseListingBuilder.build(emptyList(), "")
        assertEquals(
            listOf("DCIM", "Documents", "Download", "Movies", "Music", "Pictures"),
            listing.entries.map { it.name },
        )
        assertFalse(listing.entries.any { it.name == BrowseListingBuilder.STORAGE_ROOT })
    }

    @Test
    fun rootListsStorageOnlyWithAllFilesAccess() {
        val listing = BrowseListingBuilder.build(emptyList(), "", includeStorage = true)
        assertEquals(
            listOf(
                "DCIM",
                "Documents",
                "Download",
                "Movies",
                "Music",
                "Pictures",
                "Storage",
            ),
            listing.entries.map { it.name },
        )
        assertTrue(listing.entries.all { it.dir })
        assertFalse(listing.truncated)
    }

    @Test
    fun storageListingSortsDirectoriesFirstCaseInsensitively() {
        val listing = BrowseListingBuilder.buildStorage(
            listOf(
                row("gamma.txt"),
                row("Alpha.txt"),
                row("zeta", dir = true),
                row("Beta", dir = true),
            ),
        )
        assertEquals(listOf("Beta", "zeta", "Alpha.txt", "gamma.txt"), listing.entries.map { it.name })
        assertEquals(listOf(true, true, false, false), listing.entries.map { it.dir })
    }

    @Test
    fun storageListingSkipsDotfilesAndSymlinks() {
        val listing = BrowseListingBuilder.buildStorage(
            listOf(
                row(".hidden"),
                row(".git", dir = true),
                row("ok.txt"),
                row("link", dir = true, symlink = true),
                row("linked-file.txt", symlink = true),
            ),
        )
        assertEquals(listOf("ok.txt"), listing.entries.map { it.name })
    }

    @Test
    fun storageListingDropsNamesTheWireWouldReject() {
        val listing = BrowseListingBuilder.buildStorage(
            listOf(
                row("ok.txt"),
                row("bad/name.txt"),
                row(".."),
                row("bad\u0000name.txt"),
                row("x".repeat(RequestCodec.MAX_ENTRY_NAME_CHARS + 1)),
            ),
        )
        assertEquals(listOf("ok.txt"), listing.entries.map { it.name })
    }

    @Test
    fun storageListingCapsAtOneThousandAndSetsTruncated() {
        val atCap = (0 until BrowseListingBuilder.MAX_ENTRIES).map { row("f-$it.txt") }
        val exact = BrowseListingBuilder.buildStorage(atCap)
        assertEquals(BrowseListingBuilder.MAX_ENTRIES, exact.entries.size)
        assertFalse(exact.truncated)

        val over = atCap + row("one-more.txt")
        val cut = BrowseListingBuilder.buildStorage(over)
        assertEquals(BrowseListingBuilder.MAX_ENTRIES, cut.entries.size)
        assertTrue(cut.truncated)
    }

    @Test
    fun storageMetadataSurvivesTheListingAndDirectoriesReportNoSize() {
        val listing = BrowseListingBuilder.buildStorage(
            listOf(
                row("Download", dir = true, size = 4096L, modified = 100L),
                row("manual.pdf", size = 42_000L, modified = 1_767_225_300_000L),
            ),
        )
        val dir = listing.entries.first { it.dir }
        assertEquals(0L, dir.size)
        assertEquals(100L, dir.modified)
        val file = listing.entries.first { !it.dir }
        assertEquals(42_000L, file.size)
        assertEquals(1_767_225_300_000L, file.modified)
    }

    @Test
    fun storageSubpathPinsTheScheme() {
        assertEquals("", BrowseListingBuilder.storageSubpath("Storage"))
        assertEquals(
            "Download/manual.pdf",
            BrowseListingBuilder.storageSubpath("Storage/Download/manual.pdf"),
        )
        assertEquals(
            "DCIM/Camera/IMG_1.jpg",
            BrowseListingBuilder.storageSubpath("Storage/DCIM/Camera/IMG_1.jpg"),
        )
    }

    @Test
    fun storageSubpathRejectsEscapesAndHiddenSegments() {
        val rejected = listOf(
            "Storage/../etc/passwd",
            "Storage/./Download",
            "Storage/Download/../Documents",
            "Storage/.hidden",
            "Storage/Download/.hidden",
            "Storage//Download",
            "Storage/",
            "Storage/Download/",
            "StorageX/Download",
            "Pictures/a.jpg",
            "",
        )
        for (path in rejected) {
            assertNull(path, BrowseListingBuilder.storageSubpath(path))
        }
    }

    @Test
    fun storagePathsRouteToTheFileBackend() {
        // The routing decision is what makes a Storage fetch use the File
        // API and a file:// URI instead of a MediaStore content URI.
        assertEquals(
            BrowseBackend.STORAGE,
            BrowseListingBuilder.backendFor("Storage"),
        )
        assertEquals(
            BrowseBackend.STORAGE,
            BrowseListingBuilder.backendFor("Storage/Download/manual.pdf"),
        )
        assertEquals(
            BrowseBackend.MEDIA_STORE,
            BrowseListingBuilder.backendFor("Pictures/Screenshots/a.png"),
        )
        assertNull(BrowseListingBuilder.backendFor("Bogus/a.txt"))
        assertNull(BrowseListingBuilder.backendFor("Storage/.hidden"))
        assertNull(BrowseListingBuilder.backendFor(""))
    }

    @Test
    fun containmentAcceptsOnlyTheRootAndItsDescendants() {
        val root = "/storage/emulated/0"
        assertTrue(BrowseListingBuilder.isInsideStorageRoot(root, root))
        assertTrue(
            BrowseListingBuilder.isInsideStorageRoot(root, "$root/Download/manual.pdf"),
        )
        assertTrue(BrowseListingBuilder.isInsideStorageRoot("$root/", "$root/Download"))
        assertFalse(
            BrowseListingBuilder.isInsideStorageRoot(root, "/storage/emulated/0-backup/a.pdf"),
        )
        // The check is a prefix test on already-canonical paths; ".." is
        // rejected earlier by storageSubpath, and the adapter canonicalizes
        // before calling this, so an escape arrives here as "/data/...".
        assertFalse(BrowseListingBuilder.isInsideStorageRoot(root, "/data/local/tmp/x"))
        assertFalse(BrowseListingBuilder.isInsideStorageRoot(root, "/storage/emulated/1/x"))
    }

    @Test
    fun listingEntryToFetchPathRoundTripsThroughTheStorageScheme() {
        val listing = BrowseListingBuilder.buildStorage(
            listOf(
                row("Camera", dir = true),
                row("manual.pdf", size = 9L),
            ),
        )
        val fetchPaths = listing.entries.map { "Storage/Download/${it.name}" }
        assertEquals(
            listOf("Download/Camera", "Download/manual.pdf"),
            fetchPaths.map { BrowseListingBuilder.storageSubpath(it) },
        )
    }
}
