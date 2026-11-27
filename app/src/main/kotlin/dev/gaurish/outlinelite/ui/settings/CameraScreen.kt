package dev.gaurish.outlinelite.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.data.TransferSettingsRepository
import dev.gaurish.outlinelite.transfer.CameraLens
import dev.gaurish.outlinelite.transfer.CameraTier
import dev.gaurish.outlinelite.ui.BenchType
import kotlinx.coroutines.launch

/** Human labels for the pinned quality tiers; the wire word never changes. */
private data class TierOption(val tier: CameraTier, val label: String, val detail: String)

private val tierOptions = listOf(
    TierOption(CameraTier.HIGH, "High", "1080p · up to 8 Mbps"),
    TierOption(CameraTier.BALANCED, "Balanced", "720p · up to 3.5 Mbps"),
    TierOption(CameraTier.LOW, "Low", "360p · up to 0.5 Mbps"),
)

private data class LensOption(val lens: CameraLens, val label: String)

private val lensOptions = listOf(
    LensOption(CameraLens.BACK, "Back camera"),
    LensOption(CameraLens.FRONT, "Front camera"),
)

private fun tierSummary(tier: CameraTier): String =
    tierOptions.firstOrNull { it.tier == tier }?.let { "${it.label} · ${it.detail}" } ?: ""

/**
 * Live camera streaming to trusted desktops: the request toggle (gated on
 * the CAMERA runtime permission), auto-accept, the default quality tier, and
 * the default lens. Streaming starts from an Accept/Decline prompt; the
 * desktop controls torch, lens, and tier live, and ends with Stop.
 */
@Composable
fun CameraScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val allowCameraRequests by TransferSettingsRepository.observeAllowCameraRequests(context)
        .collectAsStateWithLifecycle(initialValue = false)
    val autoAcceptTrusted by TransferSettingsRepository.observeCameraAutoAcceptTrusted(context)
        .collectAsStateWithLifecycle(initialValue = false)
    val cameraQuality by TransferSettingsRepository.observeCameraQuality(context)
        .collectAsStateWithLifecycle(initialValue = TransferSettingsRepository.CAMERA_QUALITY_DEFAULT)
    val cameraLens by TransferSettingsRepository.observeCameraLens(context)
        .collectAsStateWithLifecycle(initialValue = TransferSettingsRepository.CAMERA_LENS_DEFAULT)

    // The camera grant lives in the OS, so the row re-reads it when the user
    // comes back from the system dialog instead of trusting a stale value.
    var cameraPermission by remember { mutableStateOf(hasCameraPermission(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                cameraPermission = hasCameraPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        when (cameraPermissionResultAction(granted)) {
            CameraToggleAction.PERSIST_ON -> {
                scope.launch {
                    TransferSettingsRepository.setAllowCameraRequests(context, true)
                }
            }
            CameraToggleAction.PERSIST_OFF, CameraToggleAction.REQUEST_PERMISSION -> {
                // Denied: keep the feature off instead of leaving a toggle
                // that can never answer.
                scope.launch {
                    TransferSettingsRepository.setAllowCameraRequests(context, false)
                }
                Toast.makeText(
                    context,
                    R.string.camera_permission_needed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    val tier = CameraTier.fromWire(cameraQuality) ?: CameraTier.HIGH
    val lens = CameraLens.fromWire(cameraLens) ?: CameraLens.BACK
    var showQualityDialog by remember { mutableStateOf(false) }
    var showLensDialog by remember { mutableStateOf(false) }

    SettingsScaffold(title = "Camera", onBack = onBack) {
        SettingsRow(
            title = "Allow camera requests",
            summary = when {
                !allowCameraRequests -> "Off. Trusted devices cannot view your camera."
                !cameraPermission -> "On, but camera access is missing; requests will be declined."
                else -> "Trusted devices can ask to view your camera live"
            },
        ) {
            GaurSwitch(allowCameraRequests) { enabled ->
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
                cameraPermission = granted
                when (cameraToggleAction(enabled, granted)) {
                    CameraToggleAction.PERSIST_ON -> {
                        scope.launch {
                            TransferSettingsRepository.setAllowCameraRequests(context, true)
                        }
                    }
                    CameraToggleAction.PERSIST_OFF -> {
                        scope.launch {
                            TransferSettingsRepository.setAllowCameraRequests(context, false)
                        }
                    }
                    CameraToggleAction.REQUEST_PERMISSION -> {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            }
        }

        RevealSetting(visible = allowCameraRequests) {
            SettingsRow(
                title = "Auto-accept from trusted devices",
                summary = "Skip the Accept prompt and start streaming immediately",
            ) {
                GaurSwitch(autoAcceptTrusted) { enabled ->
                    scope.launch {
                        TransferSettingsRepository.setCameraAutoAcceptTrusted(context, enabled)
                    }
                }
            }
            SettingsRow(
                title = "Stream quality",
                summary = tierSummary(tier),
                onClick = { showQualityDialog = true },
                trailing = { Chevron() },
            )
            SettingsRow(
                title = "Preferred lens",
                summary = lensOptions.firstOrNull { it.lens == lens }?.label ?: "",
                onClick = { showLensDialog = true },
                trailing = { Chevron() },
            )
        }

        SettingsFootnote(
            "Only devices you explicitly trusted can ask. Every prompt has an Accept and a " +
                "Decline action, the view is end-to-end encrypted inside the cluster, and the " +
                "streaming notification stays up with a Stop action while anyone is watching.",
        )
        Spacer(Modifier.height(20.dp))
    }

    if (showQualityDialog) {
        OptionDialog(
            title = "Stream quality",
            footnote = "The default for new streams; a live view can change quality " +
                "from the desktop with keys 1–3.",
            onDismiss = { showQualityDialog = false },
        ) {
            tierOptions.forEach { option ->
                OptionRow(
                    label = option.label,
                    detail = option.detail,
                    selected = option.tier == tier,
                    onClick = {
                        scope.launch {
                            TransferSettingsRepository.setCameraQuality(context, option.tier.wire)
                        }
                        showQualityDialog = false
                    },
                )
            }
        }
    }

    if (showLensDialog) {
        OptionDialog(
            title = "Preferred lens",
            footnote = "The default for new streams; a live view flips the lens " +
                "from the desktop with the f key.",
            onDismiss = { showLensDialog = false },
        ) {
            lensOptions.forEach { option ->
                OptionRow(
                    label = option.label,
                    detail = if (option.lens == CameraLens.FRONT) {
                        "Mirrors what the front camera sees"
                    } else {
                        "Mirrors what the back camera sees"
                    },
                    selected = option.lens == lens,
                    onClick = {
                        scope.launch {
                            TransferSettingsRepository.setCameraLens(context, option.lens.wire)
                        }
                        showLensDialog = false
                    },
                )
            }
        }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/** Shared shell for the quality and lens pickers. */
@Composable
private fun OptionDialog(
    title: String,
    footnote: String,
    onDismiss: () -> Unit,
    options: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = BenchType.Section) },
        text = {
            Column {
                options()
                Spacer(Modifier.height(8.dp))
                Text(
                    footnote,
                    style = BenchType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/** One labelled radio row inside an [OptionDialog]. */
@Composable
private fun OptionRow(
    label: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
            ),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                label,
                style = BenchType.Body,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                detail,
                style = BenchType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
