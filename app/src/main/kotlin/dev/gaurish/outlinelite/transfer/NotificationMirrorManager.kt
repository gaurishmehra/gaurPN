package dev.gaurish.outlinelite.transfer

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput as PlatformRemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput as CompatRemoteInput
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.data.KeyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

enum class NotificationMirrorPhase {
    DISABLED,
    WAITING_FOR_PERMISSION,
    ACTIVE,
    ERROR,
}

data class NotificationMirrorState(
    val phase: NotificationMirrorPhase = NotificationMirrorPhase.DISABLED,
    val lastMessage: String? = null,
)

/**
 * Captures local notifications and broadcasts sealed copies to every cluster
 * peer. The app's own notifications are ignored by package name, which is the
 * loop guard: a mirrored notification is posted by this package, so it is
 * never re-broadcast.
 */
object NotificationMirrorManager {

    private const val TAG = "NotificationMirror"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(NotificationMirrorState())
    val state: StateFlow<NotificationMirrorState> = _state.asStateFlow()

    @Volatile
    private var enabled = false

    @Volatile
    private var listenerConnected = false

    private val active = ConcurrentHashMap<String, StatusBarNotification>()

    fun refresh(context: Context) {
        scope.launch {
            enabled = runCatching {
                KeyRepository.notificationSyncEnabled(context)
            }.getOrDefault(false)
            val access = NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
            val phase = when {
                !enabled -> NotificationMirrorPhase.DISABLED
                !access || !listenerConnected -> NotificationMirrorPhase.WAITING_FOR_PERMISSION
                else -> NotificationMirrorPhase.ACTIVE
            }
            _state.value = NotificationMirrorState(phase, _state.value.lastMessage)
        }
    }

    fun onServiceConnected() {
        listenerConnected = true
        _state.value = _state.value.copy(phase = NotificationMirrorPhase.ACTIVE)
    }

    fun onServiceDisconnected() {
        listenerConnected = false
        active.clear()
        _state.value = _state.value.copy(phase = NotificationMirrorPhase.WAITING_FOR_PERMISSION)
    }

    fun onPosted(context: Context, sbn: StatusBarNotification) {
        if (!enabled) return
        if (sbn.packageName == context.packageName) return
        // Group summaries duplicate their children; skip them to avoid noise.
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        // Silent, ongoing, foreground-service, and progress notifications are
        // status chrome, not messages. Mirroring them spams every device.
        if (isSilent(context, sbn)) return

        active[sbn.key] = sbn
        scope.launch {
            val payload = buildPayload(context, sbn) ?: return@launch
            RelayManager.broadcastNotification(context, "posted", payload)
        }
    }

    fun onRemoved(context: Context, sbn: StatusBarNotification) {
        if (!enabled) return
        if (sbn.packageName == context.packageName) return
        active.remove(sbn.key)
        scope.launch {
            RelayManager.broadcastNotification(
                context,
                "removed",
                JSONObject().put("key", sbn.key),
            )
        }
    }

    /** Executes a mirrored action on the source device. */
    fun executeAction(context: Context, notifKey: String, actionIndex: Int, reply: String?) {
        val sbn = active[notifKey] ?: return
        val action = sbn.notification.actions?.getOrNull(actionIndex) ?: return
        runCatching {
            if (reply != null && action.remoteInputs?.isNotEmpty() == true) {
                val intent = Intent()
                val bundle = Bundle().apply {
                    putCharSequence(action.remoteInputs[0].resultKey, reply)
                }
                PlatformRemoteInput.addResultsToIntent(action.remoteInputs, intent, bundle)
                action.actionIntent.send(context, 0, intent)
            } else {
                action.actionIntent.send()
            }
        }.onFailure { Log.w(TAG, "Notification action failed: ${it.message}") }
    }

