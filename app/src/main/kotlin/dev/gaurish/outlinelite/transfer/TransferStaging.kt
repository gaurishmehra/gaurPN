package dev.gaurish.outlinelite.transfer

import java.io.File

/**
 * Hidden staging paths for an inbound transfer.
 *
 * Every receive path (v1 pipe, v2 chunks, v3 sealed offer) builds its
 * `.part`/`.state` names here. The transfer id is peer-supplied, so the shape
 * check plus the canonical-path check are the traversal guard: a crafted id
 * such as `../../evil` must never steer a write outside Downloads. Pure JVM
 * so the guard is unit-testable without Android.
 */
internal object TransferStaging {

    data class Files(val part: File, val state: File)

    /**
     * Returns the staging pair for [transferId], or null when the id is not
     * `tx-<32 lowercase hex>` or either path resolves outside [downloadsDir].
     */
    fun forTransfer(downloadsDir: File, cleanName: String, transferId: String): Files? {
        if (!Crypto.isTransferId(transferId)) return null
        val part = File(downloadsDir, ".$cleanName.$transferId.part")
        val state = File(downloadsDir, ".$cleanName.$transferId.state")
        if (!Crypto.isPathInsideDirectory(part, downloadsDir)) return null
        if (!Crypto.isPathInsideDirectory(state, downloadsDir)) return null
        return Files(part, state)
    }
}
