package dev.gaurish.outlinelite.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.data.KeyRepository
import dev.gaurish.outlinelite.data.TrustedDeviceRepository
import dev.gaurish.outlinelite.transfer.CameraSessionPhase
import dev.gaurish.outlinelite.transfer.CameraSessionState
import dev.gaurish.outlinelite.transfer.ClusterPeer
import dev.gaurish.outlinelite.transfer.RelayLinkPhase
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
    val connected = status.phase == TunnelPhase.CONNECTED
    val lanOn = status.allowLanAccess
    val borderColor by animateColorAsState(
        targetValue = if (lanOn) {
            scheme.primary.copy(alpha = 0.6f)
        } else {
            scheme.outlineVariant.copy(alpha = 0.5f)
        },
        animationSpec = Motion.base(),
        label = "lan_border",
    )
    val fillColor by animateColorAsState(
        targetValue = if (lanOn) scheme.primary.copy(alpha = 0.06f) else scheme.surface,
        animationSpec = Motion.base(),
        label = "lan_fill",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(fillColor)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
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
                if (connected) {
                    "Printers and smart devices on your Wi-Fi stay off the tunnel."
                } else {
                    "Available while the VPN is on."
                },
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = scheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = lanOn,
            onCheckedChange = { willEnable ->
                if (willEnable) {
                    showWarningDialog = true
                } else {
                    TunnelService.toggleLanAccess(context)
                }
            },
            enabled = connected,
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
    onOpenCameraPreview: () -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    showHeader: Boolean = true,
    relayLink: RelayLinkPhase = RelayLinkPhase.ONLINE,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val peers by RelayManager.peers.collectAsStateWithLifecycle()
    val token by KeyRepository.observeClusterToken(context).collectAsStateWithLifecycle(initialValue = "")
    val cameraSession by CameraSessionState.session.collectAsStateWithLifecycle()
    val motionEnabled = rememberMotionEnabled()

    var targetPeerForPicker by remember { mutableStateOf<ClusterPeer?>(null) }
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
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.heightIn(min = 44.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    ) {
                        Text("Open settings", fontSize = 14.sp)
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
                                            when {
                                                relayLink == RelayLinkPhase.AUTH_FAILED ||
                                                    relayLink == RelayLinkPhase.UNREACHABLE ->
                                                    scheme.error
                                                otherPeers.isEmpty() -> scheme.outline
                                                else -> scheme.primary
                                            }
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
                                when {
                                    relayLink == RelayLinkPhase.AUTH_FAILED -> "Key rejected"
                                    relayLink == RelayLinkPhase.UNREACHABLE -> "Relay unreachable"
                                    relayLink == RelayLinkPhase.CONNECTING -> "Connecting"
                                    else -> "${otherPeers.size} online"
                                },
                                fontSize = 13.sp,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    // The camera row only exists while there is something to
                    // say: a live stream, or a request waiting for an answer.
                    // It is the one row that names someone watching, and it
                    // opens the preview screen where the controls live.
                    val cameraStreaming = cameraSession.phase == CameraSessionPhase.STREAMING
                    if (cameraStreaming || cameraSession.phase == CameraSessionPhase.REQUEST_PENDING) {
                        val requester = cameraSession.requesterName.ifBlank { "a device" }
                        Reveal(order = 0, enabled = motionEnabled) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .benchSurface(
                                        shape = MaterialTheme.shapes.medium,
                                        color = scheme.surfaceContainerHigh,
                                        onClickLabel = if (cameraStreaming) {
                                            "Open the camera preview"
                                        } else {
                                            "Review the camera request"
                                        },
                                        role = Role.Button,
                                    ) { onOpenCameraPreview() }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_eye),
                                    contentDescription = null,
                                    tint = scheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    if (cameraStreaming) {
                                        "Camera streaming to $requester"
                                    } else {
                                        "$requester wants to view your camera"
                                    },
                                    style = BenchType.Body,
                                    color = scheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    painter = painterResource(R.drawable.ic_chevron_right),
                                    contentDescription = null,
                                    tint = scheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    if (otherPeers.isEmpty()) {
                        Text(
                            when (relayLink) {
                                RelayLinkPhase.AUTH_FAILED ->
                                    "The relay rejected this cluster key. Check that every " +
                                        "device uses the same key."
                                RelayLinkPhase.UNREACHABLE ->
                                    "Can't reach the relay. Check the relay address and " +
                                        "the VPN connection."
                                RelayLinkPhase.CONNECTING ->
                                    "Connecting to the relay\u2026"
                                else ->
                                    "No other devices are online. Both devices need the VPN " +
                                        "on and the same cluster key."
                            },
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    } else {
                        Column(Modifier.animateContentSize(Motion.spatialFast())) {
                            otherPeers.forEachIndexed { index, peer ->
                                key(peer.id) {
                                    Reveal(
                                        order = index.coerceAtMost(4),
                                        enabled = motionEnabled,
                                    ) {
                                        PeerRow(
                                            peer = peer,
                                            onSendClick = {
                                                targetPeerForPicker = peer
                                                filePickerLauncher.launch("*/*")
                                            },
                                            // A tap on Browse is an explicit,
                                            // non-destructive request; the old
                                            // confirm step only added friction.
                                            onBrowseClick = { onBrowsePeer(peer) },
                                        )
                                    }
                                }
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
    val link by RelayManager.link.collectAsStateWithLifecycle()
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
        !isVpnConnected && tunnelStatus.phase == TunnelPhase.RECONNECTING ->
            R.string.share_vpn_reconnecting
        !isVpnConnected && tunnelStatus.phase == TunnelPhase.STOPPING -> R.string.share_vpn_stopping
        !isVpnConnected -> R.string.share_connect_vpn
        token.isBlank() -> R.string.share_configure_cluster
        link == RelayLinkPhase.AUTH_FAILED -> R.string.share_relay_auth_failed
        link == RelayLinkPhase.UNREACHABLE -> R.string.share_relay_unreachable
        link == RelayLinkPhase.CONNECTING -> R.string.share_relay_connecting
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
                            Text("Send to all ${otherPeers.size} devices", fontSize = 13.sp)
                        }
                    }
                }

                if (isVpnConnected && offlineTrusted.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Offline. Queued until it reconnects.",
                        fontSize = 13.sp,
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
                tunnelStatus.phase == TunnelPhase.RECONNECTING -> {
                    Button(onClick = {}, enabled = false) {
                        Text(stringResource(R.string.status_reconnecting))
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
    val haptics = rememberHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // A monogram gives the row a fixed left edge, so a list of peers
        // scans as a list of devices rather than a wall of names.
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(scheme.surfaceContainerHigh)
                .border(1.dp, scheme.outlineVariant, MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                peer.name.trim().take(1).uppercase().ifBlank { "\u00B7" },
                style = BenchType.Data,
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                peer.name,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The section header already says who is online; the caption only
            // exists when it adds a fact (power state). No caption, no gap.
            val battery = peer.battery
            if (battery != null) {
                val meta = buildString {
                    append(battery)
                    append('%')
                    if (peer.charging == true) append(" \u00B7 charging")
                }
                Text(
                    meta,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        // Both cluster actions are visible on the row. Browsing used to hide
        // behind a row tap plus a confirmation dialog — a feature nobody can
        // find is not a feature.
        // Two explicit actions, two different weights. Browse is a quiet icon
        // that opens the file tree; Send is the labelled control. Making both
        // buttons compete made neither read as the primary action.
        if (onBrowseClick != null) {
            IconButton(
                onClick = {
                    haptics.select()
                    onBrowseClick()
                },
                modifier = Modifier.semantics {
                    contentDescription = "Browse files on ${peer.name}"
                },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_folder),
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                )
            }
        }
        // Two actions of the same weight: both are icons, and the accent
        // marks the one that starts the transfer. The label survives as the
        // accessible name instead of as row furniture.
        IconButton(
            onClick = {
                haptics.select()
                onSendClick()
            },
            modifier = Modifier.semantics {
                contentDescription = "$actionLabel to ${peer.name}"
            },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_send),
                contentDescription = null,
                tint = scheme.primary,
            )
        }
    }
}

private enum class TransferPhase { Idle, Sending, Receiving, Completed, Error }

/**
 * A stable snapshot of the dialog's state. Progress changes arrive many times
 * per second, so the transition is keyed on [phase] instead of the whole
 * object: the bar updates in place, and only a real phase change animates.
 */
private data class TransferUi(
    val phase: TransferPhase,
    val title: String,
    val fileName: String?,
    val progress: Float,
    val speedMbPerSec: Float,
    val message: String?,
)

private fun TransferState.toUi(): TransferUi = when (this) {
    is TransferState.Idle -> TransferUi(TransferPhase.Idle, "", null, 0f, 0f, null)
    is TransferState.Sending -> TransferUi(
        phase = TransferPhase.Sending,
        title = "Sending to $peerName",
        fileName = fileName,
        progress = progress,
        speedMbPerSec = speedMbPerSec,
        message = null,
    )
    is TransferState.Receiving -> TransferUi(
        phase = TransferPhase.Receiving,
        title = "Receiving from $peerName",
        fileName = fileName,
        progress = progress,
        speedMbPerSec = speedMbPerSec,
        message = null,
    )
    is TransferState.Completed -> TransferUi(
        phase = TransferPhase.Completed,
        title = "Transfer complete",
        fileName = null,
        progress = 1f,
        speedMbPerSec = 0f,
        message = message,
    )
    is TransferState.Error -> TransferUi(
        phase = TransferPhase.Error,
        title = "Transfer failed",
        fileName = null,
        progress = 0f,
        speedMbPerSec = 0f,
        message = message,
    )
}

/**
 * App-wide transfer surface. The dialog is hosted above navigation so a
 * transfer started from Browse, History, or a share sheet is still visible
 * when the screen changes.
 */
@Composable
fun TransferOverlay() {
    val state by RelayManager.transferState.collectAsStateWithLifecycle()
    if (state !is TransferState.Idle) {
        TransferDialog(
            state = state,
            onDismiss = { RelayManager.dismissTransfer() },
        )
    }
}

@Composable
private fun TransferDialog(
    state: TransferState,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val ui = state.toUi()

    LaunchedEffect(ui.phase) {
        when (ui.phase) {
            TransferPhase.Completed -> haptics.confirm()
            TransferPhase.Error -> haptics.reject()
            else -> Unit
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (ui.phase == TransferPhase.Completed || ui.phase == TransferPhase.Error) {
                onDismiss()
            }
        },
        title = {
            AnimatedContent(
                targetState = ui,
                contentKey = { it.phase },
                transitionSpec = {
                    (
                        fadeIn(tween(Motion.Base, easing = Motion.EmphasizedDecelerate)) +
                            slideInVertically(
                                animationSpec = tween(
                                    Motion.Base,
                                    easing = Motion.EmphasizedDecelerate,
                                ),
                                initialOffsetY = { it / 3 },
                            )
                        ).togetherWith(
                        fadeOut(tween(Motion.Quick, easing = Motion.EmphasizedAccelerate)) +
                            slideOutVertically(
                                animationSpec = tween(
                                    Motion.Quick,
                                    easing = Motion.EmphasizedAccelerate,
                                ),
                                targetOffsetY = { -it / 3 },
                            ),
                    )
                },
                label = "transfer_title",
            ) { target ->
                Text(
                    target.title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (target.phase) {
                        TransferPhase.Completed -> scheme.primary
                        TransferPhase.Error -> scheme.error
                        else -> scheme.onSurface
                    },
                )
            }
        },
        text = {
            AnimatedContent(
                targetState = ui,
                contentKey = { it.phase },
                transitionSpec = {
                    fadeIn(tween(Motion.Base, easing = Motion.EmphasizedDecelerate))
                        .togetherWith(fadeOut(tween(Motion.Quick, easing = Motion.EmphasizedAccelerate)))
                },
                label = "transfer_body",
            ) { target ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    when (target.phase) {
                        TransferPhase.Idle -> Unit
                        TransferPhase.Sending, TransferPhase.Receiving -> {
                            TransferFileProgress(
                                fileName = target.fileName.orEmpty(),
                                progress = target.progress,
                                speedMbPerSec = target.speedMbPerSec,
                            )
                        }
                        TransferPhase.Completed -> {
                            Text(
                                target.message.orEmpty(),
                                fontSize = 14.sp,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        TransferPhase.Error -> {
                            Text(
                                target.message.orEmpty(),
                                fontSize = 14.sp,
                                color = scheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (ui.phase) {
                TransferPhase.Completed -> {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = scheme.primary),
                    ) {
                        Text("Done", color = scheme.onPrimary)
                    }
                }
                TransferPhase.Error -> {
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
                        },
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
    val shown by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(Motion.Base, easing = Motion.Linear),
        label = "transfer_progress",
    )
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
            "${(shown * 100).toInt()}%",
            fontSize = 13.sp,
            color = scheme.onSurfaceVariant,
        )
        Text(
            "%.1f MB/s".format(speedMbPerSec),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = scheme.primary,
        )
    }
    Spacer(Modifier.height(10.dp))
    LinearProgressIndicator(
        progress = { shown },
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .semantics {
                contentDescription = "$fileName, ${(shown * 100).toInt()} percent"
            },
        color = scheme.primary,
        trackColor = scheme.outlineVariant,
    )
}
