package dev.gaurish.outlinelite.transfer

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/**
 * Browse listings this phone asked for and is still waiting on, keyed by
 * request id.
 *
 * The id is generated locally and only sent to one peer, so a reply that
 * finds no entry is dropped like an unknown decline. The expected path
 * travels with the entry so a reply for a different directory cannot be
 * spliced into the screen — and, critically, cannot consume the entry either:
 * the real answer may still arrive, and the requester's timeout cleans up if
 * it does not. Pure Kotlin, JVM-testable.
 */
internal class PendingBrowseListings {

    internal data class Pending(
        val path: String,
        val deferred: CompletableDeferred<BrowseListingEnvelope>,
    )

    private val entries = ConcurrentHashMap<String, Pending>()

    fun add(
        requestId: String,
        path: String,
        deferred: CompletableDeferred<BrowseListingEnvelope>,
    ) {
        entries[requestId] = Pending(path, deferred)
    }

    /** Drops [requestId] unconditionally; the requester's finally path. */
    fun remove(requestId: String) {
        entries.remove(requestId)
    }

    /**
     * Returns the entry for a reply at [path], removing it only when the path
     * matches. A wrong-path reply leaves the entry in place.
     */
    fun take(requestId: String, path: String): Pending? {
        while (true) {
            val current = entries[requestId] ?: return null
            if (current.path != path) return null
            if (entries.remove(requestId, current)) return current
        }
    }

    fun size(): Int = entries.size
}
