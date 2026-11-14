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

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Where DNS queries go while the tunnel is up.
 *
 * ADGUARD sends every query through the Shadowsocks tunnel to the AdGuard Home
 * instance running on the VPS. SYSTEM reuses the underlying network's resolvers
 * (still through the tunnel). CUSTOM accepts a comma-separated list.
 */
enum class DnsMode { ADGUARD, SYSTEM, CUSTOM }

/** Persists access keys (added manually or via ss:// links) plus the active selection. */
object KeyRepository {

    private val KEYS = stringPreferencesKey("keys_v1")
    private val ACTIVE = stringPreferencesKey("active_id")
    private val AUTOSTART = booleanPreferencesKey("autostart")
    private val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
    private val THEME = stringPreferencesKey("theme_mode")
    private val DEVICE_NAME = stringPreferencesKey("cluster_device_name")
    private val CLUSTER_TOKEN = stringPreferencesKey("cluster_token")
    private val RELAY_ADDR = stringPreferencesKey("cluster_relay_addr_v1")
    private val CLIPBOARD_SYNC = booleanPreferencesKey("clipboard_sync_enabled")
    private val CLIPBOARD_SENSITIVE = booleanPreferencesKey("clipboard_include_sensitive")
    private val NOTIFICATION_SYNC = booleanPreferencesKey("notification_sync_enabled")
    private val MEDIA_SYNC = booleanPreferencesKey("media_sync_enabled")
    private val DNS_MODE = stringPreferencesKey("dns_mode_v1")
    private val DNS_SERVERS = stringPreferencesKey("dns_servers_v1")
    private val BLOCK_ON_RECONNECT = booleanPreferencesKey("block_on_reconnect")
    private val DEVICE_ID = stringPreferencesKey("cluster_device_id_v1")
    private val CLIPBOARD_EXCLUDED = stringPreferencesKey("clipboard_excluded_peers_v1")
    const val DEFAULT_RELAY_ADDR = "198.51.100.1:9876"

    /**
     * AdGuard Home on the VPS, reached through the Shadowsocks tunnel.
     * The VPS private address is stable and is forced into the tunnel with a
     * /32 route, so LAN bypass cannot steal it.
     */
    const val DEFAULT_DNS_SERVERS = "10.0.0.182"

    fun observeRelayAddress(context: Context): Flow<String> =
        context.dataStore.data.map { it[RELAY_ADDR] ?: DEFAULT_RELAY_ADDR }

    suspend fun getRelayAddress(context: Context): String =
        context.dataStore.data.first()[RELAY_ADDR] ?: DEFAULT_RELAY_ADDR

    suspend fun setRelayAddress(context: Context, address: String) {
        val clean = address.trim()
        if (clean.isNotBlank()) {
            context.dataStore.edit { it[RELAY_ADDR] = clean }
        }
    }

    fun observeDeviceName(context: Context): Flow<String> =
        context.dataStore.data.map { it[DEVICE_NAME] ?: android.os.Build.MODEL }

    suspend fun getDeviceName(context: Context): String =
        context.dataStore.data.first()[DEVICE_NAME] ?: android.os.Build.MODEL

    suspend fun setDeviceName(context: Context, name: String) {
        context.dataStore.edit { it[DEVICE_NAME] = name.trim() }
    }

    fun observeClusterToken(context: Context): Flow<String> =
        context.dataStore.data.map { it[CLUSTER_TOKEN] ?: "" }

    suspend fun getClusterToken(context: Context): String =
        context.dataStore.data.first()[CLUSTER_TOKEN] ?: ""

    suspend fun setClusterToken(context: Context, token: String) {
        context.dataStore.edit { it[CLUSTER_TOKEN] = token.trim() }
    }

    fun observeClipboardSync(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[CLIPBOARD_SYNC] ?: false }

    suspend fun clipboardSyncEnabled(context: Context): Boolean =
        context.dataStore.data.first()[CLIPBOARD_SYNC] ?: false

