package dev.gaurish.outlinelite.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.data.KeyRepository
import dev.gaurish.outlinelite.data.TrustedDeviceRepository
import dev.gaurish.outlinelite.transfer.ClusterPeer
import dev.gaurish.outlinelite.transfer.RelayManager
import dev.gaurish.outlinelite.transfer.TransferState
import dev.gaurish.outlinelite.tunnel.TunnelPhase
import dev.gaurish.outlinelite.tunnel.TunnelService
import dev.gaurish.outlinelite.tunnel.TunnelStatus
import dev.gaurish.outlinelite.tunnel.TunnelState
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch

@Composable
fun LanAccessRow(
    status: TunnelStatus,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var showWarningDialog by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surface)
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Allow LAN access",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
            )
            Text(
                "Reach printers and smart devices on your Wi-Fi without the VPN.",
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = status.allowLanAccess,
            onCheckedChange = { willEnable ->
                if (willEnable) {
                    showWarningDialog = true
                } else {
                    TunnelService.toggleLanAccess(context)
                }
            },
            colors = SwitchDefaults.colors(
                checkedTrackColor = scheme.primary,
                checkedThumbColor = scheme.onPrimary,
            ),
        )
    }

    if (showWarningDialog) {
        AlertDialog(
            onDismissRequest = { showWarningDialog = false },
            title = { Text("Turn on LAN access?") },
            text = {
                Text(
                    "Local devices on this Wi-Fi can reach your phone without the VPN. This setting turns off when the VPN disconnects."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showWarningDialog = false
                        TunnelService.toggleLanAccess(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.error),
                ) {
                    Text("Turn on LAN access", color = scheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showWarningDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
fun ClusterSharingSection(
    onOpenSettings: () -> Unit,
    onBrowsePeer: (ClusterPeer) -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    showHeader: Boolean = true,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val peers by RelayManager.peers.collectAsStateWithLifecycle()
    val transferState by RelayManager.transferState.collectAsStateWithLifecycle()
    val token by KeyRepository.observeClusterToken(context).collectAsStateWithLifecycle(initialValue = "")

    var targetPeerForPicker by remember { mutableStateOf<ClusterPeer?>(null) }
    var browsePeer by remember { mutableStateOf<ClusterPeer?>(null) }
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        val peer = targetPeerForPicker
        if (uris.isNotEmpty() && peer != null) {
            RelayManager.sendFiles(context, peer, uris)
        }
        targetPeerForPicker = null
    }

    Column(modifier = modifier.fillMaxWidth()) {
    if (transferState !is TransferState.Idle) {
        TransferDialog(
            state = transferState,
            onDismiss = { RelayManager.dismissTransfer() },
        )
    }

        val containerModifier = if (embedded) {
            Modifier.fillMaxWidth()
        } else {
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(scheme.surface)
                .border(1.dp, scheme.outlineVariant, MaterialTheme.shapes.medium)
                .padding(16.dp)
        }
        Box(modifier = containerModifier) {
            if (token.isBlank()) {
                Column {
                    if (showHeader) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(scheme.outline),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Devices",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.onSurface,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(
                        "Add your sharing key in Settings to send files between your devices. Files stay encrypted inside the VPN tunnel.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.heightIn(min = 40.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                    ) {
                        Text("Open settings", fontSize = 13.sp)
                    }
                }
            } else {
                val otherPeers = remember(peers) { peers.filter { !it.isSelf } }
                Column {
                    if (showHeader) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (otherPeers.isEmpty()) scheme.outline else scheme.primary
                                        ),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Devices",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = scheme.onSurface,
                                )
                            }
                            Text(
                                "${otherPeers.size} online",
                                fontSize = 12.sp,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    if (otherPeers.isEmpty()) {
                        Text(
                            "No other devices are online. Connect the VPN on the device you want to reach.",
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    } else {
                        otherPeers.forEachIndexed { index, peer ->
                            PeerRow(
                                peer = peer,
                                onSendClick = {
                                    targetPeerForPicker = peer
                                    filePickerLauncher.launch("*/*")
                                },
                                onBrowseClick = { browsePeer = peer },
                            )
                            if (index != otherPeers.lastIndex) {
                                HorizontalDivider(
                                    color = scheme.outlineVariant,
                                    thickness = 1.dp,
                                )
                            }
                        }
                    }
                }
            }
        }

        browsePeer?.let { peer ->
            AlertDialog(
                onDismissRequest = { browsePeer = null },
                title = { Text("Browse files") },
                text = {
                    Text(
                        "Open ${peer.name}'s shared folders and download what you pick " +
                            "over the encrypted tunnel.",
                        fontSize = 13.sp,
                        color = scheme.onSurfaceVariant,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            browsePeer = null
                            onBrowsePeer(peer)
                        },
                    ) {
                        Text("Browse ${peer.name}'s files")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { browsePeer = null }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@Composable
fun ShareTargetDialog(
    uris: List<Uri>,
    text: String? = null,
    openOnArrival: Boolean = false,
    onConnectToVpn: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val peers by RelayManager.peers.collectAsStateWithLifecycle()
    val token by KeyRepository.observeClusterToken(context).collectAsStateWithLifecycle(initialValue = "")
    val trustedDevices by TrustedDeviceRepository.observe(context)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val tunnelStatus by TunnelState.flow.collectAsStateWithLifecycle()
    val otherPeers = remember(peers) { peers.filter { !it.isSelf } }
    val offlineTrusted = remember(trustedDevices, otherPeers) {
        trustedDevices.filter { device -> otherPeers.none { it.id == device.id } }
    }
    val isVpnConnected = tunnelStatus.phase == TunnelPhase.CONNECTED
    val hasAvailablePeers = isVpnConnected && otherPeers.isNotEmpty()
    val emptyStateMessage = when {
        !isVpnConnected && tunnelStatus.phase == TunnelPhase.CONNECTING -> R.string.share_waiting_for_vpn
        !isVpnConnected && tunnelStatus.phase == TunnelPhase.STOPPING -> R.string.share_vpn_stopping
        !isVpnConnected -> R.string.share_connect_vpn
        token.isBlank() -> R.string.share_configure_cluster
        else -> R.string.share_no_devices
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_choose_device)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (text != null) {
                    Text(
                        if (text.startsWith("http://") || text.startsWith("https://")) {
                            "Send this link to a device:"
                        } else {
                            "Send this text to a device:"
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                if (!hasAvailablePeers) {
                    Text(
                        stringResource(emptyStateMessage),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    if (text == null) {
                        Text(
                            stringResource(R.string.share_select_destination),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    val textKind = if (text?.startsWith("http://") == true ||
                        text?.startsWith("https://") == true
                    ) {
                        "url"
                    } else {
                        "text"
                    }
                    otherPeers.forEach { peer ->
                        PeerRow(
                            peer = peer,
                            actionLabel = if (text != null) "Send" else "Send",
                            onSendClick = {
                                if (text != null) {
                                    scope.launch {
                                        RelayManager.sendSealedText(context, peer.id, text, textKind)
                                    }
                                } else {
                                    RelayManager.sendFilesV3(
                                        context,
                                        listOf(peer),
                                        uris,
                                        offline = false,
                                    )
                                }
                                onDismiss()
                            },
                        )
                    }
                    if (otherPeers.size > 1) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = {
                                if (text != null) {
                                    otherPeers.forEach { peer ->
                                        scope.launch {
                                            RelayManager.sendSealedText(context, peer.id, text, textKind)
                                        }
                                    }
                                } else {
                                    // One upload, many recipients: the v3 path
                                    // stages the ciphertext once and sends a
                                    // sealed offer to each device.
                                    RelayManager.sendFilesV3(context, otherPeers, uris, offline = false)
                                }
                                onDismiss()
                            },
                        ) {
                            Text("Send to all ${otherPeers.size} devices", fontSize = 12.sp)
                        }
                    }
                }

                if (isVpnConnected && offlineTrusted.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Offline. Queued until it reconnects.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    offlineTrusted.forEach { device ->
                        PeerRow(
                            peer = ClusterPeer(device.id, device.name),
                            actionLabel = "Send when online",
                            onSendClick = {
                                if (text != null) {
                                    val kind = if (text.startsWith("http://") ||
                                        text.startsWith("https://")
                                    ) {
                                        "url"
                                    } else {
                                        "text"
                                    }
                                    scope.launch {
                                        RelayManager.sendSealedText(context, device.id, text, kind)
                                    }
                                } else {
                                    RelayManager.sendFilesV3(
                                        context,
                                        listOf(ClusterPeer(device.id, device.name)),
                                        uris,
                                        offline = true,
                                    )
                                }
                                onDismiss()
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                tunnelStatus.phase == TunnelPhase.DISCONNECTED -> {
                    Button(onClick = onConnectToVpn) {
                        Text(stringResource(R.string.share_connect_vpn_action))
                    }
                }
                tunnelStatus.phase == TunnelPhase.CONNECTING -> {
                    Button(onClick = {}, enabled = false) {
                        Text(stringResource(R.string.share_connecting))
                    }
                }
                tunnelStatus.phase == TunnelPhase.STOPPING -> {
                    Button(onClick = {}, enabled = false) {
                        Text(stringResource(R.string.share_disconnecting))
                    }
                }
                isVpnConnected && token.isNotBlank() && otherPeers.isEmpty() -> {
                    Button(onClick = { RelayManager.refreshDevices(context) }) {
                        Text(stringResource(R.string.share_refresh_devices))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.share_cancel))
            }
        },
    )
}

@Composable
private fun PeerRow(
    peer: ClusterPeer,
    actionLabel: String = "Send",
    onSendClick: () -> Unit,
    onBrowseClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(
                if (onBrowseClick != null) {
                    Modifier.clickable(
                        onClickLabel = "Browse files",
                        onClick = onBrowseClick,
                    )
                } else {
                    Modifier
                }
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                peer.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val battery = peer.battery
            val meta = buildString {
                if (battery != null) {
                    append(battery)
                    append('%')
                    if (peer.charging == true) append(" charging")
                } else {
                    append("Online")
                }
            }
            Text(
                meta,
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant,
            )
        }

        OutlinedButton(
            onClick = onSendClick,
            modifier = Modifier.heightIn(min = 40.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
            border = BorderStroke(1.dp, scheme.outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.primary),
        ) {
            Text(actionLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TransferDialog(
    state: TransferState,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = {
            if (state is TransferState.Completed || state is TransferState.Error) {
                onDismiss()
            }
        },
        title = {
            val titleText = when (state) {
                is TransferState.Idle -> ""
                is TransferState.Sending -> "Sending file"
                is TransferState.Receiving -> "Receiving file"
                is TransferState.Completed -> "Transfer complete"
                is TransferState.Error -> "Transfer failed"
            }
            Text(
                titleText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = when (state) {
                    is TransferState.Completed -> scheme.primary
                    is TransferState.Error -> scheme.error
                    else -> scheme.onSurface
                },
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when (state) {
                    is TransferState.Idle -> Unit
                    is TransferState.Sending -> TransferFileProgress(
                        fileName = state.fileName,
                        progress = state.progress,
                        speedMbPerSec = state.speedMbPerSec,
                    )
                    is TransferState.Receiving -> TransferFileProgress(
                        fileName = state.fileName,
                        progress = state.progress,
                        speedMbPerSec = state.speedMbPerSec,
                    )
                    is TransferState.Completed -> {
                        Text(
                            state.message,
                            fontSize = 14.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    is TransferState.Error -> {
                        Text(
                            state.message,
                            fontSize = 14.sp,
                            color = scheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                is TransferState.Completed -> {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = scheme.primary),
                    ) {
                        Text("Done", color = scheme.onPrimary)
                    }
                }
                is TransferState.Error -> {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = scheme.error),
                    ) {
                        Text("Close", color = scheme.onError)
                    }
                }
                else -> {
                    TextButton(
                        onClick = {
                            RelayManager.cancelActiveTransfers()
                            onDismiss()
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            }
        },
    )
}

/**
 * File name, percent, speed, and progress bar shared by the Sending and
 * Receiving branches of [TransferDialog]; both render identically.
 */
@Composable
private fun TransferFileProgress(
    fileName: String,
    progress: Float,
    speedMbPerSec: Float,
) {
    val scheme = MaterialTheme.colorScheme
    Text(
        fileName,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = scheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "${(progress * 100).toInt()}%",
            fontSize = 12.sp,
            color = scheme.onSurfaceVariant,
        )
        Text(
            "%.1f MB/s".format(speedMbPerSec),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = scheme.primary,
        )
    }
    Spacer(Modifier.height(10.dp))
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
        color = scheme.primary,
        trackColor = scheme.outlineVariant,
    )
}
