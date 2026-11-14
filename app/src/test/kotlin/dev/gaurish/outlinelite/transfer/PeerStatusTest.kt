package dev.gaurish.outlinelite.transfer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerStatusTest {

    private val kPeer = ByteArray(32) { it.toByte() }
    private val statusKey = ClusterCrypto.deriveStatusKey(kPeer)
    private val selfId = "android-self"

    /** Pinned clock for the freshness window; the blobs below use this ts. */
    private val now = 1_730_000_000_000L

    private fun peer(id: String, name: String, status: String? = null): JSONObject =
        JSONObject().put("id", id).put("name", name).apply {
            if (status != null) put("status", status)
        }

    @Test
    fun validStatusIsAttachedToThePeer() {
        val blob = ClusterCrypto.encodeStatusBlob(
            statusKey, "laptop-1", 82, true, now,
        )
        val array = JSONArray()
            .put(peer("laptop-1", "Laptop", blob))
            .put(peer("laptop-2", "Desk"))
            .put(peer(selfId, "Phone"))

        val decoded = PeerStatus.decodePeers(array, statusKey, selfId, now)

        assertEquals(3, decoded.peers.size)
        assertEquals(82, decoded.peers[0].battery)
        assertEquals(true, decoded.peers[0].charging)
        assertEquals("Laptop", decoded.peers[0].name)
        assertNull(decoded.peers[1].battery)
        assertNull(decoded.peers[1].charging)
        assertTrue(decoded.peers[2].isSelf)
        assertTrue(decoded.malformedStatusIds.isEmpty())
        assertTrue(decoded.staleStatusIds.isEmpty())
    }

    @Test
    fun malformedStatusFailsClosedAndIsReportedOncePerEntry() {
        // A blob bound to a different device id cannot be swapped in.
        val foreign = ClusterCrypto.encodeStatusBlob(
            statusKey, "laptop-other", 40, false, now,
        )
        val array = JSONArray()
            .put(peer("laptop-1", "Laptop", foreign))
            .put(peer("laptop-2", "Desk", "not a blob"))

        val decoded = PeerStatus.decodePeers(array, statusKey, selfId, now)

        assertNull(decoded.peers[0].battery)
        assertNull(decoded.peers[0].charging)
        assertNull(decoded.peers[1].battery)
        assertEquals(listOf("laptop-1", "laptop-2"), decoded.malformedStatusIds)
    }

    @Test
    fun entriesWithoutAnIdAreSkippedAndBlankNamesFallBackToTheId() {
        val array = JSONArray()
            .put(JSONObject().put("name", "No id"))
            .put(peer("laptop-1", ""))

        val decoded = PeerStatus.decodePeers(array, statusKey, selfId, now)

        assertEquals(1, decoded.peers.size)
        assertEquals("laptop-1", decoded.peers[0].name)
        assertEquals("laptop-1", decoded.peers[0].id)
    }

    @Test
    fun wrongKeyDropsTheStatus() {
        val otherKey = ClusterCrypto.deriveStatusKey(ByteArray(32) { 0x42 })
        val blob = ClusterCrypto.encodeStatusBlob(
            statusKey, "laptop-1", 82, true, now,
        )
        val decoded = PeerStatus.decodePeers(
            JSONArray().put(peer("laptop-1", "Laptop", blob)),
            otherKey,
            selfId,
            now,
        )
        assertNull(decoded.peers[0].battery)
        assertEquals(listOf("laptop-1"), decoded.malformedStatusIds)
    }

    @Test
    fun staleStatusIsDroppedButThePeerRemains() {
        val blob = ClusterCrypto.encodeStatusBlob(
            statusKey, "laptop-1", 82, true, now,
        )
        val array = JSONArray().put(peer("laptop-1", "Laptop", blob))

        // One millisecond past the freshness window: the battery is gone.
        val stale = PeerStatus.decodePeers(
            array,
            statusKey,
            selfId,
            now + PeerStatus.MAX_STATUS_AGE_MS + 1,
        )
        assertEquals(1, stale.peers.size)
        assertEquals("Laptop", stale.peers[0].name)
        assertNull(stale.peers[0].battery)
        assertNull(stale.peers[0].charging)
        assertEquals(listOf("laptop-1"), stale.staleStatusIds)
        assertTrue(stale.malformedStatusIds.isEmpty())

        // Exactly at the boundary the blob is still fresh.
        val fresh = PeerStatus.decodePeers(
            array,
            statusKey,
            selfId,
            now + PeerStatus.MAX_STATUS_AGE_MS,
        )
        assertEquals(82, fresh.peers[0].battery)
        assertEquals(true, fresh.peers[0].charging)
        assertTrue(fresh.staleStatusIds.isEmpty())
    }
}
