package dev.gaurish.outlinelite.transfer

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.gaurish.outlinelite.MainActivity
import dev.gaurish.outlinelite.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * Camera-stream surfaces: the high-priority Accept/Decline prompt and the
 * ongoing streaming notification. The prompt mirrors [ApprovalNotifications]:
 * the actions only send a broadcast (never start an activity), the tap is the
 * user interaction that lets the phone start a camera foreground service on
 * Android 14+, and the window is the request's own 120 s expiry, enforced by
 * the system via setTimeoutAfter. The stream notification carries the Stop
 * action; the service owns its lifecycle (startForeground / stopForeground).
 *
 * The prompt is private on the lock screen: the requester's name is visible
 * only after unlock.
 */
internal object CameraRequestNotifications {

    const val PROMPT_CHANNEL_ID = "camera_requests"
    const val STREAM_CHANNEL_ID = "camera_stream"
    const val ACTION_ACCEPT = "dev.gaurish.outlinelite.CAMERA_ACCEPT"
    const val ACTION_DECLINE = "dev.gaurish.outlinelite.CAMERA_DECLINE"
    const val ACTION_STOP = "dev.gaurish.outlinelite.CAMERA_STOP"
    const val EXTRA_REQUEST_ID = "camera_request_id"

    /**
     * PendingIntent identity ignores extras, so two live prompts that hash to
     * the same request code could collide. A monotonic sequence keeps every
     * pair of actions distinct for the life of the process.
     */
    private val requestCodes = AtomicInteger(0)

    private fun nextRequestCode(): Int = requestCodes.incrementAndGet() and 0x7fffffff

    /** Notification id of the streaming foreground service. */
    const val STREAM_NOTIFICATION_ID = 4042

    /** Posts the Accept/Decline prompt. False when notifications are blocked. */
    fun showPrompt(context: Context, request: RequestEnvelope, notifId: Int): Boolean {
        return runCatching {
            NotificationChannels.ensureChannel(
                context,
                PROMPT_CHANNEL_ID,
                context.getString(R.string.camera_channel_requests),
                NotificationManager.IMPORTANCE_HIGH,
                description = "Accept or decline live camera requests from trusted cluster devices",
            )
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return@runCatching false
            val channel = context.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(PROMPT_CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return@runCatching false
            }

            val label = requesterLabel(request.requesterName, request.trustId)
            val body = context.getString(R.string.camera_prompt_body, label)
            val accept = action(context, request.requestId, ACTION_ACCEPT, nextRequestCode())
            val decline = action(context, request.requestId, ACTION_DECLINE, nextRequestCode())
            val publicVersion = NotificationCompat.Builder(context, PROMPT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_key)
                .setContentTitle(context.getString(R.string.camera_prompt_title))
                .setContentText(context.getString(R.string.camera_prompt_public))
                .build()

            val notification = NotificationCompat.Builder(context, PROMPT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_key)
                .setContentTitle(context.getString(R.string.camera_prompt_title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setLocalOnly(true)
                .setTimeoutAfter(
                    (request.expiresTs - System.currentTimeMillis()).coerceAtLeast(1_000L),
                )
                .addAction(0, context.getString(R.string.camera_action_accept), accept)
                .addAction(0, context.getString(R.string.camera_action_decline), decline)
                .build()
            manager.notify(notifId, notification)
            true
        }.getOrDefault(false)
    }

    fun cancelPrompt(context: Context, notifId: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(notifId) }
    }

    /**
     * The ongoing streaming notification. Built here so both channels live in
     * one place; the service posts it via startForeground with the camera
     * foreground-service type.
     */
    fun buildStreamNotification(context: Context, requesterName: String): Notification {
        NotificationChannels.ensureChannel(
            context,
            STREAM_CHANNEL_ID,
            context.getString(R.string.camera_channel_stream),
            NotificationManager.IMPORTANCE_LOW,
            description = "Shown while a trusted device is viewing this phone's camera",
        )
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getBroadcast(
            context,
            nextRequestCode(),
            Intent(context, CameraActionReceiver::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, STREAM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_key)
            .setContentTitle(context.getString(R.string.camera_stream_title))
            .setContentText(
                context.getString(
                    R.string.camera_stream_body,
                    singleLineDisplayName(requesterName),
                ),
            )
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setLocalOnly(true)
            .addAction(0, context.getString(R.string.camera_action_stop), stop)
            .build()
    }

    private fun action(
        context: Context,
        requestId: String,
        action: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, CameraActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_REQUEST_ID, requestId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
