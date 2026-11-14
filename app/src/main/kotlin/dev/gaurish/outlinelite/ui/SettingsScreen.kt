package dev.gaurish.outlinelite.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.data.DnsMode
import dev.gaurish.outlinelite.data.KeyRepository
import dev.gaurish.outlinelite.data.SplitTunnelRepository
import dev.gaurish.outlinelite.data.ThemeMode
import dev.gaurish.outlinelite.data.TransferSettingsRepository
import dev.gaurish.outlinelite.transfer.ClipboardSyncManager
import dev.gaurish.outlinelite.transfer.ClipboardSyncPhase
import dev.gaurish.outlinelite.transfer.MIRROR_CHANNEL_ID
import dev.gaurish.outlinelite.transfer.MediaSessionBridge
import dev.gaurish.outlinelite.transfer.NotificationMirrorManager
import dev.gaurish.outlinelite.transfer.RelayManager
import kotlinx.coroutines.launch

/**
 * Settings: background keep-alive, split tunneling per-app routing, theme selection, and battery exemption.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSplitTunnel: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenTrustedDevices: () -> Unit,
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
    val dnsMode by KeyRepository.observeDnsMode(context)
        .collectAsStateWithLifecycle(initialValue = DnsMode.ADGUARD)
    val dnsServers by KeyRepository.observeDnsServers(context)
        .collectAsStateWithLifecycle(initialValue = KeyRepository.DEFAULT_DNS_SERVERS)
    val blockOnReconnect by KeyRepository.observeBlockOnReconnect(context)
        .collectAsStateWithLifecycle(initialValue = true)
    val clipboardSync by KeyRepository.observeClipboardSync(context)
        .collectAsStateWithLifecycle(initialValue = false)
    val clipboardSensitive by KeyRepository.observeClipboardSensitive(context)
        .collectAsStateWithLifecycle(initialValue = false)
    val clipboardState by ClipboardSyncManager.state.collectAsStateWithLifecycle()
    val peers by RelayManager.peers.collectAsStateWithLifecycle()
    val clipboardExcluded by KeyRepository.observeClipboardExcludedPeers(context)
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val notificationSync by KeyRepository.observeNotificationSync(context)
        .collectAsStateWithLifecycle(initialValue = false)
    val mediaSync by KeyRepository.observeMediaSync(context)
        .collectAsStateWithLifecycle(initialValue = false)
    val mediaState by MediaSessionBridge.state.collectAsStateWithLifecycle()
    var notificationAccess by remember {
        mutableStateOf(
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        )
    }
    val autoOpenReceived by TransferSettingsRepository.observeAutoOpen(context)
        .collectAsStateWithLifecycle(initialValue = true)
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    var showThemeDialog by remember { mutableStateOf(false) }
    var showDeviceNameDialog by remember { mutableStateOf(false) }
    var draftDeviceName by remember { mutableStateOf("") }
    var showTokenDialog by remember { mutableStateOf(false) }
    var draftToken by remember { mutableStateOf("") }
    var showRelayDialog by remember { mutableStateOf(false) }
    var draftRelay by remember { mutableStateOf("") }
    var showDnsDialog by remember { mutableStateOf(false) }
    var draftDnsMode by remember { mutableStateOf(DnsMode.ADGUARD) }
    var draftDnsServers by remember { mutableStateOf("") }

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
                overlayGranted = Settings.canDrawOverlays(context)
                notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(context)
                    .contains(context.packageName)
                ClipboardSyncManager.refresh(context)
                NotificationMirrorManager.refresh(context)
                MediaSessionBridge.refresh(context)
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

            SectionLabel("DNS")
            SettingsRow(
                title = "DNS resolver",
                summary = when (dnsMode) {
                    DnsMode.ADGUARD ->
                        "AdGuard Home through the tunnel (${dnsServers.ifBlank { KeyRepository.DEFAULT_DNS_SERVERS }})"
                    DnsMode.SYSTEM -> "System resolvers through the tunnel"
                    DnsMode.CUSTOM -> dnsServers.ifBlank { "No custom servers set" }
                },
                onClick = {
                    draftDnsMode = dnsMode
                    draftDnsServers = dnsServers
                    showDnsDialog = true
                },
                trailing = {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = "Configure DNS",
                        tint = resources.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
            )

            SettingsRow(
                title = "Block traffic while reconnecting",
                summary = "Drop packets instead of leaking direct when the tunnel drops",
            ) {
                Switch(
                    checked = blockOnReconnect,
                    onCheckedChange = { enabled ->
                        scope.launch { KeyRepository.setBlockOnReconnect(context, enabled) }
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedThumbColor = Color(0xFF0B111B),
                    ),
                )
            }

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
                summary = if (clusterToken.isNotBlank()) "Configured" else "Not set — tap to paste the cluster token",
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

            SettingsRow(
                title = "Trusted devices",
                summary = "Choose which cluster devices can send files without approval",
                onClick = onOpenTrustedDevices,
                trailing = {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = "Open trusted devices",
                        tint = resources.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
            )

            SettingsRow(
                title = "Open received files automatically",
                summary = "Launch the viewer as soon as a file shared with \"Open in...\" finishes arriving",
            ) {
                Switch(
                    checked = autoOpenReceived,
                    onCheckedChange = { enabled ->
                        scope.launch { TransferSettingsRepository.setAutoOpen(context, enabled) }
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedThumbColor = Color(0xFF0B111B),
                    ),
                )
            }

            SettingsRow(
                title = "Transfer history",
                summary = "Reopen received files, retry failed sends, and resend links",
                onClick = onOpenHistory,
                trailing = {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = "Open transfer history",
                        tint = resources.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
            )

            Spacer(Modifier.height(20.dp))
            SectionLabel("Clipboard Sync")

            SettingsRow(
                title = "Sync clipboard",
                summary = clipboardSummary,
            ) {
                Switch(
                    checked = clipboardSync,
                    onCheckedChange = { enabled ->
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
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedThumbColor = Color(0xFF0B111B),
                    ),
                )
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
                    Switch(
                        checked = clipboardSensitive,
                        onCheckedChange = { enabled ->
                            scope.launch { KeyRepository.setClipboardSensitive(context, enabled) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedThumbColor = Color(0xFF0B111B),
                        ),
                    )
                }

                val otherPeers = peers.filter { !it.isSelf }
                if (otherPeers.isNotEmpty()) {
                    Text(
                        "Per-device clipboard",
                        fontSize = 11.sp,
                        letterSpacing = 1.4.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = resources.onSurfaceVariant,
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
                            Switch(
                                checked = peer.id !in clipboardExcluded,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        KeyRepository.setClipboardExcluded(
                                            context,
                                            peer.id,
                                            !enabled,
                                        )
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedThumbColor = Color(0xFF0B111B),
                                ),
                            )
                        }
                    }
                }

                SettingsRow(
                    title = "Last clipboard activity",
                    summary = clipboardState.lastMessage ?: "No clipboard synced yet",
                )

                Text(
                    "Android 10+ blocks background clipboard reads. gaurPN briefly takes focus to check for new clips; Android may show its paste toast.",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("Notifications")
            SettingsRow(
                title = "Share notifications",
                summary = when {
                    !notificationSync -> "Off — mirror notifications to other cluster devices"
                    !notificationAccess -> "Grant notification access to start mirroring"
                    else -> "On — notifications from this device appear on every cluster device"
                },
            ) {
                Switch(
                    checked = notificationSync,
                    onCheckedChange = { enabled ->
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
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedThumbColor = Color(0xFF0B111B),
                    ),
                )
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

            Text(
                "Mirrored notifications are end-to-end encrypted. gaurPN ignores its own " +
                    "notifications, so mirrored copies are never re-broadcast.",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )

            Spacer(Modifier.height(20.dp))
            SectionLabel("Media")
            SettingsRow(
                title = "Share media controls",
                summary = when {
                    !mediaSync -> "Off — control this device's media from other cluster devices"
                    !notificationAccess -> "Grant notification access to read media sessions"
                    else -> "On — now-playing and transport controls are shared"
                },
            ) {
                Switch(
                    checked = mediaSync,
                    onCheckedChange = { enabled ->
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
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedThumbColor = Color(0xFF0B111B),
                    ),
                )
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
            Text(
                "Media metadata and commands are sealed end-to-end. Position is sent on " +
                    "state changes only; the desktop interpolates it locally.",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
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
                            "Enter the cluster secret shared with your server relay and other devices. The key is obscured for security.",
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
                            "In-tunnel file sharing relay endpoint (host:port). Defaults to the loopback alias 198.51.100.1:9876.",
                            fontSize = 12.sp,
                            color = resources.onSurfaceVariant,
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

        // DNS Selection Dialog
        if (showDnsDialog) {
            AlertDialog(
                onDismissRequest = { showDnsDialog = false },
                title = { Text("DNS resolver", fontWeight = FontWeight.SemiBold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            DnsMode.ADGUARD to "AdGuard Home (through tunnel)",
                            DnsMode.SYSTEM to "System resolvers (through tunnel)",
                            DnsMode.CUSTOM to "Custom servers",
                        ).forEach { (mode, label) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { draftDnsMode = mode }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                RadioButton(
                                    selected = draftDnsMode == mode,
                                    onClick = { draftDnsMode = mode },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = resources.primary
                                    ),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(label, fontSize = 14.sp, color = resources.onSurface)
                            }
                        }
                        if (draftDnsMode != DnsMode.SYSTEM) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = draftDnsServers,
                                onValueChange = { draftDnsServers = it },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                label = {
                                    Text(
                                        if (draftDnsMode == DnsMode.ADGUARD) {
                                            "AdGuard address"
                                        } else {
                                            "Servers (comma-separated)"
                                        },
                                        fontSize = 12.sp,
                                    )
                                },
                                placeholder = { Text("10.0.0.182", fontSize = 12.sp) },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "AdGuard queries travel inside the Shadowsocks tunnel and are " +
                                "answered on the VPS. They never reach the local network or a " +
                                "public resolver.",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = resources.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    Button(
                        enabled = draftDnsMode == DnsMode.SYSTEM ||
                            draftDnsServers.isNotBlank(),
                        onClick = {
                            scope.launch {
                                KeyRepository.setDnsMode(context, draftDnsMode)
                                if (draftDnsMode != DnsMode.SYSTEM) {
                                    KeyRepository.setDnsServers(context, draftDnsServers)
                                }
                            }
                            showDnsDialog = false
                        },
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDnsDialog = false }) {
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
internal fun SectionLabel(text: String) {
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
