package dev.gaurish.outlinelite.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.data.KeyRepository
import dev.gaurish.outlinelite.data.SplitTunnelRepository
import dev.gaurish.outlinelite.data.ThemeMode
import kotlinx.coroutines.launch

/**
 * Settings: background keep-alive, split tunneling per-app routing, theme selection, and battery exemption.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSplitTunnel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resources = MaterialTheme.colorScheme

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

    var showThemeDialog by remember { mutableStateOf(false) }
    var showDeviceNameDialog by remember { mutableStateOf(false) }
    var draftDeviceName by remember { mutableStateOf("") }
    var showTokenDialog by remember { mutableStateOf(false) }
    var draftToken by remember { mutableStateOf("") }
    var showRelayDialog by remember { mutableStateOf(false) }
    var draftRelay by remember { mutableStateOf("") }

    // Battery-optimization state lives in the OS, so re-check after returning
    // from the system dialog (every ON_RESUME).
    var batteryExempt by remember { mutableStateOf(false) }
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

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 10.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_back),
                        contentDescription = "Back",
                        tint = resources.onBackground,
                    )
                }
                Spacer(Modifier.width(2.dp))
                Text(
                    "Settings",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = resources.onBackground,
                )
            }

            SectionLabel("Appearance")
            SettingsRow(
                title = "Theme",
                summary = when (themeMode) {
                    ThemeMode.SYSTEM -> "System default"
                    ThemeMode.LIGHT -> "Light"
                    ThemeMode.DARK -> "Dark"
                },
                onClick = { showThemeDialog = true },
                trailing = {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = "Select theme",
                        tint = resources.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
            )

            SectionLabel("Routing")
            SettingsRow(
                title = "Split tunneling",
                summary = if (excludedPackages.isEmpty()) {
                    "Route all apps through the VPN"
                } else {
                    "${excludedPackages.size} app${if (excludedPackages.size == 1) "" else "s"} bypassing the VPN"
                },
                onClick = onOpenSplitTunnel,
                trailing = {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = "Open split tunneling",
                        tint = resources.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
            )

            SectionLabel("Background")
            SettingsRow(
                title = "Keep connection alive",
                summary = "Reconnect automatically if the tunnel drops (fast retries first, then every few minutes)",
            ) {
                Switch(
                    checked = autoReconnect,
                    onCheckedChange = { enabled ->
                        scope.launch { KeyRepository.setAutoReconnect(context, enabled) }
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedThumbColor = Color(0xFF0B111B),
                    ),
                )
            }
            SettingsRow(
                title = "Start on boot",
                summary = "Connect automatically after a reboot",
            ) {
                Switch(
                    checked = autostart,
                    onCheckedChange = { enabled ->
                        scope.launch { KeyRepository.setAutostart(context, enabled) }
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedThumbColor = Color(0xFF0B111B),
                    ),
                )
            }

            SettingsRow(
                title = "Ignore battery optimizations",
                summary = if (batteryExempt) {
                    "Unrestricted — the VPN may run freely in the background"
                } else {
                    "Restricted — Android may kill the VPN to save battery"
                },
                onClick = {
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
                },
                trailing = {
                    Button(
                        onClick = {
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
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (batteryExempt) Color(0xFF232F3E)
                            else MaterialTheme.colorScheme.primary,
                            contentColor = if (batteryExempt) MaterialTheme.colorScheme.onBackground
                            else Color(0xFF04231A),
                        ),
                        contentPadding =
                            androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 14.dp, vertical = 6.dp
                            ),
                    ) {
                        Text(
                            if (batteryExempt) "Exempt ✓" else "Allow",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
            )

            Spacer(Modifier.height(20.dp))
            SectionLabel("Cluster File Sharing")

            SettingsRow(
                title = "Device Name",
                summary = deviceName,
                onClick = {
                    draftDeviceName = deviceName
                    showDeviceNameDialog = true
                },
            )

            SettingsRow(
                title = "Cluster Secret Key",
                summary = if (clusterToken.isNotBlank()) "Configured (512-character secure token)" else "Not set — tap to paste 512-character token",
                onClick = {
                    draftToken = clusterToken
                    showTokenDialog = true
                },
            )

            SettingsRow(
                title = "Relay Address",
                summary = relayAddress,
                onClick = {
                    draftRelay = relayAddress
                    showRelayDialog = true
                },
            )

            Spacer(Modifier.height(20.dp))
            SectionLabel("Privacy")
            Text(
                "No analytics, no accounts, no logs. Access keys are stored only on this " +
                    "device. Traffic is protected by the server's own cipher (Shadowsocks AEAD).",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = resources.onSurfaceVariant,
            )
            Spacer(Modifier.height(30.dp))
        }

        // Device Name Dialog
        if (showDeviceNameDialog) {
            AlertDialog(
                onDismissRequest = { showDeviceNameDialog = false },
                title = { Text("Device Name", fontWeight = FontWeight.SemiBold) },
                text = {
                    Column {
                        Text(
                            "Name shown to other cluster devices on your in-tunnel VPN network.",
                            fontSize = 12.sp,
                            color = resources.onSurfaceVariant,
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
                title = { Text("Cluster Secret Key", fontWeight = FontWeight.SemiBold) },
                text = {
                    Column {
                        Text(
                            "Enter the 512-character secret key shared with your server relay and other devices. Key is obscured for security.",
                            fontSize = 12.sp,
                            color = resources.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = draftToken,
                            onValueChange = { draftToken = it },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = false,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Paste 512-char token...", fontSize = 12.sp) },
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
                        onClick = {
                            scope.launch { KeyRepository.setClusterToken(context, draftToken) }
                            showTokenDialog = false
                        },
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showTokenDialog = false }) {
                        Text("Cancel")
                    }
                },
            )
        }

        // Relay Address Dialog
        if (showRelayDialog) {
            AlertDialog(
                onDismissRequest = { showRelayDialog = false },
                title = { Text("Relay Address", fontWeight = FontWeight.SemiBold) },
                text = {
                    Column {
                        Text(
                            "In-tunnel file sharing relay endpoint (host:port). Defaults to 127.0.0.1:9876.",
                            fontSize = 12.sp,
                            color = resources.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = draftRelay,
                            onValueChange = { draftRelay = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("127.0.0.1:9876", fontSize = 12.sp) },
                        )
                    }
                },
                confirmButton = {
                    val isValid = draftRelay.trim().contains(":")
                    Button(
                        enabled = isValid,
                        onClick = {
                            scope.launch { KeyRepository.setRelayAddress(context, draftRelay) }
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
                                        selectedColor = resources.primary
                                    ),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    label,
                                    fontSize = 15.sp,
                                    color = resources.onSurface,
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
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        letterSpacing = 1.4.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp, top = 12.dp),
    )
}

@Composable
private fun SettingsRow(
    title: String,
    summary: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
    ) {
        val clickableMod =
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surface,
                    MaterialTheme.shapes.large,
                )
                .padding(16.dp)
                .then(clickableMod),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    summary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
    }
}
