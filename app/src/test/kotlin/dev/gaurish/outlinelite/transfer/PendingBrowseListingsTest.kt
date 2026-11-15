package dev.gaurish.outlinelite.transfer

import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PendingBrowseListingsTest {

    private val requestId = "req-0011223344556677"
    private val otherId = "req-8899aabbccddeeff"

    private fun deferred() = CompletableDeferred<BrowseListingEnvelope>()

    @Test
    fun wrongPathReplyLeavesThePendingEntry() {
        val pending = PendingBrowseListings()
        val deferred = deferred()
        pending.add(requestId, "Pictures", deferred)

        // A reply for another directory must not consume the entry: the real
        // answer may still arrive, and the timeout cleans up otherwise.
        assertNull(pending.take(requestId, "Movies"))
        assertEquals(1, pending.size())

        val matched = pending.take(requestId, "Pictures")
        assertNotNull(matched)
        assertEquals(deferred, matched!!.deferred)
        assertEquals("Pictures", matched.path)
        assertEquals(0, pending.size())
    }

    @Test
    fun takeIsSingleUseAndUnknownIdsAreDropped() {
        val pending = PendingBrowseListings()
        pending.add(requestId, "", deferred())

        assertNull(pending.take(otherId, ""))
        assertNotNull(pending.take(requestId, ""))
        assertNull(pending.take(requestId, ""))
        assertEquals(0, pending.size())
    }

    @Test
    fun removeDropsTheEntryUnconditionally() {
        val pending = PendingBrowseListings()
        pending.add(requestId, "Music", deferred())
        pending.remove(requestId)
        assertEquals(0, pending.size())
        assertNull(pending.take(requestId, "Music"))
    }
}
