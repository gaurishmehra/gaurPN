package dev.gaurish.outlinelite.transfer

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Chunked, content-addressed transport for file transfers.
 *
 * Each 1 MiB chunk is encrypted independently with AES-256-GCM. The nonce is
 * the chunk index and the AAD binds the chunk to its transfer, session, index,
 * count, and file size, so chunks can arrive out of order and resume after a
 * disconnect without letting the relay reorder or relocate them.
 */
object ChunkTransport {

    private const val TAG = "ChunkTransport"

    const val CHUNK_SIZE = 1 shl 20
    const val MAX_CHUNK_SIZE = 4 shl 20
    private const val CONCURRENCY = 4
    private const val UPLOAD_ATTEMPTS = 6
    private const val DOWNLOAD_ATTEMPTS = 30

    data class FileDigest(
        val fileHash: String,
        val chunkHashes: List<String>,
        val chunkCount: Int,
        val size: Long,
    )

    data class ReceiveState(
        val transferId: String,
        val fileHash: String,
        val fileSize: Long,
        val chunkSize: Int,
        val chunkCount: Int,
        val have: MutableSet<Int> = mutableSetOf(),
    ) {
        var lastSavedAt: Long = 0L
        var dirtyCount: Int = 0
    }

    private class ReadChannel(val channel: FileChannel, private val owner: Closeable) : Closeable {
        override fun close() {
            runCatching { channel.close() }
            runCatching { owner.close() }
        }
    }

