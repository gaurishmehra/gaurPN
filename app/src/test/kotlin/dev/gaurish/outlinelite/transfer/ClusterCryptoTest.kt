package dev.gaurish.outlinelite.transfer

import dev.gaurish.outlinelite.util.StdBase64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the v3.2 cluster wire formats. The Go desktop client mirrors every
 * construction here byte for byte, so the literals below are the contract:
 *
 *  - grant  = hex(HMAC-SHA256(K_peer, "gaur-request-grant-v1:<req>:<from>:<to>:<exp>"))
 *  - status = base64Std(nonce || AES-256-GCM(K_status, "status:v1:<deviceId>", json))
 *  - json   = {"battery":<n>,"charging":<bool>,"ts":<ms>}
 */
class ClusterCryptoTest {

    /** 32-byte test key, hex 00..1f. Never a real cluster key. */
    private val kPeer = hexToBytes("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")

    /** HMAC-SHA256(K_PEER, "gaur-status-v1"), pinned independently. */
    private val kStatusHex = "a277969fc57720bff75b3a807504c29e7306899fc4b5e9883368c2cba409764f"

    private val requestId = "req-0011223344556677"
    private val requesterId = "reqester-abc123"
    private val targetId = "android-f3dd4a01"
    private val expiresTs = 1_767_225_600_000L

    private val grantMessage =
        "gaur-request-grant-v1:req-0011223344556677:reqester-abc123:android-f3dd4a01:1767225600000"
    private val grantHex = "cd232c541e09733b88b73687f7b4806e7cf8ad22995adf2b5fe7542bc77506d6"

    private val statusJson = "{\"battery\":73,\"charging\":true,\"ts\":1730000000000}"

    private val deviceId = "device-test-1"

    // ------------------------------------------------------------- grants

    @Test
    fun requestGrantMessageLayoutIsPinned() {
        assertEquals(
            grantMessage,
            ClusterCrypto.requestGrantMessage(requestId, requesterId, targetId, expiresTs),
        )
    }

    @Test
    fun computeRequestGrantMatchesPinnedVector() {
        val grant = ClusterCrypto.computeRequestGrant(kPeer, requestId, requesterId, targetId, expiresTs)
        assertEquals(grantHex, grant)
        // Lowercase hex of a 32-byte MAC.
        assertEquals(64, grant.length)
    }

    @Test
    fun verifyRequestGrantAcceptsThePinnedVector() {
        assertTrue(
            ClusterCrypto.verifyRequestGrant(kPeer, requestId, requesterId, targetId, expiresTs, grantHex),
        )
    }

    @Test
    fun verifyRequestGrantRejectsTamperedGrant() {
        val tampered = grantHex.dropLast(1) + if (grantHex.last() == '0') "1" else "0"
        assertFalse(
            ClusterCrypto.verifyRequestGrant(kPeer, requestId, requesterId, targetId, expiresTs, tampered),
        )
        assertFalse(
            ClusterCrypto.verifyRequestGrant(kPeer, requestId, requesterId, targetId, expiresTs, ""),
        )
        assertFalse(
            ClusterCrypto.verifyRequestGrant(kPeer, requestId, requesterId, targetId, expiresTs, "not-hex"),
        )
        // Grants are lowercase hex on the wire; the check is exact.
        assertFalse(
            ClusterCrypto.verifyRequestGrant(
                kPeer, requestId, requesterId, targetId, expiresTs, grantHex.uppercase(),
            ),
        )
    }

    @Test
    fun verifyRequestGrantBindsEveryField() {
        assertFalse(
            ClusterCrypto.verifyRequestGrant(
                kPeer, "req-0011223344556678", requesterId, targetId, expiresTs, grantHex,
            ),
        )
        assertFalse(
            ClusterCrypto.verifyRequestGrant(
                kPeer, requestId, "reqester-abc124", targetId, expiresTs, grantHex,
            ),
        )
        assertFalse(
            ClusterCrypto.verifyRequestGrant(
                kPeer, requestId, requesterId, "android-f3dd4a02", expiresTs, grantHex,
            ),
        )
        assertFalse(
            ClusterCrypto.verifyRequestGrant(
                kPeer, requestId, requesterId, targetId, expiresTs + 1, grantHex,
            ),
        )
    }

    @Test
    fun newRequestIdHasPinnedShapeAndIsUnique() {
        val seen = HashSet<String>(1000)
        repeat(1000) {
            val id = ClusterCrypto.newRequestId()
            assertTrue("missing prefix: $id", id.startsWith("req-"))
            val random = id.removePrefix("req-")
            assertEquals("wrong length: $id", 16, random.length)
            assertTrue("not lowercase hex: $id", random.all { it in '0'..'9' || it in 'a'..'f' })
            assertTrue("duplicate id: $id", seen.add(id))
        }
    }

    // ------------------------------------------------------------- status

