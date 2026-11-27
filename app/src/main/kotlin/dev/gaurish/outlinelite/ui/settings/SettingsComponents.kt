package dev.gaurish.outlinelite.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.ui.Motion
import dev.gaurish.outlinelite.ui.BenchType
import dev.gaurish.outlinelite.ui.benchSurface
import dev.gaurish.outlinelite.ui.rememberHaptics

/** Brand switch styling shared by every settings toggle. */
@Composable
fun GaurSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptics = rememberHaptics()
    // The write goes to DataStore, which emits on a later frame. Move the
    // thumb now and reconcile when the real value arrives; a switch that
    // waits for disk looks broken.
    var shown by remember { mutableStateOf(checked) }
    LaunchedEffect(checked) { shown = checked }
    Switch(
        checked = shown,
        onCheckedChange = { next ->
            shown = next
            haptics.select()
            onCheckedChange(next)
        },
        colors = SwitchDefaults.colors(
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedBorderColor = MaterialTheme.colorScheme.primary,
            // The stock off state reads as disabled against graphite, so the
            // resting track is lifted and the thumb carries real contrast.
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceBright,
            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
    )
}

/** Small right-pointing chevron for navigable rows. */
@Composable
fun Chevron() {
    Icon(
        painter = painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

/**
 * Section heading. Sentence case at the same weight as the section headings on
 * Home: a group of settings and a group of devices are the same structural
 * idea, so they should not look like two different apps.
 */
@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        style = BenchType.Section,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .padding(top = 20.dp, bottom = 10.dp)
            .semantics { heading() },
    )
}

/** Small grey explainer paragraph used across settings screens. */
@Composable
fun SettingsFootnote(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

/** Back arrow + title header shared by every settings screen. */
@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    showBack: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 10.dp),
    ) {
        if (showBack) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Spacer(Modifier.width(2.dp))
        Text(
            title,
            style = BenchType.Title,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * Shared screen scaffold: background, system-bar padding, scrollable column,
 * and the standard header. Sub-screens pass their rows as content.
 */
@Composable
fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    showBack: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
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
                // Breathing room at the bottom so the last row never sits
                // flush against the navigation bar.
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            ScreenHeader(title, onBack, showBack)
            content()
        }
    }
}

/** A single titled settings row with optional summary, click, and trailing slot. */
@Composable
fun SettingsRow(
    title: String,
    summary: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.large
    val surfaceMod = if (onClick != null) {
        Modifier.benchSurface(
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            role = Role.Button,
            onClickLabel = title,
            onClick = onClick,
        )
    } else {
        Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .then(surfaceMod)
            .heightIn(min = 56.dp)
            .padding(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                summary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/**
 * Conditionally shown settings group. The group grows and collapses from the
 * top so toggling a feature never makes the rows below jump.
 */
@Composable
fun ColumnScope.RevealSetting(
    visible: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(Motion.Medium, easing = Motion.EmphasizedDecelerate)) +
            expandVertically(
                animationSpec = tween(Motion.Medium, easing = Motion.EmphasizedDecelerate),
                expandFrom = Alignment.Top,
            ),
        exit = fadeOut(tween(Motion.Exit, easing = Motion.EmphasizedAccelerate)) +
            shrinkVertically(
                animationSpec = tween(Motion.Exit, easing = Motion.EmphasizedAccelerate),
                shrinkTowards = Alignment.Top,
            ),
    ) {
        content()
    }
}
