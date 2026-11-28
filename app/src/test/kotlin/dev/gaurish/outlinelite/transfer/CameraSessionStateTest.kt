package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the camera session state machine: the phase transitions the relay
 * manager and the stream service drive, and the control rules — a control
 * outside a live stream or a no-op control changes nothing. The service and
 * the manager apply these pure transitions through the holder, so the UI
 * can never observe a state this table does not describe.
 */
class CameraSessionStateTest {

    // -------------------------------------------------------- transitions

    @Test
    fun idleToPendingToStreamingToIdle() {
        val pending = CameraSession().toPending("Lilac", "lilac-id")
        assertEquals(CameraSessionPhase.REQUEST_PENDING, pending.phase)
        assertEquals("Lilac", pending.requesterName)

        val streaming = pending.toStreaming(
            requesterName = "Lilac",
            requesterOriginId = "lilac-id",
            tier = CameraTier.BALANCED,
            fps = 60,
            lens = CameraLens.FRONT,
            rotationDeg = 270,
        )
        assertEquals(CameraSessionPhase.STREAMING, streaming.phase)
        assertEquals("Lilac", streaming.requesterName)
        assertEquals(CameraTier.BALANCED, streaming.tier)
        assertEquals(60, streaming.fps)
        assertEquals(CameraLens.FRONT, streaming.lens)
        assertEquals(270, streaming.rotationDeg)
        // A fresh bind has the torch off; the state must not claim otherwise.
        assertFalse(streaming.torchOn)

        val idle = streaming.toIdle()
        assertEquals(CameraSessionPhase.IDLE, idle.phase)
        // Full reset: no leftover tier, lens, or rotation from the session.
        assertEquals(CameraSession(), idle)
    }

    @Test
    fun autoAcceptStreamsStraightFromIdle() {
        // Auto-accept skips the prompt entirely.
        val streaming = CameraSession().toStreaming(
            requesterName = "Lilac",
            requesterOriginId = "lilac-id",
            tier = CameraTier.HIGH,
            fps = 30,
            lens = CameraLens.BACK,
            rotationDeg = 90,
        )
        assertEquals(CameraSessionPhase.STREAMING, streaming.phase)
        assertEquals(90, streaming.rotationDeg)
    }

    @Test
    fun aPromptNeverClobbersALiveStream() {
        // A second request cannot stack on a live session; the transition
        // keeps the truth instead of the newest write.
        val streaming = CameraSession()
            .toPending("Lilac", "lilac-id")
            .toStreaming("Lilac", "lilac-id", CameraTier.HIGH, 30, CameraLens.BACK, 90)
        val unchanged = streaming.toPending("Other desktop", "other-id")
        assertEquals(CameraSessionPhase.STREAMING, unchanged.phase)
        assertEquals("Lilac", unchanged.requesterName)
    }

    @Test
    fun resolvingAPromptDropsOnlyAPendingSession() {
        val streaming = CameraSession().toStreaming("Lilac", "lilac-id", CameraTier.HIGH, 30, CameraLens.BACK, 90)
        assertEquals(
            CameraSessionPhase.STREAMING,
            streaming.toIdleIfPending().phase,
        )
        val pending = CameraSession().toPending("Lilac", "lilac-id")
        assertEquals(
            CameraSessionPhase.IDLE,
            pending.toIdleIfPending().phase,
        )
    }

    @Test
    fun aSessionEndingWithASecondPromptGoesBackToPending() {
        // Two origins can each hold a live prompt; the first one's stream
        // ends while the second prompt is still inside its window.
        val streaming = CameraSession()
            .toPending("Lilac", "lilac-id")
            .toStreaming("Lilac", "lilac-id", CameraTier.LOW, 60, CameraLens.FRONT, 270)
            .withTorch(true)
        val back = streaming.backToPending("Other desktop", "other-id")
        assertEquals(CameraSessionPhase.REQUEST_PENDING, back.phase)
        assertEquals("Other desktop", back.requesterName)
        // The finished stream's tier, lens, torch, and rotation do not leak
        // into the pending state.
        assertEquals(CameraTier.HIGH, back.tier)
        assertEquals(CameraGeometry.FPS_30, back.fps)
        assertEquals(CameraLens.BACK, back.lens)
        assertFalse(back.torchOn)
        assertEquals(0, back.rotationDeg)
    }

