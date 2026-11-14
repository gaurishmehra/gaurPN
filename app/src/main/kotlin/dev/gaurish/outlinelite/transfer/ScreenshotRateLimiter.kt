package dev.gaurish.outlinelite.transfer

/**
 * Per-requester throttle for automatic screenshot replies.
 *
 * A screenshot request is served without a user tap, so one member must not
 * be able to pull the latest capture on a loop. The map is bounded: the least
 * recently seen requester is evicted once [capacity] is exceeded. Pure Kotlin,
 * JVM-testable.
 */
internal class ScreenshotRateLimiter(
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val capacity: Int = DEFAULT_CAPACITY,
) {

    init {
        require(minIntervalMs > 0) { "minIntervalMs must be positive" }
        require(capacity > 0) { "capacity must be positive" }
    }

    private val lastSentAt = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > capacity
    }

    /** True when [requesterId] may receive another screenshot at [now]. */
    fun isAllowed(requesterId: String, now: Long = System.currentTimeMillis()): Boolean =
        synchronized(lastSentAt) {
            val last = lastSentAt[requesterId] ?: return true
            // A backwards clock fails closed.
            now >= last && now - last >= minIntervalMs
        }

    /** Records a successful send; call only after the reply was handed off. */
    fun record(requesterId: String, now: Long = System.currentTimeMillis()) {
        synchronized(lastSentAt) { lastSentAt[requesterId] = now }
    }

    fun size(): Int = synchronized(lastSentAt) { lastSentAt.size }

    companion object {
        const val MIN_INTERVAL_MS = 60_000L
        const val DEFAULT_CAPACITY = 32
    }
}
