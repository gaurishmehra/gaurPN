package dev.gaurish.outlinelite.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableFloatStateOf
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
    TunnelPhase.CONNECTED -> Mint
    TunnelPhase.CONNECTING -> Accent
    TunnelPhase.STOPPING -> scheme.onSurfaceVariant
    TunnelPhase.DISCONNECTED -> Color(0xFF39424E)
}

private fun phaseLabel(phase: TunnelPhase): String = when (phase) {
    TunnelPhase.CONNECTED -> "Protected"
    TunnelPhase.CONNECTING -> "Connecting"
    TunnelPhase.STOPPING -> "Stopping"
    TunnelPhase.DISCONNECTED -> "Not connected"
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
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0A0E14), Color(0xFF0D1520), Color(0xFF150F07))
                )
            )
            .systemBarsPadding()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            HomeHeader(onAdd = { showAdd = true }, onSettings = onOpenSettings)
            status.lastError?.let { err ->
                Spacer(Modifier.height(8.dp))
                Text(
                    err,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
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

@Composable
private fun HomeHeader(onAdd: () -> Unit, onSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.ic_launcher),
            contentDescription = null,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Outline Lite",
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onAdd) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = "Add server",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onSettings) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun ConnectHero(
    modifier: Modifier,
    status: TunnelStatus,
    onConnectToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val ring = phaseColor(status.phase, scheme)
    val connected = status.phase == TunnelPhase.CONNECTED

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StatusRing(status = status)
        Spacer(Modifier.height(26.dp))
        Button(
            onClick = onConnectToggle,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (connected) ring else scheme.primary,
                contentColor = if (connected) Color(0xFF06231A) else scheme.onPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 34.dp, vertical = 12.dp),
        ) {
            Text(
                if (connected) "Disconnect" else "Connect",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
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
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "spin",
    )
    val rotation = if (connecting) spin else 0f

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(236.dp)) {
        Canvas(Modifier.size(224.dp).rotate(rotation)) {
            val stroke = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = Color(0xFF222B3A),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke,
            )
            when (status.phase) {
                TunnelPhase.CONNECTED -> drawArc(
                    brush = Brush.sweepGradient(listOf(ring.copy(alpha = 0.25f), ring, ring)),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = stroke,
                )
                TunnelPhase.CONNECTING -> drawArc(
                    brush = Brush.sweepGradient(listOf(ring, ring.copy(alpha = 0.25f), ring.copy(alpha = 0.05f))),
                    startAngle = 270f,
                    sweepAngle = 110f,
                    useCenter = false,
                    style = stroke,
                )
                else -> {}
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(R.drawable.ic_launcher),
                contentDescription = null,
                tint = if (status.phase == TunnelPhase.DISCONNECTED) scheme.onSurfaceVariant else ring,
                modifier = Modifier.size(46.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                phaseLabel(status.phase),
                fontSize = 23.sp,
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Servers", fontSize = 13.sp, color = scheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                "${keys.size}",
                fontSize = 12.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (phase == TunnelPhase.CONNECTED) "tunnel active" else "",
                fontSize = 11.sp,
                color = Mint,
            )
        }
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = scheme.onSurface.copy(alpha = 0.03f),
            border = BorderStroke(1.dp, scheme.onSurface.copy(alpha = 0.07f)),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            when {
                keys.isEmpty() -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No servers yet — tap ＋ to paste an ss:// access key.",
                        fontSize = 13.sp,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
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
            .background(scheme.onSurface.copy(alpha = if (selected) 0.06f else 0.0f))
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        if (selected) {
            Box(
                Modifier.size(9.dp).clip(CircleShape).background(scheme.primary)
            )
            Spacer(Modifier.width(10.dp))
        } else {
            Spacer(Modifier.width(19.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                key.name.ifBlank { key.displayAddress() },
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) scheme.primary else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${key.displayAddress()} · ${key.method}",
                fontSize = 11.5.sp,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { onDelete(key) }) {
            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = "Delete",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

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
                        draft.isBlank() -> "Paste an Outline access key (ss://…)"
                        parsed != null -> "✓ OK — ${parsed!!.name}"
                        else -> "Not recognized as an ss:// key"
                    },
                    fontSize = 12.sp,
                    color = when {
                        draft.isBlank() -> scheme.onSurfaceVariant.copy(alpha = 0.7f)
                        parsed != null -> Mint
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
