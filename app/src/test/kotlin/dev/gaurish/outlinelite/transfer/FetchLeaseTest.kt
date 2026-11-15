package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the fetch lease lifecycle: the grant's expiry gates only *new*
 * fetches, and an accepted fetch holds the single slot until its send job
 * completes, however long the bytes take.
 */
class FetchLeaseTest {

    private val now = 1_700_000_000_000L
    private val grantExpiry = now + RequestCodec.REQUEST_TTL_MS

    private fun heldLease(
        id: String = "req-0011223344556677",
        trustId: String = "laptop-1",
    ) = RequestSlot.Lease(
        id = id,
        expiresTs = RequestSlot.HELD_UNTIL_RELEASED,
        trustId = trustId,
    )

    @Test
    fun leaseOutlivesTheGrantUntilTheJobReleasesIt() {
        val slot = RequestSlot()
        val lease = heldLease()
        assertTrue(slot.acquire(lease, now).acquired)

        // A 4 GiB send can run for hours; the five-minute grant must not let
        // a second fetch displace the live lease.
        val afterGrant = grantExpiry + 60 * 60 * 1000L
        assertTrue(slot.isActive(lease.id, afterGrant))
        assertFalse(
            slot.acquire(heldLease("req-8899aabbccddeeff", "laptop-2"), afterGrant).acquired,
        )

        // The send job's finally releases it, and the next fetch is accepted.
        assertEquals(lease, slot.release(lease.id))
        assertTrue(
            slot.acquire(heldLease("req-8899aabbccddeeff", "laptop-2"), afterGrant).acquired,
        )
    }

    @Test
    fun secondFetchIsBusyWhileTheSendIsActive() {
        val slot = RequestSlot()
        assertTrue(slot.acquire(heldLease(), now).acquired)

        val second = slot.acquire(heldLease("req-8899aabbccddeeff", "laptop-2"), now + 1)
        assertFalse(second.acquired)
        assertNull(second.displaced)
        // The running lease is untouched: nothing to cancel, nothing to
        // release early.
        assertTrue(slot.isActive("req-0011223344556677", now + 1))
    }

    @Test
    fun expiredGrantIsRejectedForNewFetches() {
        assertFalse(RequestCodec.isExpired(grantExpiry, grantExpiry - 1))
        assertTrue(RequestCodec.isExpired(grantExpiry, grantExpiry))
        assertTrue(RequestCodec.isExpired(grantExpiry, grantExpiry + 1))

        // parseFetch refuses an already-dead window outright, so an expired
        // fetch never reaches the slot at all.
        val expired = RequestCodec.buildFetch(
            requestId = "req-0011223344556677",
            requesterId = "laptop-1",
            requesterName = "Laptop",
            targetId = "android-1",
            paths = listOf("Pictures/a.jpg"),
            expiresTs = grantExpiry,
            grant = "cd232c541e09733b88b73687f7b4806e7cf8ad22995adf2b5fe7542bc77506d6",
            ts = now,
        )
        assertNull(RequestCodec.parseFetch(expired, grantExpiry))
    }
}
