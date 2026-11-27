package dev.gaurish.outlinelite.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.data.AccessKey
import dev.gaurish.outlinelite.data.SsKeyParser

/**
 * Server list. One row is one key: the whole row selects it, and the trailing
 * menu holds rename, copy, and delete. The active row is marked by a node and
 * the word Active, so selection does not depend on color alone.
 */
@Composable
fun ServersScreen(
    keys: List<AccessKey>,
    loading: Boolean,
    activeId: String?,
    onBack: () -> Unit,
    onAddKey: (AccessKey) -> Unit,
    onSelect: (AccessKey) -> Unit,
    onRename: (AccessKey, String) -> Unit,
    onDelete: (AccessKey) -> Unit,
    connected: Boolean = false,
    startInAdd: Boolean = false,
    onStartInAddConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    var keyToDelete by remember { mutableStateOf<AccessKey?>(null) }
    var keyToRename by remember { mutableStateOf<AccessKey?>(null) }
    val scheme = MaterialTheme.colorScheme

    // "Add server" on Home lands directly on the paste field instead of
    // making the user find the same button twice.
    LaunchedEffect(startInAdd) {
        if (startInAdd) {
            onStartInAddConsumed()
            showAdd = true
        }
    }

    val copyKey: (AccessKey) -> Unit = { key ->
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("Shadowsocks key", key.toSsUri())
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        manager?.setPrimaryClip(clip)
        Toast.makeText(context, "Key copied", Toast.LENGTH_SHORT).show()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(scheme.background)
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
            // 20 dp minus the icon button's own inset keeps "Servers" on the
            // same left edge as every other screen title.
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = "Back",
                    tint = scheme.onBackground,
                )
            }
            Text(
                "Servers",
                style = BenchType.Title,
                color = scheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showAdd = true }, modifier = Modifier.size(48.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = "Add server",
                    tint = scheme.onSurfaceVariant,
                )
            }
        }

        if (loading) {
            SkeletonHost {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    repeat(5) {
                        SkeletonBox(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp),
                            shape = MaterialTheme.shapes.medium,
                        )
                    }
                }
            }
        } else if (keys.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No servers yet.",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = scheme.onBackground,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Add an ss:// key to connect.",
                        fontSize = 13.sp,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { showAdd = true }) {
                        Text("Add server")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
            ) {
                itemsIndexed(keys, key = { _, key -> key.id }) { index, key ->
                    // Rounded ends on the first and last row keep the
                    // group-panel look while each row owns its own item
                    // identity, so deletion and reordering can animate.
                    val shape = when {
                        keys.size == 1 -> MaterialTheme.shapes.medium
                        index == 0 -> RoundedCornerShape(
                            topStart = 8.dp,
                            topEnd = 8.dp,
                            bottomStart = 0.dp,
                            bottomEnd = 0.dp,
                        )
                        index == keys.lastIndex -> RoundedCornerShape(
                            topStart = 0.dp,
                            topEnd = 0.dp,
                            bottomStart = 8.dp,
                            bottomEnd = 8.dp,
                        )
                        else -> RectangleShape
                    }
                    Column(
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = tween(
                                    Motion.Base,
                                    easing = Motion.EmphasizedDecelerate,
                                ),
                                placementSpec = Motion.spatial(),
                                fadeOutSpec = tween(
                                    Motion.Exit,
                                    easing = Motion.EmphasizedAccelerate,
                                ),
                            )
                            .clip(shape)
                            .background(scheme.surface),
                    ) {
                        ServerRow(
                            key = key,
                            selected = key.id == activeId,
                            onSelect = onSelect,
                            onRename = { keyToRename = key },
                            onShare = { copyKey(key) },
                            onDelete = { keyToDelete = key },
                        )
                        if (index != keys.lastIndex) {
                            HorizontalDivider(
                                color = scheme.outlineVariant,
                                thickness = 1.dp,
                                modifier = Modifier.padding(start = 20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    }

    AddKeyDialog(
        visible = showAdd,
        onDismiss = { showAdd = false },
        onAdd = { key ->
            showAdd = false
            onAddKey(key)
        },
    )

    keyToRename?.let { target ->
        RenameDialog(
            key = target,
            onDismiss = { keyToRename = null },
            onSave = { name ->
                keyToRename = null
                onRename(target, name)
            },
        )
    }

    keyToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { keyToDelete = null },
            title = { Text("Delete ${target.name.ifBlank { target.displayAddress() }}?") },
            text = {
                Text(
                    if (connected && target.id == activeId) {
                        "You're connected through this server. Deleting disconnects " +
                            "the VPN and removes the key from this device. You can add " +
                            "it again with its ss:// link."
                    } else {
                        "This removes the key from this device. You can add it again " +
                            "with its ss:// link."
                    },
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        keyToDelete = null
                        onDelete(target)
                    },
                ) {
                    Text("Delete", color = scheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { keyToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ServerRow(
    key: AccessKey,
    selected: Boolean,
    onSelect: (AccessKey) -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    var menuOpen by remember { mutableStateOf(false) }
    val background by animateColorAsState(
        targetValue = if (selected) scheme.primary.copy(alpha = 0.07f) else Color.Transparent,
        animationSpec = Motion.base(),
        label = "server_row_tint",
    )
    val dotColor by animateColorAsState(
        targetValue = if (selected) scheme.primary else scheme.outlineVariant,
        animationSpec = Motion.base(),
        label = "server_dot",
    )
    val dotScale = animateFloatAsState(
        targetValue = if (selected) 1.4f else 1f,
        animationSpec = Motion.spatialFast(),
        label = "server_dot_scale",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            // Drawn, not composed: animating this tint through `.background`
            // would recompose the row on every frame of the selection change.
            .drawBehind { drawRect(background) }
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.RadioButton,
                onClick = {
                    haptics.select()
                    onSelect(key)
                },
            )
            .heightIn(min = 60.dp)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .graphicsLayer {
                    scaleX = dotScale.value
                    scaleY = dotScale.value
                }
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                key.name.ifBlank { key.displayAddress() },
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                key.displayAddress(),
                style = BenchType.Data,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn(tween(Motion.Base)) +
                expandHorizontally(
                    animationSpec = tween(Motion.Base, easing = Motion.EmphasizedDecelerate),
                ),
            exit = fadeOut(tween(Motion.Quick)) +
                shrinkHorizontally(
                    animationSpec = tween(Motion.Quick, easing = Motion.EmphasizedAccelerate),
                ),
        ) {
            Text(
                "Active",
                style = BenchType.Label,
                color = scheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_more),
                    contentDescription = "More options",
                    tint = scheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Copy key") },
                    onClick = {
                        menuOpen = false
                        onShare()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = scheme.error) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun AddKeyDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onAdd: (AccessKey) -> Unit,
) {
    if (!visible) return
    var draft by rememberSaveable { mutableStateOf("") }
    val parsed = remember(draft) { SsKeyParser.parse(draft) }
    val scheme = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add server") },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Paste an ss:// key") },
                    minLines = 2,
                    isError = draft.isNotBlank() && parsed == null,
                    shape = MaterialTheme.shapes.medium,
                )
                Spacer(Modifier.height(8.dp))
                if (draft.isBlank()) {
                    Text(
                        "Paste an ss:// access key.",
                        fontSize = 12.sp,
                        color = scheme.onSurfaceVariant,
                    )
                } else if (parsed != null) {
                    Text(
                        "Valid key: ${parsed.name}",
                        fontSize = 12.sp,
                        color = scheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        "Not a valid ss:// key.",
                        fontSize = 12.sp,
                        color = scheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null,
                onClick = { parsed?.let(onAdd) },
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun RenameDialog(
    key: AccessKey,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by rememberSaveable(key.id) { mutableStateOf(key.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename server") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Done,
                ),
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(name.trim()) },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun ServersPreview() {
    val keys = listOf(
        AccessKey("Amsterdam", "nl-01.example.net", 8388, "aes-256-gcm", "secret"),
        AccessKey("Tokyo", "jp-02.example.net", 8388, "aes-256-gcm", "secret"),
    )
    OutlineTheme(darkTheme = true) {
        ServersScreen(
            keys = keys,
            loading = false,
            activeId = keys.first().id,
            onBack = {},
            onAddKey = {},
            onSelect = {},
            onRename = { _, _ -> },
            onDelete = {},
        )
    }
}
