package dev.gaurish.outlinelite.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.transferHistoryDataStore by preferencesDataStore(name = "outline_lite_history")

/**
 * Local-only record of a transfer or text share. Nothing here is sent to the
 * relay or any other device; it exists so the user can reopen a received file
 * or retry a failed send after the notification is gone.
 */
data class TransferHistoryEntry(
    val id: String,
    val kind: String,          // "file" | "text" | "url"
    val direction: String,     // "sent" | "received"
    val peerId: String,
    val peerName: String,
    val label: String,         // file name, or a text preview
    val size: Long,
    val status: String,        // "active" | "completed" | "failed" | "cancelled"
    val timestamp: Long,
    val localPath: String? = null,
    val sourceUri: String? = null,
    val text: String? = null,
)

object TransferHistoryRepository {

    private val HISTORY = stringPreferencesKey("history_v1")
    private const val MAX_ENTRIES = 100

    /**
     * Decoding runs on IO, not in the collector's context. The collector is a
     * Compose `collectAsStateWithLifecycle`, i.e. the main thread, so parsing
     * the log inline here would block the frame that shows it.
     */
    fun observe(context: Context): Flow<List<TransferHistoryEntry>> =
        context.transferHistoryDataStore.data
            .map { decode(it[HISTORY]) }
            .flowOn(Dispatchers.IO)

    suspend fun add(context: Context, entry: TransferHistoryEntry) {
        context.transferHistoryDataStore.edit { prefs ->
            val list = decode(prefs[HISTORY]).toMutableList()
            list.removeAll { it.id == entry.id }
            list.add(0, entry)
            prefs[HISTORY] = encode(list.take(MAX_ENTRIES))
        }
    }

    suspend fun updateStatus(context: Context, id: String, status: String) {
        context.transferHistoryDataStore.edit { prefs ->
            val list = decode(prefs[HISTORY]).map {
                if (it.id == id) it.copy(status = status) else it
            }
            prefs[HISTORY] = encode(list)
        }
    }

    suspend fun clear(context: Context) {
        context.transferHistoryDataStore.edit { prefs -> prefs.remove(HISTORY) }
    }

    private fun encode(entries: List<TransferHistoryEntry>): String {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("kind", e.kind)
                    .put("direction", e.direction)
                    .put("peer_id", e.peerId)
                    .put("peer_name", e.peerName)
                    .put("label", e.label)
                    .put("size", e.size)
                    .put("status", e.status)
                    .put("timestamp", e.timestamp)
                    .put("local_path", e.localPath ?: "")
                    .put("source_uri", e.sourceUri ?: "")
                    .put("text", e.text ?: "")
            )
        }
        return arr.toString()
    }

    private fun decode(raw: String?): List<TransferHistoryEntry> {
        raw ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                if (id.isBlank()) return@mapNotNull null
                TransferHistoryEntry(
                    id = id,
                    kind = o.optString("kind", "file"),
                    direction = o.optString("direction", "sent"),
                    peerId = o.optString("peer_id"),
                    peerName = o.optString("peer_name", "Device"),
                    label = o.optString("label", "Transfer"),
                    size = o.optLong("size", 0L),
                    status = o.optString("status", "completed"),
                    timestamp = o.optLong("timestamp", 0L),
                    localPath = o.optString("local_path").ifBlank { null },
                    sourceUri = o.optString("source_uri").ifBlank { null },
                    text = o.optString("text").ifBlank { null },
                )
            }
        }.getOrDefault(emptyList())
    }
}