    private fun isSilent(context: Context, sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (sbn.isOngoing) return true
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return true
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return true
        if (n.category == Notification.CATEGORY_SERVICE ||
            n.category == Notification.CATEGORY_PROGRESS ||
            n.category == Notification.CATEGORY_TRANSPORT
        ) {
            return true
        }
        // Media notifications are redundant now that the media session is
        // mirrored as MPRIS; they also fire on every play/pause.
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = n.channelId
            if (!channelId.isNullOrBlank()) {
                val nm = context.getSystemService(NotificationManager::class.java)
                val channel = nm?.getNotificationChannel(channelId)
                if (channel != null && channel.importance <= NotificationManager.IMPORTANCE_LOW) {
                    return true
                }
            }
        } else if (n.priority <= Notification.PRIORITY_LOW) {
            return true
        }
        return false
    }

    private fun buildPayload(context: Context, sbn: StatusBarNotification): JSONObject? {
        val notification = sbn.notification
        val extras = notification.extras
        val appName = runCatching {
            val info = context.packageManager.getApplicationInfo(sbn.packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(sbn.packageName)

        val actions = JSONArray()
        notification.actions?.forEachIndexed { index, action ->
            val title = action.title?.toString()?.trim().orEmpty()
            if (title.isNotEmpty()) {
                actions.put(
                    JSONObject()
                        .put("i", index)
                        .put("t", title)
                        .put("r", action.remoteInputs?.isNotEmpty() == true)
                )
            }
        }

        return JSONObject().apply {
            put("key", sbn.key)
            put("package", sbn.packageName)
            put("app", appName)
            put("title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty())
            put("text", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty())
            put("big_text", extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty())
            put("subtext", extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty())
            put("category", notification.category ?: "")
            put("ongoing", sbn.isOngoing)
            put("clearable", sbn.isClearable)
            put("ts", sbn.postTime)
            put("icon", iconToPng(context, sbn) ?: "")
            put("actions", actions)
        }
    }

    private fun iconToPng(context: Context, sbn: StatusBarNotification): String? {
        val drawable = runCatching {
            sbn.notification.smallIcon?.loadDrawable(context)
        }.getOrNull() ?: runCatching {
            context.packageManager.getApplicationIcon(sbn.packageName)
        }.getOrNull() ?: return null

        val size = 64
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
        val bytes = out.toByteArray()
        if (bytes.size > 64 * 1024) return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}

class NotificationMirrorService : NotificationListenerService() {
    override fun onListenerConnected() {
        NotificationMirrorManager.onServiceConnected()
        NotificationMirrorManager.refresh(this)
        MediaSessionBridge.refresh(this)
    }

    override fun onListenerDisconnected() {
        NotificationMirrorManager.onServiceDisconnected()
        MediaSessionBridge.onServiceDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NotificationMirrorManager.onPosted(this, sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationMirrorManager.onRemoved(this, sbn)
    }
}

internal const val MIRROR_CHANNEL_ID = "mirrored_notifications"
private const val MIRROR_ACTION = "dev.gaurish.outlinelite.MIRRORED_ACTION"
private const val EXTRA_FROM = "from_id"
private const val EXTRA_KEY = "notif_key"
private const val EXTRA_INDEX = "action_index"
private const val EXTRA_REPLY = "reply"

/** Renders a notification received from another cluster device. */
object NotificationDisplay {

    fun show(context: Context, fromId: String, fromName: String, payload: JSONObject): Boolean {
        val notifKey = payload.optString("key")
        if (notifKey.isBlank()) return false
        if (sourceAppInstalled(context, payload)) {
            // The local install posts its own notification. Showing the
            // mirrored copy as well would duplicate it.
            RelayManager.sendNotificationAck(context, fromId, notifKey, "suppressed")
            return false
        }
        ensureChannel(context)
        val appName = payload.optString("app").ifBlank { fromName }
        val title = payload.optString("title").ifBlank { appName }
        val text = payload.optString("text")
        val bigText = payload.optString("big_text").ifBlank { text }
        val subtext = payload.optString("subtext")
        val id = ("$fromId:$notifKey").hashCode()

        val builder = NotificationCompat.Builder(context, MIRROR_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_share)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(if (subtext.isNotBlank()) subtext else appName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setAutoCancel(true)

        val iconB64 = payload.optString("icon")
        if (iconB64.isNotBlank()) {
            val bytes = runCatching { Base64.decode(iconB64, Base64.NO_WRAP) }.getOrNull()
            val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bitmap != null) builder.setLargeIcon(bitmap)
        }

        val actions = payload.optJSONArray("actions")
        if (actions != null) {
            for (i in 0 until actions.length()) {
                val action = actions.optJSONObject(i) ?: continue
                val index = action.optInt("i", i)
                val label = action.optString("t")
                if (label.isBlank()) continue
                val reply = action.optBoolean("r", false)
                val intent = Intent(context, NotificationActionReceiver::class.java).apply {
                    this.action = MIRROR_ACTION
                    putExtra(EXTRA_FROM, fromId)
                    putExtra(EXTRA_KEY, notifKey)
                    putExtra(EXTRA_INDEX, index)
                    putExtra(EXTRA_REPLY, reply)
                }
                val pending = PendingIntent.getBroadcast(
                    context,
                    ("$id:$index").hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val actionBuilder = NotificationCompat.Action.Builder(0, label, pending)
                if (reply) {
                    actionBuilder.addRemoteInput(
                        CompatRemoteInput.Builder("reply").setLabel("Reply").build()
                    )
                }
                builder.addAction(actionBuilder.build())
            }
        }

        val appEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = nm?.getNotificationChannel(MIRROR_CHANNEL_ID)
        val channelBlocked = channel != null &&
            channel.importance == NotificationManager.IMPORTANCE_NONE
        val shown = appEnabled && !channelBlocked && runCatching {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        }.isSuccess
        RelayManager.sendNotificationAck(
            context,
            fromId,
            notifKey,
            if (shown) "displayed" else "blocked",
        )
        return shown
    }

    fun cancel(context: Context, fromId: String, notifKey: String) {
        if (notifKey.isBlank()) return
        val id = ("$fromId:$notifKey").hashCode()
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    /**
     * True when the package named by the payload is installed on this device.
     * That install receives the notification natively, so the mirrored copy is
     * redundant. The manifest declares QUERY_ALL_PACKAGES so the lookup is not
     * filtered by Android 11 package visibility.
     */
    private fun sourceAppInstalled(context: Context, payload: JSONObject): Boolean {
        return NotificationDedupe.shouldSuppress(
            packageName = payload.optString("package"),
            selfPackage = context.packageName,
        ) { pkg ->
            runCatching {
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        pkg,
                        PackageManager.PackageInfoFlags.of(0),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(pkg, 0)
                }
                // A disabled app cannot post its own notification, so keep
                // showing the mirror in that case.
                info.applicationInfo?.enabled != false
            }.getOrDefault(false)
        }
    }

    private fun ensureChannel(context: Context) {
        NotificationChannels.ensureChannel(
            context,
            MIRROR_CHANNEL_ID,
            "Mirrored notifications",
            NotificationManager.IMPORTANCE_HIGH,
            description = "Notifications shared from other cluster devices",
        )
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MIRROR_ACTION) return
        val fromId = intent.getStringExtra(EXTRA_FROM) ?: return
        val notifKey = intent.getStringExtra(EXTRA_KEY) ?: return
        val index = intent.getIntExtra(EXTRA_INDEX, -1)
        if (index < 0) return
        val reply = if (intent.getBooleanExtra(EXTRA_REPLY, false)) {
            PlatformRemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence("reply")
                ?.toString()
        } else {
            null
        }
        val appCtx = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            RelayManager.sendNotificationAction(appCtx, fromId, notifKey, index, reply)
        }
        NotificationManagerCompat.from(context).cancel(("$fromId:$notifKey").hashCode())
    }
}
