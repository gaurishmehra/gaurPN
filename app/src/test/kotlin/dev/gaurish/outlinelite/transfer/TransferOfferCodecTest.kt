package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferOfferCodecTest {

    private fun msg(open: Boolean): String = TransferOfferCodec.offerAuthMessage(
        transferId = "tx-abc123",
        receiverDeviceId = "android-1a2b3c4d",
        senderPubB64 = "QUJD",
        fileHash = "deadbeef",
        chunkCount = 4,
        chunkSize = 262144,
        openOnArrival = open,
    )

    @Test
    fun offerAuthMessageAppendsOpenBitWhenOpenRequested() {
        assertEquals(
            "offer:v2:tx-abc123:android-1a2b3c4d:QUJD:deadbeef:4:262144:1",
            msg(open = true),
        )
    }

    @Test
    fun offerAuthMessageAppendsZeroBitWhenOpenNotRequested() {
        assertEquals(
            "offer:v2:tx-abc123:android-1a2b3c4d:QUJD:deadbeef:4:262144:0",
            msg(open = false),
        )
    }

    @Test
    fun openBitFlipsSignedMessage() {
        org.junit.Assert.assertNotEquals(msg(true), msg(false))
        assertEquals(TransferOfferCodec.openBit(true), "1")
        assertEquals(TransferOfferCodec.openBit(false), "0")
    }

    @Test
    fun resolveMimeTypePrefersResolverMime() {
        assertEquals(
            "text/plain",
            TransferOfferCodec.resolveMimeType("text/plain", "application/pdf"),
        )
    }

    @Test
    fun resolveMimeTypeUsesExtensionMimeWhenResolverReturnsNull() {
        assertEquals(
            "application/pdf",
            TransferOfferCodec.resolveMimeType(null, "application/pdf"),
        )
    }

    @Test
    fun resolveMimeTypeFallsBackToOctetStream() {
        // contentResolver.getType returned null and the extension lookup found nothing.
        assertEquals(
            "application/octet-stream",
            TransferOfferCodec.resolveMimeType(null, null),
        )
        assertEquals(
            "application/octet-stream",
            TransferOfferCodec.resolveMimeType(null, ""),
        )
        assertEquals(
            "application/octet-stream",
            TransferOfferCodec.resolveMimeType("", "  "),
        )
    }
}
