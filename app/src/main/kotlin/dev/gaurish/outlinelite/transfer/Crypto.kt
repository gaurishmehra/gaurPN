package dev.gaurish.outlinelite.transfer

import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crypto {

    private val cachedParams: ECParameterSpec by lazy {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        (kpg.generateKeyPair().public as ECPublicKey).params
    }

    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    fun exportPublicKey(publicKey: PublicKey): ByteArray {
        val ecPub = publicKey as ECPublicKey
        val x = toFixedLengthByteArray(ecPub.w.affineX, 32)
        val y = toFixedLengthByteArray(ecPub.w.affineY, 32)
        val result = ByteArray(65)
        result[0] = 0x04 // SEC 1 uncompressed point prefix
        System.arraycopy(x, 0, result, 1, 32)
        System.arraycopy(y, 0, result, 33, 32)
        return result
    }

    fun importPublicKey(bytes: ByteArray): PublicKey {
        require(bytes.size == 65 && bytes[0] == 0x04.toByte()) { "Invalid SEC 1 uncompressed EC public key" }
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        val point = ECPoint(x, y)
        val spec = ECPublicKeySpec(point, cachedParams)
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }

    fun deriveSharedSecret(keyPair: KeyPair, peerPublicKeyBytes: ByteArray): ByteArray {
        val peerPub = importPublicKey(peerPublicKeyBytes)
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(keyPair.private)
        ka.doPhase(peerPub, true)
        val rawSecret = ka.generateSecret()

        // HKDF-SHA256: Extract then Expand (RFC 5869)
        val salt = "gaur-share-salt".toByteArray(Charsets.UTF_8)
        val info = "gaur-share-session-aes256".toByteArray(Charsets.UTF_8)

        // 1. Extract: PRK = HMAC-SHA256(salt, rawSecret)
        val macExtract = Mac.getInstance("HmacSHA256")
        macExtract.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = macExtract.doFinal(rawSecret)

        // 2. Expand: T(1) = HMAC-SHA256(PRK, info || 0x01)
        val macExpand = Mac.getInstance("HmacSHA256")
        macExpand.init(SecretKeySpec(prk, "HmacSHA256"))
        macExpand.update(info)
        macExpand.update(0x01.toByte())
        val okm = macExpand.doFinal()

        // Return 32-byte AES-256 key
        return okm.copyOf(32)
    }

    fun encryptChunk(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, offset: Int, length: Int): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(plaintext, offset, length)
    }

    fun decryptChunk(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(ciphertext)
    }

    fun deriveSubKeys(clusterToken: String): Pair<String, ByteArray> {
        val tokenBytes = clusterToken.toByteArray(Charsets.UTF_8)

        val macRelay = Mac.getInstance("HmacSHA256")
        macRelay.init(SecretKeySpec(tokenBytes, "HmacSHA256"))
        val relayBytes = macRelay.doFinal("gaur-relay-auth-v1".toByteArray(Charsets.UTF_8))
        val relayToken = relayBytes.joinToString("") { "%02x".format(it) }

        val macPeer = Mac.getInstance("HmacSHA256")
        macPeer.init(SecretKeySpec(tokenBytes, "HmacSHA256"))
        val peerCryptoKey = macPeer.doFinal("gaur-peer-auth-v1".toByteArray(Charsets.UTF_8))

        return Pair(relayToken, peerCryptoKey)
    }

    fun computeAuthTag(peerCryptoKey: ByteArray, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(peerCryptoKey, "HmacSHA256"))
        val tagBytes = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return tagBytes.joinToString("") { "%02x".format(it) }
    }

    fun verifyAuthTag(peerCryptoKey: ByteArray, message: String, tagHex: String): Boolean {
        if (tagHex.isBlank()) return false
        val expectedTag = computeAuthTag(peerCryptoKey, message)
        return MessageDigest.isEqual(
            expectedTag.toByteArray(Charsets.UTF_8),
            tagHex.lowercase().toByteArray(Charsets.UTF_8)
        )
    }

    fun computeSafetyNumber(alicePub: ByteArray, bobPub: ByteArray, transferId: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val compare = compareByteArrays(alicePub, bobPub)
        if (compare <= 0) {
            md.update(alicePub)
            md.update(bobPub)
        } else {
            md.update(bobPub)
            md.update(alicePub)
        }
        md.update(transferId.toByteArray(Charsets.UTF_8))
        val digest = md.digest()

        val sb = StringBuilder()
        for (i in 0 until 6) {
            val offset = i * 4
            val value = ByteBuffer.wrap(digest, offset, 4).int.toLong() and 0xFFFFFFFFL
            val positive = value % 100000L
            if (i > 0) sb.append(" ")
            sb.append(String.format(java.util.Locale.US, "%05d", positive))
        }
        return sb.toString()
    }

    private fun compareByteArrays(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val byteA = a[i].toInt() and 0xFF
            val byteB = b[i].toInt() and 0xFF
            if (byteA != byteB) return byteA - byteB
        }
        return a.size - b.size
    }

    fun sanitizeFileName(rawName: String): String {
        val normalized = rawName.trim().trimEnd('/', '\\').replace('\\', '/')
        val base = normalized.substringAfterLast('/')
        val clean = base.replace(Regex("[\\x00-\\x1F\\x7F/\\\\:*?\"<>|]"), "_").trim()
        val sanitized = clean.replace(Regex("^\\.+"), "")
        return if (sanitized.isBlank()) "download_${System.currentTimeMillis()}" else sanitized
    }

    fun isPathInsideDirectory(destFile: File, baseDir: File): Boolean {
        val canonicalBase = baseDir.canonicalFile
        var parent: File? = destFile.canonicalFile.parentFile
        while (parent != null) {
            if (parent == canonicalBase) return true
            parent = parent.parentFile
        }
        return false
    }

    fun resolveUniqueFile(targetDir: File, fileName: String): File {
        val initialFile = File(targetDir, fileName)
        if (!initialFile.exists()) {
            return initialFile
        }
        val dotIndex = fileName.lastIndexOf('.')
        val stem = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
        val ext = if (dotIndex > 0) fileName.substring(dotIndex) else ""

        for (i in 1..1000) {
            val candidate = File(targetDir, "$stem ($i)$ext")
            if (!candidate.exists()) {
                return candidate
            }
        }
        return File(targetDir, "$stem (${System.currentTimeMillis()})$ext")
    }

    private fun toFixedLengthByteArray(bi: BigInteger, length: Int): ByteArray {
        val raw = bi.toByteArray()
        if (raw.size == length) return raw
        val out = ByteArray(length)
        if (raw.size > length) {
            // Drop leading sign zero
            System.arraycopy(raw, raw.size - length, out, 0, length)
        } else {
            // Left pad with zeros
            System.arraycopy(raw, 0, out, length - raw.size, raw.size)
        }
        return out
    }
}
