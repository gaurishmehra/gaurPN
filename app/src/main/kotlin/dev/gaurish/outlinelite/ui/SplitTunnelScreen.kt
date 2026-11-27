package dev.gaurish.outlinelite.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.data.SplitTunnelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val appName: String,
    val iconBitmap: Bitmap?,
)

@Composable
fun SplitTunnelScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    val excludedPackages by SplitTunnelRepository.observeExcludedPackages(context)
        .collectAsStateWithLifecycle(initialValue = emptySet())

    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
            val appList = resolveInfos.mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName) return@mapNotNull null
                val label = ri.loadLabel(pm)?.toString() ?: pkg
                // 48 px thumbnails keep the whole-app list light on devices
                // with hundreds of launchable apps.
                val icon = runCatching {
                    ri.loadIcon(pm)?.toBitmap(width = 48, height = 48)
                }.getOrNull()
                InstalledApp(packageName = pkg, appName = label, iconBitmap = icon)
            }.distinctBy { it.packageName }
                .sortedBy { it.appName.lowercase() }

            withContext(Dispatchers.Main) {
                apps = appList
                loading = false
            }
        }
    }

    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter {
            it.appName.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
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
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 10.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_back),
                        contentDescription = "Back",
                        tint = scheme.onBackground,
                    )
                }
                Spacer(Modifier.width(2.dp))
                Text(
                    "Split tunneling",
                    style = BenchType.Title,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            Text(
                "Selected apps will connect directly to your network and bypass the VPN tunnel.",
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            // Search box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search apps", fontSize = 14.sp) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${excludedPackages.size} EXCLUDED",
                    fontSize = 11.sp,
                    letterSpacing = 1.4.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${filteredApps.size} apps",
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))

            if (loading) {
                SkeletonHost {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(18.dp))
                            .background(scheme.surface)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        repeat(8) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            ) {
                                SkeletonBox(
                                    modifier = Modifier.size(38.dp),
                                    shape = RoundedCornerShape(8.dp),
                                )
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    SkeletonLine(widthFraction = 0.46f, height = 12)
                                    Spacer(Modifier.height(6.dp))
                                    SkeletonLine(widthFraction = 0.28f, height = 9)
                                }
                            }
                        }
                    }
                }
            } else {
                if (filteredApps.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(18.dp))
                            .background(scheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (searchQuery.isBlank()) {
                                "No apps found."
                            } else {
                                "No apps match \"$searchQuery\"."
                            },
                            fontSize = 13.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(18.dp))
                            .background(scheme.surface),
                        contentPadding = PaddingValues(8.dp),
                    ) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            val isExcluded = excludedPackages.contains(app.packageName)
                            AppRow(
                                app = app,
                                isExcluded = isExcluded,
                                onToggle = {
                                    scope.launch {
                                        SplitTunnelRepository.togglePackage(
                                            context,
                                            app.packageName,
                                        )
                                    }
                                },
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

@Composable
private fun AppRow(
    app: InstalledApp,
    isExcluded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val background by animateColorAsState(
        targetValue = if (isExcluded) {
            scheme.primary.copy(alpha = 0.08f)
        } else {
            Color.Transparent
        },
        animationSpec = Motion.base(),
        label = "app_row_tint",
    )
    val icon = remember(app.iconBitmap) { app.iconBitmap?.asImageBitmap() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .benchSurface(
                shape = RoundedCornerShape(12.dp),
                color = background,
                onClick = {
                    haptics.select()
                    onToggle()
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(38.dp),
            )
        } else {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = scheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(38.dp),
            ) {}
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text(
                app.appName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                app.packageName,
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(8.dp))

        Switch(
            checked = isExcluded,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedTrackColor = scheme.primary,
                checkedThumbColor = scheme.onPrimary,
            ),
        )
    }
}
