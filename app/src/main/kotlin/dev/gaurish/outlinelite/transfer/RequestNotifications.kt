package dev.gaurish.outlinelite.transfer

import android.content.Context

/**
 * Collapses a peer-supplied display name to a single line. Request parsing
 * already trims and bounds the name, but it is peer-controlled text shown in
 * notification titles and log lines, so line breaks, control characters, and
 * Unicode Cf (zero-width and bidi-override) characters are stripped at every
 * display boundary. The wire value is never changed. The Go desktop client
 * implements the same rule.
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
 * User-facing surfaces for pull requests. Screenshot requests are
 * auto-handled and only report the result; browse fetches stream through the
 * existing transfer notifications, and an open BrowseScreen shows declines
 * in place. The one-shot decline notification here is the fallback when no
 * screen is listening.
 *
 * Lock-screen visibility matches [TextShareNotifications]: SECRET, because
 * the requester's name is private to the cluster.
 */
internal object RequestNotifications {

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

    /**
     * The target-side record of an accepted fetch: who asked and how many
     * files, never which files. [requesterLabel] carries the sanitized name
     * plus the stable-id prefix, so a name that imitates a trusted device is
     * exposed.
     */
    internal fun fetchNotificationText(
        requesterName: String,
        stableId: String,
        fileCount: Int,
    ): String = "${requesterLabel(requesterName, stableId)} fetched $fileCount file(s)"

    /** Announces an accepted fetch before its send starts. Fire-and-forget. */
    fun showFetchStarted(
        context: Context,
        requesterName: String,
        stableId: String,
        fileCount: Int,
    ) {
        RelayManager.showOneShotNotification(
            context,
            key = "fetch-${System.currentTimeMillis()}",
            sending = true,
            title = "Files fetched",
            message = fetchNotificationText(requesterName, stableId, fileCount),
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
}
