package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the single-request lease: one live request at a time, expiry reclaimed
 * lazily, and the notification id held until the request is answered.
 */
class RequestSlotTest {

    private val now = 1_700_000_000_000L

    private fun lease(
        id: String = "req-0011223344556677",
        expiresTs: Long = now + 60_000L,
        trustId: String = "lilac-76ee",
        notifId: Int? = 42,
    ) = RequestSlot.Lease(id, expiresTs, trustId, notifId)

    @Test
    fun secondRequestIsBusyWhileTheLeaseIsLive() {
        val slot = RequestSlot()
        assertTrue(slot.acquire(lease(), now).acquired)

        val second = slot.acquire(lease(id = "req-8899aabbccddeeff"), now + 1)
        assertFalse(second.acquired)
        assertNull(second.displaced)
        assertTrue(slot.isActive("req-0011223344556677", now + 1))
    }

    @Test
    fun expiredLeaseIsReclaimedAtTheBoundary() {
        val slot = RequestSlot()
        val first = lease(expiresTs = now + 1_000L)
        assertTrue(slot.acquire(first, now).acquired)

        // now < expiresTs: still live.
        assertFalse(slot.acquire(lease(id = "req-8899aabbccddeeff"), now + 999).acquired)

        // now == expiresTs is expired; the next request takes the slot and
        // the caller gets the displaced lease so it can free the id.
        val result = slot.acquire(lease(id = "req-8899aabbccddeeff"), now + 1_000)
        assertTrue(result.acquired)
        assertEquals(first, result.displaced)
        assertTrue(slot.isActive("req-8899aabbccddeeff", now + 1_000))
        assertFalse(slot.isActive("req-0011223344556677", now + 1_000))
    }

    @Test
    fun releaseOnlyMatchesTheOwner() {
        val slot = RequestSlot()
        val held = lease()
        assertTrue(slot.acquire(held, now).acquired)

        assertNull(slot.release("req-8899aabbccddeeff"))
        assertTrue(slot.isActive(held.id, now))

        assertEquals(held, slot.release(held.id))
        assertFalse(slot.isActive(held.id, now))
        assertNull(slot.release(held.id))
    }

    @Test
    fun trustIdIsExposedOnlyWhileActive() {
        val slot = RequestSlot()
        val held = lease(expiresTs = now + 1_000L, trustId = "lilac-76ee")
        assertTrue(slot.acquire(held, now).acquired)

        assertEquals("lilac-76ee", slot.trustIdIfActive(held.id, now + 999))
        assertNull(slot.trustIdIfActive("req-8899aabbccddeeff", now))
        assertNull(slot.trustIdIfActive(held.id, now + 1_000))
    }

    @Test
    fun notificationLeaseIsHeldUntilTheRequestIsAnswered() {
        val slot = RequestSlot()
        val held = lease(notifId = 20_123)
        assertTrue(slot.acquire(held, now).acquired)

        // Time passes but the request is still pending: the id stays leased.
        assertEquals(20_123, slot.activeLease(now + 30_000)!!.notifId)
        assertEquals(20_123, slot.activeLease(now + 59_999)!!.notifId)

        // Answered: the lease (and the id) comes back exactly once.
        assertEquals(20_123, slot.release(held.id)!!.notifId)
        assertNull(slot.activeLease(now + 1))
    }
}
