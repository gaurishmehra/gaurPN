package dev.gaurish.outlinelite.transfer

import kotlin.math.abs

/** One battery reading from ACTION_BATTERY_CHANGED. */
data class BatterySample(val percent: Int, val charging: Boolean)

/**
 * Pure battery math for the encrypted peer status frame.
 *
 * The publisher listens to the sticky ACTION_BATTERY_CHANGED broadcast, so
 * there is no polling: the system pushes a sample on every change. This
 * object decides what the sample means and whether it is worth a frame.
 */
object BatteryStatus {

    /** BatteryManager.BATTERY_STATUS_CHARGING. */
    const val STATUS_CHARGING = 2

    /** BatteryManager.BATTERY_STATUS_FULL. */
    const val STATUS_FULL = 5

    /** A change smaller than this is noise, not news. */
    const val MIN_PERCENT_DELTA = 2

    /** At most one status frame per this window. */
    const val MIN_PUBLISH_INTERVAL_MS = 5_000L

    /**
     * Repaint threshold for an unchanged battery. Receivers drop status
     * blobs older than [PeerStatus.MAX_STATUS_AGE_MS] (10 minutes), and a
     * device parked at the same level — full and plugged in — stops
     * producing battery events. The repaint is event-driven, never a
     * timer: it happens when the relay's peers fan-out arrives, proving
     * some cluster member is listening.
     */
    const val REPAINT_AFTER_MS = 5 * 60_000L

    /**
     * Converts the raw extras into a 0..100 percentage plus a charging flag.
     * Null when the broadcast is missing usable level/scale values.
     */
    fun fromExtras(level: Int, scale: Int, status: Int): BatterySample? {
        if (level < 0 || scale <= 0) return null
        val percent = (level.toLong() * 100L / scale).toInt().coerceIn(0, 100)
        val charging = status == STATUS_CHARGING || status == STATUS_FULL
        return BatterySample(percent, charging)
    }

    /** True when [next] differs enough from [previous] to be worth sending. */
    fun isMeaningfulChange(previous: BatterySample?, next: BatterySample): Boolean {
        if (previous == null) return true
        if (previous.charging != next.charging) return true
        return abs(previous.percent - next.percent) >= MIN_PERCENT_DELTA
    }

    /**
     * Full publish decision: a meaningful change, debounced to one frame per
     * [MIN_PUBLISH_INTERVAL_MS]. [lastSentAtMs] of 0 means nothing has been
     * sent yet, so the first sample always goes out. An unchanged sample
     * whose last advertisement is older than [REPAINT_AFTER_MS] is
     * re-advertised so the stored blob never ages past the receivers'
     * freshness window.
     */
    fun shouldPublish(
        previous: BatterySample?,
        next: BatterySample,
        lastSentAtMs: Long,
        nowMs: Long,
    ): Boolean {
        if (lastSentAtMs > 0L && nowMs - lastSentAtMs >= REPAINT_AFTER_MS) return true
        if (!isMeaningfulChange(previous, next)) return false
        if (lastSentAtMs <= 0L) return true
        return nowMs - lastSentAtMs >= MIN_PUBLISH_INTERVAL_MS
    }
}
