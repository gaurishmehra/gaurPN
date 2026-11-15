package dev.gaurish.outlinelite.transfer

import java.util.concurrent.atomic.AtomicReference

/**
 * The single request lease.
 *
 * The phone serves at most one pull at a time: a screenshot request holds the
 * slot from acceptance until the send finishes or its lease expires; a browse
 * fetch holds it until its send job completes. The lease can also hold a
 * notification id (the old picker flow did); the id must stay allocated until
 * the request is answered, otherwise a crafted transfer id could steal the
 * notification slot while the request is still pending.
 *
 * Expiry is lazy — there is no timer. An expired lease is displaced by the
 * next request, which then releases the stale notification id. A fetch lease
 * is different: it uses [HELD_UNTIL_RELEASED] and stays live until its send
 * job releases it, because a 4 GiB send can outlive the five-minute grant;
 * the grant's expiry only gates whether a *new* fetch is accepted. Pure
 * Kotlin so the reclaim and re-validation rules are JVM-testable.
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

    /**
     * The trust id of [requestId] while it is the live lease, else null.
     */
    fun trustIdIfActive(requestId: String, now: Long = System.currentTimeMillis()): String? =
        activeLease(now)?.takeIf { it.id == requestId }?.trustId

    companion object {
        /**
         * Lease expiry for work that may outlive its grant. A fetch lease is
         * held until its send job completes; the grant's own expiry only
         * gates new fetches. No wall clock reaches this value.
         */
        const val HELD_UNTIL_RELEASED = Long.MAX_VALUE
    }
}
