package dev.gaurish.outlinelite.transfer

import org.json.JSONArray
import org.json.JSONObject

/** What a peer is asking for. Pinned wire values; do not rename. */
enum class RequestKind(val wire: String) {
    FILES("files"),
    SCREENSHOT("screenshot");

    companion object {
        fun fromWire(value: String): RequestKind? = entries.firstOrNull { it.wire == value }
    }
}

/** Why a request was refused. Pinned wire values; do not rename. */
enum class DeclineReason(val wire: String) {
    DECLINED("declined"),
    BUSY("busy"),
    EXPIRED("expired"),
    UNSUPPORTED("unsupported"),
    PICKER_UNAVAILABLE("picker_unavailable"),
    NOT_ALLOWED("not_allowed"),
    NOT_FOUND("not_found"),
    TOO_LARGE("too_large");

    companion object {
        fun fromWire(value: String): DeclineReason? = entries.firstOrNull { it.wire == value }
    }
}

/** Why a browse listing could not be produced. Pinned wire values. */
enum class BrowseError(val wire: String) {
    DENIED("denied"),
    NOT_FOUND("not_found"),
    RATE_LIMITED("rate_limited"),
    UNSUPPORTED("unsupported");

    companion object {
        fun fromWire(value: String): BrowseError? = entries.firstOrNull { it.wire == value }
    }
}

/** Parsed sealed `request` payload. */
internal data class RequestEnvelope(
    val requestId: String,
    val requesterId: String,
    val originId: String?,
    val requesterName: String,
    val targetId: String,
    val kind: RequestKind,
    val expiresTs: Long,
    val grant: String,
    val ts: Long,
) {
    /**
     * The id the trust gate checks. A desktop CLI asks under an ephemeral
     * `reqdev-<pid>-<hex>` connection id (so the relay does not evict the
     * daemon's connection) and carries its stable device id in `origin_id`.
     * Older clients omit the field and fall back to [requesterId]. Routing,
     * the grant, and the decline address always use [requesterId].
     */
    val trustId: String get() = originId ?: requesterId
}

/** Parsed sealed `request_declined` payload. */
internal data class DeclineEnvelope(
    val requestId: String,
    val reason: DeclineReason,
    val ts: Long,
)

/**
 * Parsed sealed `browse` payload: the requester asks for one directory
 * listing. [path] is "" for the root and otherwise a relative path with no
 * leading slash, no "." or ".." segment, and no control characters.
 */
internal data class BrowseEnvelope(
    val requestId: String,
    val requesterId: String,
    val originId: String?,
    val requesterName: String,
    val targetId: String,
    val path: String,
    val ts: Long,
) {
    /** Same trust fallback as [RequestEnvelope.trustId]. */
    val trustId: String get() = originId ?: requesterId
}

/** One row of a browse listing. `size` is bytes, `modified` is epoch-ms. */
internal data class BrowseEntry(
    val name: String,
    val size: Long,
    val modified: Long,
    val dir: Boolean,
)

/**
 * Parsed sealed `browse_listing` payload. Exactly one of [entries] and
 * [error] is meaningful: an error listing carries no entries, and a
 * successful listing carries no error. [entries] holds only rows that passed
 * validation: malformed rows are skipped (one bad name on the target's disk
 * must not blank the screen) and duplicate (name, dir) pairs are dropped so
 * the UI can key a list by them.
 */
internal data class BrowseListingEnvelope(
    val requestId: String,
    val path: String,
    val entries: List<BrowseEntry>,
    val truncated: Boolean,
    val error: BrowseError?,
    val ts: Long,
)

/**
 * Parsed sealed `fetch` payload: the requester asks for specific files it
 * saw in a listing. [paths] are relative fetch paths (1..32), and [grant] is
 * the existing v3.2 request grant bound to the request, requester, target,
 * and expiry.
 */
