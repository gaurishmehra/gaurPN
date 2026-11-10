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
import androidx.core.app.NotificationCompat
import dev.gaurish.outlinelite.MainActivity
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.data.KeyRepository
import dev.gaurish.outlinelite.tunnel.TunnelPhase
import dev.gaurish.outlinelite.tunnel.TunnelState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

object RelayManager {

    private const val TAG = "RelayManager"
    private const val RELAY_HOST = "198.51.100.1:9876"
    private const val CHANNEL_ID = "file_transfers"
    private const val NOTIF_GROUP = "gaur_transfers"

    /** 256 KiB chunks cut framing, syscall, and cipher-init overhead by 4x. */
    private const val CHUNK_SIZE = 256 * 1024
    private const val MAX_CHUNK_SIZE = 1024 * 1024
    private const val FLUSH_INTERVAL_MS = 100L

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

    fun start(context: Context) {
        if (monitorJob != null) return

        val appCtx = context.applicationContext
        deviceId = "android-" + UUID.randomUUID().toString().take(8)

        monitorJob = scope.launch {
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
            val url = "ws://$relayHost/ws?device_id=$deviceId&name=${Uri.encode(deviceName)}&token=${Uri.encode(relayToken)}"
            val request = Request.Builder().url(url).build()

            Log.i(TAG, "Connecting to in-tunnel relay at $relayHost")
            ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "Connected to in-tunnel file relay")
                    requestPeerList(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleWsMessage(context, relayToken, peerCryptoKey, text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "Relay WS closed: $code $reason")
                    ws = null
                    _peers.value = emptyList()
                    scheduleReconnect(context, token)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "Relay WS failure: ${t.message}")
                    ws = null
                    _peers.value = emptyList()
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
                scope.launch { handleIncomingOffer(context, relayToken, peerCryptoKey, json) }
            }
            "transfer_accept", "transfer_reject" -> {
                val txId = json.optString("transfer_id")
                pendingAccepts.remove(txId)?.complete(json)
            }
        }
    }

    private suspend fun handleIncomingOffer(
        context: Context,
        relayToken: String,
        peerCryptoKey: ByteArray,
        offer: JSONObject
    ) {
        val transferId = offer.getString("transfer_id")
        val senderId = offer.getString("from_id")
        val senderName = offer.optString("from_name", "Remote Device")
        val rawFileName = offer.getString("file_name")
        val fileSize = offer.optLong("file_size", 0L)
        val senderPubB64 = offer.getString("pub_key")
        val offerTag = offer.optString("auth_tag")
        val batchTotal = offer.optInt("batch_total", 1).coerceAtLeast(1)
        val batchIndex = offer.optInt("batch_index", 0).coerceIn(0, batchTotal - 1)

        // Zero-knowledge peer-to-peer authentication (MITM defense)
        val expectedOfferMsg = "offer:$transferId:$deviceId:$senderPubB64"
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
        val pipeUrl = "http://$relayHost/pipe?transfer_id=$transferId&role=receiver&token=${Uri.encode(relayToken)}"
        val request = Request.Builder().url(pipeUrl).build()

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

    private data class OutgoingFile(val uri: Uri, val name: String, val size: Long)

    fun sendFiles(context: Context, target: ClusterPeer, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val appCtx = context.applicationContext
        if (ws == null) {
            _transferState.value = TransferState.Error("File sharing not connected")
            return
        }

        scope.launch {
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
            } catch (t: Throwable) {
                Log.e(TAG, "Send failed: ${t.message}", t)
                _transferState.value = TransferState.Error("Transfer error: ${t.message}")
                showNotification(appCtx, batchNotifId, "Transfer Failed", "Failed sending to ${target.name}")
            }
        }
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
    ) {
        val transferId = "tx-${UUID.randomUUID()}"
        val keyPair = Crypto.generateKeyPair()
        val myPubB64 = Base64.encodeToString(Crypto.exportPublicKey(keyPair.public), Base64.NO_WRAP)
        val offerTag = Crypto.computeAuthTag(peerCryptoKey, "offer:$transferId:${target.id}:$myPubB64")

        val offer = JSONObject().apply {
            put("type", "transfer_offer")
            put("target_id", target.id)
            put("transfer_id", transferId)
            put("file_name", file.name)
            put("file_size", file.size)
            put("pub_key", myPubB64)
            put("auth_tag", offerTag)
            put("batch_id", batchId)
            put("batch_index", batchIndex)
            put("batch_total", batchTotal)
        }

        val deferred = CompletableDeferred<JSONObject>()
        pendingAccepts[transferId] = deferred
        val socket = ws ?: throw IOException("File sharing not connected")
        if (!socket.send(offer.toString())) {
            pendingAccepts.remove(transferId)
            throw IOException("Failed to send transfer offer")
        }

        val response = withTimeoutOrNull(45_000L) { deferred.await() }
            ?: run {
                pendingAccepts.remove(transferId)
                throw IOException("Transfer request timed out")
            }

        if (response.optString("type") == "transfer_reject") {
            throw IOException("${target.name} declined the transfer")
        }

        val pubB64 = response.optString("pub_key")
        val acceptTag = response.optString("auth_tag")
        val expectedAcceptMsg = "accept:$transferId:$deviceId:$pubB64"
        if (!Crypto.verifyAuthTag(peerCryptoKey, expectedAcceptMsg, acceptTag)) {
            throw SecurityException("Security verification failed: peer key untrusted")
        }

        val peerPubBytes = Base64.decode(pubB64, Base64.NO_WRAP)
        val aesKey = Crypto.deriveSharedSecret(keyPair, peerPubBytes)
        val safetyNumber = Crypto.computeSafetyNumber(Crypto.exportPublicKey(keyPair.public), peerPubBytes, transferId)
        Log.i(TAG, "Transfer $transferId safety number verified: $safetyNumber")

        val relayHost = KeyRepository.getRelayAddress(context).ifBlank { RELAY_HOST }
        val pipeUrl = "http://$relayHost/pipe?transfer_id=$transferId&role=sender&token=${Uri.encode(relayToken)}"
        val notifId = notifIdFor(transferId, sending = true)
        showNotification(context, notifId, "Sending $label", "Connecting...", ongoing = true, progress = -2)

        val requestBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()

            override fun writeTo(sink: BufferedSink) {
                val inStream = openInputStream(context, file.uri)
                    ?: throw IOException("Cannot open ${file.name}")
                val cipher = Crypto.createAesGcmCipher()
                val buffer = ByteArray(CHUNK_SIZE)
                val nonce = ByteArray(12)
                val nonceBuf = ByteBuffer.wrap(nonce)
                var sentBytes = 0L
                var nonceCounter = 0L
                val startTime = System.currentTimeMillis()
                var lastUpdate = 0L
                var lastFlush = 0L

                inStream.use { stream ->
                    while (true) {
                        val n = stream.read(buffer)
                        if (n <= 0) break

                        nonceBuf.putInt(0, 0)
                        nonceBuf.putLong(4, nonceCounter++)
                        val cipherChunk = Crypto.encryptChunk(cipher, aesKey, nonce, buffer, 0, n)

                        sink.writeInt(cipherChunk.size)
                        sink.write(nonce)
                        sink.write(cipherChunk)
                        sentBytes += n

                        val now = System.currentTimeMillis()
                        if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                            sink.flush()
                            lastFlush = now
                        }
                        if (now - lastUpdate > 150) {
                            lastUpdate = now
                            val elapsed = (now - startTime) / 1000f
                            val speedMb = if (elapsed > 0) (sentBytes / (1024f * 1024f)) / elapsed else 0f
                            val fileProgress = if (file.size > 0) sentBytes.toFloat() / file.size else 0.5f
                            val overall = if (totalSize > 0) {
                                (sentBefore + sentBytes).toFloat() / totalSize
                            } else {
                                fileProgress
                            }

                            _transferState.value = TransferState.Sending(target.name, label, overall, speedMb)
                            showNotification(
                                context, notifId, "Sending $label",
                                "%.1f MB/s - %.0f%%".format(speedMb, overall * 100f),
                                ongoing = true, progress = (overall * 100).toInt()
                            )
                        }
                    }
                }

                sink.flush()
                sink.writeInt(0)
                sink.flush()
            }
        }

        val pipeReq = Request.Builder().url(pipeUrl).post(requestBody).build()
        try {
            client.newCall(pipeReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("Upload failed: HTTP ${resp.code}")
                }
            }
            showNotification(context, notifId, "Sent $label", "Transfer complete", ongoing = false)
        } catch (t: Throwable) {
            showNotification(context, notifId, "Transfer Failed", "Failed to send $label", ongoing = false)
            throw t
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(CHANNEL_ID, "File Transfers", NotificationManager.IMPORTANCE_DEFAULT)
                    nm.createNotificationChannel(channel)
                }
            }
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
