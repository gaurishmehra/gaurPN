package dev.gaurish.outlinelite.transfer

import org.json.JSONObject

/**
 * Parsed sealed `ask` payload: a desktop asks the phone to approve an action.
 * [trustId] is the stable device id the trust list is keyed by; a desktop CLI
 * connects under an ephemeral reqdev- id and carries its real id in origin_id.
 */
internal data class ApprovalEnvelope(
    val requestId: String,
    val requesterId: String,
    val originId: String?,
    val requesterName: String,
    val targetId: String,
    val reason: String,
    val expiresTs: Long,
    val grant: String,
    val ts: Long,
) {
    val trustId: String get() = originId ?: requesterId
}

/** Parsed sealed `open` payload: a URL or app package to open on this phone. */
internal data class OpenEnvelope(
    val requestId: String,
    val requesterId: String,
    val originId: String?,
    val requesterName: String,
    val targetId: String,
    val openKind: String,
    val value: String,
    val expiresTs: Long,
    val grant: String,
    val ts: Long,
) {
    val trustId: String get() = originId ?: requesterId
}

/**
 * Pure codec for the v3.3 approval and handoff control payloads. Every parse
 * is strict: a malformed field rejects the whole message before any
 * notification or activity is touched.
 *
 * Wire layout (pinned by the Go desktop client):
 *  - ask: {"request_id","requester_id","origin_id","requester_name",
 *    "target_id","reason","expires_ts","grant","ts"}
 *  - ask_response: {"request_id","responder_id","decision","reason",
 *    "grant","ts"}
 *  - open: {"request_id","requester_id","origin_id","requester_name",
 *    "target_id","open_kind","value","expires_ts","grant","ts"}
 *  - open_result: {"request_id","responder_id","transfer_id","status",
 *    "reason","grant","ts"}
 */
internal object ControlCodec {

    const val MAX_REASON_CHARS = 200
    private const val MAX_NAME_CHARS = 120
    private const val MAX_VALUE_CHARS = 2048
    private const val MAX_PACKAGE_CHARS = 255

    private val PACKAGE_PATTERN =
        Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    private val APPROVAL_REASONS = setOf(
        "user_denied", "expired", "busy", "not_allowed", "notifications_blocked",
        "device_locked", "auto_open_disabled", "no_handler", "invalid",
    )

    fun parseApproval(
        json: JSONObject,
        now: Long = System.currentTimeMillis(),
    ): ApprovalEnvelope? {
        val requestId = json.optString("request_id")
        if (!RequestCodec.isRequestId(requestId)) return null
        val requesterId = json.optString("requester_id")
        if (!RequestCodec.isDeviceId(requesterId)) return null
        val originId = when (val raw = json.opt("origin_id")) {
            null, JSONObject.NULL -> null
            is String -> raw.takeIf { RequestCodec.isDeviceId(it) } ?: return null
            else -> return null
        }
        val requesterName = parseRequesterName(json, requesterId) ?: return null
        val targetId = json.optString("target_id")
        if (!RequestCodec.isDeviceId(targetId)) return null

        val reason = json.opt("reason")
        if (reason !is String) return null
        val cleanReason = reason.trim()
        if (cleanReason.isEmpty() ||
            cleanReason.codePointCount(0, cleanReason.length) > MAX_REASON_CHARS
        ) {
            return null
        }
        if (cleanReason.any { it.code < 0x20 || it.code == 0x7F }) return null

        val expiresTs = json.optLong("expires_ts", 0L)
        if (!RequestCodec.isWithinRequestWindow(expiresTs, now)) return null
        val grant = json.optString("grant")
        if (!RequestCodec.isGrant(grant)) return null
        val ts = json.optLong("ts", 0L)
        if (ts <= 0L) return null

        return ApprovalEnvelope(
            requestId, requesterId, originId, requesterName, targetId,
            cleanReason, expiresTs, grant, ts,
        )
    }

    fun parseOpen(
        json: JSONObject,
        now: Long = System.currentTimeMillis(),
    ): OpenEnvelope? {
        val requestId = json.optString("request_id")
        if (!RequestCodec.isRequestId(requestId)) return null
        val requesterId = json.optString("requester_id")
        if (!RequestCodec.isDeviceId(requesterId)) return null
        val originId = when (val raw = json.opt("origin_id")) {
            null, JSONObject.NULL -> null
            is String -> raw.takeIf { RequestCodec.isDeviceId(it) } ?: return null
            else -> return null
        }
        val requesterName = parseRequesterName(json, requesterId) ?: return null
        val targetId = json.optString("target_id")
        if (!RequestCodec.isDeviceId(targetId)) return null

        val kind = json.optString("open_kind")
        val rawValue = json.opt("value")
        if (rawValue !is String || rawValue.length > MAX_VALUE_CHARS) return null
        val value = when (kind) {
            "url" -> ClusterCrypto.validateShareUrl(rawValue) ?: return null
            "package" -> rawValue.trim().takeIf { isAndroidPackage(it) } ?: return null
            else -> return null
        }

        val expiresTs = json.optLong("expires_ts", 0L)
        if (!RequestCodec.isWithinRequestWindow(expiresTs, now)) return null
        val grant = json.optString("grant")
        if (!RequestCodec.isGrant(grant)) return null
        val ts = json.optLong("ts", 0L)
        if (ts <= 0L) return null

        return OpenEnvelope(
            requestId, requesterId, originId, requesterName, targetId,
            kind, value, expiresTs, grant, ts,
        )
    }

    fun buildApprovalResponse(
        requestId: String,
        responderId: String,
        decision: String,
        reason: String,
        grant: String,
        ts: Long,
    ): JSONObject = JSONObject().apply {
        put("request_id", requestId)
        put("responder_id", responderId)
        put("decision", decision)
        if (reason.isNotEmpty()) put("reason", reason)
        put("grant", grant)
        put("ts", ts)
    }

    fun buildOpenResult(
        requestId: String,
        responderId: String,
        transferId: String,
        status: String,
        reason: String,
        grant: String,
        ts: Long,
    ): JSONObject = JSONObject().apply {
        put("request_id", requestId)
        put("responder_id", responderId)
        if (transferId.isNotEmpty()) put("transfer_id", transferId)
        put("status", status)
        if (reason.isNotEmpty()) put("reason", reason)
        if (grant.isNotEmpty()) put("grant", grant)
        put("ts", ts)
    }

    fun isControlReason(value: String): Boolean = value in APPROVAL_REASONS

    fun isAndroidPackage(value: String): Boolean =
        value.length <= MAX_PACKAGE_CHARS && PACKAGE_PATTERN.matches(value)

    private fun parseRequesterName(json: JSONObject, requesterId: String): String? {
        if (!json.has("requester_name")) return null
        return json.optString("requester_name").trim().take(MAX_NAME_CHARS)
            .ifEmpty { requesterId }
    }
}
