package dev.gaurish.outlinelite.transfer

/** A clipboard item after it has been read locally or decrypted from a peer. */
data class ClipboardPayload(
    val kind: String,
    val mime: String,
    val bytes: ByteArray,
)

/** Local clipboard snapshot plus the metadata needed for de-duplication and UI. */
data class ClipboardSnapshot(
    val kind: String,
    val mime: String,
    val bytes: ByteArray,
    val hash: String,
    val preview: String,
    val sensitive: Boolean,
)

enum class ClipboardSyncPhase {
    DISABLED,
    WAITING_FOR_PERMISSION,
    WAITING_FOR_VPN,
    ACTIVE,
    ERROR,
}

data class ClipboardSyncState(
    val phase: ClipboardSyncPhase = ClipboardSyncPhase.DISABLED,
    val lastMessage: String? = null,
    val lastAt: Long = 0L,
)
