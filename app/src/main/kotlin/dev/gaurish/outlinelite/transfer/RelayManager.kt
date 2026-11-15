package dev.gaurish.outlinelite.transfer

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

    /** One listing round trip; a lost reply becomes a retryable UI error. */
    private const val BROWSE_TIMEOUT_MS = 10_000L
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

    /** Duplicate suppression for request payloads. */
    private val requestDeduper = RequestDeduper()

    /**
     * The single request currently being served. Held from acceptance until
     * the picker hands off/declines or the request expires; an expired lease
     * is reclaimed by the next request without any timer.
     */
    private val requestSlot = RequestSlot()

    /**
     * Requests this phone sent and is still waiting on. A sealed decline has
     * no sender id, so this map is the only gate that keeps a member from
     * firing a decline notification for a request the phone never made.
     */
    private val outstandingRequests = OutstandingRequests()

    /** Per-requester throttle for automatic screenshot replies. */
    private val screenshotRateLimiter = ScreenshotRateLimiter()

    /** Per-requester throttle for browse listings (2 accepted per second). */
    private val browseRateLimiter = BrowseRateLimiter()

    /** Phone-wide browse budget (10 accepted listings per second, all requesters). */
    private val globalBrowseBucket = GlobalBrowseBucket()

    /** At most two listings are built at once; a third is answered rate_limited. */
    private val browseWorkers = BrowseWorkerSlots()

    /**
     * Browse listings this phone asked for and is still waiting on, keyed by
     * request id. The id is generated locally and only sent to one peer, so a
     * reply that finds no entry is dropped like an unknown decline. The
     * expected path travels with the entry so a reply for a different
     * directory cannot be spliced into the screen.
     */
    private val pendingBrowseListings = PendingBrowseListings()

    /**
     * Declines for fetches this phone sent. The existing notification path
     * still posts; an open BrowseScreen also collects this to show the reason
     * in place. Buffered so emission never blocks the socket thread.
     */
    private val _browseDeclines = MutableSharedFlow<DeclineEnvelope>(extraBufferCapacity = 16)
    internal val browseDeclines: SharedFlow<DeclineEnvelope> = _browseDeclines.asSharedFlow()

    /** Devices whose malformed status blob was already logged once. */
    private val statusWarned = ConcurrentHashMap.newKeySet<String>()

    /** Devices whose stale status blob was already logged once. */
    private val staleStatusWarned = ConcurrentHashMap.newKeySet<String>()

    /**
     * Cached request toggles. The collectors in [start] keep them current;
     * the defaults fail closed for the risky kind (screenshots) and open for
     * the kind that still needs a user tap (files).
     */
    @Volatile
    private var allowFileRequests = true

    @Volatile
    private var allowScreenshotRequests = false

    /** Battery receiver registered while the relay socket is open. */
    private var statusReceiver: BroadcastReceiver? = null
    private var statusContext: Context? = null
    private var statusPeerKey: ByteArray? = null

    /** Guards the status publisher's lifecycle and debounce state. */
    private val statusLock = Any()
    private var lastStatusSample: BatterySample? = null
    private var lastStatusSentAt = 0L

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
            // Request toggles are cached here so the WS thread never blocks
            // on DataStore; the initial values are the fail-closed defaults.
            launch {
                TransferSettingsRepository.observeAllowFileRequests(appCtx)
                    .catch { Log.w(TAG, "File-request setting unavailable: ${it.message}") }
                    .collect { allowFileRequests = it }
            }
            launch {
                TransferSettingsRepository.observeAllowScreenshotRequests(appCtx)
                    .catch { Log.w(TAG, "Screenshot-request setting unavailable: ${it.message}") }
                    .collect { allowScreenshotRequests = it }
            }
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
                startStatusPublisher(context, peerCryptoKey)
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
                stopStatusPublisher()
                ClipboardSyncManager.onRelayDisconnected()
            }

            override fun onFailure(context: Context) {
                _peers.value = emptyList()
                stopStatusPublisher()
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
        stopStatusPublisher()
        _peers.value = emptyList()
        ClipboardSyncManager.onRelayDisconnected()
    }

    private fun handleWsMessage(context: Context, relayToken: String, peerCryptoKey: ByteArray, text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (json.optString("type")) {
            "peers" -> {
                val peersArray = json.optJSONArray("peers") ?: return
                val statusKey = ClusterCrypto.deriveStatusKey(peerCryptoKey)
                val decoded = PeerStatus.decodePeers(peersArray, statusKey, deviceId)
                decoded.malformedStatusIds.forEach { id ->
                    // Fail closed and stay quiet: one line per device, ever.
                    if (statusWarned.add(id)) {
                        Log.w(TAG, "Ignoring malformed status blob from $id")
                    }
                }
                decoded.staleStatusIds.forEach { id ->
                    // A replayed blob keeps the peer entry but loses the
                    // battery fields; one line per device, ever.
                    if (staleStatusWarned.add(id)) {
                        Log.w(TAG, "Ignoring stale status blob from $id")
                    }
                }
                _peers.value = decoded.peers
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
        request: RequestContext? = null,
    ): Job? {
        return outboundSender.sendFilesV3(context, targets, uris, offline, deviceId, request)
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

        // Requests carry their own trust gate (an untrusted requester gets a
        // decline, not silence) and their own sender key, so they are
        // dispatched before the generic sender_id gate. Browse and fetch do
        // the same: their envelopes name the requester and target directly,
        // and their target-side gate is the trusted-device list (a consent
        // capability), not the generic only-trusted toggle.
        when (kind) {
            "request" -> {
                handleIncomingRequest(context, relayToken, peerCryptoKey, inner)
                return
            }
            "request_declined" -> {
                handleRequestDeclined(context, inner)
                return
            }
            "browse" -> {
                handleIncomingBrowse(context, inner)
                return
            }
            "browse_listing" -> {
                handleBrowseListing(inner)
                return
            }
            "fetch" -> {
                handleIncomingFetch(context, peerCryptoKey, inner)
                return
            }
        }

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
     * Sealed `request`: a peer asks this phone for files or the latest
     * screenshot. Every refusal answers with a sealed `request_declined` so
     * the requester is never left hanging; the log line carries the request
     * id and reason only, never the grant.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun handleIncomingRequest(
        context: Context,
        relayToken: String,
        peerCryptoKey: ByteArray,
        inner: JSONObject,
    ) {
        val request = RequestCodec.parseRequest(inner) ?: return
        // A request addressed elsewhere (or to ourselves) is not ours to serve.
        // trustId covers a desktop requester whose stable origin_id is this
        // device even though its ephemeral requester_id is not.
        if (request.targetId != deviceId || request.trustId == deviceId) return

        // The trust list is keyed by stable device ids, so a desktop CLI that
        // asks under an ephemeral reqdev- id is judged by the origin_id it
        // carries. origin_id is a claim inside the E2E-sealed envelope: the
        // seal proves a cluster member sent it, exactly like an offer's
        // sender_id claim, and the trust list decides whether to believe it.
        // Routing, the grant, and the decline address keep using requesterId.
        if (!acceptsInbound(context, InboundKind.REQUEST, request.trustId)) {
            sendDecline(context, request.requesterId, request.requestId, DeclineReason.NOT_ALLOWED)
            return
        }
        if (requestDeduper.seen(request.requestId)) return
        // parseRequest already clamps the window; this covers the moment
        // between parsing and acting. `now >= expiresTs` is expired everywhere.
        if (RequestCodec.isExpired(request.expiresTs)) {
            sendDecline(context, request.requesterId, request.requestId, DeclineReason.EXPIRED)
            return
        }

        when (request.kind) {
            // The old picker flow is gone. Old clients still send kind
            // "files"; answer so they are not left waiting.
            RequestKind.FILES -> sendDecline(
                context, request.requesterId, request.requestId, DeclineReason.UNSUPPORTED,
            )
            RequestKind.SCREENSHOT -> handleScreenshotRequest(context, request)
        }
    }

    /** Screenshot requests are served automatically when the toggle is on. */
    private suspend fun handleScreenshotRequest(context: Context, request: RequestEnvelope) {
        if (!allowScreenshotRequests) {
            sendDecline(context, request.requesterId, request.requestId, DeclineReason.NOT_ALLOWED)
            return
        }
        // The stable trust id, not the ephemeral requester id, so a desktop
        // CLI cannot rotate reqdev- ids to bypass the interval.
        val now = System.currentTimeMillis()
        if (!screenshotRateLimiter.isAllowed(request.trustId, now)) {
            sendDecline(context, request.requesterId, request.requestId, DeclineReason.BUSY)
            return
        }
        if (!tryAcquireRequest(context, request, notifId = null)) {
            sendDecline(context, request.requesterId, request.requestId, DeclineReason.BUSY)
            return
        }
        try {
            val screenshot = ScreenshotSource.latestScreenshot(context)
            if (screenshot == null) {
                sendDecline(
                    context, request.requesterId, request.requestId,
                    DeclineReason.UNSUPPORTED,
                )
                return
            }
            screenshotRateLimiter.record(request.trustId, now)
            val targetName = _peers.value.firstOrNull { it.id == request.requesterId }?.name
                ?: singleLineDisplayName(request.requesterName)
            outboundSender.sendFilesV3(
                context = context,
                targets = listOf(ClusterPeer(request.requesterId, targetName)),
                uris = listOf(screenshot),
                offline = false,
                deviceId = deviceId,
                request = RequestContext(request.requestId, request.grant),
            )
            RequestNotifications.showScreenshotSent(
                context, request.requesterName, request.trustId,
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Screenshot request ${request.requestId} failed: ${t.message}")
            sendDecline(
                context, request.requesterId, request.requestId,
                DeclineReason.UNSUPPORTED,
            )
        } finally {
            releaseRequest(context, request.requestId)
        }
    }

    private fun handleRequestDeclined(context: Context, inner: JSONObject) {
        val decline = RequestCodec.parseDecline(inner) ?: return
        // The decline payload is sealed but carries no sender id, so any
        // cluster member can produce one. The outstanding-request map is the
        // only gate: a decline for a request this phone never sent (including
        // a reflected request aimed at a victim) finds nothing and is dropped.
        if (!outstandingRequests.take(decline.requestId)) return
        // An open BrowseScreen shows the reason in place; the notification is
        // the fallback when no screen is listening.
        _browseDeclines.tryEmit(decline)
        RequestNotifications.showDeclined(context, decline.requestId, decline.reason)
    }

    private suspend fun sendDecline(
        context: Context,
        targetId: String,
        requestId: String,
        reason: DeclineReason,
    ) {
        val inner = RequestCodec.buildDecline(requestId, reason, System.currentTimeMillis())
        val sent = sendSealed(context, targetId, "request_declined", inner)
        Log.i(
            TAG,
            "Request $requestId declined (${reason.wire})" + if (sent) "" else " (not delivered)",
        )
    }

    /**
     * Claims the single request slot. An expired lease is reclaimed here, so
     * a request the user never answered cannot wedge the phone forever; the
     * displaced lease's notification id is released with it.
     */
    private fun tryAcquireSlot(
        context: Context,
        requestId: String,
        expiresTs: Long,
        trustId: String,
        notifId: Int?,
    ): Boolean {
        val result = requestSlot.acquire(
            RequestSlot.Lease(
                id = requestId,
                expiresTs = expiresTs,
                trustId = trustId,
                notifId = notifId,
            ),
        )
        if (!result.acquired) return false
        releaseLeaseNotification(context, result.displaced)
        return true
    }

    private fun tryAcquireRequest(
        context: Context,
        request: RequestEnvelope,
        notifId: Int?,
    ): Boolean = tryAcquireSlot(
        context = context,
        requestId = request.requestId,
        expiresTs = request.expiresTs,
        trustId = request.trustId,
        notifId = notifId,
    )

    private fun releaseRequest(context: Context, requestId: String) {
        releaseLeaseNotification(context, requestSlot.release(requestId))
    }

    /** Cancels and frees the notification id owned by a released lease. */
    private fun releaseLeaseNotification(context: Context, lease: RequestSlot.Lease?) {
        val notifId = lease?.notifId ?: return
        cancelNotification(context, notifId)
        NotificationIds.release(notifId)
    }

    // ------------------------------------------------------ browse (target)

    /**
     * Sealed `browse`: a peer asks for one directory listing. Browse is a
     * consent capability — only a device the user explicitly trusted
     * (Settings -> Trusted devices) is served, whatever the only-trusted
     * toggle says — and every accepted request is additionally bounded by a
     * per-requester window, a phone-wide budget, and a worker cap. Every
     * refusal is a `browse_listing` carrying an error, never silence.
     *
     * With all-files access granted, the Storage root exposes the ENTIRE
     * shared storage, not just MediaStore rows. That is the explicit,
     * user-granted intent of the feature: the trust requirement is the gate
     * and the per-device cluster key (v4) is the hard boundary.
     */
    private suspend fun handleIncomingBrowse(context: Context, inner: JSONObject) {
        val browse = RequestCodec.parseBrowse(inner) ?: return
        if (browse.targetId != deviceId || browse.trustId == deviceId) return

        if (!isTrustedBrowser(context, browse.trustId)) {
            sendListingError(
                context, browse.requesterId, browse.requestId, browse.path, BrowseError.DENIED,
            )
            return
        }
        if (requestDeduper.seen(browse.requestId)) return

        val now = System.currentTimeMillis()
        if (!browseRateLimiter.isAllowed(browse.trustId, now) ||
            !globalBrowseBucket.isAllowed(now)
        ) {
            sendListingError(
                context, browse.requesterId, browse.requestId, browse.path,
                BrowseError.RATE_LIMITED,
            )
            return
        }
        // Browsing is a file request: the same toggle governs both.
        if (!allowFileRequests) {
            sendListingError(
                context, browse.requesterId, browse.requestId, browse.path, BrowseError.DENIED,
            )
            return
        }

        // A path outside every root cannot exist; answer not_found instead of
        // querying for it. Storage is only a root while all-files access
        // makes the whole shared volume readable, and a Storage path that
        // fails the dotfile/segment policy is treated the same as an unknown
        // root: neither could have come from a listing.
        val backend = if (browse.path.isEmpty()) {
            null
        } else {
            BrowseListingBuilder.backendFor(browse.path)
        }
        if (browse.path.isNotEmpty() && backend == null) {
            sendListingError(
                context, browse.requesterId, browse.requestId, browse.path,
                BrowseError.NOT_FOUND,
            )
            return
        }
        if (backend == BrowseBackend.STORAGE && !hasAllFilesAccess()) {
            sendListingError(
                context, browse.requesterId, browse.requestId, browse.path,
                BrowseError.NOT_FOUND,
            )
            return
        }

        // Claim a worker before touching MediaStore. Two listings already in
        // flight means the phone is saturated: answer rate_limited instead of
        // queueing more cursor work behind them.
        if (!browseWorkers.tryAcquire()) {
            sendListingError(
                context, browse.requesterId, browse.requestId, browse.path,
                BrowseError.RATE_LIMITED,
            )
            return
        }
        // Recorded only once the request will actually be served, so a
        // refused browse does not spend the requester's or the phone's budget.
        browseRateLimiter.record(browse.trustId, now)
        globalBrowseBucket.record(now)

        val listing = try {
            BrowseSource.list(context, browse.path)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Browse ${browse.requestId} failed: ${t.javaClass.simpleName}")
            sendListingError(
                context, browse.requesterId, browse.requestId, browse.path,
                BrowseError.UNSUPPORTED,
            )
            return
        } finally {
            browseWorkers.release()
        }
        val reply = RequestCodec.buildBrowseListing(
            requestId = browse.requestId,
            path = browse.path,
            entries = listing.entries,
            truncated = listing.truncated,
            ts = System.currentTimeMillis(),
        )
        sendSealed(context, browse.requesterId, "browse_listing", reply)
    }

    private suspend fun sendListingError(
        context: Context,
        targetId: String,
        requestId: String,
        path: String,
        error: BrowseError,
    ) {
        val reply = RequestCodec.buildBrowseListing(
            requestId = requestId,
            path = path,
            error = error,
            ts = System.currentTimeMillis(),
        )
        sendSealed(context, targetId, "browse_listing", reply)
    }

    // ------------------------------------------------------- fetch (target)

    /**
     * Sealed `fetch`: the requester picked files from a listing. The target
     * resolves every path, enforces the batch cap, and hands the URIs to the
     * existing v3 sender with the request echo. Fetch is a consent capability
     * like browse: only an explicitly trusted device is served. One fetch
     * runs at a time; the lease is held until the send job finishes, so a
     * second fetch cannot cancel the first mid-flight.
     *
     * With all-files access granted, a `Storage/...` path can name ANY file in
     * shared storage, not just a MediaStore row. That is the explicit,
     * user-granted intent of the feature: the trust requirement is the gate
     * and the per-device cluster key (v4) is the hard boundary. Every path is
     * still canonicalized inside the storage root, so an escape resolves to
     * not_found exactly like a missing file.
     */
    private suspend fun handleIncomingFetch(
        context: Context,
        peerCryptoKey: ByteArray,
        inner: JSONObject,
    ) {
        val fetch = RequestCodec.parseFetch(inner) ?: return
        if (fetch.targetId != deviceId || fetch.trustId == deviceId) return

        if (!isTrustedBrowser(context, fetch.trustId)) {
            sendDecline(context, fetch.requesterId, fetch.requestId, DeclineReason.NOT_ALLOWED)
            return
        }
        if (requestDeduper.seen(fetch.requestId)) return
        // Expiry gates NEW fetches only. Once accepted, the lease below is
        // held until the send job completes, however long that takes.
        if (RequestCodec.isExpired(fetch.expiresTs)) {
            sendDecline(context, fetch.requesterId, fetch.requestId, DeclineReason.EXPIRED)
            return
        }
        // The grant binds the request to this target and expiry; the seal
        // already proves cluster membership, and this proves the fetch was
        // minted for this exact request rather than replayed.
        if (!ClusterCrypto.verifyRequestGrant(
                peerCryptoKey,
                fetch.requestId,
                fetch.requesterId,
                fetch.targetId,
                fetch.expiresTs,
                fetch.grant,
            )
        ) {
            sendDecline(context, fetch.requesterId, fetch.requestId, DeclineReason.NOT_ALLOWED)
            return
        }
        if (!allowFileRequests) {
            sendDecline(context, fetch.requesterId, fetch.requestId, DeclineReason.NOT_ALLOWED)
            return
        }
        if (!tryAcquireSlot(
                context = context,
                requestId = fetch.requestId,
                // Held until the send job completes: a 4 GiB send can outlive
                // the grant's five-minute window, and expiry only gates new
                // fetches (checked above).
                expiresTs = RequestSlot.HELD_UNTIL_RELEASED,
                trustId = fetch.trustId,
                notifId = null,
            )
        ) {
            sendDecline(context, fetch.requesterId, fetch.requestId, DeclineReason.BUSY)
            return
        }

        try {
            val resolved = fetch.paths.map { BrowseSource.resolve(context, it) }
            val rejection = FetchValidation.validate(
                paths = fetch.paths,
                sizes = resolved.map { it?.size },
            )
            if (rejection != null) {
                sendDecline(context, fetch.requesterId, fetch.requestId, rejection.reason)
                releaseRequest(context, fetch.requestId)
                return
            }
            val uris = resolved.mapNotNull { it?.uri }
            val targetName = _peers.value.firstOrNull { it.id == fetch.requesterId }?.name
                ?: singleLineDisplayName(fetch.requesterName)
            // Tell the user what left the phone before it starts moving: the
            // peer label and the count, never the file names.
            RequestNotifications.showFetchStarted(
                context = context,
                requesterName = fetch.requesterName,
                stableId = fetch.trustId,
                fileCount = fetch.paths.size,
            )
            val job = outboundSender.sendFilesV3(
                context = context,
                targets = listOf(ClusterPeer(fetch.requesterId, targetName)),
                uris = uris,
                offline = false,
                deviceId = deviceId,
                request = RequestContext(fetch.requestId, fetch.grant),
            )
            if (job == null) {
                releaseRequest(context, fetch.requestId)
            } else {
                // Hold the slot for the whole send, however long it runs: a
                // second fetch while this one streams is answered BUSY instead
                // of cancelling it.
                val appCtx = context.applicationContext
                job.invokeOnCompletion { releaseRequest(appCtx, fetch.requestId) }
            }
        } catch (c: CancellationException) {
            releaseRequest(context, fetch.requestId)
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Fetch ${fetch.requestId} failed: ${t.javaClass.simpleName}")
            releaseRequest(context, fetch.requestId)
            sendDecline(context, fetch.requesterId, fetch.requestId, DeclineReason.UNSUPPORTED)
        }
    }

    // --------------------------------------------------- browse (requester)

    /**
     * Asks [target] for the listing at [path] and waits one round trip. The
     * reply is matched by the locally generated request id; a timeout or a
     * dropped socket yields null and the screen shows a retryable error.
     */
    internal suspend fun browseListing(
        context: Context,
        target: ClusterPeer,
        path: String,
    ): BrowseListingEnvelope? {
        if (!RelayConnection.isConnected) return null
        if (!RequestCodec.isBrowsePath(path)) return null
        val token = KeyRepository.getClusterToken(context)
        if (token.isBlank()) return null

        val now = System.currentTimeMillis()
        val requestId = ClusterCrypto.newRequestId()
        val inner = RequestCodec.buildBrowse(
            requestId = requestId,
            requesterId = deviceId,
            // The phone always asks under its real id; the stable origin is
            // the same value, so a target's trust gate sees it either way.
            originId = deviceId,
            requesterName = KeyRepository.getDeviceName(context),
            targetId = target.id,
            path = path,
            ts = now,
        )
        val deferred = CompletableDeferred<BrowseListingEnvelope>()
        pendingBrowseListings.add(requestId, path, deferred)
        return try {
            if (!sendSealed(context, target.id, "browse", inner)) {
                null
            } else {
                withTimeoutOrNull(BROWSE_TIMEOUT_MS) { deferred.await() }
            }
        } finally {
            pendingBrowseListings.remove(requestId)
        }
    }

    private fun handleBrowseListing(inner: JSONObject) {
        val listing = RequestCodec.parseBrowseListing(inner) ?: return
        // A reply for a different directory is not the answer to this
        // request. Match the path before consuming the entry: the real reply
        // may still arrive, and the requester's timeout cleans up if it does
        // not.
        val pending = pendingBrowseListings.take(listing.requestId, listing.path) ?: return
        pending.deferred.complete(listing)
    }

    /**
     * Sends a sealed `fetch` for paths the user picked in a listing. Returns
     * the request id on success so the screen can match a decline, or null
     * when the frame never left the phone.
     */
    suspend fun requestFetch(
        context: Context,
        target: ClusterPeer,
        paths: List<String>,
    ): String? {
        if (!RelayConnection.isConnected) return null
        if (paths.isEmpty() || paths.size > RequestCodec.MAX_FETCH_PATHS) return null
        if (paths.any { !RequestCodec.isRelativePath(it) }) return null
        val token = KeyRepository.getClusterToken(context)
        if (token.isBlank()) return null
        val (_, peerCryptoKey) = Crypto.deriveSubKeys(token)

        val now = System.currentTimeMillis()
        val requestId = ClusterCrypto.newRequestId()
        val expiresTs = now + RequestCodec.REQUEST_TTL_MS
        val grant = ClusterCrypto.computeRequestGrant(
            peerCryptoKey, requestId, deviceId, target.id, expiresTs,
        )
        val inner = RequestCodec.buildFetch(
            requestId = requestId,
            requesterId = deviceId,
            originId = deviceId,
            requesterName = KeyRepository.getDeviceName(context),
            targetId = target.id,
            paths = paths,
            expiresTs = expiresTs,
            grant = grant,
            ts = now,
        )
        // Record before sending so a fast decline cannot beat the map; drop
        // the entry again if the socket refused the frame.
        outstandingRequests.add(requestId, expiresTs, now)
        val sent = sendSealed(context, target.id, "fetch", inner)
        if (!sent) {
            outstandingRequests.remove(requestId)
            return null
        }
        return requestId
    }

    /**
     * Starts publishing the encrypted battery status while the socket is up.
     * ACTION_BATTERY_CHANGED is sticky and event-driven: registering delivers
     * the current value immediately and the system pushes later changes, so
     * there is no polling and no wakeup timer.
     */
    private fun startStatusPublisher(context: Context, peerCryptoKey: ByteArray) {
        val appCtx = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
                val sample = BatteryStatus.fromExtras(
                    level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                    scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                    status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
                ) ?: return
                publishStatus(sample)
            }
        }
        var initialSample: BatterySample? = null
        synchronized(statusLock) {
            unregisterStatusReceiverLocked()
            // Set the key before registering: the sticky broadcast may be
            // delivered before registerReceiver returns.
            statusContext = appCtx
            statusPeerKey = peerCryptoKey.copyOf()
            val registered = runCatching {
                ContextCompat.registerReceiver(
                    appCtx,
                    receiver,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }.isSuccess
            if (registered) {
                statusReceiver = receiver
                // API 34+ does not deliver the sticky initial value to a
                // context-registered receiver on registration — query it
                // directly so the first sample publishes deterministically
                // on every Android version.
                val sticky = runCatching {
                    appCtx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                }.getOrNull()
                if (sticky?.action == Intent.ACTION_BATTERY_CHANGED) {
                    initialSample = BatteryStatus.fromExtras(
                        level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                        scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                        status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
                    )
                }
            } else {
                statusContext = null
                statusPeerKey = null
            }
        }
        // Publish outside the lock — publishStatus takes statusLock itself.
        initialSample?.let { publishStatus(it) }
    }

    /** Unregisters the battery receiver; safe to call when none is registered. */
    private fun stopStatusPublisher() {
        synchronized(statusLock) {
            unregisterStatusReceiverLocked()
        }
    }

    private fun unregisterStatusReceiverLocked() {
        val receiver = statusReceiver
        val appCtx = statusContext
        statusReceiver = null
        statusContext = null
        statusPeerKey = null
        lastStatusSample = null
        lastStatusSentAt = 0L
        if (receiver != null && appCtx != null) {
            runCatching { appCtx.unregisterReceiver(receiver) }
        }
    }

    private fun publishStatus(sample: BatterySample) {
        val key = statusPeerKey ?: return
        synchronized(statusLock) {
            val now = System.currentTimeMillis()
            if (!BatteryStatus.shouldPublish(lastStatusSample, sample, lastStatusSentAt, now)) {
                return
            }
            val blob = runCatching {
                ClusterCrypto.encodeStatusBlob(
                    ClusterCrypto.deriveStatusKey(key),
                    deviceId,
                    sample.percent,
                    sample.charging,
                    now,
                )
            }.getOrNull() ?: return
            val frame = JSONObject().apply {
                put("type", "status")
                put("v", 1)
                put("status", blob)
            }
            if (RelayConnection.send(frame.toString())) {
                lastStatusSample = sample
                lastStatusSentAt = now
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

    /**
     * Browse and fetch are a consent capability, not passive receive: a
     * listing or a file pull is served only to a device the user explicitly
     * trusted (Settings -> Trusted devices), whatever the "only trusted
     * devices" toggle says. The toggle governs passive kinds (clipboard,
     * offers); browsing needs an explicit grant. The claimed origin id
     * (`origin_id ?: requester_id`) is all that is available today;
     * per-device keys (v4) will replace the claim-based check.
     *
     * Fails closed: a DataStore read error means no browsing.
     */
    private suspend fun isTrustedBrowser(context: Context, trustId: String): Boolean {
        val trustedIds = runCatching {
            TrustedDeviceRepository.observe(context).first().map { it.id }.toSet()
        }.getOrDefault(emptySet())
        val allowed = InboundPolicy.shouldServeBrowse(trustId, deviceId, trustedIds)
        if (!allowed) {
            Log.i(
                TAG,
                "Rejected browse/fetch from ${trustId.ifBlank { "(no id)" }}: not a trusted device",
            )
        }
        return allowed
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
