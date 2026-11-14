package dev.gaurish.outlinelite.transfer

import dev.gaurish.outlinelite.util.StdBase64
import java.net.URI
import java.net.URISyntaxException
import java.security.GeneralSecurityException
import java.security.MessageDigest

/**
 * Protocol v3.2 cluster primitives: request grants, encrypted status blobs,
 * and share-URL validation.
 *
 * Every construction here is pinned by the Go desktop client and by the
 * vectors in ClusterCryptoTest. Domain prefixes, separators and the status
 * JSON layout are part of the wire format; changing them breaks
 * cross-platform interop.
 *
 * The file is JVM-pure on purpose (no android.* classes) so the wire contract
 * stays testable with plain unit tests. Only [StdBase64] is used for base64:
 * java.util.Base64 needs API 26 while minSdk is 24.
 */
object ClusterCrypto {

    /** Domain separator for request grants. */
    private const val REQUEST_GRANT_DOMAIN = "gaur-request-grant-v1"

    /** Domain separator for the status-blob key. */
    private const val STATUS_KEY_DOMAIN = "gaur-status-v1"

    /** AAD prefix that binds a status blob to a single device. */
    private const val STATUS_AAD_PREFIX = "status:v1:"

    private const val STATUS_NONCE_BYTES = 12
    private const val GCM_TAG_BYTES = 16

    /** Real status blobs are ~120 chars; anything far larger is not ours. */
    private const val MAX_STATUS_BLOB_CHARS = 4096

    private const val MAX_SHARE_URL_LENGTH = 2048
    private const val REQUEST_ID_PREFIX = "req-"
    private const val REQUEST_ID_RANDOM_BYTES = 8

    // ------------------------------------------------------------- grants

    /**
     * Capability string a peer presents when asking another device for a
     * file: lowercase hex HMAC-SHA256 over the pinned grant message. The
     * target can verify it without keeping per-request state.
     */
    fun computeRequestGrant(
        peerCryptoKey: ByteArray,
        requestId: String,
        requesterId: String,
        targetId: String,
        expiresTs: Long,
    ): String = Crypto.computeAuthTag(
        peerCryptoKey,
        requestGrantMessage(requestId, requesterId, targetId, expiresTs),
    )

