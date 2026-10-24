package dev.gaurish.outlinelite.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.DisposableEffectResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.data.KeyRepository
import kotlinx.coroutines.launch

/**
 * Settings: background keep-alive (battery exemption + auto-reconnect + boot start)
 * and a privacy statement. Values persist in DataStore.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resources = MaterialTheme.colorScheme

    val autoReconnect by KeyRepository.observeAutoReconnect(context)
        .collectAsStateWithLifecycle(initialValue = true)
    val autostart by KeyRepository.observeAutostart(context)
        .collectAsStateWithLifecycle(initialValue = false)

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

            SectionLabel("Background")
            SettingsRow(
                title = "Keep connection alive",
                summary = "Reconnect automatically if the tunnel drops (up to 5 attempts)",
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
            .fillMaxSize()
            .padding(bottom = 10.dp)
    ) {
        val clickableMod =
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
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
