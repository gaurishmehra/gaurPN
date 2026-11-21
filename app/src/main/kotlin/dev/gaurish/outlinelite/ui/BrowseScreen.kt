package dev.gaurish.outlinelite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.transfer.BrowseEntry
import dev.gaurish.outlinelite.transfer.ClusterPeer
import dev.gaurish.outlinelite.transfer.RelayManager
import dev.gaurish.outlinelite.transfer.RequestCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Full-screen browser for a peer's shared collections. The requester drives
 * it: each directory is one sealed `browse` round trip, and the download
 * button sends one sealed `fetch` whose bytes arrive through the ordinary
 * transfer flow (trust-gated offer, progress notification, resume).
 *
 * State is in-memory only: the path stack, the per-path listing cache, and
 * the selection map. Nothing is persisted and no timer runs; the only
 * background work is one request per directory the user opens.
 */
@Composable
fun BrowseScreen(
    peer: ClusterPeer,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val peers by RelayManager.peers.collectAsStateWithLifecycle()
    // The peer passed in is a snapshot; prefer the live entry so the battery
    // in the top bar follows the relay's status broadcasts.
    val livePeer = peers.firstOrNull { it.id == peer.id } ?: peer
    val stack = remember { mutableStateListOf("") }
    val listings = remember { mutableStateMapOf<String, ListingState>() }
    val selected = remember { mutableStateMapOf<String, BrowseEntry>() }
    var selectMode by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }
    var fetchRequestId by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableIntStateOf(0) }

    val path = stack.last()
    val state = listings[path]

    // One listing request per directory the user opens. A cached listing is
    // reused when navigating back; the retry button drops the cache entry
    // and bumps the tick to re-run this effect. A cancelled request (the user
    // left the directory) drops its placeholder so the next visit retries.
    LaunchedEffect(path, retryTick) {
        if (listings[path] != null) return@LaunchedEffect
        listings[path] = ListingState.Loading
        try {
            val reply = RelayManager.browseListing(context, peer, path)
            listings[path] = when {
                reply == null -> ListingState.Failed("Device didn't respond")
                reply.error != null -> ListingState.Failed(browseErrorMessage(reply.error))
                else -> ListingState.Ready(reply.entries, reply.truncated)
            }
        } catch (c: CancellationException) {
            listings.remove(path)
            throw c
        }
    }

    LaunchedEffect(Unit) {
        RelayManager.browseDeclines.collect { decline ->
            if (decline.requestId == fetchRequestId) {
                fetching = false
                snackbar.showSnackbar(declineMessage(decline.reason))
            }
        }
    }

    BackHandler {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack()
    }

    fun navigateTo(depth: Int) {
        while (stack.size > depth + 1) stack.removeAt(stack.lastIndex)
    }

    fun toggleSelection(entryPath: String, entry: BrowseEntry) {
        if (selected.remove(entryPath) != null) return
        if (selected.size >= RequestCodec.MAX_FETCH_PATHS) {
            scope.launch {
                snackbar.showSnackbar("Up to ${RequestCodec.MAX_FETCH_PATHS} files at a time")
            }
            return
        }
        selected[entryPath] = entry
    }

    fun startFetch() {
        if (fetching || selected.isEmpty()) return
        val paths = selected.keys.toList()
        fetching = true
        scope.launch {
            val requestId = RelayManager.requestFetch(context, peer, paths)
            fetching = false
            if (requestId == null) {
                snackbar.showSnackbar("Not connected. The download did not start.")
            } else {
                fetchRequestId = requestId
                selected.clear()
                selectMode = false
                snackbar.showSnackbar("Download requested")
            }
        }
    }

    val selectedBytes = selected.values.sumOf { it.size }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            BrowseTopBar(
                peer = livePeer,
                path = path,
                canGoUp = stack.size > 1,
                selectMode = selectMode,
                onBack = onBack,
                onUp = { if (stack.size > 1) navigateTo(stack.size - 2) },
                onBreadcrumb = ::navigateTo,
                onToggleSelectMode = { selectMode = !selectMode },
            )
        },
        bottomBar = {
            AnimatedVisibility(visible = selected.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    shadowElevation = 10.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${selected.size} selected",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                formatBytes(selectedBytes),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        TextButton(
                            onClick = {
                                selected.clear()
                                selectMode = false
                            },
                        ) {
                            Text("Clear selection", fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(6.dp))
                        Button(
                            onClick = ::startFetch,
                            enabled = !fetching,
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Text(
                                if (fetching) {
                                    "Requesting"
                                } else {
                                    "Download (${selected.size}) · ${formatBytes(selectedBytes)}"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val current = state) {
                null, ListingState.Loading -> BrowseLoading()
                is ListingState.Failed -> BrowseErrorState(
                    message = current.message,
                    onRetry = {
                        listings.remove(path)
                        retryTick++
                    },
                )
                is ListingState.Ready -> {
                    if (current.entries.isEmpty()) {
                        BrowseEmpty()
                    } else {
                        // The codec already dedupes (name, dir); the helper
                        // adds an index suffix if a collision still slips
                        // through, because a duplicate key crashes Compose.
                        val keys = remember(current.entries) {
                            browseEntryKeys(current.entries)
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            itemsIndexed(
                                items = current.entries,
                                key = { index, _ -> keys[index] },
                            ) { _, entry ->
                                val entryPath = if (path.isEmpty()) {
                                    entry.name
                                } else {
                                    "$path/${entry.name}"
                                }
                                EntryRow(
                                    entry = entry,
                                    selected = selected.containsKey(entryPath),
                                    selectMode = selectMode,
                                    onClick = {
                                        when {
                                            entry.dir -> {
                                                if (stack.last() != entryPath) stack.add(entryPath)
                                            }
                                            selectMode -> toggleSelection(entryPath, entry)
                                        }
                                    },
                                    onLongClick = {
                                        if (!entry.dir) {
                                            selectMode = true
                                            toggleSelection(entryPath, entry)
                                        }
                                    },
                                    onToggle = { toggleSelection(entryPath, entry) },
                                )
                            }
                            if (current.truncated) {
                                item {
                                    Text(
                                        "Showing the first ${RequestCodec.MAX_LISTING_ENTRIES} entries",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface ListingState {
    data object Loading : ListingState
    data class Ready(val entries: List<BrowseEntry>, val truncated: Boolean) : ListingState
    data class Failed(val message: String) : ListingState
}

@Composable
private fun BrowseTopBar(
    peer: ClusterPeer,
    path: String,
    canGoUp: Boolean,
    selectMode: Boolean,
    onBack: () -> Unit,
    onUp: () -> Unit,
    onBreadcrumb: (Int) -> Unit,
    onToggleSelectMode: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val segments = if (path.isEmpty()) emptyList() else path.split('/')

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.background)
            .statusBarsPadding()
            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = "Back",
                    tint = scheme.onBackground,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        peer.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    peer.battery?.let { battery ->
                        Spacer(Modifier.width(6.dp))
                        Text(
                            buildString {
                                append(battery)
                                append('%')
                                if (peer.charging == true) append(" \u26A1")
                            },
                            fontSize = 11.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BreadcrumbSegment(
                        label = "Home",
                        enabled = path.isNotEmpty(),
                        onClick = { onBreadcrumb(0) },
                    )
                    segments.forEachIndexed { index, segment ->
                        Text(
                            "/",
                            fontSize = 11.sp,
                            color = scheme.outline,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        BreadcrumbSegment(
                            label = segment,
                            enabled = index < segments.lastIndex,
                            onClick = { onBreadcrumb(index + 1) },
                        )
                    }
                }
            }
            TextButton(onClick = onToggleSelectMode) {
                Text(if (selectMode) "Done" else "Select", fontSize = 12.sp)
            }
        }
        Row(
            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onUp, enabled = canGoUp) {
                Text("Up", fontSize = 12.sp)
            }
            Text(
                if (path.isEmpty()) "Choose a folder" else path,
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun BreadcrumbSegment(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        fontSize = 12.sp,
        fontWeight = if (enabled) FontWeight.Medium else FontWeight.SemiBold,
        color = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 2.dp, vertical = 2.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: BrowseEntry,
    selected: Boolean,
    selectMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) scheme.primary.copy(alpha = 0.10f) else Color.Transparent,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.dir) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(scheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_folder),
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            val kind = fileKind(entry.name)
            val tint = fileKindColor(kind)
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    kind.badge,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = tint,
                    letterSpacing = 0.4.sp,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                entrySubtitle(entry),
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        when {
            selectMode && !entry.dir -> Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
            )
            entry.dir -> Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun entrySubtitle(entry: BrowseEntry): String {
    if (entry.dir) return "Folder"
    val date = formatModified(entry.modified)
    return if (date.isEmpty()) formatBytes(entry.size) else "${formatBytes(entry.size)} · $date"
}

private fun fileKindColor(kind: FileKind): Color = when (kind) {
    FileKind.IMAGE -> Color(0xFF5BD9A3)
    FileKind.VIDEO -> Color(0xFF7FB2F0)
    FileKind.AUDIO -> Color(0xFFC9A0FF)
    FileKind.DOCUMENT -> Color(0xFFE8C877)
    FileKind.ARCHIVE -> Color(0xFFF09A7A)
    FileKind.OTHER -> Color(0xFF9AA6B2)
}

@Composable
private fun BrowseLoading() {
    val scheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "browse_loading")
    val alpha by transition.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "browse_loading_alpha",
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = scheme.primary,
            trackColor = scheme.outlineVariant,
        )
        Spacer(Modifier.height(16.dp))
        repeat(7) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(scheme.surfaceVariant.copy(alpha = alpha)),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.62f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(scheme.surfaceVariant.copy(alpha = alpha)),
                    )
                    Spacer(Modifier.height(7.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.34f)
                            .height(9.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(scheme.surfaceVariant.copy(alpha = alpha * 0.8f)),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun BrowseEmpty() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Nothing here",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "This folder is empty, or the phone hasn't granted access to it.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun BrowseErrorState(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                message,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = onRetry) {
                Text("Try again", fontSize = 13.sp)
            }
        }
    }
}
