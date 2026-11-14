package dev.gaurish.outlinelite.data

/**
 * A parsed `gaur://pair` link, held for explicit confirmation before
 * anything is written. Pairing rewrites the relay address, cluster token,
 * DNS, and device name, so a mis-scanned or malicious QR must never apply
 * silently.
 */
data class PairingRequest(
    val relay: String,
    val token: String,
    val dns: String,
    val name: String,
)

/**
 * host:port with optional IPv6 brackets. The host part may be a hostname or
 * IPv4 literal (no whitespace, no colon) or a bracketed IPv6 literal.
 */
private val RELAY_PATTERN = Regex("^(?:[^\\s:]+|\\[[0-9A-Fa-f:.]+\\]):\\d{1,5}$")

/**
 * True when the request is safe to present for confirmation: the token is
 * present, and the optional relay and dns fields at least look like a
 * host(:port) and a comma-separated host list with no whitespace.
 */
fun PairingRequest.isValid(): Boolean {
    if (token.isBlank()) return false
    if (relay.isNotBlank() && !RELAY_PATTERN.matches(relay)) return false
    if (dns.isNotBlank()) {
        val segments = dns.split(",")
        if (segments.any { it.isBlank() || it.any { c -> c.isWhitespace() } }) return false
    }
    return true
}

/** Token preview for the confirmation dialog; never reveals the middle of a secret. */
fun PairingRequest.maskedToken(): String =
    if (token.length <= 8) "••••" else token.take(4) + "…" + token.takeLast(4)
