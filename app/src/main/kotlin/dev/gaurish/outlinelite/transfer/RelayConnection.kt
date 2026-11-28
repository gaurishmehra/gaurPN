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
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory

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
        fun onConnecting(context: Context)
        fun onOpen(context: Context, socket: WebSocket, relayToken: String, peerCryptoKey: ByteArray)
        fun onMessage(context: Context, relayToken: String, peerCryptoKey: ByteArray, text: String)
        fun onClosed(context: Context, code: Int)
        fun onFailure(context: Context, httpCode: Int?)
    }

    /**
     * Shared HTTP/WebSocket client; also used by the chunk transport and
     * the camera stream pipe. The socket factory wraps the platform default
     * and sets TCP_NODELAY on every socket, so each sealed envelope or
     * camera frame chunk leaves the phone the moment it is written instead
     * of waiting for Nagle batching; the relay is a loopback hop inside the
     * tunnel, where a 40 ms coalescing delay is pure latency.
     */
    val client = OkHttpClient.Builder()
        .socketFactory(NoDelaySocketFactory())
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
                listener.onConnecting(context)
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
                        listener.onClosed(context, code)
                        scheduleReconnect(context)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.w(TAG, "Relay WS failure: ${t.message}")
                        ws = null
                        connecting.set(false)
                        listener.onFailure(context, response?.code)
                        // A rejected cluster key will be rejected again.
                        // Leave the link in AUTH_FAILED until the key changes
                        // or the user asks for a refresh.
                        if (response?.code != 401 && response?.code != 403) {
                            scheduleReconnect(context)
                        }
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
    fun scheduleReconnect(context: Context) {
        if (TunnelState.flow.value.phase != TunnelPhase.CONNECTED) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            // Re-read the key: the user may have just fixed a rejected
            // cluster key, and a reconnect must not reuse the old one.
            val token = runCatching {
                KeyRepository.getClusterToken(context)
            }.getOrDefault("")
            if (token.isBlank()) return@launch
            // Show "connecting" for the whole backoff, not only when the
            // socket attempt actually starts.
            listener?.onConnecting(context)
            delay(backoff.delayFor(reconnectAttempts++))
            if (TunnelState.flow.value.phase == TunnelPhase.CONNECTED && ws == null) {
                val currentListener = listener ?: return@launch
                val currentToken = runCatching {
                    KeyRepository.getClusterToken(context)
                }.getOrDefault("")
                if (currentToken.isBlank()) return@launch
                connect(context, currentToken, deviceId, currentListener)
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

/**
 * The platform's default socket factory with TCP_NODELAY and bounded socket
 * buffers on every socket it hands out. NODELAY keeps per-frame writes from
 * waiting on Nagle; the 256 KiB buffers are the phone's share of the live
 * latency budget — a default multi-megabyte send buffer silently holds
 * seconds of camera frames when the path is slow, and the viewer watches
 * the past. Bounded here, backpressure reaches the frame queue (and the
 * adaptive bitrate) within a few hundred milliseconds instead. Best-effort
 * per socket: a stack that refuses a buffer size keeps its default rather
 * than failing the connection.
 */
private class NoDelaySocketFactory : SocketFactory() {

    private val delegate: SocketFactory = SocketFactory.getDefault()

    private fun Socket.tuned(): Socket = apply {
        tcpNoDelay = true
        runCatching { sendBufferSize = LIVE_SOCKET_BUFFER_BYTES }
        runCatching { receiveBufferSize = LIVE_SOCKET_BUFFER_BYTES }
    }

    override fun createSocket(): Socket = delegate.createSocket().tuned()

    override fun createSocket(host: String, port: Int): Socket =
        delegate.createSocket(host, port).tuned()

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        delegate.createSocket(host, port, localHost, localPort).tuned()

    override fun createSocket(host: InetAddress, port: Int): Socket =
        delegate.createSocket(host, port).tuned()

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        delegate.createSocket(address, port, localAddress, localPort).tuned()

    private companion object {
        const val LIVE_SOCKET_BUFFER_BYTES = 256 * 1024
    }
}
