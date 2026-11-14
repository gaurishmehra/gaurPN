package dev.gaurish.outlinelite.transfer

/**
 * Pure helpers shared by the sender and receiver of v2 transfer offers.
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
