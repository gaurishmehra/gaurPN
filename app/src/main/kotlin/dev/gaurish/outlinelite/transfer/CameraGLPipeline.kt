package dev.gaurish.outlinelite.transfer

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The GL rotation stage between the camera and the HEVC encoder. CameraX
 * writes sensor-native frames into a SurfaceTexture owned by this pipeline;
 * a dedicated thread ("gaur-camera-gl") holds the EGL context, and every
 * frame available is drawn through a full-screen quad — rotated upright —
 * onto the encoder's input surface. Without this stage the encoder receives
 * the sensor's own orientation, which is sideways on a portrait-held phone.
 *
 * The EGL config is RGBA8888 with EGL_RECORDABLE_ANDROID so a MediaCodec
 * input surface accepts the window. The camera-facing surface exists from
 * [start] (set up over a throwaway 1x1 pbuffer), so CameraX's first
 * SurfaceRequest can be served immediately; the encoder is created later by
 * the service and attached with [attachEncoderSurface]. Tier, fps, and
 * rotation changes recreate only the encoder and this EGL window surface —
 * the SurfaceTexture keeps receiving camera frames across encoder
 * generations, so the camera never sees its surface go away.
 *
 * Every draw is event-driven: no timers, and no work at all while no encoder
 * window is attached. The rotation is read at draw time from a field that
 * only a user action changes — the sensor's orientation is sampled once per
 * session by the service, never per frame.
 */
internal class CameraGLPipeline(private val onError: () -> Unit) {

    companion object {
        private const val TAG = "CameraGLPipeline"
        private const val GL_THREAD_NAME = "gaur-camera-gl"

        /**
         * Not exposed by EGL14: the Android extension bit that marks a
         * surface as consumable by MediaCodec, required in the config for
         * the encoder's input surface.
         */
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null

    private var oesTexture = 0

    @Volatile
    private var surfaceTexture: SurfaceTexture? = null

    /** The camera-facing surface, backed by the SurfaceTexture. */
    @Volatile
    private var cameraTarget: Surface? = null

    /** The EGL window surface wrapping the current encoder's input surface. */
    private var encoderWindow: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var uvMatrixHandle = 0
    private var positionHandle = 0
    private var texCoordHandle = 0

    /** Clockwise rotation in degrees; written by [setRotation], read at draw time. */
    @Volatile
    private var rotationDeg = 0

    /** Set before release; the draw and posted blocks stop on it. */
    private val released = AtomicBoolean(false)

    private val surfaceMatrix = FloatArray(16)
    private val uvMatrix = FloatArray(16)

    /**
     * UV transforms for the four quarter turns, column-major 4x4, applied to
     * the quad's base texcoords before the SurfaceTexture transform. Each is
     * a corner-to-corner remap, so the full frame lands in the swapped
     * destination with no cropping.
     */
    private val uvRotationMatrices: Array<FloatArray> = arrayOf(
        // 0 degrees: identity.
        floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 0f,
            0f, 0f, 0f, 1f,
        ),
        // 90 clockwise: (u, v) -> (1 - v, u).
        floatArrayOf(
            0f, 1f, 0f, 0f,
            -1f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f,
            1f, 0f, 0f, 1f,
        ),
        // 180: (u, v) -> (1 - u, 1 - v).
        floatArrayOf(
            -1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 0f, 0f,
            1f, 1f, 0f, 1f,
        ),
        // 270 clockwise: (u, v) -> (v, 1 - u).
        floatArrayOf(
            0f, -1f, 0f, 0f,
            1f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f,
            0f, 1f, 0f, 1f,
        ),
    )

    private val vertexSource = """
        uniform mat4 uUvMatrix;
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTex;
        void main() {
            gl_Position = aPosition;
            vTex = (uUvMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    private val fragmentSource = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTex;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTex);
        }
    """.trimIndent()

    // Full-screen quad, drawn as a triangle strip.
    private val quadCoords = floatBuffer(
        floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
    )
    private val quadTexCoords = floatBuffer(
        floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
    )

    // ------------------------------------------------------------- lifecycle

