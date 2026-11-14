package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChunkTransportTest {

    @Test
    fun testDeriveTransferIdIsStableAndOpaque() {
        val key = "peer-key".toByteArray()
        val hash = "deadbeef"
        val a = Crypto.deriveTransferId(key, hash)
        val b = Crypto.deriveTransferId(key, hash)
        assertEquals(a, b)
        assertTrue(a.startsWith("tx-"))
        assertEquals(35, a.length)
        assertNotEquals("tx-$hash", a)
        assertNotEquals(a, Crypto.deriveTransferId("other-key".toByteArray(), hash))
    }

    @Test
    fun testChunkNonceEncodesIndex() {
        val nonce0 = Crypto.chunkNonce(0)
        val nonce1 = Crypto.chunkNonce(1)
        assertEquals(12, nonce0.size)
        assertEquals(0, nonce0[0].toInt())
        assertEquals(0, nonce0[3].toInt())
        assertNotEquals(nonce0.toList(), nonce1.toList())
        assertEquals(1L, java.nio.ByteBuffer.wrap(nonce1).getLong(4))
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 7),
            Crypto.chunkNonce(7),
        )
    }

    @Test
    fun testChunkAadBindsPosition() {
        val a = Crypto.chunkAad("tx", "s", 0, 3, 100)
        val b = Crypto.chunkAad("tx", "s", 1, 3, 100)
        val c = Crypto.chunkAad("tx", "s2", 0, 3, 100)
        assertNotEquals(a.toList(), b.toList())
        assertNotEquals(a.toList(), c.toList())
        assertEquals("chunk:v2:tx:s:0:3:100", String(a, Charsets.UTF_8))
    }

    @Test
    fun testChunkEncryptDecryptWithAad() {
        val key = ByteArray(32) { 0x42 }
        val plain = ByteArray(1024) { (it % 251).toByte() }
        val nonce = Crypto.chunkNonce(7)
        val aad = Crypto.chunkAad("tx", "s", 7, 12, 12345)

        val ciphertext = Crypto.encryptChunkAad(key, nonce, plain, aad)
        val decrypted = Crypto.decryptChunkAad(key, nonce, ciphertext, aad)
        assertArrayEquals(plain, decrypted)

        val wrongAad = Crypto.chunkAad("tx", "s", 8, 12, 12345)
        try {
            Crypto.decryptChunkAad(key, nonce, ciphertext, wrongAad)
            fail("decrypt accepted mismatched AAD")
        } catch (_: Exception) {
            // expected
        }

        val tampered = ciphertext.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0xff).toByte()
        try {
            Crypto.decryptChunkAad(key, nonce, tampered, aad)
            fail("decrypt accepted tampered ciphertext")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun testHaveHashOrderIndependent() {
        assertEquals(Crypto.haveHash(listOf(2, 0, 1)), Crypto.haveHash(listOf(0, 1, 2)))
        assertNotEquals(Crypto.haveHash(emptyList<Int>()), Crypto.haveHash(listOf(0)))
    }

    @Test
    fun testChunkLength() {
        assertEquals(0, ChunkTransport.chunkLength(0, 0))
        assertEquals(1, ChunkTransport.chunkLength(0, 1))
        assertEquals(
            ChunkTransport.CHUNK_SIZE,
            ChunkTransport.chunkLength(0, ChunkTransport.CHUNK_SIZE.toLong()),
        )
        assertEquals(
            1,
            ChunkTransport.chunkLength(1, ChunkTransport.CHUNK_SIZE.toLong() + 1),
        )
        assertEquals(
            0,
            ChunkTransport.chunkLength(2, ChunkTransport.CHUNK_SIZE.toLong() + 1),
        )
    }

    @Test
    fun testParsePresentIndices() {
        // Well-formed status body: staged indices, order-independent.
        assertEquals(
            setOf(0, 2, 5),
            ChunkTransport.parsePresentIndices("""{"present":[5,0,2]}""", 10),
        )
        assertEquals(
            emptySet<Int>(),
            ChunkTransport.parsePresentIndices("""{"present":[]}""", 10),
        )
        // Out-of-range and non-numeric entries are dropped, not fatal.
        assertEquals(
            setOf(1),
            ChunkTransport.parsePresentIndices("""{"present":[1,10,-1,"x"]}""", 10),
        )
        // Malformed bodies fail open to an empty set (full re-upload).
        assertEquals(emptySet<Int>(), ChunkTransport.parsePresentIndices("not json", 10))
        assertEquals(emptySet<Int>(), ChunkTransport.parsePresentIndices("""{"other":[]}""", 10))
        assertEquals(emptySet<Int>(), ChunkTransport.parsePresentIndices("""{"present":[0]}""", 0))
    }
}
