package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotRateLimiterTest {

    private val now = 1_700_000_000_000L
    private val requester = "lilac-76ee"

    @Test
    fun defaultsArePinned() {
        assertEquals(60_000L, ScreenshotRateLimiter.MIN_INTERVAL_MS)
        assertEquals(32, ScreenshotRateLimiter.DEFAULT_CAPACITY)
    }

    @Test
    fun firstSendIsAllowedAndSecondInsideTheWindowIsBusy() {
        val limiter = ScreenshotRateLimiter()
        assertTrue(limiter.isAllowed(requester, now))
        limiter.record(requester, now)

        assertFalse(limiter.isAllowed(requester, now + 1))
        assertFalse(limiter.isAllowed(requester, now + 59_999))
        assertTrue(limiter.isAllowed(requester, now + 60_000))
    }

    @Test
    fun requestersAreIndependent() {
        val limiter = ScreenshotRateLimiter()
        limiter.record(requester, now)

        assertFalse(limiter.isAllowed(requester, now + 1))
        assertTrue(limiter.isAllowed("desk-1234", now + 1))
    }

    @Test
    fun capacityIsBoundedAndEvictsTheOldestRequester() {
        val limiter = ScreenshotRateLimiter(capacity = 2)
        limiter.record("a", now)
        limiter.record("b", now)
        limiter.record("c", now)

        assertEquals(2, limiter.size())
        // "a" fell out, so it is eligible again immediately.
        assertTrue(limiter.isAllowed("a", now + 1))
        assertFalse(limiter.isAllowed("c", now + 1))
    }

    @Test
    fun backwardsClockFailsClosed() {
        val limiter = ScreenshotRateLimiter()
        limiter.record(requester, now)
        assertFalse(limiter.isAllowed(requester, now - 1))
    }
}
