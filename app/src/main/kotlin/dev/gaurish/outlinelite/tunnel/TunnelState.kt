package dev.gaurish.outlinelite.tunnel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Coarse connection lifecycle used by app UI, the QS tile and the widget. */
enum class TunnelPhase { DISCONNECTED, CONNECTING, RECONNECTING, CONNECTED, STOPPING }

data class TunnelStatus(
    val phase: TunnelPhase = TunnelPhase.DISCONNECTED,
    val activeId: String? = null,
    val activeName: String? = null,
    /** Elapsed realtime (ms) timestamp when the tunnel reached CONNECTED. */
    val connectedAt: Long = 0L,
    val lastError: String? = null,
    val allowLanAccess: Boolean = false,
    /** 1-based retry count while RECONNECTING; 0 when idle or connected. */
    val attempt: Int = 0,
    /** Elapsed realtime (ms) of the next retry, or 0 while connecting now. */
    val retryAt: Long = 0L,
)

/** Single source of truth for connection state, shared across UI, tile and widget. */
object TunnelState {

    private val _flow = MutableStateFlow(TunnelStatus())
    val flow: StateFlow<TunnelStatus> = _flow

    val status: TunnelStatus get() = _flow.value

    fun update(transform: (TunnelStatus) -> TunnelStatus) {
        _flow.value = transform(_flow.value)
    }
}
