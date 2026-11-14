package dev.gaurish.outlinelite.transfer

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadata
import android.net.Uri
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import dev.gaurish.outlinelite.data.KeyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class MediaBridgeState(
    val active: Boolean = false,
    val title: String? = null,
    val app: String? = null,
    val error: String? = null,
)

/**
 * Observes the device's active media session and mirrors it to the cluster as
 * sealed `media_state` messages. Commands from the desktop arrive as sealed
 * `media_command` messages and are executed through the session's
 * transport controls. Position is sent on state changes only; the desktop
 * interpolates it locally.
 */
object MediaSessionBridge {

    private const val TAG = "MediaSessionBridge"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(MediaBridgeState())
    val state: StateFlow<MediaBridgeState> = _state.asStateFlow()

    @Volatile
    private var enabled = false

    private var manager: MediaSessionManager? = null
    private var component: ComponentName? = null
    private var activeListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    @Volatile
    private var controller: MediaController? = null

    @Volatile
    private var callback: MediaController.Callback? = null

    private val controllers = ConcurrentHashMap<String, MediaController>()

    @Volatile
    private var lastArtworkHash: String? = null

    @Volatile
    private var lastArtwork: String? = null

    @Volatile
    private var loggedArtworkKeys = false

    fun refresh(context: Context) {
        scope.launch {
            enabled = runCatching { KeyRepository.mediaSyncEnabled(context) }
                .getOrDefault(false)
            if (!enabled) {
                mainHandler.post { stopOnMain() }
                return@launch
            }
            val appCtx = context.applicationContext
            mainHandler.post { startOnMain(appCtx) }
        }
    }

    fun onServiceDisconnected() {
        mainHandler.post { stopOnMain() }
    }

    fun onRelayConnected(context: Context) {
        if (!enabled) return
        mainHandler.post { sendState(context.applicationContext) }
    }

