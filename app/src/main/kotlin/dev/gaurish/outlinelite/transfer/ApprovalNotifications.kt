package dev.gaurish.outlinelite.transfer

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.gaurish.outlinelite.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-priority approval prompt with two broadcast actions. The actions only
 * send a sealed response; they never start an activity, so they are not
 * affected by the Android 12 notification-trampoline restriction. The prompt
 * is private on the lock screen: the reason is visible only after unlock.
 */
internal object ApprovalNotifications {

    const val CHANNEL_ID = "approvals"
    const val ACTION_APPROVE = "dev.gaurish.outlinelite.APPROVAL_APPROVE"
    const val ACTION_DENY = "dev.gaurish.outlinelite.APPROVAL_DENY"
    const val EXTRA_REQUEST_ID = "approval_request_id"

    /**
     * PendingIntent identity ignores extras, so two live prompts that hash to
     * the same request code could collide. A monotonic sequence keeps every
     * pair of actions distinct for the life of the process.
     */
    private val requestCodes = AtomicInteger(0)

    private fun nextRequestCode(): Int = requestCodes.incrementAndGet() and 0x7fffffff

    fun show(context: Context, request: ApprovalEnvelope, notifId: Int): Boolean {
        return runCatching {
            NotificationChannels.ensureChannel(
                context,
                CHANNEL_ID,
                "Approval requests",
                NotificationManager.IMPORTANCE_HIGH,
                description = "Approve or deny actions requested by trusted cluster devices",
            )
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return@runCatching false
            val channel = context.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return@runCatching false
            }

            val label = requesterLabel(request.requesterName, request.trustId)
            val safeReason = singleLineDisplayName(request.reason).ifEmpty { "Approval requested" }
            val approve = action(
                context, request.requestId, ACTION_APPROVE, nextRequestCode(),
            )
            val deny = action(
                context, request.requestId, ACTION_DENY, nextRequestCode(),
            )
            val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_key)
                .setContentTitle("Approval requested")
                .setContentText("Unlock to review")
                .build()

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_key)
                .setContentTitle("Approval requested")
                .setContentText(safeReason)
                .setSubText(label)
                .setStyle(NotificationCompat.BigTextStyle().bigText(safeReason))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setLocalOnly(true)
                .setTimeoutAfter(
                    (request.expiresTs - System.currentTimeMillis()).coerceAtLeast(1_000L),
                )
                .addAction(0, "Approve", approve)
                .addAction(0, "Deny", deny)
                .build()
            manager.notify(notifId, notification)
            true
        }.getOrDefault(false)
    }

    fun cancel(context: Context, notifId: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(notifId) }
    }

    private fun action(
        context: Context,
        requestId: String,
        action: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, ApprovalActionReceiver::class.java).apply {
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

/** Receives the Approve/Deny tap and hands the decision to the relay facade. */
class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(ApprovalNotifications.EXTRA_REQUEST_ID) ?: return
        val approved = when (intent.action) {
            ApprovalNotifications.ACTION_APPROVE -> true
            ApprovalNotifications.ACTION_DENY -> false
            else -> return
        }
        RelayManager.resolveApproval(context.applicationContext, requestId, approved)
    }
}
