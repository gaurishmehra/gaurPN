package dev.gaurish.outlinelite.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.transfer.CameraLens
import dev.gaurish.outlinelite.transfer.CameraTier
import dev.gaurish.outlinelite.transfer.CameraGeometry
import dev.gaurish.outlinelite.transfer.CameraSession
import dev.gaurish.outlinelite.transfer.CameraSessionPhase
import dev.gaurish.outlinelite.transfer.CameraSessionState
import dev.gaurish.outlinelite.transfer.CameraStreamService
import dev.gaurish.outlinelite.transfer.RelayManager

/** Pinned labels for the quality segmented control; the wire words never appear in the UI. */
private val tierLabels = listOf("High", "Balanced", "Low")

/**
 * The live camera session surface: the accept card while a request is
 * waiting, the local preview plus controls while streaming, and a short
 * explainer when neither. The screen is a window onto [CameraSessionState]
 * — every control here routes through the same service functions the
 * desktop's `cam_control` does, so the wire and the screen can never
 * disagree about the session.
 */
@Composable
fun CameraPreviewScreen(onBack: () -> Unit) {
    val session by CameraSessionState.session.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CameraPreviewTopBar(onBack = onBack) },
    ) { padding ->
        AnimatedContent(
            // Keyed on the phase but carrying the whole session, so a phase
            // change animates the old panel out with its own state — not
            // with the just-reset defaults — while control updates change
            // the live panel in place.
            targetState = session,
            contentKey = { it.phase },
            // A phase change is an in-place state change: fade-through with
            // a small vertical slide, so the outgoing panel is gone before
            // the incoming one starts.
            transitionSpec = {
                (
                    fadeIn(
                        tween(Motion.Base, delayMillis = 110, easing = Motion.EmphasizedDecelerate),
                    ) + slideInVertically(
                        animationSpec = tween(Motion.Base, delayMillis = 110, easing = Motion.EmphasizedDecelerate),
                        initialOffsetY = { it / 12 },
                    )
                    ).togetherWith(
                    fadeOut(tween(Motion.Quick, easing = Motion.EmphasizedAccelerate)) +
                        slideOutVertically(
                            animationSpec = tween(Motion.Quick, easing = Motion.EmphasizedAccelerate),
                            targetOffsetY = { -it / 12 },
                        ),
                )
            },
            label = "camera_phase",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) { state ->
            when (state.phase) {
                CameraSessionPhase.REQUEST_PENDING -> PendingCard()
                CameraSessionPhase.STREAMING -> StreamingPanel(session = state)
                CameraSessionPhase.IDLE -> IdlePanel()
            }
        }
    }
}

