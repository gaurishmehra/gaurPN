package dev.gaurish.outlinelite.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.gaurish.outlinelite.util.StdBase64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Symmetric seal/open for secrets persisted in DataStore. Implementations
 * must produce self-contained sealed blobs (randomized IV included) so the
 * stored value alone is enough to open later.
 */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** Marker prefix for sealed secret values stored in DataStore. */
const val SECRET_MARKER = "gk1:"

/** Seals [value] into its stored representation: "gk1:" + base64(IV || ciphertext+tag). */
fun sealSecret(value: String, box: SecretBox): String =
    SECRET_MARKER + StdBase64.encode(box.seal(value.toByteArray(Charsets.UTF_8)))

/**
 * Read/migration helper for stored secrets. A legacy plaintext value is
 * returned as-is so the caller can re-seal it; a "gk1:" value is opened; a
 * corrupt sealed value fails closed as "" (callers treat that as unset).
 * Pure Kotlin so the migration decision is JVM-testable.
 */
fun unwrapSecret(value: String, box: SecretBox): String {
    if (!value.startsWith(SECRET_MARKER)) return value
    val sealed = StdBase64.decode(value.removePrefix(SECRET_MARKER)) ?: return ""
    return runCatching { String(box.open(sealed), Charsets.UTF_8) }.getOrDefault("")
}

/**
 * Seals secrets with an AES-256-GCM key held by the AndroidKeyStore. The
 * key is generated on first use and never leaves the keystore; no user auth
 * is required because secrets must stay readable in the background while
 * the tunnel is up. Sealed layout: 12-byte IV || ciphertext+tag.
 *
 * Kept thin and dumb — the AndroidKeyStore cannot run in JVM unit tests,
 * so all logic that can be tested lives in [sealSecret]/[unwrapSecret].
 */
class KeystoreSecretBox(context: Context) : SecretBox {

    private val appContext = context.applicationContext

    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < IV_SIZE + GCM_TAG_SIZE) {
            throw IllegalArgumentException("sealed value too short")
        }
        val iv = sealed.copyOfRange(0, IV_SIZE)
        val ciphertext = sealed.copyOfRange(IV_SIZE, sealed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "gaurpn-master-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val GCM_TAG_SIZE = 16
    }
}

/** Identity box: plaintext in, plaintext out. Used by tests and as the migration source format. */
object PlainSecretBox : SecretBox {
    override fun seal(plain: ByteArray): ByteArray = plain
    override fun open(sealed: ByteArray): ByteArray = sealed
}
