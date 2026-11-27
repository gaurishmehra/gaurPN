package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryStatusTest {

    @Test
    fun extrasConvertToPercentAndCharging() {
        assertEquals(BatterySample(50, true), BatteryStatus.fromExtras(50, 100, 2))
        assertEquals(BatterySample(100, true), BatteryStatus.fromExtras(100, 100, 5))
        assertEquals(BatterySample(0, false), BatteryStatus.fromExtras(0, 100, 3))
        assertEquals(BatterySample(73, false), BatteryStatus.fromExtras(73, 100, 4))
        // Some devices report a non-100 scale.
        assertEquals(BatterySample(50, false), BatteryStatus.fromExtras(2, 4, 1))
    }

    @Test
    fun unusableExtrasAreRejected() {
        assertNull(BatteryStatus.fromExtras(-1, 100, 2))
        assertNull(BatteryStatus.fromExtras(50, 0, 2))
        assertNull(BatteryStatus.fromExtras(50, -1, 2))
    }

    @Test
    fun levelAboveScaleIsClamped() {
        assertEquals(BatterySample(100, false), BatteryStatus.fromExtras(150, 100, 3))
    }

    @Test
    fun meaningfulChangesArePercentStepsAndChargingFlips() {
        val base = BatterySample(50, false)
        assertTrue(BatteryStatus.isMeaningfulChange(null, base))
        assertFalse(BatteryStatus.isMeaningfulChange(base, BatterySample(50, false)))
        assertFalse(BatteryStatus.isMeaningfulChange(base, BatterySample(51, false)))
        assertTrue(BatteryStatus.isMeaningfulChange(base, BatterySample(52, false)))
        assertTrue(BatteryStatus.isMeaningfulChange(base, BatterySample(48, false)))
        assertTrue(BatteryStatus.isMeaningfulChange(base, BatterySample(50, true)))
        assertTrue(BatteryStatus.isMeaningfulChange(BatterySample(50, true), BatterySample(50, false)))
    }

    @Test
    fun publishIsDebouncedToTheMinimumInterval() {
        val sample = BatterySample(50, false)
        // Nothing sent yet: the first sample always goes out.
        assertTrue(BatteryStatus.shouldPublish(null, sample, lastSentAtMs = 0L, nowMs = 1_000L))
        // Meaningful but inside the window: suppressed.
        assertFalse(
            BatteryStatus.shouldPublish(sample, BatterySample(55, false), 1_000L, 5_999L),
        )
        // Exactly at the window boundary: allowed.
        assertTrue(
            BatteryStatus.shouldPublish(sample, BatterySample(55, false), 1_000L, 6_000L),
        )
        // Not meaningful and inside the repaint window: suppressed.
        assertFalse(
            BatteryStatus.shouldPublish(
                sample, BatterySample(51, false), 600_000L, 600_000L + BatteryStatus.REPAINT_AFTER_MS - 1,
            ),
        )
    }

    @Test
    fun unchangedReadingIsRepaintedInsideTheFreshnessWindow() {
        val sample = BatterySample(100, true) // laptop parked at full, plugged in
        // Past the repaint window the same reading is re-advertised
        // (receivers drop blobs older than PeerStatus.MAX_STATUS_AGE_MS —
        // 10 minutes).
        assertTrue(
            BatteryStatus.shouldPublish(sample, sample, 600_000L, 600_000L + BatteryStatus.REPAINT_AFTER_MS),
        )
        assertTrue(
            BatteryStatus.shouldPublish(sample, sample, 600_000L, 600_000L + BatteryStatus.REPAINT_AFTER_MS + 1),
        )
        // The repaint cadence is at worst half the freshness window, so the
        // stored blob never ages out between repaints.
        assertTrue(BatteryStatus.REPAINT_AFTER_MS <= PeerStatus.MAX_STATUS_AGE_MS / 2)
    }
}
