package dev.gaurish.outlinelite.transfer

/**
 * Registry of in-flight transfers and how to cancel them.
 *
 * [RelayManager.stop] drains this registry so active sends and receives do
 * not keep retrying against a dead relay after the tunnel goes down. Each
 * registration is a (transfer id, cancel handle) pair; a handle runs at most
 * once — either through [cancel]/[cancelAll] or displaced by a
 * re-registration for the same id (a superseded attempt).
 *
 * Pure Kotlin — no Android imports — so the exactly-once semantics are JVM
 * unit-testable under concurrency.
 */
class TransferRegistry {

    internal class Entry internal constructor(val cancel: () -> Unit)

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    /**
     * Handle for one registration. [remove] unregisters only while this
     * exact registration is still current, so a coroutine that finishes
     * after being superseded cannot drop the replacement's registration.
     */
    class Registration internal constructor(
        private val registry: TransferRegistry,
        private val id: String,
        private val entry: Entry,
    ) {
        /** Unregisters this registration without invoking its cancel handle. */
        fun remove() {
            synchronized(registry.lock) {
                if (registry.entries[id] === entry) {
                    registry.entries.remove(id)
                }
            }
        }
    }

    /**
     * Registers [cancel] under [id] and returns its registration handle.
     * Displaces (and cancels) any previous registration for the same id —
     * a new attempt supersedes the old one.
     */
    fun register(id: String, cancel: () -> Unit): Registration {
        val entry = Entry(cancel)
        val displaced = synchronized(lock) { entries.put(id, entry) }
        displaced?.let { it.cancel() }
        return Registration(this, id, entry)
    }

    /** Cancels the transfer registered under [id]; a no-op when it already finished. */
    fun cancel(id: String) {
        val entry = synchronized(lock) { entries.remove(id) } ?: return
        entry.cancel()
    }

    /** Cancels every active transfer. */
    fun cancelAll() {
        val snapshot = synchronized(lock) {
            val all = entries.values.toList()
            entries.clear()
            all
        }
        snapshot.forEach { it.cancel() }
    }

    /** Unregisters a finished transfer without invoking its cancel handle. */
    fun remove(id: String) {
        synchronized(lock) { entries.remove(id) }
    }

    /** Ids with a live registration, in registration order. */
    fun activeIds(): List<String> = synchronized(lock) { entries.keys.toList() }
}
