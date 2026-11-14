package dev.gaurish.outlinelite.transfer

import dev.gaurish.outlinelite.util.StdBase64

/**
 * Pure helpers shared by the sender and receiver of transfer offers.
 * Kept free of Android dependencies so JVM unit tests can verify the
 * offer-auth contract directly.
 */
internal object TransferOfferCodec {

    /**
     * Canonical offer verification string for v2 transfers. The sender signs
     * this and the receiver rebuilds it before trusting an offer. The trailing
     * [openBit] is "1" when the sender asked the receiver to open the file on
     * arrival, "0" otherwise.
     */
    fun offerAuthMessage(
        transferId: String,
        receiverDeviceId: String,
        senderPubB64: String,
        fileHash: String,
        chunkCount: Int,
        chunkSize: Int,
        openOnArrival: Boolean,
    ): String =
        "offer:v2:$transferId:$receiverDeviceId:$senderPubB64:$fileHash:$chunkCount:$chunkSize:" +
            openBit(openOnArrival)

    /**
     * Canonical offer verification string for v1 transfers. Binds the file
     * name and size so a relay cannot strip or swap the payload after the
     * sender signed the offer. Computed over the RAW envelope fields, before
     * any sanitization.
     *
     * Layout (desktop client mirrors this in Go with base64.StdEncoding):
     * "offer:v1:" + transferId + ":" + receiverDeviceId + ":" + senderPubB64 +
     * ":" + base64Std(fileNameUtf8) + ":" + fileSize
     */
    fun v1OfferAuthMessage(
        transferId: String,
        receiverDeviceId: String,
        senderPubB64: String,
        fileName: String,
        fileSize: Long,
    ): String =
        "offer:v1:$transferId:$receiverDeviceId:$senderPubB64:" +
            StdBase64.encode(fileName.toByteArray(Charsets.UTF_8)) + ":$fileSize"

    fun openBit(openOnArrival: Boolean): String = if (openOnArrival) "1" else "0"

    /**
     * Picks the mime type for a transferred file: the resolver/offer mime wins,
     * then the extension-derived mime, then a generic binary fallback. Never
     * returns null or blank.
     */
    fun resolveMimeType(resolverMime: String?, extensionMime: String?): String =
        resolverMime?.takeIf { it.isNotBlank() }
            ?: extensionMime?.takeIf { it.isNotBlank() }
            ?: "application/octet-stream"
}