    @Test
    fun statusKeyDerivationMatchesPinnedVector() {
        val kStatus = ClusterCrypto.deriveStatusKey(kPeer)
        assertEquals(32, kStatus.size)
        assertEquals(kStatusHex, toHex(kStatus))
    }

    @Test
    fun statusPlaintextMatchesPinnedJson() {
        assertEquals(
            statusJson,
            ClusterCrypto.statusPlaintext(73, true, 1_730_000_000_000L),
        )
        assertEquals(
            "{\"battery\":0,\"charging\":false,\"ts\":0}",
            ClusterCrypto.statusPlaintext(0, false, 0L),
        )
    }

    @Test
    fun statusBlobRoundTrips() {
        val kStatus = ClusterCrypto.deriveStatusKey(kPeer)
        val blob = ClusterCrypto.encodeStatusBlob(kStatus, deviceId, 73, true, 1_730_000_000_000L)
        assertEquals(
            StatusInfo(73, true, 1_730_000_000_000L),
            ClusterCrypto.decodeStatusBlob(kStatus, deviceId, blob),
        )
    }

    @Test
    fun statusBlobLayoutIsPinned() {
        // Independent construction: fixed nonce, AAD "status:v1:<deviceId>",
        // the pinned JSON bytes, standard base64 with padding.
        val kStatus = ClusterCrypto.deriveStatusKey(kPeer)
        val nonce = ByteArray(12) { 9 }
        val sealed = Crypto.encryptGcm(
            kStatus,
            nonce,
            statusJson.toByteArray(Charsets.UTF_8),
            "status:v1:$deviceId".toByteArray(Charsets.UTF_8),
        )
        val blob = StdBase64.encode(nonce + sealed)
        assertEquals(
            StatusInfo(73, true, 1_730_000_000_000L),
            ClusterCrypto.decodeStatusBlob(kStatus, deviceId, blob),
        )
    }

    @Test
    fun statusBlobIsBoundToTheDeviceId() {
        val kStatus = ClusterCrypto.deriveStatusKey(kPeer)
        val blob = ClusterCrypto.encodeStatusBlob(kStatus, deviceId, 73, true, 1_730_000_000_000L)
        assertNull(ClusterCrypto.decodeStatusBlob(kStatus, "device-test-2", blob))
        assertNull(ClusterCrypto.decodeStatusBlob(kStatus, "", blob))
    }

    @Test
    fun statusBlobIsBoundToTheKey() {
        val kStatus = ClusterCrypto.deriveStatusKey(kPeer)
        val otherKey = ClusterCrypto.deriveStatusKey(ByteArray(32) { 0x42 })
        val blob = ClusterCrypto.encodeStatusBlob(kStatus, deviceId, 73, true, 1_730_000_000_000L)
        assertNull(ClusterCrypto.decodeStatusBlob(otherKey, deviceId, blob))
    }

    @Test
    fun statusBlobRejectsTamperedCiphertext() {
        val kStatus = ClusterCrypto.deriveStatusKey(kPeer)
        val blob = ClusterCrypto.encodeStatusBlob(kStatus, deviceId, 73, true, 1_730_000_000_000L)
        val raw = StdBase64.decode(blob)
        assertNotNull(raw)

        // Flip one ciphertext bit.
        val body = raw!!.copyOf()
        body[12] = (body[12].toInt() xor 0x01).toByte()
        assertNull(ClusterCrypto.decodeStatusBlob(kStatus, deviceId, StdBase64.encode(body)))

        // Flip one tag bit.
        val tag = raw.copyOf()
        tag[tag.size - 1] = (tag[tag.size - 1].toInt() xor 0x01).toByte()
        assertNull(ClusterCrypto.decodeStatusBlob(kStatus, deviceId, StdBase64.encode(tag)))
    }

    @Test
    fun statusBlobRejectsMalformedInput() {
        val kStatus = ClusterCrypto.deriveStatusKey(kPeer)
        assertNull(ClusterCrypto.decodeStatusBlob(kStatus, deviceId, ""))
        assertNull(ClusterCrypto.decodeStatusBlob(kStatus, deviceId, "not base64 !!"))
        assertNull(ClusterCrypto.decodeStatusBlob(kStatus, deviceId, "AAAA"))
        // Valid base64 but shorter than nonce + tag + one byte.
        assertNull(ClusterCrypto.decodeStatusBlob(kStatus, deviceId, StdBase64.encode(ByteArray(28))))
    }

