package dev.gaurish.outlinelite.transfer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the v3.2 request/decline inner payloads and the optional request echo
 * in a returning v3 offer. The Go desktop client builds and parses the same
 * shapes, so the literals here are the cross-platform contract.
 */
class RequestCodecTest {

    private val requestId = "req-0011223344556677"
    private val requesterId = "desktop-abc123"
    private val requesterName = "Gaurish Laptop"
    private val targetId = "android-f3dd4a01"
    private val expiresTs = 1_767_225_600_000L
    private val grant = "cd232c541e09733b88b73687f7b4806e7cf8ad22995adf2b5fe7542bc77506d6"
    private val ts = 1_767_225_300_000L

    /**
     * Every parse runs one minute before [expiresTs]. The codec now enforces
     * the request window, so the pinned wire fixtures need a pinned clock
     * rather than the wall clock.
     */
    private val now = expiresTs - 60_000L

    private fun parse(json: JSONObject) = RequestCodec.parseRequest(json, now)

    private fun validRequest(): JSONObject = JSONObject()
        .put("request_id", requestId)
        .put("requester_id", requesterId)
        .put("requester_name", requesterName)
        .put("target_id", targetId)
        .put("kind", "files")
        .put("expires_ts", expiresTs)
        .put("grant", grant)
        .put("ts", ts)

    // ------------------------------------------------------------ request

    @Test
    fun validRequestParsesEveryField() {
        val parsed = parse(validRequest())
        assertNotNull(parsed)
        assertEquals(requestId, parsed!!.requestId)
        assertEquals(requesterId, parsed.requesterId)
        assertEquals(requesterName, parsed.requesterName)
        assertEquals(targetId, parsed.targetId)
        assertEquals(RequestKind.FILES, parsed.kind)
        assertEquals(expiresTs, parsed.expiresTs)
        assertEquals(grant, parsed.grant)
        assertEquals(ts, parsed.ts)
    }

    @Test
    fun desktopRequesterIdentityShapeIsAccepted() {
        // The desktop CLI asks under a short-lived "reqdev-<pid>-<hex>"
        // identity, not its daemon device id.
        val json = JSONObject(validRequest().toString())
            .put("requester_id", "reqdev-4242-a1b2")
            .put("requester_name", "Lilac")
        val parsed = parse(json)
        assertNotNull(parsed)
        assertEquals("reqdev-4242-a1b2", parsed!!.requesterId)
        assertEquals("Lilac", parsed.requesterName)
    }

    @Test
    fun originIdIsOptionalAndDrivesTheTrustId() {
        // Older clients and Android omit origin_id; the trust gate then uses
        // requester_id.
        val legacy = parse(validRequest())
        assertNull(legacy!!.originId)
        assertEquals(requesterId, legacy.trustId)

        // A desktop CLI keeps its ephemeral connection id in requester_id and
        // carries the stable device id in origin_id.
        val desktop = JSONObject(validRequest().toString())
            .put("requester_id", "reqdev-4242-a1b2")
            .put("origin_id", "laptop-1")
        val parsed = parse(desktop)
        assertNotNull(parsed)
        assertEquals("reqdev-4242-a1b2", parsed!!.requesterId)
        assertEquals("laptop-1", parsed.originId)
        assertEquals("laptop-1", parsed.trustId)
    }

    @Test
    fun nullOriginIdIsTreatedAsAbsent() {
        val json = JSONObject(validRequest().toString()).put("origin_id", JSONObject.NULL)
        val parsed = parse(json)
        assertNotNull(parsed)
        assertNull(parsed!!.originId)
        assertEquals(requesterId, parsed.trustId)
    }

    @Test
    fun malformedOriginIdRejectsTheWholeRequest() {
        val badValues = listOf<Any>(
            "",
            "two words",
            "tab\there",
            "x".repeat(65),
            42,
        )
        for (bad in badValues) {
            val json = JSONObject(validRequest().toString()).put("origin_id", bad)
            assertNull("accepted origin_id: $bad", parse(json))
        }
    }

    @Test
    fun buildRequestCarriesOriginIdOnlyWhenPresent() {
        val withOrigin = RequestCodec.buildRequest(
            requestId = requestId,
            requesterId = "reqdev-4242-a1b2",
            originId = "laptop-1",
            requesterName = requesterName,
            targetId = targetId,
            kind = RequestKind.FILES,
            expiresTs = expiresTs,
            grant = grant,
            ts = ts,
        )
        assertEquals("laptop-1", withOrigin.getString("origin_id"))
        assertEquals("laptop-1", parse(withOrigin)!!.trustId)

        val legacy = RequestCodec.buildRequest(
            requestId = requestId,
            requesterId = requesterId,
            requesterName = requesterName,
            targetId = targetId,
            kind = RequestKind.FILES,
            expiresTs = expiresTs,
            grant = grant,
            ts = ts,
        )
        assertFalse(legacy.has("origin_id"))
        assertEquals(requesterId, parse(legacy)!!.trustId)
    }

