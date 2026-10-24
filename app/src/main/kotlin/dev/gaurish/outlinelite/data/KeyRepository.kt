package dev.gaurish.outlinelite.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "outline_lite")

/** Persists access keys (added manually or via ss:// links) plus the active selection. */
object KeyRepository {

    private val KEYS = stringPreferencesKey("keys_v1")
    private val ACTIVE = stringPreferencesKey("active_id")
    private val AUTOSTART = booleanPreferencesKey("autostart")
    private val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")

    fun observeKeys(context: Context): Flow<List<AccessKey>> =
        context.dataStore.data.map { decodeKeys(it[KEYS]) }

    fun observeActiveId(context: Context): Flow<String?> =
        context.dataStore.data.map { it[ACTIVE] }

    fun observeAutostart(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[AUTOSTART] ?: false }

    fun observeAutoReconnect(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[AUTO_RECONNECT] ?: true }

    suspend fun autoReconnect(context: Context): Boolean =
        context.dataStore.data.first()[AUTO_RECONNECT] ?: true

    suspend fun activeKey(context: Context): AccessKey? {
        val keys = decodeKeys(context.dataStore.data.first()[KEYS])
        val active = context.dataStore.data.first()[ACTIVE]
        return keys.firstOrNull { it.id == active }
    }

    suspend fun autostart(context: Context): Boolean = context.dataStore.data.first()[AUTOSTART] ?: false

    /** Adds a key. Returns false if a key with the same id already exists. */
    suspend fun addKey(context: Context, key: AccessKey): Boolean {
        var added = false
        context.dataStore.edit { prefs ->
            val list = decodeKeys(prefs[KEYS]).toMutableList()
            if (list.none { it.id == key.id }) {
                list += key
                added = true
                prefs[KEYS] = encodeKeys(list)
                if (prefs[ACTIVE] == null) {
                    prefs[ACTIVE] = key.id
                }
            }
        }
        return added
    }

    suspend fun setActive(context: Context, id: String) {
        context.dataStore.edit { prefs ->
            val exists = decodeKeys(prefs[KEYS]).any { it.id == id }
            if (exists) prefs[ACTIVE] = id
        }
    }

    suspend fun removeKey(context: Context, id: String) {
        context.dataStore.edit { prefs ->
            val list = decodeKeys(prefs[KEYS]).filterNot { it.id == id }
            prefs[KEYS] = encodeKeys(list)
            if (prefs[ACTIVE] == id) {
                val next = list.firstOrNull()?.id
                if (next != null) {
                    prefs[ACTIVE] = next
                } else {
                    prefs.remove(ACTIVE)
                }
            }
        }
    }

    suspend fun setAutostart(context: Context, enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[AUTOSTART] = enabled }
    }

    suspend fun setAutoReconnect(context: Context, enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[AUTO_RECONNECT] = enabled }
    }

    /*
     * JSON: on-device org.json is provided by the framework.
     */
    private fun encodeKeys(keys: List<AccessKey>): String {
        val arr = JSONArray()
        keys.forEach {
            arr.put(
                JSONObject()
                    .put("name", it.name)
                    .put("host", it.host)
                    .put("port", it.port)
                    .put("method", it.method)
                    .put("password", it.password)
            )
        }
        return arr.toString()
    }

    private fun decodeKeys(json: String?): List<AccessKey> {
        json ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                AccessKey(
                    name = o.optString("name"),
                    host = o.optString("host"),
                    port = o.optInt("port"),
                    method = o.optString("method"),
                    password = o.optString("password")
                )
            }.filter { it.host.isNotBlank() && it.port > 0 && it.method.isNotBlank() }
        }.getOrDefault(emptyList())
    }
}
