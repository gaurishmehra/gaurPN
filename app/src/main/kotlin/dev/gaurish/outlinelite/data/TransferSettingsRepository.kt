package dev.gaurish.outlinelite.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.transferPrefsDataStore by preferencesDataStore(name = "outline_lite_transfer_prefs")

/**
 * Transfer-related user preferences, stored in their own DataStore
 * (same pattern as [SplitTunnelRepository]).
 */
object TransferSettingsRepository {

    private val AUTO_OPEN_RECEIVED = booleanPreferencesKey("auto_open_received_v1")
    private val ONLY_TRUSTED = booleanPreferencesKey("only_trusted_devices_v1")

    /** Whether files received over the cluster relay open automatically. Default: on. */
    fun observeAutoOpen(context: Context): Flow<Boolean> =
        context.transferPrefsDataStore.data.map { it[AUTO_OPEN_RECEIVED] ?: true }

    suspend fun getAutoOpen(context: Context): Boolean =
        context.transferPrefsDataStore.data.first()[AUTO_OPEN_RECEIVED] ?: true

    suspend fun setAutoOpen(context: Context, enabled: Boolean) {
        context.transferPrefsDataStore.edit { prefs ->
            prefs[AUTO_OPEN_RECEIVED] = enabled
        }
    }

    /** Reject offers from devices that have not been explicitly trusted. */
    fun observeOnlyTrusted(context: Context): Flow<Boolean> =
        context.transferPrefsDataStore.data.map { it[ONLY_TRUSTED] ?: false }

    suspend fun onlyTrusted(context: Context): Boolean =
        context.transferPrefsDataStore.data.first()[ONLY_TRUSTED] ?: false

    suspend fun setOnlyTrusted(context: Context, enabled: Boolean) {
        context.transferPrefsDataStore.edit { prefs -> prefs[ONLY_TRUSTED] = enabled }
    }
}
