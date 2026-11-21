package dev.gaurish.outlinelite.ui.settings

import android.content.Intent
import android.os.Build
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gaurish.outlinelite.data.KeyRepository
import dev.gaurish.outlinelite.transfer.MIRROR_CHANNEL_ID
import dev.gaurish.outlinelite.transfer.NotificationMirrorManager
import kotlinx.coroutines.launch

/**
 * Notification mirroring settings: master toggle, notification-listener
 * access, the mirrored channel, and the app-level notification toggle.
 */
@Composable
fun NotificationsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val notificationSync by KeyRepository.observeNotificationSync(context)
        .collectAsStateWithLifecycle(initialValue = false)
    var notificationAccess by remember {
        mutableStateOf(
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(context)
                    .contains(context.packageName)
                NotificationMirrorManager.refresh(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsScaffold(title = "Notifications", onBack = onBack) {
        SettingsRow(
            title = "Share notifications",
            summary = when {
                !notificationSync -> "Off. Notifications are not shared."
                !notificationAccess -> "Grant notification access to start mirroring"
                else -> "On. Notifications appear on every device."
            },
        ) {
            GaurSwitch(notificationSync) { enabled ->
                scope.launch { KeyRepository.setNotificationSync(context, enabled) }
                NotificationMirrorManager.refresh(context)
                if (enabled && !notificationAccess) {
                    Toast.makeText(
                        context,
                        "Grant notification access to mirror notifications",
                        Toast.LENGTH_LONG,
                    ).show()
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        )
                    }
                }
            }
        }

        if (notificationSync && !notificationAccess) {
            SettingsRow(
                title = "Notification access",
                summary = "Required to read notifications from other apps",
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        )
                    }
                },
            )
        }

        if (notificationSync) {
            val appNotificationsEnabled =
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            if (!appNotificationsEnabled) {
                SettingsRow(
                    title = "Allow gaurPN notifications",
                    summary = "Android is blocking mirrored notifications",
                    onClick = {
                        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                        } else {
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            )
                        }
                        runCatching { context.startActivity(intent) }
                    },
                )
            }
            SettingsRow(
                title = "Mirrored notification channel",
                summary = "Android may mute this channel independently",
                onClick = {
                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            putExtra(Settings.EXTRA_CHANNEL_ID, MIRROR_CHANNEL_ID)
                        }
                    } else {
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        )
                    }
                    runCatching { context.startActivity(intent) }
                },
            )
        }

        SettingsFootnote(
            "Mirrored notifications are end-to-end encrypted. gaurPN ignores its own " +
                "notifications, so mirrored copies are never re-broadcast."
        )
        Spacer(Modifier.height(20.dp))
    }
}
