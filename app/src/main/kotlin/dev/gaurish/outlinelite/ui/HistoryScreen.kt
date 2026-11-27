package dev.gaurish.outlinelite.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.data.TransferHistoryEntry
import dev.gaurish.outlinelite.data.TransferHistoryRepository
import dev.gaurish.outlinelite.transfer.ClusterPeer
import dev.gaurish.outlinelite.transfer.RelayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Three distinct states: a null flow read is not the same as an empty list. */
private enum class HistoryPhase { Pending, Loading, Empty, List }

/**
 * Local transfer history. Received files can be reopened, failed sends can be
 * retried, and text/link shares can be copied or resent. Nothing on this
 * screen is synced to the relay or other devices.
 */
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val historyFlow = remember(context) { TransferHistoryRepository.observe(context) }
    val history by historyFlow.collectAsStateWithLifecycle(initialValue = null)
    val entries = history.orEmpty()
    val loading = history == null
    var confirmClear by remember { mutableStateOf(false) }
    // A local read usually lands inside a frame or two. Showing a skeleton for
    // 40 ms is a flash, not a loading state, so the placeholders wait for a
    // delay long enough to mean "this is taking a moment".
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        if (!loading) {
            slow = false
            return@LaunchedEffect
        }
        delay(Motion.Quick.toLong())
        slow = true
    }
    val timeFormatter = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
    Column(
        Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .fillMaxHeight()
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
                "Transfer history",
                style = BenchType.Title,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            AnimatedVisibility(
                visible = !loading && entries.isNotEmpty(),
                enter = fadeIn(tween(Motion.Base)) +
                    expandHorizontally(
                        animationSpec = tween(Motion.Base, easing = Motion.EmphasizedDecelerate),
                    ),
                exit = fadeOut(tween(Motion.Quick)) +
                    shrinkHorizontally(
                        animationSpec = tween(Motion.Quick, easing = Motion.EmphasizedAccelerate),
                    ),
            ) {
                // Destructive, so it stays quiet at rest: the accent belongs
                // to live state, and the dialog carries the red.
                TextButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text("Clear", style = BenchType.Caption, fontWeight = FontWeight.Medium)
                }
            }
        }

        AnimatedContent(
            targetState = when {
                loading && !slow -> HistoryPhase.Pending
                loading -> HistoryPhase.Loading
                entries.isEmpty() -> HistoryPhase.Empty
                else -> HistoryPhase.List
            },
            transitionSpec = {
                fadeIn(tween(Motion.Base, easing = Motion.EmphasizedDecelerate))
                    .togetherWith(fadeOut(tween(Motion.Quick, easing = Motion.EmphasizedAccelerate)))
            },
            label = "history_state",
        ) { phase ->
            when (phase) {
                HistoryPhase.Pending -> Spacer(Modifier.fillMaxSize())
                HistoryPhase.Loading -> HistorySkeleton()
                HistoryPhase.Empty -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Nothing sent or received yet",
                                style = BenchType.Section,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Files you share appear here. Received files land in Downloads.",
                                style = BenchType.Body,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                HistoryPhase.List -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 30.dp),
                    ) {
                        items(entries, key = { it.id }) { entry ->
                            HistoryRow(
                                entry = entry,
                                context = context,
                                scope = scope,
                                timeFormatter = timeFormatter,
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(
                                        Motion.Base,
                                        easing = Motion.EmphasizedDecelerate,
                                    ),
                                    placementSpec = Motion.spatial(),
                                    fadeOutSpec = tween(
                                        Motion.Exit,
                                        easing = Motion.EmphasizedAccelerate,
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = {
                Text(
                    "This removes every local transfer record. Files already on this phone are not deleted.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        scope.launch { TransferHistoryRepository.clear(context) }
                    },
                ) {
                    Text("Clear history", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun HistorySkeleton() {
    SkeletonHost {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Same pitch and same lead glyph as a real row, so the list does
            // not grow when the read lands.
            repeat(8) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.shapes.large,
                        )
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SkeletonBox(Modifier.size(32.dp), shape = CircleShape)
                    Spacer(Modifier.width(12.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SkeletonLine(widthFraction = 0.58f, height = 12)
                        SkeletonLine(widthFraction = 0.36f, height = 9)
                    }
                }
            }
        }
    }
}

/**
 * One entry, one row. The status is a coloured span inside the caption rather
 * than a badge, and the action is a single trailing control, so a page of
 * history reads as a log instead of a stack of cards each shouting "Done".
 */
@Composable
private fun HistoryRow(
    entry: TransferHistoryEntry,
    context: Context,
    scope: CoroutineScope,
    timeFormatter: SimpleDateFormat,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val directionLabel = if (entry.direction == "sent") "Sent" else "Received"
    // Amber means live, so a finished transfer states its outcome in words and
    // leaves the accent to the transfer that is actually running.
    val statusColor = when (entry.status) {
        "active" -> scheme.primary
        "failed" -> scheme.error
        else -> scheme.onSurfaceVariant
    }
    val statusWord = when (entry.status) {
        "completed" -> "Done"
        else -> entry.status.replaceFirstChar { it.uppercase() }
    }
    val meta = buildAnnotatedString {
        append(directionLabel)
        append(' ')
        append(entry.kind)
        if (entry.peerName.isNotBlank()) {
            append(" \u00B7 ")
            append(entry.peerName)
        }
        val time = formatTime(timeFormatter, entry.timestamp)
        if (time.isNotEmpty()) {
            append(" \u00B7 ")
            append(time)
        }
        append(" \u00B7 ")
        pushStyle(SpanStyle(color = statusColor, fontWeight = FontWeight.Medium))
        append(statusWord)
        pop()
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(scheme.surface, MaterialTheme.shapes.large)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(scheme.onSurfaceVariant.copy(alpha = 0.10f)),
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
                    color = scheme.onSurface,
                    // Two lines: a truncated filename is unreadable, and the
                    // long ones are exactly the files people come back for.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    meta,
                    style = BenchType.Caption,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val actions = historyActions(entry)
            if (actions.size == 1) {
                Spacer(Modifier.width(4.dp))
                HistoryAction(
                    label = actions.first(),
                    onClick = {
                        haptics.select()
                        runHistoryAction(
                            action = actions.first(),
                            context = context,
                            entry = entry,
                            scope = scope,
                        )
                    },
                )
            }
        }
        if (historyActions(entry).size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                historyActions(entry).forEach { label ->
                    HistoryAction(
                        label = label,
                        onClick = {
                            haptics.select()
                            runHistoryAction(
                                action = label,
                                context = context,
                                entry = entry,
                                scope = scope,
                            )
                        },
                    )
                }
            }
        }
    }
}

/** What this entry can still do. Labels are the control names. */
private fun historyActions(entry: TransferHistoryEntry): List<String> = when {
    entry.direction == "received" && entry.kind == "file" && entry.localPath != null ->
        listOf("Open")
    entry.direction == "sent" && entry.kind == "file" && entry.sourceUri != null ->
        listOf(if (entry.status == "failed") "Retry" else "Send again")
    entry.kind != "file" && entry.text != null ->
        listOf("Copy", if (entry.status == "failed") "Retry" else "Send again")
    else -> emptyList()
}

@Composable
private fun HistoryAction(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Text(label, style = BenchType.Caption, fontWeight = FontWeight.Medium)
    }
}

private fun runHistoryAction(
    action: String,
    context: Context,
    entry: TransferHistoryEntry,
    scope: CoroutineScope,
) {
    when (action) {
        "Open" -> openReceived(context, entry)
        "Copy" -> entry.text?.let { copyText(context, it) }
        else -> {
            val peer = ClusterPeer(entry.peerId, entry.peerName)
            if (entry.kind == "file" && entry.sourceUri != null) {
                scope.launch {
                    RelayManager.sendFiles(context, peer, listOf(Uri.parse(entry.sourceUri)))
                }
            } else if (entry.text != null) {
                scope.launch { RelayManager.sendText(context, peer, entry.text) }
            }
        }
    }
}

private fun openReceived(context: Context, entry: TransferHistoryEntry) {
    val path = entry.localPath ?: return
    val file = File(path)
    if (!file.exists()) {
        Toast.makeText(context, "File is no longer in Downloads", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val mime = context.contentResolver.getType(uri) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("gaurPN", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

private fun formatTime(formatter: SimpleDateFormat, timestamp: Long): String {
    if (timestamp <= 0L) return ""
    return formatter.format(Date(timestamp))
}