internal data class FetchEnvelope(
    val requestId: String,
    val requesterId: String,
    val originId: String?,
    val requesterName: String,
    val targetId: String,
    val paths: List<String>,
    val expiresTs: Long,
    val grant: String,
    val ts: Long,
) {
    /** Same trust fallback as [RequestEnvelope.trustId]. */
    val trustId: String get() = originId ?: requesterId
}

/**
 * Request metadata echoed in a returning v3 offer. The grant lets the
 * requesting desktop verify that the offer answers its own request; on the
 * Android receiver the fields are inert.
 */
data class RequestContext(val requestId: String, val grant: String)

/**
 * Pure codec for the v3.2 request/decline inner payloads, the v3.2.1 browse
 * envelopes, and the optional request echo in v3 offers. Every parse returns
 * null on anything that is not exactly the pinned shape, so a malformed
 * payload can never reach the notification or browse paths. The one
 * exception is a single row inside a `browse_listing`: rows are skipped
 * individually so one unlistable name cannot blank the screen.
 *
 * The wire layout is pinned by the Go desktop client:
 *  - request: {"request_id","requester_id","origin_id","requester_name",
 *    "target_id","kind","expires_ts","grant","ts"} with "origin_id" optional
 *  - decline: {"request_id","reason","ts"}
 *  - browse: {"request_id","requester_id","origin_id","requester_name",
 *    "target_id","path","ts"} with "origin_id" optional
 *  - browse_listing: {"request_id","path","entries","truncated","error","ts"}
 *    where entries and error are mutually exclusive
 *  - fetch: {"request_id","requester_id","origin_id","requester_name",
 *    "target_id","paths","expires_ts","grant","ts"} with "origin_id" optional
 *  - offer echo: optional "request_id" + "grant"
 */
internal object RequestCodec {

    /** How long a request stays actionable after it arrives. */
    const val REQUEST_TTL_MS = 5 * 60 * 1000L

    /** Allowed clock skew between cluster devices. */
    const val CLOCK_SKEW_MS = 2 * 60 * 1000L

    /**
     * Latest accepted `expires_ts` relative to the receiver's clock. A sealed
     * packet beyond this would otherwise hold the single request slot until
     * process restart.
     */
    const val MAX_REQUEST_LIFETIME_MS = REQUEST_TTL_MS + CLOCK_SKEW_MS

    private const val REQUEST_ID_PREFIX = "req-"
    private const val REQUEST_ID_RANDOM_CHARS = 16
    private const val REQUEST_ID_CHARS = REQUEST_ID_PREFIX.length + REQUEST_ID_RANDOM_CHARS
    private const val GRANT_HEX_CHARS = 64
    private const val MAX_DEVICE_ID_CHARS = 64
    private const val MAX_NAME_CHARS = 120

    /** Longest accepted browse/fetch path, in characters. */
    const val MAX_PATH_CHARS = 1024

    /** Most entries a `browse_listing` may carry. */
    const val MAX_LISTING_ENTRIES = 1000

    /** Most paths one `fetch` may request. */
    const val MAX_FETCH_PATHS = 32

    /** Longest accepted listing entry name (a single path segment). */
    const val MAX_ENTRY_NAME_CHARS = 255

    /** Largest exactly-representable integer a JSON double can carry. */
    private const val MAX_JSON_LONG = 9_007_199_254_740_991.0

    // ------------------------------------------------------------ request

