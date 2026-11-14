package dev.gaurish.outlinelite.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gaurish.outlinelite.data.KeyRepository
import dev.gaurish.outlinelite.transfer.ClipboardSyncManager
import dev.gaurish.outlinelite.transfer.ClipboardSyncPhase
import dev.gaurish.outlinelite.transfer.RelayManager
import kotlinx.coroutines.launch

/**
 * Clipboard sync settings: master toggle, the overlay permission it needs on
 * Android 10+, sensitive-clip handling, and per-device exclusions.
 */
@Composable
fun ClipboardSyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val clipboardSync by KeyRepository.observeClipboardSync(context)
        .collectAsStateWithLifecycle(initialValue = false)
    val clipboardSensitive by KeyRepository.observeClipboardSensitive(context)
        .collectAsStateWithLifecycle(initialValue = false)
    val clipboardState by ClipboardSyncManager.state.collectAsStateWithLifecycle()
    val peers by RelayManager.peers.collectAsStateWithLifecycle()
    val clipboardExcluded by KeyRepository.observeClipboardExcludedPeers(context)
        .collectAsStateWithLifecycle(initialValue = emptySet())
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
                ClipboardSyncManager.refresh(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val clipboardSummary = when {
        !clipboardSync -> "Off — copy on one device, paste on another"
        !overlayGranted ->
            "Grant \"Display over other apps\" to read the clipboard in the background"
        clipboardState.phase == ClipboardSyncPhase.WAITING_FOR_VPN ->
            "Waiting for the VPN connection"
        clipboardState.phase == ClipboardSyncPhase.ERROR ->
            clipboardState.lastMessage ?: "Clipboard sync error"
        else -> clipboardState.lastMessage ?: "On — text and images sync across your cluster"
    }

    SettingsScaffold(title = "Clipboard Sync", onBack = onBack) {
        SettingsRow(
            title = "Sync clipboard",
            summary = clipboardSummary,
        ) {
            GaurSwitch(clipboardSync) { enabled ->
                scope.launch { KeyRepository.setClipboardSync(context, enabled) }
                if (enabled && !Settings.canDrawOverlays(context)) {
                    Toast.makeText(
                        context,
                        "Grant overlay permission so gaurPN can read the clipboard in the background",
                        Toast.LENGTH_LONG,
                    ).show()
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                    runCatching { context.startActivity(intent) }
                } else {
                    ClipboardSyncManager.refresh(context)
                }
            }
        }

        if (clipboardSync && !overlayGranted) {
            SettingsRow(
                title = "Overlay permission",
                summary = "Required for background clipboard reads on Android 10+",
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                    runCatching { context.startActivity(intent) }
                },
            )
        }

        if (clipboardSync) {
            SettingsRow(
                title = "Include sensitive clips",
                summary = "Also sync passwords and OTP codes. Not recommended.",
            ) {
                GaurSwitch(clipboardSensitive) { enabled ->
                    scope.launch { KeyRepository.setClipboardSensitive(context, enabled) }
                }
            }

            val otherPeers = peers.filter { !it.isSelf }
            if (otherPeers.isNotEmpty()) {
                Text(
                    "Per-device clipboard",
                    fontSize = 11.sp,
                    letterSpacing = 1.4.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                )
                otherPeers.forEach { peer ->
                    SettingsRow(
                        title = peer.name,
                        summary = if (peer.id in clipboardExcluded) {
                            "Clipboard sync off for this device"
                        } else {
                            "Clipboard sync on for this device"
                        },
                    ) {
                        GaurSwitch(peer.id !in clipboardExcluded) { enabled ->
                            scope.launch {
                                KeyRepository.setClipboardExcluded(
                                    context,
                                    peer.id,
                                    !enabled,
                                )
                            }
                        }
                    }
                }
            }

            SettingsRow(
                title = "Last clipboard activity",
                summary = clipboardState.lastMessage ?: "No clipboard synced yet",
            )

            SettingsFootnote(
                "Android 10+ blocks background clipboard reads. gaurPN briefly takes " +
                    "focus to check for new clips; Android may show its paste toast."
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}
