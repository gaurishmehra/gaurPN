package dev.gaurish.outlinelite.transfer

import kotlin.math.min
import kotlin.math.pow

/**
 * Exponential reconnect backoff with jitter.
 *
 * The delay for attempt n is `min(maxMs, baseMs * 2^n)`, spread by
 * ±[jitterRatio] so a cohort of reconnecting clients does not synchronize
 * into a thundering herd against the relay.
 *
 * Pure Kotlin — no Android imports — and deterministic under a seeded
 * [random], so the bounds are JVM unit-testable.
 */
class ReconnectBackoff(
    private val baseMs: Long = 2_000,
    private val maxMs: Long = 30_000,
    private val jitterRatio: Double = 0.2,
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
) {
    init {
        require(baseMs > 0) { "baseMs must be positive" }
        require(maxMs >= baseMs) { "maxMs must be >= baseMs" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be within [0, 1]" }
    }

    /** Deepest attempt fed to [delayFor] since the last [reset]. */
    private var attempt = 0

    /**
     * Delay to wait before the [attempt]-th retry (0-based count of failed
     * attempts so far). Doubles per attempt up to [maxMs], then jitters the
     * result. Huge attempt counts saturate at [maxMs] instead of overflowing.
     */
    fun delayFor(attempt: Int): Long {
        val safe = attempt.coerceAtLeast(0)
        this.attempt = safe
        val exponential = baseMs * 2.0.pow(safe.toDouble())
        val raw = min(exponential, maxMs.toDouble())
        val spread = 1.0 + (random.nextDouble() * 2.0 - 1.0) * jitterRatio
        return (raw * spread).toLong().coerceAtLeast(1L)
    }

    /** Returns to the base delay after a successful connection. */
    fun reset() {
        attempt = 0
    }
}
