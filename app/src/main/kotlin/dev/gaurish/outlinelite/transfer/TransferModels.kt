package dev.gaurish.outlinelite.transfer

data class ClusterPeer(
    val id: String,
    val name: String,
    val isSelf: Boolean = false,
    /** Last decrypted battery percentage from the peer's status blob, if any. */
    val battery: Int? = null,
    /** Last decrypted charging flag; null when no status has arrived. */
    val charging: Boolean? = null,
)

sealed interface TransferState {
    data object Idle : TransferState
    data class Sending(
        val peerName: String,
        val fileName: String,
        val progress: Float,
        val speedMbPerSec: Float,
    ) : TransferState
    data class Receiving(
        val peerName: String,
        val fileName: String,
        val progress: Float,
        val speedMbPerSec: Float,
    ) : TransferState
    data class Completed(val message: String) : TransferState
    data class Error(val message: String) : TransferState
}
