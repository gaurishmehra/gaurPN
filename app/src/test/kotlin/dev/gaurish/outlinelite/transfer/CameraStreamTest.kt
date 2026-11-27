package dev.gaurish.outlinelite.transfer

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pins the camera stream v1 wire contract: the session derivation, the frame
 * seal/open layout with its index-bound AAD, the pinned tier values, and the
 * `cam_control` payload. The Go desktop client (`gaur-share cam`) implements
 * the same shapes, so the literals here are the cross-platform contract.
 */
class CameraStreamTest {

    private val clusterToken = "test-cluster-token"
    private val peerKey: ByteArray = Crypto.deriveSubKeys(clusterToken).second
    private val requestId = "req-0011223344556677"

    // ------------------------------------------------ session derivation

    /** Raw HMAC-SHA256, computed independently of the app's [Crypto]. */
    private fun hmac(key: ByteArray, message: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { String.format("%02x", it) }

    @Test
    fun sessionDerivationVectorsMatchThePinnedDomains() {
        // session_id = hex(HMAC-SHA256(K_peer, "gaur-cam-session-v1:" + request_id))
        val expectedSession = hex(hmac(peerKey, "gaur-cam-session-v1:$requestId"))
        val session = CameraStream.sessionId(peerKey, requestId)
        assertEquals(expectedSession, session)
        assertEquals(64, session.length)
        assertTrue(CameraStream.isSessionId(session))

        // K_cam = HMAC-SHA256(K_peer, "gaur-camera-v1:" + session_id)
        val expectedKey = hmac(peerKey, "gaur-camera-v1:$session")
        assertArrayEquals(expectedKey, CameraStream.cameraKey(peerKey, session))
    }

    @Test
    fun sessionDerivationIsDeterministicAndPerRequest() {
        val a = CameraStream.sessionId(peerKey, requestId)
        val b = CameraStream.sessionId(peerKey, requestId)
        val other = CameraStream.sessionId(peerKey, "req-8899aabbccddeeff")
        assertEquals(a, b)
        assertFalse(a == other)
        // A different cluster token never lands on the same session or key.
        val otherPeerKey = Crypto.deriveSubKeys("another-token").second
        assertFalse(CameraStream.sessionId(otherPeerKey, requestId) == a)
        assertFalse(
            CameraStream.cameraKey(otherPeerKey, a).contentEquals(
                CameraStream.cameraKey(peerKey, a),
            ),
        )
    }

    @Test
    fun sessionIdShapeIsStrict() {
        val session = CameraStream.sessionId(peerKey, requestId)
        assertTrue(CameraStream.isSessionId(session))
        // 63 chars, 65 chars, uppercase, non-hex: all rejected.
        assertFalse(CameraStream.isSessionId(session.dropLast(1)))
        assertFalse(CameraStream.isSessionId(session + "0"))
        assertFalse(CameraStream.isSessionId(session.uppercase()))
        assertFalse(CameraStream.isSessionId("g".repeat(64)))
        assertFalse(CameraStream.isSessionId(""))
    }

    // -------------------------------------------------------------- frames

    @Test
    fun frameRoundTripCarriesThePinnedLayout() {
        val session = CameraStream.sessionId(peerKey, requestId)
        val key = CameraStream.cameraKey(peerKey, session)
        val csd = byteArrayOf(0, 0, 0, 1, 0x40, 0x01, 0x0C, 0x01)
        val sealed = CameraStream.sealFrame(key, session, frameIndex = 0, plaintext = csd)

        // [4-byte length L][12-byte nonce][L-28 ciphertext][16-byte tag]
        val length = ((sealed[0].toInt() and 0xFF) shl 24) or
            ((sealed[1].toInt() and 0xFF) shl 16) or
            ((sealed[2].toInt() and 0xFF) shl 8) or
            (sealed[3].toInt() and 0xFF)
        assertEquals(4 + length, sealed.size)
        assertEquals(12 + csd.size + 16, length)

        val opened = CameraStream.openFrame(key, session, frameIndex = 0, frame = sealed)
        assertNotNull(opened)
        assertArrayEquals(csd, opened)
    }

    @Test
    fun frameIndexIsBoundIntoTheAad() {
        val session = CameraStream.sessionId(peerKey, requestId)
        val key = CameraStream.cameraKey(peerKey, session)
        val frame = byteArrayOf(1, 2, 3, 4, 5)
        val sealedAtThree = CameraStream.sealFrame(key, session, frameIndex = 3, plaintext = frame)

        // The right index opens; a wrong one fails authentication.
        assertArrayEquals(frame, CameraStream.openFrame(key, session, 3, sealedAtThree))
        assertNull(CameraStream.openFrame(key, session, 2, sealedAtThree))
        assertNull(CameraStream.openFrame(key, session, 4, sealedAtThree))

        // A different session fails too: the AAD binds the session id.
        val otherSession = CameraStream.sessionId(peerKey, "req-8899aabbccddeeff")
        assertNull(CameraStream.openFrame(key, otherSession, 3, sealedAtThree))
    }

    @Test
    fun frameNonceIsFreshPerSeal() {
        val session = CameraStream.sessionId(peerKey, requestId)
        val key = CameraStream.cameraKey(peerKey, session)
        val frame = byteArrayOf(9, 8, 7)
        val a = CameraStream.sealFrame(key, session, 0, frame)
        val b = CameraStream.sealFrame(key, session, 1, frame)
        // The 12 bytes after the length prefix differ: nonce reuse would let
        // a watcher correlate ciphertexts across frames.
        assertFalse(a.copyOfRange(4, 16).contentEquals(b.copyOfRange(4, 16)))
    }

    @Test
    fun oversizeFrameIsRejectedByBothSides() {
        val session = CameraStream.sessionId(peerKey, requestId)
        val key = CameraStream.cameraKey(peerKey, session)

        // Sealing a frame beyond the 8 MiB limit aborts the stream.
        val tooBig = ByteArray(CameraStream.MAX_FRAME_BYTES) // L = size + 28 > 8 MiB
        assertThrows(IllegalStateException::class.java) {
            CameraStream.sealFrame(key, session, 0, tooBig)
        }

        // The largest in-limit plaintext seals fine.
        val maxPlain = ByteArray(CameraStream.MAX_FRAME_BYTES - 28) { 0x61 }
        val sealed = CameraStream.sealFrame(key, session, 0, maxPlain)
        assertEquals(4 + CameraStream.MAX_FRAME_BYTES, sealed.size)

        // A forged length prefix beyond the limit is rejected by the open.
        val oversized = ByteArray(4) { 0x7F }
        assertNull(CameraStream.openFrame(key, session, 0, oversized))
    }

    @Test
    fun malformedFramesAreDropped() {
        val session = CameraStream.sessionId(peerKey, requestId)
        val key = CameraStream.cameraKey(peerKey, session)
        val sealed = CameraStream.sealFrame(key, session, 0, byteArrayOf(1, 2, 3))

        // Too short, length mismatch, truncated body, tampered tag, and an
        // L below the nonce+tag minimum all fail closed.
        assertNull(CameraStream.openFrame(key, session, 0, ByteArray(0)))
        assertNull(CameraStream.openFrame(key, session, 0, ByteArray(4)))
        assertNull(CameraStream.openFrame(key, session, 0, sealed.copyOf(sealed.size - 1)))
        assertNull(CameraStream.openFrame(key, session, 0, sealed.drop(4).toByteArray()))
        val tampered = sealed.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1] + 1).toByte()
        assertNull(CameraStream.openFrame(key, session, 0, tampered))
        val emptyL = byteArrayOf(0, 0, 0, 28)
        assertNull(CameraStream.openFrame(key, session, 0, emptyL))
    }

    // ---------------------------------------------------------------- tiers

    @Test
    fun tierValuesArePinnedToTheWireSpec() {
        assertEquals(CameraTier.HIGH, CameraTier.fromWire("high"))
        assertEquals(CameraTier.BALANCED, CameraTier.fromWire("balanced"))
        assertEquals(CameraTier.LOW, CameraTier.fromWire("low"))

        assertEquals("high", CameraTier.HIGH.wire)
        assertEquals(1920, CameraTier.HIGH.width)
        assertEquals(1080, CameraTier.HIGH.height)
        assertEquals(30, CameraTier.HIGH.fps)
        assertEquals(8_000_000, CameraTier.HIGH.bitrate)

        assertEquals(1280, CameraTier.BALANCED.width)
        assertEquals(720, CameraTier.BALANCED.height)
        assertEquals(30, CameraTier.BALANCED.fps)
        assertEquals(3_500_000, CameraTier.BALANCED.bitrate)

        assertEquals(640, CameraTier.LOW.width)
        assertEquals(360, CameraTier.LOW.height)
        assertEquals(30, CameraTier.LOW.fps)
        assertEquals(500_000, CameraTier.LOW.bitrate)

        assertNull(CameraTier.fromWire("ultra"))
        assertNull(CameraTier.fromWire("HIGH"))
        assertNull(CameraTier.fromWire(""))
    }

    @Test
    fun lensValuesArePinned() {
        assertEquals(CameraLens.BACK, CameraLens.fromWire("back"))
        assertEquals(CameraLens.FRONT, CameraLens.fromWire("front"))
        assertNull(CameraLens.fromWire("rear"))
        assertNull(CameraLens.fromWire(""))
    }

    // -------------------------------------------------------------- control

    private fun control(vararg fields: Pair<String, Any>): JSONObject {
        val json = JSONObject()
            .put("request_id", requestId)
            .put("session_id", CameraStream.sessionId(peerKey, requestId))
            .put("ts", 1_767_225_300_000L)
        fields.forEach { (k, v) -> json.put(k, v) }
        return json
    }

    @Test
    fun everyPinnedControlActionParses() {
        val session = CameraStream.sessionId(peerKey, requestId)

        val torchOn = CameraStream.parseControl(control("action" to "torch", "on" to true))
        assertEquals(CameraAction.TORCH, torchOn!!.action)
        assertEquals(true, torchOn.on)

        val torchOff = CameraStream.parseControl(control("action" to "torch", "on" to false))
        assertEquals(false, torchOff!!.on)

        val lens = CameraStream.parseControl(control("action" to "lens"))
        assertEquals(CameraAction.LENS, lens!!.action)
        assertNull(lens.on)
        assertNull(lens.tier)

        val tier = CameraStream.parseControl(control("action" to "tier", "tier" to "balanced"))
        assertEquals(CameraAction.TIER, tier!!.action)
        assertEquals(CameraTier.BALANCED, tier.tier)

        val stop = CameraStream.parseControl(control("action" to "stop"))
        assertEquals(CameraAction.STOP, stop!!.action)

        // The ids travel with every action.
        assertEquals(requestId, torchOn.requestId)
        assertEquals(session, torchOn.sessionId)
    }

    @Test
    fun malformedControlsAreDropped() {
        // Bad request id / session id / action.
        assertNull(
            CameraStream.parseControl(
                control("action" to "stop").put("request_id", "nope"),
            ),
        )
        assertNull(
            CameraStream.parseControl(
                control("action" to "stop").put("session_id", "z".repeat(64)),
            ),
        )
        assertNull(CameraStream.parseControl(control("action" to "record")))
        assertNull(CameraStream.parseControl(JSONObject().put("action", "stop")))

        // torch without on, tier without a valid tier.
        assertNull(CameraStream.parseControl(control("action" to "torch")))
        assertNull(CameraStream.parseControl(control("action" to "tier")))
        assertNull(CameraStream.parseControl(control("action" to "tier", "tier" to "ultra")))

        // Non-boolean on, non-string tier, bad ts.
        assertNull(CameraStream.parseControl(control("action" to "torch", "on" to 1)))
        assertNull(CameraStream.parseControl(control("action" to "tier", "tier" to 3)))
        assertNull(CameraStream.parseControl(control("action" to "stop").put("ts", 0)))
        assertNull(
            CameraStream.parseControl(
                control("action" to "stop").put("ts", "1767225300000"),
            ),
        )
        assertNull(
            CameraStream.parseControl(
                JSONObject(control("action" to "stop").toString()).apply { remove("ts") },
            ),
        )
    }
}
