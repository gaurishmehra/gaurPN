package dev.gaurish.outlinelite.transfer

/**
 * Pure geometry of the camera stream: the clockwise rotation that turns a
 * sensor-native frame upright for the current lens and display, how that
 * rotation swaps the encoder's dimensions, and how the frame rate scales the
 * encoder bitrate. None of this is on the wire — the desktop never sees a
 * rotation or a frame-rate change, only their effect on the encoded frames —
 * so it lives beside [CameraStream] as JVM-pure, table-testable logic.
 *
 * The rotation is applied once, in GL, between the camera and the encoder
 * ([CameraGLPipeline]); the sensor's own orientation never changes mid-stream
 * and the display is only read at session start, so the value is computed
 * once per session and then only ever changed by the user cycling it.
 */
object CameraGeometry {

    /** Standard capture rate; the tier's pinned default. */
    const val FPS_30 = 30

    /** High capture rate; doubles the tier bitrate and pins the AE range. */
    const val FPS_60 = 60

    /**
     * True for the two frame rates a live session can pin. Anything else is
     * rejected by the control path rather than clamped, so a stray value can
     * never half-configure the encoder and the AE lock.
     */
    fun isSupportedFps(fps: Int): Boolean = fps == FPS_30 || fps == FPS_60

    /**
     * The clockwise rotation, in degrees, that turns a frame from the sensor
     * into an upright image on the current display. Back lens: the sensor is
     * mounted clockwise from the device, so the display rotation comes off
     * the sensor orientation. Front lens: the sensor is mounted counter-
     * clockwise, so the display rotation adds. The result is rounded to the
     * nearest quarter turn — sensor orientation and display rotation are
     * multiples of 90 on every device this app runs on, so the rounding is
     * defense, not a correction.
     *
     * A portrait-held phone with the usual 90-degree back sensor rotates 90;
     * the front camera is not mirrored here (the local preview view mirrors
     * itself, the stream must not).
     */
    fun streamRotation(sensorOrientationDeg: Int, displayRotationDeg: Int, front: Boolean): Int {
        val raw = if (front) {
            (sensorOrientationDeg + displayRotationDeg) % 360
        } else {
            (sensorOrientationDeg - displayRotationDeg + 360) % 360
        }
        return roundToQuarterTurn(raw)
    }

    /** Nearest quarter turn, wrapped into [0, 270]. */
    fun roundToQuarterTurn(deg: Int): Int {
        val wrapped = ((deg % 360) + 360) % 360
        return ((wrapped + 45) / 90 * 90) % 360
    }

    /** Encoder surface dimensions for a tier at a rotation. */
    data class EncoderSize(val width: Int, val height: Int)

    /**
     * The encoder's dimensions: the tier's, swapped when the rotation stands
     * the frame up (90/270), so a rotated frame lands in a portrait surface
     * without cropping and an unrotated one keeps the tier's landscape dims.
     */
    fun encoderSize(tier: CameraTier, rotationDeg: Int): EncoderSize =
        if (rotationDeg % 180 == 90) EncoderSize(tier.height, tier.width)
        else EncoderSize(tier.width, tier.height)

    /**
     * The encoder bitrate. The tier's pinned value at 30 fps; doubled at 60
     * so the same per-frame quality survives twice the frames per second.
     */
    fun encoderBitrate(tier: CameraTier, fps: Int): Int =
        if (fps >= FPS_60) tier.bitrate * 2 else tier.bitrate

    /**
     * The AE target range pinned to exactly [fps] on both ends, as the
     * Camera2Interop option carries it: a closed range, never [fps, 60], so
     * the sensor cannot hand back 15 fps in the dark and silently add
     * hundreds of milliseconds of stream latency.
     */
    fun captureFpsRange(fps: Int): Pair<Int, Int> = Pair(fps, fps)
}
