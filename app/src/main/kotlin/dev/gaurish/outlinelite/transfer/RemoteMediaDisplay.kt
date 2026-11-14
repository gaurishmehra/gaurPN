package dev.gaurish.outlinelite.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.support.v4.media.session.MediaSessionCompat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import dev.gaurish.outlinelite.R
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private const val REMOTE_MEDIA_CHANNEL = "remote_media"
private const val REMOTE_MEDIA_ACTION = "dev.gaurish.outlinelite.REMOTE_MEDIA_ACTION"
private const val EXTRA_FROM = "from_id"
private const val EXTRA_SESSION = "session_id"
private const val EXTRA_COMMAND = "command"

private class RemoteSession(
    val context: Context,
    val fromId: String,
    val session: MediaSession,
) {
    @Volatile
    var fromName: String = ""

    @Volatile
    var sessionId: String = ""

    @Volatile
    var lastPayload: JSONObject? = null
}

/**
 * A desktop MPRIS player is exposed to Android as a real local MediaSession,
 * not just a notification. That puts it in the system media controls, lock
 * screen, quick settings, and Bluetooth, with working transport actions.
 */
object RemoteMediaDisplay {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessions = ConcurrentHashMap<String, RemoteSession>()

    fun show(context: Context, fromId: String, fromName: String, payload: JSONObject) {
        val appContext = context.applicationContext
        val copy = JSONObject(payload.toString())
        mainHandler.post { showOnMain(appContext, fromId, fromName, copy) }
    }

    fun cancel(context: Context, fromId: String) {
        val appContext = context.applicationContext
        mainHandler.post {
            sessions.remove(fromId)?.let { remote ->
                remote.session.isActive = false
                remote.session.release()
            }
            NotificationManagerCompat.from(appContext).cancel(notificationId(fromId))
        }
    }

    /**
     * Updates the local session immediately so the phone's controls do not
     * wait for the command round trip. The next desktop state reconciles it.
     */
    fun optimistic(context: Context, fromId: String, playing: Boolean) {
        val appContext = context.applicationContext
        mainHandler.post {
            val remote = sessions[fromId] ?: return@post
            val payload = remote.lastPayload?.let { JSONObject(it.toString()) } ?: return@post
            payload.put("playing", playing)
            showOnMain(appContext, fromId, remote.fromName, payload)
        }
    }

