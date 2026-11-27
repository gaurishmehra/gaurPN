package dev.gaurish.outlinelite.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
    private val ALLOW_FILE_REQUESTS = booleanPreferencesKey("allow_file_requests_v1")
    private val ALLOW_SCREENSHOT_REQUESTS = booleanPreferencesKey("allow_screenshot_requests_v1")
    private val ALLOW_REMOTE_OPEN = booleanPreferencesKey("allow_remote_open_v1")
    private val ALLOW_CAMERA_REQUESTS = booleanPreferencesKey("allow_camera_requests_v1")
    private val CAMERA_QUALITY = stringPreferencesKey("camera_quality_v1")
    private val CAMERA_LENS = stringPreferencesKey("camera_lens_v1")
    private val CAMERA_AUTO_ACCEPT_TRUSTED = booleanPreferencesKey("camera_auto_accept_trusted_v1")

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

    /**
     * Whether cluster peers may browse this phone's shared collections and
     * fetch files from them. On by default: the trust gate applies first and
     * the phone only exposes its six media collections.
     */
    fun observeAllowFileRequests(context: Context): Flow<Boolean> =
        context.transferPrefsDataStore.data.map { it[ALLOW_FILE_REQUESTS] ?: true }

    suspend fun setAllowFileRequests(context: Context, enabled: Boolean) {
        context.transferPrefsDataStore.edit { prefs -> prefs[ALLOW_FILE_REQUESTS] = enabled }
    }

    /**
     * Whether a peer may pull the latest screenshot without a picker. Off by
     * default: it is the one request kind that sends content automatically.
     */
    fun observeAllowScreenshotRequests(context: Context): Flow<Boolean> =
        context.transferPrefsDataStore.data.map { it[ALLOW_SCREENSHOT_REQUESTS] ?: false }

    suspend fun setAllowScreenshotRequests(context: Context, enabled: Boolean) {
        context.transferPrefsDataStore.edit { prefs -> prefs[ALLOW_SCREENSHOT_REQUESTS] = enabled }
    }

    /**
     * Whether a trusted device may open a URL or launch an app on this phone.
     * On by default; the explicit trust list is the consent boundary.
     */
    fun observeAllowRemoteOpen(context: Context): Flow<Boolean> =
        context.transferPrefsDataStore.data.map { it[ALLOW_REMOTE_OPEN] ?: true }

    suspend fun getAllowRemoteOpen(context: Context): Boolean =
        context.transferPrefsDataStore.data.first()[ALLOW_REMOTE_OPEN] ?: true

    suspend fun setAllowRemoteOpen(context: Context, enabled: Boolean) {
        context.transferPrefsDataStore.edit { prefs -> prefs[ALLOW_REMOTE_OPEN] = enabled }
    }

    /**
     * Whether a trusted peer may ask to view this phone's camera as a live
     * stream. Off by default: it is the one request kind that shows the world
     * through this phone, so it needs both the explicit trust of the
     * requester and this switch.
     */
    fun observeAllowCameraRequests(context: Context): Flow<Boolean> =
        context.transferPrefsDataStore.data.map { it[ALLOW_CAMERA_REQUESTS] ?: false }

    suspend fun getAllowCameraRequests(context: Context): Boolean =
        context.transferPrefsDataStore.data.first()[ALLOW_CAMERA_REQUESTS] ?: false

    suspend fun setAllowCameraRequests(context: Context, enabled: Boolean) {
        context.transferPrefsDataStore.edit { prefs -> prefs[ALLOW_CAMERA_REQUESTS] = enabled }
    }

    /**
     * Quality tier a new camera session starts at. Stored as the pinned wire
     * word ("high"/"balanced"/"low"); a stored value this build does not know
     * falls back to the default tier at the mapping site, never on disk.
     */
    fun observeCameraQuality(context: Context): Flow<String> =
        context.transferPrefsDataStore.data.map { it[CAMERA_QUALITY] ?: CAMERA_QUALITY_DEFAULT }

    suspend fun getCameraQuality(context: Context): String =
        context.transferPrefsDataStore.data.first()[CAMERA_QUALITY] ?: CAMERA_QUALITY_DEFAULT

    suspend fun setCameraQuality(context: Context, quality: String) {
        context.transferPrefsDataStore.edit { prefs -> prefs[CAMERA_QUALITY] = quality }
    }

    /** Lens a new camera session starts with ("back"/"front"). */
    fun observeCameraLens(context: Context): Flow<String> =
        context.transferPrefsDataStore.data.map { it[CAMERA_LENS] ?: CAMERA_LENS_DEFAULT }

    suspend fun getCameraLens(context: Context): String =
        context.transferPrefsDataStore.data.first()[CAMERA_LENS] ?: CAMERA_LENS_DEFAULT

    suspend fun setCameraLens(context: Context, lens: String) {
        context.transferPrefsDataStore.edit { prefs -> prefs[CAMERA_LENS] = lens }
    }

    /**
     * Whether a camera request from a trusted device skips the Accept prompt
     * and starts streaming immediately. Off by default: auto-accepting a
     * live camera view should be a deliberate choice.
     */
    fun observeCameraAutoAcceptTrusted(context: Context): Flow<Boolean> =
        context.transferPrefsDataStore.data.map { it[CAMERA_AUTO_ACCEPT_TRUSTED] ?: false }

    suspend fun getCameraAutoAcceptTrusted(context: Context): Boolean =
        context.transferPrefsDataStore.data.first()[CAMERA_AUTO_ACCEPT_TRUSTED] ?: false

    suspend fun setCameraAutoAcceptTrusted(context: Context, enabled: Boolean) {
        context.transferPrefsDataStore.edit { prefs -> prefs[CAMERA_AUTO_ACCEPT_TRUSTED] = enabled }
    }

    /** Wire word for the default camera quality tier. */
    const val CAMERA_QUALITY_DEFAULT = "high"

    /** Wire word for the default camera lens. */
    const val CAMERA_LENS_DEFAULT = "back"
}
