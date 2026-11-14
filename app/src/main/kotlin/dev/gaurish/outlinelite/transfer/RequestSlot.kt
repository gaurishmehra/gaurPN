package dev.gaurish.outlinelite.transfer

import java.util.concurrent.atomic.AtomicReference

/**
 * The single request lease.
 *
 * A file request parks in a notification until the user answers it, so the
 * phone serves at most one at a time. The lease also holds the notification
 * id: the id must stay allocated until the request is answered (picked,
 * declined, or reclaimed after expiry), otherwise a crafted transfer id could
 * steal the notification slot while the request is still pending.
 *
 * Expiry is lazy — there is no timer. An expired lease is displaced by the
 * next request, which then releases the stale notification id. Pure Kotlin so
 * the reclaim and re-validation rules are JVM-testable.
 */
internal class RequestSlot {

    data class Lease(
        val id: String,
        val expiresTs: Long,
        val trustId: String,
        val notifId: Int? = null,
    )

    data class AcquireResult(val acquired: Boolean, val displaced: Lease? = null)

    private val lease = AtomicReference<Lease?>(null)

    /**
     * Claims the slot unless a live lease is held. `now >= expiresTs` means
     * the previous lease is dead and is returned as [AcquireResult.displaced]
     * so its notification id can be released.
     */
    fun acquire(next: Lease, now: Long = System.currentTimeMillis()): AcquireResult {
        while (true) {
            val current = lease.get()
            if (current != null && now < current.expiresTs) return AcquireResult(false)
            if (lease.compareAndSet(current, next)) return AcquireResult(true, current)
        }
    }

    /** Releases [requestId] only when it still owns the slot. */
    fun release(requestId: String): Lease? {
        while (true) {
            val current = lease.get() ?: return null
            if (current.id != requestId) return null
            if (lease.compareAndSet(current, null)) return current
        }
    }

    fun activeLease(now: Long = System.currentTimeMillis()): Lease? =
        lease.get()?.takeIf { now < it.expiresTs }

    fun isActive(requestId: String, now: Long = System.currentTimeMillis()): Boolean =
        activeLease(now)?.id == requestId

    /** The trust id of [requestId] while it is the live lease, else null. */
    fun trustIdIfActive(requestId: String, now: Long = System.currentTimeMillis()): String? =
        activeLease(now)?.takeIf { it.id == requestId }?.trustId
}