    private fun startOnMain(context: Context) {
        if (!enabled) return
        val msm = context.getSystemService(MediaSessionManager::class.java) ?: return
        val comp = ComponentName(context, NotificationMirrorService::class.java)
        manager = msm
        component = comp

        if (activeListener == null) {
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
                selectController(context, sessions ?: emptyList())
            }
            activeListener = listener
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    msm.addOnActiveSessionsChangedListener(listener, comp, mainHandler)
                } else {
                    msm.addOnActiveSessionsChangedListener(listener, comp)
                }
            }.onFailure { Log.w(TAG, "Media session listener failed: ${it.message}") }
        }

        val sessions = runCatching { msm.getActiveSessions(comp) }.getOrElse {
            Log.w(TAG, "Cannot read media sessions: ${it.message}")
            _state.value = MediaBridgeState(false, error = "Notification access required")
            emptyList()
        }
        selectController(context, sessions)
    }

    private fun selectController(context: Context, sessions: List<MediaController>) {
        controllers.clear()
        // Our own remote-media sessions represent desktop players. Mirroring
        // them back would echo desktop media to the desktop as "gaurPN".
        val localSessions = sessions.filter { it.packageName != context.packageName }
        localSessions.forEach { controllers[it.sessionToken.toString()] = it }

        val chosen = localSessions.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: localSessions.firstOrNull()

        if (chosen == null) {
            unregisterCallback()
            controller = null
            _state.value = MediaBridgeState(false, error = "No active media session")
            return
        }

        if (controller?.sessionToken != chosen.sessionToken) {
            unregisterCallback()
            controller = chosen
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    sendState(context)
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    sendState(context)
                }

                override fun onSessionDestroyed() {
                    sendState(context)
                }
            }
            callback = cb
            runCatching { chosen.registerCallback(cb, mainHandler) }
        }
        sendState(context)
    }

    private fun unregisterCallback() {
        val current = controller
        val cb = callback
        if (current != null && cb != null) {
            runCatching { current.unregisterCallback(cb) }
        }
        callback = null
    }

    private fun stopOnMain() {
        val msm = manager
        val listener = activeListener
        if (msm != null && listener != null) {
            runCatching { msm.removeOnActiveSessionsChangedListener(listener) }
        }
        activeListener = null
        unregisterCallback()
        controller = null
        controllers.clear()
        manager = null
        component = null
        _state.value = MediaBridgeState(false)
    }

    private fun sendState(context: Context) {
        val current = controller ?: return
        val metadata = current.metadata
        val playback = current.playbackState
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val playing = playback?.state == PlaybackState.STATE_PLAYING
        val position = playback?.position ?: 0L
        val rate = playback?.playbackSpeed?.toDouble() ?: 1.0
        val actions = playback?.actions ?: 0L
        val app = runCatching {
            val info = context.packageManager.getApplicationInfo(current.packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(current.packageName)

        val inner = JSONObject().apply {
            put("source_kind", "android")
            put("session_id", current.sessionToken.toString())
            put("app", app)
            put("title", title)
            put("artist", artist)
            put("album", album)
            put("duration_ms", duration)
            put("position_ms", position)
            put("rate", rate)
            put("playing", playing)
            put("can_play", actions == 0L || actions and PlaybackState.ACTION_PLAY != 0L)
            put("can_pause", actions == 0L || actions and PlaybackState.ACTION_PAUSE != 0L)
            put("can_next", actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
            put("can_prev", actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
            put("can_seek", actions and PlaybackState.ACTION_SEEK_TO != 0L)
        }
        artworkFor(context, metadata)?.let { (encoded, hash) ->
            inner.put("artwork", encoded)
            inner.put("artwork_hash", hash)
        }

        _state.value = MediaBridgeState(true, title, app)
        RelayManager.broadcastMediaState(context, inner)
    }

    private fun artworkFor(context: Context, metadata: MediaMetadata?): Pair<String, String>? {
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: loadArtworkUri(context, metadata)
        if (bitmap == null) {
            if (!loggedArtworkKeys) {
                Log.d(TAG, "No artwork; metadata keys=${metadata?.keySet()}")
                loggedArtworkKeys = true
            }
            return null
        }

        val max = 512
        val scaled = if (bitmap.width > max || bitmap.height > max) {
            val scale = minOf(max.toFloat() / bitmap.width, max.toFloat() / bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
        var bytes = out.toByteArray()
        if (bytes.size > 128 * 1024) {
            out.reset()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            bytes = out.toByteArray()
        }
        if (bytes.size > 128 * 1024) {
            Log.d(TAG, "Artwork too large after downscale: ${bytes.size} bytes")
            return null
        }

        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .take(16)
        if (hash == lastArtworkHash) {
            return lastArtwork?.let { it to hash }
        }
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        lastArtworkHash = hash
        lastArtwork = encoded
        return encoded to hash
    }

    private fun loadArtworkUri(context: Context, metadata: MediaMetadata?): Bitmap? {
        if (metadata == null) return null
        val uri = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            ?: return null
        if (!uri.startsWith("content://") && !uri.startsWith("file://")) {
            Log.d(TAG, "Skipping non-local artwork URI: $uri")
            return null
        }
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                BitmapFactory.decodeStream(it)
            }
        }.getOrNull()
    }

    fun handleCommand(context: Context, inner: JSONObject, ts: Long) {
        if (!enabled) return
        // Media commands are ephemeral; a mailbox replay minutes later must
        // not pause or skip a track unexpectedly.
        if (System.currentTimeMillis() - ts > 30_000L) return
        val sessionID = inner.optString("session_id")
        val target = controllers[sessionID] ?: controller ?: return
        val controls = target.transportControls ?: return
        when (inner.optString("command")) {
            "play" -> controls.play()
            "pause" -> controls.pause()
            "play_pause" -> {
                if (target.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    controls.pause()
                } else {
                    controls.play()
                }
            }
            "next" -> controls.skipToNext()
            "previous" -> controls.skipToPrevious()
            "stop" -> controls.stop()
            "seek" -> controls.seekTo(inner.optLong("position_ms", 0L))
            "volume" -> {
                val audio = context.getSystemService(AudioManager::class.java) ?: return
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val level = (inner.optDouble("volume", 1.0).coerceIn(0.0, 1.0) * max).toInt()
                runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0) }
            }
        }
    }
}
