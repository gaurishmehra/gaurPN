package dev.gaurish.outlinelite.ui

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.gaurish.outlinelite.data.AccessKey
import dev.gaurish.outlinelite.data.TransferHistoryEntry
import dev.gaurish.outlinelite.data.TransferHistoryRepository
import dev.gaurish.outlinelite.transfer.ClusterPeer
import dev.gaurish.outlinelite.transfer.RelayLinkPhase
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
    TunnelPhase.RECONNECTING -> "Reconnecting"
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
    onAddServer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onBrowsePeer: (ClusterPeer) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val connected = status.phase == TunnelPhase.CONNECTED
    // While a session is live, the displayed key comes from the tunnel, not
    // from whatever key would be "next" if the active one disappears.
    val sessionActive = status.phase != TunnelPhase.DISCONNECTED && status.activeId != null
    val sessionKey = remember(keys, status.activeId) {
        status.activeId?.let { id -> keys.firstOrNull { it.id == id } }
    }
    val idleKey = remember(keys, activeId) {
        keys.firstOrNull { it.id == activeId } ?: keys.firstOrNull()
    }
    val activeKey = if (sessionActive) sessionKey else idleKey
    // The server rides beside the phase word; Servers is one tab away, so the
    // home surface does not repeat the picker.
    val serverLine = when {
        sessionActive -> status.activeName ?: sessionKey.resolveName()
        !keysLoaded -> "Loading servers"
        keys.isEmpty() -> null
        else -> idleKey.resolveName()
    }
    val motionEnabled = rememberMotionEnabled()

    // Outcomes get a haptic. The phase and the error both arrive from the
    // tunnel, so this is the one place that can feel them for both layouts.
    // A returning screen starts from the current phase and stays silent.
    val haptics = rememberHaptics()
    var lastPhase by remember { mutableStateOf(status.phase) }
    LaunchedEffect(status.phase) {
        val previous = lastPhase
        lastPhase = status.phase
        if (previous != status.phase && status.phase == TunnelPhase.CONNECTED) {
            haptics.confirm()
        }
    }
    var lastError by remember { mutableStateOf(status.lastError) }
    LaunchedEffect(status.lastError) {
        val previous = lastError
        lastError = status.lastError
        if (status.lastError != null && status.lastError != previous) haptics.reject()
    }

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
                    .statusBarsPadding(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item { Reveal(0, motionEnabled) { HomeHeader() } }
                item {
                    Reveal(1, motionEnabled) {
                        HeroSurface {
                            ConnectionPanel(
                                compact = true,
                                status = status,
                                activeKey = activeKey,
                                serverLine = serverLine,
                                hasKey = keys.isNotEmpty(),
                                keysLoaded = keysLoaded,
                                onConnectToggle = onConnectToggle,
                                onAddServer = onAddServer,
                            )
                        }
                    }
                }
                item {
                    Reveal(2, motionEnabled) {
                        DevicesSection(
                            connected = connected,
                            reconnecting = status.phase == TunnelPhase.RECONNECTING,
                            onOpenSettings = onOpenSettings,
                            onBrowsePeer = onBrowsePeer,
                        )
                    }
                }
                item {
                    Reveal(3, motionEnabled) {
                        ActivitySection(
                            compact = true,
                            onOpenHistory = onOpenHistory,
                        )
                    }
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
                    .statusBarsPadding()
                    .padding(horizontal = 32.dp, vertical = 20.dp),
            ) {
                Reveal(0, motionEnabled) { HomeHeader() }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = scheme.outlineVariant, thickness = 1.dp)
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    // The hero owns the left column, with activity beneath it;
                    // the right column carries the cluster. Without activity
                    // here the left half of the screen would sit empty.
                    Column(
                        modifier = Modifier
                            .weight(0.46f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Reveal(1, motionEnabled) {
                            HeroSurface {
                                ConnectionPanel(
                                    compact = false,
                                    status = status,
                                    activeKey = activeKey,
                                    serverLine = serverLine,
                                    hasKey = keys.isNotEmpty(),
                                    keysLoaded = keysLoaded,
                                    onConnectToggle = onConnectToggle,
                                    onAddServer = onAddServer,
                                )
                            }
                        }
                        Reveal(2, motionEnabled) {
                            ActivitySection(
                                compact = false,
                                onOpenHistory = onOpenHistory,
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(0.54f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Reveal(3, motionEnabled) {
                            DevicesSection(
                                connected = connected,
                                reconnecting = status.phase == TunnelPhase.RECONNECTING,
                                onOpenSettings = onOpenSettings,
                                onBrowsePeer = onBrowsePeer,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader() {
    Text(
        "gaurPN",
        style = BenchType.Title,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

/**
 * The connection unit owns one graphite plane: ring, phase, server, action,
 * and the live readout read as a single instrument. Without the plane the
 * unit floats on the background and the sections below feel disconnected
 * from it.
 */
@Composable
private fun HeroSurface(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surfaceContainerLow)
            // One light source, from above. A sheen at the top edge and a
            // shade at the bottom are what make the plane read as a surface
            // rather than a filled rectangle, and it holds in both themes
            // because the overlay is built from the scheme's own ink.
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        0f to scheme.onSurface.copy(alpha = 0.035f),
                        0.45f to Color.Transparent,
                        1f to scheme.scrim.copy(alpha = 0.10f),
                    )
                )
            }
            .border(1.dp, scheme.outlineVariant, shape)
            .padding(20.dp),
    ) {
        content()
    }
}

@Composable
private fun ConnectionPanel(
    compact: Boolean,
    status: TunnelStatus,
    activeKey: AccessKey?,
    serverLine: String?,
    hasKey: Boolean,
    keysLoaded: Boolean,
    onConnectToggle: () -> Unit,
    onAddServer: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // The phase word lifts 10 dp: enough to read as a change of state, small
    // enough that it never looks like the layout is moving.
    val phaseSlide = with(LocalDensity.current) { 10.dp.roundToPx() }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            GaitRing(
                phase = status.phase,
                modifier = Modifier.size(if (compact) 96.dp else 132.dp),
            )
            Column(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = status.phase,
                    transitionSpec = {
                        (
                            fadeIn(tween(Motion.Enter, easing = Motion.EmphasizedDecelerate)) +
                                slideInVertically(
                                    animationSpec = tween(
                                        Motion.Enter,
                                        easing = Motion.EmphasizedDecelerate,
                                    ),
                                    initialOffsetY = { phaseSlide },
                                )
                            ).togetherWith(
                            fadeOut(tween(Motion.Exit, easing = Motion.EmphasizedAccelerate)) +
                                slideOutVertically(
                                    animationSpec = tween(
                                        Motion.Exit,
                                        easing = Motion.EmphasizedAccelerate,
                                    ),
                                    targetOffsetY = { -phaseSlide },
                                ),
                        )
                    },
                    label = "phase_label",
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                ) { phase ->
                    Text(
                        phaseLabel(phase),
                        fontSize = if (compact) 24.sp else 30.sp,
                        lineHeight = if (compact) 30.sp else 36.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onBackground,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AnimatedContent(
                    targetState = serverLine,
                    transitionSpec = {
                        fadeIn(tween(Motion.Quick, easing = Motion.EmphasizedDecelerate))
                            .togetherWith(
                                fadeOut(tween(Motion.Quick, easing = Motion.EmphasizedAccelerate))
                            )
                    },
                    label = "server_line",
                ) { line ->
                    if (line != null) {
                        Text(
                            line,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                if (status.phase == TunnelPhase.RECONNECTING ||
                    (status.phase == TunnelPhase.CONNECTING && status.attempt > 0)
                ) {
                    Spacer(Modifier.height(4.dp))
                    ReconnectStatus(status)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        PrimaryAction(
            phase = status.phase,
            hasKey = hasKey,
            keysLoaded = keysLoaded,
            compact = compact,
            onConnectToggle = onConnectToggle,
            onAddServer = onAddServer,
        )

        LiveStats(status, activeKey)

        // Keep the last message through the exit animation; a null error
        // should collapse the banner, not blank it first.
        var retainedError by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(status.lastError) {
            if (status.lastError != null) retainedError = status.lastError
        }
        AnimatedVisibility(
            visible = status.lastError != null,
            enter = fadeIn(tween(Motion.Enter, easing = Motion.EmphasizedDecelerate)) +
                expandVertically(
                    animationSpec = tween(Motion.Enter, easing = Motion.EmphasizedDecelerate),
                    expandFrom = Alignment.Top,
                ),
            exit = fadeOut(tween(Motion.Exit, easing = Motion.EmphasizedAccelerate)) +
                shrinkVertically(
                    animationSpec = tween(Motion.Exit, easing = Motion.EmphasizedAccelerate),
                    shrinkTowards = Alignment.Top,
                ),
        ) {
            retainedError?.let { message ->
                Column {
                    Spacer(Modifier.height(20.dp))
                    ErrorLine(message)
                }
            }
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
    onAddServer: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    // With no key, the only useful action is adding one. Rendering a dead
    // Connect button above a small text link made first run a dead end.
    val addFirst = keysLoaded && !hasKey && phase == TunnelPhase.DISCONNECTED
    val label = when {
        !keysLoaded -> "Loading"
        addFirst -> "Add server"
        phase == TunnelPhase.CONNECTED -> "Disconnect"
        phase == TunnelPhase.CONNECTING -> "Cancel"
        phase == TunnelPhase.RECONNECTING -> "Stop trying"
        phase == TunnelPhase.STOPPING -> "Stopping\u2026"
        else -> "Connect"
    }
    val enabled = when {
        addFirst -> true
        phase == TunnelPhase.STOPPING -> false
        else -> keysLoaded && (hasKey || phase != TunnelPhase.DISCONNECTED)
    }
    val modifier = if (compact) {
        Modifier.fillMaxWidth()
    } else {
        Modifier.widthIn(min = 220.dp)
    }
        .heightIn(min = 54.dp)
        .pressScale(interaction, pressedScale = 0.97f)

    val action = {
        haptics.select()
        if (addFirst) onAddServer() else onConnectToggle()
    }

    val live = phase == TunnelPhase.CONNECTED
    val container by animateColorAsState(
        targetValue = if (live) Color.Transparent else scheme.primary,
        animationSpec = Motion.base(),
        label = "action_container",
    )
    val content by animateColorAsState(
        targetValue = if (live) scheme.onBackground else scheme.onPrimary,
        animationSpec = Motion.base(),
        label = "action_content",
    )
    val borderColor by animateColorAsState(
        targetValue = if (live) scheme.outline else Color.Transparent,
        animationSpec = Motion.base(),
        label = "action_border",
    )

    // One control morphs between the filled connect state and the outlined
    // disconnect state. Swapping two button types made the primary action
    // jump at exactly the moment the user is watching it.
    Button(
        onClick = action,
        enabled = enabled,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = scheme.surfaceVariant,
            disabledContentColor = scheme.onSurfaceVariant,
        ),
        border = BorderStroke(1.dp, borderColor),
        interactionSource = interaction,
    ) {
        ActionLabel(label)
    }
}

/** Button copy swaps without the control jumping; the label crossfades in place. */
@Composable
private fun ActionLabel(label: String) {
    AnimatedContent(
        targetState = label,
        transitionSpec = {
            fadeIn(tween(Motion.Quick, easing = Motion.EmphasizedDecelerate))
                .togetherWith(fadeOut(tween(Motion.Instant, easing = Motion.EmphasizedAccelerate)))
        },
        label = "action_label",
    ) { text ->
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
            .semantics {
                liveRegion = LiveRegionMode.Polite
                error(message)
            }
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
            fontSize = 13.sp,
            lineHeight = 18.sp,
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
    val motionEnabled = rememberMotionEnabled()
    val lifecycleOwner = LocalLifecycleOwner.current

    // How many segments are fully amber. It sweeps around the dial once on
    // connect and drains on disconnect, so the ring records the transition
    // instead of merely switching color.
    val lit = remember { Animatable(if (phase == TunnelPhase.CONNECTED) 8f else 0f) }

    // While connecting, a two-segment comet orbits the dial. The head lives in
    // [0,8) and the tail is computed with a modulo distance, so the wrap never
    // shows the snap the old stepped counter had.
    val head = remember { Animatable(0f) }

    // A single halo on arrival. It runs once and stops; a live screen must
    // reach idle, so there is deliberately no steady breathing loop here.
    val arrival = remember { Animatable(0f) }

    // The dial lands rather than merely lights up: a 6% overshoot on connect,
    // decaying to rest. Drawn as a layer transform, so it costs no layout.
    val dialScale = remember { Animatable(1f) }
    LaunchedEffect(phase, motionEnabled) {
        if (phase == TunnelPhase.CONNECTED && motionEnabled) {
            dialScale.animateTo(1.06f, tween(180, easing = Motion.EmphasizedDecelerate))
            dialScale.animateTo(1f, Motion.pop())
        } else {
            dialScale.snapTo(1f)
        }
    }

    LaunchedEffect(phase, motionEnabled) {
        when (phase) {
            TunnelPhase.CONNECTING, TunnelPhase.RECONNECTING -> {
                arrival.snapTo(0f)
                lit.animateTo(0f, tween(Motion.Base, easing = Motion.EmphasizedAccelerate))
                if (motionEnabled) {
                    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        while (true) {
                            head.animateTo(8f, tween(1300, easing = LinearEasing))
                            head.snapTo(0f)
                        }
                    }
                } else {
                    lit.animateTo(2.4f, Motion.base())
                }
            }
            TunnelPhase.CONNECTED -> {
                head.snapTo(0f)
                lit.animateTo(8f, Motion.settle())
                if (motionEnabled) {
                    arrival.animateTo(1f, tween(240, easing = Motion.EmphasizedDecelerate))
                    arrival.animateTo(0f, tween(760, easing = Motion.Standard))
                }
            }
            else -> {
                head.snapTo(0f)
                arrival.snapTo(0f)
                lit.animateTo(0f, tween(Motion.Base, easing = Motion.Standard))
            }
        }
    }

    Canvas(
        modifier.graphicsLayer {
            scaleX = dialScale.value
            scaleY = dialScale.value
        }
    ) {
        val strokeWidth = 6.dp.toPx()
        val radius = (size.minDimension - strokeWidth) / 2f
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2f, radius * 2f)
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        val after = lit.value
        val orbit = head.value
        val halo = arrival.value

        // An active tunnel lights the plane under the dial. It is drawn from
        // state, not from a clock: connected means the wash is there, and
        // disconnecting removes it. Nothing pulses while the screen idles.
        if (phase == TunnelPhase.CONNECTED) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        scheme.primary.copy(alpha = 0.13f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = radius + strokeWidth,
                ),
                radius = radius + strokeWidth,
            )
        }

        if (halo > 0f) {
            drawCircle(
                color = scheme.primary.copy(alpha = 0.16f * halo),
                radius = radius + strokeWidth * (0.8f + halo),
                style = Stroke(width = strokeWidth * 1.4f),
            )
        }

        for (index in 0 until 8) {
            val settled = (after - index).coerceIn(0f, 1f)
            val distance = ((orbit - index) % 8f + 8f) % 8f
            val comet = if (
                (phase == TunnelPhase.CONNECTING || phase == TunnelPhase.RECONNECTING) &&
                distance < 2.2f
            ) {
                1f - distance / 2.2f
            } else {
                0f
            }
            val fill = maxOf(settled, comet)
            val color = if (fill <= 0f) {
                scheme.outlineVariant.copy(alpha = 0.55f)
            } else {
                scheme.primary.copy(alpha = 0.32f + 0.68f * fill)
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

/**
 * Retry status for the gap between a dropped tunnel and the next attempt.
 * The timer only runs while the screen is resumed and the status is
 * RECONNECTING; it is not a general-purpose clock.
 */
@Composable
private fun ReconnectStatus(status: TunnelStatus) {
    val scheme = MaterialTheme.colorScheme
    val lifecycleOwner = LocalLifecycleOwner.current
    var now by remember(status.retryAt) {
        mutableLongStateOf(SystemClock.elapsedRealtime())
    }
    LaunchedEffect(status.retryAt) {
        if (status.retryAt <= 0L) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(500)
            }
        }
    }
    val remaining = (status.retryAt - now).coerceAtLeast(0L)
    val detail = when {
        status.attempt <= 0 -> "Reconnecting"
        remaining > 0L ->
            "Attempt ${status.attempt} \u00B7 next try in ${formatRetry(remaining)}"
        else -> "Attempt ${status.attempt} \u00B7 retrying now"
    }
    Text(
        detail,
        style = BenchType.Data,
        color = scheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun formatRetry(ms: Long): String {
    val totalSeconds = (ms + 999L) / 1000L
    return if (totalSeconds >= 60L) {
        "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    } else {
        "${totalSeconds}s"
    }
}

@Composable
private fun LiveStats(status: TunnelStatus, activeKey: AccessKey?) {
    val scheme = MaterialTheme.colorScheme
    val connected = status.phase == TunnelPhase.CONNECTED && status.connectedAt > 0
    // Keep the last session start through the exit animation: the service
    // clears connectedAt on disconnect, and a zero start would flash a
    // five-decade uptime on the way out.
    var lastStart by remember { mutableLongStateOf(status.connectedAt) }
    LaunchedEffect(status.connectedAt) {
        if (status.connectedAt > 0) lastStart = status.connectedAt
    }
    var tick by remember(status.connectedAt) {
        mutableLongStateOf(SystemClock.elapsedRealtime())
    }
    var rxBytes by remember { mutableLongStateOf(0L) }
    var txBytes by remember { mutableLongStateOf(0L) }
    var pingMs by remember { mutableStateOf<Long?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // The clock, the probe, and the throughput sample stop with the screen.
    // Leaving them running while backgrounded would wake the radio for values
    // nobody can see.
    LaunchedEffect(connected, status.connectedAt, activeKey?.id) {
        if (!connected) {
            rxBytes = 0L
            txBytes = 0L
            pingMs = null
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (activeKey != null) {
                launch {
                    while (true) {
                        pingMs = measurePing(activeKey.host, activeKey.port)
                        delay(15_000)
                    }
                }
            }
            launch {
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
        }
    }

    // Idle is quiet: no placeholders, no zeroed counters, just the phase and
    // the action. The readout appears when there is something to read.
    AnimatedVisibility(
        visible = connected,
        enter = fadeIn(tween(Motion.Enter, easing = Motion.EmphasizedDecelerate)) +
            expandVertically(
                animationSpec = tween(Motion.Enter, easing = Motion.EmphasizedDecelerate),
                expandFrom = Alignment.Top,
            ),
        exit = fadeOut(tween(Motion.Exit, easing = Motion.EmphasizedAccelerate)) +
            shrinkVertically(
                animationSpec = tween(Motion.Exit, easing = Motion.EmphasizedAccelerate),
                shrinkTowards = Alignment.Top,
            ),
    ) {
        val start = if (status.connectedAt > 0) status.connectedAt else lastStart
        Column {
            Spacer(Modifier.height(18.dp))
            // The readout is the instrument panel: what the session is, how
            // long it has run, then the three measurements. Labels exist
            // because a bare "13 ms" beside "15.7 KB/s" is guesswork.
            Text(
                "Session",
                style = BenchType.Caption,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                formatUptime(tick - start),
                style = BenchType.Stat,
                color = scheme.onBackground,
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.height(14.dp))
            BenchDivider()
            Spacer(Modifier.height(12.dp))
            BenchReadout(
                metrics = listOf(
                    "Latency" to (pingMs?.let { "$it ms" } ?: "\u2014"),
                    "Down" to formatSpeed(rxBytes),
                    "Up" to formatSpeed(txBytes),
                ),
            )
        }
    }
}

@Composable
private fun DevicesSection(
    connected: Boolean,
    reconnecting: Boolean,
    onOpenSettings: () -> Unit,
    onBrowsePeer: (ClusterPeer) -> Unit,
) {
    val peers by RelayManager.peers.collectAsStateWithLifecycle()
    val link by RelayManager.link.collectAsStateWithLifecycle()
    val onlineCount = remember(peers) { peers.count { !it.isSelf } }
    Column(Modifier.animateContentSize(Motion.spatialFast())) {
        BenchSectionHeader(
            title = "Devices",
            trailing = when {
                !connected -> null
                link == RelayLinkPhase.ONLINE -> "$onlineCount online"
                link == RelayLinkPhase.AUTH_FAILED -> "Key rejected"
                link == RelayLinkPhase.UNREACHABLE -> "Relay unreachable"
                link == RelayLinkPhase.CONNECTING -> "Connecting"
                else -> null
            },
        )
        AnimatedContent(
            targetState = connected,
            transitionSpec = {
                fadeIn(tween(Motion.Enter, easing = Motion.EmphasizedDecelerate))
                    .togetherWith(
                        fadeOut(tween(Motion.Exit, easing = Motion.EmphasizedAccelerate))
                    )
            },
            label = "devices_state",
        ) { live ->
            if (live) {
                ClusterSharingSection(
                    onOpenSettings = onOpenSettings,
                    onBrowsePeer = onBrowsePeer,
                    embedded = true,
                    showHeader = false,
                    relayLink = link,
                )
            } else {
                Text(
                    if (reconnecting) {
                        "Reconnecting. Devices return when the tunnel is back."
                    } else {
                        "Connect to see your other devices."
                    },
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ActivitySection(
    compact: Boolean,
    onOpenHistory: () -> Unit,
) {
    val context = LocalContext.current
    val historyFlow = remember(context) { TransferHistoryRepository.observe(context) }
    val history by historyFlow.collectAsStateWithLifecycle(initialValue = null)
    val entries = remember(history, compact) {
        history.orEmpty().take(if (compact) 2 else 6)
    }
    val loading = history == null
    val scheme = MaterialTheme.colorScheme
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val motionEnabled = rememberMotionEnabled()
    Column {
        BenchSectionHeader(
            title = "Activity",
            trailing = "History",
            trailingClickLabel = "Open history",
            onTrailingClick = onOpenHistory,
        )
        when {
            loading -> {
                // Placeholder rows mirror the real rows' shape, so the list
                // lands without a jump when history loads.
                SkeletonHost {
                    val skeletonRows = if (compact) 2 else 3
                    Column {
                        repeat(skeletonRows) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SkeletonBox(
                                    modifier = Modifier.size(28.dp),
                                    shape = CircleShape,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    SkeletonLine(0.62f, height = 12)
                                    SkeletonLine(0.40f, height = 10)
                                }
                            }
                        }
                    }
                }
            }
            entries.isEmpty() -> {
                Text(
                    "Nothing sent or received yet.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
            else -> {
                entries.forEachIndexed { index, entry ->
                    key(entry.id) {
                        Reveal(0, motionEnabled) {
                            ActivityRow(entry, formatter, onOpenHistory)
                        }
                    }
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
    // Amber is the live accent, so it belongs to a transfer that is running
    // now. A finished transfer is history: it states its outcome in words and
    // stays quiet. Only failure earns colour at rest.
    val statusColor by animateColorAsState(
        targetValue = when (entry.status) {
            "active" -> scheme.primary
            "failed" -> scheme.error
            else -> scheme.onSurfaceVariant
        },
        animationSpec = Motion.base(),
        label = "activity_status",
    )
    val statusLabel = when (entry.status) {
        "completed" -> "Done"
        "failed" -> "Failed"
        "active" -> "In progress"
        else -> entry.status
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .benchClickable(onClickLabel = "Open history", onClick = onOpenHistory)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
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
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.label,
                style = BenchType.Body,
                fontWeight = FontWeight.Medium,
                color = scheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${entry.peerName} \u00B7 ${formatter.format(Date(entry.timestamp))}",
                style = BenchType.Caption,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        AnimatedContent(
            targetState = statusLabel,
            transitionSpec = {
                fadeIn(tween(Motion.Quick, easing = Motion.EmphasizedDecelerate))
                    .togetherWith(fadeOut(tween(Motion.Instant, easing = Motion.EmphasizedAccelerate)))
            },
            label = "activity_status_label",
        ) { label ->
            Text(
                label,
                style = BenchType.Caption,
                fontWeight = FontWeight.Medium,
                color = statusColor,
                maxLines = 1,
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

/** Falls back to the address when a server was never given a name. */
private fun AccessKey?.resolveName(): String? =
    this?.name?.takeIf { it.isNotBlank() } ?: this?.displayAddress()

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