    fun parseRequest(
        json: JSONObject,
        now: Long = System.currentTimeMillis(),
    ): RequestEnvelope? {
        val requestId = json.optString("request_id")
        if (!isRequestId(requestId)) return null

        val requesterId = json.optString("requester_id")
        if (!isDeviceId(requesterId)) return null

        // Optional: the requester's stable device id. A desktop CLI asks
        // under an ephemeral reqdev- id and carries the real one here; an
        // absent field means "trust requester_id" (older clients, and
        // Android, where both ids are the same). A present but malformed
        // value fails the whole parse rather than being ignored.
        val originId = when (val raw = json.opt("origin_id")) {
            null, JSONObject.NULL -> null
            is String -> raw.takeIf { isDeviceId(it) } ?: return null
            else -> return null
        }

        // The key must be present, but an empty display name falls back to
        // the id (the desktop receiver does the same), so a config with no
        // name still produces a usable request.
        if (!json.has("requester_name")) return null
        val requesterName = json.optString("requester_name").trim().take(MAX_NAME_CHARS)
            .ifEmpty { requesterId }

        val targetId = json.optString("target_id")
        if (!isDeviceId(targetId)) return null

        val kind = RequestKind.fromWire(json.optString("kind")) ?: return null
        val expiresTs = positiveLong(json, "expires_ts") ?: return null
        // One sealed packet with a far-future expiry would otherwise wedge
        // the single request slot until process restart; a request that is
        // already dead on arrival is not actionable either. The upper bound
        // is the sender's TTL plus allowed clock skew.
        if (!isWithinRequestWindow(expiresTs, now)) return null

        val grant = json.optString("grant")
        if (!isGrant(grant)) return null

        val ts = positiveLong(json, "ts") ?: return null

        return RequestEnvelope(
            requestId = requestId,
            requesterId = requesterId,
            originId = originId,
            requesterName = requesterName,
            targetId = targetId,
            kind = kind,
            expiresTs = expiresTs,
            grant = grant,
            ts = ts,
        )
    }

    fun buildRequest(
        requestId: String,
        requesterId: String,
        originId: String? = null,
        requesterName: String,
        targetId: String,
        kind: RequestKind,
        expiresTs: Long,
        grant: String,
        ts: Long,
    ): JSONObject = JSONObject().apply {
        put("request_id", requestId)
        put("requester_id", requesterId)
        if (!originId.isNullOrEmpty()) put("origin_id", originId)
        put("requester_name", requesterName)
        put("target_id", targetId)
        put("kind", kind.wire)
        put("expires_ts", expiresTs)
        put("grant", grant)
        put("ts", ts)
    }

    // ------------------------------------------------------------- browse

    fun parseBrowse(json: JSONObject): BrowseEnvelope? {
        val requestId = json.optString("request_id")
        if (!isRequestId(requestId)) return null

        val requesterId = json.optString("requester_id")
        if (!isDeviceId(requesterId)) return null

        val originId = when (val raw = json.opt("origin_id")) {
            null, JSONObject.NULL -> null
            is String -> raw.takeIf { isDeviceId(it) } ?: return null
            else -> return null
        }

        if (!json.has("requester_name")) return null
        val requesterName = json.optString("requester_name").trim().take(MAX_NAME_CHARS)
            .ifEmpty { requesterId }

        val targetId = json.optString("target_id")
        if (!isDeviceId(targetId)) return null

        // "" is the root listing; every other value must be a safe relative
        // path. The field itself is required, so a missing path is malformed
        // rather than an accidental root browse.
        val path = json.opt("path")
        if (path !is String || !isBrowsePath(path)) return null

        val ts = positiveLong(json, "ts") ?: return null

        return BrowseEnvelope(
            requestId = requestId,
            requesterId = requesterId,
            originId = originId,
            requesterName = requesterName,
            targetId = targetId,
            path = path,
            ts = ts,
        )
    }

    fun buildBrowse(
        requestId: String,
        requesterId: String,
        originId: String? = null,
        requesterName: String,
        targetId: String,
        path: String,
        ts: Long,
    ): JSONObject = JSONObject().apply {
        put("request_id", requestId)
        put("requester_id", requesterId)
        if (!originId.isNullOrEmpty()) put("origin_id", originId)
        put("requester_name", requesterName)
        put("target_id", targetId)
        put("path", path)
        put("ts", ts)
    }

    // ------------------------------------------------------ browse listing

