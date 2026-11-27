package dev.gaurish.outlinelite.ui.settings

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.data.DnsMode
import dev.gaurish.outlinelite.data.KeyRepository
import dev.gaurish.outlinelite.data.SplitTunnelRepository
import dev.gaurish.outlinelite.data.ThemeMode
import dev.gaurish.outlinelite.tunnel.TunnelState
import dev.gaurish.outlinelite.transfer.RelayManager
import dev.gaurish.outlinelite.ui.LanAccessRow
import dev.gaurish.outlinelite.ui.rememberHaptics
import kotlinx.coroutines.launch

/**
 * Settings home. Rows are grouped into sections (appearance, connection,
 * network, cluster, sync); detail-heavy features live behind sub-screens so
 * the top level stays scannable.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSplitTunnel: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenTrustedDevices: () -> Unit,
    onOpenDns: () -> Unit,
    onOpenClipboardSync: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenMedia: () -> Unit,
    onOpenTransfers: () -> Unit,
    onOpenCamera: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    val autoReconnect by KeyRepository.observeAutoReconnect(context)
        .collectAsStateWithLifecycle(initialValue = true)
    val autostart by KeyRepository.observeAutostart(context)
        .collectAsStateWithLifecycle(initialValue = false)
    val themeMode by KeyRepository.observeThemeMode(context)
        .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val excludedPackages by SplitTunnelRepository.observeExcludedPackages(context)
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val deviceName by KeyRepository.observeDeviceName(context)
        .collectAsStateWithLifecycle(initialValue = android.os.Build.MODEL)
    val clusterToken by KeyRepository.observeClusterToken(context)
        .collectAsStateWithLifecycle(initialValue = "")
    val relayAddress by KeyRepository.observeRelayAddress(context)
        .collectAsStateWithLifecycle(initialValue = KeyRepository.DEFAULT_RELAY_ADDR)
    val dnsMode by KeyRepository.observeDnsMode(context)
        .collectAsStateWithLifecycle(initialValue = DnsMode.ADGUARD)
    val dnsServers by KeyRepository.observeDnsServers(context)
        .collectAsStateWithLifecycle(initialValue = KeyRepository.DEFAULT_DNS_SERVERS)
    val blockOnReconnect by KeyRepository.observeBlockOnReconnect(context)
        .collectAsStateWithLifecycle(initialValue = true)
    val clipboardSync by KeyRepository.observeClipboardSync(context)
        .collectAsStateWithLifecycle(initialValue = false)
    val notificationSync by KeyRepository.observeNotificationSync(context)
        .collectAsStateWithLifecycle(initialValue = false)
    val mediaSync by KeyRepository.observeMediaSync(context)
        .collectAsStateWithLifecycle(initialValue = false)

    var showThemeDialog by remember { mutableStateOf(false) }
    var showDeviceNameDialog by remember { mutableStateOf(false) }
    var draftDeviceName by remember { mutableStateOf("") }
    var showTokenDialog by remember { mutableStateOf(false) }
    var showClearTokenConfirm by remember { mutableStateOf(false) }
    var draftToken by remember { mutableStateOf("") }
    var showRelayDialog by remember { mutableStateOf(false) }
    var draftRelay by remember { mutableStateOf("") }

    // Battery-optimization state lives in the OS, so re-check after returning
    // from the system dialog (every ON_RESUME).
    var batteryExempt by remember { mutableStateOf(false) }
    val tunnelStatus by TunnelState.flow.collectAsStateWithLifecycle()
    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryExempt =
                    powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsScaffold(title = "Settings", onBack = onBack, showBack = false) {
        SectionLabel("Appearance")
        SettingsRow(
            title = "Theme",
            summary = when (themeMode) {
                ThemeMode.SYSTEM -> "System default"
                ThemeMode.LIGHT -> "Light"
                ThemeMode.DARK -> "Dark"
            },
            onClick = { showThemeDialog = true },
            trailing = { Chevron() },
        )

        SectionLabel("Connection")
        SettingsRow(
            title = "Keep connection alive",
            summary = "Reconnect automatically when the connection drops.",
        ) {
            GaurSwitch(autoReconnect) { enabled ->
                scope.launch { KeyRepository.setAutoReconnect(context, enabled) }
            }
        }
        SettingsRow(
            title = "Start on boot",
            summary = "Connect automatically after a reboot",
        ) {
            GaurSwitch(autostart) { enabled ->
                scope.launch { KeyRepository.setAutostart(context, enabled) }
            }
        }
        SettingsRow(
            title = "Block traffic while reconnecting",
            summary = "Block traffic when the VPN drops instead of sending it unprotected.",
        ) {
            GaurSwitch(blockOnReconnect) { enabled ->
                scope.launch { KeyRepository.setBlockOnReconnect(context, enabled) }
            }
        }
        SettingsRow(
            title = "Ignore battery optimizations",
            summary = if (batteryExempt) {
                "Unrestricted. The VPN can run in the background."
            } else {
                "Restricted. Android may stop the VPN to save battery."
            },
            onClick = { openBatterySettings(context, batteryExempt) },
            trailing = {
                Button(
                    onClick = { openBatterySettings(context, batteryExempt) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (batteryExempt) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.primary,
                        contentColor = if (batteryExempt) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.onPrimary,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 14.dp, vertical = 6.dp
                    ),
                ) {
                    Text(
                        if (batteryExempt) "Allowed" else "Allow",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
        )

        LanAccessRow(status = tunnelStatus)

        SectionLabel("Network & DNS")
        SettingsRow(
            title = "DNS resolver",
            summary = when (dnsMode) {
                DnsMode.ADGUARD ->
                    "AdGuard Home through the tunnel, ${dnsServers.ifBlank { KeyRepository.DEFAULT_DNS_SERVERS }}"
                DnsMode.SYSTEM -> "System resolvers through the tunnel"
                DnsMode.CUSTOM -> dnsServers.ifBlank { "No custom servers set" }
            },
            onClick = onOpenDns,
            trailing = { Chevron() },
        )
        SettingsRow(
            title = "Split tunneling",
            summary = if (excludedPackages.isEmpty()) {
                "Route all apps through the VPN"
            } else {
                "${excludedPackages.size} app${if (excludedPackages.size == 1) "" else "s"} bypassing the VPN"
            },
            onClick = onOpenSplitTunnel,
            trailing = { Chevron() },
        )

        SectionLabel("Devices")
        SettingsRow(
            title = "Device name",
            summary = deviceName,
            onClick = {
                draftDeviceName = deviceName
                showDeviceNameDialog = true
            },
        )
        SettingsRow(
            title = "Sharing key",
            summary = if (clusterToken.isNotBlank()) "Configured" else "Not set. Tap to paste the cluster token.",
            onClick = {
                draftToken = clusterToken
                showTokenDialog = true
            },
        )
        SettingsRow(
            title = "Relay server",
            summary = relayAddress,
            onClick = {
                draftRelay = relayAddress
                showRelayDialog = true
            },
        )
        SettingsRow(
            title = "Trusted devices",
            summary = "Decide which devices can send, browse, or open things here",
            onClick = onOpenTrustedDevices,
            trailing = { Chevron() },
        )
        SettingsRow(
            title = "Transfers",
            summary = "Auto-open received files and review transfer history",
            onClick = onOpenTransfers,
            trailing = { Chevron() },
        )
        SettingsRow(
            title = "Camera",
            summary = "Let trusted devices view this phone's camera as a live stream",
            onClick = onOpenCamera,
            trailing = { Chevron() },
        )

        SectionLabel("Sync")
        SettingsRow(
            title = "Clipboard sync",
            summary = if (clipboardSync) {
                "On. Text and images sync across your devices."
            } else {
                "Off. Copy on one device, paste on another."
            },
            onClick = onOpenClipboardSync,
            trailing = { Chevron() },
        )
        SettingsRow(
            title = "Notifications",
            summary = if (notificationSync) {
                "On. Notifications appear on every device."
            } else {
                "Off. Notifications are not shared."
            },
            onClick = onOpenNotifications,
            trailing = { Chevron() },
        )
        SettingsRow(
            title = "Media controls",
            summary = if (mediaSync) {
                "On. Playback controls are shared."
            } else {
                "Off. Other devices cannot control playback."
            },
            onClick = onOpenMedia,
            trailing = { Chevron() },
        )

        Spacer(Modifier.height(20.dp))
        SectionLabel("Privacy")
        Text(
            "No analytics, no accounts, no logs. Access keys are stored only on this " +
                "device. Traffic is protected by the server's own cipher (Shadowsocks AEAD).",
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(30.dp))
    }

    // Device Name Dialog
    if (showDeviceNameDialog) {
        AlertDialog(
            onDismissRequest = { showDeviceNameDialog = false },
            title = { Text("Device name", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        "Name shown to other cluster devices on your in-tunnel VPN network.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = draftDeviceName,
                        onValueChange = { draftDeviceName = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = draftDeviceName.isNotBlank(),
                    onClick = {
                        scope.launch { KeyRepository.setDeviceName(context, draftDeviceName) }
                        showDeviceNameDialog = false
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeviceNameDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Cluster Token Dialog
    if (showTokenDialog) {
        AlertDialog(
            onDismissRequest = { showTokenDialog = false },
            title = { Text("Sharing key", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        "Shared by the relay and every device in your cluster. Anyone " +
                            "with this key can join the cluster and see your devices. " +
                            "Treat it like a password.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = draftToken,
                        onValueChange = { draftToken = it },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = false,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Paste cluster token...", fontSize = 12.sp) },
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = cm?.primaryClip?.getItemAt(0)?.text?.toString()
                            if (!clip.isNullOrBlank()) {
                                draftToken = clip.trim()
                            }
                        },
                    ) {
                        Text("Paste from Clipboard", fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = draftToken.isNotBlank(),
                    onClick = {
                        haptics.confirm()
                        scope.launch {
                            KeyRepository.setClusterToken(context, draftToken)
                            RelayManager.onSettingsChanged(context)
                        }
                        showTokenDialog = false
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                Row {
                    if (clusterToken.isNotBlank()) {
                        TextButton(
                            onClick = {
                                showTokenDialog = false
                                showClearTokenConfirm = true
                            },
                        ) {
                            Text(
                                "Remove key",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    TextButton(onClick = { showTokenDialog = false }) {
                        Text("Cancel")
                    }
                }
            },
        )
    }

    if (showClearTokenConfirm) {
        AlertDialog(
            onDismissRequest = { showClearTokenConfirm = false },
            title = { Text("Remove sharing key?", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "File sharing and device sync stop on this device until you add " +
                        "the key again. Other devices keep their copy.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.reject()
                        showClearTokenConfirm = false
                        scope.launch {
                            KeyRepository.setClusterToken(context, "")
                            RelayManager.onSettingsChanged(context)
                        }
                    },
                ) {
                    Text("Remove key", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearTokenConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Relay Address Dialog
    if (showRelayDialog) {
        AlertDialog(
            onDismissRequest = { showRelayDialog = false },
            title = { Text("Relay server", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        "In-tunnel file sharing relay endpoint (host:port). Defaults to the loopback alias 198.51.100.1:9876.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = draftRelay,
                        onValueChange = { draftRelay = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("198.51.100.1:9876", fontSize = 12.sp) },
                    )
                }
            },
            confirmButton = {
                val isValid = draftRelay.trim().contains(":")
                Button(
                    enabled = isValid,
                    onClick = {
                        scope.launch {
                            KeyRepository.setRelayAddress(context, draftRelay)
                            RelayManager.onSettingsChanged(context)
                        }
                        showRelayDialog = false
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRelayDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Theme Selection Dialog
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Choose theme", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        ThemeMode.SYSTEM to "System default",
                        ThemeMode.LIGHT to "Light",
                        ThemeMode.DARK to "Dark",
                    ).forEach { (mode, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    scope.launch { KeyRepository.setThemeMode(context, mode) }
                                    showThemeDialog = false
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            RadioButton(
                                selected = themeMode == mode,
                                onClick = {
                                    scope.launch { KeyRepository.setThemeMode(context, mode) }
                                    showThemeDialog = false
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.primary
                                ),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                label,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun openBatterySettings(context: Context, batteryExempt: Boolean) {
    val intent = if (batteryExempt) {
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    } else {
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
    }
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(
            context, R.string.settings_battery_failed, Toast.LENGTH_SHORT
        ).show()
    }
}
