package dev.gaurish.outlinelite.transfer

/**
 * Per-requester throttle for browse listings.
 *
 * A listing is served without a user tap, so one member must not be able to
 * walk the tree on a loop. At most [MAX_PER_WINDOW] accepted listings per
 * trust id inside a one-second sliding window; excess requests are answered
 * with a `rate_limited` listing error instead of being served. The map is
 * bounded: the least recently seen requester is evicted once [capacity] is
 * exceeded. Pure Kotlin, JVM-testable.
 */
internal class BrowseRateLimiter(
    private val maxPerWindow: Int = MAX_PER_WINDOW,
    private val windowMs: Long = WINDOW_MS,
    private val capacity: Int = DEFAULT_CAPACITY,
) {

    init {
        require(maxPerWindow > 0) { "maxPerWindow must be positive" }
        require(windowMs > 0) { "windowMs must be positive" }
        require(capacity > 0) { "capacity must be positive" }
    }

    private val hits = object : LinkedHashMap<String, ArrayDeque<Long>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ArrayDeque<Long>>?,
        ): Boolean = size > capacity
    }

    /** True when [trustId] may receive another listing at [now]. */
    fun isAllowed(trustId: String, now: Long = System.currentTimeMillis()): Boolean =
        synchronized(hits) {
            val stamps = hits[trustId] ?: return true
            prune(stamps, now)
            if (stamps.isEmpty()) return true
            // A backwards clock fails closed, like ScreenshotRateLimiter.
            if (now < stamps.last()) return false
            stamps.size < maxPerWindow
        }

    /** Records an accepted listing; call only after the decision to serve. */
    fun record(trustId: String, now: Long = System.currentTimeMillis()) {
        synchronized(hits) {
            val stamps = hits.getOrPut(trustId) { ArrayDeque() }
            prune(stamps, now)
            stamps.addLast(now)
        }
    }

    fun size(): Int = synchronized(hits) { hits.size }

    private fun prune(stamps: ArrayDeque<Long>, now: Long) {
        while (stamps.isNotEmpty() && now - stamps.first() >= windowMs) {
            stamps.removeFirst()
        }
    }

    companion object {
        const val MAX_PER_WINDOW = 2
        const val WINDOW_MS = 1000L
        const val DEFAULT_CAPACITY = 64
    }
}