    /**
     * Parses one `browse_listing`. The envelope is strict — a bad request id,
     * path, timestamp, error, or flag still rejects the whole payload, as
     * does `error` together with `entries`. Individual entry rows are not: a
     * row that fails [parseEntry] is skipped so one unlistable name on the
     * target's disk cannot blank the screen. At most [MAX_LISTING_ENTRIES]
     * accepted entries are kept, and duplicate (name, dir) pairs are dropped
     * with the first occurrence winning.
     */
    fun parseBrowseListing(json: JSONObject): BrowseListingEnvelope? {
        val requestId = json.optString("request_id")
        if (!isRequestId(requestId)) return null

        val path = json.opt("path")
        if (path !is String || !isBrowsePath(path)) return null

        val ts = positiveLong(json, "ts") ?: return null

        val error = when (val raw = json.opt("error")) {
            null, JSONObject.NULL -> null
            is String -> BrowseError.fromWire(raw) ?: return null
            else -> return null
        }
        val entriesRaw = json.opt("entries")

        if (error != null) {
            // An error listing and entries are mutually exclusive. A
            // truncated flag is meaningless without entries, so it is
            // accepted (some builders emit `false`) but ignored.
            if (entriesRaw != null && entriesRaw != JSONObject.NULL) return null
            return BrowseListingEnvelope(
                requestId = requestId,
                path = path,
                entries = emptyList(),
                truncated = false,
                error = error,
                ts = ts,
            )
        }

        val array = entriesRaw as? JSONArray ?: return null
        // One malformed row — a 300-char or control-char directory name on
        // the target's disk, a provider row that changed mid-listing — must
        // not blank the whole screen. Skip it and keep the rest. The cap
        // applies to accepted entries, and duplicate (name, dir) pairs are
        // dropped (first wins) because they would collide as Compose list
        // keys.
        val entries = ArrayList<BrowseEntry>(minOf(array.length(), MAX_LISTING_ENTRIES))
        val seen = HashSet<Pair<String, Boolean>>(minOf(array.length(), MAX_LISTING_ENTRIES) * 2)
        var capped = false
        for (i in 0 until array.length()) {
            if (entries.size >= MAX_LISTING_ENTRIES) {
                capped = true
                break
            }
            val entry = array.optJSONObject(i)?.let { parseEntry(it) } ?: continue
            if (!seen.add(entry.name to entry.dir)) continue
            entries.add(entry)
        }

        val truncated = when (val raw = json.opt("truncated")) {
            null, JSONObject.NULL -> capped
            is Boolean -> raw || capped
            else -> return null
        }

        return BrowseListingEnvelope(
            requestId = requestId,
            path = path,
            entries = entries,
            truncated = truncated,
            error = null,
            ts = ts,
        )
    }

    fun buildBrowseListing(
        requestId: String,
        path: String,
        entries: List<BrowseEntry> = emptyList(),
        truncated: Boolean = false,
        error: BrowseError? = null,
        ts: Long,
    ): JSONObject = JSONObject().apply {
        put("request_id", requestId)
        put("path", path)
        if (error != null) {
            put("error", error.wire)
        } else {
            put(
                "entries",
                JSONArray().apply {
                    entries.forEach { entry ->
                        put(
                            JSONObject().apply {
                                put("name", entry.name)
                                put("size", entry.size)
                                put("modified", entry.modified)
                                put("dir", entry.dir)
                            },
                        )
                    }
                },
            )
            if (truncated) put("truncated", true)
        }
        put("ts", ts)
    }

    private fun parseEntry(json: JSONObject): BrowseEntry? {
        val name = json.opt("name")
        if (name !is String || !isEntryName(name)) return null
        val size = nonNegativeLong(json, "size") ?: return null
        val modified = nonNegativeLong(json, "modified") ?: return null
        val dir = json.opt("dir") as? Boolean ?: return null
        return BrowseEntry(name, size, modified, dir)
    }

    // -------------------------------------------------------------- fetch

