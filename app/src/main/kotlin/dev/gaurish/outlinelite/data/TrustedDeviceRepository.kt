package dev.gaurish.outlinelite.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.trustedDeviceDataStore by preferencesDataStore(name = "outline_lite_trusted")

data class TrustedDevice(
    val id: String,
    val name: String,
    val trustedAt: Long,
)

/**
 * Peer-side trust list. The cluster token still gates relay access; this list
 * decides whose offers are auto-accepted and gives the user a revoke action
 * without rotating the token for the whole cluster.
 */
object TrustedDeviceRepository {

    private val DEVICES = stringPreferencesKey("trusted_devices_v1")

    fun observe(context: Context): Flow<List<TrustedDevice>> =
        context.trustedDeviceDataStore.data.map { decode(it[DEVICES]) }

    suspend fun isTrusted(context: Context, id: String): Boolean =
        decode(context.trustedDeviceDataStore.data.first()[DEVICES]).any { it.id == id }

    suspend fun trust(context: Context, id: String, name: String) {
        if (id.isBlank()) return
        context.trustedDeviceDataStore.edit { prefs ->
            val list = decode(prefs[DEVICES]).toMutableList()
            list.removeAll { it.id == id }
            list.add(0, TrustedDevice(id, name.ifBlank { id }, System.currentTimeMillis()))
            prefs[DEVICES] = encode(list)
        }
    }

    suspend fun revoke(context: Context, id: String) {
        context.trustedDeviceDataStore.edit { prefs ->
            prefs[DEVICES] = encode(decode(prefs[DEVICES]).filterNot { it.id == id })
        }
    }

    private fun encode(devices: List<TrustedDevice>): String {
        val arr = JSONArray()
        devices.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("trusted_at", it.trustedAt)
            )
        }
        return arr.toString()
    }

    private fun decode(raw: String?): List<TrustedDevice> {
        raw ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                if (id.isBlank()) return@mapNotNull null
                TrustedDevice(
                    id = id,
                    name = o.optString("name", id),
                    trustedAt = o.optLong("trusted_at", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }
}
