package dev.gaurish.outlinelite.transfer

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.content.FileProvider
import dev.gaurish.outlinelite.data.KeyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Captures the local clipboard and applies clipboard updates received from the
 * cluster.
 *
 * Android 10+ only lets the focused app or the default IME read the clipboard.
 * This app is neither, so the background path briefly adds a 1x1 focusable
 * overlay window, reads the clipboard while its UID owns focus, and removes the
 * window again. The overlay is only added while the screen is unlocked and the
 * relay is connected. This is the same mechanism desktop clipboard managers use
 * on Android; it is not silent, and Android may show its "pasted from
 * clipboard" privacy toast when a new clip is read.
 */
object ClipboardSyncManager {

    private const val TAG = "ClipboardSync"
    private const val POLL_INTERVAL_MS = 3_000L
    private const val MAX_TEXT_BYTES = 1024 * 1024
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private const val MAX_IMAGE_DIMENSION = 2_048
    private const val KEEP_IMAGE_FILES = 10

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val applyMutex = Mutex()

    @Volatile private var settingsJob: Job? = null
    @Volatile private var pollJob: Job? = null
    @Volatile private var appContext: Context? = null

    @Volatile private var relayConnected = false
    @Volatile private var enabled = false
    @Volatile private var includeSensitive = false
    @Volatile private var applyingRemote = false
    @Volatile private var lastSentHash: String? = null
    @Volatile private var lastAppliedHash: String? = null

    @Volatile
    var lastAppliedTs: Long = 0L
        private set

    private val _state = MutableStateFlow(ClipboardSyncState())
    val state: StateFlow<ClipboardSyncState> = _state.asStateFlow()

    /** Starts observing the persisted setting. Safe to call repeatedly. */
    @Synchronized
    fun start(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (settingsJob != null) return
        settingsJob = scope.launch {
            combine(
                KeyRepository.observeClipboardSync(ctx),
                KeyRepository.observeClipboardSensitive(ctx),
            ) { sync, sensitive -> sync to sensitive }
                .collect { (sync, sensitive) ->
                    enabled = sync
                    includeSensitive = sensitive
                    refreshInternal()
                }
        }
    }

    /** Stops everything, e.g. when the tunnel disconnects. */
    @Synchronized
    fun stop() {
        settingsJob?.cancel()
        settingsJob = null
        pollJob?.cancel()
        pollJob = null
        relayConnected = false
        updateState(ClipboardSyncPhase.DISABLED, "Off")
    }

    @Synchronized
    fun onRelayConnected(context: Context) {
        appContext = context.applicationContext
        relayConnected = true
        start(context)
        refreshInternal()
    }

    @Synchronized
    fun onRelayDisconnected() {
        relayConnected = false
        pollJob?.cancel()
        pollJob = null
        if (enabled) {
            updateState(ClipboardSyncPhase.WAITING_FOR_VPN, "Waiting for the VPN connection")
        } else {
            updateState(ClipboardSyncPhase.DISABLED, "Off")
        }
    }

    /** Re-evaluates permissions and connectivity after returning from Settings. */
    @Synchronized
    fun refresh(context: Context) {
        appContext = context.applicationContext
        start(context)
        refreshInternal()
    }

    @Synchronized
    private fun refreshInternal() {
        val ctx = appContext ?: return
        if (!enabled) {
            pollJob?.cancel()
            pollJob = null
            updateState(ClipboardSyncPhase.DISABLED, "Off")
            return
        }
        if (!Settings.canDrawOverlays(ctx)) {
            pollJob?.cancel()
            pollJob = null
            updateState(
                ClipboardSyncPhase.WAITING_FOR_PERMISSION,
                "Grant \"Display over other apps\" to read the clipboard in the background",
            )
            return
        }
        if (!relayConnected) {
            pollJob?.cancel()
            pollJob = null
            updateState(ClipboardSyncPhase.WAITING_FOR_VPN, "Waiting for the VPN connection")
            return
        }
        if (pollJob == null) {
            _state.value = _state.value.copy(
                phase = ClipboardSyncPhase.ACTIVE,
                lastMessage = _state.value.lastMessage ?: "Watching the clipboard",
                lastAt = System.currentTimeMillis(),
            )
            pollJob = scope.launch { pollLoop(ctx) }
        }
    }

