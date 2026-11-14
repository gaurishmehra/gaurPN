package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionIdTest {

    private val peerKey = "peer-key".toByteArray(Charsets.UTF_8)

    @Test
    fun deterministicForSameInputs() {
        val a = Crypto.deriveSessionId(peerKey, "tx-abc123")
        val b = Crypto.deriveSessionId(peerKey.copyOf(), "tx-abc123")
        assertEquals(a, b)
    }

    @Test
    fun sixteenLowercaseHexChars() {
        val sessionId = Crypto.deriveSessionId(peerKey, "tx-abc123")
        assertEquals(16, sessionId.length)
        assertTrue("not lowercase hex: $sessionId", sessionId.matches(Regex("^[0-9a-f]{16}$")))
    }

    @Test
    fun differsAcrossTransferIds() {
        assertNotEquals(
            Crypto.deriveSessionId(peerKey, "tx-1"),
            Crypto.deriveSessionId(peerKey, "tx-2"),
        )
    }

    @Test
    fun differsAcrossPeerKeys() {
        assertNotEquals(
            Crypto.deriveSessionId(peerKey, "tx-1"),
            Crypto.deriveSessionId("other-peer-key".toByteArray(Charsets.UTF_8), "tx-1"),
        )
    }

    @Test
    fun domainSeparatedFromClipboardKeyMaterial() {
        // The session id must not collide with the file key derivation domain.
        val sessionId = Crypto.deriveSessionId(peerKey, "tx-1")
        val fileKeyHex = Crypto.deriveFileKey(peerKey, "tx-1")
            .joinToString("") { "%02x".format(it) }
        assertNotEquals(sessionId, fileKeyHex.take(16))
    }
}