    private fun showOnMain(context: Context, fromId: String, fromName: String, payload: JSONObject) {
        val title = payload.optString("title")
        if (title.isBlank()) {
            cancel(context, fromId)
            return
        }
        ensureChannel(context)

        val artist = payload.optString("artist")
        val album = payload.optString("album")
        val app = payload.optString("app").ifBlank { fromName }
        val sessionId = payload.optString("session_id")
        val playing = payload.optBoolean("playing")
        val duration = payload.optLong("duration_ms", 0L)
        val position = payload.optLong("position_ms", 0L)
        val rate = payload.optDouble("rate", 1.0).toFloat().coerceAtLeast(0.1f)
        val artwork = decodeArtwork(payload.optString("artwork"))

        val remote = sessions.getOrPut(fromId) { createSession(context, fromId) }
        remote.fromName = fromName
        remote.sessionId = sessionId
        remote.lastPayload = JSONObject(payload.toString())

        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
            .apply {
                if (artwork != null) {
                    putBitmap(MediaMetadata.METADATA_KEY_ART, artwork)
                }
            }
            .build()
        remote.session.setMetadata(metadata)

        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SEEK_TO
        remote.session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    position,
                    rate,
                )
                .build()
        )

        val id = notificationId(fromId)
        val builder = NotificationCompat.Builder(context, REMOTE_MEDIA_CHANNEL)
            .setSmallIcon(R.drawable.ic_share)
            .setContentTitle(title)
            .setContentText(if (artist.isNotBlank()) artist else app)
            .setSubText("$app · $fromName")
            .setLargeIcon(artwork)
            .setStyle(
                MediaStyle()
                    .setMediaSession(
                        MediaSessionCompat.Token.fromToken(remote.session.sessionToken)
                    )
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setAutoCancel(false)
            .setShowWhen(false)

        builder.addAction(
            action(context, id, fromId, sessionId, "previous", "Previous")
        )
        builder.addAction(
            action(
                context,
                id,
                fromId,
                sessionId,
                if (playing) "pause" else "play",
                if (playing) "Pause" else "Play",
            )
        )
        builder.addAction(
            action(context, id, fromId, sessionId, "next", "Next")
        )

        runCatching {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        }
    }

    private fun createSession(context: Context, fromId: String): RemoteSession {
        val session = MediaSession(context, "gaurpn-remote-$fromId")
        session.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        val mediaButtonIntent = Intent(context, RemoteMediaButtonReceiver::class.java)
        val mediaButtonPending = PendingIntent.getBroadcast(
            context,
            0,
            mediaButtonIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        session.setMediaButtonReceiver(mediaButtonPending)

        val remote = RemoteSession(context, fromId, session)
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                optimistic(remote.context, remote.fromId, true)
                send(remote, "play")
            }

            override fun onPause() {
                optimistic(remote.context, remote.fromId, false)
                send(remote, "pause")
            }

            override fun onStop() {
                optimistic(remote.context, remote.fromId, false)
                send(remote, "stop")
            }

            override fun onSkipToNext() = send(remote, "next")
            override fun onSkipToPrevious() = send(remote, "previous")
            override fun onSeekTo(pos: Long) = send(remote, "seek", pos)

            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    as? KeyEvent
                    ?: return super.onMediaButtonEvent(mediaButtonIntent)
                if (event.action != KeyEvent.ACTION_DOWN) return true
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        optimistic(remote.context, remote.fromId, true)
                        send(remote, "play")
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        optimistic(remote.context, remote.fromId, false)
                        send(remote, "pause")
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        val playing = remote.lastPayload?.optBoolean("playing") ?: false
                        val next = !playing
                        optimistic(remote.context, remote.fromId, next)
                        send(remote, if (next) "play" else "pause")
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> send(remote, "next")
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> send(remote, "previous")
                    KeyEvent.KEYCODE_MEDIA_STOP -> {
                        optimistic(remote.context, remote.fromId, false)
                        send(remote, "stop")
                    }
                    else -> return super.onMediaButtonEvent(mediaButtonIntent)
                }
                return true
            }
        })
        session.isActive = true
        return remote
    }

    private fun send(remote: RemoteSession, command: String, positionMs: Long? = null) {
        Log.d("RemoteMediaDisplay", "command $command -> ${remote.fromId}/${remote.sessionId}")
        RelayManager.sendMediaCommand(
            remote.context,
            remote.fromId,
            remote.sessionId,
            command,
            positionMs,
        )
    }

    private fun decodeArtwork(encoded: String): Bitmap? {
        if (encoded.isBlank()) return null
        val bytes = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
            ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun notificationId(fromId: String): Int =
        ("remote-media:$fromId").hashCode()

    private fun action(
        context: Context,
        notificationId: Int,
        fromId: String,
        sessionId: String,
        command: String,
        label: String,
    ): NotificationCompat.Action {
        val intent = Intent(context, RemoteMediaActionReceiver::class.java).apply {
            action = REMOTE_MEDIA_ACTION
            putExtra(EXTRA_FROM, fromId)
            putExtra(EXTRA_SESSION, sessionId)
            putExtra(EXTRA_COMMAND, command)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            ("$notificationId:$command").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(0, label, pending).build()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(REMOTE_MEDIA_CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                REMOTE_MEDIA_CHANNEL,
                "Desktop media",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Now playing from another cluster device"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }
}

class RemoteMediaActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != REMOTE_MEDIA_ACTION) return
        val fromId = intent.getStringExtra(EXTRA_FROM) ?: return
        val sessionId = intent.getStringExtra(EXTRA_SESSION) ?: ""
        val command = intent.getStringExtra(EXTRA_COMMAND) ?: return
        if (command == "play" || command == "pause") {
            RemoteMediaDisplay.optimistic(context, fromId, command == "play")
        }
        RelayManager.sendMediaCommand(
            context.applicationContext,
            fromId,
            sessionId,
            command,
        )
    }
}

/**
 * The active MediaSession callback receives media buttons. This receiver
 * exists so the system can route them to this app when the session is not
 * currently the media-button session.
 */
class RemoteMediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // No-op; the MediaSession callback handles the key event.
    }
}