    suspend fun setClipboardSync(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[CLIPBOARD_SYNC] = enabled }
    }

    fun observeNotificationSync(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[NOTIFICATION_SYNC] ?: false }

    suspend fun notificationSyncEnabled(context: Context): Boolean =
        context.dataStore.data.first()[NOTIFICATION_SYNC] ?: false

    suspend fun setNotificationSync(context: Context, enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[NOTIFICATION_SYNC] = enabled }
    }

    fun observeMediaSync(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[MEDIA_SYNC] ?: false }

    suspend fun mediaSyncEnabled(context: Context): Boolean =
        context.dataStore.data.first()[MEDIA_SYNC] ?: false

    suspend fun setMediaSync(context: Context, enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[MEDIA_SYNC] = enabled }
    }

    fun observeClipboardSensitive(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[CLIPBOARD_SENSITIVE] ?: false }

    suspend fun setClipboardSensitive(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[CLIPBOARD_SENSITIVE] = enabled }
    }

    fun observeKeys(context: Context): Flow<List<AccessKey>> =
        context.dataStore.data.map { decodeKeys(it[KEYS]) }

    fun observeActiveId(context: Context): Flow<String?> =
        context.dataStore.data.map { it[ACTIVE] }

    fun observeAutostart(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[AUTOSTART] ?: false }

    fun observeAutoReconnect(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[AUTO_RECONNECT] ?: true }

    fun observeThemeMode(context: Context): Flow<ThemeMode> =
        context.dataStore.data.map { prefs ->
            when (prefs[THEME]) {
                "light" -> ThemeMode.LIGHT
                "dark" -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
        }

    suspend fun setThemeMode(context: Context, mode: ThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[THEME] = when (mode) {
                ThemeMode.LIGHT -> "light"
                ThemeMode.DARK -> "dark"
                ThemeMode.SYSTEM -> "system"
            }
        }
    }

    suspend fun autoReconnect(context: Context): Boolean =
        context.dataStore.data.first()[AUTO_RECONNECT] ?: true

    suspend fun activeKey(context: Context): AccessKey? {
        val prefs = context.dataStore.data.first()
        val keys = decodeKeys(prefs[KEYS])
        val active = prefs[ACTIVE]
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

    suspend fun renameKey(context: Context, id: String, newName: String) {
        val cleanName = newName.trim()
        if (cleanName.isBlank()) return
        context.dataStore.edit { prefs ->
            val list = decodeKeys(prefs[KEYS]).map { key ->
                if (key.id == id) key.copy(name = cleanName) else key
            }
            prefs[KEYS] = encodeKeys(list)
        }
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

    fun observeDnsMode(context: Context): Flow<DnsMode> =
        context.dataStore.data.map { dnsModeFrom(it[DNS_MODE]) }

    suspend fun getDnsMode(context: Context): DnsMode =
        dnsModeFrom(context.dataStore.data.first()[DNS_MODE])

    suspend fun setDnsMode(context: Context, mode: DnsMode) {
        context.dataStore.edit { prefs ->
            prefs[DNS_MODE] = when (mode) {
                DnsMode.ADGUARD -> "adguard"
                DnsMode.SYSTEM -> "system"
                DnsMode.CUSTOM -> "custom"
            }
        }
    }

    fun observeDnsServers(context: Context): Flow<String> =
        context.dataStore.data.map { it[DNS_SERVERS] ?: DEFAULT_DNS_SERVERS }

    suspend fun getDnsServers(context: Context): String =
        context.dataStore.data.first()[DNS_SERVERS] ?: DEFAULT_DNS_SERVERS

    suspend fun setDnsServers(context: Context, servers: String) {
        context.dataStore.edit { prefs -> prefs[DNS_SERVERS] = servers.trim() }
    }

    /** Keep the TUN interface up while reconnecting so traffic is dropped instead of leaking. */
    fun observeBlockOnReconnect(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[BLOCK_ON_RECONNECT] ?: true }

    suspend fun blockOnReconnect(context: Context): Boolean =
        context.dataStore.data.first()[BLOCK_ON_RECONNECT] ?: true

    suspend fun setBlockOnReconnect(context: Context, enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[BLOCK_ON_RECONNECT] = enabled }
    }

    /**
     * Stable per-install identity so trust decisions survive process restarts.
     * The old implementation generated a fresh UUID in RelayManager.start(),
     * which made every reconnect look like a new device.
     */
    suspend fun getOrCreateDeviceId(context: Context): String {
        val existing = context.dataStore.data.first()[DEVICE_ID]
        if (!existing.isNullOrBlank()) return existing
        val generated = "android-" + java.util.UUID.randomUUID().toString().take(8)
        context.dataStore.edit { prefs ->
            if (prefs[DEVICE_ID].isNullOrBlank()) prefs[DEVICE_ID] = generated
        }
        return context.dataStore.data.first()[DEVICE_ID] ?: generated
    }

    fun observeClipboardExcludedPeers(context: Context): Flow<Set<String>> =
        context.dataStore.data.map { prefs ->
            prefs[CLIPBOARD_EXCLUDED]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()
        }

    suspend fun clipboardExcludedPeers(context: Context): Set<String> =
        context.dataStore.data.first()[CLIPBOARD_EXCLUDED]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    suspend fun setClipboardExcluded(context: Context, peerId: String, excluded: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[CLIPBOARD_EXCLUDED]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toMutableSet()
                ?: mutableSetOf()
            if (excluded) current.add(peerId) else current.remove(peerId)
            prefs[CLIPBOARD_EXCLUDED] = current.joinToString(",")
        }
    }

    private fun dnsModeFrom(raw: String?): DnsMode = when (raw) {
        "system" -> DnsMode.SYSTEM
        "custom" -> DnsMode.CUSTOM
        else -> DnsMode.ADGUARD
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
