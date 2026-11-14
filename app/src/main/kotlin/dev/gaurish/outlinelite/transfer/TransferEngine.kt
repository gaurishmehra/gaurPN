package dev.gaurish.outlinelite.transfer

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.data.KeyRepository
import dev.gaurish.outlinelite.data.TransferHistoryEntry
import dev.gaurish.outlinelite.data.TransferHistoryRepository
import dev.gaurish.outlinelite.data.TransferSettingsRepository
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Inbound file transfers: the v1 streaming pipe, the v2 chunked resume
 * flow, and the v3 sealed offer. Part-file and resume-state management,
 * integrity checks, and completion receipts live here; the facade keeps
 * the trust gate, sealed dispatch, and notification posting.
 */
internal class TransferEngine(
    private val scope: CoroutineScope,
    private val transferState: MutableStateFlow<TransferState>,
    private val registry: TransferRegistry,
) {

    private companion object {
        const val TAG = "RelayManager"

        /** v1 pipe chunks are at most 1 MiB, plus the 16-byte GCM tag. */
        const val MAX_CHUNK_SIZE = 1024 * 1024

        /** Min ms between two v1 pipe progress updates. */
        const val V1_PROGRESS_INTERVAL_MS = 150L

        /** v1 part-file write buffer. */
        const val V1_PIPE_BUFFER_BYTES = 1 shl 20

        /** Chunk-count sanity bound for a v2 offer. */
        const val MAX_CHUNK_COUNT = 65536
    }

    suspend fun handleSealedOfferV3(
        context: Context,
        relayToken: String,
        peerCryptoKey: ByteArray,
        senderId: String,
        senderName: String,
        inner: JSONObject,
        deviceId: String,
    ) {
        val transferId = inner.optString("transfer_id")
        val sessionId = inner.optString("session_id")
        val rawName = inner.optString("file_name")
        val fileHash = inner.optString("file_hash")
        val fileSize = inner.optLong("file_size", 0L)
        val chunkSize = inner.optInt("chunk_size", 0)
        val chunkCount = inner.optInt("chunk_count", 0)
        val chunkHashesArray = inner.optJSONArray("chunk_hashes")
        if (!Crypto.isTransferId(transferId) || sessionId.isBlank() || rawName.isBlank() ||
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

        // v3 offers are sealed, so a malformed id is dropped silently like
        // every other malformed v3 field; there is no unsealed v3 reject.
        val staging = TransferStaging.forTransfer(downloadsDir, cleanName, transferId)
        if (staging == null) {
            Log.w(TAG, "Rejecting sealed offer with an unsafe transfer id or staging path")
            return
        }
        val partFile = staging.part
        val stateFile = staging.state
        // Resume like v2: reuse the staged .part/.state while it still
        // matches this transfer; a changed file under the same transfer id
        // (content hash differs) resets cleanly.
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

        val digest = ChunkTransport.FileDigest(fileHash, chunkHashes, chunkCount, fileSize)
        val missing = (0 until chunkCount).filter { it !in state.have }
        val fileKey = Crypto.deriveFileKey(peerCryptoKey, transferId)
        val relayHost = KeyRepository.getRelayAddress(context).ifBlank { RelayManager.RELAY_HOST }
        val notifId = NotificationIds.acquire(transferId, sending = false)
        val self = coroutineContext[Job]
        val registration = registry.register(transferId) { self?.cancel() }

        try {
            ChunkTransport.downloadMissing(
                context = context,
                client = RelayConnection.client,
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
                transferState.value = TransferState.Receiving(senderName, cleanName, progress, 0f)
                RelayManager.showNotification(
                    context,
                    notifId,
                    "Receiving $cleanName",
                    "%.0f%%".format(progress * 100f),
                    ongoing = true,
                    progress = (progress * 100).toInt(),
                )
            }
            if (!ChunkTransport.verifyFileHash(partFile, fileHash)) {
                partFile.delete()
                stateFile.delete()
                throw SecurityException("Integrity check failed")
            }
            val finalFile = Crypto.resolveUniqueFile(downloadsDir, cleanName)
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = false)
                partFile.delete()
            }
            stateFile.delete()
            transferState.value = TransferState.Completed("Received ${finalFile.name}")
            RelayManager.showNotification(context, notifId, "Transfer Complete", "Saved ${finalFile.name} to Downloads")
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
            RelayManager.sendSealed(context, senderId, "complete", completion)
        } catch (c: CancellationException) {
            // Keep .part/.state for resume; the tunnel stop or a superseding
            // offer cancelled the download mid-flight.
            throw c
        } catch (t: Throwable) {
            // Keep .part/.state for resume (matching v2); only an integrity
            // failure above clears them, since that state is poisoned.
            Log.e(TAG, "v3 receive failed: ${t.message}", t)
            RelayManager.showNotification(context, notifId, "Transfer Failed", "Failed to receive $cleanName")
        } finally {
            registration.remove()
            NotificationIds.release(notifId)
        }
    }

    suspend fun handleIncomingOfferV1(
        context: Context,
        relayToken: String,
        peerCryptoKey: ByteArray,
        offer: JSONObject,
        deviceId: String,
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

        // The transfer id names the .part file, so it must have the shape
        // Crypto.deriveTransferId produces before any path is built.
        if (!Crypto.isTransferId(transferId)) {
            Log.w(TAG, "Rejecting v1 offer with an invalid transfer id")
            RelayConnection.send(JSONObject().apply {
                put("type", "transfer_reject")
                put("target_id", senderId)
                put("transfer_id", transferId)
                put("reason", "invalid_transfer_id")
            }.toString())
            return
        }

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
            RelayConnection.send(rejectMsg.toString())
            return
        }

        if (!RelayManager.acceptsInbound(context, InboundKind.OFFER, senderDeviceId)) {
            RelayConnection.send(JSONObject().apply {
                put("type", "transfer_reject")
                put("target_id", senderId)
                put("transfer_id", transferId)
                put("reason", "untrusted")
            }.toString())
            RelayManager.showOneShotNotification(
                context,
                transferId,
                sending = false,
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
            RelayConnection.send(rejectMsg.toString())
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
        RelayConnection.send(acceptMsg.toString())

        val label = if (batchTotal > 1) {
            "$batchTotal files (${batchIndex + 1}/$batchTotal): $cleanName"
        } else {
            cleanName
        }
        val notifId = NotificationIds.acquire(transferId, sending = false)

        transferState.value = TransferState.Receiving(senderName, label, 0f, 0f)
        RelayManager.showNotification(context, notifId, "Receiving $label", "From $senderName...", ongoing = true, progress = -2)

        val partFile = TransferStaging.forTransfer(downloadsDir, cleanName, transferId)?.part
        if (partFile == null) {
            Log.w(TAG, "Rejecting v1 offer: staging path escaped Downloads")
            RelayConnection.send(JSONObject().apply {
                put("type", "transfer_reject")
                put("target_id", senderId)
                put("transfer_id", transferId)
                put("reason", "invalid_path")
            }.toString())
            return
        }
        val relayHost = KeyRepository.getRelayAddress(context).ifBlank { RelayManager.RELAY_HOST }
        val pipeUrl = "http://$relayHost/pipe?transfer_id=$transferId&role=receiver"
        val request = Request.Builder()
            .url(pipeUrl)
            .header("X-Gaur-Token", relayToken)
            .build()

        // The pipe outlives this coroutine on an OkHttp callback thread;
        // registering the Call lets stop() cancel an in-flight v1 receive.
        val call = RelayConnection.client.newCall(request)
        val registration = registry.register(transferId) { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                registration.remove()
                NotificationIds.release(notifId)
                if (call.isCanceled()) return // tunnel stop or superseded attempt
                Log.e(TAG, "Pipe error: ${e.message}")
                if (partFile.exists()) partFile.delete()
                transferState.value = TransferState.Error("Transfer failed: ${e.message}")
                RelayManager.showNotification(context, notifId, "Transfer Failed", "Connection error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    registration.remove()
                    NotificationIds.release(notifId)
                    if (partFile.exists()) partFile.delete()
                    transferState.value = TransferState.Error("Pipe rejected with code ${response.code}")
                    RelayManager.showNotification(context, notifId, "Transfer Failed", "Relay returned code ${response.code}")
                    return
                }

                val body = response.body
                if (body == null) {
                    registration.remove()
                    NotificationIds.release(notifId)
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

                    BufferedOutputStream(FileOutputStream(partFile), V1_PIPE_BUFFER_BYTES).use { out ->
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
                            if (now - lastUpdate > V1_PROGRESS_INTERVAL_MS) {
                                lastUpdate = now
                                val speedMb = TransferProgress.speedMbPerSec(totalRead, now - startTime)
                                val fileProgress = if (fileSize > 0) (totalRead.toFloat() / fileSize.toFloat()) else 0.5f
                                val overall = if (batchTotal > 1) {
                                    (batchIndex + fileProgress) / batchTotal
                                } else {
                                    fileProgress
                                }

                                transferState.value = TransferState.Receiving(senderName, label, overall, speedMb)
                                RelayManager.showNotification(
                                    context, notifId, "Receiving $label",
                                    TransferProgress.speedAndPercentText(speedMb, overall),
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
                        transferState.value = TransferState.Completed("Received ${finalFile.name} successfully")
                        RelayManager.showNotification(context, notifId, "Transfer Complete", "Saved ${finalFile.name} to Downloads")
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
                    transferState.value = TransferState.Error("Transfer error: ${t.message}")
                    RelayManager.showNotification(context, notifId, "Transfer Failed", "Failed to receive $cleanName")
                } finally {
                    registration.remove()
                    NotificationIds.release(notifId)
                }
            }
        })
    }

    suspend fun handleIncomingOfferV2(
        context: Context,
        relayToken: String,
        peerCryptoKey: ByteArray,
        offer: JSONObject,
        notifId: Int,
        deviceId: String,
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

        // The transfer id names the .part/.state files, so it must have the
        // shape Crypto.deriveTransferId produces before any path is built.
        if (!Crypto.isTransferId(transferId)) {
            Log.w(TAG, "Rejecting v2 offer with an invalid transfer id")
            RelayConnection.send(JSONObject().apply {
                put("type", "transfer_reject")
                put("target_id", senderId)
                put("transfer_id", transferId)
                put("reason", "invalid_transfer_id")
            }.toString())
            return
        }

        val validEmpty = fileSize == 0L && chunkCount == 0
        val validNonEmpty = fileSize > 0L && chunkCount > 0
        if (sessionId.isBlank() || fileHash.isBlank() || chunkSize <= 0 ||
            chunkSize > ChunkTransport.MAX_CHUNK_SIZE || chunkCount > MAX_CHUNK_COUNT ||
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
            RelayConnection.send(JSONObject().apply {
                put("type", "transfer_reject")
                put("target_id", senderId)
                put("transfer_id", transferId)
                put("reason", "auth_failed")
            }.toString())
            return
        }

        if (!RelayManager.acceptsInbound(context, InboundKind.OFFER, senderDeviceId)) {
            RelayConnection.send(JSONObject().apply {
                put("type", "transfer_reject")
                put("target_id", senderId)
                put("transfer_id", transferId)
                put("reason", "untrusted")
            }.toString())
            RelayManager.showOneShotNotification(
                context,
                transferId,
                sending = false,
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
            RelayConnection.send(JSONObject().apply {
                put("type", "transfer_reject")
                put("target_id", senderId)
                put("transfer_id", transferId)
                put("reason", "invalid_path")
            }.toString())
            return
        }

        val staging = TransferStaging.forTransfer(downloadsDir, cleanName, transferId)
        if (staging == null) {
            Log.w(TAG, "Rejecting v2 offer: staging path escaped Downloads")
            RelayConnection.send(JSONObject().apply {
                put("type", "transfer_reject")
                put("target_id", senderId)
                put("transfer_id", transferId)
                put("reason", "invalid_path")
            }.toString())
            return
        }
        val partFile = staging.part
        val stateFile = staging.state
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
        RelayConnection.send(JSONObject().apply {
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
        transferState.value = TransferState.Receiving(senderName, label, 0f, 0f)
        RelayManager.showNotification(context, notifId, "Receiving $label", "From $senderName...", ongoing = true, progress = -2)

        val digest = ChunkTransport.FileDigest(fileHash, chunkHashes, chunkCount, fileSize)
        val missing = (0 until chunkCount).filter { it !in state.have }
        val startTime = System.currentTimeMillis()
        val lastProgress = AtomicLong(0)

        try {
            ChunkTransport.downloadMissing(
                context = context,
                client = RelayConnection.client,
                relayHost = KeyRepository.getRelayAddress(context).ifBlank { RelayManager.RELAY_HOST },
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
                if (now - lastProgress.get() < TransferProgress.THROTTLE_MS) return@downloadMissing
                lastProgress.set(now)
                val fileProgress = if (total > 0) done.toFloat() / total else 0f
                val overall = if (batchTotal > 1) (batchIndex + fileProgress) / batchTotal else fileProgress
                val speedMb = TransferProgress.speedMbPerSec(done, now - startTime)
                transferState.value = TransferState.Receiving(senderName, label, overall, speedMb)
                RelayManager.showNotification(
                    context, notifId, "Receiving $label",
                    TransferProgress.speedAndPercentText(speedMb, overall),
                    ongoing = true, progress = (overall * 100).toInt(),
                )
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "v2 download failed for $transferId: ${t.message}", t)
            RelayConnection.send(JSONObject().apply {
                put("type", "transfer_error")
                put("target_id", senderId)
                put("transfer_id", transferId)
                put("reason", t.message ?: "download_failed")
            }.toString())
            transferState.value = TransferState.Error("Transfer failed: ${t.message}")
            RelayManager.showNotification(context, notifId, "Transfer Failed", "Failed to receive $cleanName")
            return
        }

        ChunkTransport.saveState(state, stateFile)
        if (fileSize == 0L && !partFile.exists()) {
            partFile.createNewFile()
        }
        if (!ChunkTransport.verifyFileHash(partFile, fileHash)) {
            partFile.delete()
            stateFile.delete()
            RelayConnection.send(JSONObject().apply {
                put("type", "transfer_error")
                put("target_id", senderId)
                put("transfer_id", transferId)
                put("reason", "integrity_failed")
            }.toString())
            transferState.value = TransferState.Error("Transfer failed integrity check")
            RelayManager.showNotification(context, notifId, "Transfer Failed", "$cleanName failed verification")
            return
        }

        val finalFile = Crypto.resolveUniqueFile(downloadsDir, cleanName)
        val renamed = partFile.renameTo(finalFile)
        if (!renamed) {
            partFile.copyTo(finalFile, overwrite = false)
            partFile.delete()
        }
        stateFile.delete()

        RelayConnection.send(JSONObject().apply {
            put("type", "transfer_complete")
            put("v", 2)
            put("target_id", senderId)
            put("transfer_id", transferId)
            put("file_hash", fileHash)
            put("auth_tag", Crypto.computeAuthTag(peerCryptoKey, "complete:v2:$transferId:$fileHash"))
        }.toString())

        transferState.value = TransferState.Completed("Received ${finalFile.name} successfully")
        RelayManager.showNotification(context, notifId, "Transfer Complete", "Saved ${finalFile.name} to Downloads")
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
                RelayManager.mimeFromExtension(finalFile.name),
            )
            openReceivedFile(context, finalFile, mime)
        }
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
            RelayManager.ensureNotificationChannel(context)
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val openPi = PendingIntent.getActivity(
                context, fileName.hashCode(), viewIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(context, RelayManager.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_key)
                .setContentTitle("Transfer Complete")
                .setContentText("Saved $fileName to Downloads")
                .setContentIntent(openPi)
                .setAutoCancel(true)
                .setGroup(RelayManager.NOTIF_GROUP)
                .addAction(0, "Open", openPi)
                .build()
            val notifId = NotificationIds.acquire(fileName, sending = false)
            try {
                nm.notify(notifId, notification)
            } finally {
                NotificationIds.release(notifId)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Open notification error: ${t.message}")
        }
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
}
