package dev.gaurish.outlinelite.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gaurish.outlinelite.data.TransferSettingsRepository
import dev.gaurish.outlinelite.transfer.allFilesAccessIntent
import dev.gaurish.outlinelite.transfer.hasAllFilesAccess
import kotlinx.coroutines.launch

/**
 * File-transfer behavior: auto-open on arrival, browse/fetch visibility, the
 * transfer history entry point (received files, retries, resends), and the
 * screenshot-request toggle.
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
    val allowRemoteOpen by TransferSettingsRepository.observeAllowRemoteOpen(context)
        .collectAsStateWithLifecycle(initialValue = true)
    val allowFileRequests by TransferSettingsRepository.observeAllowFileRequests(context)
        .collectAsStateWithLifecycle(initialValue = true)
    val allowScreenshotRequests by TransferSettingsRepository.observeAllowScreenshotRequests(context)
        .collectAsStateWithLifecycle(initialValue = false)

    // All-files access is granted on a system settings page, so the row must
    // re-read it when the user comes back instead of trusting a stale value.
    var allFilesAccess by remember { mutableStateOf(hasAllFilesAccess()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allFilesAccess = hasAllFilesAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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

    // Enabling file requests asks for the media grants the six collections
    // need. Denial is not fatal: an ungranted collection lists empty, and
    // all-files access covers everything outside MediaStore.
    val browsePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Grants are optional; the browse roots stay available. */ }

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
            title = "Allow remote open",
            summary = "Let trusted devices open links, files, and apps on this phone",
        ) {
            GaurSwitch(allowRemoteOpen) { enabled ->
                scope.launch { TransferSettingsRepository.setAllowRemoteOpen(context, enabled) }
            }
        }

        SettingsRow(
            title = "Allow file requests",
            summary = "Let cluster devices browse this phone's shared folders and download what they pick",
        ) {
            GaurSwitch(allowFileRequests) { enabled ->
                scope.launch { TransferSettingsRepository.setAllowFileRequests(context, enabled) }
                if (enabled) browsePermissionLauncher.launch(browseMediaPermissions())
            }
        }

        if (allowFileRequests) {
            val grantAction: (@Composable () -> Unit)? = if (allFilesAccess) {
                null
            } else {
                {
                    TextButton(
                        onClick = {
                            // Some ROMs ship no all-files-access activity;
                            // the row simply stays "Limited" there.
                            runCatching {
                                context.startActivity(allFilesAccessIntent(context))
                            }
                        },
                    ) {
                        Text("Grant")
                    }
                }
            }
            SettingsRow(
                title = "All files access",
                summary = if (allFilesAccess) {
                    "Complete — all shared-storage files are browsable by trusted devices"
                } else {
                    "Limited — trusted devices can only browse media collections. " +
                        "Grant for full file visibility."
                },
                trailing = grantAction,
            )
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
