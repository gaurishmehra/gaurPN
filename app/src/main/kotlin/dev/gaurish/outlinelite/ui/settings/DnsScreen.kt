package dev.gaurish.outlinelite.ui.settings

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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gaurish.outlinelite.data.DnsMode
import dev.gaurish.outlinelite.data.KeyRepository
import kotlinx.coroutines.launch

/**
 * DNS resolver configuration. Draft-then-save: picking a mode or editing the
 * server list only applies when Save is pressed; back discards the draft.
 */
@Composable
fun DnsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val dnsMode by KeyRepository.observeDnsMode(context)
        .collectAsStateWithLifecycle(initialValue = DnsMode.ADGUARD)
    val dnsServers by KeyRepository.observeDnsServers(context)
        .collectAsStateWithLifecycle(initialValue = KeyRepository.DEFAULT_DNS_SERVERS)

    var draftMode by remember(dnsMode) { mutableStateOf(dnsMode) }
    var draftServers by remember(dnsServers) { mutableStateOf(dnsServers) }

    SettingsScaffold(title = "DNS Resolver", onBack = onBack) {
        SectionLabel("Resolver")
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
                    .clickable { draftMode = mode }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                RadioButton(
                    selected = draftMode == mode,
                    onClick = { draftMode = mode },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary
                    ),
                )
                Spacer(Modifier.width(10.dp))
                Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }

        if (draftMode != DnsMode.SYSTEM) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draftServers,
                onValueChange = { draftServers = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        if (draftMode == DnsMode.ADGUARD) {
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

        Spacer(Modifier.height(12.dp))
        Button(
            enabled = draftMode == DnsMode.SYSTEM || draftServers.isNotBlank(),
            onClick = {
                scope.launch {
                    KeyRepository.setDnsMode(context, draftMode)
                    if (draftMode != DnsMode.SYSTEM) {
                        KeyRepository.setDnsServers(context, draftServers)
                    }
                }
                onBack()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save", fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(12.dp))
        SettingsFootnote(
            "AdGuard queries travel inside the Shadowsocks tunnel and are " +
                "answered on the VPS. They never reach the local network or a " +
                "public resolver."
        )
    }
}
