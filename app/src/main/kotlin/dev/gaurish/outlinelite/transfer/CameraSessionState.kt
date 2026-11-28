package dev.gaurish.outlinelite.transfer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the live camera session is doing, for the in-app preview screen and
 * the devices card. The service owns every transition after a request is
 * accepted; the relay manager owns the pending phase, because a prompt lives
 * before any service does. Nothing here touches the wire — the desktop
 * controls the stream through `cam_control` and this state simply follows.
 */
enum class CameraSessionPhase { IDLE, REQUEST_PENDING, STREAMING }

/**
 * One immutable snapshot of the session. Defaults are the honest idle
 * values: no tier, lens, torch, or rotation is "live" until a stream is.
 */
data class CameraSession(
    val phase: CameraSessionPhase = CameraSessionPhase.IDLE,
    val requesterName: String = "",
    /**
     * The stable device id of whoever is viewing (or asking to). The
     * devices card matches it against a peer row, so the eye indicator
     * can sit on the viewer's row instead of a separate banner. Empty
     * only for the idle default.
     */
    val requesterOriginId: String = "",
    val tier: CameraTier = CameraTier.HIGH,
    val fps: Int = CameraGeometry.FPS_30,
    val lens: CameraLens = CameraLens.BACK,
    val torchOn: Boolean = false,
    val rotationDeg: Int = 0,
) {
    /**
     * An accept prompt went up. A prompt never coexists with a live stream
     * (the request slot is held for the whole session), so a pending state
     * that arrives while streaming is ignored rather than clobbering the
     * truth.
     */
    fun toPending(requesterName: String, requesterOriginId: String): CameraSession =
        if (phase == CameraSessionPhase.IDLE) {
            copy(
                phase = CameraSessionPhase.REQUEST_PENDING,
                requesterName = requesterName,
                requesterOriginId = requesterOriginId,
            )
        } else {
            this
        }

    /**
     * The prompt was answered, expired, or cancelled. Only a pending session
     * drops to idle here; a stream that is already up keeps its state.
     */
    fun toIdleIfPending(): CameraSession =
        if (phase == CameraSessionPhase.REQUEST_PENDING) CameraSession() else this

    /**
     * The live session ended with this prompt still waiting — a second
     * origin asked while the first stream ran, and its 120 s window is still
     * open. Unlike [toPending], this replaces the finished stream: the
     * stream's state stopped being true the moment the encoder went away.
     */
    fun backToPending(requesterName: String, requesterOriginId: String): CameraSession =
        copy(
            phase = CameraSessionPhase.REQUEST_PENDING,
            requesterName = requesterName,
            requesterOriginId = requesterOriginId,
            tier = CameraTier.HIGH,
            fps = CameraGeometry.FPS_30,
            lens = CameraLens.BACK,
            torchOn = false,
            rotationDeg = 0,
        )

    /**
     * The service is up and encoding. Reached from a prompt (the accept tap)
     * or straight from idle (auto-accept), which is why the phase check is an
     * acceptance of either, not a gate. Torch starts off: a fresh bind has
     * the torch off and the session state must not claim otherwise.
     */
    fun toStreaming(
        requesterName: String,
        requesterOriginId: String,
        tier: CameraTier,
        fps: Int,
        lens: CameraLens,
        rotationDeg: Int,
    ): CameraSession = copy(
        phase = CameraSessionPhase.STREAMING,
        requesterName = requesterName,
        requesterOriginId = requesterOriginId,
        tier = tier,
        fps = fps,
        lens = lens,
        torchOn = false,
        rotationDeg = rotationDeg,
    )

    /**
     * The session ended; every field falls back to the idle defaults so no
     * stale tier or torch outlives the stream.
     */
    fun toIdle(): CameraSession = CameraSession()

    /** A control change; a no-op or a control outside a live stream is ignored. */
    fun withTier(tier: CameraTier): CameraSession =
        if (phase == CameraSessionPhase.STREAMING && tier != this.tier) copy(tier = tier) else this

    /** A control change; a no-op or a control outside a live stream is ignored. */
    fun withFps(fps: Int): CameraSession =
        if (phase == CameraSessionPhase.STREAMING && fps != this.fps) copy(fps = fps) else this

    /** A control change; a no-op or a control outside a live stream is ignored. */
    fun withLens(lens: CameraLens): CameraSession =
        if (phase == CameraSessionPhase.STREAMING && lens != this.lens) copy(lens = lens) else this

    /** A control change; a no-op or a control outside a live stream is ignored. */
    fun withTorch(on: Boolean): CameraSession =
        if (phase == CameraSessionPhase.STREAMING && on != torchOn) copy(torchOn = on) else this

    /** A control change; a no-op or a control outside a live stream is ignored. */
    fun withRotation(rotationDeg: Int): CameraSession =
        if (phase == CameraSessionPhase.STREAMING && rotationDeg != this.rotationDeg) {
            copy(rotationDeg = rotationDeg)
        } else {
            this
        }
}

/**
 * The observable holder for [CameraSession]. A plain object with one
 * StateFlow: the service and the relay manager write through the transition
 * mutators below, and the UI collects [session]. In-memory only — after
 * process death there is no session to describe.
 */
object CameraSessionState {

    private val _session = MutableStateFlow(CameraSession())

    /** The current session; IDLE with defaults until a request arrives. */
    val session: StateFlow<CameraSession> = _session.asStateFlow()

    /** An accept prompt was posted with this requester. */
    internal fun promptPosted(requesterName: String, requesterOriginId: String) {
        _session.update { it.toPending(requesterName, requesterOriginId) }
    }

    /** The newest prompt is gone; a live stream is untouched. */
    internal fun promptResolved() {
        _session.update { it.toIdleIfPending() }
    }

    /** The service bound the camera and started the encoder. */
    internal fun streaming(
        requesterName: String,
        requesterOriginId: String,
        tier: CameraTier,
        fps: Int,
        lens: CameraLens,
        rotationDeg: Int,
    ) {
        _session.update {
            it.toStreaming(requesterName, requesterOriginId, tier, fps, lens, rotationDeg)
        }
    }

    /** A local or desktop control changed the live session. */
    internal fun tierChanged(tier: CameraTier) {
        _session.update { it.withTier(tier) }
    }

    /** A local or desktop control changed the live session. */
    internal fun fpsChanged(fps: Int) {
        _session.update { it.withFps(fps) }
    }

    /** A local or desktop control changed the live session. */
    internal fun lensChanged(lens: CameraLens) {
        _session.update { it.withLens(lens) }
    }

    /** A local or desktop control changed the live session. */
    internal fun torchChanged(on: Boolean) {
        _session.update { it.withTorch(on) }
    }

    /** A local control rotated the live session. */
    internal fun rotationChanged(rotationDeg: Int) {
        _session.update { it.withRotation(rotationDeg) }
    }

    /** The session ended — Stop, pipe close, or a failed start. */
    internal fun reset() {
        _session.value = CameraSession()
    }

    /**
     * The session ended while another origin's prompt is still waiting: the
     * phase goes back to pending with that prompt, not to idle.
     */
    internal fun sessionEndedWithPrompt(requesterName: String, requesterOriginId: String) {
        _session.update { it.backToPending(requesterName, requesterOriginId) }
    }
}