    /** Reads the file once and returns the whole-file hash plus per-chunk hashes. */
    fun hashUri(context: Context, uri: Uri, chunkSize: Int = CHUNK_SIZE): FileDigest {
        val fileDigest = MessageDigest.getInstance("SHA-256")
        val hashes = ArrayList<String>()
        var total = 0L
        val input = openInputStream(context, uri) ?: throw IOException("Cannot open input stream")
        input.use { stream ->
            val buffer = ByteArray(chunkSize)
            while (true) {
                val n = readFully(stream, buffer, chunkSize)
                if (n <= 0) break
                fileDigest.update(buffer, 0, n)
                hashes.add(Crypto.sha256Hex(buffer.copyOf(n)))
                total += n
                if (n < chunkSize) break
            }
        }
        return FileDigest(
            fileHash = fileDigest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) },
            chunkHashes = hashes,
            chunkCount = hashes.size,
            size = total,
        )
    }

    fun chunkLength(index: Int, fileSize: Long): Int {
        val offset = index.toLong() * CHUNK_SIZE
        val remaining = fileSize - offset
        if (remaining <= 0) return 0
        return if (remaining > CHUNK_SIZE) CHUNK_SIZE else remaining.toInt()
    }

    suspend fun uploadMissing(
        context: Context,
        client: OkHttpClient,
        relayHost: String,
        relayToken: String,
        deviceId: String,
        peerKey: ByteArray,
        transferId: String,
        sessionId: String,
        digest: FileDigest,
        aesKey: ByteArray,
        missing: List<Int>,
        uri: Uri,
        onProgress: (done: Long, total: Long) -> Unit,
    ) {
        if (missing.isEmpty()) return
        val total = missing.sumOf { chunkLength(it, digest.size).toLong() }
        val done = AtomicLong(0)
        val readChannel = openReadChannel(context, uri)
        try {
            coroutineScope {
                val semaphore = Semaphore(CONCURRENCY)
                missing.map { index ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val length = chunkLength(index, digest.size)
                            val buffer = ByteBuffer.allocate(length)
                            var position = index.toLong() * CHUNK_SIZE
                            while (buffer.hasRemaining()) {
                                val n = readChannel.channel.read(buffer, position)
                                if (n < 0) throw IOException("Unexpected EOF at $position")
                                if (n == 0) throw IOException("Read stalled at $position")
                                position += n
                            }
                            val plain = buffer.array()
                            if (Crypto.sha256Hex(plain) != digest.chunkHashes[index]) {
                                throw IOException("Chunk $index hash mismatch before upload")
                            }
                            val nonce = Crypto.chunkNonce(index)
                            val aad = Crypto.chunkAad(transferId, sessionId, index, digest.chunkCount, digest.size)
                            val ciphertext = Crypto.encryptChunkAad(aesKey, nonce, plain, aad)
                            putChunkWithRetry(client, relayHost, relayToken, deviceId, peerKey, transferId, sessionId, index, ciphertext)
                            val n = done.addAndGet(length.toLong())
                            onProgress(n, total)
                        }
                    }
                }.awaitAll()
            }
        } finally {
            readChannel.close()
        }
    }

    suspend fun downloadMissing(
        context: Context,
        client: OkHttpClient,
        relayHost: String,
        relayToken: String,
        deviceId: String,
        peerKey: ByteArray,
        transferId: String,
        sessionId: String,
        digest: FileDigest,
        aesKey: ByteArray,
        missing: List<Int>,
        partFile: File,
        state: ReceiveState,
        stateFile: File,
        onProgress: (done: Long, total: Long) -> Unit,
    ) {
        if (missing.isEmpty()) return
        val total = missing.sumOf { chunkLength(it, digest.size).toLong() }
        val done = AtomicLong(0)
        val raf = RandomAccessFile(partFile, "rw")
        val channel = raf.channel
        try {
            coroutineScope {
                val semaphore = Semaphore(CONCURRENCY)
                missing.map { index ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val ciphertext = fetchChunkWithRetry(
                                client, relayHost, relayToken, deviceId, peerKey, transferId, sessionId, index,
                            )
                            if (ciphertext.size > MAX_CHUNK_SIZE) {
                                throw IOException("Chunk $index exceeds relay limit")
                            }
                            val nonce = Crypto.chunkNonce(index)
                            val aad = Crypto.chunkAad(transferId, sessionId, index, digest.chunkCount, digest.size)
                            val plain = Crypto.decryptChunkAad(aesKey, nonce, ciphertext, aad)
                            val expected = chunkLength(index, digest.size)
                            if (plain.size != expected) {
                                throw IOException("Chunk $index length ${plain.size}, want $expected")
                            }
                            if (Crypto.sha256Hex(plain) != digest.chunkHashes[index]) {
                                throw IOException("Chunk $index content hash mismatch")
                            }
                            val buffer = ByteBuffer.wrap(plain)
                            val base = index.toLong() * CHUNK_SIZE
                            while (buffer.hasRemaining()) {
                                val n = channel.write(buffer, base + buffer.position())
                                if (n <= 0) throw IOException("Chunk $index write stalled")
                            }
                            markHave(state, stateFile, index)
                            val n = done.addAndGet(plain.size.toLong())
                            onProgress(n, total)
                        }
                    }
                }.awaitAll()
            }
            channel.force(true)
        } finally {
            runCatching { channel.close() }
            runCatching { raf.close() }
        }
    }

    fun verifyFileHash(file: File, expected: String): Boolean {
        if (!file.exists()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) } == expected
    }

    fun loadState(stateFile: File, transferId: String, fileHash: String): ReceiveState? {
        if (!stateFile.exists()) return null
        return runCatching {
            val json = JSONObject(stateFile.readText())
            if (json.optString("transfer_id") != transferId) return null
            if (json.optString("file_hash") != fileHash) return null
            val chunkCount = json.optInt("chunk_count", 0)
            val chunkSize = json.optInt("chunk_size", 0)
            val fileSize = json.optLong("file_size", -1L)
            if (chunkCount <= 0 || chunkSize <= 0 || fileSize < 0) return null
            val have = mutableSetOf<Int>()
            json.optJSONArray("have")?.let { array ->
                for (i in 0 until array.length()) {
                    val idx = array.optInt(i, -1)
                    if (idx in 0 until chunkCount) have.add(idx)
                }
            }
            ReceiveState(transferId, fileHash, fileSize, chunkSize, chunkCount, have)
        }.getOrNull()
    }

    fun saveState(state: ReceiveState, stateFile: File) {
        val json = JSONObject().apply {
            put("transfer_id", state.transferId)
            put("file_hash", state.fileHash)
            put("file_size", state.fileSize)
            put("chunk_size", state.chunkSize)
            put("chunk_count", state.chunkCount)
            put("have", JSONArray(state.have.sorted()))
        }
        val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
        runCatching {
            tmp.writeText(json.toString())
            if (!tmp.renameTo(stateFile)) {
                stateFile.writeText(json.toString())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "Failed to persist resume state: ${it.message}") }
    }

    fun deleteTransfer(
        client: OkHttpClient,
        relayHost: String,
        relayToken: String,
        deviceId: String,
        peerKey: ByteArray,
        transferId: String,
        sessionId: String,
    ) {
        val url = "http://$relayHost/transfer?transfer_id=${Uri.encode(transferId)}&session_id=${Uri.encode(sessionId)}" +
            "&device_id=${Uri.encode(deviceId)}&cap=${Crypto.capabilityTag(peerKey, transferId, sessionId)}"
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("X-Gaur-Token", relayToken)
                .delete()
                .build()
            client.newCall(request).execute().use { }
        }
    }

    private fun markHave(state: ReceiveState, stateFile: File, index: Int) {
        synchronized(state) {
            if (!state.have.add(index)) return
            state.dirtyCount++
            val now = System.currentTimeMillis()
            if (state.dirtyCount >= 8 || now - state.lastSavedAt >= 1000L) {
                saveState(state, stateFile)
                state.lastSavedAt = now
                state.dirtyCount = 0
            }
        }
    }

    private suspend fun putChunkWithRetry(
        client: OkHttpClient,
        relayHost: String,
        relayToken: String,
        deviceId: String,
        peerKey: ByteArray,
        transferId: String,
        sessionId: String,
        index: Int,
        data: ByteArray,
    ) {
        val url = "http://$relayHost/chunk?transfer_id=${Uri.encode(transferId)}&session_id=${Uri.encode(sessionId)}&index=$index" +
            "&device_id=${Uri.encode(deviceId)}&cap=${Crypto.capabilityTag(peerKey, transferId, sessionId)}"
        var backoff = 200L
        var lastError: Throwable? = null
        repeat(UPLOAD_ATTEMPTS) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("X-Gaur-Token", relayToken)
                    .put(data.toRequestBody("application/octet-stream".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return
                    lastError = IOException("Relay returned HTTP ${response.code}")
                }
            } catch (t: Throwable) {
                lastError = t
            }
            delay(backoff)
            backoff = minOf(backoff * 2, 2000L)
        }
        throw IOException("Chunk $index upload failed", lastError)
    }

    private suspend fun fetchChunkWithRetry(
        client: OkHttpClient,
        relayHost: String,
        relayToken: String,
        deviceId: String,
        peerKey: ByteArray,
        transferId: String,
        sessionId: String,
        index: Int,
    ): ByteArray {
        val url = "http://$relayHost/chunk?transfer_id=${Uri.encode(transferId)}&session_id=${Uri.encode(sessionId)}&index=$index" +
            "&device_id=${Uri.encode(deviceId)}&cap=${Crypto.capabilityTag(peerKey, transferId, sessionId)}"
        var backoff = 100L
        var lastError: Throwable? = null
        repeat(DOWNLOAD_ATTEMPTS) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("X-Gaur-Token", relayToken)
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 404 -> lastError = IOException("Chunk $index not uploaded yet")
                        response.isSuccessful -> {
                            val body = response.body ?: throw IOException("Empty chunk response")
                            val bytes = body.bytes()
                            if (bytes.size > MAX_CHUNK_SIZE) {
                                throw IOException("Chunk $index exceeds relay limit")
                            }
                            return bytes
                        }
                        else -> lastError = IOException("Relay returned HTTP ${response.code}")
                    }
                }
            } catch (t: Throwable) {
                lastError = t
            }
            delay(backoff)
            backoff = minOf(backoff * 2, 2000L)
        }
        throw IOException("Chunk $index unavailable", lastError)
    }

    private fun openInputStream(context: Context, uri: Uri): InputStream? =
        if (uri.scheme == "file") {
            runCatching { File(uri.path ?: "").inputStream() }.getOrNull()
        } else {
            context.contentResolver.openInputStream(uri)
        }

    private fun openReadChannel(context: Context, uri: Uri): ReadChannel {
        if (uri.scheme == "file") {
            val raf = RandomAccessFile(File(uri.path ?: ""), "r")
            return ReadChannel(raf.channel, raf)
        }
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Cannot open file descriptor")
        val fis = FileInputStream(pfd.fileDescriptor)
        return ReadChannel(
            fis.channel,
            object : Closeable {
                override fun close() {
                    runCatching { fis.close() }
                    runCatching { pfd.close() }
                }
            },
        )
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Int {
        var total = 0
        while (total < length) {
            val n = input.read(buffer, total, length - total)
            if (n <= 0) break
            total += n
        }
        return total
    }
}
