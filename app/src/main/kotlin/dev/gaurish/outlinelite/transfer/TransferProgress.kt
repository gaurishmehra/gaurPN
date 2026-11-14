package dev.gaurish.outlinelite.transfer

/**
 * Shared transfer progress math. The send and receive flows throttle
 * progress updates identically and render the same
 * "&lt;speed&gt; - &lt;percent&gt;%" notification text; the overall-fraction
 * computation stays at each call site because it differs per flow (batch
 * position, resumed bytes, ...).
 */
internal object TransferProgress {

    /** Minimum ms between two progress updates pushed to the flow/notification. */
    const val THROTTLE_MS = 200L

    /** Transfer speed in MiB/s for [doneBytes] transferred over [elapsedMs]. */
    fun speedMbPerSec(doneBytes: Long, elapsedMs: Long): Float {
        val elapsedSec = elapsedMs / 1000f
        return if (elapsedSec > 0f) (doneBytes / (1024f * 1024f)) / elapsedSec else 0f
    }

    /** Progress notification text for an [overall] fraction in 0..1. */
    fun speedAndPercentText(speedMb: Float, overall: Float): String =
        "%.1f MB/s - %.0f%%".format(speedMb, overall * 100f)
}
