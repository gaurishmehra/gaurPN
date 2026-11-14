package dev.gaurish.outlinelite.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Shared creation helper for notification channels. Every channel in the
 * app is created through this so the SDK guard and the already-exists check
 * behave identically everywhere. Channel ids, names, and importances are
 * user-visible settings keys: never change an existing one.
 */
object NotificationChannels {

    /**
     * Creates channel [id] if (and only if) it does not exist yet.
     * [configure] receives the freshly built channel for extra one-time
     * setup, e.g. silencing it.
     */
    fun ensureChannel(
        context: Context,
        id: String,
        name: String,
        importance: Int,
        description: String? = null,
        configure: (NotificationChannel.() -> Unit)? = null,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(id) != null) return
        nm.createNotificationChannel(
            NotificationChannel(id, name, importance).apply {
                if (description != null) this.description = description
                configure?.invoke(this)
            }
        )
    }
}