    @Test
    fun statusBlobRejectsNonPinnedPlaintext() {
        val kStatus = ClusterCrypto.deriveStatusKey(kPeer)
        val nonce = ByteArray(12) { 4 }
        val aad = "status:v1:$deviceId".toByteArray(Charsets.UTF_8)
        val badPayloads = listOf(
            // Extra field.
            "{\"battery\":73,\"charging\":true,\"ts\":1730000000000,\"x\":1}",
            // Reordered fields.
            "{\"charging\":true,\"battery\":73,\"ts\":1730000000000}",
            // Out-of-range battery.
            "{\"battery\":101,\"charging\":true,\"ts\":1730000000000}",
            // Leading zeros.
            "{\"battery\":073,\"charging\":true,\"ts\":1730000000000}",
            // Missing field.
            "{\"battery\":73,\"charging\":true}",
            // Trailing bytes.
            "{\"battery\":73,\"charging\":true,\"ts\":1730000000000}x",
            // Not JSON at all.
            "",
        )
        for (payload in badPayloads) {
            val sealed = Crypto.encryptGcm(kStatus, nonce, payload.toByteArray(Charsets.UTF_8), aad)
            val blob = StdBase64.encode(nonce + sealed)
            assertNull("accepted: $payload", ClusterCrypto.decodeStatusBlob(kStatus, deviceId, blob))
        }
    }

    @Test
    fun statusBlobUsesAFreshNonce() {
        val kStatus = ClusterCrypto.deriveStatusKey(kPeer)
        val first = ClusterCrypto.encodeStatusBlob(kStatus, deviceId, 73, true, 1_730_000_000_000L)
        val second = ClusterCrypto.encodeStatusBlob(kStatus, deviceId, 73, true, 1_730_000_000_000L)
        assertNotEquals(first, second)
        assertEquals(StatusInfo(73, true, 1_730_000_000_000L), ClusterCrypto.decodeStatusBlob(kStatus, deviceId, first))
        assertEquals(StatusInfo(73, true, 1_730_000_000_000L), ClusterCrypto.decodeStatusBlob(kStatus, deviceId, second))
    }

    @Test
    fun statusBlobStaysWithinTheLengthBudget() {
        val kStatus = ClusterCrypto.deriveStatusKey(kPeer)
        for (battery in 0..100) {
            for (charging in listOf(true, false)) {
                for (ts in listOf(0L, 1_730_000_000_000L, Long.MAX_VALUE)) {
                    val blob = ClusterCrypto.encodeStatusBlob(kStatus, deviceId, battery, charging, ts)
                    assertTrue("blob too long: ${blob.length}", blob.length <= 160)
                    assertTrue("blob not padded: ${blob.length}", blob.length % 4 == 0)
                }
            }
        }
    }

    // ---------------------------------------------------------------- URLs

    @Test
    fun shareUrlAcceptsThePinnedSet() {
        val accepted = listOf(
            "https://example.com/path?q=1",
            "http://example.com",
            "HTTPS://EXAMPLE.COM/Path",
            "https://example.com:8080/p#frag",
            " https://example.com ",
        )
        for (raw in accepted) {
            assertNotNull("expected accept: $raw", ClusterCrypto.validateShareUrl(raw))
        }
    }

    @Test
    fun shareUrlRejectsThePinnedSet() {
        val rejected = listOf(
            "https://",
            "steam://store",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "https://example.com/a b",
            "example.com",
            "https://example.com/a<b",
            "https://example.com/a|b",
            "https://example.com/a`b",
            "https://example.com/" + "a".repeat(3072),
            "https://example.com/a\tb",
            "",
            "   ",
        )
        for (raw in rejected) {
            assertNull("expected reject: $raw", ClusterCrypto.validateShareUrl(raw))
        }
    }

    @Test
    fun shareUrlLengthLimitIsInclusiveAndCountsUtf16Units() {
        val base = "https://example.com/"
        val atLimit = base + "a".repeat(2048 - base.length)
        assertEquals(2048, atLimit.length)
        assertNotNull(ClusterCrypto.validateShareUrl(atLimit))
        assertNull(ClusterCrypto.validateShareUrl(atLimit + "a"))

        // Each emoji is two UTF-16 units, so this is exactly 2048 units but
        // more than 2048 UTF-8 bytes. The Go validator counts units too.
        val emoji = base + "\uD83D\uDE00".repeat((2048 - base.length) / 2)
        assertEquals(2048, emoji.length)
        assertNotNull(ClusterCrypto.validateShareUrl(emoji))
    }

    @Test
    fun shareUrlCanonicalFormIsLowercasedAndLossless() {
        assertEquals(
            "https://example.com/Path",
            ClusterCrypto.validateShareUrl("HTTPS://EXAMPLE.COM/Path"),
        )
        assertEquals(
            "https://example.com",
            ClusterCrypto.validateShareUrl(" https://example.com "),
        )
        // Percent escapes must survive canonicalisation untouched.
        val encoded = "https://example.com/%20a%2Fb?q=a%20b#f%20g"
        assertEquals(encoded, ClusterCrypto.validateShareUrl(encoded))
        // Userinfo and port keep their case and value.
        assertEquals(
            "https://User:Pass@example.com:8080/x",
            ClusterCrypto.validateShareUrl("https://User:Pass@EXAMPLE.com:8080/x"),
        )
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
