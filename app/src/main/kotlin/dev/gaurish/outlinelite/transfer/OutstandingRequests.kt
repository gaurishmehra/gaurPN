package dev.gaurish.outlinelite.transfer

/**
 * Requests this phone sent and is still waiting on, keyed by request id.
 *
 * A `request_declined` payload is sealed but carries no sender id, so any
 * cluster member can produce one. The only gate available is whether this
 * phone actually has that request outstanding: unknown, stale, and reflected
 * ids find nothing and are dropped. Bounded and expiry-aware so a flood
 * cannot grow it without bound. Pure Kotlin, JVM-testable.
 */
internal class OutstandingRequests(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val entries = object : LinkedHashMap<String, Long>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > capacity
    }

    fun add(requestId: String, expiresTs: Long, now: Long = System.currentTimeMillis()) {
        synchronized(entries) {
            evictExpiredLocked(now)
            entries[requestId] = expiresTs
        }
    }

    fun remove(requestId: String) {
        synchronized(entries) { entries.remove(requestId) }
    }

    /**
     * Removes [requestId] and reports whether it was outstanding and not yet
     * expired. A stale entry is removed and reported false.
     */
    fun take(requestId: String, now: Long = System.currentTimeMillis()): Boolean =
        synchronized(entries) {
            val expiresTs = entries.remove(requestId) ?: return false
            now < expiresTs
        }

    fun size(now: Long = System.currentTimeMillis()): Int = synchronized(entries) {
        evictExpiredLocked(now)
        entries.size
    }

    private fun evictExpiredLocked(now: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            if (now >= iterator.next().value) iterator.remove()
        }
    }

    companion object {
        /** Far more than the phone can have in flight at once. */
        const val DEFAULT_CAPACITY = 64
    }
}