    @Test
    fun buildRequestRoundTrips() {
        val built = RequestCodec.buildRequest(
            requestId = requestId,
            requesterId = requesterId,
            requesterName = requesterName,
            targetId = targetId,
            kind = RequestKind.SCREENSHOT,
            expiresTs = expiresTs,
            grant = grant,
            ts = ts,
        )
        val parsed = parse(built)
        assertNotNull(parsed)
        assertEquals(RequestKind.SCREENSHOT, parsed!!.kind)
        assertEquals(grant, parsed.grant)
    }

    @Test
    fun everyMissingFieldIsRejected() {
        val required = listOf(
            "request_id", "requester_id", "requester_name", "target_id",
            "kind", "expires_ts", "grant", "ts",
        )
        for (field in required) {
            val json = JSONObject(validRequest().toString()).apply { remove(field) }
            assertNull("accepted without $field", parse(json))
        }
    }

    @Test
    fun grantMustBeSixtyFourLowercaseHexChars() {
        val badGrants = listOf(
            "",
            "not-hex",
            grant.dropLast(1),
            grant + "0",
            grant.uppercase(),
            "g".repeat(64),
            "0".repeat(63) + "G",
        )
        for (bad in badGrants) {
            val json = JSONObject(validRequest().toString()).put("grant", bad)
            assertNull("accepted grant: $bad", parse(json))
        }
    }

    @Test
    fun requestIdShapeIsPinned() {
        val badIds = listOf(
            "",
            "req-",
            "req-001122334455667",
            "req-00112233445566778",
            "req-00112233445566ZZ",
            "REQ-0011223344556677",
            "req_0011223344556677",
        )
        for (bad in badIds) {
            val json = JSONObject(validRequest().toString()).put("request_id", bad)
            assertNull("accepted id: $bad", parse(json))
        }
    }

    @Test
    fun kindIsPinnedToFilesAndScreenshot() {
        assertEquals(
            RequestKind.SCREENSHOT,
            parse(JSONObject(validRequest().toString()).put("kind", "screenshot"))!!.kind,
        )
        for (bad in listOf("file", "FILES", "Screenshot", "")) {
            val json = JSONObject(validRequest().toString()).put("kind", bad)
            assertNull("accepted kind: $bad", parse(json))
        }
    }

    @Test
    fun expiresTsAcceptsLongAndWholeFloat() {
        val asLong = parse(validRequest())
        assertEquals(expiresTs, asLong!!.expiresTs)

        // org.json turns a decimal literal into a Double; a whole value is
        // still a valid epoch-ms timestamp.
        val asDouble = JSONObject(validRequest().toString())
            .put("expires_ts", expiresTs.toDouble())
        assertEquals(expiresTs, parse(asDouble)!!.expiresTs)

        // Fractional, non-numeric, zero, negative, and out-of-range values
        // are all rejected.
        val badValues = listOf<Any>(
            expiresTs + 0.5,
            expiresTs.toString(),
            0,
            -1L,
            1e300,
            JSONObject.NULL,
        )
        for (bad in badValues) {
            val json = JSONObject(validRequest().toString()).put("expires_ts", bad)
            assertNull("accepted expires_ts: $bad", parse(json))
        }
    }

    @Test
    fun requesterNameIsTrimmedAndBounded() {
        val padded = JSONObject(validRequest().toString()).put("requester_name", "  Laptop  ")
        assertEquals("Laptop", parse(padded)!!.requesterName)

        val long = "n".repeat(400)
        val bounded = JSONObject(validRequest().toString()).put("requester_name", long)
        assertEquals(120, parse(bounded)!!.requesterName.length)

        val blank = JSONObject(validRequest().toString()).put("requester_name", "   ")
        assertEquals(requesterId, parse(blank)!!.requesterName)
    }