    // ----------------------------------------------------------- controls

    @Test
    fun controlsChangeOnlyALiveSession() {
        val idle = CameraSession()
        // A control landing outside a live session is a stray, not a command.
        assertEquals(
            idle,
            idle
                .withTier(CameraTier.LOW)
                .withFps(60)
                .withLens(CameraLens.FRONT)
                .withTorch(true)
                .withRotation(90),
        )
    }

    @Test
    fun controlsUpdateTheLiveSession() {
        val changed = CameraSession()
            .toStreaming("Lilac", "lilac-id", CameraTier.HIGH, 30, CameraLens.BACK, 90)
            .withTier(CameraTier.LOW)
            .withFps(60)
            .withLens(CameraLens.FRONT)
            .withTorch(true)
            .withRotation(180)
        assertEquals(CameraSessionPhase.STREAMING, changed.phase)
        assertEquals(CameraTier.LOW, changed.tier)
        assertEquals(60, changed.fps)
        assertEquals(CameraLens.FRONT, changed.lens)
        assertTrue(changed.torchOn)
        assertEquals(180, changed.rotationDeg)
    }

    @Test
    fun noOpControlsAreRejectedWithoutAChange() {
        val streaming = CameraSession().toStreaming("Lilac", "lilac-id", CameraTier.HIGH, 30, CameraLens.BACK, 90)
        // The service uses the same rule to skip an encoder restart.
        assertEquals(streaming, streaming.withTier(CameraTier.HIGH))
        assertEquals(streaming, streaming.withFps(30))
        assertEquals(streaming, streaming.withLens(CameraLens.BACK))
        assertEquals(streaming, streaming.withTorch(false))
        assertEquals(streaming, streaming.withRotation(90))
    }

    // ------------------------------------------------------ the holder

    @Test
    fun theHolderFollowsTheSameTransitions() {
        CameraSessionState.reset()
        assertEquals(CameraSessionPhase.IDLE, CameraSessionState.session.value.phase)

        CameraSessionState.promptPosted("Lilac", "lilac-id")
        assertEquals(CameraSessionPhase.REQUEST_PENDING, CameraSessionState.session.value.phase)
        assertEquals("Lilac", CameraSessionState.session.value.requesterName)

        CameraSessionState.promptResolved()
        assertEquals(CameraSessionPhase.IDLE, CameraSessionState.session.value.phase)

        CameraSessionState.promptPosted("Lilac", "lilac-id")
        CameraSessionState.streaming("Lilac", "lilac-id", CameraTier.HIGH, 30, CameraLens.BACK, 90)
        val streaming = CameraSessionState.session.value
        assertEquals(CameraSessionPhase.STREAMING, streaming.phase)
        assertEquals(90, streaming.rotationDeg)

        CameraSessionState.torchChanged(true)
        assertTrue(CameraSessionState.session.value.torchOn)
        CameraSessionState.tierChanged(CameraTier.LOW)
        assertEquals(CameraTier.LOW, CameraSessionState.session.value.tier)
        CameraSessionState.fpsChanged(60)
        assertEquals(60, CameraSessionState.session.value.fps)
        CameraSessionState.lensChanged(CameraLens.FRONT)
        assertEquals(CameraLens.FRONT, CameraSessionState.session.value.lens)
        CameraSessionState.rotationChanged(180)
        assertEquals(180, CameraSessionState.session.value.rotationDeg)

        CameraSessionState.reset()
        assertEquals(CameraSessionPhase.IDLE, CameraSessionState.session.value.phase)
        assertFalse(CameraSessionState.session.value.torchOn)
    }
}
