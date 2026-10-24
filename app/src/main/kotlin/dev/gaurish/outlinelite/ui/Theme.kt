package dev.gaurish.outlinelite.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Neutral dark + one accent. On Android 12+ the neutrals (and the accent tint)
 * come from Material You dynamic color; below that the fallback mirrors the
 * same structure with a quiet mint accent.
 */

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5BD9A3),
    onPrimary = Color(0xFF04231A),
    primaryContainer = Color(0xFF1E4636),
    onPrimaryContainer = Color(0xFFB8F1D8),
    secondary = Color(0xFF9FB6C9),
    onSecondary = Color(0xFF12212E),
    tertiary = Color(0xFFD9C08A),
    onTertiary = Color(0xFF2A2113),
    background = Color(0xFF0A0D12),
    onBackground = Color(0xFFE8EDF3),
    surface = Color(0xFF12151B),
    onSurface = Color(0xFFDCE3EA),
    surfaceVariant = Color(0xFF1A2028),
    onSurfaceVariant = Color(0xFF98A2B0),
    outline = Color(0xFF2A313B),
    outlineVariant = Color(0xFF232A33),
    error = Color(0xFFEF6C6C),
    onError = Color(0xFF2C0507),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF24684A),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF171B21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B2026),
    surfaceVariant = Color(0xFFEAEDF1),
    onSurfaceVariant = Color(0xFF485059),
    outline = Color(0xFFD4D9DF),
    outlineVariant = Color(0xFFE2E6EB),
    error = Color(0xFFB3261E),
)

@Composable
fun OutlineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
