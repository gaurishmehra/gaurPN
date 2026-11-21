package dev.gaurish.outlinelite.ui

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.data.AccessKey
import dev.gaurish.outlinelite.data.TransferHistoryEntry
import dev.gaurish.outlinelite.data.TransferHistoryRepository
import dev.gaurish.outlinelite.transfer.ClusterPeer
import dev.gaurish.outlinelite.transfer.RelayManager
import dev.gaurish.outlinelite.tunnel.TunnelPhase
import dev.gaurish.outlinelite.tunnel.TunnelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun phaseLabel(phase: TunnelPhase): String = when (phase) {
    TunnelPhase.CONNECTED -> "Connected"
    TunnelPhase.CONNECTING -> "Connecting"
    TunnelPhase.STOPPING -> "Disconnecting"
    TunnelPhase.DISCONNECTED -> "Not connected"
}

@Composable
fun HomeScreen(
    compact: Boolean,
    status: TunnelStatus,
    keys: List<AccessKey>,
    keysLoaded: Boolean,
    activeId: String?,
    onConnectToggle: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onBrowsePeer: (ClusterPeer) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val activeKey = remember(keys, activeId) {
        keys.firstOrNull { it.id == activeId } ?: keys.firstOrNull()
    }
    val history by TransferHistoryRepository.observe(context)
        .collectAsStateWithLifecycle(initialValue = null)
    val peers by RelayManager.peers.collectAsStateWithLifecycle()
    val connected = status.phase == TunnelPhase.CONNECTED
    val onlineCount = remember(peers) { peers.count { !it.isSelf } }
    val activityLimit = if (compact) 1 else 6

    Box(
        Modifier
            .fillMaxSize()
            .background(scheme.background)
    ) {
        if (compact) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .systemBarsPadding(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                item { HomeHeader(onAdd = onOpenServers) }
                item {
                    ConnectionPanel(
                        compact = true,
                        status = status,
                        activeKey = activeKey,
                        hasKey = keys.isNotEmpty(),
                        keysLoaded = keysLoaded,
                        onConnectToggle = onConnectToggle,
                        onOpenServers = onOpenServers,
                    )
                }
                item { RouteRow(activeKey, !keysLoaded, onOpenServers) }
                item {
                    DevicesSection(
                        connected = connected,
                        onlineCount = onlineCount,
                        onOpenSettings = onOpenSettings,
                        onBrowsePeer = onBrowsePeer,
                    )
                }
                item {
                    ActivitySection(
                        entries = history.orEmpty().take(activityLimit),
                        loading = history == null,
                        compact = true,
                        onOpenHistory = onOpenHistory,
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 1200.dp)
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .systemBarsPadding()
                    .padding(horizontal = 32.dp, vertical = 20.dp),
            ) {
                HomeHeader(onAdd = onOpenServers)
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = scheme.outlineVariant, thickness = 1.dp)
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(0.46f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        ConnectionPanel(
                            compact = false,
                            status = status,
                            activeKey = activeKey,
                            hasKey = keys.isNotEmpty(),
                            keysLoaded = keysLoaded,
                            onConnectToggle = onConnectToggle,
                            onOpenServers = onOpenServers,
                        )
                        RouteRow(activeKey, !keysLoaded, onOpenServers)
                        Spacer(Modifier.height(8.dp))
                    }
                    Column(
                        modifier = Modifier
                            .weight(0.54f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        DevicesSection(
                            connected = connected,
                            onlineCount = onlineCount,
                            onOpenSettings = onOpenSettings,
                            onBrowsePeer = onBrowsePeer,
                        )
                        ActivitySection(
                            entries = history.orEmpty().take(activityLimit),
                            loading = history == null,
                            compact = false,
                            onOpenHistory = onOpenHistory,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "gaurPN",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onAdd, modifier = Modifier.size(48.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = "Add server",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun ConnectionPanel(
    compact: Boolean,
    status: TunnelStatus,
    activeKey: AccessKey?,
    hasKey: Boolean,
    keysLoaded: Boolean,
    onConnectToggle: () -> Unit,
    onOpenServers: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            GaitRing(
                phase = status.phase,
                modifier = Modifier.size(if (compact) 88.dp else 128.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "VPN",
                    style = BenchType.Micro,
                    color = scheme.onSurfaceVariant,
                )
                AnimatedContent(
                    targetState = status.phase,
                    transitionSpec = {
                        fadeIn(tween(140, easing = Motion.Decelerate))
                            .togetherWith(fadeOut(tween(90, easing = Motion.Accelerate)))
                    },
                    label = "phase_label",
                ) { phase ->
                    Text(
                        phaseLabel(phase),
                        fontSize = if (compact) 24.sp else 28.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onBackground,
                    )
                }
                Text(
                    when {
                        activeKey != null -> activeKey.name
                        keysLoaded -> "No server selected"
                        else -> "Loading servers"
                    },
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                LiveStatsLine(status, activeKey)
            }
        }

        PrimaryAction(
            phase = status.phase,
            hasKey = hasKey,
            keysLoaded = keysLoaded,
            compact = compact,
            onConnectToggle = onConnectToggle,
            onOpenServers = onOpenServers,
        )

        status.lastError?.let { error ->
            ErrorLine(error)
        }
    }
}

@Composable
private fun PrimaryAction(
    phase: TunnelPhase,
    hasKey: Boolean,
    keysLoaded: Boolean,
    compact: Boolean,
    onConnectToggle: () -> Unit,
    onOpenServers: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val label = when (phase) {
        TunnelPhase.CONNECTED -> "Disconnect"
        TunnelPhase.CONNECTING, TunnelPhase.STOPPING -> "Cancel"
        TunnelPhase.DISCONNECTED -> "Connect"
    }
    val enabled = keysLoaded && (hasKey || phase != TunnelPhase.DISCONNECTED)
    val modifier = if (compact) {
        Modifier.fillMaxWidth()
    } else {
        Modifier.widthIn(min = 220.dp)
    }

    if (phase == TunnelPhase.CONNECTED) {
        OutlinedButton(
            onClick = onConnectToggle,
            modifier = modifier.heightIn(min = 52.dp),
            shape = MaterialTheme.shapes.medium,
            border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.onBackground),
        ) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    } else {
        Button(
            onClick = onConnectToggle,
            enabled = enabled,
            modifier = modifier.heightIn(min = 52.dp),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = scheme.primary,
                contentColor = scheme.onPrimary,
                disabledContainerColor = scheme.surfaceVariant,
                disabledContentColor = scheme.onSurfaceVariant,
            ),
        ) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    if (keysLoaded && !hasKey) {
        TextButton(onClick = onOpenServers) {
            Text("Add a server", fontSize = 13.sp)
        }
    }
}

@Composable
private fun ErrorLine(message: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(scheme.errorContainer.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(3.dp, 20.dp)
                .clip(CircleShape)
                .background(scheme.error)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            message,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = scheme.onErrorContainer,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GaitRing(
    phase: TunnelPhase,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val progress = remember {
        Animatable(if (phase == TunnelPhase.CONNECTED) 8f else 0f)
    }

    LaunchedEffect(phase) {
        when (phase) {
            TunnelPhase.CONNECTING -> {
                progress.snapTo(0f)
                while (true) {
                    progress.animateTo(8f, tween(1600, easing = LinearEasing))
                    progress.snapTo(0f)
                }
            }
            TunnelPhase.CONNECTED -> progress.animateTo(
                8f,
                tween(Motion.Settle, easing = Motion.Decelerate),
            )
            else -> progress.animateTo(
                0f,
                tween(Motion.Base, easing = Motion.Standard),
            )
        }
    }

    Canvas(modifier) {
        val strokeWidth = 6.dp.toPx()
        val radius = (size.minDimension - strokeWidth) / 2f
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2f, radius * 2f)
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

        for (index in 0 until 8) {
            val fill = (progress.value - index).coerceIn(0f, 1f)
            val color = if (fill <= 0f) {
                scheme.outlineVariant.copy(alpha = 0.70f)
            } else {
                scheme.primary.copy(alpha = 0.30f + 0.70f * fill)
            }
            drawArc(
                color = color,
                startAngle = -90f + index * 45f + 6.5f,
                sweepAngle = 32f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
        }
    }
}

@Composable
private fun LiveStatsLine(status: TunnelStatus, activeKey: AccessKey?) {
    val scheme = MaterialTheme.colorScheme
    val connected = status.phase == TunnelPhase.CONNECTED && status.connectedAt > 0
    var tick by remember(status.connectedAt) {
        mutableLongStateOf(SystemClock.elapsedRealtime())
    }
    var rxBytes by remember { mutableLongStateOf(0L) }
    var txBytes by remember { mutableLongStateOf(0L) }
    var pingMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(connected, status.connectedAt, activeKey?.id) {
        if (!connected) {
            rxBytes = 0L
            txBytes = 0L
            pingMs = null
            return@LaunchedEffect
        }
        if (activeKey != null) {
            launch {
                while (true) {
                    pingMs = measurePing(activeKey.host, activeKey.port)
                    delay(15_000)
                }
            }
        }
        var lastRx = TrafficStats.getUidRxBytes(Process.myUid())
        var lastTx = TrafficStats.getUidTxBytes(Process.myUid())
        while (true) {
            delay(1000)
            tick = SystemClock.elapsedRealtime()
            val nowRx = TrafficStats.getUidRxBytes(Process.myUid())
            val nowTx = TrafficStats.getUidTxBytes(Process.myUid())
            if (lastRx > 0 && nowRx >= lastRx) rxBytes = nowRx - lastRx
            if (lastTx > 0 && nowTx >= lastTx) txBytes = nowTx - lastTx
            lastRx = nowRx
            lastTx = nowTx
        }
    }

    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            if (connected) formatUptime(tick - status.connectedAt) else "--:--",
            style = BenchType.Readout,
            color = if (connected) scheme.onBackground else scheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("PING", style = BenchType.Micro, color = scheme.onSurfaceVariant)
            Text(
                if (connected && pingMs != null) "$pingMs ms" else "--",
                style = BenchType.Data,
                color = scheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "IN ${formatSpeed(rxBytes)}   OUT ${formatSpeed(txBytes)}",
        style = BenchType.Data,
        color = scheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RouteRow(key: AccessKey?, loading: Boolean, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column {
        HorizontalDivider(color = scheme.outlineVariant, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !loading, onClick = onOpen)
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("SERVER", style = BenchType.Micro, color = scheme.onSurfaceVariant)
                Text(
                    when {
                        key != null -> key.name
                        loading -> "Loading servers"
                        else -> "Add a server"
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!loading && key != null) {
                    Text(
                        key.displayAddress(),
                        style = BenchType.Data,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = scheme.outlineVariant, thickness = 1.dp)
    }
}

@Composable
private fun DevicesSection(
    connected: Boolean,
    onlineCount: Int,
    onOpenSettings: () -> Unit,
    onBrowsePeer: (ClusterPeer) -> Unit,
) {
    Column {
        SectionHeader(
            title = "DEVICES",
            trailing = if (connected) "$onlineCount online" else null,
        )
        if (connected) {
            ClusterSharingSection(
                onOpenSettings = onOpenSettings,
                onBrowsePeer = onBrowsePeer,
                embedded = true,
                showHeader = false,
            )
        } else {
            Text(
                "No devices online. Connect to find your other devices.",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActivitySection(
    entries: List<TransferHistoryEntry>,
    loading: Boolean,
    compact: Boolean,
    onOpenHistory: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Column {
        SectionHeader("ACTIVITY", trailing = "History", onTrailingClick = onOpenHistory)
        when {
            loading -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(scheme.surfaceVariant.copy(alpha = 0.35f))
                )
            }
            entries.isEmpty() -> {
                Text(
                    "No transfers yet.",
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
            else -> {
                entries.forEachIndexed { index, entry ->
                    ActivityRow(entry, formatter, onOpenHistory)
                    if (index != entries.lastIndex) {
                        HorizontalDivider(color = scheme.outlineVariant, thickness = 1.dp)
                    }
                }
                if (compact) {
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(
    entry: TransferHistoryEntry,
    formatter: SimpleDateFormat,
    onOpenHistory: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val statusColor = when (entry.status) {
        "completed" -> scheme.primary
        "failed" -> scheme.error
        else -> scheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenHistory)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(statusColor.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (entry.direction == "sent") "\u2191" else "\u2193",
                style = BenchType.Data,
                color = statusColor,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${entry.peerName}  ${formatter.format(Date(entry.timestamp))}",
                style = BenchType.Data,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            when (entry.status) {
                "completed" -> "Done"
                "failed" -> "Failed"
                "active" -> "In progress"
                else -> entry.status
            },
            style = BenchType.Micro,
            color = statusColor,
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    trailing: String? = null,
    onTrailingClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = BenchType.Micro, color = scheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(
                trailing,
                style = BenchType.Micro,
                color = if (onTrailingClick != null) scheme.primary else scheme.onSurfaceVariant,
                modifier = if (onTrailingClick != null) {
                    Modifier.clickable(onClick = onTrailingClick)
                } else {
                    Modifier
                },
            )
        }
    }
}

private fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec < 1024) return "$bytesPerSec B/s"
    val kb = bytesPerSec / 1024f
    if (kb < 1024) return "%.1f KB/s".format(kb)
    val mb = kb / 1024f
    return "%.1f MB/s".format(mb)
}

private fun formatUptime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0)
    val h = total / 3600L
    val m = (total / 60L) % 60L
    val s = total % 60L
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private suspend fun measurePing(host: String, port: Int): Long? = withContext(Dispatchers.IO) {
    runCatching {
        val start = SystemClock.elapsedRealtime()
        Socket().use { socket ->
            socket.soTimeout = 2500
            socket.connect(InetSocketAddress(host, port), 2500)
        }
        SystemClock.elapsedRealtime() - start
    }.getOrNull()
}