@Composable
private fun CameraPreviewTopBar(onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.background)
            .statusBarsPadding()
            .padding(start = 4.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = "Back",
                    tint = scheme.onBackground,
                )
            }
            Column {
                Text(
                    "Camera preview",
                    style = BenchType.Title,
                    color = scheme.onBackground,
                )
                Text(
                    "What a trusted device sees, and the controls for it",
                    style = BenchType.Caption,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

// -------------------------------------------------------------- pending

/**
 * The in-app twin of the accept prompt: names the requester and offers the
 * same Accept/Decline actions the notification does. When the prompt
 * registry is already empty (the request was answered or expired a beat
 * ago), the card says so instead of offering dead buttons.
 */
@Composable
private fun PendingCard() {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val pending by RelayManager.pendingCameraRequest.collectAsStateWithLifecycle()
    val name = pending?.requesterName ?: "A trusted device"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(scheme.surface)
                .border(1.dp, scheme.outlineVariant, MaterialTheme.shapes.medium)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_eye),
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        name,
                        style = BenchType.Section,
                        color = scheme.onSurface,
                    )
                    Text(
                        "wants to view your camera",
                        style = BenchType.Body,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            if (pending != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = {
                            haptics.tap()
                            RelayManager.declinePendingCameraRequest(context)
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Decline", style = BenchType.Label)
                    }
                    Button(
                        onClick = {
                            haptics.confirm()
                            RelayManager.acceptPendingCameraRequest(context)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = scheme.primary,
                            contentColor = scheme.onPrimary,
                        ),
                    ) {
                        Text(
                            "Accept",
                            style = BenchType.Label,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            } else {
                Text(
                    "This request was just answered or expired.",
                    style = BenchType.Caption,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "Accepting starts an end-to-end encrypted stream to this device. " +
                "The stream shows a notification with a Stop action the whole time.",
            style = BenchType.Body,
            color = scheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------- streaming

/**
 * The live panel: the local preview above, the controls below. Every
 * control is local — none of them sends anything to the desktop; the
 * stream simply follows.
 */
@Composable
private fun StreamingPanel(session: CameraSession) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_eye),
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Streaming to ${session.requesterName}",
                style = BenchType.Section,
                color = scheme.onSurface,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            CameraPreviewSurface(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.medium)
                    .border(1.dp, scheme.outlineVariant, MaterialTheme.shapes.medium),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surfaceContainerHigh)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ControlLabel("Stream quality")
            SegmentedControl(
                options = tierLabels,
                selectedIndex = session.tier.ordinal,
                onSelect = { index ->
                    CameraStreamService.setTier(CameraTier.entries[index])
                },
            )
            ControlLabel("Frame rate")
            SegmentedControl(
                options = listOf("30 fps", "60 fps"),
                selectedIndex = if (session.fps >= 60) 1 else 0,
                onSelect = { index ->
                    CameraStreamService.setFps(if (index == 1) 60 else 30)
                },
            )
            ControlLabel("Aspect")
            SegmentedControl(
                options = listOf("Fill (crop)", "Fit (bars)"),
                selectedIndex = if (session.aspect == CameraGeometry.CameraAspect.FIT) 1 else 0,
                onSelect = { index ->
                    CameraStreamService.setAspect(
                        if (index == 1) CameraGeometry.CameraAspect.FIT else CameraGeometry.CameraAspect.FILL,
                    )
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ControlTile(
                    caption = "Lens",
                    value = if (session.lens == CameraLens.FRONT) "Front" else "Back",
                    onClickLabel = "Flip lens",
                    onClick = { CameraStreamService.toggleLens() },
                    modifier = Modifier.weight(1f),
                )
                ControlTile(
                    caption = "Torch",
                    value = if (session.torchOn) "On" else "Off",
                    onClickLabel = "Toggle torch",
                    active = session.torchOn,
                    onClick = { CameraStreamService.setTorch(!session.torchOn) },
                    modifier = Modifier.weight(1f),
                )
                ControlTile(
                    caption = "Rotate",
                    value = "${session.rotationDeg}\u00B0",
                    onClickLabel = "Rotate 90 degrees",
                    onClick = { CameraStreamService.setRotation((session.rotationDeg + 90) % 360) },
                    modifier = Modifier.weight(1f),
                )
            }
            StopButton()
        }
    }
}

/**
 * The camera as the phone shows it to itself: a PreviewView bound by the
 * service as a second Preview use case. Devices that refuse PREVIEW+PREVIEW
 * show the placeholder instead; the stream itself never depends on this view.
 */
@Composable
private fun CameraPreviewSurface(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val previewView = remember { PreviewView(context) }
    // null while attaching, true when bound, false when the device refused.
    var attached by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(previewView) {
        attached = CameraStreamService.attachPreview(previewView)
    }
    DisposableEffect(previewView) {
        onDispose { CameraStreamService.detachPreview() }
    }

    Box(modifier) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
        if (attached == false) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scheme.surface)
                    .padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Local preview unavailable",
                    style = BenchType.Section,
                    color = scheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "This device cannot run a second preview surface next to " +
                        "the stream. The stream itself continues.",
                    style = BenchType.Body,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun StopButton() {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    Button(
        onClick = {
            haptics.tap()
            CameraStreamService.stop(context)
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = scheme.error,
            contentColor = scheme.onError,
        ),
    ) {
        Text(
            "Stop streaming",
            style = BenchType.Label,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** One small caption above a control group. */
@Composable
private fun ControlLabel(text: String) {
    Text(
        text,
        style = BenchType.Caption,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Pinned-option selector: one plane, every option visible, the selected one
 * marked by the container fill. The options never reorder, so the mark
 * moves and the words stay where the finger learned them.
 */
@Composable
private fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(scheme.surfaceContainerHighest),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(if (selected) scheme.primaryContainer else Color.Transparent)
                    .benchClickable(
                        onClickLabel = label,
                        role = Role.Button,
                    ) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = BenchType.Label,
                    color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One tappable measured value: a caption above a mono figure, the same form
 * the home readout uses. [active] spends the amber accent on a state that
 * is on right now (the torch).
 */
@Composable
private fun ControlTile(
    caption: String,
    value: String,
    onClickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(MaterialTheme.shapes.small)
            .background(scheme.surfaceContainerHighest)
            .benchClickable(
                onClickLabel = onClickLabel,
                role = Role.Button,
            ) { onClick() }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            caption,
            style = BenchType.Caption,
            color = scheme.onSurfaceVariant,
        )
        Text(
            value,
            style = BenchType.Data,
            color = if (active) scheme.primary else scheme.onSurface,
            modifier = Modifier.semantics { contentDescription = "$caption: $value" },
        )
    }
}

// ----------------------------------------------------------------- idle

/**
 * Nothing is live. A short explainer, and nothing else: this screen is a
 * status surface, and an idle state that offered actions would be lying.
 */
@Composable
private fun IdlePanel() {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_eye),
            contentDescription = null,
            tint = scheme.outline,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "No live camera stream",
            style = BenchType.Section,
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "When a trusted device asks to view your camera, the request " +
                "appears here and as a notification. Accept it on either " +
                "surface and this screen shows the live view with the " +
                "quality, frame rate, lens, torch, and rotation controls.",
            style = BenchType.Body,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
