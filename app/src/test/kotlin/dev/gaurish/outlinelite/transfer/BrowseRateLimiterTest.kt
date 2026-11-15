package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseRateLimiterTest {

    @Test
    fun twoAcceptedBrowsesPerSecondThenRateLimited() {
        val limiter = BrowseRateLimiter()
        assertTrue(limiter.isAllowed("laptop-1", now = 0L))
        limiter.record("laptop-1", now = 0L)
        assertTrue(limiter.isAllowed("laptop-1", now = 100L))
        limiter.record("laptop-1", now = 100L)
        assertFalse(limiter.isAllowed("laptop-1", now = 200L))
        assertFalse(limiter.isAllowed("laptop-1", now = 999L))
    }

    @Test
    fun windowSlidesAndRecovers() {
        val limiter = BrowseRateLimiter()
        limiter.record("laptop-1", now = 0L)
        limiter.record("laptop-1", now = 100L)
        // At t=1000 the t=0 hit has aged out; one slot is free again.
        assertTrue(limiter.isAllowed("laptop-1", now = 1_000L))
        limiter.record("laptop-1", now = 1_000L)
        // t=100 is still inside the window, so the next request waits.
        assertFalse(limiter.isAllowed("laptop-1", now = 1_001L))
        // At t=1100 the t=100 hit ages out too.
        assertTrue(limiter.isAllowed("laptop-1", now = 1_100L))
    }

    @Test
    fun throttlingIsPerTrustId() {
        val limiter = BrowseRateLimiter()
        limiter.record("laptop-1", now = 0L)
        limiter.record("laptop-1", now = 0L)
        assertFalse(limiter.isAllowed("laptop-1", now = 0L))
        assertTrue(limiter.isAllowed("laptop-2", now = 0L))
    }

    @Test
    fun mapIsBounded() {
        val limiter = BrowseRateLimiter(capacity = 2)
        limiter.record("a", now = 0L)
        limiter.record("b", now = 0L)
        limiter.record("c", now = 0L)
        assertEquals(2, limiter.size())
    }

    @Test
    fun backwardsClockFailsClosed() {
        val limiter = BrowseRateLimiter()
        limiter.record("laptop-1", now = 10_000L)
        assertFalse(limiter.isAllowed("laptop-1", now = 5_000L))
    }

    @Test
    fun defaultsArePinned() {
        assertEquals(2, BrowseRateLimiter.MAX_PER_WINDOW)
        assertEquals(1_000L, BrowseRateLimiter.WINDOW_MS)
    }
}
