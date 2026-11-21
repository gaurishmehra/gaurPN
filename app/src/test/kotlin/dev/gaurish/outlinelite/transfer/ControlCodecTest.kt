package dev.gaurish.outlinelite.transfer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCodecTest {

    private val peerKey = ByteArray(32) { it.toByte() }
    private val now = 1_700_000_000_000L
    private val requestId = "req-0123456789abcdef"
    private val expires = now + 60_000L
    private val grant = ClusterCrypto.computeRequestGrant(
        peerKey, requestId, "reqdev-1-abcd", "phone-1", expires,
    )

    private fun approval(overrides: JSONObject.() -> Unit = {}): JSONObject = JSONObject().apply {
        put("request_id", requestId)
        put("requester_id", "reqdev-1-abcd")
        put("origin_id", "desktop-1")
        put("requester_name", "Laptop")
        put("target_id", "phone-1")
        put("reason", "Deploy to prod?")
        put("expires_ts", expires)
        put("grant", grant)
        put("ts", now)
        overrides()
    }

    private fun open(kind: String, value: String, overrides: JSONObject.() -> Unit = {}): JSONObject =
        JSONObject().apply {
            put("request_id", requestId)
            put("requester_id", "reqdev-1-abcd")
            put("origin_id", "desktop-1")
            put("requester_name", "Laptop")
            put("target_id", "phone-1")
            put("open_kind", kind)
            put("value", value)
            put("expires_ts", expires)
            put("grant", grant)
            put("ts", now)
            overrides()
        }

    @Test
    fun parsesAValidApproval() {
        val parsed = ControlCodec.parseApproval(approval(), now)
        assertNotNull(parsed)
        assertEquals(requestId, parsed!!.requestId)
        assertEquals("desktop-1", parsed.trustId)
        assertEquals("Deploy to prod?", parsed.reason)
    }

    @Test
    fun rejectsExpiredOrMalformedApprovals() {
        assertNull(ControlCodec.parseApproval(approval { put("expires_ts", now - 1) }, now))
        assertNull(ControlCodec.parseApproval(approval { put("request_id", "nope") }, now))
        assertNull(ControlCodec.parseApproval(approval { put("grant", "00") }, now))
        assertNull(ControlCodec.parseApproval(approval { put("reason", "line\nbreak") }, now))
        assertNull(ControlCodec.parseApproval(approval { put("reason", "x".repeat(201)) }, now))
        assertNull(ControlCodec.parseApproval(approval { put("origin_id", "bad id") }, now))
    }

    @Test
    fun parsesUrlAndPackageHandoffs() {
        val url = ControlCodec.parseOpen(open("url", "https://Example.com/x"), now)
        assertNotNull(url)
        assertEquals("https://example.com/x", url!!.value)
        val pkg = ControlCodec.parseOpen(open("package", "com.example.app"), now)
        assertNotNull(pkg)
        assertEquals("com.example.app", pkg!!.value)
    }

    @Test
    fun rejectsUnsafeOrMalformedHandoffs() {
        assertNull(ControlCodec.parseOpen(open("url", "javascript:alert(1)"), now))
        assertNull(ControlCodec.parseOpen(open("url", "file:///etc/passwd"), now))
        assertNull(ControlCodec.parseOpen(open("package", "not a package"), now))
        assertNull(ControlCodec.parseOpen(open("package", "com..example"), now))
        assertNull(ControlCodec.parseOpen(open("url", "https://example.com/x") {
            put("expires_ts", now - 1)
        }, now))
    }

    @Test
    fun buildsPinnedResponses() {
        val response = ControlCodec.buildApprovalResponse(
            requestId, "phone-1", "denied", "user_denied", grant, now,
        )
        assertEquals(requestId, response.getString("request_id"))
        assertEquals("phone-1", response.getString("responder_id"))
        assertEquals("denied", response.getString("decision"))
        assertEquals("user_denied", response.getString("reason"))
        assertTrue(ControlCodec.isControlReason("user_denied"))
        assertFalse(ControlCodec.isControlReason("made_up"))

        val result = ControlCodec.buildOpenResult(
            requestId, "phone-1", "tx-1", "notification", "", "", now,
        )
        assertEquals("notification", result.getString("status"))
        assertFalse(result.has("reason"))
        assertFalse(result.has("grant"))
    }
}
