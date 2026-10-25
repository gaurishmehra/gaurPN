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
}
