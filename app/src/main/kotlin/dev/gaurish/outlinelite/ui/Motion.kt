package dev.gaurish.outlinelite.ui

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * One motion vocabulary for the app.
 *
 * The instrument metaphor sets the rules: things snap into place, settle, and
 * stop. Spatial springs use Material 3's standard physics (0.9 damping, a
 * 0.15% overshoot that is below perception); effects stay critically damped.
 * Every animation either explains a state change or confirms input; none of
 * them run to decorate an idle screen.
 */
object Motion {
    /*
     * Durations are set so that the *geometry* is what carries the change, not
     * the clock. They read as deliberate at 1x and still register at the 0.5x
     * animator scale some devices ship with, because nothing here moves less
     * than 8 dp or scales less than 2%.
     */

    /** Input acknowledgement. Fast enough to feel instant, present enough to see. */
    const val Instant = 90

    /** Small local state: a tint, a checkmark, a chevron. */
    const val Quick = 160

    /** Default in-place change: labels, values, selection marks. */
    const val Base = 240

    /** A block of content settling into place. */
    const val Medium = 320

    /** Something arriving: a row, a banner, an expanded section. */
    const val Enter = 360

    /** Something leaving. Always shorter than its entrance. */
    const val Exit = 220

    /** Whole-surface navigation, forward or back. */
    const val Screen = 400

    /** Long decelerating moves: ring progress, arrival halo. */
    const val Settle = 760

    /** Material 3 standard, for colour and layout. */
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Only for continuous rotation. */
    val Linear = LinearEasing

    /** Asymmetric Material 3 curves: leave fast, land softly. */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun <T> snap(): FiniteAnimationSpec<T> = tween(Instant, easing = Linear)

    fun <T> quick(): FiniteAnimationSpec<T> = tween(Quick, easing = Standard)

    fun <T> base(): FiniteAnimationSpec<T> = tween(Base, easing = Standard)

    fun <T> medium(): FiniteAnimationSpec<T> = tween(Medium, easing = EmphasizedDecelerate)

    fun <T> enter(): FiniteAnimationSpec<T> = tween(Enter, easing = EmphasizedDecelerate)

    fun <T> exit(): FiniteAnimationSpec<T> = tween(Exit, easing = EmphasizedAccelerate)

    fun <T> screen(): FiniteAnimationSpec<T> = tween(Screen, easing = EmphasizedDecelerate)

    fun <T> settle(): FiniteAnimationSpec<T> = tween(Settle, easing = Standard)

    /**
     * Spatial spring for anything that changes size or position. 0.85 damping
     * leaves a ~1% overshoot: felt as weight, never as a bounce.
     */
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.85f,
        stiffness = 380f,
    )

    /** Tighter spatial spring, for short travel inside a row. */
    fun <T> spatialFast(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.9f,
        stiffness = 700f,
    )

    /** Press feedback: the finger is already down, so this answers instantly. */
    fun <T> press(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessHigh,
    )

    /** A mark that lands: selection ticks, badges, the connected dial. */
    fun <T> pop(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.55f,
        stiffness = 460f,
    )
}

/**
 * True when the system has not disabled animations. Compose scales finite
 * animations on its own, but infinite ones (breathing indicators, shimmer)
 * keep drawing at zero scale, so those call sites must opt out explicitly.
 */
fun animationsEnabled(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) != 0f
}.getOrDefault(true)

@Composable
fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(animationsEnabled(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = animationsEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return enabled
}

/**
 * Shrinks the surface under a finger. The value is read inside
 * [graphicsLayer], so the press never recomposes the content, only redraws
 * one layer. Defaults are deliberately visible: a 1% shrink is invisible on a
 * phone held at arm's length, and press feedback that cannot be seen is not
 * feedback.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.97f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = Motion.press(),
        label = "press_scale",
    )
    return graphicsLayer {
        val value = scale.value
        scaleX = value
        scaleY = value
    }
}

/**
 * Haptics with intent. Selection ticks are nearly subliminal; confirm and
 * reject are reserved for outcomes the user should feel without looking.
 */
class BenchHaptics internal constructor(private val view: View) {
    fun tap() {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun select() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun confirm() {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(constant)
    }

    fun reject() {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(constant)
    }
}

@Composable
fun rememberHaptics(): BenchHaptics {
    val view = LocalView.current
    return remember(view) { BenchHaptics(view) }
}
