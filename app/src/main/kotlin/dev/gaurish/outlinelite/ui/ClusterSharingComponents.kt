package dev.gaurish.outlinelite.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import dev.gaurish.outlinelite.transfer.ClusterPeer
import dev.gaurish.outlinelite.transfer.RelayManager
import dev.gaurish.outlinelite.transfer.TransferState
import dev.gaurish.outlinelite.tunnel.TunnelService
import dev.gaurish.outlinelite.tunnel.TunnelStatus

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
                "Bypass VPN for local printers and smart devices",
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
                checkedThumbColor = Color(0xFF0B111B),
            ),
        )
    }

    if (showWarningDialog) {
        AlertDialog(
            onDismissRequest = { showWarningDialog = false },
            title = { Text("Security Warning: LAN Access") },
            text = {
                Text(
                    "Enabling LAN access allows local network devices (printers, routers, and other machines on this Wi-Fi) to communicate directly with your phone outside the encrypted VPN tunnel.\n\n" +
                    "This may expose your local IP address on untrusted networks. This setting is strictly per-session and resets to OFF automatically when the VPN disconnects."
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
                    Text("Enable Anyway", color = scheme.onError)
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val peers by RelayManager.peers.collectAsStateWithLifecycle()
    val transferState by RelayManager.transferState.collectAsStateWithLifecycle()
    val token by KeyRepository.observeClusterToken(context).collectAsStateWithLifecycle(initialValue = "")

    var targetPeerForPicker by remember { mutableStateOf<ClusterPeer?>(null) }
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val peer = targetPeerForPicker
        if (uri != null && peer != null) {
            RelayManager.sendFile(context, peer, uri)
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(scheme.surface)
                .border(1.dp, scheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .padding(14.dp),
        ) {
            if (token.isBlank()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(scheme.outline),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "In-Tunnel File Sharing",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Paste your cluster token in Settings to share encrypted files directly between your devices inside the VPN tunnel.",
                        fontSize = 11.sp,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.height(34.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                    ) {
                        Text("Configure Token", fontSize = 12.sp)
                    }
                }
            } else {
                val otherPeers = remember(peers) { peers.filter { !it.isSelf } }
                Column {
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
                                    .background(Color(0xFF00C853)),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Cluster Devices (In-Tunnel)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.onSurface,
                            )
                        }
                        Text(
                            "${otherPeers.size} online",
                            fontSize = 11.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    if (otherPeers.isEmpty()) {
                        Text(
                            "No other cluster devices currently connected. Start gaur-share on your laptop/server.",
                            fontSize = 11.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    } else {
                        otherPeers.forEach { peer ->
                            PeerRow(
                                peer = peer,
                                onSendClick = {
                                    targetPeerForPicker = peer
                                    filePickerLauncher.launch("*/*")
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerRow(
    peer: ClusterPeer,
    onSendClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                peer.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                peer.id,
                fontSize = 10.sp,
                color = scheme.onSurfaceVariant,
            )
        }

        Button(
            onClick = onSendClick,
            modifier = Modifier.height(30.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = scheme.primary,
                contentColor = Color(0xFF04231A),
            ),
        ) {
            Text("Send File", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
                is TransferState.Connecting -> "Connecting..."
                is TransferState.Sending -> "Sending File"
                is TransferState.Receiving -> "Receiving File"
                is TransferState.Completed -> "Transfer Complete"
                is TransferState.Error -> "Transfer Error"
            }
            Text(
                titleText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = when (state) {
                    is TransferState.Completed -> Color(0xFF00C853)
                    is TransferState.Error -> scheme.error
                    else -> scheme.onSurface
                },
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when (state) {
                    is TransferState.Idle -> Unit
                    is TransferState.Connecting -> {
                        Text(
                            "Establishing tunnel connection to ${state.peerName}...",
                            fontSize = 14.sp,
                            color = scheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = scheme.primary,
                            trackColor = scheme.outlineVariant,
                        )
                    }
                    is TransferState.Sending -> {
                        Text(
                            state.fileName,
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
                                "${(state.progress * 100).toInt()}%",
                                fontSize = 12.sp,
                                color = scheme.onSurfaceVariant,
                            )
                            Text(
                                "%.1f MB/s".format(state.speedMbPerSec),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = scheme.primary,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = scheme.primary,
                            trackColor = scheme.outlineVariant,
                        )
                    }
                    is TransferState.Receiving -> {
                        Text(
                            state.fileName,
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
                                "${(state.progress * 100).toInt()}%",
                                fontSize = 12.sp,
                                color = scheme.onSurfaceVariant,
                            )
                            Text(
                                "%.1f MB/s".format(state.speedMbPerSec),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = scheme.primary,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = scheme.primary,
                            trackColor = scheme.outlineVariant,
                        )
                    }
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
                        Text("Done", color = Color(0xFF04231A))
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
                    TextButton(onClick = onDismiss) {
                        Text("Dismiss")
                    }
                }
            }
        },
    )
}

