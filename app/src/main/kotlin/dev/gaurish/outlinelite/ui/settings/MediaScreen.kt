package dev.gaurish.outlinelite.ui.settings

import android.content.Intent
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
import dev.gaurish.outlinelite.transfer.MediaSessionBridge
import kotlinx.coroutines.launch

/**
 * Media control (MPRIS) settings: master toggle, live session status, and the
 * notification-listener access required to read media sessions.
 */
@Composable
fun MediaScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val mediaSync by KeyRepository.observeMediaSync(context)
        .collectAsStateWithLifecycle(initialValue = false)
    val mediaState by MediaSessionBridge.state.collectAsStateWithLifecycle()
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
                MediaSessionBridge.refresh(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsScaffold(title = "Media Controls", onBack = onBack) {
        SettingsRow(
            title = "Share media controls",
            summary = when {
                !mediaSync -> "Off — control this device's media from other cluster devices"
                !notificationAccess -> "Grant notification access to read media sessions"
                else -> "On — now-playing and transport controls are shared"
            },
        ) {
            GaurSwitch(mediaSync) { enabled ->
                scope.launch { KeyRepository.setMediaSync(context, enabled) }
                MediaSessionBridge.refresh(context)
                if (enabled && !notificationAccess) {
                    Toast.makeText(
                        context,
                        "Grant notification access to share media controls",
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
        SettingsRow(
            title = "Media session",
            summary = when {
                !mediaSync -> "Off"
                mediaState.active -> "${mediaState.title ?: "Unknown"} — ${mediaState.app ?: ""}"
                mediaState.error != null -> mediaState.error!!
                else -> "No active media session"
            },
            onClick = { MediaSessionBridge.refresh(context) },
        )
        if (mediaSync && !notificationAccess) {
            SettingsRow(
                title = "Notification access",
                summary = "Required to read media sessions",
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        )
                    }
                },
            )
        }
        SettingsFootnote(
            "Media metadata and commands are sealed end-to-end. Position is sent on " +
                "state changes only; the desktop interpolates it locally."
        )
        Spacer(Modifier.height(20.dp))
    }
}
