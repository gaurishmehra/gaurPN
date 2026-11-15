package dev.gaurish.outlinelite.ui

import dev.gaurish.outlinelite.transfer.BrowseEntry
import dev.gaurish.outlinelite.transfer.BrowseError
import dev.gaurish.outlinelite.transfer.DeclineReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseFormatTest {

    @Test
    fun byteSizesUseBinaryUnits() {
        assertEquals("0 B", formatBytes(0L))
        assertEquals("1023 B", formatBytes(1023L))
        assertEquals("1 KB", formatBytes(1024L))
        assertEquals("1.5 KB", formatBytes(1536L))
        assertEquals("12.4 MB", formatBytes(13_002_342L))
        assertEquals("4 GB", formatBytes(4L * 1024 * 1024 * 1024))
        assertEquals("—", formatBytes(-1L))
    }

    @Test
    fun fileKindUsesTheExtensionCaseInsensitively() {
        assertEquals(FileKind.IMAGE, fileKind("photo.JPG"))
        assertEquals(FileKind.IMAGE, fileKind("clip.heic"))
        assertEquals(FileKind.VIDEO, fileKind("movie.mkv"))
        assertEquals(FileKind.AUDIO, fileKind("song.flac"))
        assertEquals(FileKind.DOCUMENT, fileKind("report.pdf"))
        assertEquals(FileKind.DOCUMENT, fileKind("notes.md"))
        assertEquals(FileKind.ARCHIVE, fileKind("bundle.zip"))
        assertEquals(FileKind.ARCHIVE, fileKind("archive.tar.gz"))
        assertEquals(FileKind.OTHER, fileKind("README"))
        assertEquals(FileKind.OTHER, fileKind("trailing."))
        assertEquals(FileKind.OTHER, fileKind("weird.xyz"))
    }

    @Test
    fun everyKindHasAShortBadge() {
        for (kind in FileKind.entries) {
            assertTrue(kind.badge.isNotEmpty())
            assertTrue(kind.badge.length <= 4)
        }
    }

    @Test
    fun listingErrorsMapToFriendlyText() {
        assertEquals("Device refused the request", browseErrorMessage(BrowseError.DENIED))
        assertEquals("Path not found", browseErrorMessage(BrowseError.NOT_FOUND))
        assertEquals("Too many requests — slow down", browseErrorMessage(BrowseError.RATE_LIMITED))
        assertEquals("This device can't browse files", browseErrorMessage(BrowseError.UNSUPPORTED))
    }

    @Test
    fun everyDeclineReasonHasText() {
        for (reason in DeclineReason.entries) {
            assertTrue(declineMessage(reason).isNotBlank())
        }
        assertEquals(
            "Too large — the batch is over 4 GB",
            declineMessage(DeclineReason.TOO_LARGE),
        )
        assertEquals(
            "Some files no longer exist",
            declineMessage(DeclineReason.NOT_FOUND),
        )
    }

    @Test
    fun browseEntryKeysAreUniqueEvenWithADuplicate() {
        val entries = listOf(
            BrowseEntry("a.jpg", 1L, 1L, dir = false),
            BrowseEntry("a.jpg", 2L, 2L, dir = false),
            BrowseEntry("a.jpg", 3L, 3L, dir = true),
        )
        val keys = browseEntryKeys(entries)
        assertEquals(keys.size, keys.toSet().size)
        // The first occurrence keeps the plain key; a collision gets the
        // index suffix so Compose cannot crash on a duplicate.
        assertEquals(listOf("false:a.jpg", "false:a.jpg:1", "true:a.jpg"), keys)
    }

    @Test
    fun missingModifiedTimestampIsBlank() {
        assertEquals("", formatModified(0L))
        assertEquals("", formatModified(-1L))
    }
}