    fun parseFetch(
        json: JSONObject,
        now: Long = System.currentTimeMillis(),
    ): FetchEnvelope? {
        val requestId = json.optString("request_id")
        if (!isRequestId(requestId)) return null

        val requesterId = json.optString("requester_id")
        if (!isDeviceId(requesterId)) return null

        val originId = when (val raw = json.opt("origin_id")) {
            null, JSONObject.NULL -> null
            is String -> raw.takeIf { isDeviceId(it) } ?: return null
            else -> return null
        }

        if (!json.has("requester_name")) return null
        val requesterName = json.optString("requester_name").trim().take(MAX_NAME_CHARS)
            .ifEmpty { requesterId }

        val targetId = json.optString("target_id")
        if (!isDeviceId(targetId)) return null

        val pathsArray = json.opt("paths") as? JSONArray ?: return null
        if (pathsArray.length() < 1 || pathsArray.length() > MAX_FETCH_PATHS) return null
        val paths = ArrayList<String>(pathsArray.length())
        for (i in 0 until pathsArray.length()) {
            val raw = pathsArray.opt(i)
            if (raw !is String || !isRelativePath(raw)) return null
            paths.add(raw)
        }

        val expiresTs = positiveLong(json, "expires_ts") ?: return null
        if (!isWithinRequestWindow(expiresTs, now)) return null

        val grant = json.optString("grant")
        if (!isGrant(grant)) return null

        val ts = positiveLong(json, "ts") ?: return null

        return FetchEnvelope(
            requestId = requestId,
            requesterId = requesterId,
            originId = originId,
            requesterName = requesterName,
            targetId = targetId,
            paths = paths,
            expiresTs = expiresTs,
            grant = grant,
            ts = ts,
        )
    }

    fun buildFetch(
        requestId: String,
        requesterId: String,
        originId: String? = null,
        requesterName: String,
        targetId: String,
        paths: List<String>,
        expiresTs: Long,
        grant: String,
        ts: Long,
    ): JSONObject = JSONObject().apply {
        put("request_id", requestId)
        put("requester_id", requesterId)
        if (!originId.isNullOrEmpty()) put("origin_id", originId)
        put("requester_name", requesterName)
        put("target_id", targetId)
        put("paths", JSONArray().apply { paths.forEach { put(it) } })
        put("expires_ts", expiresTs)
        put("grant", grant)
        put("ts", ts)
    }

    // ------------------------------------------------------------ decline

    fun parseDecline(json: JSONObject): DeclineEnvelope? {
        val requestId = json.optString("request_id")
        if (!isRequestId(requestId)) return null
        val reason = DeclineReason.fromWire(json.optString("reason")) ?: return null
        val ts = positiveLong(json, "ts") ?: return null
        return DeclineEnvelope(requestId, reason, ts)
    }

    fun buildDecline(requestId: String, reason: DeclineReason, ts: Long): JSONObject =
        JSONObject().apply {
            put("request_id", requestId)
            put("reason", reason.wire)
            put("ts", ts)
        }

    // -------------------------------------------------------- offer echo

    /** Adds the optional request fields to a v3 offer; a no-op without a request. */
    fun applyRequestEcho(offer: JSONObject, context: RequestContext?) {
        if (context == null) return
        offer.put("request_id", context.requestId)
        offer.put("grant", context.grant)
    }

    /** Reads the optional request fields back out of a v3 offer. */
    fun requestEcho(offer: JSONObject): RequestContext? {
        val requestId = offer.optString("request_id")
        if (!isRequestId(requestId)) return null
        val grant = offer.optString("grant")
        if (!isGrant(grant)) return null
        return RequestContext(requestId, grant)
    }

    // -------------------------------------------------------- validation

    /**
     * `now >= expiresTs` is expired everywhere: the request handler, the
     * picker, and the request slot all use this boundary so a request cannot
     * be simultaneously live and dead.
     */
    fun isExpired(expiresTs: Long, now: Long = System.currentTimeMillis()): Boolean =
        now >= expiresTs

