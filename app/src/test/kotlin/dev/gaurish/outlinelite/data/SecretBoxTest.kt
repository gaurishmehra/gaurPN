package dev.gaurish.outlinelite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Fake SecretBox mirroring the sealed layout of KeystoreSecretBox:
 * 12-byte IV || ciphertext+tag, with a fixed in-memory AES key so the
 * migration logic can be exercised on the JVM.
 */
private class FixedAesSecretBox : SecretBox {

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val iv = sealed.copyOfRange(0, 12)
        val ciphertext = sealed.copyOfRange(12, sealed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }
}

class SecretBoxTest {

    private val box = FixedAesSecretBox()

    @Test
    fun sealedValueRoundTrips() {
        val token = "cluster-secret-token-0123456789"
        val sealed = sealSecret(token, box)
        assertEquals(token, unwrapSecret(sealed, box))
    }

    @Test
    fun sealedValueCarriesGk1Marker() {
        val sealed = sealSecret("value", box)
        assertTrue("stored secrets must start with gk1:, got: $sealed", sealed.startsWith("gk1:"))
        assertNotEquals("value", sealed.removePrefix("gk1:"))
    }

    @Test
    fun eachSealIsRandomized() {
        // Randomized encryption: same plaintext, different ciphertexts.
        val a = sealSecret("same", box)
        val b = sealSecret("same", box)
        assertNotEquals(a, b)
        assertEquals("same", unwrapSecret(a, box))
        assertEquals("same", unwrapSecret(b, box))
    }

    @Test
    fun legacyPlaintextIsReturnedAsIs() {
        // Migration source: values written before sealing pass through untouched.
        assertEquals("legacy-plaintext-token", unwrapSecret("legacy-plaintext-token", box))
        assertEquals("", unwrapSecret("", box))
    }

    @Test
    fun corruptGk1ValueFailsClosedAsEmpty() {
        val sealed = sealSecret("some-token", box)
        val corruptBase64 = "gk1:!!!not-base64!!!"
        assertEquals("", unwrapSecret(corruptBase64, box))

        // Flip a ciphertext byte: GCM authentication must reject it.
        val bytes = sealed.removePrefix(SECRET_MARKER).toCharArray().also { it[10] = 'A' }
        assertEquals("", unwrapSecret(SECRET_MARKER + String(bytes), box))
    }

    @Test
    fun truncatedGk1ValueFailsClosedAsEmpty() {
        assertEquals("", unwrapSecret("gk1:AAAA", box))
        assertEquals("", unwrapSecret("gk1:", box))
    }

    @Test
    fun plainSecretBoxRoundTripsWithoutCrypto() {
        val sealed = sealSecret("legacy", PlainSecretBox)
        assertEquals("gk1:bGVnYWN5", sealed)
        assertEquals("legacy", unwrapSecret(sealed, PlainSecretBox))
    }

    @Test
    fun sealOpenUseTheUnderlyingBox() {
        val plain = "abc".toByteArray(Charsets.UTF_8)
        assertTrue(box.open(box.seal(plain)).contentEquals(plain))
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        assertEquals(12, iv.size) // sanity: the sealed layout reserves exactly 12 IV bytes
    }
}
