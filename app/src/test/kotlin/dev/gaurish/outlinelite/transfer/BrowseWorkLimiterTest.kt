package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the phone-wide browse bounds: two concurrent workers, and ten accepted
 * listings per second across every requester.
 */
class BrowseWorkLimiterTest {

    @Test
    fun thirdConcurrentBrowseIsRefusedUntilAWorkerFrees() {
        val slots = BrowseWorkerSlots()
        assertTrue(slots.tryAcquire())
        assertTrue(slots.tryAcquire())
        assertEquals(2, slots.inFlight())

        // The third browse is answered rate_limited without touching
        // MediaStore.
        assertFalse(slots.tryAcquire())

        slots.release()
        assertTrue(slots.tryAcquire())
        assertEquals(2, slots.inFlight())

        slots.release()
        slots.release()
        assertEquals(0, slots.inFlight())
        // A stray release cannot drive the count negative.
        slots.release()
        assertEquals(0, slots.inFlight())
        assertTrue(slots.tryAcquire())
    }

    @Test
    fun workerCapIsPinned() {
        assertEquals(2, BrowseWorkerSlots.MAX_CONCURRENT)
    }

    private data class Step(val now: Long, val allowed: Boolean)

    @Test
    fun globalBucketTable() {
        val bucket = GlobalBrowseBucket()
        val steps = buildList {
            // Ten accepted listings fill the window, whoever asked for them.
            repeat(GlobalBrowseBucket.MAX_PER_WINDOW) {
                add(Step(now = 0L, allowed = true))
            }
            add(Step(now = 0L, allowed = false))
            add(Step(now = 500L, allowed = false))
            // At t=1000 the t=0 stamps age out of the one-second window.
            add(Step(now = 1_000L, allowed = true))
            // One token used, nine remain.
            add(Step(now = 1_000L, allowed = true))
        }

        steps.forEachIndexed { index, step ->
            val actual = bucket.isAllowed(step.now)
            assertEquals("step $index at t=${step.now}", step.allowed, actual)
            if (actual) bucket.record(step.now)
        }
        assertEquals(2, bucket.size())
    }

    @Test
    fun globalBucketFailsClosedOnABackwardsClock() {
        val bucket = GlobalBrowseBucket()
        bucket.record(now = 10_000L)
        assertFalse(bucket.isAllowed(now = 5_000L))
    }

    @Test
    fun globalBudgetIsPinned() {
        assertEquals(10, GlobalBrowseBucket.MAX_PER_WINDOW)
        assertEquals(1_000L, GlobalBrowseBucket.WINDOW_MS)
    }
}