    @Test
    fun deviceIdsUseTheRelayCharset() {
        assertTrue(RequestCodec.isDeviceId("android-f3dd4a01"))
        assertTrue(RequestCodec.isDeviceId("laptop.local-1"))
        assertTrue(RequestCodec.isDeviceId("reqdev-4242-a1b2"))
        assertTrue(RequestCodec.isDeviceId("A_b-9.z"))
        assertFalse(RequestCodec.isDeviceId(""))
        assertFalse(RequestCodec.isDeviceId("two words"))
        assertFalse(RequestCodec.isDeviceId("tab\there"))
        assertFalse(RequestCodec.isDeviceId("x".repeat(65)))
        // ':' separates fields in the grant message; ',' splits desktop
        // target lists. Either would make an id ambiguous.
        assertFalse(RequestCodec.isDeviceId("laptop.local:1"))
        assertFalse(RequestCodec.isDeviceId("laptop-1,laptop-2"))
        assertFalse(RequestCodec.isDeviceId("laptop/1"))
        assertFalse(RequestCodec.isDeviceId("lilac→phone"))
    }

    // ------------------------------------------------------ request window

    @Test
    fun requestTtlAndSkewArePinned() {
        assertEquals(5 * 60 * 1000L, RequestCodec.REQUEST_TTL_MS)
        assertEquals(2 * 60 * 1000L, RequestCodec.CLOCK_SKEW_MS)
        assertEquals(7 * 60 * 1000L, RequestCodec.MAX_REQUEST_LIFETIME_MS)
    }

    @Test
    fun farFutureExpiryIsRejectedAtParse() {
        val base = JSONObject(validRequest().toString())
        // TTL 5 min + skew 2 min: anything past +7 min is not a request this
        // phone can serve, and would otherwise wedge the single request slot.
        val beyond = now + RequestCodec.MAX_REQUEST_LIFETIME_MS + 1
        assertNull(
            "accepted a far-future expiry",
            RequestCodec.parseRequest(base.put("expires_ts", beyond), now),
        )
    }

    @Test
    fun requestWindowBoundariesAreInclusive() {
        val base = JSONObject(validRequest().toString())
        // Exactly TTL + skew is still accepted.
        val max = now + RequestCodec.MAX_REQUEST_LIFETIME_MS
        assertEquals(
            max,
            RequestCodec.parseRequest(JSONObject(base.toString()).put("expires_ts", max), now)!!.expiresTs,
        )
        // One millisecond past it is not.
        assertNull(
            RequestCodec.parseRequest(
                JSONObject(base.toString()).put("expires_ts", max + 1),
                now,
            ),
        )
        // `now >= expiresTs` is expired everywhere: equality is rejected.
        assertNull(
            RequestCodec.parseRequest(JSONObject(base.toString()).put("expires_ts", now), now),
        )
        assertNull(
            RequestCodec.parseRequest(JSONObject(base.toString()).put("expires_ts", now - 1), now),
        )
        // One millisecond of life is enough to parse.
        assertEquals(
            now + 1,
            RequestCodec.parseRequest(JSONObject(base.toString()).put("expires_ts", now + 1), now)!!.expiresTs,
        )
    }

    @Test
    fun expiryBoundaryIsSharedByEveryCaller() {
        assertTrue(RequestCodec.isExpired(now, now))
        assertTrue(RequestCodec.isExpired(now - 1, now))
        assertFalse(RequestCodec.isExpired(now + 1, now))
        assertTrue(RequestCodec.isWithinRequestWindow(now + 1, now))
        assertTrue(RequestCodec.isWithinRequestWindow(now + RequestCodec.MAX_REQUEST_LIFETIME_MS, now))
        assertFalse(RequestCodec.isWithinRequestWindow(now, now))
        assertFalse(
            RequestCodec.isWithinRequestWindow(now + RequestCodec.MAX_REQUEST_LIFETIME_MS + 1, now),
        )
        assertTrue(RequestCodec.isBeyondRequestWindow(now + RequestCodec.MAX_REQUEST_LIFETIME_MS + 1, now))
        assertFalse(RequestCodec.isBeyondRequestWindow(now + RequestCodec.MAX_REQUEST_LIFETIME_MS, now))
    }

    // ------------------------------------------------------ display boundary

    @Test
    fun displayNameIsCollapsedToOneLine() {
        assertEquals("Lavander", singleLineDisplayName("  Lavander  "))
        assertEquals("a b  c", singleLineDisplayName("a\nb\r\nc"))
        assertEquals("a b c", singleLineDisplayName("a\tb\u0000c"))
        assertEquals("a b c", singleLineDisplayName("a\u007fb\u009bc"))
        assertEquals("a b c", singleLineDisplayName("a\u2028b\u2029c"))
        assertEquals("Lavandér 📱", singleLineDisplayName("Lavandér 📱"))
        assertEquals("", singleLineDisplayName("\n\r\t"))
    }

