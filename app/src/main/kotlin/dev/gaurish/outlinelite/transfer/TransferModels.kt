package dev.gaurish.outlinelite.transfer

data class ClusterPeer(
    val id: String,
    val name: String,
    val isSelf: Boolean = false,
)

sealed interface TransferState {
    data object Idle : TransferState
    data class Connecting(val peerName: String) : TransferState
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
