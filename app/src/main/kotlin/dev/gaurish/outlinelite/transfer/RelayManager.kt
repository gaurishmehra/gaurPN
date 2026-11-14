package dev.gaurish.outlinelite.transfer

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import dev.gaurish.outlinelite.MainActivity
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.data.KeyRepository
import dev.gaurish.outlinelite.data.TransferHistoryEntry
import dev.gaurish.outlinelite.data.TransferHistoryRepository
import dev.gaurish.outlinelite.data.TransferSettingsRepository
import dev.gaurish.outlinelite.data.TrustedDeviceRepository
import dev.gaurish.outlinelite.tunnel.TunnelPhase
import dev.gaurish.outlinelite.tunnel.TunnelState
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Facade for the cluster relay: owns the device identity, the peer and
 * transfer-state flows, and the WebSocket message dispatch (clipboard,
 * text, notification, and media routing). The socket session lives in
 * [RelayConnection], inbound transfers in [TransferEngine], outbound
 * transfers in [OutboundSender].
 */
object RelayManager {

    private const val TAG = "RelayManager"

    /** Default relay address; the configured one normally overrides it. */
    internal const val RELAY_HOST = "198.51.100.1:9876"
    internal const val CHANNEL_ID = "file_transfers"
    internal const val NOTIF_GROUP = "gaur_transfers"

    private const val MAX_TEXT_CHARS = 64 * 1024
    private const val MAX_TEXT_BYTES = 64 * 1024
    private const val MAX_SEALED_PAYLOAD_BYTES = 512 * 1024
    private const val MAX_NOTIFICATION_PAYLOAD_BYTES = 256 * 1024
    private const val MAX_HISTORY_LABEL_CHARS = 120
    private val URL_REGEX = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    @Volatile
    private var deviceId: String = ""

    private val _peers = MutableStateFlow<List<ClusterPeer>>(emptyList())
    val peers: StateFlow<List<ClusterPeer>> = _peers.asStateFlow()

    private val _transferState = MutableStateFlow<TransferState>(TransferState.Idle)
    val transferState: StateFlow<TransferState> = _transferState.asStateFlow()

    private val pendingAccepts = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val pendingCompletes = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    /** In-flight transfers and how to cancel them; drained by [stop]. */
    private val transferRegistry = TransferRegistry()

    private val transferEngine = TransferEngine(
        scope = scope,
        transferState = _transferState,
        registry = transferRegistry,
    )

