package dev.gaurish.outlinelite.transfer

/**
 * Notification id allocation for in-flight transfers.
 *
 * A transfer's progress notification needs a stable id for its whole
 * lifetime (so updates replace each other), and two concurrent transfers
 * must never share one — they would clobber each other's progress. The
 * preferred id is derived from the transfer id; a collision with an id
 * still in use is resolved by probing upward, wrapping within the base's
 * 24-bit range.
 *
 * Pure Kotlin — no Android imports — so allocation is JVM unit-testable.
 */
object NotificationIds {
    const val SEND_BASE = 10_000
    const val RECEIVE_BASE = 20_000
    private const val MASK = 0xFFFFFF
    private const val RANGE = MASK + 1

    private val lock = Any()
    private val inUse = mutableSetOf<Int>()

    /**
     * Acquires a stable notification id for a transfer. Distinct concurrent
     * transfers always get distinct ids, whatever their hash codes; the same
     * transfer id maps back to the same id once its previous lease is
     * released.
     */
    fun acquire(transferId: String, sending: Boolean): Int {
        val base = if (sending) SEND_BASE else RECEIVE_BASE
        synchronized(lock) {
            val preferred = base + (transferId.hashCode() and MASK)
            var candidate = preferred
            var probes = 0
            while (candidate in inUse && probes < RANGE) {
                candidate = base + ((candidate - base + 1) and MASK)
                probes++
            }
            if (candidate in inUse) candidate = preferred // range exhausted (unreachable in practice)
            inUse.add(candidate)
            return candidate
        }
    }

    /** Returns an id to the pool. Call once per successful [acquire]. */
    fun release(id: Int) {
        synchronized(lock) {
            inUse.remove(id)
        }
    }
}
