package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CryptoTest {

    @Test
    fun testKeyExchangeAndEncryption() {
        val aliceKP = Crypto.generateKeyPair()
        val bobKP = Crypto.generateKeyPair()

        val alicePub = Crypto.exportPublicKey(aliceKP.public)
        val bobPub = Crypto.exportPublicKey(bobKP.public)

        assertEquals(65, alicePub.size)
        assertEquals(0x04.toByte(), alicePub[0])

        val aliceSharedKey = Crypto.deriveSharedSecret(aliceKP, bobPub)
        val bobSharedKey = Crypto.deriveSharedSecret(bobKP, alicePub)

        assertEquals(32, aliceSharedKey.size)
        assertArrayEquals(aliceSharedKey, bobSharedKey)

        val plaintext = "Hello from gaurPN in-tunnel E2EE!".toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(12) { 42 }

        val ciphertext = Crypto.encryptChunk(aliceSharedKey, nonce, plaintext, 0, plaintext.size)
        assertNotNull(ciphertext)

        val decrypted = Crypto.decryptChunk(bobSharedKey, nonce, ciphertext)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun testSubKeyDerivationAndAuthTag() {
        val masterToken = "test-cluster-token-xyz-12345"
        val (relayToken, peerKey) = Crypto.deriveSubKeys(masterToken)

        // Relay token should match canonical HMAC vector
        assertEquals("bbd0b8b332a2ba8131e9c750a97a112da3d200a4bc0887f8457ec4507c438e01", relayToken)
        // Peer crypto key must be 32 bytes binary
        assertEquals(32, peerKey.size)

        val message = "offer:tx-1001:bob-device:04abcdef"
        val tag = Crypto.computeAuthTag(peerKey, message)
        assertEquals("b043ff3d60b1633570b44528ec8f77ebe2c9f2b96d7e4b4088c5a5ffca4eaf6a", tag)

        // Valid tag must verify
        org.junit.Assert.assertTrue(Crypto.verifyAuthTag(peerKey, message, tag))

        // Tampered message must fail
        org.junit.Assert.assertFalse(Crypto.verifyAuthTag(peerKey, message + "tampered", tag))

        // Different key must fail
        val (_, otherPeerKey) = Crypto.deriveSubKeys("other-token")
        org.junit.Assert.assertFalse(Crypto.verifyAuthTag(otherPeerKey, message, tag))
    }

    @Test
    fun testSafetyNumberDeterminism() {
        val alicePub = ByteArray(65) { 1 }
        val bobPub = ByteArray(65) { 2 }
        val transferId = "tx-sample-transfer-123"

        val sn1 = Crypto.computeSafetyNumber(alicePub, bobPub, transferId)
        val sn2 = Crypto.computeSafetyNumber(bobPub, alicePub, transferId)

        // Must be identical regardless of peer ordering and match reference vector
        assertEquals("63877 21084 56392 35848 28620 54908", sn1)
        assertEquals(sn1, sn2)
        // Must consist of 6 blocks of 5 digits
        val parts = sn1.split(" ")
        assertEquals(6, parts.size)
        for (part in parts) {
            assertEquals(5, part.length)
            org.junit.Assert.assertTrue(part.all { it.isDigit() })
        }
    }

    @Test
    fun testFilenameSanitizationAndPathTraversal() {
        // Path traversal attempts must be sanitized to pure filenames
        assertEquals("passwd", Crypto.sanitizeFileName("../../etc/passwd"))
        assertEquals("shadow", Crypto.sanitizeFileName("/etc/shadow"))
        assertEquals("system32", Crypto.sanitizeFileName("..\\..\\windows\\system32"))
        assertEquals("nul_lbyte.exe", Crypto.sanitizeFileName("nul\u0000lbyte.exe"))
        assertEquals("hidden", Crypto.sanitizeFileName("...hidden"))

        // Blank/spaces fallback to timestamped name
        val blankClean = Crypto.sanitizeFileName("   ")
        org.junit.Assert.assertTrue(blankClean.startsWith("download_"))

        // Path containment checks
        val baseDir = java.io.File("/tmp/safe_downloads")
        val safeFile = java.io.File(baseDir, "report.pdf")
        val escapeFile = java.io.File(baseDir, "../../etc/passwd")

        org.junit.Assert.assertTrue(Crypto.isPathInsideDirectory(safeFile, baseDir))
        org.junit.Assert.assertFalse(Crypto.isPathInsideDirectory(escapeFile, baseDir))
    }

    @Test
    fun testSafeCollisionResolution() {
        val tempDir = java.nio.file.Files.createTempDirectory("gaurpn_test_collision").toFile()
        try {
            val fileA = java.io.File(tempDir, "document.pdf")
            fileA.writeText("first content")

            val unique1 = Crypto.resolveUniqueFile(tempDir, "document.pdf")
            assertEquals("document (1).pdf", unique1.name)

            unique1.writeText("second content")
            val unique2 = Crypto.resolveUniqueFile(tempDir, "document.pdf")
            assertEquals("document (2).pdf", unique2.name)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