    private suspend fun pollLoop(context: Context) {
        while (currentCoroutineContext().isActive) {
            try {
                if (!applyingRemote && isDeviceInteractive(context)) {
                    val clip = readClipboard(context)
                    if (clip != null) {
                        val snapshot = extractSnapshot(context, clip)
                        if (snapshot == null) {
                            Log.d(TAG, "Clipboard had no usable text or image")
                        } else if (snapshot.hash != lastSentHash && snapshot.hash != lastAppliedHash) {
                            if (snapshot.sensitive && !includeSensitive) {
                                updateState(
                                    ClipboardSyncPhase.ACTIVE,
                                    "Skipped a sensitive clip",
                                )
                            } else {
                                val sent = RelayManager.broadcastClipboard(context, snapshot)
                                if (sent) {
                                    lastSentHash = snapshot.hash
                                    updateState(
                                        ClipboardSyncPhase.ACTIVE,
                                        "Sent ${snapshot.preview}",
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Clipboard poll failed: ${t.message}")
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun isDeviceInteractive(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        if (!pm.isInteractive) return false
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return true
        return !km.isKeyguardLocked
    }

    private suspend fun readClipboard(context: Context): ClipData? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null

        // If this app happens to be foreground, it already has focus and can
        // read directly without the overlay flicker. Avoid calling this in the
        // background: it would just log a denial every poll interval.
        if (isAppForeground(context)) {
            if (!runCatching { cm.hasPrimaryClip() }.getOrDefault(false)) return null
            val direct = runCatching { cm.primaryClip }.getOrNull()
            if (direct != null) return direct
        }

        return readWithOverlay(context, cm)
    }

    private fun isAppForeground(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.pid == Process.myPid() &&
            info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private suspend fun readWithOverlay(
        context: Context,
        cm: ClipboardManager,
    ): ClipData? = withContext(Dispatchers.Main) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return@withContext null
        val view = View(context)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                // Take window focus so the UID can read the clipboard, but do
                // not become the IME target, so the user's keyboard stays up.
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = 0.01f
        }

        try {
            wm.addView(view, params)
            // Give WindowManager a moment to make this UID the focused window.
            // Keep this window short: it briefly owns focus, so long waits
            // would eat keystrokes from the app underneath.
            repeat(2) {
                delay(60)
                val clip = runCatching { cm.primaryClip }.getOrNull()
                if (clip != null) return@withContext clip
            }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "Overlay clipboard read failed: ${t.message}")
            null
        } finally {
            runCatching { wm.removeView(view) }
        }
    }

    private fun extractSnapshot(context: Context, clip: ClipData): ClipboardSnapshot? {
        val sensitive = isSensitive(clip)
        Log.d(TAG, "Clipboard clip: items=${clip.itemCount}, desc=${clip.description}")
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val mime = item.uri?.let { runCatching { context.contentResolver.getType(it) }.getOrNull() }
            Log.d(TAG, "  item $i: uri=${item.uri} mime=$mime textLen=${item.text?.length ?: 0}")
        }

        // Prefer an actual image when the source app put both a text
        // representation and an image URI on the clipboard (browsers often do).
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val uri = item.uri ?: continue
            // Screenshot providers often expose content://media/.../12345 with
            // no extension. getType can return null there, so fall back to the
            // clip description before guessing from the path.
            val mime = context.contentResolver.getType(uri)
                ?: runCatching { clip.description.getMimeType(i) }.getOrNull()
                ?: guessMime(uri)
            if (!mime.startsWith("image/")) continue
            val encoded = readImage(context, uri)
            if (encoded == null) {
                Log.w(TAG, "Skipping unreadable clipboard image $uri")
                continue
            }
            if (encoded.bytes.size > MAX_IMAGE_BYTES) {
                Log.w(TAG, "Clipboard image too large (${encoded.bytes.size} bytes); skipping")
                continue
            }
            return ClipboardSnapshot(
                kind = "image",
                mime = encoded.mime,
                bytes = encoded.bytes,
                hash = Crypto.sha256Hex(encoded.bytes),
                preview = "Image (${encoded.bytes.size / 1024} KB)",
                sensitive = sensitive,
            )
        }

        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val text = (item.text ?: item.htmlText)?.toString()
            if (!text.isNullOrEmpty()) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_TEXT_BYTES) {
                    Log.w(TAG, "Clipboard text too large (${bytes.size} bytes); skipping")
                    continue
                }
                return ClipboardSnapshot(
                    kind = "text",
                    mime = "text/plain",
                    bytes = bytes,
                    hash = Crypto.sha256Hex(bytes),
                    preview = text.take(80).replace('\n', ' '),
                    sensitive = sensitive,
                )
            }
        }
        return null
    }

    private fun isSensitive(clip: ClipData): Boolean {
        val extras = clip.description.extras ?: return false
        return extras.getBoolean("android.content.extra.IS_SENSITIVE", false)
    }

    private fun guessMime(uri: Uri): String {
        val path = uri.lastPathSegment ?: return "application/octet-stream"
        return when (path.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "application/octet-stream"
        }
    }

    private data class EncodedImage(val bytes: ByteArray, val mime: String)

    private fun readImage(context: Context, uri: Uri): EncodedImage? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "Clipboard image $uri has invalid bounds ${bounds.outWidth}x${bounds.outHeight}")
            return null
        }

        val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_IMAGE_DIMENSION)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val original = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        encodeBitmap(original)
    }.onFailure {
        Log.w(TAG, "Failed to read clipboard image $uri: ${it.message}", it)
    }.getOrNull()

    private fun encodeImageBytes(bytes: ByteArray): EncodedImage? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_IMAGE_DIMENSION)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        encodeBitmap(original)
    }.getOrNull()

    private fun encodeBitmap(original: Bitmap): EncodedImage {
        val scale = minOf(
            1f,
            MAX_IMAGE_DIMENSION.toFloat() / maxOf(original.width, original.height),
        )
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                original,
                (original.width * scale).toInt().coerceAtLeast(1),
                (original.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            original
        }

        val out = ByteArrayOutputStream()
        val format = if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val mime = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
        bitmap.compress(format, 85, out)
        if (bitmap !== original) bitmap.recycle()
        original.recycle()
        return EncodedImage(out.toByteArray(), mime)
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= maxDimension && h / 2 >= maxDimension) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    /** Applies a decrypted peer clipboard update to the local clipboard. */
    suspend fun applyRemote(context: Context, payload: ClipboardPayload, relayTs: Long) {
        applyMutex.withLock {
            if (relayTs <= lastAppliedTs) return@withLock
            val hash = Crypto.sha256Hex(payload.bytes)
            if (hash == lastSentHash || hash == lastAppliedHash) {
                lastAppliedTs = relayTs
                return@withLock
            }

            applyingRemote = true
            lastAppliedHash = hash
            lastAppliedTs = relayTs
            try {
                withContext(Dispatchers.Main) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        ?: return@withContext
                    when (payload.kind) {
                        "text" -> {
                            val text = String(payload.bytes, Charsets.UTF_8)
                            cm.setPrimaryClip(ClipData.newPlainText("gaurPN", text))
                            updateState(ClipboardSyncPhase.ACTIVE, "Received text")
                        }
                        "image" -> {
                            val file = saveClipboardImage(context, payload.bytes, payload.mime)
                            // The clipboard round-trip re-encodes the image, so
                            // hash what the poll loop will read back, not the
                            // bytes we received. Otherwise the next poll sees a
                            // "new" image and echoes it back to the cluster.
                            val reencoded = encodeImageBytes(payload.bytes)
                            if (reencoded != null) {
                                lastAppliedHash = Crypto.sha256Hex(reencoded.bytes)
                            }
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            cm.setPrimaryClip(
                                ClipData.newUri(context.contentResolver, "gaurPN image", uri)
                            )
                            updateState(ClipboardSyncPhase.ACTIVE, "Received image")
                        }
                    }
                }
                delay(350)
            } finally {
                applyingRemote = false
            }
        }
    }

    private fun saveClipboardImage(context: Context, bytes: ByteArray, mime: String): File {
        val dir = File(context.filesDir, "clipboard").apply { mkdirs() }
        val ext = when {
            mime.contains("jpeg", ignoreCase = true) -> "jpg"
            mime.contains("png", ignoreCase = true) -> "png"
            mime.contains("webp", ignoreCase = true) -> "webp"
            else -> "png"
        }
        val file = File(dir, "clip-${System.currentTimeMillis()}.$ext")
        file.writeBytes(bytes)
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP_IMAGE_FILES)
            ?.forEach { it.delete() }
        return file
    }

    private fun updateState(phase: ClipboardSyncPhase, message: String?) {
        _state.value = ClipboardSyncState(
            phase = phase,
            lastMessage = message,
            lastAt = System.currentTimeMillis(),
        )
    }
}
