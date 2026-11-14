package dev.gaurish.outlinelite.transfer

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import dev.gaurish.outlinelite.data.KeyRepository
import dev.gaurish.outlinelite.data.TransferHistoryEntry
import dev.gaurish.outlinelite.data.TransferHistoryRepository
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Outbound file transfers: the v2 offer/upload flow and the v3 sealed send
 * (upload once, sealed offers to every target). Owns the single in-flight
 * send job; the socket comes from [RelayConnection], keys and notification
 * posting from [RelayManager].
 */
internal class OutboundSender(
    private val scope: CoroutineScope,
    private val transferState: MutableStateFlow<TransferState>,
    private val registry: TransferRegistry,
    private val pendingAccepts: ConcurrentHashMap<String, CompletableDeferred<JSONObject>>,
    private val pendingCompletes: ConcurrentHashMap<String, CompletableDeferred<JSONObject>>,
) {

    private companion object {
        const val TAG = "RelayManager"

        /** Waiting for transfer_accept before the offer times out. */
        const val ACCEPT_TIMEOUT_MS = 60_000L

        /** Upload attempts per file before giving up. */
        const val SEND_ATTEMPTS = 3

        /** Base delay between send attempts; grows per attempt. */
        const val SEND_RETRY_DELAY_MS = 1_000L

        /** Completion-receipt timeout allowance: 1 s per 512 KiB of file. */
        const val COMPLETION_TIMEOUT_PER_BYTES = 512 * 1024
        const val COMPLETION_TIMEOUT_MIN_MS = 120_000L
        const val COMPLETION_TIMEOUT_MAX_MS = 2 * 60 * 60 * 1000L
    }

    private data class OutgoingFile(val uri: Uri, val name: String, val size: Long)

    /** The in-flight send, cancelled by [cancelActive] and by facade stop. */
    private var sendJob: Job? = null

    /** Cancels the in-flight send, if any. */
    fun cancelActive() {
        sendJob?.cancel()
        sendJob = null
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
        deviceId: String,
        request: RequestContext? = null,
        releaseUriGrants: Boolean = false,
    ) {
        val appCtx = context.applicationContext
        if (targets.isEmpty() || uris.isEmpty()) {
            if (releaseUriGrants) releaseReadGrants(appCtx, uris)
            return
        }
        if (!RelayConnection.isConnected) {
            if (releaseUriGrants) releaseReadGrants(appCtx, uris)
            transferState.value = TransferState.Error("File sharing not connected")
            return
        }
        sendJob?.cancel()
        val job = scope.launch {
            val token = runCatching { KeyRepository.getClusterToken(appCtx) }.getOrDefault("")
            if (token.isBlank()) {
                transferState.value = TransferState.Error("Cluster token not configured")
                return@launch
            }
            val (relayToken, peerCryptoKey) = Crypto.deriveSubKeys(token)
            val relayHost = KeyRepository.getRelayAddress(appCtx).ifBlank { RelayManager.RELAY_HOST }
            val files = uris.map { uri ->
                val (name, size) = queryFileInfo(appCtx.contentResolver, uri)
                OutgoingFile(uri, name, size)
            }
            val batchId = "batch-${UUID.randomUUID()}"
            val totalSize = files.sumOf { it.size }
            val batchNotifId = NotificationIds.acquire(batchId, sending = true)
            val self = coroutineContext[Job]
            val registration = registry.register(batchId) { self?.cancel() }
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
                        notifId = batchNotifId,
                        deviceId = deviceId,
                        request = request,
                    )
                    sentBefore += file.size
                }
                val message = if (offline) {
                    "Queued ${files.size} file(s) for ${targets.size} device(s)"
                } else {
                    "Sent ${files.size} file(s) to ${targets.size} device(s)"
                }
                transferState.value = TransferState.Completed(message)
                RelayManager.showNotification(appCtx, batchNotifId, "Transfer Complete", message)
            } catch (t: CancellationException) {
                transferState.value = TransferState.Idle
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "v3 send failed: ${t.message}", t)
                transferState.value = TransferState.Error("Transfer error: ${t.message}")
                RelayManager.showNotification(
                    appCtx,
                    batchNotifId,
                    "Transfer Failed",
                    "Failed sending to ${targets.size} device(s)",
                )
            } finally {
                registration.remove()
                NotificationIds.release(batchNotifId)
            }
        }
        sendJob = job
        if (releaseUriGrants) {
            // Fire-and-forget: the picker's persistable grants die with the
            // send, whether it completes, fails, or is cancelled.
            job.invokeOnCompletion { releaseReadGrants(appCtx, uris) }
        }
    }

    /**
     * Drops the persistable read grants the request picker took for a send.
     * Providers without persistable grants throw and are ignored, exactly
     * like the take side.
     */
    internal fun releaseReadGrants(context: Context, uris: List<Uri>) {
        uris.forEach { uri ->
            if (uri.scheme != "content") return@forEach
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
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
        notifId: Int,
        deviceId: String,
        request: RequestContext?,
    ) {
        transferState.value = TransferState.Sending(targets.first().name, label, 0f, 0f)
        RelayManager.showNotification(context, notifId, "Preparing $label", "Hashing file...", ongoing = true, progress = -2)

        val digest = withContext(Dispatchers.IO) { ChunkTransport.hashUri(context, file.uri) }
        RelayManager.cancelNotification(context, notifId)
        if (file.size > 0 && digest.size != file.size) {
            throw IOException("File changed while hashing (expected ${file.size}, read ${digest.size})")
        }

        val transferId = Crypto.deriveTransferId(peerCryptoKey, digest.fileHash)
        // Deterministic session: staged ciphertext from an earlier attempt of
        // the same file stays addressable, so a retry resumes instead of
        // re-uploading (the receiver derives the same id from the offer).
        val sessionId = Crypto.deriveSessionId(peerCryptoKey, transferId)
        val fileKey = Crypto.deriveFileKey(peerCryptoKey, transferId)
        // Sender-side resume: ask the relay what is already staged and upload
        // only the rest. Fails open to a full upload.
        val present = ChunkTransport.fetchPresent(
            RelayConnection.client, relayHost, relayToken, deviceId, peerCryptoKey, transferId, sessionId, digest.chunkCount,
        )
        val presentBytes = present.sumOf { ChunkTransport.chunkLength(it, digest.size).toLong() }
        val missing = (0 until digest.chunkCount).filter { it !in present }
        val startTime = System.currentTimeMillis()
        val lastProgress = AtomicLong(0)

        ChunkTransport.uploadMissing(
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
            uri = file.uri,
        ) { done, total ->
            val now = System.currentTimeMillis()
            if (now - lastProgress.get() < TransferProgress.THROTTLE_MS) return@uploadMissing
            lastProgress.set(now)
            // Progress reflects the whole file — staged chunks from a previous
            // attempt count as done — so a resumed send starts where it left
            // off instead of restarting at 0%.
            val fileProgress = if (digest.size > 0) {
                (presentBytes + done).toFloat() / digest.size
            } else {
                0f
            }
            val overall = if (totalSize > 0) {
                (sentBefore + fileProgress * file.size).toFloat() / totalSize
            } else {
                fileProgress
            }
            val speedMb = TransferProgress.speedMbPerSec(done, now - startTime)
            transferState.value = TransferState.Sending(targets.first().name, label, overall, speedMb)
            RelayManager.showNotification(
                context,
                notifId,
                "Sending $label",
                TransferProgress.speedAndPercentText(speedMb, overall),
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
            // Request answers echo the request id and grant verbatim; the
            // requester verifies them against its own request. Absent on
            // every ordinary send, so that path is byte-identical.
            RequestCodec.applyRequestEcho(inner, request)
            RelayManager.sendSealed(context, target.id, "offer", inner)
        }
        RelayManager.showNotification(
            context,
            notifId,
            "Queued $label",
            "Uploaded once for ${targets.size} device(s)",
            ongoing = false,
        )
    }

    fun sendFiles(
        context: Context,
        target: ClusterPeer,
        uris: List<Uri>,
        openOnArrival: Boolean = false,
        cancelPrevious: Boolean = true,
        deviceId: String,
    ) {
        if (uris.isEmpty()) return
        val appCtx = context.applicationContext
        if (!RelayConnection.isConnected) {
            transferState.value = TransferState.Error("File sharing not connected")
            return
        }

        if (cancelPrevious) sendJob?.cancel()
        val job = scope.launch {
            val masterToken = runCatching { KeyRepository.getClusterToken(appCtx) }.getOrDefault("")
            if (masterToken.isBlank()) {
                transferState.value = TransferState.Error("Cluster token not configured")
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
            val batchNotifId = NotificationIds.acquire(batchId, sending = true)
            val self = coroutineContext[Job]
            val registration = registry.register(batchId) { self?.cancel() }

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
                        notifId = batchNotifId,
                        deviceId = deviceId,
                    )
                    sentBefore += file.size
                }
                val message = if (files.size == 1) {
                    "Sent ${files[0].name} to ${target.name}"
                } else {
                    "Sent ${files.size} files to ${target.name}"
                }
                transferState.value = TransferState.Completed(message)
                RelayManager.showNotification(appCtx, batchNotifId, "Transfer Complete", message)
                historyIds.forEach { id ->
                    runCatching { TransferHistoryRepository.updateStatus(appCtx, id, "completed") }
                }
            } catch (t: CancellationException) {
                historyIds.forEach { id ->
                    runCatching { TransferHistoryRepository.updateStatus(appCtx, id, "cancelled") }
                }
                transferState.value = TransferState.Idle
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "Send failed: ${t.message}", t)
                transferState.value = TransferState.Error("Transfer error: ${t.message}")
                RelayManager.showNotification(appCtx, batchNotifId, "Transfer Failed", "Failed sending to ${target.name}")
                historyIds.forEach { id ->
                    runCatching { TransferHistoryRepository.updateStatus(appCtx, id, "failed") }
                }
            } finally {
                registration.remove()
                NotificationIds.release(batchNotifId)
            }
        }
        if (cancelPrevious) sendJob = job
    }

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
        notifId: Int,
        deviceId: String,
    ) {
        transferState.value = TransferState.Sending(target.name, label, 0f, 0f)
        RelayManager.showNotification(context, notifId, "Preparing $label", "Hashing file...", ongoing = true, progress = -2)

        val digest = withContext(Dispatchers.IO) { ChunkTransport.hashUri(context, file.uri) }
        RelayManager.cancelNotification(context, notifId)
        if (file.size > 0 && digest.size != file.size) {
            throw IOException("File changed while hashing (expected ${file.size}, read ${digest.size})")
        }
        val transferId = Crypto.deriveTransferId(peerCryptoKey, digest.fileHash)

        var lastError: Throwable? = null
        for (attempt in 1..SEND_ATTEMPTS) {
            if (attempt > 1) delay((attempt - 1) * SEND_RETRY_DELAY_MS)
            val sessionId = Crypto.randomHex(16)
            try {
                sendAttempt(
                    context, target, relayToken, peerCryptoKey, file, digest,
                    transferId, sessionId, batchId, batchIndex, batchTotal, label, sentBefore, totalSize,
                    openOnArrival, deviceId,
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
        deviceId: String,
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
                    RelayManager.mimeFromExtension(file.uri.path),
                ))
            }
        }

        val deferred = CompletableDeferred<JSONObject>()
        pendingAccepts[transferId] = deferred
        val socket = RelayConnection.socketOrNull() ?: throw IOException("File sharing not connected")
        if (!socket.send(offer.toString())) {
            pendingAccepts.remove(transferId)
            throw IOException("Failed to send transfer offer")
        }

        val response = withTimeoutOrNull(ACCEPT_TIMEOUT_MS) { deferred.await() }
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
        val relayHost = KeyRepository.getRelayAddress(context).ifBlank { RelayManager.RELAY_HOST }
        val notifId = NotificationIds.acquire(transferId, sending = true)
        val startTime = System.currentTimeMillis()
        val lastProgress = AtomicLong(0)

        val completion = CompletableDeferred<JSONObject>()
        pendingCompletes[transferId] = completion
        try {
            ChunkTransport.uploadMissing(
                context = context,
                client = RelayConnection.client,
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
                if (now - lastProgress.get() < TransferProgress.THROTTLE_MS) return@uploadMissing
                lastProgress.set(now)
                val fileProgress = if (total > 0) done.toFloat() / total else 0f
                val overall = if (totalSize > 0) {
                    (sentBefore + fileProgress * file.size).toFloat() / totalSize
                } else {
                    fileProgress
                }
                val speedMb = TransferProgress.speedMbPerSec(done, now - startTime)
                transferState.value = TransferState.Sending(target.name, label, overall, speedMb)
                RelayManager.showNotification(
                    context, notifId, "Sending $label",
                    TransferProgress.speedAndPercentText(speedMb, overall),
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
            RelayManager.showNotification(context, notifId, "Sent $label", "Transfer complete", ongoing = false)
        } catch (t: Throwable) {
            RelayManager.showNotification(context, notifId, "Transfer Failed", "Failed to send $label", ongoing = false)
            throw t
        } finally {
            pendingCompletes.remove(transferId)
            NotificationIds.release(notifId)
        }

        ChunkTransport.deleteTransfer(RelayConnection.client, relayHost, relayToken, deviceId, peerCryptoKey, transferId, sessionId)
    }

    private fun completionTimeout(fileSize: Long): Long {
        val bySize = fileSize / COMPLETION_TIMEOUT_PER_BYTES * 1000L
        return bySize.coerceIn(COMPLETION_TIMEOUT_MIN_MS, COMPLETION_TIMEOUT_MAX_MS)
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
