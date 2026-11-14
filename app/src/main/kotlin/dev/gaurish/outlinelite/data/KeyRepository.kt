package dev.gaurish.outlinelite.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
     * Seals secret values (cluster token, SS keys JSON) before they land in
     * DataStore. Injectable so JVM tests can swap in a fake box; the real
     * one is only ever built on an Android runtime via the AndroidKeyStore.
     */
    internal var secretBoxProvider: (Context) -> SecretBox = { KeystoreSecretBox(it) }

    private fun box(context: Context): SecretBox = secretBoxProvider(context.applicationContext)

    /** Opens a stored secret; null stays null, legacy plaintext passes through. */
    private fun openSecret(context: Context, raw: String?): String? {
        if (raw == null) return null
        if (!raw.startsWith(SECRET_MARKER)) return raw
        return unwrapSecret(raw, box(context))
    }

    private fun sealStored(context: Context, plain: String): String =
        sealSecret(plain, box(context))

    private fun decodeStoredKeys(context: Context, raw: String?): List<AccessKey> =
        decodeKeys(openSecret(context, raw) ?: "")

    private fun encodeStoredKeys(context: Context, keys: List<AccessKey>): String =
        sealStored(context, encodeKeys(keys))

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
        context.dataStore.data
            .map { openSecret(context, it[CLUSTER_TOKEN]) ?: "" }
            .flowOn(Dispatchers.IO)

    suspend fun getClusterToken(context: Context): String = withContext(Dispatchers.IO) {
        val raw = context.dataStore.data.first()[CLUSTER_TOKEN] ?: return@withContext ""
        if (!raw.startsWith(SECRET_MARKER)) {
            // Legacy plaintext: best-effort re-seal, then return it as-is.
            if (raw.isNotEmpty()) runCatching { setClusterToken(context, raw) }
            return@withContext raw
        }
        unwrapSecret(raw, box(context))
    }

    suspend fun setClusterToken(context: Context, token: String) {
        val clean = token.trim()
        context.dataStore.edit { prefs ->
            prefs[CLUSTER_TOKEN] = sealStored(context, clean)
        }
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
        context.dataStore.data
            .map { decodeStoredKeys(context, it[KEYS]) }
            .flowOn(Dispatchers.IO)

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

    suspend fun activeKey(context: Context): AccessKey? = withContext(Dispatchers.IO) {
        val prefs = context.dataStore.data.first()
        val rawKeys = prefs[KEYS]
        val keys = decodeStoredKeys(context, rawKeys)
        val active = prefs[ACTIVE]
        val migration = migrateAccessKeyIds(keys, active)
        // Re-seal a legacy plaintext key list and persist the remapped
        // selection in one pass ("persist the migrated list once").
        val needsReseal = !rawKeys.isNullOrEmpty() && !rawKeys.startsWith(SECRET_MARKER)
        if (migration.changed || needsReseal) {
            runCatching {
                context.dataStore.edit { p ->
                    if (needsReseal) p[KEYS] = encodeStoredKeys(context, keys)
                    if (migration.changed) p[ACTIVE] = migration.activeId ?: ""
                }
            }
        }
        keys.firstOrNull { it.id == migration.activeId }
    }

    suspend fun autostart(context: Context): Boolean = context.dataStore.data.first()[AUTOSTART] ?: false

    /** Adds a key. Returns false if a key with the same id already exists. */
    suspend fun addKey(context: Context, key: AccessKey): Boolean {
        var added = false
        context.dataStore.edit { prefs ->
            val list = decodeStoredKeys(context, prefs[KEYS]).toMutableList()
            val migration = migrateAccessKeyIds(list, prefs[ACTIVE])
            if (migration.changed) prefs[ACTIVE] = migration.activeId ?: ""
            if (list.none { it.id == key.id }) {
                list += key
                added = true
                prefs[KEYS] = encodeStoredKeys(context, list)
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
            val list = decodeStoredKeys(context, prefs[KEYS]).map { key ->
                if (key.id == id) key.copy(name = cleanName) else key
            }
            val migration = migrateAccessKeyIds(list, prefs[ACTIVE])
            if (migration.changed) prefs[ACTIVE] = migration.activeId ?: ""
            prefs[KEYS] = encodeStoredKeys(context, list)
        }
    }

    suspend fun setActive(context: Context, id: String) {
        context.dataStore.edit { prefs ->
            val keys = decodeStoredKeys(context, prefs[KEYS])
            val migration = migrateAccessKeyIds(keys, prefs[ACTIVE])
            if (migration.changed) prefs[ACTIVE] = migration.activeId ?: ""
            if (keys.any { it.id == id }) prefs[ACTIVE] = id
        }
    }

    suspend fun removeKey(context: Context, id: String) {
        context.dataStore.edit { prefs ->
            val list = decodeStoredKeys(context, prefs[KEYS]).filterNot { it.id == id }
            val migration = migrateAccessKeyIds(list, prefs[ACTIVE])
            val activeStillValid = prefs[ACTIVE] == null || list.any { it.id == migration.activeId }
            prefs[KEYS] = encodeStoredKeys(context, list)
            when {
                !activeStillValid -> {
                    val next = list.firstOrNull()?.id
                    if (next != null) {
                        prefs[ACTIVE] = next
                    } else {
                        prefs.remove(ACTIVE)
                    }
                }
                migration.changed -> prefs[ACTIVE] = migration.activeId ?: ""
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
