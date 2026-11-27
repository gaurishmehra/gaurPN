package dev.gaurish.outlinelite.transfer

import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.GeneralSecurityException

/**
 * Camera stream quality tier. Pinned wire values; do not rename or reorder
 * the dimensions — the desktop CLI maps "high"/"balanced"/"low" to the same
 * encoder settings and displays them in the status line.
 */
enum class CameraTier(val wire: String, val width: Int, val height: Int, val fps: Int, val bitrate: Int) {
    HIGH("high", 1920, 1080, 30, 8_000_000),
    BALANCED("balanced", 1280, 720, 30, 3_500_000),
    LOW("low", 640, 360, 30, 500_000);

    companion object {
        fun fromWire(value: String): CameraTier? = entries.firstOrNull { it.wire == value }
    }
}

/** Lens a new camera session starts with. Pinned wire values. */
enum class CameraLens(val wire: String) {
    BACK("back"),
    FRONT("front");

    companion object {
        fun fromWire(value: String): CameraLens? = entries.firstOrNull { it.wire == value }
    }
}

/** Sealed `cam_control` action. Pinned wire values; do not rename. */
enum class CameraAction(val wire: String) {
    TORCH("torch"),
    LENS("lens"),
    TIER("tier"),
    STOP("stop");

    companion object {
        fun fromWire(value: String): CameraAction? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Parsed sealed `cam_control` payload. [on] carries the torch state and
 * [tier] the new quality; both are only meaningful for their own action and
 * are null otherwise.
 */
data class CameraControl(
    val requestId: String,
    val sessionId: String,
    val action: CameraAction,
    val on: Boolean?,
    val tier: CameraTier?,
    val ts: Long,
)

/**
 * Pure primitives of the camera stream v1 wire contract. Everything here is
 * pinned byte-exact by the Go desktop client (`gaur-share cam`):
 *
 *  - session derivation, identical on both sides:
 *    session_id = hex(HMAC-SHA256(K_peer, "gaur-cam-session-v1:" + request_id))
 *    K_cam      = HMAC-SHA256(K_peer, "gaur-camera-v1:" + session_id)
 *    where K_peer is the existing peer crypto key
 *    (HMAC-SHA256(cluster_token, "gaur-peer-auth-v1")).
 *  - frame layout, sender -> receiver, big-endian:
 *    [4-byte length L][12-byte nonce][L-28 ciphertext][16-byte GCM tag]
 *    AES-256-GCM with K_cam, a fresh nonce per frame, and the AAD
 *    ASCII "cam:v1:<session_id>:<frame_index>" where frame_index is the
 *    decimal count of frames sent so far (== received so far), starting 0.
 *    Frame 0 is the HEVC csd (VPS/SPS/PPS Annex-B from MediaCodec
 *    INFO_OUTPUT_FORMAT_CHANGED csd-0); frames 1+ are encoder output.
 *  - control payload: {"request_id","session_id","action","on"?,"tier"?,"ts"}.
 *
 * The file is JVM-pure on purpose (no android.* classes) so the wire contract
 * stays testable with plain unit tests, like ClusterCrypto and RequestCodec.
 */
object CameraStream {

    /** Largest accepted L (the bytes after the length prefix). Abort beyond. */
    const val MAX_FRAME_BYTES = 8 shl 20

    private const val LENGTH_PREFIX_BYTES = 4
    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BYTES = 16

    /** Smallest sane L: nonce + at least one plaintext byte + GCM tag. */
    const val MIN_FRAME_BYTES = NONCE_BYTES + GCM_TAG_BYTES + 1

    /** Domain separator for the session id derivation. */
    private const val SESSION_DOMAIN = "gaur-cam-session-v1"

    /** Domain separator for the frame key derivation. */
    private const val KEY_DOMAIN = "gaur-camera-v1"

    /** Prefix of the per-frame AAD; the rest is "<session_id>:<frame_index>". */
    private const val AAD_PREFIX = "cam:v1"

    /** session ids are the 64 lowercase hex chars of HMAC-SHA256. */
    private const val SESSION_ID_HEX_CHARS = 64

    // ------------------------------------------------------------ session

    /**
     * Deterministic session id for one camera request. Identical on the
     * desktop and the phone because both hold K_peer; it also doubles as the
     * relay's transfer_id, which is why it is hex and collision-free by
     * construction.
     */
    fun sessionId(peerCryptoKey: ByteArray, requestId: String): String =
        hex(hmac(peerCryptoKey, "$SESSION_DOMAIN:$requestId"))

    /** Per-session frame key K_cam, domain-separated from every other key. */
    fun cameraKey(peerCryptoKey: ByteArray, sessionId: String): ByteArray =
        hmac(peerCryptoKey, "$KEY_DOMAIN:$sessionId")

    /** 64 lowercase hex chars, exactly as [sessionId] emits. */
    fun isSessionId(value: String): Boolean =
        value.length == SESSION_ID_HEX_CHARS && value.all { it in '0'..'9' || it in 'a'..'f' }

    // -------------------------------------------------------------- frame

    /**
     * AAD binds every frame to its session and its position in the stream:
     * ASCII "cam:v1:<session_id>:<frame_index>". The index is the count of
     * frames sent so far, so a relay cannot reorder, drop, or splice frames
     * without failing authentication.
     */
    fun frameAad(sessionId: String, frameIndex: Long): ByteArray =
        "$AAD_PREFIX:$sessionId:$frameIndex".toByteArray(Charsets.US_ASCII)

    /**
     * Seals one frame in the pinned layout. [frameIndex] is the count of
     * frames already sent (0 for the csd). Throws [IllegalStateException]
     * when the frame would exceed [MAX_FRAME_BYTES] — the sender aborts the
     * stream rather than emitting a frame the receiver must reject.
     */
    fun sealFrame(
        key: ByteArray,
        sessionId: String,
        frameIndex: Long,
        plaintext: ByteArray,
        nonce: ByteArray = Crypto.randomBytes(NONCE_BYTES),
    ): ByteArray {
        require(plaintext.isNotEmpty()) { "camera frame is empty" }
        require(nonce.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes" }
        val ciphertext = Crypto.encryptGcm(key, nonce, plaintext, frameAad(sessionId, frameIndex))
        // ciphertext already carries the 16-byte GCM tag; L = nonce + ciphertext.
        val length = NONCE_BYTES + ciphertext.size
        if (length > MAX_FRAME_BYTES) {
            throw IllegalStateException("camera frame of $length bytes exceeds ${MAX_FRAME_BYTES}")
        }
        return ByteBuffer.allocate(LENGTH_PREFIX_BYTES + length)
            .putInt(length)
            .put(nonce)
            .put(ciphertext)
            .array()
    }

    /**
     * Opens one sealed frame at the expected index. Returns null on anything
     * that is not exactly the pinned shape or fails authentication — the
     * receiver drops the frame, never the session (a wrong index means the
     * stream is unrecoverable and is surfaced by the caller).
     */
    fun openFrame(key: ByteArray, sessionId: String, frameIndex: Long, frame: ByteArray): ByteArray? {
        if (frame.size <= LENGTH_PREFIX_BYTES) return null
        val length = ByteBuffer.wrap(frame, 0, LENGTH_PREFIX_BYTES).int
        if (length < MIN_FRAME_BYTES || length > MAX_FRAME_BYTES) return null
        if (frame.size != LENGTH_PREFIX_BYTES + length) return null
        val nonce = frame.copyOfRange(LENGTH_PREFIX_BYTES, LENGTH_PREFIX_BYTES + NONCE_BYTES)
        val ciphertext = frame.copyOfRange(LENGTH_PREFIX_BYTES + NONCE_BYTES, frame.size)
        return try {
            Crypto.decryptGcm(key, nonce, ciphertext, frameAad(sessionId, frameIndex))
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    // ----------------------------------------------------------- control

    /**
     * Parses one sealed `cam_control` inner payload. Strict: a malformed field
     * rejects the whole message (null) so it is dropped silently, exactly like
     * the other codecs. `on` is required for torch and `tier` for a tier
     * change; anything else carrying them is still accepted because the Go
     * builder only emits them for their own actions.
     */
    fun parseControl(json: JSONObject): CameraControl? {
        val requestId = json.optString("request_id")
        if (!RequestCodec.isRequestId(requestId)) return null
        val sessionId = json.optString("session_id")
        if (!isSessionId(sessionId)) return null
        val action = CameraAction.fromWire(json.optString("action")) ?: return null

        val on = when (val raw = json.opt("on")) {
            null, JSONObject.NULL -> null
            is Boolean -> raw
            else -> return null
        }
        val tier = when (val raw = json.opt("tier")) {
            null, JSONObject.NULL -> null
            is String -> CameraTier.fromWire(raw) ?: return null
            else -> return null
        }
        if (action == CameraAction.TORCH && on == null) return null
        if (action == CameraAction.TIER && tier == null) return null

        // Same positive-number rule as every other timestamp in the wire:
        // a quoted number is not a JSON number.
        val ts = RequestCodec.positiveLong(json, "ts") ?: return null

        return CameraControl(requestId, sessionId, action, on, tier, ts)
    }

    // ------------------------------------------------------------ helpers

    private fun hmac(key: ByteArray, message: String): ByteArray =
        Crypto.hmacSha256(key, message.toByteArray(Charsets.UTF_8))

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) append("%02x".format(b))
    }
}
