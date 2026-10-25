package dev.gaurish.outlinelite.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.splitTunnelDataStore by preferencesDataStore(name = "outline_lite_split_tunnel")

/**
 * Manages the set of application package names excluded from the VPN tunnel (split tunneling).
 */
object SplitTunnelRepository {

    private val EXCLUDED_PACKAGES = stringSetPreferencesKey("excluded_packages_v1")

    fun observeExcludedPackages(context: Context): Flow<Set<String>> =
        context.splitTunnelDataStore.data.map { it[EXCLUDED_PACKAGES] ?: emptySet() }

    suspend fun getExcludedPackages(context: Context): Set<String> =
        context.splitTunnelDataStore.data.first()[EXCLUDED_PACKAGES] ?: emptySet()

    suspend fun setExcludedPackages(context: Context, packages: Set<String>) {
        context.splitTunnelDataStore.edit { prefs ->
            prefs[EXCLUDED_PACKAGES] = packages
        }
    }

    suspend fun togglePackage(context: Context, packageName: String) {
        context.splitTunnelDataStore.edit { prefs ->
            val current = prefs[EXCLUDED_PACKAGES]?.toMutableSet() ?: mutableSetOf()
            if (current.contains(packageName)) {
                current.remove(packageName)
            } else {
                current.add(packageName)
            }
            prefs[EXCLUDED_PACKAGES] = current
        }
    }
}
