package dev.gaurish.outlinelite.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Bench: the app reads like a network instrument. Graphite planes, warm
 * hairlines, one amber live accent, and monospace for anything measured.
 * Dynamic color is intentionally not used: a wallpaper palette would turn the
 * live/fault semantics into decoration.
 */

private val BenchDark = darkColorScheme(
    primary = Color(0xFFE8A33D),
    onPrimary = Color(0xFF1C1204),
    primaryContainer = Color(0xFF3A2A12),
    onPrimaryContainer = Color(0xFFF6D9A8),
    inversePrimary = Color(0xFF7A5208),
    secondary = Color(0xFFA29C93),
    onSecondary = Color(0xFF12100D),
    secondaryContainer = Color(0xFF292521),
    onSecondaryContainer = Color(0xFFDDD6CC),
    tertiary = Color(0xFF7FA89C),
    onTertiary = Color(0xFF0D1714),
    tertiaryContainer = Color(0xFF243A33),
    onTertiaryContainer = Color(0xFFC4E3D9),
    background = Color(0xFF0E0D0C),
    onBackground = Color(0xFFE8E3DA),
    surface = Color(0xFF171513),
    onSurface = Color(0xFFE8E3DA),
    surfaceVariant = Color(0xFF211E1B),
    onSurfaceVariant = Color(0xFF9A9389),
    surfaceTint = Color(0xFFE8A33D),
    inverseSurface = Color(0xFFE8E3DA),
    inverseOnSurface = Color(0xFF171513),
    error = Color(0xFFE05B47),
    onError = Color(0xFF2B0B07),
    errorContainer = Color(0xFF4A1B14),
    onErrorContainer = Color(0xFFF4C5BE),
    outline = Color(0xFF423D37),
    outlineVariant = Color(0xFF2C2824),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF302B26),
    surfaceDim = Color(0xFF0E0D0C),
    surfaceContainerLowest = Color(0xFF090807),
    surfaceContainerLow = Color(0xFF12100E),
    surfaceContainer = Color(0xFF171513),
    surfaceContainerHigh = Color(0xFF1E1B18),
    surfaceContainerHighest = Color(0xFF25211D),
)

private val BenchLight = lightColorScheme(
    primary = Color(0xFF7A5208),
    onPrimary = Color(0xFFFFFDFA),
    primaryContainer = Color(0xFFF7E2BD),
    onPrimaryContainer = Color(0xFF2A1B00),
    inversePrimary = Color(0xFFE8A33D),
    secondary = Color(0xFF5F5A53),
    onSecondary = Color(0xFFFFFDFA),
    secondaryContainer = Color(0xFFE0E5E2),
    onSecondaryContainer = Color(0xFF171B1A),
    tertiary = Color(0xFF3D5F56),
    onTertiary = Color(0xFFFFFDFA),
    tertiaryContainer = Color(0xFFC4E3D9),
    onTertiaryContainer = Color(0xFF10251F),
    background = Color(0xFFF4F2EE),
    onBackground = Color(0xFF151310),
    surface = Color(0xFFFFFDFA),
    onSurface = Color(0xFF151310),
    surfaceVariant = Color(0xFFE7E3DC),
    onSurfaceVariant = Color(0xFF5F5A53),
    surfaceTint = Color(0xFF7A5208),
    inverseSurface = Color(0xFF2A2F2D),
    inverseOnSurface = Color(0xFFF4F2EE),
    error = Color(0xFFA33325),
    onError = Color(0xFFFFFDFA),
    errorContainer = Color(0xFFF6DAD4),
    onErrorContainer = Color(0xFF3B0903),
    outline = Color(0xFF7A746C),
    outlineVariant = Color(0xFFCFC9C1),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFDFA),
    surfaceDim = Color(0xFFE5E1DA),
    surfaceContainerLowest = Color(0xFFFFFDFA),
    surfaceContainerLow = Color(0xFFFAF7F2),
    surfaceContainer = Color(0xFFF4F2EE),
    surfaceContainerHigh = Color(0xFFF0ECE5),
    surfaceContainerHighest = Color(0xFFEAE6DF),
)

private val BenchShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

private val BenchTypography = Typography()

/** Shared type roles for the instrument readout. */
object BenchType {
    val Readout = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.6).sp,
    )
    val Data = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
    )
    val Micro = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp,
    )
}

@Composable
fun OutlineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) BenchDark else BenchLight,
        typography = BenchTypography,
        shapes = BenchShapes,
        content = content,
    )
}