    /**
     * Constant-time grant check. A mismatch in the request id, requester,
     * target or expiry all invalidate the grant. Grants are lowercase hex on
     * the wire, so the comparison is exact: no trimming or case folding,
     * which keeps the accept/reject decision identical to the Go client.
     */
    fun verifyRequestGrant(
        peerCryptoKey: ByteArray,
        requestId: String,
        requesterId: String,
        targetId: String,
        expiresTs: Long,
        grant: String,
    ): Boolean {
        val expected =
            computeRequestGrant(peerCryptoKey, requestId, requesterId, targetId, expiresTs)
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            grant.toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * Pinned grant message layout:
     * "gaur-request-grant-v1:<requestId>:<requesterId>:<targetId>:<expiresTs>".
     */
    internal fun requestGrantMessage(
        requestId: String,
        requesterId: String,
        targetId: String,
        expiresTs: Long,
    ): String = "$REQUEST_GRANT_DOMAIN:$requestId:$requesterId:$targetId:$expiresTs"

    /** "req-" followed by 16 lowercase hex chars from SecureRandom. */
    fun newRequestId(): String = REQUEST_ID_PREFIX + Crypto.randomHex(REQUEST_ID_RANDOM_BYTES)

    // ------------------------------------------------------------- status

    /** Domain-separated key for encrypted peer status blobs. */
    fun deriveStatusKey(peerCryptoKey: ByteArray): ByteArray =
        Crypto.hmacSha256(peerCryptoKey, STATUS_KEY_DOMAIN.toByteArray(Charsets.UTF_8))

    /**
     * Encrypts one status sample for [deviceId]. A fresh 12-byte nonce is
     * generated per call and prepended to the ciphertext; the AAD binds the
     * blob to the device so peers cannot swap blobs.
     *
     * Wire format: StdBase64(nonce || ciphertext || GCM tag).
     */
    fun encodeStatusBlob(
        statusKey: ByteArray,
        deviceId: String,
        battery: Int,
        charging: Boolean,
        ts: Long,
    ): String {
        require(battery in 0..100) { "battery must be 0..100" }
        val plaintext = statusPlaintext(battery, charging, ts).toByteArray(Charsets.UTF_8)
        val nonce = Crypto.randomBytes(STATUS_NONCE_BYTES)
        val sealed = Crypto.encryptGcm(statusKey, nonce, plaintext, statusAad(deviceId))
        return StdBase64.encode(nonce + sealed)
    }

    /**
     * Decrypts and parses a status blob. Returns null on any failure —
     * malformed base64, wrong key, wrong device, bad tag, or a payload that
     * is not exactly the pinned JSON shape.
     */
    fun decodeStatusBlob(statusKey: ByteArray, deviceId: String, blob: String): StatusInfo? {
        if (blob.length > MAX_STATUS_BLOB_CHARS) return null
        val raw = StdBase64.decode(blob) ?: return null
        // nonce + tag, plus at least one byte of plaintext.
        if (raw.size <= STATUS_NONCE_BYTES + GCM_TAG_BYTES) return null
        val nonce = raw.copyOfRange(0, STATUS_NONCE_BYTES)
        val ciphertext = raw.copyOfRange(STATUS_NONCE_BYTES, raw.size)
        val plaintext = try {
            Crypto.decryptGcm(statusKey, nonce, ciphertext, statusAad(deviceId))
        } catch (e: GeneralSecurityException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        return parseStatusPlaintext(String(plaintext, Charsets.UTF_8))
    }

    /**
     * Pinned plaintext layout, built by hand so the bytes are identical to
     * the Go encoder (no org.json key reordering, no escaping surprises):
     * {"battery":73,"charging":true,"ts":1730000000000}
     */
    internal fun statusPlaintext(battery: Int, charging: Boolean, ts: Long): String =
        "{\"battery\":" + battery +
            ",\"charging\":" + (if (charging) "true" else "false") +
            ",\"ts\":" + ts + "}"

    /**
     * Strict inverse of [statusPlaintext]. Anything that is not exactly the
     * pinned shape is rejected; JSON numbers must be plain decimal digits
     * without leading zeros, as encoding/json requires.
     */
    internal fun parseStatusPlaintext(text: String): StatusInfo? {
        val batteryPrefix = "{\"battery\":"
        if (!text.startsWith(batteryPrefix)) return null
        var i = batteryPrefix.length

        val batteryStart = i
        while (i < text.length && text[i] in '0'..'9') i++
        if (i == batteryStart) return null
        if (i - batteryStart > 1 && text[batteryStart] == '0') return null
        val battery = text.substring(batteryStart, i).toIntOrNull() ?: return null

        val chargingMarker = ",\"charging\":"
        if (!text.startsWith(chargingMarker, i)) return null
        i += chargingMarker.length
        val charging = when {
            text.startsWith("true", i) -> {
                i += 4
                true
            }
            text.startsWith("false", i) -> {
                i += 5
                false
            }
            else -> return null
        }

        val tsMarker = ",\"ts\":"
        if (!text.startsWith(tsMarker, i)) return null
        i += tsMarker.length
        val tsStart = i
        while (i < text.length && text[i] in '0'..'9') i++
        if (i == tsStart) return null
        if (i - tsStart > 1 && text[tsStart] == '0') return null
        val ts = text.substring(tsStart, i).toLongOrNull() ?: return null

        if (i != text.length - 1 || text[i] != '}') return null
        if (battery !in 0..100) return null
        return StatusInfo(battery, charging, ts)
    }

    private fun statusAad(deviceId: String): ByteArray =
        (STATUS_AAD_PREFIX + deviceId).toByteArray(Charsets.UTF_8)

    // ---------------------------------------------------------------- URLs

    /**
     * Validates a share URL and returns its canonical form for local use.
     * Null means reject. The returned string never crosses the wire, so only
     * the accept/reject decision has to match the Go validator.
     *
     * Rules, in order: trim, cap at 2048 UTF-16 code units, reject control
     * characters and the URL-unsafe set, then require an http/https scheme and
     * a non-empty host. The character whitelist is stricter than RFC 3986 on
     * purpose; the Go validator mirrors it so both sides reject the same
     * inputs.
     */
    fun validateShareUrl(raw: String): String? {
        val trimmed = trimSpace(raw)
        // Counted in UTF-16 code units (String.length), matching the Go
        // validator's utf16Length helper.
        if (trimmed.length > MAX_SHARE_URL_LENGTH) return null
        for (c in trimmed) {
            if (isRejectedUrlChar(c)) return null
        }
        val uri = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrEmpty()) return null
        return canonicalUrl(uri, trimmed)
    }

    /**
     * Lowercases the scheme and host while leaving every other byte of the
     * URL untouched. Rebuilding the URI through java.net.URI's component
     * constructor is not an option: it quotes '%' again ("%20" -> "%2520")
     * and would change the path.
     */
    private fun canonicalUrl(uri: URI, raw: String): String {
        val scheme = uri.scheme ?: return raw
        val host = uri.host ?: return raw
        val authority = uri.rawAuthority ?: return raw
        // raw is "<scheme>://<authority><rest>" once trimmed.
        if (!raw.startsWith("//", scheme.length + 1)) return raw
        val authorityStart = scheme.length + 3
        if (!raw.regionMatches(authorityStart, authority, 0, authority.length)) return raw
        val hostStart = authorityStart + authority.lastIndexOf('@') + 1
        if (hostStart + host.length > raw.length) return raw
        val out = StringBuilder(raw)
        for (i in scheme.indices) out[i] = scheme[i].lowercaseChar()
        for (i in host.indices) out[hostStart + i] = host[i].lowercaseChar()
        return out.toString()
    }

    private fun isRejectedUrlChar(c: Char): Boolean {
        if (c.code < 0x21 || c.code == 0x7F) return true
        return when (c) {
            '"', '<', '>', '\\', '^', '`', '{', '|', '}' -> true
            else -> false
        }
    }

    /**
     * Trims exactly the code points Go's strings.TrimSpace trims
     * (unicode.IsSpace). Kotlin's String.trim() would also strip U+2007,
     * which Go keeps, so the two validators could disagree on edge cases.
     */
    private fun trimSpace(value: String): String {
        var start = 0
        var end = value.length
        while (start < end && isGoSpace(value[start])) start++
        while (end > start && isGoSpace(value[end - 1])) end--
        return value.substring(start, end)
    }

    private fun isGoSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u0085', '\u00A0',
        '\u1680', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000',
        -> true
        else -> c in '\u2000'..'\u200A'
    }
}

/** Decoded status sample. [ts] is the sender's wall clock in milliseconds. */
data class StatusInfo(val battery: Int, val charging: Boolean, val ts: Long)
