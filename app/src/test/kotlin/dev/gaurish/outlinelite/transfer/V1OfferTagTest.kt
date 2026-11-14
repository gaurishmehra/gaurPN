package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pins the v1 offer auth-tag format. The desktop client (gaur-share)
 * mirrors the same construction in Go with base64.StdEncoding, so the
 * string layout below is the cross-platform contract:
 *
 *   "offer:v1:" + transferId + ":" + receiverDeviceId + ":" + senderPubB64 +
 *   ":" + base64Std(fileNameUtf8) + ":" + fileSize
 *
 * base64Std is standard base64 WITH padding and no line wraps
 * (Kotlin: Base64.encodeToString(bytes, Base64.NO_WRAP);
 *  Go: base64.StdEncoding.EncodeToString).
 */
class V1OfferTagTest {

    private val transferId = "tx-9f2c7a1b3d4e5f60718293a4b5c6d7e8"
    private val receiverDeviceId = "android-1a2b3c4d"
    private val senderPubB64 = "BJ7z0QxkR2VmW9yL4nNqTsXzYvAaBbCcDdEeFfGgHhIiJjKkLlMmNnOoPpQqRr"
    private val fileName = "report.pdf"
    private val fileSize = 12345L

    /** Fixed K_peer for the pinned vector (never a real cluster key). */
    private val peerKey = ByteArray(32) { (it * 7 + 3).toByte() }

    @Test
    fun v1OfferAuthMessageLayoutIsPinned() {
        // base64("report.pdf") == "cmVwb3J0LnBkZg==" — standard base64 with padding.
        val message = TransferOfferCodec.v1OfferAuthMessage(
            transferId, receiverDeviceId, senderPubB64, fileName, fileSize,
        )
        assertEquals(
            "offer:v1:$transferId:$receiverDeviceId:$senderPubB64:cmVwb3J0LnBkZg==:$fileSize",
            message,
        )
    }

    @Test
    fun v1OfferAuthMessageMatchesIndependentConstruction() {
        val message = TransferOfferCodec.v1OfferAuthMessage(
            transferId, receiverDeviceId, senderPubB64, fileName, fileSize,
        )
        // Independent pin: JVM java.util.Base64 + plain string concatenation.
        val expectedMessage =
            "offer:v1:" + transferId + ":" + receiverDeviceId + ":" + senderPubB64 + ":" +
                Base64.getEncoder().encodeToString(fileName.toByteArray(Charsets.UTF_8)) + ":" +
                fileSize
        assertEquals(expectedMessage, message)
        assertEquals(hmacHex(expectedMessage), Crypto.computeAuthTag(peerKey, message))
    }

    @Test
    fun fileNameIsBoundByTheTag() {
        val base = TransferOfferCodec.v1OfferAuthMessage(
            transferId, receiverDeviceId, senderPubB64, fileName, fileSize,
        )
        val renamed = TransferOfferCodec.v1OfferAuthMessage(
            transferId, receiverDeviceId, senderPubB64, "malware.pdf", fileSize,
        )
        assertNotEquals(base, renamed)
        assertNotEquals(
            Crypto.computeAuthTag(peerKey, base),
            Crypto.computeAuthTag(peerKey, renamed),
        )
    }

    @Test
    fun fileSizeIsBoundByTheTag() {
        val base = TransferOfferCodec.v1OfferAuthMessage(
            transferId, receiverDeviceId, senderPubB64, fileName, fileSize,
        )
        val resized = TransferOfferCodec.v1OfferAuthMessage(
            transferId, receiverDeviceId, senderPubB64, fileName, 12346L,
        )
        assertNotEquals(base, resized)
    }

    @Test
    fun utf8FileNamesSurviveTheJourney() {
        val base = TransferOfferCodec.v1OfferAuthMessage(
            transferId, receiverDeviceId, senderPubB64, "résumé ✓.pdf", fileSize,
        )
        val expected =
            "offer:v1:" + transferId + ":" + receiverDeviceId + ":" + senderPubB64 + ":" +
                Base64.getEncoder().encodeToString("résumé ✓.pdf".toByteArray(Charsets.UTF_8)) + ":" +
                fileSize
        assertEquals(expected, base)
    }

    private fun hmacHex(message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(peerKey, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