    /**
     * Starts the GL thread, brings up the EGL display, config, and context,
     * and creates the OES texture, the SurfaceTexture, its camera-facing
     * surface, and the draw program over a throwaway pbuffer. False when the
     * device cannot provide any of that, in which case the caller should end
     * the session and still call [release] to drop the thread.
     */
    fun start(): Boolean {
        if (thread != null) return true
        val glThread = HandlerThread(GL_THREAD_NAME).apply { start() }
        thread = glThread
        handler = Handler(glThread.looper)
        return try {
            runOnGlThread {
                initEgl()
                createCameraStageOnGl()
            }
            context != EGL14.EGL_NO_CONTEXT && cameraTarget != null
        } catch (t: Throwable) {
            Log.w(TAG, "Camera GL init failed: ${t.javaClass.simpleName}")
            false
        }
    }

    private fun initEgl() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != null && display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        if (!EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0)) {
            error("eglInitialize failed")
        }
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            // Window surfaces serve the encoder; the pbuffer bit is only
            // needed for the throwaway setup surface below.
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val matched = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, matched, 0) || matched[0] < 1) {
            error("no recordable RGBA8888 config")
        }
        val context = EGL14.eglCreateContext(
            display,
            configs[0],
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context != null && context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        this.display = display
        this.config = configs[0]
        this.context = context
    }

    /**
     * GL objects that must exist before any encoder does, created with the
     * context briefly current on a 1x1 pbuffer.
     */
    @Suppress("DEPRECATION")
    private fun createCameraStageOnGl() {
        val pbuffer = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        check(pbuffer != null && pbuffer != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        try {
            check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent failed" }
            createProgramOnGl()
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            oesTexture = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            val texture = SurfaceTexture(oesTexture)
            // Bound to the GL thread's handler, so the frame-available
            // callback is itself the draw on the GL thread.
            texture.setOnFrameAvailableListener({ drawFrameOnGl() }, handler)
            surfaceTexture = texture
            cameraTarget = Surface(texture)
        } finally {
            // Leave the context current on nothing until the encoder window
            // attaches; frames that arrive in between are simply not drawn.
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, context)
            EGL14.eglDestroySurface(display, pbuffer)
        }
    }

    /**
     * Rebuilds the EGL window surface around the encoder's input surface and
     * makes it current. Called once per encoder generation; the camera side
     * of the pipeline persists across calls.
     */
    fun attachEncoderSurface(surface: Surface) {
        runOnGlThread { attachEncoderSurfaceOnGl(surface) }
    }

    private fun attachEncoderSurfaceOnGl(surface: Surface) {
        destroyEncoderWindowOnGl()
        val window = EGL14.eglCreateWindowSurface(
            display,
            config,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        check(window != null && window != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        encoderWindow = window
        check(EGL14.eglMakeCurrent(display, window, window, context)) { "eglMakeCurrent failed" }
        // The new encoder generation may carry swapped dimensions.
        val width = IntArray(1)
        val height = IntArray(1)
        EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, width, 0)
        EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, height, 0)
        GLES20.glViewport(0, 0, width[0], height[0])
    }

    /**
     * Drops the current encoder window. Frames are skipped (not drawn, not
     * errored) until the next [attachEncoderSurface]; the encoder restart
     * calls this before releasing the old codec, so a frame that arrives
     * mid-restart never lands on a dead window.
     */
    fun detachEncoderSurface() {
        runOnGlThread { destroyEncoderWindowOnGl() }
    }

    private fun destroyEncoderWindowOnGl() {
        if (encoderWindow == EGL14.EGL_NO_SURFACE) return
        // A current surface cannot be destroyed; unbind it first.
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, context)
        EGL14.eglDestroySurface(display, encoderWindow)
        encoderWindow = EGL14.EGL_NO_SURFACE
    }

    private fun createProgramOnGl() {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) { "program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        this.program = program
        uvMatrixHandle = GLES20.glGetUniformLocation(program, "uUvMatrix")
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] == GLES20.GL_TRUE) { "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    // ------------------------------------------------------------- camera IO

    /** The camera-facing surface; null before [start] or after [release]. */
    fun cameraSurface(): Surface? = cameraTarget

    /**
     * Sizes the SurfaceTexture's buffers to the requested resolution before
     * the surface is provided to CameraX; applied per SurfaceRequest, so a
     * lens-switch rebind lands on the same size.
     */
    fun setCameraBufferSize(width: Int, height: Int) {
        surfaceTexture?.setDefaultBufferSize(width, height)
    }

    /** Sets the clockwise rotation applied to every frame from here on. */
    fun setRotation(deg: Int) {
        rotationDeg = ((deg % 360) + 360) % 360
    }

    // -------------------------------------------------------------- the draw

    /**
     * One frame: consume the newest camera frame into the OES texture, then
     * draw the quad with the SurfaceTexture transform composed with the
     * current rotation. The SurfaceTexture transform is what an external
     * image needs to sample the right way up; the rotation remap turns that
     * upright frame by the session's quarter turns, corner to corner.
     */
    private fun drawFrameOnGl() {
        if (released.get()) return
        val texture = surfaceTexture ?: return
        val window = encoderWindow
        if (window == EGL14.EGL_NO_SURFACE) return
        try {
            texture.updateTexImage()
            texture.getTransformMatrix(surfaceMatrix)
            Matrix.multiplyMM(uvMatrix, 0, surfaceMatrix, 0, rotationMatrix(), 0)
            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(uvMatrixHandle, 1, false, uvMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, quadCoords)
            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            if (!EGL14.eglSwapBuffers(display, window)) {
                // The encoder side of the window is gone; a stream that can
                // no longer hand over frames must end, not freeze.
                throw IllegalStateException("eglSwapBuffers failed")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Camera GL draw failed: ${t.javaClass.simpleName}")
            onError()
        }
    }

    private fun rotationMatrix(): FloatArray {
        val quarter = (((rotationDeg % 360) + 360) % 360) / 90
        return uvRotationMatrices[quarter.coerceIn(0, 3)]
    }

    // ------------------------------------------------------------- teardown

    /**
     * Stops the GL thread and releases everything it owns: the camera
     * surface, the SurfaceTexture, the encoder window, the EGL context and
     * display. Idempotent and safe in any state, including a failed [start]
     * — call it on every path that leaves the pipeline behind.
     */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        val h = handler
        if (h != null) {
            try {
                runOnGlThread { releaseOnGl() }
            } catch (t: Throwable) {
                Log.w(TAG, "Camera GL release failed: ${t.javaClass.simpleName}")
            }
        }
        thread?.quitSafely()
        thread = null
        handler = null
        surfaceTexture = null
        cameraTarget = null
    }

    private fun releaseOnGl() {
        // The surface detaches the camera producer; the SurfaceTexture then
        // releases its buffers. Both must happen before the context goes.
        cameraTarget?.let { runCatching { it.release() } }
        cameraTarget = null
        surfaceTexture?.let { texture ->
            runCatching { texture.setOnFrameAvailableListener(null) }
            runCatching { texture.release() }
        }
        surfaceTexture = null
        destroyEncoderWindowOnGl()
        if (context != EGL14.EGL_NO_CONTEXT) {
            // The window is unbound; these are the last GL calls before the
            // context is destroyed (which would free them anyway).
            if (program != 0) runCatching { GLES20.glDeleteProgram(program) }
            if (oesTexture != 0) {
                runCatching { GLES20.glDeleteTextures(1, intArrayOf(oesTexture), 0) }
            }
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroyContext(display, context)
            context = EGL14.EGL_NO_CONTEXT
            program = 0
            oesTexture = 0
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
        }
    }

    /**
     * Runs [block] on the GL thread and waits for it. The service calls the
     * pipeline from its startup and control paths, which are already
     * synchronous with respect to the camera; a posted block after release is
     * a no-op.
     */
    private fun runOnGlThread(block: () -> Unit) {
        val h = handler ?: throw IllegalStateException("GL pipeline not started")
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        h.post {
            try {
                if (!released.get()) block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                done.countDown()
            }
        }
        done.await()
        failure?.let { throw it }
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }
}
