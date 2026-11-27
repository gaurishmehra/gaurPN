package dev.gaurish.outlinelite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
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
    sort: BrowseSort = BrowseSort.NAME,
    ascending: Boolean = true,
    onSortChange: (BrowseSort, Boolean) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // The peer passed in is a snapshot; BrowseTopBar resolves the live entry
    // itself so relay status broadcasts do not recompose the file list.
    val stack = rememberSaveable(
        saver = listSaver(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { mutableStateListOf("") }
    val listings = remember { mutableStateMapOf<String, ListingState>() }
    val selected = remember { mutableStateMapOf<String, BrowseEntry>() }
    var selectMode by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }
    var fetchRequestId by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableIntStateOf(0) }

    val path = stack.last()

    // One scroll state for the listing. Reordering or changing folder must put
    // the reader back at the top: keeping the pixel offset after a sort leaves
    // the new first row half-hidden under the header.
    val listState = rememberLazyListState()
    LaunchedEffect(path, sort, ascending) {
        listState.scrollToItem(0)
    }

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
                peer = peer,
                path = path,
                selectMode = selectMode,
                sort = sort,
                ascending = ascending,
                onSortChange = onSortChange,
                onBack = onBack,
                onBreadcrumb = ::navigateTo,
                onToggleSelectMode = { selectMode = !selectMode },
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = selected.isNotEmpty(),
                // Size snaps so the file list is never re-measured frame by
                // frame; the fade is what the eye reads as the bar arriving.
                enter = fadeIn(tween(Motion.Base, easing = Motion.EmphasizedDecelerate)) +
                    expandVertically(animationSpec = tween(0), expandFrom = Alignment.Bottom),
                exit = fadeOut(tween(Motion.Quick, easing = Motion.EmphasizedAccelerate)) +
                    shrinkVertically(animationSpec = tween(0), shrinkTowards = Alignment.Bottom),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    BenchDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${selected.size} selected",
                                style = BenchType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                formatBytes(selectedBytes),
                                style = BenchType.Data,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        TextButton(
                            onClick = {
                                selected.clear()
                                selectMode = false
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Clear", style = BenchType.Caption)
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
                                if (fetching) "Requesting" else "Download",
                                style = BenchType.Caption,
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
            contentAlignment = Alignment.TopCenter,
        ) {
            AnimatedContent(
                targetState = path,
                transitionSpec = {
                    val goingDeeper =
                        targetState.count { it == '/' } > initialState.count { it == '/' }
                    val direction = if (goingDeeper) 1 else -1
                    (
                        fadeIn(tween(Motion.Enter, easing = Motion.EmphasizedDecelerate)) +
                            slideInHorizontally(
                                animationSpec = tween(
                                    Motion.Screen,
                                    easing = Motion.EmphasizedDecelerate,
                                ),
                                initialOffsetX = { width -> direction * (width / 8) },
                            )
                        ).togetherWith(
                        fadeOut(tween(Motion.Exit, easing = Motion.EmphasizedAccelerate)) +
                            slideOutHorizontally(
                                animationSpec = tween(
                                    Motion.Exit,
                                    easing = Motion.EmphasizedAccelerate,
                                ),
                                targetOffsetX = { width -> -direction * (width / 24) },
                            ),
                    )
                },
                label = "browse_path",
                modifier = Modifier
                    .widthIn(max = 900.dp)
                    .fillMaxSize(),
            ) { shownPath ->
                val shown = listings[shownPath]
                AnimatedContent(
                    targetState = shown,
                    contentKey = { listing -> listing?.let { it::class } },
                    transitionSpec = {
                        fadeIn(tween(Motion.Base, easing = Motion.EmphasizedDecelerate))
                            .togetherWith(
                                fadeOut(tween(Motion.Quick, easing = Motion.EmphasizedAccelerate))
                            )
                    },
                    label = "browse_listing",
                    modifier = Modifier.fillMaxSize(),
                ) { current ->
                    when (current) {
                        null, ListingState.Loading -> BrowseLoading()
                        is ListingState.Failed -> BrowseErrorState(
                            message = current.message,
                            onRetry = {
                                listings.remove(shownPath)
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
                                val sortedEntries = remember(current.entries, sort, ascending) {
                                    sortBrowseEntries(current.entries, sort, ascending)
                                }
                                val keys = remember(sortedEntries) {
                                    browseEntryKeys(sortedEntries)
                                }
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(
                                        horizontal = 12.dp,
                                        vertical = 8.dp,
                                    ),
                                ) {
                                    itemsIndexed(
                                        items = sortedEntries,
                                        key = { index, _ -> keys[index] },
                                    ) { index, entry ->
                                        val entryPath = if (shownPath.isEmpty()) {
                                            entry.name
                                        } else {
                                            "$shownPath/${entry.name}"
                                        }
                                        EntryRow(
                                            entry = entry,
                                            selected = selected.containsKey(entryPath),
                                            selectMode = selectMode,
                                            showDivider = index != sortedEntries.lastIndex,
                                            onClick = {
                                                when {
                                                    entry.dir -> {
                                                        if (stack.last() != entryPath) {
                                                            stack.add(entryPath)
                                                        }
                                                    }
                                                    else -> {
                                                        if (!selectMode) selectMode = true
                                                        toggleSelection(entryPath, entry)
                                                    }
                                                }
                                            },
                                            onLongClick = {
                                                if (!entry.dir) {
                                                    selectMode = true
                                                    toggleSelection(entryPath, entry)
                                                }
                                            },
                                            onToggle = { toggleSelection(entryPath, entry) },
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
                                    if (current.truncated) {
                                        item {
                                            Text(
                                                "Showing the first " +
                                                    "${RequestCodec.MAX_LISTING_ENTRIES} entries",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(
                                                    horizontal = 10.dp,
                                                    vertical = 12.dp,
                                                ),
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
    selectMode: Boolean,
    sort: BrowseSort,
    ascending: Boolean,
    onSortChange: (BrowseSort, Boolean) -> Unit,
    onBack: () -> Unit,
    onBreadcrumb: (Int) -> Unit,
    onToggleSelectMode: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val peers by RelayManager.peers.collectAsStateWithLifecycle()
    val livePeer = peers.firstOrNull { it.id == peer.id } ?: peer
    val segments = if (path.isEmpty()) emptyList() else path.split('/')
    // You are looking at a folder, so the folder is the title. The device is
    // context, and it reads as the first crumb: Lilac / Documents / Projects.
    val folderName = segments.lastOrNull() ?: "Home"
    val ancestors = segments.dropLast(1)

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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    folderName,
                    style = BenchType.Title,
                    color = scheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The chain is every level above the title, starting with the
                // device itself. Each one is tappable, so root is always one
                // tap away no matter how deep the folder is.
                val crumbs = listOf(livePeer.name) + ancestors
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (crumbs.size == 1) {
                        Text(
                            livePeer.name,
                            style = BenchType.Caption,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        crumbs.forEachIndexed { index, label ->
                            if (index > 0) {
                                Text(
                                    "/",
                                    style = BenchType.Caption,
                                    color = scheme.outline,
                                    modifier = Modifier.padding(horizontal = 2.dp),
                                )
                            }
                            BreadcrumbSegment(
                                label = label,
                                onClick = { onBreadcrumb(index) },
                            )
                        }
                    }
                }
            }
            SortMenu(
                sort = sort,
                ascending = ascending,
                onSortChange = onSortChange,
            )
            TextButton(
                onClick = onToggleSelectMode,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    if (selectMode) "Done" else "Select",
                    style = BenchType.Caption,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * One crumb. Ancestors are quiet and tappable; the level you are standing on
 * is plain text, not a control.
 */
@Composable
private fun BreadcrumbSegment(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = BenchType.Caption,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = "Open $label", onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
private fun SortMenu(
    sort: BrowseSort,
    ascending: Boolean,
    onSortChange: (BrowseSort, Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_sort),
                contentDescription = "Sort files",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortOption(
                label = "Name \u00B7 A\u2192Z",
                selected = sort == BrowseSort.NAME && ascending,
            ) {
                onSortChange(BrowseSort.NAME, true)
                open = false
            }
            SortOption(
                label = "Name \u00B7 Z\u2192A",
                selected = sort == BrowseSort.NAME && !ascending,
            ) {
                onSortChange(BrowseSort.NAME, false)
                open = false
            }
            SortOption(
                label = "Modified \u00B7 newest",
                selected = sort == BrowseSort.MODIFIED && !ascending,
            ) {
                onSortChange(BrowseSort.MODIFIED, false)
                open = false
            }
            SortOption(
                label = "Modified \u00B7 oldest",
                selected = sort == BrowseSort.MODIFIED && ascending,
            ) {
                onSortChange(BrowseSort.MODIFIED, true)
                open = false
            }
            SortOption(
                label = "Size \u00B7 largest",
                selected = sort == BrowseSort.SIZE && !ascending,
            ) {
                onSortChange(BrowseSort.SIZE, false)
                open = false
            }
            SortOption(
                label = "Size \u00B7 smallest",
                selected = sort == BrowseSort.SIZE && ascending,
            ) {
                onSortChange(BrowseSort.SIZE, true)
                open = false
            }
        }
    }
}

@Composable
private fun SortOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label, fontSize = 14.sp) },
        onClick = onClick,
        leadingIcon = {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Transparent
                        }
                    ),
            )
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: BrowseEntry,
    selected: Boolean,
    selectMode: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    // Colour is read in the draw phase: a selection tap is the most repeated
    // gesture on this screen and must not recompose the row.
    val background by animateColorAsState(
        targetValue = if (selected) scheme.primary.copy(alpha = 0.12f) else Color.Transparent,
        animationSpec = Motion.base(),
        label = "entry_selection",
    )
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .pressScale(interaction, pressedScale = 0.98f)
                .drawBehind { drawRect(background) }
                .combinedClickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onClickLabel = if (entry.dir) "Open ${entry.name}" else null,
                    onClick = onClick,
                    onLongClick = {
                        haptics.select()
                        onLongClick()
                    },
                )
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EntryGlyph(entry)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = BenchType.Body,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = entrySubtitle(entry)
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = BenchType.Caption,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when {
                selectMode && !entry.dir -> Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggle() },
                )
                entry.dir -> Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = scheme.outline,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (showDivider) {
            BenchDivider(Modifier.padding(start = 62.dp, end = 8.dp))
        }
    }
}

/**
 * Folders get a quiet outline glyph and files get a type badge. Folders are
 * the common case, so they stay neutral: twenty-four amber folder icons turn
 * the accent into wallpaper and leave nothing to say "this is live".
 */
@Composable
private fun EntryGlyph(entry: BrowseEntry) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        if (entry.dir) {
            Icon(
                painter = painterResource(R.drawable.ic_folder),
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        } else {
            val kind = fileKind(entry.name)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(scheme.surfaceContainerHigh)
                    .border(1.dp, scheme.outlineVariant, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    kind.badge,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = fileKindTint(kind),
                    letterSpacing = 0.2.sp,
                )
            }
        }
    }
}

/** Folders say nothing a second time: the glyph already means folder. */
private fun entrySubtitle(entry: BrowseEntry): String {
    if (entry.dir) return ""
    val date = formatModified(entry.modified)
    return if (date.isEmpty()) formatBytes(entry.size) else "${formatBytes(entry.size)} \u00B7 $date"
}

@Composable
private fun BrowseLoading() {
    SkeletonHost {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            repeat(9) {
                Row(
                    modifier = Modifier.height(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SkeletonBox(
                        modifier = Modifier.size(40.dp),
                        shape = MaterialTheme.shapes.medium,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        SkeletonLine(widthFraction = 0.62f, height = 12)
                        Spacer(Modifier.height(6.dp))
                        SkeletonLine(widthFraction = 0.34f, height = 9)
                    }
                }
            }
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
                style = BenchType.Section,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "This folder is empty, or the phone hasn't granted access to it.",
                style = BenchType.Body,
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
                style = BenchType.Body,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Try again", style = BenchType.Caption, fontWeight = FontWeight.Medium)
            }
        }
    }
}
