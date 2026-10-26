package dev.gaurish.outlinelite.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.data.AccessKey
import dev.gaurish.outlinelite.data.KeyRepository
import dev.gaurish.outlinelite.data.SsKeyParser
import dev.gaurish.outlinelite.tunnel.TunnelPhase
import dev.gaurish.outlinelite.tunnel.TunnelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

private fun phaseColor(phase: TunnelPhase, scheme: ColorScheme): Color = when (phase) {
    TunnelPhase.CONNECTED -> scheme.primary
    TunnelPhase.CONNECTING -> scheme.secondary
    TunnelPhase.STOPPING -> scheme.onSurfaceVariant
    TunnelPhase.DISCONNECTED -> scheme.outlineVariant
}

private fun phaseLabel(phase: TunnelPhase): String = when (phase) {
    TunnelPhase.CONNECTED -> "Connected"
    TunnelPhase.CONNECTING -> "Connecting…"
    TunnelPhase.STOPPING -> "Stopping…"
    TunnelPhase.DISCONNECTED -> "Idle"
}

@Composable
fun HomeScreen(
    compact: Boolean,
    status: TunnelStatus,
    keys: List<AccessKey>,
    activeId: String?,
    onConnectToggle: () -> Unit,
    onSelect: (AccessKey) -> Unit,
    onRename: (AccessKey, String) -> Unit,
    onDelete: (AccessKey) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var keyToDelete by remember { mutableStateOf<AccessKey?>(null) }
    var keyToRename by remember { mutableStateOf<AccessKey?>(null) }

    val activeKey = remember(keys, activeId) {
        keys.firstOrNull { it.id == activeId } ?: keys.firstOrNull()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            HomeHeader(onAdd = { showAdd = true }, onSettings = onOpenSettings)
            Spacer(Modifier.height(10.dp))
            status.lastError?.let { err ->
                Text(
                    err,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (compact) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ConnectHero(
                        Modifier.fillMaxWidth(),
                        status = status,
                        activeKey = activeKey,
                        onConnectToggle = onConnectToggle,
                        onOpenSettings = onOpenSettings,
                    )
                    ServersSection(
                        Modifier.fillMaxWidth(),
                        phase = status.phase,
                        keys = keys,
                        activeId = activeId,
                        onSelect = onSelect,
                        onRenameRequest = { keyToRename = it },
                        onDeleteRequest = { keyToDelete = it },
                        onShare = { key ->
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("Shadowsocks Key", key.toSsUri()))
                            Toast.makeText(context, "Key copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                }
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ConnectHero(
                        Modifier
                            .weight(0.54f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        status = status,
                        activeKey = activeKey,
                        onConnectToggle = onConnectToggle,
                        onOpenSettings = onOpenSettings,
                    )
                    ServersSection(
                        Modifier
                            .weight(0.46f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        phase = status.phase,
                        keys = keys,
                        activeId = activeId,
                        onSelect = onSelect,
                        onRenameRequest = { keyToRename = it },
                        onDeleteRequest = { keyToDelete = it },
                        onShare = { key ->
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("Shadowsocks Key", key.toSsUri()))
                            Toast.makeText(context, "Key copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }

        AddKeyDialog(
            visible = showAdd,
            onDismiss = { showAdd = false },
            onImport = { key ->
                showAdd = false
                scope.launch {
                    val added = KeyRepository.addKey(context, key)
                    if (added) KeyRepository.setActive(context, key.id)
                    Toast.makeText(
                        context,
                        context.getString(if (added) R.string.key_added else R.string.key_exists),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
        )

        // Delete Confirmation Dialog
        keyToDelete?.let { targetKey ->
            AlertDialog(
                onDismissRequest = { keyToDelete = null },
                title = { Text("Delete server?", fontWeight = FontWeight.SemiBold) },
                text = {
                    Text(
                        "Are you sure you want to remove \"${targetKey.name}\"? This action cannot be undone.",
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDelete(targetKey)
                            keyToDelete = null
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { keyToDelete = null }) {
                        Text("Cancel")
                    }
                },
            )
        }

        // Rename Server Dialog
        keyToRename?.let { targetKey ->
            var draftName by remember(targetKey) { mutableStateOf(targetKey.name) }
            AlertDialog(
                onDismissRequest = { keyToRename = null },
                title = { Text("Rename server", fontWeight = FontWeight.SemiBold) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = draftName,
                            onValueChange = { draftName = it },
                            label = { Text("Server name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = draftName.isNotBlank(),
                        onClick = {
                            onRename(targetKey, draftName.trim())
                            keyToRename = null
                        },
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { keyToRename = null }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

// ---------- header ----------

@Composable
private fun HomeHeader(onAdd: () -> Unit, onSettings: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(R.drawable.ic_key),
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.app_name),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp,
            color = scheme.onBackground,
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onAdd) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = "Add server",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onSettings) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = "Settings",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

// ---------- hero ----------

@Composable
private fun ConnectHero(
    modifier: Modifier,
    status: TunnelStatus,
    activeKey: AccessKey?,
    onConnectToggle: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val connected = status.phase == TunnelPhase.CONNECTED

    val btnBgColor by animateColorAsState(
        targetValue = if (connected) scheme.surface else scheme.primary,
        animationSpec = tween(350, easing = FastOutSlowInEasing),
        label = "btn_bg"
    )
    val btnContentColor by animateColorAsState(
        targetValue = if (connected) scheme.onSurface else scheme.onPrimary,
        animationSpec = tween(350, easing = FastOutSlowInEasing),
        label = "btn_content"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StatusRing(status = status, activeKey = activeKey)
        Spacer(Modifier.height(26.dp))
        Button(
            onClick = onConnectToggle,
            modifier = Modifier
                .height(52.dp)
                .widthIn(min = 164.dp),
            shape = RoundedCornerShape(26.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = btnBgColor,
                contentColor = btnContentColor,
            ),
            contentPadding = PaddingValues(horizontal = 32.dp),
        ) {
            AnimatedContent(
                targetState = connected,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 2 })
                        .togetherWith(fadeOut(tween(140)) + slideOutVertically(tween(140)) { -it / 2 })
                },
                label = "btn_anim"
            ) { isConn ->
                Text(
                    if (isConn) "Disconnect" else "Connect",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    letterSpacing = 0.2.sp,
                )
            }
        }

        if (connected) {
            Spacer(Modifier.height(18.dp))
            LanAccessRow(status = status)
            Spacer(Modifier.height(10.dp))
            ClusterSharingSection(onOpenSettings = onOpenSettings)
        }
    }
}

@Composable
private fun StatusRing(
    status: TunnelStatus,
    activeKey: AccessKey?,
) {
    val scheme = MaterialTheme.colorScheme
    val connected = status.phase == TunnelPhase.CONNECTED
    val connecting = status.phase == TunnelPhase.CONNECTING || status.phase == TunnelPhase.STOPPING

    val ringColor by animateColorAsState(
        targetValue = phaseColor(status.phase, scheme),
        animationSpec = tween(450, easing = FastOutSlowInEasing),
        label = "ring_color"
    )

    val fillProgress = remember { Animatable(if (connected) 1f else 0f) }
    val rippleRadius = remember { Animatable(0f) }
    val rippleAlpha = remember { Animatable(0f) }
    val iconScale = remember { Animatable(1f) }

    LaunchedEffect(status.phase) {
        when (status.phase) {
            TunnelPhase.CONNECTED -> {
                launch {
                    fillProgress.animateTo(
                        1f,
                        animationSpec = tween(550, easing = FastOutSlowInEasing)
                    )
                }
                launch {
                    rippleRadius.snapTo(0f)
                    rippleAlpha.snapTo(0.42f)
                    launch {
                        rippleRadius.animateTo(16f, tween(650, easing = FastOutSlowInEasing))
                    }
                    launch {
                        rippleAlpha.animateTo(0f, tween(650, easing = FastOutSlowInEasing))
                    }
                }
                launch {
                    iconScale.snapTo(0.93f)
                    iconScale.animateTo(
                        1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    )
                }
            }
            TunnelPhase.CONNECTING -> {
                launch {
                    fillProgress.animateTo(0f, tween(200))
                }
            }
            else -> {
                launch {
                    fillProgress.animateTo(
                        0f,
                        animationSpec = tween(400, easing = FastOutSlowInEasing)
                    )
                }
            }
        }
    }

    val infinite = rememberInfiniteTransition(label = "ring_spin")
    val spinAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "spin_angle"
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(224.dp)) {
        Canvas(Modifier.size(212.dp)) {
            val strokeWidthPx = 8.dp.toPx()
            val stroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
            val centerOffset = center
            val baseRadius = (size.minDimension - strokeWidthPx) / 2

            // Background subtle track
            drawCircle(
                color = scheme.outlineVariant.copy(alpha = 0.35f),
                radius = baseRadius,
                center = centerOffset,
                style = stroke
            )

            // Ripple wave on connection lock-in
            if (rippleAlpha.value > 0.01f) {
                drawCircle(
                    color = ringColor.copy(alpha = rippleAlpha.value),
                    radius = baseRadius + rippleRadius.value.dp.toPx(),
                    center = centerOffset,
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            // Connecting animated arc
            if (connecting) {
                val connectingBrush = Brush.sweepGradient(
                    listOf(ringColor.copy(alpha = 0.05f), ringColor.copy(alpha = 0.4f), ringColor)
                )
                rotate(degrees = spinAngle) {
                    drawArc(
                        brush = connectingBrush,
                        startAngle = 0f,
                        sweepAngle = 110f,
                        useCenter = false,
                        style = stroke,
                    )
                }
            }

            // Connected or retiring sweep
            if (fillProgress.value > 0.01f) {
                val connectedBrush = Brush.sweepGradient(
                    listOf(ringColor.copy(alpha = 0.6f), ringColor, ringColor)
                )
                drawArc(
                    brush = connectedBrush,
                    startAngle = 270f,
                    sweepAngle = 360f * fillProgress.value,
                    useCenter = false,
                    style = stroke,
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_key),
                contentDescription = null,
                tint = if (status.phase == TunnelPhase.DISCONNECTED) {
                    scheme.onSurfaceVariant.copy(alpha = 0.75f)
                } else {
                    ringColor
                },
                modifier = Modifier
                    .size(42.dp)
                    .graphicsLayer {
                        scaleX = iconScale.value
                        scaleY = iconScale.value
                    },
            )
            Spacer(Modifier.height(8.dp))
            AnimatedContent(
                targetState = status.phase,
                transitionSpec = {
                    (fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 3 })
                        .togetherWith(fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 3 })
                },
                label = "phase_label"
            ) { phase ->
                Text(
                    phaseLabel(phase),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onBackground,
                )
            }
            status.activeName?.let { name ->
                Spacer(Modifier.height(2.dp))
                Text(
                    name,
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (connected && status.connectedAt > 0) {
                LiveStatsLine(status.connectedAt, activeKey)
            }
        }
    }
}

@Composable
private fun LiveStatsLine(
    connectedAt: Long,
    activeKey: AccessKey?,
) {
    var tick by remember(connectedAt) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var rxSpeed by remember { mutableLongStateOf(0L) }
    var txSpeed by remember { mutableLongStateOf(0L) }
    var pingMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(connectedAt, activeKey?.id) {
        if (activeKey != null) {
            launch {
                while (true) {
                    pingMs = measurePing(activeKey.host, activeKey.port)
                    delay(4000)
                }
            }
        }
        var lastRx = TrafficStats.getUidRxBytes(Process.myUid())
        var lastTx = TrafficStats.getUidTxBytes(Process.myUid())
        while (true) {
            delay(1000)
            tick = SystemClock.elapsedRealtime()
            val currentRx = TrafficStats.getUidRxBytes(Process.myUid())
            val currentTx = TrafficStats.getUidTxBytes(Process.myUid())
            if (lastRx > 0 && currentRx >= lastRx) {
                rxSpeed = currentRx - lastRx
            }
            if (lastTx > 0 && currentTx >= lastTx) {
                txSpeed = currentTx - lastTx
            }
            lastRx = currentRx
            lastTx = currentTx
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(5.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (pingMs != null) {
                val pingColor = when {
                    pingMs!! < 100 -> Color(0xFF4CAF50)
                    pingMs!! < 250 -> Color(0xFFFFB300)
                    else -> Color(0xFFF44336)
                }
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(pingColor)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    "${pingMs}ms",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = pingColor,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "·",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                "up ${formatUptime(tick - connectedAt)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (rxSpeed > 0 || txSpeed > 0) {
            Spacer(Modifier.height(3.dp))
            Text(
                "↓ ${formatSpeed(rxSpeed)}  ↑ ${formatSpeed(txSpeed)}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
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

// ---------- servers ----------

@Composable
private fun ServersSection(
    modifier: Modifier,
    phase: TunnelPhase,
    keys: List<AccessKey>,
    activeId: String?,
    onSelect: (AccessKey) -> Unit,
    onRenameRequest: (AccessKey) -> Unit,
    onDeleteRequest: (AccessKey) -> Unit,
    onShare: (AccessKey) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.section_servers).uppercase(),
                fontSize = 11.sp,
                letterSpacing = 1.6.sp,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${keys.size}",
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.65f),
            )
            Spacer(Modifier.weight(1f))
            if (phase == TunnelPhase.CONNECTED) {
                Text(
                    "connected",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.primary,
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = scheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            when {
                keys.isEmpty() ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.empty_servers),
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                else -> Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    keys.forEach { key ->
                        ServerRow(
                            key = key,
                            selected = key.id == activeId,
                            onSelect = onSelect,
                            onRenameRequest = onRenameRequest,
                            onDeleteRequest = onDeleteRequest,
                            onShare = onShare,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerRow(
    key: AccessKey,
    selected: Boolean,
    onSelect: (AccessKey) -> Unit,
    onRenameRequest: (AccessKey) -> Unit,
    onDeleteRequest: (AccessKey) -> Unit,
    onShare: (AccessKey) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .clickable { onSelect(key) }
            .background(if (selected) scheme.primary.copy(alpha = 0.10f) else Color.Transparent)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(
                    if (selected) scheme.primary.copy(alpha = 0.18f)
                    else scheme.outlineVariant.copy(alpha = 0.30f)
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_key),
                contentDescription = null,
                tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                key.name.ifBlank { key.displayAddress() },
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                key.displayAddress(),
                fontSize = 12.sp,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Rename Action
        IconButton(onClick = { onRenameRequest(key) }, modifier = Modifier.size(38.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_edit),
                contentDescription = "Rename",
                tint = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.size(16.dp),
            )
        }

        // Share Action
        IconButton(onClick = { onShare(key) }, modifier = Modifier.size(38.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_share),
                contentDescription = "Share",
                tint = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.size(16.dp),
            )
        }

        // Delete Action
        IconButton(onClick = { onDeleteRequest(key) }, modifier = Modifier.size(38.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = "Delete",
                tint = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
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

// ---------- add dialog ----------

@Composable
private fun AddKeyDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onImport: (AccessKey) -> Unit,
) {
    if (!visible) return
    val context = LocalContext.current
    var draft by rememberSaveable { mutableStateOf("") }
    val parsed = remember(draft) { SsKeyParser.parse(draft) }
    val scheme = MaterialTheme.colorScheme

    // Auto-detect ss:// link on clipboard when dialog opens
    LaunchedEffect(visible) {
        if (draft.isBlank()) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = cm?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (clipText != null && clipText.startsWith("ss://", ignoreCase = true)) {
                draft = clipText
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add server", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("ss://…") },
                    minLines = 2,
                    isError = draft.isNotBlank() && parsed == null,
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        draft.isBlank() -> "Paste an ss:// access key."
                        parsed != null -> "✓ OK — ${parsed.name}"
                        else -> "Not recognized as an ss:// key"
                    },
                    fontSize = 12.sp,
                    color = when {
                        draft.isBlank() -> scheme.onSurfaceVariant.copy(alpha = 0.7f)
                        parsed != null -> scheme.primary
                        else -> scheme.error
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null,
                onClick = { parsed?.let(onImport) },
            ) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
