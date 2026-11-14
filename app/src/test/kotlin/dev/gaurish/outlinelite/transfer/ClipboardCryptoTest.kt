package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ClipboardCryptoTest {

    @Test
    fun clipboardKeyIsDomainSeparatedFromPeerKey() {
        val peerKey = ByteArray(32) { it.toByte() }
        val clipboardKey = Crypto.deriveClipboardKey(peerKey)
        assertEquals(32, clipboardKey.size)
        assertFalse(clipboardKey.contentEquals(peerKey))
    }

    @Test
    fun gcmRoundTripBindsAad() {
        val key = Crypto.deriveClipboardKey(ByteArray(32) { 7 })
        val nonce = ByteArray(12) { 3 }
        val plaintext = "hello clipboard".toByteArray(Charsets.UTF_8)
        val aad = "clipboard:dev:msg:1:text:text/plain".toByteArray(Charsets.UTF_8)

        val ciphertext = Crypto.encryptGcm(key, nonce, plaintext, aad)
        assertArrayEquals(plaintext, Crypto.decryptGcm(key, nonce, ciphertext, aad))

        val wrongAad = "clipboard:dev:msg:1:text:text/html".toByteArray(Charsets.UTF_8)
        assertThrows(Exception::class.java) {
            Crypto.decryptGcm(key, nonce, ciphertext, wrongAad)
        }
    }

    @Test
    fun sha256HexIsStable() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            Crypto.sha256Hex("hello".toByteArray(Charsets.UTF_8)),
        )
    }
}
