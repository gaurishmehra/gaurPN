package dev.gaurish.outlinelite.util

/**
 * Minimal RFC 4648 standard base64 (with padding) in pure Kotlin.
 *
 * java.util.Base64 needs API 26 (minSdk is 24) and android.util.Base64 is
 * unavailable in JVM unit tests, so the code paths that must stay
 * JVM-testable (sealed secrets, v1 offer tags) use this instead.
 */
internal object StdBase64 {

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val REVERSE = IntArray(128) { -1 }.apply {
        for (i in ALPHABET.indices) this[ALPHABET[i].code] = i
    }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[(n shr 18) and 0x3F])
            out.append(ALPHABET[(n shr 12) and 0x3F])
            out.append(ALPHABET[(n shr 6) and 0x3F])
            out.append(ALPHABET[n and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                out.append(ALPHABET[(n shr 18) and 0x3F])
                out.append(ALPHABET[(n shr 12) and 0x3F])
                out.append("==")
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 8)
                out.append(ALPHABET[(n shr 18) and 0x3F])
                out.append(ALPHABET[(n shr 12) and 0x3F])
                out.append(ALPHABET[(n shr 6) and 0x3F])
                out.append("=")
            }
        }
        return out.toString()
    }

    /** Returns null on any malformed input; never throws. */
    fun decode(text: String): ByteArray? {
        val clean = text.trim()
        if (clean.isEmpty() || clean.length % 4 != 0) return null
        val pad = when {
            clean.endsWith("==") -> 2
            clean.endsWith("=") -> 1
            else -> 0
        }
        val dataEnd = clean.length - pad
        if (dataEnd <= 0) return null
        if (pad == 1 && dataEnd % 4 != 3) return null
        if (pad == 2 && dataEnd % 4 != 2) return null
        val out = ByteArray(dataEnd / 4 * 3 + (3 - pad) % 3)
        var buffer = 0
        var bits = 0
        var outIdx = 0
        for (i in 0 until dataEnd) {
            val code = clean[i].code
            val v = if (code in 0..127) REVERSE[code] else -1
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[outIdx++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return out
    }
}
