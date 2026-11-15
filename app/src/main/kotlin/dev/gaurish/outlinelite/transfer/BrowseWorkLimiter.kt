package dev.gaurish.outlinelite.transfer

import java.util.concurrent.atomic.AtomicInteger

/**
 * Concurrency cap for browse listings.
 *
 * A listing is served without a user tap, so the per-requester window alone
 * still lets several members (or one member with several ids) stack cursor
 * work on the phone. At most [MAX_CONCURRENT] listings are built at a time; a
 * browse that finds every worker busy is answered `rate_limited` immediately
 * instead of queueing more MediaStore work behind the running queries. Pure
 * Kotlin, JVM-testable.
 */
internal class BrowseWorkerSlots(private val maxConcurrent: Int = MAX_CONCURRENT) {

    init {
        require(maxConcurrent > 0) { "maxConcurrent must be positive" }
    }

    private val inFlight = AtomicInteger(0)

    /** Claims one worker slot, or false when all [maxConcurrent] are busy. */
    fun tryAcquire(): Boolean {
        while (true) {
            val current = inFlight.get()
            if (current >= maxConcurrent) return false
            if (inFlight.compareAndSet(current, current + 1)) return true
        }
    }

    /** Frees one slot; a stray release never drives the count negative. */
    fun release() {
        while (true) {
            val current = inFlight.get()
            if (current <= 0) return
            if (inFlight.compareAndSet(current, current - 1)) return
        }
    }

    fun inFlight(): Int = inFlight.get()

    companion object {
        const val MAX_CONCURRENT = 2
    }
}

/**
 * Phone-wide browse budget: at most [MAX_PER_WINDOW] accepted listings per
 * [WINDOW_MS] across every requester, on top of [BrowseRateLimiter]'s
 * per-device window. One member rotating trust ids, or several members at
 * once, still cannot keep MediaStore busy.
 *
 * Implemented as a sliding window over accepted timestamps (the same shape as
 * [BrowseRateLimiter]); a backwards clock fails closed. Pure Kotlin,
 * JVM-testable.
 */
internal class GlobalBrowseBucket(
    private val maxPerWindow: Int = MAX_PER_WINDOW,
    private val windowMs: Long = WINDOW_MS,
) {

    init {
        require(maxPerWindow > 0) { "maxPerWindow must be positive" }
        require(windowMs > 0) { "windowMs must be positive" }
    }

    private val stamps = ArrayDeque<Long>()

    /** True when the phone may accept another listing at [now]. */
    fun isAllowed(now: Long = System.currentTimeMillis()): Boolean = synchronized(stamps) {
        prune(now)
        if (stamps.isEmpty()) return true
        if (now < stamps.last()) return false
        stamps.size < maxPerWindow
    }

    /** Records an accepted listing; call only after the decision to serve. */
    fun record(now: Long = System.currentTimeMillis()) {
        synchronized(stamps) {
            prune(now)
            stamps.addLast(now)
        }
    }

    fun size(): Int = synchronized(stamps) { stamps.size }

    private fun prune(now: Long) {
        while (stamps.isNotEmpty() && now - stamps.first() >= windowMs) {
            stamps.removeFirst()
        }
    }

    companion object {
        const val MAX_PER_WINDOW = 10
        const val WINDOW_MS = 1_000L
    }
}
