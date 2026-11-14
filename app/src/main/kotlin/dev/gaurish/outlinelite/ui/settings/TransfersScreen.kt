package dev.gaurish.outlinelite.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gaurish.outlinelite.data.TransferSettingsRepository
import kotlinx.coroutines.launch

/**
 * File-transfer behavior: auto-open on arrival and the transfer history
 * entry point (received files, retries, resends).
 */
@Composable
fun TransfersScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val autoOpenReceived by TransferSettingsRepository.observeAutoOpen(context)
        .collectAsStateWithLifecycle(initialValue = true)
    val allowFileRequests by TransferSettingsRepository.observeAllowFileRequests(context)
        .collectAsStateWithLifecycle(initialValue = true)
    val allowScreenshotRequests by TransferSettingsRepository.observeAllowScreenshotRequests(context)
        .collectAsStateWithLifecycle(initialValue = false)

    // API 33+ splits the old storage permission into media types; the
    // screenshot lookup only needs images.
    val screenshotPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        when (screenshotPermissionResultAction(granted)) {
            ScreenshotToggleAction.PERSIST_ON -> {
                scope.launch {
                    TransferSettingsRepository.setAllowScreenshotRequests(context, true)
                }
            }
            ScreenshotToggleAction.PERSIST_OFF, ScreenshotToggleAction.REQUEST_PERMISSION -> {
                // Denied: keep the feature off instead of leaving a toggle
                // that can never answer.
                scope.launch {
                    TransferSettingsRepository.setAllowScreenshotRequests(context, false)
                }
                Toast.makeText(
                    context,
                    "Screenshot requests need photo access",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    SettingsScaffold(title = "Transfers", onBack = onBack) {
        SettingsRow(
            title = "Open received files automatically",
            summary = "Launch the viewer as soon as a file shared with \"Open in...\" finishes arriving",
        ) {
            GaurSwitch(autoOpenReceived) { enabled ->
                scope.launch { TransferSettingsRepository.setAutoOpen(context, enabled) }
            }
        }

        SettingsRow(
            title = "Allow file requests",
            summary = "Let cluster devices ask this phone for files; you still choose what to send",
        ) {
            GaurSwitch(allowFileRequests) { enabled ->
                scope.launch { TransferSettingsRepository.setAllowFileRequests(context, enabled) }
            }
        }

        SettingsRow(
            title = "Allow screenshot requests",
            summary = "Answer a request with your latest screenshot automatically, without a picker",
        ) {
            GaurSwitch(allowScreenshotRequests) { enabled ->
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    screenshotPermission,
                ) == PackageManager.PERMISSION_GRANTED
                when (screenshotToggleAction(enabled, granted)) {
                    ScreenshotToggleAction.PERSIST_ON -> {
                        scope.launch {
                            TransferSettingsRepository.setAllowScreenshotRequests(context, true)
                        }
                    }
                    ScreenshotToggleAction.PERSIST_OFF -> {
                        scope.launch {
                            TransferSettingsRepository.setAllowScreenshotRequests(context, false)
                        }
                    }
                    ScreenshotToggleAction.REQUEST_PERMISSION -> {
                        permissionLauncher.launch(screenshotPermission)
                    }
                }
            }
        }

        SettingsRow(
            title = "Transfer history",
            summary = "Reopen received files, retry failed sends, and resend links",
            onClick = onOpenHistory,
            trailing = { Chevron() },
        )
        Spacer(Modifier.height(20.dp))
    }
}
