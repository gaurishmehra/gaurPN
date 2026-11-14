package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the only gate available for a senderless sealed decline: the request
 * must be one this phone actually sent and is still waiting on.
 */
class OutstandingRequestsTest {

    private val now = 1_700_000_000_000L

    @Test
    fun defaultCapacityIsPinned() {
        assertEquals(64, OutstandingRequests.DEFAULT_CAPACITY)
    }

    @Test
    fun matchingDeclineIsAcceptedExactlyOnce() {
        val outstanding = OutstandingRequests()
        outstanding.add("req-0011223344556677", now + 60_000L, now)

        assertTrue(outstanding.take("req-0011223344556677", now + 1_000L))
        // A replay of the same decline finds nothing.
        assertFalse(outstanding.take("req-0011223344556677", now + 1_000L))
    }

    @Test
    fun unknownIdsAreDropped() {
        val outstanding = OutstandingRequests()
        outstanding.add("req-0011223344556677", now + 60_000L, now)

        // A reflected decline for a request this phone never made.
        assertFalse(outstanding.take("req-8899aabbccddeeff", now))
        assertEquals(1, outstanding.size(now))
    }

    @Test
    fun staleEntriesAreDroppedAtTheBoundary() {
        val outstanding = OutstandingRequests()
        outstanding.add("req-0011223344556677", now + 1_000L, now)

        assertFalse(outstanding.take("req-0011223344556677", now + 1_000L))
        assertEquals(0, outstanding.size(now + 1_000L))
    }

    @Test
    fun capacityEvictsTheOldestRequest() {
        val outstanding = OutstandingRequests(capacity = 3)
        outstanding.add("req-0000000000000001", now + 60_000L, now)
        outstanding.add("req-0000000000000002", now + 60_000L, now)
        outstanding.add("req-0000000000000003", now + 60_000L, now)
        outstanding.add("req-0000000000000004", now + 60_000L, now)

        assertEquals(3, outstanding.size(now))
        assertFalse(outstanding.take("req-0000000000000001", now))
        assertTrue(outstanding.take("req-0000000000000004", now))
    }

    @Test
    fun expiredEntriesDoNotCountTowardCapacity() {
        val outstanding = OutstandingRequests(capacity = 2)
        outstanding.add("req-0000000000000001", now + 1_000L, now)
        outstanding.add("req-0000000000000002", now + 1_000L, now)

        // After both expire, new requests fit without evicting each other.
        outstanding.add("req-0000000000000003", now + 60_000L, now + 2_000L)
        outstanding.add("req-0000000000000004", now + 60_000L, now + 2_000L)
        assertEquals(2, outstanding.size(now + 2_000L))
        assertTrue(outstanding.take("req-0000000000000003", now + 2_000L))
        assertTrue(outstanding.take("req-0000000000000004", now + 2_000L))
    }
}
