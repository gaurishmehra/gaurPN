package dev.gaurish.outlinelite.transfer

/**
 * Pure decision for the text-share notification's Open action.
 *
 * Incoming text is untrusted input. Only a URL that passes the shared
 * [ClusterCrypto.validateShareUrl] rules gets an Open action, and it opens
 * the canonical form (lowercased scheme/host) rather than the raw string.
 * Everything else keeps Copy but hides Open.
 */
internal object TextShareActions {

    /** Canonical URL to open, or null when the Open action must be hidden. */
    fun openTarget(text: String, kind: String): String? {
        if (kind != "url") return null
        return ClusterCrypto.validateShareUrl(text)
    }
}
