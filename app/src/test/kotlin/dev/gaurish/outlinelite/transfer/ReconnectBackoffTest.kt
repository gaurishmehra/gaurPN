package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReconnectBackoffTest {

    @Test
    fun delayStaysWithinJitteredBounds() {
        val backoff = ReconnectBackoff(baseMs = 2_000, maxMs = 30_000, jitterRatio = 0.2, random = Random(1))
        for (attempt in 0..40) {
            val delay = backoff.delayFor(attempt)
            assertTrue("delay $delay below floor for attempt $attempt", delay >= 1_600L)
            assertTrue("delay $delay above ceiling for attempt $attempt", delay <= 36_000L)
        }
    }

    @Test
    fun doublesPerAttemptUpToCap() {
        val backoff = ReconnectBackoff(baseMs = 1_000, maxMs = 8_000, jitterRatio = 0.0, random = Random(7))
        assertEquals(1_000L, backoff.delayFor(0))
        assertEquals(2_000L, backoff.delayFor(1))
        assertEquals(4_000L, backoff.delayFor(2))
        assertEquals(8_000L, backoff.delayFor(3))
        assertEquals(8_000L, backoff.delayFor(4))
        // Capped, and never overflows on absurd attempt counts.
        assertEquals(8_000L, backoff.delayFor(100))
        assertEquals(8_000L, backoff.delayFor(1_000))
    }

    @Test
    fun resetReturnsToBase() {
        val backoff = ReconnectBackoff(baseMs = 1_000, maxMs = 60_000, jitterRatio = 0.0, random = Random(11))
        for (attempt in 0..10) backoff.delayFor(attempt)
        assertEquals(60_000L, backoff.delayFor(10))
        backoff.reset()
        assertEquals(1_000L, backoff.delayFor(0))
    }

    @Test
    fun seededRandomIsDeterministic() {
        val a = ReconnectBackoff(baseMs = 2_000, maxMs = 30_000, jitterRatio = 0.2, random = Random(42))
        val b = ReconnectBackoff(baseMs = 2_000, maxMs = 30_000, jitterRatio = 0.2, random = Random(42))
        for (attempt in 0..20) {
            assertEquals(a.delayFor(attempt), b.delayFor(attempt))
        }
    }

    @Test
    fun jitterNeverLeavesTheExpectedSpread() {
        // Full-jitter boundary: every draw stays inside ±jitterRatio of the raw value.
        val backoff = ReconnectBackoff(baseMs = 1_000, maxMs = 4_000, jitterRatio = 0.5, random = Random(99))
        repeat(200) {
            val delay = backoff.delayFor(1) // raw = 2_000
            assertTrue(delay in 1_000L..3_000L)
        }
    }

    @Test
    fun negativeAttemptIsCoercedToBase() {
        val backoff = ReconnectBackoff(baseMs = 1_000, maxMs = 30_000, jitterRatio = 0.0, random = Random(3))
        assertEquals(1_000L, backoff.delayFor(-5))
    }
}
