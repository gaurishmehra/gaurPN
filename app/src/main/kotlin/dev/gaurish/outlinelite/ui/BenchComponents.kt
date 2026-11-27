package dev.gaurish.outlinelite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Settlement for content that arrives after the first frame: a lift, a small
 * scale-up and a fade, staggered by row. The distance is real (16 dp, 3%) so
 * the arrival is legible even when the animator scale is cut in half.
 *
 * The progress lives in an [Animatable] and is read only inside
 * [graphicsLayer], which means the entrance never recomposes its content. Doing
 * this with `animateFloatAsState` and a boolean would recompose the entire
 * section at the moment the flag flips — one stall per section, mid-transition.
 *
 * It plays once per item, not once per visit: the played flag is saved, so a
 * section that scrolls out of the viewport and back does not replay.
 */
@Composable
fun Modifier.appearOnEnter(
    order: Int = 0,
    enabled: Boolean = true,
): Modifier {
    var played by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (enabled && !played) 0f else 1f) }
    LaunchedEffect(enabled, played) {
        if (!enabled || played) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        delay(order.coerceAtLeast(0) * 55L)
        progress.animateTo(1f, Motion.enter())
        played = true
    }
    return graphicsLayer {
        val value = progress.value
        alpha = value
        translationY = (1f - value) * 16.dp.toPx()
        val scale = 0.97f + 0.03f * value
        scaleX = scale
        scaleY = scale
        transformOrigin = TransformOrigin(0.5f, 0f)
    }
}

// A loading screen can contain twenty placeholders. They share one clock.
private val LocalSkeletonPhase = compositionLocalOf<State<Float>?> { null }

/**
 * One shimmer for a group of skeletons. Without a host each [SkeletonBox]
 * owns an infinite transition and a lifecycle observer, so a twenty-row
 * loading list would run twenty clocks for one visual effect.
 */
@Composable
fun SkeletonHost(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSkeletonPhase provides rememberSkeletonPhase()) {
        content()
    }
}

@Composable
private fun rememberSkeletonPhase(): State<Float>? {
    val enabled = rememberMotionEnabled()
    if (!enabled) return null
    val transition = rememberInfiniteTransition(label = "skeleton")
    return transition.animateFloat(
        initialValue = -1.2f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "skeleton_shift",
    )
}

/**
 * Shimmering placeholder used wherever an async list is still empty. The
 * gradient is read in the draw phase, so the shimmer never recomposes the
 * row, and it degrades to a flat block when animations are disabled.
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
) {
    val scheme = MaterialTheme.colorScheme
    val track = scheme.surfaceVariant.copy(alpha = 0.40f)
    val light = scheme.surfaceVariant.copy(alpha = 0.78f)

    val hosted = LocalSkeletonPhase.current
    val fallback = if (hosted == null) rememberSkeletonPhase() else null
    val shimmer = hosted ?: fallback

    if (shimmer == null) {
        Box(modifier.clip(shape).background(track))
        return
    }

    // The brush is rebuilt at most once per size change instead of once per
    // box per frame; a loading list is a dozen boxes wide on a wide screen.
    Box(
        modifier
            .clip(shape)
            .drawWithCache {
                val brush = Brush.linearGradient(
                    colors = listOf(track, light, track),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                )
                onDrawBehind {
                    translate(shimmer.value * size.width) {
                        drawRect(brush)
                    }
                }
            }
    )
}

/** One line of skeleton text. Height follows the 11–13sp caption scale. */
@Composable
fun SkeletonLine(
    widthFraction: Float,
    modifier: Modifier = Modifier,
    height: Int = 10,
) {
    SkeletonBox(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height.dp),
    )
}

/** Container form of [appearOnEnter], for content that is not a lone modifier. */
@Composable
fun Reveal(
    order: Int = 0,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(Modifier.appearOnEnter(order, enabled)) {
        content()
    }
}

/**
 * Ripple plus a half-percent shrink, wired to one interaction source so the
 * press reads as a single physical response. Rows use this instead of a bare
 * [clickable] so every pressable surface in the app answers the finger.
 */
@Composable
fun Modifier.benchClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    pressedScale: Float = 0.985f,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return pressScale(interaction, pressedScale)
        .clickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
}

/**
 * Clickable surface whose clip and fill live *inside* the press layer, so the
 * whole card moves under the finger instead of only its text.
 */
@Composable
fun Modifier.benchSurface(
    shape: Shape,
    color: Color,
    enabled: Boolean = true,
    pressedScale: Float = 0.98f,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return pressScale(interaction, pressedScale)
        .clip(shape)
        .background(color)
        .clickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            enabled = enabled,
            role = role,
            onClickLabel = onClickLabel,
            onClick = onClick,
        )
}

/**
 * The one section heading in the app. Sentence case, medium weight, and a
 * single optional trailing word for a count or a link. The slot height is
 * reserved whether or not a trailer exists, so two neighbouring sections
 * never disagree about where their content starts.
 */
@Composable
fun BenchSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    trailingClickLabel: String? = null,
    onTrailingClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = BenchType.Section,
            color = scheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(
                trailing,
                style = BenchType.Caption,
                fontWeight = FontWeight.Medium,
                color = if (onTrailingClick != null) scheme.primary else scheme.onSurfaceVariant,
                maxLines = 1,
                modifier = if (onTrailingClick != null) {
                    // The negative-free offset keeps the word flush with the
                    // content edge while the tap target keeps its own inset.
                    Modifier
                        .minimumInteractiveComponentSize()
                        .benchClickable(
                            onClickLabel = trailingClickLabel,
                            role = Role.Button,
                            onClick = onTrailingClick,
                        )
                        .padding(horizontal = 8.dp)
                        .offset(x = 8.dp)
                } else {
                    Modifier.padding(horizontal = 8.dp).offset(x = 8.dp)
                },
            )
        }
    }
}

/** Hairline between rows inside one plane. Optionally inset to align under text. */
@Composable
fun BenchDivider(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    HorizontalDivider(
        modifier = modifier.padding(start = inset),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * A row of measured values, each under a small caption, separated by
 * hairlines. This is the readout form: labels say what the number is, the
 * mono figures carry the value, and the dividers keep the columns honest
 * when the digits change width.
 */
@Composable
fun BenchReadout(
    metrics: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        metrics.forEachIndexed { index, (label, value) ->
            if (index > 0) {
                Box(
                    Modifier
                        .padding(horizontal = 12.dp)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(scheme.outlineVariant)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = BenchType.Caption,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    value,
                    style = BenchType.Data,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
