package dev.gaurish.outlinelite.transfer

/**
 * One approval prompt that is waiting for the user's tap. [notifId] is held
 * until the prompt is answered or expires so a concurrent post cannot take
 * the id while the notification is still visible.
 */
internal data class PendingApproval(
    val requestId: String,
    val requesterId: String,
    val requesterName: String,
    val trustId: String,
    val expiresTs: Long,
    val grant: String,
    val notifId: Int,
)

/**
 * Bounded registry of live approval prompts. At most [capacity] prompts are
 * tracked; the oldest is evicted when the cap is reached, and the caller is
 * responsible for cancelling its notification. Expiry is lazy: no timer is
 * installed, consistent with the rest of the request code.
 */
internal class ApprovalRegistry(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val pending = object : LinkedHashMap<String, PendingApproval>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PendingApproval>?): Boolean =
            size > capacity
    }

    /** Adds one prompt. Returns the evicted prompt when the cap was reached. */
    fun add(entry: PendingApproval): PendingApproval? = synchronized(pending) {
        val evicted = if (!pending.containsKey(entry.requestId) && pending.size >= capacity) {
            pending.entries.iterator().next().value
        } else {
            null
        }
        pending[entry.requestId] = entry
        evicted
    }

    /** Removes and returns the prompt for [requestId], if any. */
    fun take(requestId: String): PendingApproval? = synchronized(pending) {
        pending.remove(requestId)
    }

    /** True while an unexpired prompt for [trustId] exists. */
    fun hasActiveFor(trustId: String, now: Long): Boolean = synchronized(pending) {
        pending.values.any { it.trustId == trustId && now < it.expiresTs }
    }

    /** Removes and returns every expired prompt. */
    fun removeExpired(now: Long): List<PendingApproval> = synchronized(pending) {
        val expired = pending.values.filter { now >= it.expiresTs }
        expired.forEach { pending.remove(it.requestId) }
        expired
    }

    fun size(): Int = synchronized(pending) { pending.size }

    companion object {
        const val DEFAULT_CAPACITY = 16
    }
}
