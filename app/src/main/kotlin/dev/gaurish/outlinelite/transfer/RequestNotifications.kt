package dev.gaurish.outlinelite.transfer

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.gaurish.outlinelite.R

/**
 * Collapses a peer-supplied display name to a single line. Request parsing
 * already trims and bounds the name, but it is peer-controlled text shown in
 * notification titles, the picker, and log lines, so line breaks, control
 * characters, and Unicode Cf (zero-width and bidi-override) characters are
 * stripped at every display boundary. The wire value is never changed. The Go
 * desktop client implements the same rule.
 */
internal fun singleLineDisplayName(raw: String): String = buildString(raw.length) {
    var index = 0
    while (index < raw.length) {
        val codePoint = raw.codePointAt(index)
        when {
            // Unicode line and paragraph separators render as line breaks.
            codePoint == 0x2028 || codePoint == 0x2029 -> append(' ')
            // C0, DEL, and C1 controls (same ranges as the Go client).
            codePoint in 0x00..0x1F || codePoint in 0x7F..0x9F -> append(' ')
            // Cf: zero-width spaces/joiners, bidi overrides, BOM, and the
            // other invisible format characters a member could use to make
            // its name render as a trusted device.
            Character.getType(codePoint) == Character.FORMAT.toInt() -> Unit
            else -> appendCodePoint(codePoint)
        }
        index += Character.charCount(codePoint)
    }
}.trim()

/**
 * "Lilac (lilac-76ee…)" for notifications and the picker title. The name is
 * a peer-supplied claim; the id prefix is the stable identity the trust list
 * actually contains, so showing both exposes a name that imitates a trusted
 * device.
 */
internal fun requesterLabel(rawName: String, stableId: String): String {
    val name = singleLineDisplayName(rawName).ifEmpty { stableId }
    val id = singleLineDisplayName(stableId)
    if (id.isEmpty()) return name
    return "$name (${id.take(8)}…)"
}

/**
 * User-facing surfaces for pull requests. A file request needs an explicit
 * tap (choosing what to send is a decision, not a background action), so it
 * gets its own high-importance channel and a content action into
 * [RequestPickerActivity]. Screenshot requests are auto-handled and only
 * report the result.
 *
 * Lock-screen visibility matches [TextShareNotifications]: SECRET, because
 * the requester's name is private to the cluster.
 */
internal object RequestNotifications {

    private const val CHANNEL_ID = "file_requests"

    /**
     * Posts the "wants a file" notification. False when it could not be shown.
     *
     * [notifId] is allocated by the caller and owned by the request lease; it
     * is not released here. The lease holds it until the request is answered
     * (picked, declined, or reclaimed after expiry), so a crafted transfer id
     * cannot take the slot while the request is still pending. The PendingIntent
     * request code is the same id: unique while pending, so a second request
     * cannot overwrite the first's extras through FLAG_UPDATE_CURRENT.
     */
    fun showFileRequest(context: Context, request: RequestEnvelope, notifId: Int): Boolean {
        ensureChannel(context)
        val manager = NotificationManagerCompat.from(context)
        // Without notifications there is no picker entry point, so report
        // failure and let the caller decline instead of parking the request.
        if (!manager.areNotificationsEnabled()) return false
        val label = requesterLabel(request.requesterName, request.trustId)
        val intent = Intent(context, RequestPickerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(RequestPickerActivity.EXTRA_REQUEST_ID, request.requestId)
            putExtra(RequestPickerActivity.EXTRA_REQUESTER_ID, request.requesterId)
            putExtra(RequestPickerActivity.EXTRA_REQUESTER_NAME, label)
            putExtra(RequestPickerActivity.EXTRA_GRANT, request.grant)
            putExtra(RequestPickerActivity.EXTRA_EXPIRES_TS, request.expiresTs)
            putExtra(RequestPickerActivity.EXTRA_KIND, request.kind.wire)
        }
        val pending = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_share)
            .setContentTitle("$label requests a file")
            .setContentText("Tap to choose files to send")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .addAction(0, "Choose files", pending)

        return try {
            manager.notify(notifId, builder.build())
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Confirms the automatic screenshot reply. */
    fun showScreenshotSent(context: Context, requesterName: String, stableId: String) {
        RelayManager.showOneShotNotification(
            context,
            key = "screenshot-${System.currentTimeMillis()}",
            sending = false,
            title = "Screenshot sent",
            message = "${requesterLabel(requesterName, stableId)} received your latest screenshot",
        )
    }

    /** Reports a decline to the requester side. */
    fun showDeclined(context: Context, requestId: String, reason: DeclineReason) {
        RelayManager.showOneShotNotification(
            context,
            key = "decline-$requestId",
            sending = false,
            title = "Request declined (${reason.wire})",
            message = "The other device could not send the files",
        )
    }

    private fun ensureChannel(context: Context) {
        NotificationChannels.ensureChannel(
            context,
            CHANNEL_ID,
            "File requests",
            NotificationManager.IMPORTANCE_HIGH,
            description = "Requests from cluster devices for files on this phone",
        )
    }
}
