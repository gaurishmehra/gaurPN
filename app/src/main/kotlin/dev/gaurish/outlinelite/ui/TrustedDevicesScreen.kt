package dev.gaurish.outlinelite.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.data.TransferSettingsRepository
import dev.gaurish.outlinelite.data.TrustedDeviceRepository
import dev.gaurish.outlinelite.transfer.RelayManager
import kotlinx.coroutines.launch

/**
 * Peer-side trust list. The cluster token still gates relay access; this
 * screen decides whose offers are auto-accepted and lets the user revoke a
 * device without rotating the token for the whole cluster.
 */
@Composable
fun TrustedDevicesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val trusted by TrustedDeviceRepository.observe(context)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val peers by RelayManager.peers.collectAsStateWithLifecycle()
    val onlyTrusted by TransferSettingsRepository.observeOnlyTrusted(context)
        .collectAsStateWithLifecycle(initialValue = false)

    val trustedIds = trusted.map { it.id }.toSet()
    val online = peers.filter { !it.isSelf }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Spacer(Modifier.width(2.dp))
            Text(
                "Trusted devices",
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
                    .padding(16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Only accept from trusted devices",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "Block offers from any device that is not on the list below.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = onlyTrusted,
                    onCheckedChange = { enabled ->
                        scope.launch { TransferSettingsRepository.setOnlyTrusted(context, enabled) }
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedThumbColor = Color(0xFF0B111B),
                    ),
                )
            }

            SectionLabel("Online now")
            if (online.isEmpty()) {
                Text(
                    "No other cluster devices are online.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            } else {
                online.forEach { peer ->
                    DeviceRow(
                        name = peer.name,
                        id = peer.id,
                        trusted = peer.id in trustedIds,
                        actionLabel = if (peer.id in trustedIds) "Trusted" else "Trust",
                        actionEnabled = peer.id !in trustedIds,
                        onAction = {
                            scope.launch {
                                TrustedDeviceRepository.trust(context, peer.id, peer.name)
                                Toast.makeText(context, "Trusted ${peer.name}", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }

            SectionLabel("Trusted")
            if (trusted.isEmpty()) {
                Text(
                    "No devices trusted yet. Trust a device above, then enable the switch.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            } else {
                trusted.forEach { device ->
                    DeviceRow(
                        name = device.name,
                        id = device.id,
                        trusted = true,
                        actionLabel = "Revoke",
                        actionEnabled = true,
                        onAction = {
                            scope.launch {
                                TrustedDeviceRepository.revoke(context, device.id)
                                Toast.makeText(context, "Revoked ${device.name}", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "The cluster token is still the cryptographic boundary. Revoking a device " +
                    "stops this phone from accepting its offers; rotate the cluster token to " +
                    "remove its relay access entirely.",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun DeviceRow(
    name: String,
    id: String,
    trusted: Boolean,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (trusted) "$id • trusted" else id,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = onAction,
            enabled = actionEnabled,
            modifier = Modifier.height(30.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        ) {
            Text(actionLabel, fontSize = 11.sp)
        }
    }
}
