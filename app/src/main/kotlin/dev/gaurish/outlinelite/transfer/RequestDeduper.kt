package dev.gaurish.outlinelite.transfer

/**
 * Bounded record of recently handled request ids.
 *
 * A request can arrive twice (relay retry, reconnect, mailbox replay). The
 * first delivery is acted on; repeats are dropped. The set is capped so a
 * long-lived session cannot grow without bound: the least recently touched
 * id is evicted once [capacity] is exceeded, which is far beyond the window
 * in which a duplicate can matter.
 *
 * Pure Kotlin — no Android imports — so the exactly-once semantics are JVM
 * unit-testable under concurrency.
 */
class RequestDeduper(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val seen = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > capacity
    }

    /**
     * Records [id] and reports whether it was already present. The first
     * call for an id returns false; every later call returns true until the
     * id is evicted.
     */
    fun seen(id: String): Boolean = synchronized(seen) {
        seen.put(id, true) != null
    }

    companion object {
        /**
         * Large enough that a member cannot evict the dedup memory with a
         * flood and then replay an earlier request; still tiny in memory.
         */
        const val DEFAULT_CAPACITY = 4096
    }
}
