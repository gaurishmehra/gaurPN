package dev.gaurish.outlinelite.transfer

/**
 * Kinds of unsolicited inbound traffic from cluster peers. Carried into the
 * gate so rejections can be logged with a single descriptive line; the
 * accept rules themselves are kind-independent.
 *
 * Browse and fetch are deliberately absent: they are a consent capability
 * and go through [InboundPolicy.shouldServeBrowse] instead of the generic
 * gate.
 */
enum class InboundKind {
    CLIPBOARD,
    TEXT,
    NOTIFICATION,
    NOTIFICATION_ACTION,
    OFFER,
    MEDIA,
    REQUEST,
}

/**
 * Central trust gate for passive inbound traffic. A message is dropped when
 * the sender id is blank, spoofs this device, or — while the "only trusted
 * devices" mode is on — is not on the trusted-device list. Requests use the
 * same rules as offers: an untrusted peer is declined, not served. Browse and
 * fetch are not passive: they go through [shouldServeBrowse]. Pure Kotlin so
 * the rules are unit-testable.
 */
object InboundPolicy {

    fun shouldAccept(
        kind: InboundKind,
        senderId: String,
        selfId: String,
        trustedIds: Set<String>,
        onlyTrusted: Boolean,
    ): Boolean {
        if (senderId.isBlank() || senderId == selfId) return false
        if (onlyTrusted && senderId !in trustedIds) return false
        return true
    }

    /**
     * Browse and fetch are a consent capability, not passive receive: the
     * target serves a listing or a file pull only to a device the user
     * explicitly trusted (Settings -> Trusted devices), whatever the "only
     * trusted devices" toggle says. The toggle governs passive kinds
     * (clipboard, offers); browsing needs an explicit grant, so there is no
     * `onlyTrusted` input here on purpose.
     *
     * [trustId] is the claimed origin id (`origin_id ?: requester_id`). The
     * seal proves a cluster member sent the claim; the trust list decides
     * whether to believe it. Per-device keys (v4) will replace the
     * claim-based check.
     */
    fun shouldServeBrowse(trustId: String, selfId: String, trustedIds: Set<String>): Boolean {
        if (trustId.isBlank() || trustId == selfId) return false
        return trustId in trustedIds
    }
}
