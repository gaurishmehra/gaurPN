package dev.gaurish.outlinelite.transfer

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import dev.gaurish.outlinelite.R
import java.io.File

/**
 * Opens a URL, app, or received file with the best available UX. A directly
 * launched activity works only while this app is visible; from the background
 * Android 10+ silently blocks it. The reliable background path is a
 * tap-to-open notification whose content intent is a system-sent
 * `PendingIntent.getActivity`, which is exempt from the background-activity
 * start restriction. This deliberately does not rely on the clipboard
 * overlay or SYSTEM_ALERT_WINDOW: Android 15 no longer treats non-visible
 * overlay windows as a launch exemption.
 */
internal object HandoffDisplay {

    const val CHANNEL_ID = "handoff"

    /** Returns "opened", "notification", or "failed". */
    fun open(
        context: Context,
        intent: Intent,
        title: String,
        text: String,
        key: String,
    ): String {
        if (isForeground(context)) {
            val opened = runCatching { context.startActivity(intent) }.isSuccess
            if (opened) return "opened"
        }
        return if (post(context, intent, title, text, key)) "notification" else "failed"
    }

    /** Builds the FileProvider view intent for a received file. */
    fun openFile(
        context: Context,
        file: File,
        mime: String,
        title: String,
        text: String,
        key: String,
    ): String {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return "failed"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, safeMime(mime))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri(file.name, uri)
        }
        return open(context, intent, title, text, key)
    }

    private val mimePattern =
        Regex("^[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+$")

    /** Keeps a peer-supplied MIME hint from reaching Intent.setType raw. */
    private fun safeMime(raw: String): String {
        val value = raw.trim().take(255)
        return if (mimePattern.matches(value)) value else "application/octet-stream"
    }

    private fun post(
        context: Context,
        intent: Intent,
        title: String,
        text: String,
        key: String,
    ): Boolean {
        return runCatching {
            NotificationChannels.ensureChannel(
                context,
                CHANNEL_ID,
                "Handoffs",
                NotificationManager.IMPORTANCE_HIGH,
                description = "Links, apps, and files opened from other cluster devices",
            )
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return@runCatching false
            val channel = context.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return@runCatching false
            }

            val requestCode = key.hashCode()
            val openIntent = PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_key)
                .setContentTitle("Handoff")
                .setContentText("Tap to open")
                .build()
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_key)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setContentIntent(openIntent)
                .addAction(0, "Open", openIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setLocalOnly(true)
                .build()
            manager.notify(("handoff:$key").hashCode(), notification)
            true
        }.getOrDefault(false)
    }

    private fun isForeground(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val pid = Process.myPid()
        return manager.runningAppProcesses?.any {
            it.pid == pid && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } == true
    }
}
