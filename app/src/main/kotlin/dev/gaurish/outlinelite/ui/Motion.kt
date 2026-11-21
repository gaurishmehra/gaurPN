package dev.gaurish.outlinelite.ui

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing

/**
 * One motion vocabulary for the app. No springs that overshoot, no decorative
 * loops. Durations are short enough that a state change still feels direct.
 */
object Motion {
    const val Quick = 120
    const val Base = 200
    const val Enter = 240
    const val Exit = 160
    const val Settle = 420
    const val Screen = 220

    val Standard = FastOutSlowInEasing
    val Decelerate = LinearOutSlowInEasing
    val Accelerate = FastOutLinearInEasing
    val Linear = LinearEasing
}
