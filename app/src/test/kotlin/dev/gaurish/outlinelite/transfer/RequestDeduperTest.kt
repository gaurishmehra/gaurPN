package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RequestDeduperTest {

    @Test
    fun firstSightingIsRecordedAndRepeatsAreReported() {
        val deduper = RequestDeduper()
        assertFalse(deduper.seen("req-0000000000000001"))
        assertTrue(deduper.seen("req-0000000000000001"))
        assertTrue(deduper.seen("req-0000000000000001"))

        // A different id is independent.
        assertFalse(deduper.seen("req-0000000000000002"))
        assertTrue(deduper.seen("req-0000000000000002"))
    }

    @Test
    fun capacityEvictsTheLeastRecentlySeenId() {
        val deduper = RequestDeduper(capacity = 3)
        assertFalse(deduper.seen("a"))
        assertFalse(deduper.seen("b"))
        assertFalse(deduper.seen("c"))

        // Touching "a" makes "b" the least recently seen entry.
        assertTrue(deduper.seen("a"))
        assertFalse(deduper.seen("d"))
        assertFalse(deduper.seen("b"))

        // "d" was recorded after the eviction and is still remembered.
        assertTrue(deduper.seen("d"))
    }

    @Test
    fun defaultCapacityIsBounded() {
        assertEquals(4096, RequestDeduper.DEFAULT_CAPACITY)
        val deduper = RequestDeduper()
        repeat(RequestDeduper.DEFAULT_CAPACITY + 50) { i ->
            assertFalse(deduper.seen("req-%016x".format(i)))
        }
        // The oldest ids fell out; the newest are still remembered.
        assertFalse(deduper.seen("req-%016x".format(0)))
        assertTrue(deduper.seen("req-%016x".format(RequestDeduper.DEFAULT_CAPACITY + 49)))
    }

    @Test
    fun concurrentCallersSeeEachIdExactlyOnce() {
        val deduper = RequestDeduper(capacity = 4096)
        val threads = 8
        val perThread = 200
        val firstSightings = AtomicInteger()
        val duplicates = AtomicInteger()
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)

        repeat(threads) { thread ->
            pool.execute {
                start.await()
                try {
                    repeat(perThread) { index ->
                        val id = "req-%016x".format(thread * perThread + index)
                        if (deduper.seen(id)) {
                            duplicates.incrementAndGet()
                        } else {
                            firstSightings.incrementAndGet()
                        }
                    }
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        assertTrue("workers did not finish", done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(threads * perThread, firstSightings.get())
        assertEquals(0, duplicates.get())
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroCapacityIsRejected() {
        RequestDeduper(capacity = 0)
    }
}
