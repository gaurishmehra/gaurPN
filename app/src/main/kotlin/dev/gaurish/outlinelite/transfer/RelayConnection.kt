package dev.gaurish.outlinelite.transfer

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.gaurish.outlinelite.data.KeyRepository
import dev.gaurish.outlinelite.tunnel.TunnelPhase
import dev.gaurish.outlinelite.tunnel.TunnelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The OkHttp WebSocket session to the in-tunnel relay: the shared client,
 * the live socket, the single-flight connect guard, and reconnect
 * scheduling. Protocol handling stays in [RelayManager], which installs a
 * [Listener] and drives the connection through [connect], [send], and
 * [close].
 */
internal object RelayConnection {

    private const val TAG = "RelayManager"

    /**
     * Callbacks the facade installs. [context] is the one the connect was
     * started with; [relayToken] and [peerCryptoKey] are the subkeys
     * derived for this session.
     */
    internal interface Listener {
        fun onOpen(context: Context, socket: WebSocket, relayToken: String, peerCryptoKey: ByteArray)
        fun onMessage(context: Context, relayToken: String, peerCryptoKey: ByteArray, text: String)
        fun onClosed(context: Context)
        fun onFailure(context: Context)
    }

    /** Shared HTTP/WebSocket client; also used by the chunk transport. */
    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite for WS and streaming
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var ws: WebSocket? = null

    /** Single-flight guard so two rapid connects cannot open two sockets. */
    private val connecting = AtomicBoolean(false)

    private val backoff = ReconnectBackoff()
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null

    /** The most recently installed listener and device id, reused on reconnects. */
    private var listener: Listener? = null
    private var deviceId: String = ""

    val isConnected: Boolean
        get() = ws != null

    /** Current socket, for call sites that must capture it atomically. */
    fun socketOrNull(): WebSocket? = ws

    /** Sends [text] on the live socket; false when the relay is not connected. */
    fun send(text: String): Boolean = ws?.send(text) ?: false

    fun connect(context: Context, token: String, deviceId: String, listener: Listener) {
        this.listener = listener
        this.deviceId = deviceId
        if (ws != null) return
        // Single-flight: a second call while an attempt is still connecting
        // must not open a second socket that overwrites `ws`.
        if (!connecting.compareAndSet(false, true)) return
        scope.launch {
            try {
                val (relayToken, peerCryptoKey) = Crypto.deriveSubKeys(token)
                val deviceName = KeyRepository.getDeviceName(context)
                val relayHost = KeyRepository.getRelayAddress(context).ifBlank { RelayManager.RELAY_HOST }
                if (!RelayHosts.isInTunnelHost(relayHost)) {
                    Log.w(TAG, "Refusing relay '$relayHost': host is not tunnel-only")
                    return@launch
                }
                val url = "ws://$relayHost/ws?device_id=$deviceId&name=${Uri.encode(deviceName)}"
                val request = Request.Builder()
                    .url(url)
                    .header("X-Gaur-Token", relayToken)
                    .build()

                Log.i(TAG, "Connecting to in-tunnel relay at $relayHost")
                ws = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.i(TAG, "Connected to in-tunnel file relay")
                        reconnectAttempts = 0
                        backoff.reset()
                        connecting.set(false)
                        listener.onOpen(context, webSocket, relayToken, peerCryptoKey)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        listener.onMessage(context, relayToken, peerCryptoKey, text)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.i(TAG, "Relay WS closed: $code $reason")
                        ws = null
                        connecting.set(false)
                        listener.onClosed(context)
                        scheduleReconnect(context, token)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.w(TAG, "Relay WS failure: ${t.message}")
                        ws = null
                        connecting.set(false)
                        listener.onFailure(context)
                        scheduleReconnect(context, token)
                    }
                })
            } finally {
                connecting.set(false)
            }
        }
    }

    /** Drops the socket only if it is still [socket]; a newer one survives. */
    fun clearIfCurrent(socket: WebSocket?) {
        if (ws === socket) ws = null
    }

    /** Reconnects with the installed listener once the backoff delay elapses. */
    fun scheduleReconnect(context: Context, token: String) {
        if (TunnelState.flow.value.phase != TunnelPhase.CONNECTED) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoff.delayFor(reconnectAttempts++))
            if (TunnelState.flow.value.phase == TunnelPhase.CONNECTED && ws == null) {
                val currentListener = listener ?: return@launch
                connect(context, token, deviceId, currentListener)
            }
        }
    }

    /** Closes the socket and stops reconnecting; the facade clears peer state. */
    fun close() {
        reconnectJob?.cancel()
        reconnectJob = null
        ws?.close(1000, "Disconnect")
        ws = null
    }
}
