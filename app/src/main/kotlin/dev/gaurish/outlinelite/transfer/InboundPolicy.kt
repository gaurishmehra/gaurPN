package dev.gaurish.outlinelite.transfer

/**
 * Kinds of unsolicited inbound traffic from cluster peers. Carried into the
 * gate so rejections can be logged with a single descriptive line; the
 * accept rules themselves are kind-independent.
 */
enum class InboundKind { CLIPBOARD, TEXT, NOTIFICATION, NOTIFICATION_ACTION, OFFER, MEDIA }

/**
 * Central trust gate for every unsealed inbound path. A message is dropped
 * when the sender id is blank, spoofs this device, or — while the
 * "only trusted devices" mode is on — is not on the trusted-device list.
 * Pure Kotlin so the rules are unit-testable.
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
}