    @Test
    fun displayNameStripsUnicodeFormatCharacters() {
        // Zero-width space/joiners, bidi overrides, isolates, BOM, soft hyphen.
        assertEquals("Lilac", singleLineDisplayName("Lil\u200Bac"))
        assertEquals("Lilac", singleLineDisplayName("Lil\u200Dac"))
        assertEquals("Lilac", singleLineDisplayName("Lil\uFEFFac"))
        assertEquals("Lilac", singleLineDisplayName("Lil\u00ADac"))
        assertEquals("lilac", singleLineDisplayName("\u202Elilac\u202C"))
        assertEquals("lilac", singleLineDisplayName("\u2066lilac\u2069"))
        assertEquals("trusted", singleLineDisplayName("trus\u200Bted"))
        // Supplementary-plane Cf (U+E0001 LANGUAGE TAG) is stripped too.
        assertEquals("tag", singleLineDisplayName("tag\uDB40\uDC01"))
    }

    @Test
    fun requesterLabelShowsTheStableIdPrefix() {
        assertEquals("Lilac (lilac-76…)", requesterLabel("Lilac", "lilac-76ee-1234"))
        assertEquals("Lilac (lilac-76…)", requesterLabel("  Lil\u200Bac  ", "lilac-76ee-1234"))
        // A blank name falls back to the id, never to an empty title.
        assertEquals("lilac-76ee-1234 (lilac-76…)", requesterLabel("", "lilac-76ee-1234"))
    }

    // ------------------------------------------------------------ decline

    @Test
    fun everyDeclineReasonRoundTrips() {
        for (reason in DeclineReason.entries) {
            val built = RequestCodec.buildDecline(requestId, reason, ts)
            val parsed = RequestCodec.parseDecline(built)
            assertNotNull("rejected reason: ${reason.wire}", parsed)
            assertEquals(requestId, parsed!!.requestId)
            assertEquals(reason, parsed.reason)
            assertEquals(ts, parsed.ts)
        }
    }

    @Test
    fun declineRejectsUnknownReasonsAndBadIds() {
        val unknown = RequestCodec.buildDecline(requestId, DeclineReason.BUSY, ts)
            .put("reason", "because")
        assertNull(RequestCodec.parseDecline(unknown))

        val badId = RequestCodec.buildDecline("req-nope", DeclineReason.BUSY, ts)
        assertNull(RequestCodec.parseDecline(badId))

        val noTs = JSONObject(RequestCodec.buildDecline(requestId, DeclineReason.BUSY, ts).toString())
            .apply { remove("ts") }
        assertNull(RequestCodec.parseDecline(noTs))
    }

    // --------------------------------------------------------- offer echo

    @Test
    fun requestEchoSurvivesAnOfferRoundTrip() {
        val offer = JSONObject()
            .put("type", "transfer_offer")
            .put("v", 3)
            .put("transfer_id", "tx-00112233445566778899aabbccddeeff")
            .put("file_name", "notes.txt")
        val context = RequestContext(requestId, grant)
        RequestCodec.applyRequestEcho(offer, context)

        // The ordinary offer fields are untouched.
        assertEquals("tx-00112233445566778899aabbccddeeff", offer.getString("transfer_id"))
        assertEquals("notes.txt", offer.getString("file_name"))
        assertEquals(requestId, offer.getString("request_id"))
        assertEquals(grant, offer.getString("grant"))

        val parsed = RequestCodec.requestEcho(JSONObject(offer.toString()))
        assertEquals(context, parsed)
    }

    @Test
    fun offersWithoutARequestAreUnchanged() {
        val offer = JSONObject().put("type", "transfer_offer").put("v", 3)
        RequestCodec.applyRequestEcho(offer, null)
        assertFalse(offer.has("request_id"))
        assertFalse(offer.has("grant"))
        assertNull(RequestCodec.requestEcho(offer))
    }

    @Test
    fun partialOrMalformedEchoIsRejected() {
        val onlyId = JSONObject().put("request_id", requestId)
        assertNull(RequestCodec.requestEcho(onlyId))

        val onlyGrant = JSONObject().put("grant", grant)
        assertNull(RequestCodec.requestEcho(onlyGrant))

        val badGrant = JSONObject().put("request_id", requestId).put("grant", "abc")
        assertNull(RequestCodec.requestEcho(badGrant))
    }
}
