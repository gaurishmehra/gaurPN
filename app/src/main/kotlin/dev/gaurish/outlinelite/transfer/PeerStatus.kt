package dev.gaurish.outlinelite.transfer

import org.json.JSONArray

/** Peers decoded from one relay broadcast plus the entries whose status failed. */
internal data class DecodedPeers(
    val peers: List<ClusterPeer>,
    val malformedStatusIds: List<String>,
    /** Entries whose blob authenticated but was older than the freshness window. */
    val staleStatusIds: List<String> = emptyList(),
)

/**
 * Pure decoder for the relay's `{"type":"peers"}` broadcast.
 *
 * Each entry is `{"id","name","status"?}` where `status` is an opaque
 * encrypted blob from [ClusterCrypto.encodeStatusBlob]. A missing blob
 * leaves the battery fields null; a malformed one is reported so the caller
 * can log it once per device and otherwise stay quiet.
 *
 * A blob whose authenticated `ts` is older than [MAX_STATUS_AGE_MS] is
 * dropped (battery fields stay null) so a malicious relay cannot replay stale
 * battery state. The desktop client applies the same freshness rule.
 */
internal object PeerStatus {

    /** Status older than this is stale; the battery fields are dropped. */
    const val MAX_STATUS_AGE_MS = 10 * 60 * 1000L

    fun decodePeers(
        peersArray: JSONArray,
        statusKey: ByteArray,
        selfId: String,
        now: Long = System.currentTimeMillis(),
    ): DecodedPeers {
        val peers = ArrayList<ClusterPeer>(peersArray.length())
        val malformed = ArrayList<String>()
        val stale = ArrayList<String>()
        for (i in 0 until peersArray.length()) {
            val entry = peersArray.optJSONObject(i) ?: continue
            val id = entry.optString("id")
            if (id.isBlank()) continue
            val name = entry.optString("name").ifBlank { id }
            val blob = entry.optString("status")
            val info = if (blob.isBlank()) {
                null
            } else {
                ClusterCrypto.decodeStatusBlob(statusKey, id, blob)
            }
            if (blob.isNotBlank() && info == null) malformed.add(id)
            val isStale = info != null && info.ts < now - MAX_STATUS_AGE_MS
            if (isStale) stale.add(id)
            val fresh = if (isStale) null else info
            peers.add(
                ClusterPeer(
                    id = id,
                    name = name,
                    isSelf = id == selfId,
                    battery = fresh?.battery,
                    charging = fresh?.charging,
                ),
            )
        }
        return DecodedPeers(peers, malformed, stale)
    }
}
