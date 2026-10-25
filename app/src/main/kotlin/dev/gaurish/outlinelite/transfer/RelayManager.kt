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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private const val NOTIF_RECV_ID = 8801
    private const val NOTIF_SEND_ID = 8802
    private const val CHANNEL_ID = "file_transfers"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var ws: WebSocket? = null
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

    private val pendingAccepts = ConcurrentHashMap<String, (JSONObject) -> Unit>()

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
            val deviceName = KeyRepository.getDeviceName(context)
            val url = "ws://$RELAY_HOST/ws?device_id=$deviceId&name=${Uri.encode(deviceName)}&token=${Uri.encode(token)}"
            val request = Request.Builder().url(url).build()

            Log.i(TAG, "Connecting to in-tunnel relay")
            ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "Connected to in-tunnel file relay")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleWsMessage(context, token, text)
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

    private fun disconnectRelay() {
        reconnectJob?.cancel()
        reconnectJob = null
        ws?.close(1000, "Disconnect")
        ws = null
        _peers.value = emptyList()
    }

    private fun handleWsMessage(context: Context, token: String, text: String) {
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
                scope.launch { handleIncomingOffer(context, token, json) }
            }
            "transfer_accept" -> {
                val txId = json.optString("transfer_id")
                pendingAccepts[txId]?.invoke(json)
            }
            "transfer_reject" -> {
                val txId = json.optString("transfer_id")
                pendingAccepts[txId]?.invoke(json)
            }
        }
    }

    private suspend fun handleIncomingOffer(context: Context, token: String, offer: JSONObject) {
        val transferId = offer.getString("transfer_id")
        val senderId = offer.getString("from_id")
        val senderName = offer.optString("from_name", "Remote Device")
        val fileName = offer.getString("file_name")
        val fileSize = offer.optLong("file_size", 0L)
        val senderPubB64 = offer.getString("pub_key")

        val senderPubBytes = Base64.decode(senderPubB64, Base64.NO_WRAP)
        val keyPair = Crypto.generateKeyPair()
        val aesKey = Crypto.deriveSharedSecret(keyPair, senderPubBytes)

        val acceptMsg = JSONObject().apply {
            put("type", "transfer_accept")
            put("target_id", senderId)
            put("transfer_id", transferId)
            put("pub_key", Base64.encodeToString(Crypto.exportPublicKey(keyPair.public), Base64.NO_WRAP))
        }
        ws?.send(acceptMsg.toString())

        _transferState.value = TransferState.Receiving(senderName, fileName, 0f, 0f)
        val appCtx = context.applicationContext
        showNotification(appCtx, NOTIF_RECV_ID, "Receiving $fileName", "From $senderName...", ongoing = true, progress = -2)

        // Stream and save file directly to Downloads
        val pipeUrl = "http://$RELAY_HOST/pipe?transfer_id=$transferId&role=receiver&token=${Uri.encode(token)}"
        val request = Request.Builder().url(pipeUrl).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Pipe error: ${e.message}")
                _transferState.value = TransferState.Error("Transfer failed: ${e.message}")
                showNotification(appCtx, NOTIF_RECV_ID, "Transfer Failed", "Connection error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    _transferState.value = TransferState.Error("Pipe rejected with code ${response.code}")
                    showNotification(appCtx, NOTIF_RECV_ID, "Transfer Failed", "Relay returned code ${response.code}")
                    return
                }

                val body = response.body ?: return
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloadsDir.mkdirs()
                val outFile = File(downloadsDir, fileName)
                var totalRead = 0L

                try {
                    val inStream = body.byteStream()
                    val outStream = FileOutputStream(outFile)
                    val startTime = System.currentTimeMillis()
                    var lastUpdate = 0L

                    val lenBytes = ByteArray(4)
                    val nonceBytes = ByteArray(12)

                    while (true) {
                        if (!readFully(inStream, lenBytes, 4)) break
                        val chunkLen = ByteBuffer.wrap(lenBytes).order(ByteOrder.BIG_ENDIAN).int
                        if (chunkLen == 0) break // EOF
                        if (chunkLen < 0 || chunkLen > 10 * 1024 * 1024) {
                            throw IOException("Invalid chunk length: $chunkLen bytes")
                        }

                        if (!readFully(inStream, nonceBytes, 12)) break
                        val cipherBuf = ByteArray(chunkLen)
                        if (!readFully(inStream, cipherBuf, chunkLen)) break

                        val plain = Crypto.decryptChunk(aesKey, nonceBytes, cipherBuf)
                        outStream.write(plain)
                        totalRead += plain.size

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 150) {
                            lastUpdate = now
                            val elapsed = (now - startTime) / 1000f
                            val speedMb = if (elapsed > 0) (totalRead / (1024f * 1024f)) / elapsed else 0f
                            val progress = if (fileSize > 0) (totalRead.toFloat() / fileSize.toFloat()) else 0.5f

                            _transferState.value = TransferState.Receiving(senderName, fileName, progress, speedMb)
                            showNotification(appCtx, NOTIF_RECV_ID, "Receiving $fileName", "%.1f MB/s - %.0f%%".format(speedMb, progress * 100f), ongoing = true, progress = (progress * 100).toInt())
                        }
                    }

                    outStream.flush()
                    outStream.close()

                    if (fileSize > 0 && totalRead < fileSize) {
                        throw IOException("Incomplete transfer: received $totalRead of $fileSize bytes")
                    }
                    if (totalRead > 0 || fileSize == 0L) {
                        _transferState.value = TransferState.Completed("Received $fileName successfully")
                        showNotification(appCtx, NOTIF_RECV_ID, "Transfer Complete", "Saved $fileName to Downloads")
                    } else {
                        throw IOException("Received 0 bytes")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error decrypting stream: ${t.message}", t)
                    _transferState.value = TransferState.Error("Transfer error: ${t.message}")
                    showNotification(appCtx, NOTIF_RECV_ID, "Transfer Failed", "Failed to receive $fileName")
                    try {
                        if (outFile.exists() && (totalRead == 0L || fileSize > totalRead)) {
                            outFile.delete()
                        }
                    } catch (_: Exception) {}
                }
            }
        })
    }

    fun sendFile(context: Context, target: ClusterPeer, uri: Uri) {
        val appCtx = context.applicationContext
        if (ws == null) {
            _transferState.value = TransferState.Error("File sharing not connected")
            return
        }

        scope.launch {
            val token = runCatching { KeyRepository.getClusterToken(appCtx) }.getOrDefault("")
            if (token.isBlank()) {
                _transferState.value = TransferState.Error("Cluster token not configured")
                return@launch
            }
            val (fileName, fileSize) = queryFileInfo(appCtx.contentResolver, uri)
            _transferState.value = TransferState.Connecting(target.name)

            val transferId = "tx-${System.currentTimeMillis()}"
            val keyPair = Crypto.generateKeyPair()

            val offer = JSONObject().apply {
                put("type", "transfer_offer")
                put("target_id", target.id)
                put("transfer_id", transferId)
                put("file_name", fileName)
                put("file_size", fileSize)
                put("pub_key", Base64.encodeToString(Crypto.exportPublicKey(keyPair.public), Base64.NO_WRAP))
            }

            var accepted = false
            var peerPubBytes: ByteArray? = null

            pendingAccepts[transferId] = { response ->
                if (response.optString("type") == "transfer_accept") {
                    val pubB64 = response.optString("pub_key")
                    peerPubBytes = Base64.decode(pubB64, Base64.NO_WRAP)
                    accepted = true
                } else {
                    _transferState.value = TransferState.Error("${target.name} declined the transfer")
                }
            }

            ws?.send(offer.toString())

            // Wait for acceptance
            val startWait = System.currentTimeMillis()
            while (!accepted && _transferState.value !is TransferState.Error) {
                if (System.currentTimeMillis() - startWait > 45_000L) {
                    pendingAccepts.remove(transferId)
                    _transferState.value = TransferState.Error("Transfer request timed out")
                    return@launch
                }
                delay(200)
            }
            pendingAccepts.remove(transferId)

            val pubBytes = peerPubBytes ?: return@launch
            val aesKey = Crypto.deriveSharedSecret(keyPair, pubBytes)

            // Connect to pipe as sender
            val pipeUrl = "http://$RELAY_HOST/pipe?transfer_id=$transferId&role=sender&token=${Uri.encode(token)}"
            showNotification(appCtx, NOTIF_SEND_ID, "Sending $fileName", "Connecting...", ongoing = true, progress = -2)

            val requestBody = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()

                override fun writeTo(sink: BufferedSink) {
                    val inStream = (if (uri.scheme == "file") {
                        try { File(uri.path ?: "").inputStream() } catch (_: Exception) { null }
                    } else {
                        appCtx.contentResolver.openInputStream(uri)
                    }) ?: return
                    val buffer = ByteArray(64 * 1024)
                    var sentBytes = 0L
                    var nonceCounter = 0L
                    val startTime = System.currentTimeMillis()
                    var lastUpdate = 0L

                    inStream.use { stream ->
                        while (true) {
                            val n = stream.read(buffer)
                            if (n <= 0) break

                            val nonce = ByteArray(12)
                            ByteBuffer.wrap(nonce).putLong(4, nonceCounter++)

                            val cipherChunk = Crypto.encryptChunk(aesKey, nonce, buffer, 0, n)

                            // 4-byte len
                            sink.writeInt(cipherChunk.size)
                            // 12-byte nonce
                            sink.write(nonce)
                            // Ciphertext + tag
                            sink.write(cipherChunk)
                            sink.flush()

                            sentBytes += n
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 150) {
                                lastUpdate = now
                                val elapsed = (now - startTime) / 1000f
                                val speedMb = if (elapsed > 0) (sentBytes / (1024f * 1024f)) / elapsed else 0f
                                val progress = if (fileSize > 0) (sentBytes.toFloat() / fileSize.toFloat()) else 0.5f

                                _transferState.value = TransferState.Sending(target.name, fileName, progress, speedMb)
                                showNotification(appCtx, NOTIF_SEND_ID, "Sending $fileName", "%.1f MB/s - %.0f%%".format(speedMb, progress * 100f), ongoing = true, progress = (progress * 100).toInt())
                            }
                        }
                    }

                    // Send 4-byte zero length sentinel for EOF
                    sink.writeInt(0)
                    sink.flush()
                }
            }

            val pipeReq = Request.Builder().url(pipeUrl).post(requestBody).build()
            try {
                client.newCall(pipeReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        _transferState.value = TransferState.Completed("Sent $fileName to ${target.name}")
                        showNotification(appCtx, NOTIF_SEND_ID, "Transfer Complete", "Sent $fileName to ${target.name}")
                    } else {
                        _transferState.value = TransferState.Error("Upload failed: code ${resp.code}")
                        showNotification(appCtx, NOTIF_SEND_ID, "Transfer Failed", "Upload failed: HTTP ${resp.code}")
                    }
                }
            } catch (e: Exception) {
                _transferState.value = TransferState.Error("Upload error: ${e.message}")
                showNotification(appCtx, NOTIF_SEND_ID, "Transfer Failed", "Upload error: ${e.message}")
            }
        }
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
