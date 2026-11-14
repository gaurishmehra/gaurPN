package dev.gaurish.outlinelite.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import dev.gaurish.outlinelite.MainActivity
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.data.KeyRepository
import dev.gaurish.outlinelite.data.TransferHistoryEntry
import dev.gaurish.outlinelite.data.TransferHistoryRepository
import dev.gaurish.outlinelite.data.TransferSettingsRepository
import dev.gaurish.outlinelite.data.TrustedDeviceRepository
import dev.gaurish.outlinelite.tunnel.TunnelPhase
import dev.gaurish.outlinelite.tunnel.TunnelState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object RelayManager {

    private const val TAG = "RelayManager"
    private const val RELAY_HOST = "198.51.100.1:9876"
    private const val CHANNEL_ID = "file_transfers"
    private const val NOTIF_GROUP = "gaur_transfers"

    /** 256 KiB chunks cut framing, syscall, and cipher-init overhead by 4x. */
    private const val CHUNK_SIZE = 256 * 1024
    private const val MAX_CHUNK_SIZE = 1024 * 1024
    private const val FLUSH_INTERVAL_MS = 100L
    private const val MAX_TEXT_CHARS = 64 * 1024
    private const val MAX_TEXT_BYTES = 64 * 1024
    private val URL_REGEX = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    @Volatile
    private var ws: WebSocket? = null

    @Volatile
    private var deviceId: String = ""

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite for WS and streaming
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _peers = MutableStateFlow<List<ClusterPeer>>(emptyList())
    val peers: StateFlow<List<ClusterPeer>> = _peers.asStateFlow()

    private val _transferState = MutableStateFlow<TransferState>(TransferState.Idle)
    val transferState: StateFlow<TransferState> = _transferState.asStateFlow()

    private val pendingAccepts = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val pendingCompletes = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val activeTransfers = ConcurrentHashMap<String, Job>()
    private var sendJob: Job? = null

    fun start(context: Context) {
        if (monitorJob != null) return

        val appCtx = context.applicationContext

        monitorJob = scope.launch {
            deviceId = KeyRepository.getOrCreateDeviceId(appCtx)
            Log.i(TAG, "Relay identity: $deviceId")
            TunnelState.flow.collect { status ->
                if (status.phase == TunnelPhase.CONNECTED) {
                    val token = KeyRepository.getClusterToken(appCtx)
                    if (token.isNotBlank()) {
                        connectRelay(appCtx, token)
                    } else {
                        disconnectRelay()
                    }
                } else {
                    disconnectRelay()
                }
            }
        }
    }

    private fun connectRelay(context: Context, token: String) {
        if (ws != null) return
        scope.launch {
            val (relayToken, peerCryptoKey) = Crypto.deriveSubKeys(token)
            val deviceName = KeyRepository.getDeviceName(context)
            val relayHost = KeyRepository.getRelayAddress(context).ifBlank { RELAY_HOST }
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
                    requestPeerList(webSocket)
                    ClipboardSyncManager.onRelayConnected(context)
                    MediaSessionBridge.onRelayConnected(context)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleWsMessage(context, relayToken, peerCryptoKey, text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "Relay WS closed: $code $reason")
                    ws = null
                    _peers.value = emptyList()
                    ClipboardSyncManager.onRelayDisconnected()
                    scheduleReconnect(context, token)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "Relay WS failure: ${t.message}")
                    ws = null
                    _peers.value = emptyList()
                    ClipboardSyncManager.onRelayDisconnected()
                    scheduleReconnect(context, token)
                }
            })
        }
    }

    private var reconnectJob: Job? = null

    private fun scheduleReconnect(context: Context, token: String) {
        if (TunnelState.flow.value.phase != TunnelPhase.CONNECTED) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(3000)
            if (TunnelState.flow.value.phase == TunnelPhase.CONNECTED && ws == null) {
                connectRelay(context, token)
            }
        }
    }

    fun refreshDevices(context: Context) {
        val appCtx = context.applicationContext
        scope.launch {
            if (TunnelState.flow.value.phase != TunnelPhase.CONNECTED) return@launch

            val token = KeyRepository.getClusterToken(appCtx)
            if (token.isBlank()) return@launch

            val currentSocket = ws
            if (currentSocket != null && currentSocket.send(peerListRequest())) return@launch

            Log.i(TAG, "Relay unavailable during device refresh; reconnecting")
            currentSocket?.close(1012, "Refresh device list")
            if (ws === currentSocket) ws = null
            scheduleReconnect(appCtx, token)
        }
    }

    private fun requestPeerList(webSocket: WebSocket) {
        if (!webSocket.send(peerListRequest())) {
            Log.w(TAG, "Could not request cluster device list")
        }
    }

    private fun peerListRequest(): String = JSONObject().put("type", "list_peers").toString()

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        disconnectRelay()
    }

    private fun disconnectRelay() {
        reconnectJob?.cancel()
        reconnectJob = null
        ws?.close(1000, "Disconnect")
        ws = null
        _peers.value = emptyList()
        ClipboardSyncManager.onRelayDisconnected()
    }

    private fun handleWsMessage(context: Context, relayToken: String, peerCryptoKey: ByteArray, text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (json.optString("type")) {
            "peers" -> {
                val peersArray = json.optJSONArray("peers") ?: return
                val list = mutableListOf<ClusterPeer>()
                for (i in 0 until peersArray.length()) {
                    val p = peersArray.getJSONObject(i)
                    val id = p.getString("id")
                    val name = p.getString("name")
                    list.add(ClusterPeer(id = id, name = name, isSelf = (id == deviceId)))
                }
                _peers.value = list
            }
            "transfer_offer" -> {
                val txId = json.optString("transfer_id")
                if (txId.isNotBlank()) {
                    activeTransfers.remove(txId)?.cancel()
                    val holder = arrayOfNulls<Job>(1)
                    val job = scope.launch {
                        try {
                            if (json.optInt("v", 1) >= 2) {
                                handleIncomingOfferV2(context, relayToken, peerCryptoKey, json)
                            } else {
                                handleIncomingOfferV1(context, relayToken, peerCryptoKey, json)
                            }
                        } finally {
                            holder[0]?.let { activeTransfers.remove(txId, it) }
                        }
                    }
                    holder[0] = job
                    activeTransfers[txId] = job
                }
            }
            "transfer_accept", "transfer_reject" -> {
                val txId = json.optString("transfer_id")
                pendingAccepts.remove(txId)?.complete(json)
            }
            "transfer_complete", "transfer_error" -> {
                val txId = json.optString("transfer_id")
                pendingCompletes.remove(txId)?.complete(json)
            }
            "clipboard_update" -> {
                scope.launch { handleClipboardUpdate(context, json) }
            }
            "text_message" -> {
                scope.launch { handleTextMessage(context, json) }
            }
            "notification_event" -> {
                scope.launch { handleNotificationEvent(context, json) }
            }
            "notification_action" -> {
                scope.launch { handleNotificationAction(context, json) }
            }
            "sealed" -> {
                scope.launch { handleSealed(context, json) }
            }
        }
    }

    /**
     * Encrypts one clipboard snapshot and sends it to every other cluster
     * device. The relay only sees ciphertext plus routing metadata.
     */
    suspend fun broadcastClipboard(context: Context, snapshot: ClipboardSnapshot): Boolean {
        val socket = ws ?: return false
        val excluded = runCatching {
            KeyRepository.clipboardExcludedPeers(context)
        }.getOrDefault(emptySet())
        val peers = _peers.value.filter { !it.isSelf && it.id !in excluded }
        if (peers.isEmpty()) return false

        val token = KeyRepository.getClusterToken(context)
        if (token.isBlank()) return false
        val (_, peerCryptoKey) = Crypto.deriveSubKeys(token)
        val clipboardKey = Crypto.deriveClipboardKey(peerCryptoKey)

        val msgId = "clip-${UUID.randomUUID()}"
        val ts = System.currentTimeMillis()
        val nonce = Crypto.randomBytes(12)
        val aad = "clipboard:$deviceId:$msgId:$ts:${snapshot.kind}:${snapshot.mime}"
            .toByteArray(Charsets.UTF_8)
        val ciphertext = Crypto.encryptGcm(clipboardKey, nonce, snapshot.bytes, aad)

        val base = JSONObject().apply {
            put("type", "clipboard_update")
            put("msg_id", msgId)
            put("ts", ts)
            put("kind", snapshot.kind)
            put("mime", snapshot.mime)
            put("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
            put("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }

        var sent = false
        peers.forEach { peer ->
            val message = JSONObject(base.toString()).put("target_id", peer.id)
            if (socket.send(message.toString())) sent = true
        }
        return sent
    }

    private suspend fun handleClipboardUpdate(context: Context, json: JSONObject) {
        val fromId = json.optString("from_id")
        if (!acceptsInbound(context, InboundKind.CLIPBOARD, fromId)) return

        val msgId = json.optString("msg_id")
        val ts = json.optLong("ts", 0L)
        val relayTs = json.optLong("relay_ts", ts)
        val kind = json.optString("kind")
        val mime = json.optString("mime")
        val nonceB64 = json.optString("nonce")
        val dataB64 = json.optString("data")
        if (msgId.isBlank() || ts <= 0L || kind !in setOf("text", "image") ||
            nonceB64.isBlank() || dataB64.isBlank()
        ) {
            return
        }
        if (relayTs <= ClipboardSyncManager.lastAppliedTs) return

        val token = KeyRepository.getClusterToken(context)
        if (token.isBlank()) return
        val (_, peerCryptoKey) = Crypto.deriveSubKeys(token)
        val clipboardKey = Crypto.deriveClipboardKey(peerCryptoKey)

        val nonce = Base64.decode(nonceB64, Base64.NO_WRAP)
        val ciphertext = Base64.decode(dataB64, Base64.NO_WRAP)
        val aad = "clipboard:$fromId:$msgId:$ts:$kind:$mime".toByteArray(Charsets.UTF_8)
        val plaintext = runCatching {
            Crypto.decryptGcm(clipboardKey, nonce, ciphertext, aad)
        }.getOrNull() ?: run {
            Log.w(TAG, "Clipboard update failed authentication from $fromId")
            return
        }

        ClipboardSyncManager.applyRemote(
            context,
            ClipboardPayload(kind = kind, mime = mime, bytes = plaintext),
            relayTs,
        )
    }

    /**
     * Sends a one-shot text or link to a peer. The payload uses a key
     * domain-separated from file transfers and clipboard sync; the relay only
     * sees ciphertext plus routing metadata.
     */
    suspend fun sendText(context: Context, target: ClusterPeer, text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_TEXT_CHARS) return false
        val plaintext = trimmed.toByteArray(Charsets.UTF_8)
        if (plaintext.size > MAX_TEXT_BYTES) return false
        val socket = ws ?: return false
        val token = KeyRepository.getClusterToken(context)
        if (token.isBlank()) return false

        val (_, peerCryptoKey) = Crypto.deriveSubKeys(token)
        val textKey = Crypto.deriveTextKey(peerCryptoKey)
        val kind = if (URL_REGEX.matches(trimmed)) "url" else "text"
        val msgId = "text-${UUID.randomUUID()}"
        val ts = System.currentTimeMillis()
        val nonce = Crypto.randomBytes(12)
        val aad = "text:$deviceId:$msgId:$ts:$kind".toByteArray(Charsets.UTF_8)
        val ciphertext = Crypto.encryptGcm(textKey, nonce, plaintext, aad)

        val message = JSONObject().apply {
            put("type", "text_message")
            put("v", 1)
            put("target_id", target.id)
            put("sender_id", deviceId)
            put("msg_id", msgId)
            put("ts", ts)
            put("kind", kind)
            put("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
            put("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
        runCatching {
            TransferHistoryRepository.add(
                context,
                TransferHistoryEntry(
                    id = msgId,
                    kind = kind,
                    direction = "sent",
                    peerId = target.id,
                    peerName = target.name,
                    label = if (kind == "url") trimmed else trimmed.take(120),
                    size = plaintext.size.toLong(),
                    status = "active",
                    timestamp = ts,
                    text = trimmed,
                ),
            )
        }
        val sent = socket.send(message.toString())
        runCatching {
            TransferHistoryRepository.updateStatus(
                context,
                msgId,
                if (sent) "completed" else "failed",
            )
        }
        return sent
    }

    private suspend fun handleTextMessage(context: Context, json: JSONObject) {
        if (json.optInt("v", 1) > 1) return
        val fromId = json.optString("from_id")
        if (fromId.isBlank() || fromId == deviceId) return
        val senderDeviceId = json.optString("sender_id", fromId)
        if (!acceptsInbound(context, InboundKind.TEXT, senderDeviceId)) return
        val fromName = json.optString("from_name", "Remote device")
        val msgId = json.optString("msg_id")
        val ts = json.optLong("ts", 0L)
        val kind = json.optString("kind")
        val nonceB64 = json.optString("nonce")
        val dataB64 = json.optString("data")
        if (msgId.isBlank() || ts <= 0L || kind !in setOf("text", "url") ||
            nonceB64.isBlank() || dataB64.isBlank()
        ) {
            return
        }

        val token = KeyRepository.getClusterToken(context)
        if (token.isBlank()) return
        val (_, peerCryptoKey) = Crypto.deriveSubKeys(token)
        val textKey = Crypto.deriveTextKey(peerCryptoKey)
        val nonce = Base64.decode(nonceB64, Base64.NO_WRAP)
        val ciphertext = Base64.decode(dataB64, Base64.NO_WRAP)
        val aad = "text:$fromId:$msgId:$ts:$kind".toByteArray(Charsets.UTF_8)
        val plaintext = runCatching {
            Crypto.decryptGcm(textKey, nonce, ciphertext, aad)
        }.getOrNull() ?: run {
            Log.w(TAG, "Text message failed authentication from $fromId")
            return
        }
        if (plaintext.size > MAX_TEXT_BYTES) return

        val text = String(plaintext, Charsets.UTF_8)
        runCatching {
            TransferHistoryRepository.add(
                context,
                TransferHistoryEntry(
                    id = msgId,
                    kind = kind,
                    direction = "received",
                    peerId = senderDeviceId,
                    peerName = fromName,
                    label = if (kind == "url") text else text.take(120),
                    size = plaintext.size.toLong(),
                    status = "completed",
                    timestamp = System.currentTimeMillis(),
                    text = text,
                ),
            )
        }
        TextShareNotifications.show(
            context,
            fromName = fromName,
            text = text,
            kind = kind,
        )
    }

    /**
     * Sealed notification event. The payload (app name, title, text, icon,
     * actions) is encrypted with K_notification; the relay sees only the
     * event kind and routing metadata.
     */
    suspend fun broadcastNotification(context: Context, event: String, payload: JSONObject): Boolean {
        if (ws == null) return false
        val peers = _peers.value.filter { !it.isSelf }
        if (peers.isEmpty()) return false
        val token = KeyRepository.getClusterToken(context)
        if (token.isBlank()) return false
        val senderName = KeyRepository.getDeviceName(context)
        val inner = JSONObject(payload.toString()).apply {
            put("sender_id", deviceId)
            put("sender_name", senderName)
            put("event", event)
        }
        var sent = false
        peers.forEach { peer ->
            if (sendSealed(context, peer.id, "notification", inner)) sent = true
        }
        return sent
    }

    private suspend fun handleNotificationEvent(context: Context, json: JSONObject) {
        val fromId = json.optString("from_id")
        if (!acceptsInbound(context, InboundKind.NOTIFICATION, fromId)) return
        val fromName = json.optString("from_name", "Remote device")
        val msgId = json.optString("msg_id")
        val ts = json.optLong("ts", 0L)
        val event = json.optString("event")
        val nonceB64 = json.optString("nonce")
        val dataB64 = json.optString("data")
        if (msgId.isBlank() || ts <= 0L || event !in setOf("posted", "removed") ||
            nonceB64.isBlank() || dataB64.isBlank()
        ) {
            return
        }
        val token = KeyRepository.getClusterToken(context)
        if (token.isBlank()) return
        val (_, peerCryptoKey) = Crypto.deriveSubKeys(token)
        val key = Crypto.deriveNotificationKey(peerCryptoKey)
        val nonce = Base64.decode(nonceB64, Base64.NO_WRAP)
        val ciphertext = Base64.decode(dataB64, Base64.NO_WRAP)
        val aad = "notification:$fromId:$msgId:$ts:$event".toByteArray(Charsets.UTF_8)
        val plaintext = runCatching {
            Crypto.decryptGcm(key, nonce, ciphertext, aad)
        }.getOrNull() ?: run {
            Log.w(TAG, "Notification event failed authentication from $fromId")
            return
        }
        if (plaintext.size > 256 * 1024) return
        val payload = runCatching {
            JSONObject(String(plaintext, Charsets.UTF_8))
        }.getOrNull() ?: return

        if (event == "posted") {
            NotificationDisplay.show(context, fromId, fromName, payload)
        } else {
            NotificationDisplay.cancel(context, fromId, payload.optString("key"))
        }
    }

    private suspend fun handleNotificationAction(context: Context, json: JSONObject) {
        val fromId = json.optString("from_id")
        if (!acceptsInbound(context, InboundKind.NOTIFICATION_ACTION, fromId)) return
        val msgId = json.optString("msg_id")
        val ts = json.optLong("ts", 0L)
        val nonceB64 = json.optString("nonce")
        val dataB64 = json.optString("data")
        if (msgId.isBlank() || ts <= 0L || nonceB64.isBlank() || dataB64.isBlank()) return
        val token = KeyRepository.getClusterToken(context)
        if (token.isBlank()) return
        val (_, peerCryptoKey) = Crypto.deriveSubKeys(token)
        val key = Crypto.deriveNotificationKey(peerCryptoKey)
        val nonce = Base64.decode(nonceB64, Base64.NO_WRAP)
        val ciphertext = Base64.decode(dataB64, Base64.NO_WRAP)
        val aad = "notification_action:$fromId:$msgId:$ts".toByteArray(Charsets.UTF_8)
        val plaintext = runCatching {
            Crypto.decryptGcm(key, nonce, ciphertext, aad)
        }.getOrNull() ?: run {
            Log.w(TAG, "Notification action failed authentication from $fromId")
            return
        }
        val payload = runCatching {
            JSONObject(String(plaintext, Charsets.UTF_8))
        }.getOrNull() ?: return
        NotificationMirrorManager.executeAction(
            context,
            payload.optString("notif_key"),
            payload.optInt("action_index", -1),
            payload.optString("reply").ifBlank { null },
        )
    }

    suspend fun sendNotificationAction(
        context: Context,
        targetId: String,
        notifKey: String,
        actionIndex: Int,
        reply: String?,
    ): Boolean {
        if (ws == null) return false
        val inner = JSONObject().apply {
            put("sender_id", deviceId)
            put("sender_name", KeyRepository.getDeviceName(context))
            put("notif_key", notifKey)
            put("action_index", actionIndex)
            if (reply != null) put("reply", reply)
        }
        return sendSealed(context, targetId, "notification_action", inner)
    }

    /** Publishes the current media session to every peer. */
    fun broadcastMediaState(context: Context, inner: JSONObject) {
        scope.launch {
            val peers = _peers.value.filter { !it.isSelf }
            if (peers.isEmpty()) return@launch
            val payload = JSONObject(inner.toString()).apply {
                put("sender_id", deviceId)
                put("sender_name", KeyRepository.getDeviceName(context))
            }
            peers.forEach { peer ->
                sendSealed(context, peer.id, "media_state", payload)
            }
        }
    }

    /** Sends a transport command to a desktop's local MPRIS player. */
    fun sendMediaCommand(
        context: Context,
        targetId: String,
        sessionId: String,
        command: String,
        positionMs: Long? = null,
    ) {
        scope.launch {
            val inner = JSONObject().apply {
                put("sender_id", deviceId)
                put("sender_name", KeyRepository.getDeviceName(context))
                put("session_id", sessionId)
                put("command", command)
                if (positionMs != null) put("position_ms", positionMs)
            }
            sendSealed(context, targetId, "media_command", inner)
        }
    }

    /** Tells the sender whether the mirrored notification was actually shown. */
    fun sendNotificationAck(
        context: Context,
        targetId: String,
        notifKey: String,
        status: String,
    ) {
        scope.launch {
            val inner = JSONObject().apply {
                put("sender_id", deviceId)
                put("sender_name", KeyRepository.getDeviceName(context))
                put("notif_key", notifKey)
                put("status", status)
            }
            sendSealed(context, targetId, "notification_ack", inner)
        }
    }

    /**
     * v3: upload each file once with a shared content key, then send a sealed
     * offer to every target. Offline targets are served by the relay mailbox
     * when they reconnect. The relay never sees the sender, file name, or key.
     */
    fun sendFilesV3(
        context: Context,
        targets: List<ClusterPeer>,
        uris: List<Uri>,
        offline: Boolean = false,
    ) {
        if (targets.isEmpty() || uris.isEmpty()) return
        val appCtx = context.applicationContext
        if (ws == null) {
            _transferState.value = TransferState.Error("File sharing not connected")
            return
        }
        sendJob?.cancel()
        sendJob = scope.launch {
            val token = runCatching { KeyRepository.getClusterToken(appCtx) }.getOrDefault("")
            if (token.isBlank()) {
                _transferState.value = TransferState.Error("Cluster token not configured")
                return@launch
            }
            val (relayToken, peerCryptoKey) = Crypto.deriveSubKeys(token)
            val relayHost = KeyRepository.getRelayAddress(appCtx).ifBlank { RELAY_HOST }
            val files = uris.map { uri ->
                val (name, size) = queryFileInfo(appCtx.contentResolver, uri)
                OutgoingFile(uri, name, size)
            }
            val batchId = "batch-${UUID.randomUUID()}"
            val totalSize = files.sumOf { it.size }
            val batchNotifId = notifIdFor(batchId, sending = true)
            try {
                var sentBefore = 0L
                files.forEachIndexed { index, file ->
                    val label = if (files.size > 1) {
                        "${files.size} files (${index + 1}/${files.size}): ${file.name}"
                    } else {
                        file.name
                    }
                    sendOneV3(
                        context = appCtx,
                        targets = targets,
                        relayToken = relayToken,
                        peerCryptoKey = peerCryptoKey,
                        relayHost = relayHost,
                        file = file,
                        batchId = batchId,
                        batchIndex = index,
                        batchTotal = files.size,
                        label = label,
                        sentBefore = sentBefore,
                        totalSize = totalSize,
                        offline = offline,
                    )
                    sentBefore += file.size
                }
                val message = if (offline) {
                    "Queued ${files.size} file(s) for ${targets.size} device(s)"
                } else {
                    "Sent ${files.size} file(s) to ${targets.size} device(s)"
                }
                _transferState.value = TransferState.Completed(message)
                showNotification(appCtx, batchNotifId, "Transfer Complete", message)
            } catch (t: CancellationException) {
                _transferState.value = TransferState.Idle
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "v3 send failed: ${t.message}", t)
                _transferState.value = TransferState.Error("Transfer error: ${t.message}")
                showNotification(
                    appCtx,
                    batchNotifId,
                    "Transfer Failed",
                    "Failed sending to ${targets.size} device(s)",
                )
            }
        }
    }

    private suspend fun sendOneV3(
        context: Context,
        targets: List<ClusterPeer>,
        relayToken: String,
        peerCryptoKey: ByteArray,
        relayHost: String,
        file: OutgoingFile,
        batchId: String,
        batchIndex: Int,
        batchTotal: Int,
        label: String,
        sentBefore: Long,
        totalSize: Long,
        offline: Boolean,
    ) {
        val notifId = notifIdFor(batchId, sending = true)
        _transferState.value = TransferState.Sending(targets.first().name, label, 0f, 0f)
        showNotification(context, notifId, "Preparing $label", "Hashing file...", ongoing = true, progress = -2)

        val digest = withContext(Dispatchers.IO) { ChunkTransport.hashUri(context, file.uri) }
        cancelNotification(context, notifId)
        if (file.size > 0 && digest.size != file.size) {
            throw IOException("File changed while hashing (expected ${file.size}, read ${digest.size})")
        }

        val transferId = Crypto.deriveTransferId(peerCryptoKey, digest.fileHash)
        val sessionId = UUID.randomUUID().toString().replace("-", "")
        val fileKey = Crypto.deriveFileKey(peerCryptoKey, transferId)
        val missing = (0 until digest.chunkCount).toList()
        val startTime = System.currentTimeMillis()
        val lastProgress = AtomicLong(0)

        ChunkTransport.uploadMissing(
            context = context,
            client = client,
            relayHost = relayHost,
            relayToken = relayToken,
            deviceId = deviceId,
            peerKey = peerCryptoKey,
            transferId = transferId,
            sessionId = sessionId,
            digest = digest,
            aesKey = fileKey,
            missing = missing,
            uri = file.uri,
        ) { done, total ->
            val now = System.currentTimeMillis()
            if (now - lastProgress.get() < 200L) return@uploadMissing
            lastProgress.set(now)
            val fileProgress = if (total > 0) done.toFloat() / total else 0f
            val overall = if (totalSize > 0) {
                (sentBefore + fileProgress * file.size).toFloat() / totalSize
            } else {
                fileProgress
            }
            val elapsed = (now - startTime) / 1000f
            val speedMb = if (elapsed > 0) (done / (1024f * 1024f)) / elapsed else 0f
            _transferState.value = TransferState.Sending(targets.first().name, label, overall, speedMb)
            showNotification(
                context,
                notifId,
                "Sending $label",
                "%.1f MB/s - %.0f%%".format(speedMb, overall * 100f),
                ongoing = true,
                progress = (overall * 100).toInt(),
            )
        }

        val senderName = KeyRepository.getDeviceName(context)
        targets.forEach { target ->
            val inner = JSONObject().apply {
                put("sender_id", deviceId)
                put("sender_name", senderName)
                put("transfer_id", transferId)
                put("session_id", sessionId)
                put("file_name", file.name)
                put("file_size", digest.size)
                put("chunk_size", ChunkTransport.CHUNK_SIZE)
                put("chunk_count", digest.chunkCount)
                put("file_hash", digest.fileHash)
                put("chunk_hashes", JSONArray(digest.chunkHashes))
                put("batch_id", batchId)
                put("batch_index", batchIndex)
                put("batch_total", batchTotal)
                put("open", false)
                put("offline", offline)
            }
            sendSealed(context, target.id, "offer", inner)
        }
        showNotification(
            context,
            notifId,
            "Queued $label",
            "Uploaded once for ${targets.size} device(s)",
            ongoing = false,
        )
    }

    private suspend fun sendSealed(
        context: Context,
        targetId: String,
        kind: String,
        inner: JSONObject,
    ): Boolean {
        val socket = ws ?: return false
        val token = KeyRepository.getClusterToken(context)
        if (token.isBlank()) return false
        val (_, peerCryptoKey) = Crypto.deriveSubKeys(token)
        val key = Crypto.deriveSealKey(peerCryptoKey, kind)
        val msgId = "seal-${UUID.randomUUID()}"
        val ts = System.currentTimeMillis()
        val nonce = Crypto.randomBytes(12)
        val aad = "seal:v3:$targetId:$msgId:$ts:$kind".toByteArray(Charsets.UTF_8)
        val ciphertext = Crypto.encryptGcm(
            key,
            nonce,
            inner.toString().toByteArray(Charsets.UTF_8),
            aad,
        )
        val message = JSONObject().apply {
            put("type", "sealed")
            put("v", 3)
            put("target_id", targetId)
            put("msg_id", msgId)
            put("ts", ts)
            put("kind", kind)
            put("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
            put("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
        return socket.send(message.toString())
    }

    /** Sealed text/link for offline or multi-device delivery. */
    suspend fun sendSealedText(
        context: Context,
        targetId: String,
        text: String,
        kind: String,
    ): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_TEXT_CHARS) return false
        val inner = JSONObject().apply {
            put("sender_id", deviceId)
            put("sender_name", KeyRepository.getDeviceName(context))
            put("text", trimmed)
            put("kind", kind)
        }
        return sendSealed(context, targetId, "text", inner)
    }

    private suspend fun handleSealed(context: Context, json: JSONObject) {
        val targetId = json.optString("target_id")
        val msgId = json.optString("msg_id")
        val ts = json.optLong("ts", 0L)
        val relayTs = json.optLong("relay_ts", ts)
        val kind = json.optString("kind")
        val nonceB64 = json.optString("nonce")
        val dataB64 = json.optString("data")
        if (targetId != deviceId || msgId.isBlank() || ts <= 0L || kind.isBlank() ||
            nonceB64.isBlank() || dataB64.isBlank()
        ) {
            return
        }
        val token = KeyRepository.getClusterToken(context)
        if (token.isBlank()) return
        val (relayToken, peerCryptoKey) = Crypto.deriveSubKeys(token)
        val key = Crypto.deriveSealKey(peerCryptoKey, kind)
        val nonce = Base64.decode(nonceB64, Base64.NO_WRAP)
        val ciphertext = Base64.decode(dataB64, Base64.NO_WRAP)
        val aad = "seal:v3:$targetId:$msgId:$ts:$kind".toByteArray(Charsets.UTF_8)
        val plaintext = runCatching {
            Crypto.decryptGcm(key, nonce, ciphertext, aad)
        }.getOrNull() ?: run {
            Log.w(TAG, "Sealed $kind failed authentication")
            return
        }
        if (plaintext.size > 512 * 1024) return
        val inner = runCatching {
            JSONObject(String(plaintext, Charsets.UTF_8))
        }.getOrNull() ?: return
        val senderId = inner.optString("sender_id")
        if (!acceptsInbound(context, sealedInboundKind(kind), senderId)) return
        val senderName = inner.optString("sender_name", "Remote device")

        when (kind) {
            "offer" -> handleSealedOfferV3(
                context, relayToken, peerCryptoKey, senderId, senderName, inner,
            )
            "complete" -> {
                val fileName = inner.optString("file_name", "file")
                showNotification(
                    context,
                    notifIdFor(msgId, sending = false),
                    "Delivered",
                    "$senderName received $fileName",
                    ongoing = false,
                )
            }
            "text" -> {
                val text = inner.optString("text")
                if (text.isNotBlank()) {
                    TextShareNotifications.show(
                        context,
                        senderName,
                        text,
                        inner.optString("kind", "text"),
                    )
                }
            }
            "notification" -> {
                val event = inner.optString("event", "posted")
                if (event == "removed") {
                    NotificationDisplay.cancel(context, senderId, inner.optString("key"))
                } else {
                    NotificationDisplay.show(context, senderId, senderName, inner)
                }
            }
            "notification_action" -> {
                NotificationMirrorManager.executeAction(
                    context,
                    inner.optString("notif_key"),
                    inner.optInt("action_index", -1),
                    inner.optString("reply").ifBlank { null },
                )
            }
            "media_state" -> {
                if (inner.optString("source_kind") == "desktop") {
                    if (inner.optBoolean("stopped") || inner.optString("title").isBlank()) {
                        RemoteMediaDisplay.cancel(context, senderId)
                    } else {
                        RemoteMediaDisplay.show(context, senderId, senderName, inner)
                    }
                }
            }
            "media_command" -> {
                MediaSessionBridge.handleCommand(context, inner, relayTs)
            }
        }
    }

    private suspend fun handleSealedOfferV3(
        context: Context,
        relayToken: String,
        peerCryptoKey: ByteArray,
        senderId: String,
        senderName: String,
        inner: JSONObject,
    ) {
        val transferId = inner.optString("transfer_id")
        val sessionId = inner.optString("session_id")
        val rawName = inner.optString("file_name")
        val fileHash = inner.optString("file_hash")
        val fileSize = inner.optLong("file_size", 0L)
        val chunkSize = inner.optInt("chunk_size", 0)
        val chunkCount = inner.optInt("chunk_count", 0)
        val chunkHashesArray = inner.optJSONArray("chunk_hashes")
        if (transferId.isBlank() || sessionId.isBlank() || rawName.isBlank() ||
            fileHash.isBlank() || chunkSize <= 0 || chunkCount < 0 ||
            chunkHashesArray == null || chunkHashesArray.length() != chunkCount
        ) {
            return
        }
        val chunkHashes = ArrayList<String>(chunkCount)
        for (i in 0 until chunkCount) chunkHashes.add(chunkHashesArray.getString(i))

        val cleanName = Crypto.sanitizeFileName(rawName)
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        downloadsDir.mkdirs()
        if (!Crypto.isPathInsideDirectory(File(downloadsDir, cleanName), downloadsDir)) return

        val partFile = File(downloadsDir, ".$cleanName.$transferId.part")
        val stateFile = File(downloadsDir, ".$cleanName.$transferId.state")
        val state = ChunkTransport.ReceiveState(transferId, fileHash, fileSize, chunkSize, chunkCount)
        partFile.delete()
        stateFile.delete()
        ChunkTransport.saveState(state, stateFile)

        val digest = ChunkTransport.FileDigest(fileHash, chunkHashes, chunkCount, fileSize)
        val missing = (0 until chunkCount).toList()
        val fileKey = Crypto.deriveFileKey(peerCryptoKey, transferId)
        val relayHost = KeyRepository.getRelayAddress(context).ifBlank { RELAY_HOST }
        val notifId = notifIdFor(transferId, sending = false)

        try {
            ChunkTransport.downloadMissing(
                context = context,
                client = client,
                relayHost = relayHost,
                relayToken = relayToken,
                deviceId = deviceId,
                peerKey = peerCryptoKey,
                transferId = transferId,
                sessionId = sessionId,
                digest = digest,
                aesKey = fileKey,
                missing = missing,
                partFile = partFile,
                state = state,
                stateFile = stateFile,
            ) { done, total ->
                val progress = if (total > 0) done.toFloat() / total else 0f
                _transferState.value = TransferState.Receiving(senderName, cleanName, progress, 0f)
                showNotification(
                    context,
                    notifId,
                    "Receiving $cleanName",
                    "%.0f%%".format(progress * 100f),
                    ongoing = true,
                    progress = (progress * 100).toInt(),
                )
            }
            if (!ChunkTransport.verifyFileHash(partFile, fileHash)) {
                throw SecurityException("Integrity check failed")
            }
            val finalFile = Crypto.resolveUniqueFile(downloadsDir, cleanName)
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = false)
                partFile.delete()
            }
            stateFile.delete()
            _transferState.value = TransferState.Completed("Received ${finalFile.name}")
            showNotification(context, notifId, "Transfer Complete", "Saved ${finalFile.name} to Downloads")
            runCatching {
                TransferHistoryRepository.add(
                    context,
                    TransferHistoryEntry(
                        id = transferId,
                        kind = "file",
                        direction = "received",
                        peerId = senderId,
                        peerName = senderName,
                        label = finalFile.name,
                        size = fileSize,
                        status = "completed",
                        timestamp = System.currentTimeMillis(),
                        localPath = finalFile.absolutePath,
                    ),
                )
            }
            val completion = JSONObject().apply {
                put("sender_id", deviceId)
                put("sender_name", KeyRepository.getDeviceName(context))
                put("transfer_id", transferId)
                put("file_hash", fileHash)
                put("file_name", finalFile.name)
            }
            sendSealed(context, senderId, "complete", completion)
        } catch (t: Throwable) {
            Log.e(TAG, "v3 receive failed: ${t.message}", t)
            partFile.delete()
            stateFile.delete()
            showNotification(context, notifId, "Transfer Failed", "Failed to receive $cleanName")
        }
    }

    /**
     * Central inbound trust gate. Resolves the "only trusted devices"
     * setting and the trusted-device list, then delegates to
     * [InboundPolicy]. On rejection, logs a single line with the sender id
     * and kind — never any payload.
     */
    private suspend fun acceptsInbound(context: Context, kind: InboundKind, senderId: String): Boolean {
        val onlyTrusted = runCatching {
            TransferSettingsRepository.onlyTrusted(context)
        }.getOrDefault(false)
        val trustedIds = if (onlyTrusted) {
            runCatching {
                TrustedDeviceRepository.observe(context).first().map { it.id }.toSet()
            }.getOrDefault(emptySet())
        } else {
            emptySet()
        }
        val accepted = InboundPolicy.shouldAccept(kind, senderId, deviceId, trustedIds, onlyTrusted)
        if (!accepted) {
            Log.i(
                TAG,
                "Rejected inbound ${kind.name.lowercase().replace('_', ' ')} " +
                    "from sender ${senderId.ifBlank { "(no id)" }}",
            )
        }
        return accepted
    }

    private fun sealedInboundKind(kind: String): InboundKind = when (kind) {
        "offer", "complete" -> InboundKind.OFFER
        "text" -> InboundKind.TEXT
        "notification" -> InboundKind.NOTIFICATION
        "notification_action" -> InboundKind.NOTIFICATION_ACTION
        "media_state", "media_command" -> InboundKind.MEDIA
        else -> InboundKind.TEXT
    }

    private suspend fun handleIncomingOfferV1(
        context: Context,
        relayToken: String,
        peerCryptoKey: ByteArray,
        offer: JSONObject
    ) {
        val transferId = offer.getString("transfer_id")
        val senderId = offer.getString("from_id")
        val senderDeviceId = offer.optString("sender_id", senderId)
        val senderName = offer.optString("from_name", "Remote Device")
        val rawFileName = offer.getString("file_name")
        val fileSize = offer.optLong("file_size", 0L)
        val senderPubB64 = offer.getString("pub_key")
        val offerTag = offer.optString("auth_tag")
        val batchTotal = offer.optInt("batch_total", 1).coerceAtLeast(1)
        val batchIndex = offer.optInt("batch_index", 0).coerceIn(0, batchTotal - 1)

        // Zero-knowledge peer-to-peer authentication (MITM defense). The tag
        // binds the raw envelope fields — including file name and size —
        // before any sanitization, so a relay cannot swap the payload.
        val expectedOfferMsg = TransferOfferCodec.v1OfferAuthMessage(
            transferId, deviceId, senderPubB64, rawFileName, fileSize,
        )
        if (!Crypto.verifyAuthTag(peerCryptoKey, expectedOfferMsg, offerTag)) {
            Log.e(TAG, "Incoming offer auth tag invalid! Rejecting potential MITM or unauthorized peer.")
            val rejectMsg = JSONObject().apply {
                put("type", "transfer_reject")
                put("target_id", senderId)
                put("transfer_id", transferId)
                put("reason", "auth_failed")
            }
            ws?.send(rejectMsg.toString())
            return
        }

        if (!acceptsInbound(context, InboundKind.OFFER, senderDeviceId)) {
            ws?.send(JSONObject().apply {
                put("type", "transfer_reject")
                put("target_id", senderId)
                put("transfer_id", transferId)
                put("reason", "untrusted")
            }.toString())
            showNotification(
                context,
                notifIdFor(transferId, sending = false),
                "Transfer blocked",
                "Not from a trusted device",
            )
            return
        }

        // Path traversal protection
        val cleanName = Crypto.sanitizeFileName(rawFileName)
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        downloadsDir.mkdirs()
        val testDest = File(downloadsDir, cleanName)
        if (!Crypto.isPathInsideDirectory(testDest, downloadsDir)) {
            Log.e(TAG, "Path traversal attempt detected in fileName: $rawFileName")
            val rejectMsg = JSONObject().apply {
                put("type", "transfer_reject")
                put("target_id", senderId)
                put("transfer_id", transferId)
                put("reason", "invalid_path")
            }
            ws?.send(rejectMsg.toString())
            return
        }

        val senderPubBytes = Base64.decode(senderPubB64, Base64.NO_WRAP)
        val keyPair = Crypto.generateKeyPair()
        val aesKey = Crypto.deriveSharedSecret(keyPair, senderPubBytes)
        val myPubB64 = Base64.encodeToString(Crypto.exportPublicKey(keyPair.public), Base64.NO_WRAP)
        val acceptTag = Crypto.computeAuthTag(peerCryptoKey, "accept:$transferId:$senderId:$myPubB64")

        val safetyNumber = Crypto.computeSafetyNumber(senderPubBytes, Crypto.exportPublicKey(keyPair.public), transferId)
        Log.i(TAG, "Transfer $transferId safety number verified: $safetyNumber")

        val acceptMsg = JSONObject().apply {
            put("type", "transfer_accept")
            put("target_id", senderId)
            put("transfer_id", transferId)
            put("pub_key", myPubB64)
            put("auth_tag", acceptTag)
        }
        ws?.send(acceptMsg.toString())

        val label = if (batchTotal > 1) {
            "$batchTotal files (${batchIndex + 1}/$batchTotal): $cleanName"
        } else {
            cleanName
        }
        val notifId = notifIdFor(transferId, sending = false)

        _transferState.value = TransferState.Receiving(senderName, label, 0f, 0f)
        showNotification(context, notifId, "Receiving $label", "From $senderName...", ongoing = true, progress = -2)

        val partFile = File(downloadsDir, ".${cleanName}.${transferId}.part")
        val relayHost = KeyRepository.getRelayAddress(context).ifBlank { RELAY_HOST }
        val pipeUrl = "http://$relayHost/pipe?transfer_id=$transferId&role=receiver"
        val request = Request.Builder()
            .url(pipeUrl)
            .header("X-Gaur-Token", relayToken)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Pipe error: ${e.message}")
                if (partFile.exists()) partFile.delete()
                _transferState.value = TransferState.Error("Transfer failed: ${e.message}")
                showNotification(context, notifId, "Transfer Failed", "Connection error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    if (partFile.exists()) partFile.delete()
                    _transferState.value = TransferState.Error("Pipe rejected with code ${response.code}")
                    showNotification(context, notifId, "Transfer Failed", "Relay returned code ${response.code}")
                    return
                }

                val body = response.body
                if (body == null) {
                    if (partFile.exists()) partFile.delete()
                    return
                }

                var totalRead = 0L
                var expectedNonceCounter = 0L

                try {
                    val inStream = body.byteStream()
                    val cipher = Crypto.createAesGcmCipher()
                    val cipherBuf = ByteArray(MAX_CHUNK_SIZE + 16)
                    val lenBytes = ByteArray(4)
                    val nonceBytes = ByteArray(12)
                    val startTime = System.currentTimeMillis()
                    var lastUpdate = 0L

                    BufferedOutputStream(FileOutputStream(partFile), 1 shl 20).use { out ->
                        while (true) {
                            if (!readFully(inStream, lenBytes, 4)) break
                            val chunkLen = ByteBuffer.wrap(lenBytes).order(ByteOrder.BIG_ENDIAN).int
                            if (chunkLen == 0) break // EOF sentinel
                            if (chunkLen < 0 || chunkLen > MAX_CHUNK_SIZE) {
                                throw IOException("Invalid chunk length: $chunkLen bytes")
                            }

                            if (!readFully(inStream, nonceBytes, 12)) break

                            // Enforce sequential nonces
                            val nonceBuf = ByteBuffer.wrap(nonceBytes)
                            val noncePrefix = nonceBuf.getInt(0)
                            val nonceCounter = nonceBuf.getLong(4)
                            if (noncePrefix != 0 || nonceCounter != expectedNonceCounter) {
                                throw SecurityException("Nonce sequence violation: expected $expectedNonceCounter, got $nonceCounter (prefix=$noncePrefix)")
                            }
                            expectedNonceCounter++

                            if (!readFully(inStream, cipherBuf, chunkLen)) break

                            val plain = Crypto.decryptChunk(cipher, aesKey, nonceBytes, cipherBuf, 0, chunkLen)
                            out.write(plain)
                            totalRead += plain.size

                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 150) {
                                lastUpdate = now
                                val elapsed = (now - startTime) / 1000f
                                val speedMb = if (elapsed > 0) (totalRead / (1024f * 1024f)) / elapsed else 0f
                                val fileProgress = if (fileSize > 0) (totalRead.toFloat() / fileSize.toFloat()) else 0.5f
                                val overall = if (batchTotal > 1) {
                                    (batchIndex + fileProgress) / batchTotal
                                } else {
                                    fileProgress
                                }

                                _transferState.value = TransferState.Receiving(senderName, label, overall, speedMb)
                                showNotification(
                                    context, notifId, "Receiving $label",
                                    "%.1f MB/s - %.0f%%".format(speedMb, overall * 100f),
                                    ongoing = true, progress = (overall * 100).toInt()
                                )
                            }
                        }
                        out.flush()
                    }

                    if (fileSize > 0 && totalRead < fileSize) {
                        throw IOException("Incomplete transfer: received $totalRead of $fileSize bytes")
                    }

                    if (totalRead > 0 || fileSize == 0L) {
                        val finalFile = Crypto.resolveUniqueFile(downloadsDir, cleanName)
                        val renamed = partFile.renameTo(finalFile)
                        if (!renamed) {
                            partFile.copyTo(finalFile, overwrite = false)
                            partFile.delete()
                        }
                        _transferState.value = TransferState.Completed("Received ${finalFile.name} successfully")
                        showNotification(context, notifId, "Transfer Complete", "Saved ${finalFile.name} to Downloads")
                        scope.launch {
                            runCatching {
                                TransferHistoryRepository.add(
                                    context,
                                    TransferHistoryEntry(
                                        id = transferId,
                                        kind = "file",
                                        direction = "received",
                                        peerId = senderDeviceId,
                                        peerName = senderName,
                                        label = finalFile.name,
                                        size = fileSize,
                                        status = "completed",
                                        timestamp = System.currentTimeMillis(),
                                        localPath = finalFile.absolutePath,
                                    ),
                                )
                            }
                        }
                    } else {
                        throw IOException("Received 0 bytes")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error decrypting stream: ${t.message}", t)
                    if (partFile.exists()) partFile.delete()
                    _transferState.value = TransferState.Error("Transfer error: ${t.message}")
                    showNotification(context, notifId, "Transfer Failed", "Failed to receive $cleanName")
                }
            }
        })
    }

    private suspend fun handleIncomingOfferV2(
        context: Context,
        relayToken: String,
        peerCryptoKey: ByteArray,
        offer: JSONObject,
    ) {
        val transferId = offer.getString("transfer_id")
        val sessionId = offer.optString("session_id")
        val senderId = offer.getString("from_id")
        val senderDeviceId = offer.optString("sender_id", senderId)
        val senderName = offer.optString("from_name", "Remote Device")
        val rawFileName = offer.getString("file_name")
        val fileSize = offer.optLong("file_size", 0L)
        val chunkSize = offer.optInt("chunk_size", 0)
        val chunkCount = offer.optInt("chunk_count", 0)
        val fileHash = offer.optString("file_hash")
        val senderPubB64 = offer.getString("pub_key")
        val offerTag = offer.optString("auth_tag")
        val chunkHashesArray = offer.optJSONArray("chunk_hashes")
        val batchTotal = offer.optInt("batch_total", 1).coerceAtLeast(1)
        val batchIndex = offer.optInt("batch_index", 0).coerceIn(0, batchTotal - 1)
        val openOnArrival = offer.optBoolean("open", false)
        val offerMime = offer.optString("mime_type")

        val validEmpty = fileSize == 0L && chunkCount == 0
        val validNonEmpty = fileSize > 0L && chunkCount > 0
        if (sessionId.isBlank() || fileHash.isBlank() || chunkSize <= 0 ||
            chunkSize > ChunkTransport.MAX_CHUNK_SIZE || chunkCount > 65536 ||
            (!validEmpty && !validNonEmpty) || chunkHashesArray == null ||
            chunkHashesArray.length() != chunkCount
        ) {
            Log.e(TAG, "Rejecting malformed v2 offer $transferId")
            return
        }
        val chunkHashes = ArrayList<String>(chunkCount)
        for (i in 0 until chunkCount) chunkHashes.add(chunkHashesArray.getString(i))

        val expectedOffer = TransferOfferCodec.offerAuthMessage(
            transferId, deviceId, senderPubB64, fileHash, chunkCount, chunkSize, openOnArrival,
        )
        if (!Crypto.verifyAuthTag(peerCryptoKey, expectedOffer, offerTag)) {
            Log.e(TAG, "Incoming v2 offer auth tag invalid! Rejecting $transferId")
            ws?.send(JSONObject().apply {
                put("type", "transfer_reject")
                put("target_id", senderId)
                put("transfer_id", transferId)
                put("reason", "auth_failed")
            }.toString())
            return
        }

        if (!acceptsInbound(context, InboundKind.OFFER, senderDeviceId)) {
            ws?.send(JSONObject().apply {
                put("type", "transfer_reject")
                put("target_id", senderId)
                put("transfer_id", transferId)
                put("reason", "untrusted")
            }.toString())
            showNotification(
                context,
                notifIdFor(transferId, sending = false),
                "Transfer blocked",
                "Not from a trusted device",
            )
            return
        }

        val cleanName = Crypto.sanitizeFileName(rawFileName)
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        downloadsDir.mkdirs()
        val testDest = File(downloadsDir, cleanName)
        if (!Crypto.isPathInsideDirectory(testDest, downloadsDir)) {
            ws?.send(JSONObject().apply {
                put("type", "transfer_reject")
                put("target_id", senderId)
                put("transfer_id", transferId)
                put("reason", "invalid_path")
            }.toString())
            return
        }

        val partFile = File(downloadsDir, ".$cleanName.$transferId.part")
        val stateFile = File(downloadsDir, ".$cleanName.$transferId.state")
        var state = ChunkTransport.loadState(stateFile, transferId, fileHash)
        if (state == null) {
            partFile.delete()
            stateFile.delete()
            state = ChunkTransport.ReceiveState(transferId, fileHash, fileSize, chunkSize, chunkCount)
            ChunkTransport.saveState(state, stateFile)
        } else if (!partFile.exists() && fileSize > 0) {
            state = ChunkTransport.ReceiveState(transferId, fileHash, fileSize, chunkSize, chunkCount)
            ChunkTransport.saveState(state, stateFile)
        }

        val senderPubBytes = Base64.decode(senderPubB64, Base64.NO_WRAP)
        val keyPair = Crypto.generateKeyPair()
        val aesKey = Crypto.deriveSharedSecret(keyPair, senderPubBytes)
        val myPubB64 = Base64.encodeToString(Crypto.exportPublicKey(keyPair.public), Base64.NO_WRAP)
        val have = state.have.toList().sorted()
        val acceptTag = Crypto.computeAuthTag(
            peerCryptoKey,
            "accept:v2:$transferId:$senderId:$myPubB64:${Crypto.haveHash(have)}",
        )
        ws?.send(JSONObject().apply {
            put("type", "transfer_accept")
            put("v", 2)
            put("target_id", senderId)
            put("transfer_id", transferId)
            put("pub_key", myPubB64)
            put("auth_tag", acceptTag)
            put("have", JSONArray(have))
        }.toString())

        val safetyNumber = Crypto.computeSafetyNumber(senderPubBytes, Crypto.exportPublicKey(keyPair.public), transferId)
        Log.i(TAG, "Transfer $transferId safety number verified: $safetyNumber")

        val label = if (batchTotal > 1) {
            "$batchTotal files (${batchIndex + 1}/$batchTotal): $cleanName"
        } else {
            cleanName
        }
        val notifId = notifIdFor(transferId, sending = false)
        _transferState.value = TransferState.Receiving(senderName, label, 0f, 0f)
        showNotification(context, notifId, "Receiving $label", "From $senderName...", ongoing = true, progress = -2)

        val digest = ChunkTransport.FileDigest(fileHash, chunkHashes, chunkCount, fileSize)
        val missing = (0 until chunkCount).filter { it !in state.have }
        val startTime = System.currentTimeMillis()
        val lastProgress = AtomicLong(0)

        try {
            ChunkTransport.downloadMissing(
                context = context,
                client = client,
                relayHost = KeyRepository.getRelayAddress(context).ifBlank { RELAY_HOST },
                relayToken = relayToken,
                deviceId = deviceId,
                peerKey = peerCryptoKey,
                transferId = transferId,
                sessionId = sessionId,
                digest = digest,
                aesKey = aesKey,
                missing = missing,
                partFile = partFile,
                state = state,
                stateFile = stateFile,
            ) { done, total ->
                val now = System.currentTimeMillis()
                if (now - lastProgress.get() < 200L) return@downloadMissing
                lastProgress.set(now)
                val fileProgress = if (total > 0) done.toFloat() / total else 0f
                val overall = if (batchTotal > 1) (batchIndex + fileProgress) / batchTotal else fileProgress
                val elapsed = (now - startTime) / 1000f
                val speedMb = if (elapsed > 0) (done / (1024f * 1024f)) / elapsed else 0f
                _transferState.value = TransferState.Receiving(senderName, label, overall, speedMb)
                showNotification(
                    context, notifId, "Receiving $label",
                    "%.1f MB/s - %.0f%%".format(speedMb, overall * 100f),
                    ongoing = true, progress = (overall * 100).toInt(),
                )
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "v2 download failed for $transferId: ${t.message}", t)
            ws?.send(JSONObject().apply {
                put("type", "transfer_error")
                put("target_id", senderId)
                put("transfer_id", transferId)
                put("reason", t.message ?: "download_failed")
            }.toString())
            _transferState.value = TransferState.Error("Transfer failed: ${t.message}")
            showNotification(context, notifId, "Transfer Failed", "Failed to receive $cleanName")
            return
        }

        ChunkTransport.saveState(state, stateFile)
        if (fileSize == 0L && !partFile.exists()) {
            partFile.createNewFile()
        }
        if (!ChunkTransport.verifyFileHash(partFile, fileHash)) {
            partFile.delete()
            stateFile.delete()
            ws?.send(JSONObject().apply {
                put("type", "transfer_error")
                put("target_id", senderId)
                put("transfer_id", transferId)
                put("reason", "integrity_failed")
            }.toString())
            _transferState.value = TransferState.Error("Transfer failed integrity check")
            showNotification(context, notifId, "Transfer Failed", "$cleanName failed verification")
            return
        }

        val finalFile = Crypto.resolveUniqueFile(downloadsDir, cleanName)
        val renamed = partFile.renameTo(finalFile)
        if (!renamed) {
            partFile.copyTo(finalFile, overwrite = false)
            partFile.delete()
        }
        stateFile.delete()

        ws?.send(JSONObject().apply {
            put("type", "transfer_complete")
            put("v", 2)
            put("target_id", senderId)
            put("transfer_id", transferId)
            put("file_hash", fileHash)
            put("auth_tag", Crypto.computeAuthTag(peerCryptoKey, "complete:v2:$transferId:$fileHash"))
        }.toString())

        _transferState.value = TransferState.Completed("Received ${finalFile.name} successfully")
        showNotification(context, notifId, "Transfer Complete", "Saved ${finalFile.name} to Downloads")
        runCatching {
            TransferHistoryRepository.add(
                context,
                TransferHistoryEntry(
                    id = transferId,
                    kind = "file",
                    direction = "received",
                    peerId = senderDeviceId,
                    peerName = senderName,
                    label = finalFile.name,
                    size = fileSize,
                    status = "completed",
                    timestamp = System.currentTimeMillis(),
                    localPath = finalFile.absolutePath,
                ),
            )
        }

        if (openOnArrival && TransferSettingsRepository.getAutoOpen(context)) {
            val mime = TransferOfferCodec.resolveMimeType(
                offerMime.ifBlank { null },
                mimeFromExtension(finalFile.name),
            )
            openReceivedFile(context, finalFile, mime)
        }
    }

    private data class OutgoingFile(val uri: Uri, val name: String, val size: Long)

    fun sendFiles(
        context: Context,
        target: ClusterPeer,
        uris: List<Uri>,
        openOnArrival: Boolean = false,
        cancelPrevious: Boolean = true,
    ) {
        if (uris.isEmpty()) return
        val appCtx = context.applicationContext
        if (ws == null) {
            _transferState.value = TransferState.Error("File sharing not connected")
            return
        }

        if (cancelPrevious) sendJob?.cancel()
        val job = scope.launch {
            val masterToken = runCatching { KeyRepository.getClusterToken(appCtx) }.getOrDefault("")
            if (masterToken.isBlank()) {
                _transferState.value = TransferState.Error("Cluster token not configured")
                return@launch
            }
            val (relayToken, peerCryptoKey) = Crypto.deriveSubKeys(masterToken)
            val files = uris.map { uri ->
                val (name, size) = queryFileInfo(appCtx.contentResolver, uri)
                OutgoingFile(uri, name, size)
            }
            val batchId = "batch-${UUID.randomUUID()}"
            val batchName = if (files.size == 1) files[0].name else "${files.size} files"
            val totalSize = files.sumOf { it.size }
            val batchNotifId = notifIdFor(batchId, sending = true)

            val historyIds = files.mapIndexed { index, file ->
                val entryId = "$batchId-$index"
                runCatching {
                    TransferHistoryRepository.add(
                        appCtx,
                        TransferHistoryEntry(
                            id = entryId,
                            kind = "file",
                            direction = "sent",
                            peerId = target.id,
                            peerName = target.name,
                            label = file.name,
                            size = file.size,
                            status = "active",
                            timestamp = System.currentTimeMillis(),
                            sourceUri = file.uri.toString(),
                        ),
                    )
                }
                entryId
            }

            try {
                var sentBefore = 0L
                files.forEachIndexed { index, file ->
                    val label = if (files.size > 1) {
                        "$batchName (${index + 1}/${files.size}): ${file.name}"
                    } else {
                        file.name
                    }
                    sendOne(
                        context = appCtx,
                        target = target,
                        relayToken = relayToken,
                        peerCryptoKey = peerCryptoKey,
                        file = file,
                        batchId = batchId,
                        batchIndex = index,
                        batchTotal = files.size,
                        label = label,
                        sentBefore = sentBefore,
                        totalSize = totalSize,
                        openOnArrival = openOnArrival,
                    )
                    sentBefore += file.size
                }
                val message = if (files.size == 1) {
                    "Sent ${files[0].name} to ${target.name}"
                } else {
                    "Sent ${files.size} files to ${target.name}"
                }
                _transferState.value = TransferState.Completed(message)
                showNotification(appCtx, batchNotifId, "Transfer Complete", message)
                historyIds.forEach { id ->
                    runCatching { TransferHistoryRepository.updateStatus(appCtx, id, "completed") }
                }
            } catch (t: CancellationException) {
                historyIds.forEach { id ->
                    runCatching { TransferHistoryRepository.updateStatus(appCtx, id, "cancelled") }
                }
                _transferState.value = TransferState.Idle
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "Send failed: ${t.message}", t)
                _transferState.value = TransferState.Error("Transfer error: ${t.message}")
                showNotification(appCtx, batchNotifId, "Transfer Failed", "Failed sending to ${target.name}")
                historyIds.forEach { id ->
                    runCatching { TransferHistoryRepository.updateStatus(appCtx, id, "failed") }
                }
            }
        }
        if (cancelPrevious) sendJob = job
    }

    fun sendFile(context: Context, target: ClusterPeer, uri: Uri) =
        sendFiles(context, target, listOf(uri))

    private suspend fun sendOne(
        context: Context,
        target: ClusterPeer,
        relayToken: String,
        peerCryptoKey: ByteArray,
        file: OutgoingFile,
        batchId: String,
        batchIndex: Int,
        batchTotal: Int,
        label: String,
        sentBefore: Long,
        totalSize: Long,
        openOnArrival: Boolean,
    ) {
        val preparingNotifId = notifIdFor(batchId, sending = true)
        _transferState.value = TransferState.Sending(target.name, label, 0f, 0f)
        showNotification(context, preparingNotifId, "Preparing $label", "Hashing file...", ongoing = true, progress = -2)

        val digest = withContext(Dispatchers.IO) { ChunkTransport.hashUri(context, file.uri) }
        cancelNotification(context, preparingNotifId)
        if (file.size > 0 && digest.size != file.size) {
            throw IOException("File changed while hashing (expected ${file.size}, read ${digest.size})")
        }
        val transferId = Crypto.deriveTransferId(peerCryptoKey, digest.fileHash)

        var lastError: Throwable? = null
        for (attempt in 1..3) {
            if (attempt > 1) delay((attempt - 1) * 1000L)
            val sessionId = Crypto.randomHex(16)
            try {
                sendAttempt(
                    context, target, relayToken, peerCryptoKey, file, digest,
                    transferId, sessionId, batchId, batchIndex, batchTotal, label, sentBefore, totalSize,
                    openOnArrival,
                )
                return
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "Send attempt $attempt for ${file.name} failed: ${t.message}", t)
            }
        }
        throw IOException("Transfer failed after 3 attempts", lastError)
    }

    private suspend fun sendAttempt(
        context: Context,
        target: ClusterPeer,
        relayToken: String,
        peerCryptoKey: ByteArray,
        file: OutgoingFile,
        digest: ChunkTransport.FileDigest,
        transferId: String,
        sessionId: String,
        batchId: String,
        batchIndex: Int,
        batchTotal: Int,
        label: String,
        sentBefore: Long,
        totalSize: Long,
        openOnArrival: Boolean,
    ) {
        val keyPair = Crypto.generateKeyPair()
        val myPubB64 = Base64.encodeToString(Crypto.exportPublicKey(keyPair.public), Base64.NO_WRAP)
        val offerTag = Crypto.computeAuthTag(
            peerCryptoKey,
            TransferOfferCodec.offerAuthMessage(
                transferId, target.id, myPubB64,
                digest.fileHash, digest.chunkCount, ChunkTransport.CHUNK_SIZE, openOnArrival,
            ),
        )

        val offer = JSONObject().apply {
            put("type", "transfer_offer")
            put("v", 2)
            put("target_id", target.id)
            put("sender_id", deviceId)
            put("transfer_id", transferId)
            put("session_id", sessionId)
            put("file_name", file.name)
            put("file_size", digest.size)
            put("chunk_size", ChunkTransport.CHUNK_SIZE)
            put("chunk_count", digest.chunkCount)
            put("file_hash", digest.fileHash)
            put("chunk_hashes", JSONArray(digest.chunkHashes))
            put("pub_key", myPubB64)
            put("auth_tag", offerTag)
            put("batch_id", batchId)
            put("batch_index", batchIndex)
            put("batch_total", batchTotal)
            if (openOnArrival) {
                put("open", true)
                put("mime_type", TransferOfferCodec.resolveMimeType(
                    context.contentResolver.getType(file.uri),
                    mimeFromExtension(file.uri.path),
                ))
            }
        }

        val deferred = CompletableDeferred<JSONObject>()
        pendingAccepts[transferId] = deferred
        val socket = ws ?: throw IOException("File sharing not connected")
        if (!socket.send(offer.toString())) {
            pendingAccepts.remove(transferId)
            throw IOException("Failed to send transfer offer")
        }

        val response = withTimeoutOrNull(60_000L) { deferred.await() }
            ?: run {
                pendingAccepts.remove(transferId)
                throw IOException("Transfer request timed out")
            }
        if (response.optString("type") == "transfer_reject") {
            throw IOException("${target.name} declined the transfer")
        }

        val pubB64 = response.optString("pub_key")
        val acceptTag = response.optString("auth_tag")
        val have = parseHave(response.optJSONArray("have"))
        val expectedAccept = "accept:v2:$transferId:$deviceId:$pubB64:${Crypto.haveHash(have)}"
        if (!Crypto.verifyAuthTag(peerCryptoKey, expectedAccept, acceptTag)) {
            throw SecurityException("Security verification failed: peer key untrusted")
        }

        val peerPubBytes = Base64.decode(pubB64, Base64.NO_WRAP)
        val aesKey = Crypto.deriveSharedSecret(keyPair, peerPubBytes)
        val safetyNumber = Crypto.computeSafetyNumber(Crypto.exportPublicKey(keyPair.public), peerPubBytes, transferId)
        Log.i(TAG, "Transfer $transferId safety number verified: $safetyNumber")

        val haveSet = have.toHashSet()
        val missing = (0 until digest.chunkCount).filter { it !in haveSet }
        val relayHost = KeyRepository.getRelayAddress(context).ifBlank { RELAY_HOST }
        val notifId = notifIdFor(transferId, sending = true)
        val startTime = System.currentTimeMillis()
        val lastProgress = AtomicLong(0)

        val completion = CompletableDeferred<JSONObject>()
        pendingCompletes[transferId] = completion
        try {
            ChunkTransport.uploadMissing(
                context = context,
                client = client,
                relayHost = relayHost,
                relayToken = relayToken,
                deviceId = deviceId,
                peerKey = peerCryptoKey,
                transferId = transferId,
                sessionId = sessionId,
                digest = digest,
                aesKey = aesKey,
                missing = missing,
                uri = file.uri,
            ) { done, total ->
                val now = System.currentTimeMillis()
                if (now - lastProgress.get() < 200L) return@uploadMissing
                lastProgress.set(now)
                val fileProgress = if (total > 0) done.toFloat() / total else 0f
                val overall = if (totalSize > 0) {
                    (sentBefore + fileProgress * file.size).toFloat() / totalSize
                } else {
                    fileProgress
                }
                val elapsed = (now - startTime) / 1000f
                val speedMb = if (elapsed > 0) (done / (1024f * 1024f)) / elapsed else 0f
                _transferState.value = TransferState.Sending(target.name, label, overall, speedMb)
                showNotification(
                    context, notifId, "Sending $label",
                    "%.1f MB/s - %.0f%%".format(speedMb, overall * 100f),
                    ongoing = true, progress = (overall * 100).toInt(),
                )
            }

            val receipt = withTimeoutOrNull(completionTimeout(digest.size)) { completion.await() }
                ?: throw IOException("Timed out waiting for receiver confirmation")
            if (receipt.optString("type") == "transfer_error") {
                throw IOException("Receiver reported: ${receipt.optString("reason")}")
            }
            val fileHash = receipt.optString("file_hash")
            val receiptTag = receipt.optString("auth_tag")
            if (fileHash != digest.fileHash ||
                !Crypto.verifyAuthTag(peerCryptoKey, "complete:v2:$transferId:$fileHash", receiptTag)
            ) {
                throw SecurityException("Completion receipt failed verification")
            }
            showNotification(context, notifId, "Sent $label", "Transfer complete", ongoing = false)
        } catch (t: Throwable) {
            showNotification(context, notifId, "Transfer Failed", "Failed to send $label", ongoing = false)
            throw t
        } finally {
            pendingCompletes.remove(transferId)
        }

        ChunkTransport.deleteTransfer(client, relayHost, relayToken, deviceId, peerCryptoKey, transferId, sessionId)
    }

    private fun completionTimeout(fileSize: Long): Long {
        val bySize = fileSize / (512 * 1024) * 1000L
        return bySize.coerceIn(120_000L, 2 * 60 * 60 * 1000L)
    }

    private fun parseHave(array: JSONArray?): List<Int> {
        if (array == null) return emptyList()
        val out = ArrayList<Int>(array.length())
        for (i in 0 until array.length()) {
            val idx = array.optInt(i, -1)
            if (idx >= 0) out.add(idx)
        }
        return out.sorted()
    }

    private fun cancelNotification(context: Context, id: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { nm.cancel(id) }
    }

    private fun ensureNotificationChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(CHANNEL_ID, "File Transfers", NotificationManager.IMPORTANCE_DEFAULT)
                nm.createNotificationChannel(channel)
            }
        }
    }

    /** Extension-derived mime for a path or file name, or null when unknown. */
    private fun mimeFromExtension(path: String?): String? {
        val ext = path?.let { MimeTypeMap.getFileExtensionFromUrl(it) } ?: ""
        if (ext.isBlank()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
    }

    /**
     * Opens a freshly received file for the user. Starts an ACTION_VIEW
     * activity directly; if Android blocks the background activity launch,
     * falls back to a notification with an "Open" action carrying the same
     * intent.
     */
    private fun openReceivedFile(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(viewIntent)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not auto-open ${file.name}: ${t.message}")
            showOpenNotification(context, file.name, uri, mime)
        }
    }

    private fun showOpenNotification(context: Context, fileName: String, uri: Uri, mime: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            ensureNotificationChannel(nm)
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val openPi = PendingIntent.getActivity(
                context, fileName.hashCode(), viewIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_key)
                .setContentTitle("Transfer Complete")
                .setContentText("Saved $fileName to Downloads")
                .setContentIntent(openPi)
                .setAutoCancel(true)
                .setGroup(NOTIF_GROUP)
                .addAction(0, "Open", openPi)
                .build()
            nm.notify(notifIdFor(fileName, sending = false), notification)
        } catch (t: Throwable) {
            Log.w(TAG, "Open notification error: ${t.message}")
        }
    }

    private fun openInputStream(context: Context, uri: Uri): InputStream? =
        if (uri.scheme == "file") {
            runCatching { File(uri.path ?: "").inputStream() }.getOrNull()
        } else {
            context.contentResolver.openInputStream(uri)
        }

    private fun notifIdFor(key: String, sending: Boolean): Int {
        val base = if (sending) 10_000 else 20_000
        return base + (key.hashCode() and 0x0FFF)
    }

    private fun showNotification(
        context: Context,
        id: Int,
        title: String,
        message: String,
        ongoing: Boolean = false,
        progress: Int = -1
    ) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            ensureNotificationChannel(nm)
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pi = PendingIntent.getActivity(
                context, id, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            if (!ongoing) {
                nm.cancel(id)
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_key)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(pi)
                .setAutoCancel(!ongoing)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(ongoing)
                .setGroup(NOTIF_GROUP)

            if (progress in 0..100) {
                builder.setProgress(100, progress, false)
            } else if (progress == -2) {
                builder.setProgress(0, 0, true)
            }
            nm.notify(id, builder.build())
        } catch (t: Throwable) {
            Log.w(TAG, "Notification error: ${t.message}")
        }
    }

    /**
     * Cancels the active send and every in-flight receive. A later retry
     * reuses the same transfer ID, so v2 resumes from the receiver's bitmap
     * instead of restarting the whole file.
     */
    fun cancelActiveTransfers() {
        sendJob?.cancel()
        sendJob = null
        activeTransfers.values.forEach { it.cancel() }
        activeTransfers.clear()
        pendingAccepts.values.forEach { it.cancel() }
        pendingAccepts.clear()
        pendingCompletes.values.forEach { it.cancel() }
        pendingCompletes.clear()
        _transferState.value = TransferState.Idle
    }

    fun dismissTransfer() {
        _transferState.value = TransferState.Idle
    }

    private fun readFully(inStream: InputStream, b: ByteArray, len: Int): Boolean {
        var n = 0
        while (n < len) {
            val count = inStream.read(b, n, len - n)
            if (count < 0) return false
            n += count
        }
        return true
    }

    private fun queryFileInfo(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
        if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            return (if (f.name.isNotBlank()) f.name else "file_${System.currentTimeMillis()}") to f.length()
        }
        var name = "file_${System.currentTimeMillis()}"
        var size = 0L
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) name = cursor.getString(nameIdx)
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        return name to size
    }
}