    /** True when [expiresTs] is further out than TTL plus clock skew allows. */
    fun isBeyondRequestWindow(
        expiresTs: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean = expiresTs > now + MAX_REQUEST_LIFETIME_MS

    /** True when [expiresTs] is still in the future and inside the clamp. */
    fun isWithinRequestWindow(
        expiresTs: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean = !isExpired(expiresTs, now) && !isBeyondRequestWindow(expiresTs, now)

    /** "req-" plus 16 lowercase hex chars, exactly as [ClusterCrypto.newRequestId]. */
    fun isRequestId(value: String): Boolean {
        if (value.length != REQUEST_ID_CHARS) return false
        if (!value.startsWith(REQUEST_ID_PREFIX)) return false
        return value.substring(REQUEST_ID_PREFIX.length).all { it in '0'..'9' || it in 'a'..'f' }
    }

    /**
     * A browse path: "" for the root, otherwise a relative path of non-empty
     * segments joined by "/". Leading/trailing slashes, "." and ".."
     * segments, empty segments, and control characters are rejected so a
     * peer can never steer the target outside its virtual collection roots.
     */
    fun isBrowsePath(value: String): Boolean = value.isEmpty() || isRelativePath(value)

    /** A non-empty browse path; fetch paths are never the root. */
    fun isRelativePath(value: String): Boolean {
        if (value.isEmpty() || value.length > MAX_PATH_CHARS) return false
        if (value.startsWith('/') || value.endsWith('/')) return false
        if (value.contains("//")) return false
        if (value.any { it.code < 0x20 || it.code in 0x7F..0x9F }) return false
        return value.split('/').all { it != "." && it != ".." }
    }

    /** A listing entry name is one safe path segment, never a separator. */
    fun isEntryName(value: String): Boolean {
        if (value.isEmpty() || value.length > MAX_ENTRY_NAME_CHARS) return false
        if (value == "." || value == "..") return false
        if (value.any { it == '/' || it.code < 0x20 || it.code in 0x7F..0x9F }) return false
        return true
    }

    /** Grants are 64 lowercase hex chars and compared exactly. */
    fun isGrant(value: String): Boolean =
        value.length == GRANT_HEX_CHARS && value.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * Device ids use the relay's connection charset: 1-64 chars of
     * `[A-Za-z0-9._-]`. `:` is rejected because it separates fields in the
     * grant message, and `,` because the desktop splits target lists on it;
     * either would make an id ambiguous.
     */
    fun isDeviceId(value: String): Boolean {
        if (value.isEmpty() || value.length > MAX_DEVICE_ID_CHARS) return false
        return value.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' ||
                it == '.' || it == '_' || it == '-'
        }
    }

    /**
     * Positive integral JSON number. Integers arrive as Int/Long and decimals
     * as Double/BigDecimal; both are accepted when the value is a whole
     * number, so "1767225600000" and "1767225600000.0" parse identically.
     */
    private fun positiveLong(json: JSONObject, key: String): Long? {
        val value = json.opt(key) ?: return null
        val asDouble = when (value) {
            is Int -> return value.toLong().takeIf { it > 0L }
            is Long -> return value.takeIf { it > 0L }
            is Number -> value.toDouble()
            else -> return null
        }
        if (!asDouble.isFinite() || asDouble <= 0.0 || asDouble > MAX_JSON_LONG) return null
        val asLong = asDouble.toLong()
        return if (asLong.toDouble() == asDouble) asLong else null
    }

    /** Like [positiveLong] but zero is valid (sizes and mtimes). */
    private fun nonNegativeLong(json: JSONObject, key: String): Long? {
        val value = json.opt(key) ?: return null
        val asDouble = when (value) {
            is Int -> return value.toLong().takeIf { it >= 0L }
            is Long -> return value.takeIf { it >= 0L }
            is Number -> value.toDouble()
            else -> return null
        }
        if (!asDouble.isFinite() || asDouble < 0.0 || asDouble > MAX_JSON_LONG) return null
        val asLong = asDouble.toLong()
        return if (asLong.toDouble() == asDouble) asLong else null
    }
}
