package dev.gaurish.outlinelite.transfer

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.gaurish.outlinelite.R

private const val CHANNEL_ID = "text_shares"
private const val ACTION_COPY = "dev.gaurish.outlinelite.action.COPY_TEXT"
private const val EXTRA_TEXT = "text"

/**
 * One-shot text/link shares arrive over the relay WebSocket and are surfaced
 * as a notification instead of silently replacing the clipboard. The user
 * chooses Copy or Open, so an incoming link cannot hijack what they are
 * doing.
 */
object TextShareNotifications {

    fun show(context: Context, fromName: String, text: String, kind: String) {
        ensureChannel(context)
        val isUrl = kind == "url"
        val title = if (isUrl) "Link from $fromName" else "Text from $fromName"
        val notificationId = (System.currentTimeMillis() and 0x7fffffff).toInt()

        val copyIntent = Intent(context, TextShareActionReceiver::class.java).apply {
            action = ACTION_COPY
            putExtra(EXTRA_TEXT, text)
        }
        val copyPending = PendingIntent.getBroadcast(
            context,
            notificationId,
            copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_share)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setAutoCancel(true)
            .addAction(0, "Copy", copyPending)

        // Untrusted text only gets an Open action when it passes the shared
        // URL validator; the canonical form is what opens.
        val openTarget = TextShareActions.openTarget(text, kind)
        if (openTarget != null) {
            val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(openTarget))
            val openPending = PendingIntent.getActivity(
                context,
                notificationId + 1,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Open", openPending)
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        }
    }

    private fun ensureChannel(context: Context) {
        NotificationChannels.ensureChannel(
            context,
            CHANNEL_ID,
            "Text and links",
            NotificationManager.IMPORTANCE_HIGH,
            description = "Text and links shared from other cluster devices",
        )
    }
}

class TextShareActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COPY) return
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("gaurPN", text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }
}
