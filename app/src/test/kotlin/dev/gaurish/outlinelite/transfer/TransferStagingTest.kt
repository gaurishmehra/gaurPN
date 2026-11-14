package dev.gaurish.outlinelite.transfer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the traversal guard shared by the v1, v2, and v3 receive paths. The
 * transfer id is peer-supplied and becomes part of the staging file name, so
 * a crafted offer must never steer a write outside Downloads.
 */
class TransferStagingTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val validId = "tx-00112233445566778899aabbccddeeff"

    @Test
    fun validIdBuildsHiddenStagingFilesInsideDownloads() {
        val downloads = temp.newFolder("Downloads")

        val files = TransferStaging.forTransfer(downloads, "notes.txt", validId)

        assertEquals(File(downloads, ".notes.txt.$validId.part"), files!!.part)
        assertEquals(File(downloads, ".notes.txt.$validId.state"), files.state)
        assertTrue(Crypto.isPathInsideDirectory(files.part, downloads))
        assertTrue(Crypto.isPathInsideDirectory(files.state, downloads))
    }

    @Test
    fun traversalTransferIdsAreRejected() {
        val downloads = temp.newFolder("Downloads")
        val badIds = listOf(
            "../../evil",
            "../evil",
            "..",
            "tx-../../../../evil",
            "tx-00112233445566778899aabbccddeef",
            "tx-00112233445566778899aabbccddeeff0",
            "tx-00112233445566778899AABBCCDDEEFF",
            "TX-00112233445566778899aabbccddeeff",
            "tx-00112233445566778899aabbccddeefg",
            "",
        )
        for (id in badIds) {
            assertNull("accepted transfer id: $id", TransferStaging.forTransfer(downloads, "notes.txt", id))
        }
    }

    @Test
    fun escapedCleanNameIsRejectedByTheInsideDownloadsCheck() {
        val downloads = temp.newFolder("Downloads")
        // The id is well-formed, so only the canonical-path check can catch
        // this. Every receive path sanitizes the name first; this is the
        // defense-in-depth assertion behind that. (A leading "../" is
        // neutralized by the staging dot prefix, so the escape has to sit
        // deeper in the name.)
        assertNull(TransferStaging.forTransfer(downloads, "a/../../evil", validId))
        assertNull(TransferStaging.forTransfer(downloads, "a/b/../../../evil", validId))
    }

    @Test
    fun transferIdShapeMatchesDerivedIds() {
        val key = ByteArray(32) { it.toByte() }
        assertTrue(Crypto.isTransferId(Crypto.deriveTransferId(key, "a".repeat(64))))
        assertTrue(Crypto.isTransferId(validId))
        assertFalse(Crypto.isTransferId("tx-00112233445566778899aabbccddeeff "))
        assertFalse(Crypto.isTransferId(" tx-00112233445566778899aabbccddeeff"))
        assertFalse(Crypto.isTransferId("tx-00112233445566778899aabbccddeeff/../x"))
    }
}
