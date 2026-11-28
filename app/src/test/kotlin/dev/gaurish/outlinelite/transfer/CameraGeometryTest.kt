package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the camera stream's geometry: the rotation that turns a sensor-native
 * frame upright, the encoder dimension swap that follows it, and the fps
 * scaling of the bitrate and the AE target range. None of this is on the
 * wire, but a wrong rotation streams a sideways image and a wrong swap
 * crops it, so the arithmetic is table-pinned here.
 */
class CameraGeometryTest {

    // --------------------------------------------------------- rotation

    @Test
    fun portraitBackSensorRotatesNinety() {
        // This repo's phones: portrait-held, the usual 90-degree back sensor.
        assertEquals(90, CameraGeometry.streamRotation(sensorOrientationDeg = 90, displayRotationDeg = 0, front = false))
    }

    @Test
    fun streamRotationTable() {
        // (sensor, display, front) -> clockwise rotation to upright.
        val cases = listOf(
            // Back lens: the display rotation comes off the sensor orientation.
            Triple(90, 0, false) to 90,
            Triple(90, 90, false) to 0,
            Triple(90, 180, false) to 270,
            Triple(90, 270, false) to 180,
            Triple(0, 0, false) to 0,
            Triple(270, 0, false) to 270,
            Triple(270, 90, false) to 180,
            Triple(180, 270, false) to 270,
            // Front lens: the display rotation adds; never mirrored here.
            Triple(270, 0, true) to 270,
            Triple(270, 90, true) to 0,
            Triple(270, 180, true) to 90,
            Triple(270, 270, true) to 180,
            Triple(180, 0, true) to 180,
            Triple(0, 90, true) to 90,
        )
        cases.forEach { (input, expected) ->
            assertEquals(
                "sensor=${input.first} display=${input.second} front=${input.third}",
                expected,
                CameraGeometry.streamRotation(input.first, input.second, input.third),
            )
        }
    }

    @Test
    fun streamRotationRoundsToTheNearestQuarterTurn() {
        // Sensor orientation and display rotation are multiples of 90 on
        // every real device; the rounding is defense and must not invent a
        // diagonal.
        assertEquals(90, CameraGeometry.streamRotation(95, 0, front = false))
        assertEquals(90, CameraGeometry.streamRotation(134, 0, front = false))
        assertEquals(180, CameraGeometry.streamRotation(135, 0, front = false))
        assertEquals(0, CameraGeometry.streamRotation(44, 0, front = false))
        assertEquals(0, CameraGeometry.streamRotation(315, 0, front = true))
        assertEquals(0, CameraGeometry.streamRotation(359, 0, front = true))
    }

    @Test
    fun quarterTurnRoundingWrapsAndHandlesTiesUpward() {
        assertEquals(0, CameraGeometry.roundToQuarterTurn(0))
        assertEquals(90, CameraGeometry.roundToQuarterTurn(90))
        assertEquals(270, CameraGeometry.roundToQuarterTurn(314))
        assertEquals(0, CameraGeometry.roundToQuarterTurn(360))
        assertEquals(90, CameraGeometry.roundToQuarterTurn(450))
        assertEquals(0, CameraGeometry.roundToQuarterTurn(-1))
        assertEquals(270, CameraGeometry.roundToQuarterTurn(-90))
        assertEquals(90, CameraGeometry.roundToQuarterTurn(-270))
    }

    // ----------------------------------------------------------- encoder

    @Test
    fun encoderSizeSwapsAtQuarterTurnsOnly() {
        val cases = listOf(
            Triple(CameraTier.HIGH, 0, CameraGeometry.EncoderSize(1920, 1080)),
            Triple(CameraTier.HIGH, 90, CameraGeometry.EncoderSize(1080, 1920)),
            Triple(CameraTier.HIGH, 180, CameraGeometry.EncoderSize(1920, 1080)),
            Triple(CameraTier.HIGH, 270, CameraGeometry.EncoderSize(1080, 1920)),
            Triple(CameraTier.BALANCED, 0, CameraGeometry.EncoderSize(1280, 720)),
            Triple(CameraTier.BALANCED, 270, CameraGeometry.EncoderSize(720, 1280)),
            Triple(CameraTier.LOW, 0, CameraGeometry.EncoderSize(640, 360)),
            Triple(CameraTier.LOW, 90, CameraGeometry.EncoderSize(360, 640)),
        )
        cases.forEach { (tier, rotation, expected) ->
            assertEquals(
                "$tier at $rotation degrees",
                expected,
                CameraGeometry.encoderSize(tier, rotation),
            )
        }
    }

    // -------------------------------------------------------- fps scaling

    @Test
    fun sixtyFpsDoublesTheTierBitrate() {
        assertEquals(8_000_000, CameraGeometry.encoderBitrate(CameraTier.HIGH, 30))
        assertEquals(16_000_000, CameraGeometry.encoderBitrate(CameraTier.HIGH, 60))
        assertEquals(3_500_000, CameraGeometry.encoderBitrate(CameraTier.BALANCED, 30))
        assertEquals(7_000_000, CameraGeometry.encoderBitrate(CameraTier.BALANCED, 60))
        assertEquals(500_000, CameraGeometry.encoderBitrate(CameraTier.LOW, 30))
        assertEquals(1_000_000, CameraGeometry.encoderBitrate(CameraTier.LOW, 60))
    }

    @Test
    fun captureFpsRangeIsClosedAtTheSessionFps() {
        // A closed range, never [fps, 60]: the AE must not be able to run
        // the sensor slower than the session says.
        assertEquals(30 to 30, CameraGeometry.captureFpsRange(30))
        assertEquals(60 to 60, CameraGeometry.captureFpsRange(60))
    }

    @Test
    fun onlyTwoFrameRatesAreSupported() {
        assertTrue(CameraGeometry.isSupportedFps(CameraGeometry.FPS_30))
        assertTrue(CameraGeometry.isSupportedFps(CameraGeometry.FPS_60))
        listOf(0, 15, 24, 29, 31, 59, 61, 120, -30).forEach { fps ->
            assertFalse("fps $fps", CameraGeometry.isSupportedFps(fps))
        }
    }
}
