package dev.gaurish.outlinelite.transfer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.gaurish.outlinelite.data.KeyRepository
import dev.gaurish.outlinelite.data.TransferSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The phone side of a camera stream: CameraX feeds the camera into a
 * MediaCodec HEVC input surface, and the encrypted Annex-B frames are POSTed
 * to the relay's existing `/pipe` endpoint (role=sender) for the desktop to
 * pull. The relay never sees plaintext: each frame is sealed with K_cam and
 * an index-bound AAD ([CameraStream]), and only ciphertext crosses the wire.
 *
 * Teardown is event-driven: the Stop action, a `cam_control` stop, or the
 * pipe socket closing (desktop hang-up, relay 504/409, tunnel drop) all land
 * in one place. There are no timers — the relay's own pipe session lifetime
 * is the only deadline, and it surfaces as a socket error here.
 *
 * The request slot is held from accept until teardown, so a second camera
 * request or a fetch is answered busy while this session lives.
 */
class CameraStreamService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Single-shot teardown guard; every end of the pipe funnels through it. */
    private val stopping = AtomicBoolean(false)
    private val tornDown = AtomicBoolean(false)

    /**
     * True once the encoder, the camera, and the writer pipe are all up. A
     * control that beats the startup window is dropped rather than racing
     * [bindAndEncode] into a second encoder.
     */
    @Volatile
    private var sessionReady = false

    /**
     * Pacing between the encoder and the socket. At most two frames buffer;
     * a full queue drops the incoming frame and asks the encoder for a sync
     * frame. The queue carries plaintext frames: the frame index (and so the
     * AAD) is only assigned when a frame is actually written, so dropped
     * frames never consume an index.
     */
    private val frameQueue = Channel<ByteArray>(capacity = FRAME_QUEUE_CAPACITY, onBufferOverflow = BufferOverflow.SUSPEND)

    /** Count of frames sent so far; the AAD frame index, starting at the csd. */
    private val sentFrames = AtomicLong(0L)

    @Volatile
    private var requestId: String? = null

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var encoderGeneration = 0

    @Volatile
    private var encoderSurface: Surface? = null

    /** Session constants captured at start; reused for rebinds and tier changes. */
    @Volatile
    private var relayHost: String? = null

    @Volatile
    private var relayToken: String? = null

    @Volatile
    private var camKey: ByteArray? = null

    private var requesterId: String = ""
    private var requesterName: String = ""
    private var tier: CameraTier = CameraTier.HIGH
    private var lens: CameraLens = CameraLens.BACK
    private var torchOn = false

    private var lifecycleOwner: ServiceLifecycleOwner? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var codec: MediaCodec? = null
    private var encoderThread: HandlerThread? = null
    private var writerCall: Call? = null
    private var writerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val owner = ServiceLifecycleOwner()
        lifecycleOwner = owner
        activeService = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            // ACTION_STOP and any stray delivery: the session ends. A stop
            // intent that arrives with no live session is a no-op teardown.
            else -> stopSession()
        }
        // A stream must not resurrect itself: after process death the desktop
        // re-dials only until expires_ts, and a redelivered start would emit
        // frame indices the receiver can no longer match.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        activeService = null
        serviceScope.cancel()
        super.onDestroy()
    }

    // --------------------------------------------------------------- start

    private fun handleStart(intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        if (this.requestId != null) {
            // The request slot guarantees one live session; a second start
            // would orphan the first lease, so it is refused outright. The
            // foreground contract of this delivery is met by refreshing the
            // existing notification.
            Log.w(TAG, "Camera session already live; ignoring restart")
            startForegroundCompat()
            return
        }
        // Validate before claiming anything: an invalid delivery must not
        // adopt ids whose lease it can then release by accident. The
        // manager derives the session id itself, so this is defense only.
        if (requestId.isNullOrBlank() || sessionId.isNullOrBlank() || !CameraStream.isSessionId(sessionId)) {
            Log.w(TAG, "Camera start with invalid extras; stopping")
            startForegroundCompat()
            stopSession()
            return
        }
        requesterId = intent.getStringExtra(EXTRA_REQUESTER_ID) ?: ""
        requesterName = intent.getStringExtra(EXTRA_REQUESTER_NAME) ?: ""
        // Claim the ids first: when the system denies the foreground start —
        // the Android 14+ while-in-use rule bites the auto-accept path —
        // teardown must still release the manager's HELD_UNTIL_RELEASED lease.
        this.requestId = requestId
        this.sessionId = sessionId
        // The foreground-service contract must be met before any other
        // outcome: a startForegroundService delivery that never reaches
        // startForeground crashes the app.
        if (!startForegroundCompat()) return

        serviceScope.launch {
            try {
                startSession()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "Camera session $requestId failed: ${t.javaClass.simpleName}")
                stopSession()
            }
        }
    }

    /**
     * Resolves the session constants (default tier/lens, relay subkeys,
     * K_cam) and hands the camera to [bindAndEncode]. Everything from here
     * runs on the main thread through the provider's listener.
     */
    private suspend fun startSession() {
        val quality = runCatching {
            TransferSettingsRepository.getCameraQuality(this)
        }.getOrDefault(TransferSettingsRepository.CAMERA_QUALITY_DEFAULT)
        tier = CameraTier.fromWire(quality) ?: CameraTier.HIGH
        val lensWire = runCatching {
            TransferSettingsRepository.getCameraLens(this)
        }.getOrDefault(TransferSettingsRepository.CAMERA_LENS_DEFAULT)
        lens = CameraLens.fromWire(lensWire) ?: CameraLens.BACK

        val token = runCatching { KeyRepository.getClusterToken(this) }.getOrDefault("")
        if (token.isBlank()) {
            stopSession()
            return
        }
        val (relayToken, peerCryptoKey) = Crypto.deriveSubKeys(token)
        val host = runCatching { KeyRepository.getRelayAddress(this) }.getOrDefault("")
            .ifBlank { RelayManager.RELAY_HOST }
        if (!RelayHosts.isInTunnelHost(host)) {
            Log.w(TAG, "Refusing relay host for camera session")
            stopSession()
            return
        }
        relayHost = host
        this.relayToken = relayToken
        camKey = CameraStream.cameraKey(peerCryptoKey, sessionId!!)

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull()
            if (provider == null) {
                stopSession()
                return@addListener
            }
            cameraProvider = provider
            try {
                bindAndEncode(provider)
            } catch (t: Throwable) {
                Log.w(TAG, "Camera bind failed: ${t.javaClass.simpleName}")
                stopSession()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Starts the encoder, binds the camera to its input surface through a
     * Preview, and opens the sender pipe. All on the main thread, as CameraX
     * requires; the encoder drains on its own handler thread.
     */
    private fun bindAndEncode(provider: ProcessCameraProvider) {
        startEncoder()
        bindPreview(provider)
        startWriter()
        sessionReady = true
    }

    // ------------------------------------------------------------- encoder

    /**
     * Creates and starts the HEVC encoder for the current tier: CBR at the
     * tier bitrate, an i-frame every 2 s, no B-frames (lowest latency), and
     * the tier's exact size and frame rate. Frame 0 is the csd the encoder
     * reports through INFO_OUTPUT_FORMAT_CHANGED.
     */
    private fun startEncoder() {
        val thread = HandlerThread("gaur-camera-encoder").apply { start() }
        encoderThread = thread
        val handler = Handler(thread.looper)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            tier.width,
            tier.height,
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, tier.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, tier.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL_SECONDS)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            )
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        val generation = encoderGeneration
        encoder.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                if (generation != encoderGeneration) return
                // Frame 0: the Annex-B VPS/SPS/PPS that lets the receiver
                // configure its decoder before any sample arrives.
                val csd = format.getByteBuffer("csd-0") ?: return
                val bytes = ByteArray(csd.remaining())
                csd.get(bytes)
                enqueueFrame(bytes)
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                // A superseded encoder is released mid-tier-change; its last
                // buffers are discarded so indices stay with the new stream.
                if (generation != encoderGeneration) {
                    runCatching { codec.releaseOutputBuffer(index, false) }
                    return
                }
                // The csd already left through the format change; some
                // encoders also emit it as a codec-config buffer.
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    runCatching { codec.releaseOutputBuffer(index, false) }
                    return
                }
                val buffer = runCatching { codec.getOutputBuffer(index) }.getOrNull()
                if (buffer == null) {
                    runCatching { codec.releaseOutputBuffer(index, false) }
                    return
                }
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                runCatching { codec.releaseOutputBuffer(index, false) }
                enqueueFrame(bytes)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.w(TAG, "Camera encoder error: ${e.javaClass.simpleName}")
                if (generation == encoderGeneration) stopSession()
            }
        }, handler)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()
        codec = encoder
        encoderSurface = surface
    }

    /** Paces one encoded frame into the bounded queue; drops on a full queue. */
    private fun enqueueFrame(frame: ByteArray) {
        if (frameQueue.trySend(frame).isFailure) {
            // The socket is behind; drop this frame and ask the encoder for
            // an IDR so the receiver resynchronizes on the next frame. The
            // dropped frame never reaches the writer, so it never consumes
            // a frame index.
            runCatching {
                codec?.setParameters(
                    Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) },
                )
            }
        }
    }

    // -------------------------------------------------------------- camera

    /** Binds a fresh Preview that feeds the encoder input surface. */
    private fun bindPreview(provider: ProcessCameraProvider) {
        val owner = lifecycleOwner ?: return
        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector(tier))
            .build()
        preview.setSurfaceProvider(
            ContextCompat.getMainExecutor(this),
            Preview.SurfaceProvider { request ->
                val surface = encoderSurface
                if (surface == null) {
                    request.willNotProvideSurface()
                    return@SurfaceProvider
                }
                // The camera writes straight into the MediaCodec input
                // surface; a rebind (lens switch) simply asks again for the
                // same surface.
                request.provideSurface(surface, ContextCompat.getMainExecutor(this)) { }
            },
        )
        camera = provider.bindToLifecycle(owner, cameraSelector(), preview)
        camera?.cameraControl?.enableTorch(torchOn)
    }

    private fun cameraSelector(): CameraSelector =
        if (lens == CameraLens.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA

    private fun resolutionSelector(tier: CameraTier): ResolutionSelector =
        ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(tier.width, tier.height),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()

    // -------------------------------------------------------------- writer

    /**
     * Opens the sender pipe. The request body streams sealed frames as they
     * are produced; the relay answers only after the receiver drained the
     * pipe, so a returned response — whatever its status — means the stream
     * is over. [RelayConnection] holds the shared client: connect timeout,
     * no read or write timeout, exactly what a long-lived streaming body
     * needs.
     */
    private fun startWriter() {
        val host = relayHost ?: return
        val token = relayToken ?: return
        val key = camKey ?: return
        val sessionId = sessionId ?: return
        val body = object : RequestBody() {
            override fun contentType(): MediaType = OCTET_STREAM

            override fun isOneShot(): Boolean = true

            override fun writeTo(sink: BufferedSink) {
                while (true) {
                    // The only pacing between the encoder and the socket: a
                    // blocked write backpressures through the bounded queue.
                    val frame = runBlocking { frameQueue.receive() }
                    // Assign the AAD index at write time: it counts frames
                    // that actually left the phone, in order, without gaps.
                    val sealed = CameraStream.sealFrame(
                        key,
                        sessionId,
                        sentFrames.getAndIncrement(),
                        frame,
                    )
                    sink.write(sealed)
                    sink.flush()
                }
            }
        }
        val request = Request.Builder()
            .url("http://$host/pipe?transfer_id=${Uri.encode(sessionId)}&role=sender")
            .header("X-Gaur-Token", token)
            .post(body)
            .build()
        val call = RelayConnection.client.newCall(request)
        writerCall = call
        writerJob = serviceScope.launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    Log.i(TAG, "Camera pipe for request $requestId finished: HTTP ${response.code}")
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // Socket close, relay hang-up, 504 rendezvous timeout, 409:
                // every end of the pipe lands here. The ids are logged, the
                // payload never.
                Log.i(TAG, "Camera pipe for request $requestId ended: ${t.javaClass.simpleName}")
            } finally {
                stopSession()
            }
        }
    }

    // ------------------------------------------------------------- control

    /**
     * Applies one validated `cam_control`. The manager has already matched
     * the request id and session id against the live lease, so every action
     * here belongs to the current session.
     */
    internal fun applyControl(control: CameraControl) {
        mainHandler.post {
            if (stopping.get() || !sessionReady) return@post
            when (control.action) {
                CameraAction.TORCH -> {
                    torchOn = control.on == true
                    runCatching { camera?.cameraControl?.enableTorch(torchOn) }
                }
                CameraAction.LENS -> switchLens()
                CameraAction.TIER -> control.tier?.let { changeTier(it) }
                CameraAction.STOP -> stopSession()
            }
        }
    }

    /** Flips the lens; a brief rebind hitch is expected and fine. */
    private fun switchLens() {
        lens = if (lens == CameraLens.FRONT) CameraLens.BACK else CameraLens.FRONT
        rebindCamera()
    }

    /**
     * Restarts the encoder at a new tier. The old codec stops first (no more
     * old-tier frames enter), the frames still queued are discarded, and the
     * new encoder's csd therefore lands in an empty queue — the csd is never
     * the dropped frame. The desktop decodes the new csd in-band and
     * reconfigures; the brief hitch is fine.
     */
    private fun changeTier(newTier: CameraTier) {
        if (newTier == tier) return
        tier = newTier
        encoderGeneration++
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        encoderSurface = null
        encoderThread?.quitSafely()
        encoderThread = null
        drainFrameQueue()
        startEncoder()
        rebindCamera()
    }

    /** Unbinds and rebinds the camera (lens switch, tier change). */
    private fun rebindCamera() {
        val provider = cameraProvider ?: return
        runCatching { provider.unbindAll() }
        camera = null
        val owner = lifecycleOwner ?: return
        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector(tier))
            .build()
        preview.setSurfaceProvider(
            ContextCompat.getMainExecutor(this),
            Preview.SurfaceProvider { request ->
                val surface = encoderSurface
                if (surface == null) {
                    request.willNotProvideSurface()
                    return@SurfaceProvider
                }
                request.provideSurface(surface, ContextCompat.getMainExecutor(this)) { }
            },
        )
        camera = runCatching {
            provider.bindToLifecycle(owner, cameraSelector(), preview)
        }.getOrNull()
        if (camera == null) {
            Log.w(TAG, "Camera rebind failed; stopping session")
            stopSession()
            return
        }
        // A rebind resets the torch; the session state is what the desktop set.
        camera?.cameraControl?.enableTorch(torchOn)
    }

    /** Discards stale frames left in the queue (tier change). */
    private fun drainFrameQueue() {
        while (frameQueue.tryReceive().isSuccess) {
            // old-tier frame; dropped without ever consuming an index
        }
    }

    // ------------------------------------------------------------- teardown

    /**
     * The single entry point for every end of the session. Idempotent and
     * safe from any thread; the work happens on the main thread.
     */
    private fun stopSession() {
        if (stopping.getAndSet(true)) return
        mainHandler.post { teardown() }
    }

    /**
     * Releases the request slot, stops the camera and the encoder, closes the
     * pipe, and removes the notification. The slot is released only after
     * [requestId] is read, so the manager can free exactly this lease.
     */
    private fun teardown() {
        if (tornDown.getAndSet(true)) return
        sessionReady = false
        writerJob?.cancel()
        writerJob = null
        writerCall?.cancel()
        writerCall = null
        frameQueue.close()
        // Invalidates any late encoder callback before the codec goes away.
        encoderGeneration++
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        encoderSurface = null
        encoderThread?.quitSafely()
        encoderThread = null
        runCatching { cameraProvider?.unbindAll() }
        cameraProvider = null
        camera = null
        lifecycleOwner?.destroy()
        lifecycleOwner = null
        val rid = requestId
        requestId = null
        sessionId = null
        relayToken = null
        camKey = null
        if (rid != null) {
            RelayManager.onCameraSessionEnded(applicationContext, rid)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Meets the foreground-service contract before any slow work. The camera
     * type needs the CAMERA runtime permission; when the system denies the
     * start (the Android 14+ while-in-use rule bites the auto-accept path,
     * which has no notification tap), the session ends cleanly instead of
     * crashing.
     */
    private fun startForegroundCompat(): Boolean {
        val notification = CameraRequestNotifications.buildStreamNotification(
            this,
            requesterName.ifBlank { requesterId },
        )
        val placed = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    CameraRequestNotifications.STREAM_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
                )
            } else {
                startForeground(CameraRequestNotifications.STREAM_NOTIFICATION_ID, notification)
            }
        }
        if (placed.isFailure) {
            Log.w(TAG, "Camera foreground start denied: ${placed.exceptionOrNull()?.javaClass?.simpleName}")
            stopSession()
            return false
        }
        return true
    }

    /**
     * Minimal lifecycle owner so CameraX can bind to the service itself: the
     * registry is started in [onCreate] and destroyed in teardown, so an
     * unbind can never race a destroyed lifecycle.
     */
    private class ServiceLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        init {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }

        override val lifecycle: Lifecycle get() = registry

        fun destroy() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }

    companion object {
        private const val TAG = "CameraStreamService"

        /** Bounded frame queue; the pinned phone pacing. */
        private const val FRAME_QUEUE_CAPACITY = 2

        /** HEVC i-frame cadence, seconds. */
        private const val IFRAME_INTERVAL_SECONDS = 2

        private val OCTET_STREAM: MediaType = "application/octet-stream".toMediaType()

        const val ACTION_START = "dev.gaurish.outlinelite.action.CAMERA_STREAM_START"
        const val ACTION_STOP = "dev.gaurish.outlinelite.action.CAMERA_STREAM_STOP"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_REQUESTER_ID = "requester_id"
        const val EXTRA_REQUESTER_NAME = "requester_name"

        @Volatile
        private var activeService: CameraStreamService? = null

        /**
         * Starts one live session. The accept path runs inside a
         * notification-action tap, the user interaction that exempts the
         * phone from the Android 14+ camera-FGS background-start restriction.
         */
        fun start(
            context: Context,
            requestId: String,
            sessionId: String,
            requesterId: String,
            requesterName: String,
        ): Boolean = runCatching {
            val intent = Intent(context, CameraStreamService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_REQUESTER_ID, requesterId)
                .putExtra(EXTRA_REQUESTER_NAME, requesterName)
            ContextCompat.startForegroundService(context, intent)
            true
        }.getOrDefault(false)

        /** Stops the live session, whatever it is; there is at most one. */
        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, CameraStreamService::class.java).setAction(ACTION_STOP),
                )
            }
        }

        /**
         * Routes a validated control to the live service. False (dropped
         * silently) when no session is running — the manager has already
         * matched the request and session ids against the live lease.
         */
        fun dispatchControl(control: CameraControl): Boolean {
            val service = activeService ?: return false
            return runCatching {
                service.applyControl(control)
                true
            }.getOrDefault(false)
        }
    }
}