    private val outboundSender = OutboundSender(
        scope = scope,
        transferState = _transferState,
        registry = transferRegistry,
        pendingAccepts = pendingAccepts,
        pendingCompletes = pendingCompletes,
    )

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
        RelayConnection.connect(context, token, deviceId, object : RelayConnection.Listener {
            override fun onOpen(
                context: Context,
                socket: WebSocket,
                relayToken: String,
                peerCryptoKey: ByteArray,
            ) {
                requestPeerList(socket)
                ClipboardSyncManager.onRelayConnected(context)
                MediaSessionBridge.onRelayConnected(context)
            }

            override fun onMessage(
                context: Context,
                relayToken: String,
                peerCryptoKey: ByteArray,
                text: String,
            ) {
                handleWsMessage(context, relayToken, peerCryptoKey, text)
            }

            override fun onClosed(context: Context) {
                _peers.value = emptyList()
                ClipboardSyncManager.onRelayDisconnected()
            }

            override fun onFailure(context: Context) {
                _peers.value = emptyList()
                ClipboardSyncManager.onRelayDisconnected()
            }
        })
    }

    fun refreshDevices(context: Context) {
        val appCtx = context.applicationContext
        scope.launch {
            if (TunnelState.flow.value.phase != TunnelPhase.CONNECTED) return@launch

            val token = KeyRepository.getClusterToken(appCtx)
            if (token.isBlank()) return@launch

            val currentSocket = RelayConnection.socketOrNull()
            if (currentSocket != null && currentSocket.send(peerListRequest())) return@launch

            Log.i(TAG, "Relay unavailable during device refresh; reconnecting")
            currentSocket?.close(1012, "Refresh device list")
            RelayConnection.clearIfCurrent(currentSocket)
            RelayConnection.scheduleReconnect(appCtx, token)
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
        outboundSender.cancelActive()
        transferRegistry.cancelAll()
        _transferState.value = TransferState.Idle
        disconnectRelay()
    }

    private fun disconnectRelay() {
        RelayConnection.close()
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
                    if (json.optInt("v", 1) >= 2) {
                        scope.launch {
                            val self = coroutineContext[Job]
                            val registration = transferRegistry.register(txId) { self?.cancel() }
                            val notifId = NotificationIds.acquire(txId, sending = false)
                            try {
                                transferEngine.handleIncomingOfferV2(
                                    context, relayToken, peerCryptoKey, json, notifId, deviceId,
                                )
                            } finally {
                                registration.remove()
                                NotificationIds.release(notifId)
                            }
                        }
                    } else {
                        // v1 streams on an OkHttp callback thread; the receiver
                        // registers the Call itself so stop() can cancel the pipe.
                        transferRegistry.cancel(txId) // supersede any previous attempt
                        scope.launch {
                            transferEngine.handleIncomingOfferV1(
                                context, relayToken, peerCryptoKey, json, deviceId,
                            )
                        }
                    }
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
        val socket = RelayConnection.socketOrNull() ?: return false
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
        val socket = RelayConnection.socketOrNull() ?: return false
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
                    label = if (kind == "url") trimmed else trimmed.take(MAX_HISTORY_LABEL_CHARS),
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
                    label = if (kind == "url") text else text.take(MAX_HISTORY_LABEL_CHARS),
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
        if (!RelayConnection.isConnected) return false
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
        if (plaintext.size > MAX_NOTIFICATION_PAYLOAD_BYTES) return
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
        if (!RelayConnection.isConnected) return false
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
        outboundSender.sendFilesV3(context, targets, uris, offline, deviceId)
    }

    fun sendFiles(
        context: Context,
        target: ClusterPeer,
        uris: List<Uri>,
        openOnArrival: Boolean = false,
        cancelPrevious: Boolean = true,
    ) {
        outboundSender.sendFiles(context, target, uris, openOnArrival, cancelPrevious, deviceId)
    }

    internal suspend fun sendSealed(
        context: Context,
        targetId: String,
        kind: String,
        inner: JSONObject,
    ): Boolean {
        val socket = RelayConnection.socketOrNull() ?: return false
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
        if (plaintext.size > MAX_SEALED_PAYLOAD_BYTES) return
        val inner = runCatching {
            JSONObject(String(plaintext, Charsets.UTF_8))
        }.getOrNull() ?: return
        val senderId = inner.optString("sender_id")
        if (!acceptsInbound(context, sealedInboundKind(kind), senderId)) return
        val senderName = inner.optString("sender_name", "Remote device")

        when (kind) {
            "offer" -> transferEngine.handleSealedOfferV3(
                context, relayToken, peerCryptoKey, senderId, senderName, inner, deviceId,
            )
            "complete" -> {
                val fileName = inner.optString("file_name", "file")
                showOneShotNotification(
                    context,
                    msgId,
                    sending = false,
                    "Delivered",
                    "$senderName received $fileName",
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

    /**
     * Central inbound trust gate. Resolves the "only trusted devices"
     * setting and the trusted-device list, then delegates to
     * [InboundPolicy]. On rejection, logs a single line with the sender id
     * and kind — never any payload.
     */
    internal suspend fun acceptsInbound(context: Context, kind: InboundKind, senderId: String): Boolean {
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

    internal fun cancelNotification(context: Context, id: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { nm.cancel(id) }
    }

    internal fun ensureNotificationChannel(context: Context) {
        NotificationChannels.ensureChannel(
            context,
            CHANNEL_ID,
            "File Transfers",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
    }

    /** Extension-derived mime for a path or file name, or null when unknown. */
    internal fun mimeFromExtension(path: String?): String? {
        val ext = path?.let { MimeTypeMap.getFileExtensionFromUrl(it) } ?: ""
        if (ext.isBlank()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
    }

    /**
     * One-shot informational notification: acquires an id for the post, then
     * releases it immediately. Concurrent one-shots still never clobber an
     * in-flight transfer's progress notification.
     */
    internal fun showOneShotNotification(
        context: Context,
        key: String,
        sending: Boolean,
        title: String,
        message: String,
    ) {
        val id = NotificationIds.acquire(key, sending)
        try {
            showNotification(context, id, title, message)
        } finally {
            NotificationIds.release(id)
        }
    }

    internal fun showNotification(
        context: Context,
        id: Int,
        title: String,
        message: String,
        ongoing: Boolean = false,
        progress: Int = -1
    ) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            ensureNotificationChannel(context)
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
     * reuses the same transfer ID, so v2/v3 resume from the receiver's
     * bitmap instead of restarting the whole file.
     */
    fun cancelActiveTransfers() {
        outboundSender.cancelActive()
        transferRegistry.cancelAll()
        pendingAccepts.values.forEach { it.cancel() }
        pendingAccepts.clear()
        pendingCompletes.values.forEach { it.cancel() }
        pendingCompletes.clear()
        _transferState.value = TransferState.Idle
    }

    fun dismissTransfer() {
        _transferState.value = TransferState.Idle
    }
}
