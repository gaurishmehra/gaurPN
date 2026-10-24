package dev.gaurish.outlinelite.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    onDelete: (AccessKey) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
    ) {
        Column(
            Modifier
                .fillMaxSize()
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
                Column(Modifier.fillMaxSize()) {
                    ConnectHero(
                        Modifier
                            .fillMaxWidth()
                            .weight(0.64f),
                        status = status,
                        onConnectToggle = onConnectToggle,
                    )
                    ServersSection(
                        Modifier
                            .fillMaxWidth()
                            .weight(0.36f),
                        phase = status.phase,
                        keys = keys,
                        activeId = activeId,
                        onSelect = onSelect,
                        onDelete = onDelete,
                    )
                }
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ConnectHero(
                        Modifier
                            .weight(0.56f)
                            .fillMaxHeight(),
                        status = status,
                        onConnectToggle = onConnectToggle,
                    )
                    ServersSection(
                        Modifier
                            .weight(0.44f)
                            .fillMaxHeight(),
                        phase = status.phase,
                        keys = keys,
                        activeId = activeId,
                        onSelect = onSelect,
                        onDelete = onDelete,
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
    onConnectToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val connected = status.phase == TunnelPhase.CONNECTED

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StatusRing(status = status)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onConnectToggle,
            shape = RoundedCornerShape(27.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            colors = if (connected) {
                ButtonDefaults.buttonColors(
                    containerColor = scheme.surface,
                    contentColor = scheme.onSurface,
                )
            } else {
                ButtonDefaults.buttonColors(
                    containerColor = scheme.primary,
                    contentColor = scheme.onPrimary,
                )
            },
            contentPadding = PaddingValues(horizontal = 36.dp, vertical = 13.dp),
        ) {
            Text(
                if (connected) "Disconnect" else "Connect",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                letterSpacing = 0.2.sp,
            )
        }
    }
}

@Composable
private fun StatusRing(status: TunnelStatus) {
    val scheme = MaterialTheme.colorScheme
    val ring = phaseColor(status.phase, scheme)
    val connecting = status.phase == TunnelPhase.CONNECTING
    val infinite = rememberInfiniteTransition(label = "ring")
    val spin by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "spin",
    )
    val rotation = if (connecting) spin else 0f

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(212.dp)) {
        Canvas(Modifier.size(204.dp).rotate(rotation)) {
            val stroke = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = scheme.outlineVariant.copy(alpha = 0.55f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke,
            )
            when (status.phase) {
                TunnelPhase.CONNECTED -> drawArc(
                    brush = Brush.sweepGradient(listOf(ring.copy(alpha = 0.4f), ring, ring)),
                    startAngle = 270f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = stroke,
                )
                TunnelPhase.CONNECTING -> drawArc(
                    brush = Brush.sweepGradient(
                        listOf(ring, ring.copy(alpha = 0.35f), ring.copy(alpha = 0.08f))
                    ),
                    startAngle = 270f,
                    sweepAngle = 120f,
                    useCenter = false,
                    style = stroke,
                )
                else -> {}
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(R.drawable.ic_key),
                contentDescription = null,
                tint = if (status.phase == TunnelPhase.DISCONNECTED) {
                    scheme.onSurfaceVariant.copy(alpha = 0.85f)
                } else {
                    ring
                },
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                phaseLabel(status.phase),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onBackground,
            )
            status.activeName?.let { name ->
                Spacer(Modifier.height(4.dp))
                Text(
                    name,
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (status.phase == TunnelPhase.CONNECTED && status.connectedAt > 0) {
                UptimeLine(status.connectedAt)
            }
        }
    }
}

@Composable
private fun UptimeLine(connectedAt: Long) {
    var tick by remember(connectedAt) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(connectedAt) {
        while (true) {
            delay(1000)
            tick = SystemClock.elapsedRealtime()
        }
    }
    Text(
        "up ${formatUptime(tick - connectedAt)}",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
    onDelete: (AccessKey) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            when {
                keys.isEmpty() ->
                    Box(
                        Modifier.fillMaxSize().padding(24.dp),
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
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(keys.size) { i ->
                        ServerRow(
                            key = keys[i],
                            selected = keys[i].id == activeId,
                            onSelect = onSelect,
                            onDelete = onDelete,
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
    onDelete: (AccessKey) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .clickable { onSelect(key) }
            .background(if (selected) scheme.primary.copy(alpha = 0.10f) else Color.Transparent)
            .padding(start = 12.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
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
        IconButton(onClick = { onDelete(key) }, modifier = Modifier.size(34.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = "Delete",
                tint = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

// ---------- add dialog ----------

@Composable
private fun AddKeyDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onImport: (AccessKey) -> Unit,
) {
    if (!visible) return
    var draft by rememberSaveable { mutableStateOf("") }
    val parsed = remember(draft) { SsKeyParser.parse(draft) }
    val scheme = MaterialTheme.colorScheme

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
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        draft.isBlank() -> "Paste an ss:// access key."
                        parsed != null -> "✓ OK — ${parsed!!.name}"
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
