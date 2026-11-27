package dev.gaurish.outlinelite.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gaurish.outlinelite.R

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

/**
 * Plex Sans carries the interface: open counters and a generous x-height keep
 * sentence-case copy readable at phone sizes. Plex Mono is reserved for
 * measured values, where fixed-width digits actually mean something.
 */
private object BenchFonts {
    val Sans = FontFamily(
        Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
    )
    val Mono = FontFamily(
        Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    )
}

/**
 * Compose reserves asymmetric space above and below a line for ascenders and
 * descenders. With Plex that lands as a pixel or two of sag whenever type sits
 * beside an icon or a mono figure, so the font padding is dropped everywhere
 * and single-line roles trim their leading to the cap height.
 */
private fun TextStyle.optical(trim: Boolean = false): TextStyle = copy(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = if (trim) {
        LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both,
        )
    } else {
        lineHeightStyle
    },
)

private fun Typography.withFontFamily(family: FontFamily): Typography = Typography(
    displayLarge = displayLarge.copy(fontFamily = family).optical(),
    displayMedium = displayMedium.copy(fontFamily = family).optical(),
    displaySmall = displaySmall.copy(fontFamily = family).optical(),
    headlineLarge = headlineLarge.copy(fontFamily = family).optical(),
    headlineMedium = headlineMedium.copy(fontFamily = family).optical(),
    headlineSmall = headlineSmall.copy(fontFamily = family).optical(),
    titleLarge = titleLarge.copy(fontFamily = family).optical(),
    titleMedium = titleMedium.copy(fontFamily = family).optical(),
    titleSmall = titleSmall.copy(fontFamily = family).optical(),
    bodyLarge = bodyLarge.copy(fontFamily = family).optical(),
    bodyMedium = bodyMedium.copy(fontFamily = family).optical(),
    bodySmall = bodySmall.copy(fontFamily = family).optical(),
    labelLarge = labelLarge.copy(fontFamily = family).optical(),
    labelMedium = labelMedium.copy(fontFamily = family).optical(),
    labelSmall = labelSmall.copy(fontFamily = family).optical(),
)

private val BenchTypography = Typography().withFontFamily(BenchFonts.Sans)

/** Shared type roles for the instrument readout. */
object BenchType {
    /** Screen title. One size for every hierarchy level. */
    val Title = TextStyle(
        fontFamily = BenchFonts.Sans,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    ).optical(trim = true)

    /** Session clock and latency. Fixed-width digits, never prose. */
    val Stat = TextStyle(
        fontFamily = BenchFonts.Mono,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum",
    ).optical(trim = true)

    /** Addresses and small numeric details. Never prose. */
    val Data = TextStyle(
        fontFamily = BenchFonts.Mono,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
        fontFeatureSettings = "tnum",
    ).optical(trim = true)

    /** Small supporting label, always in sentence case. */
    val Label = TextStyle(
        fontFamily = BenchFonts.Sans,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
    ).optical(trim = true)

    /** Section heading. Sentence case, no tracking, no shouting. */
    val Section = TextStyle(
        fontFamily = BenchFonts.Sans,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ).optical(trim = true)

    /** Default reading size for row copy and explainers. */
    val Body = TextStyle(
        fontFamily = BenchFonts.Sans,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    ).optical()

    /** Tightest legible caption: metadata, units, counts. */
    val Caption = TextStyle(
        fontFamily = BenchFonts.Sans,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
        fontFeatureSettings = "tnum",
    ).optical()
}

@Composable
fun OutlineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val target = if (darkTheme) BenchDark else BenchLight
    CompositionLocalProvider(LocalBenchDark provides darkTheme) {
        MaterialTheme(
            colorScheme = animatedColorScheme(target),
            typography = BenchTypography,
            shapes = BenchShapes,
        ) {
            // The stock ripple is a large, near-opaque circle: it reads as a
            // water drop rather than as a surface acknowledging a finger. A
            // 10% ink wash keeps the feedback and drops the splash.
            val indication = remember(target.onSurface) {
                ripple(color = target.onSurface.copy(alpha = 0.10f))
            }
            CompositionLocalProvider(LocalIndication provides indication) {
                content()
            }
        }
    }
}

private val LocalBenchDark = staticCompositionLocalOf { true }

/**
 * File-type tints. These are the one place the palette leaves the amber axis,
 * because the badge has to be read at 10 sp before the filename is read. They
 * are tuned per mode instead of hardcoded, so light mode is not a washed-out
 * copy of the dark set.
 */
private val FileTintsDark = mapOf(
    FileKind.IMAGE to Color(0xFF6FDCA8),
    FileKind.VIDEO to Color(0xFF8FBEF5),
    FileKind.AUDIO to Color(0xFFCBA4FF),
    FileKind.DOCUMENT to Color(0xFFEFCE84),
    FileKind.ARCHIVE to Color(0xFFF2A184),
    FileKind.OTHER to Color(0xFFA6B0BA),
)

private val FileTintsLight = mapOf(
    FileKind.IMAGE to Color(0xFF1C6B4B),
    FileKind.VIDEO to Color(0xFF1F4E85),
    FileKind.AUDIO to Color(0xFF54308C),
    FileKind.DOCUMENT to Color(0xFF6F5208),
    FileKind.ARCHIVE to Color(0xFF8A3A1E),
    FileKind.OTHER to Color(0xFF4A5359),
)

@Composable
internal fun fileKindTint(kind: FileKind): Color {
    val dark = LocalBenchDark.current
    return (if (dark) FileTintsDark else FileTintsLight)[kind] ?: MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Recolors the whole app over one beat instead of swapping all thirty roles in
 * a single frame. Each role animates in place, so screen state, scroll
 * positions, and navigation survive the change.
 */
@Composable
private fun animatedColorScheme(target: ColorScheme): ColorScheme {
    val spec = tween<Color>(300, easing = Motion.Standard)
    return target.copy(
        primary = target.primary.themed(spec),
        onPrimary = target.onPrimary.themed(spec),
        primaryContainer = target.primaryContainer.themed(spec),
        onPrimaryContainer = target.onPrimaryContainer.themed(spec),
        inversePrimary = target.inversePrimary.themed(spec),
        secondary = target.secondary.themed(spec),
        onSecondary = target.onSecondary.themed(spec),
        secondaryContainer = target.secondaryContainer.themed(spec),
        onSecondaryContainer = target.onSecondaryContainer.themed(spec),
        tertiary = target.tertiary.themed(spec),
        onTertiary = target.onTertiary.themed(spec),
        tertiaryContainer = target.tertiaryContainer.themed(spec),
        onTertiaryContainer = target.onTertiaryContainer.themed(spec),
        background = target.background.themed(spec),
        onBackground = target.onBackground.themed(spec),
        surface = target.surface.themed(spec),
        onSurface = target.onSurface.themed(spec),
        surfaceVariant = target.surfaceVariant.themed(spec),
        onSurfaceVariant = target.onSurfaceVariant.themed(spec),
        surfaceTint = target.surfaceTint.themed(spec),
        inverseSurface = target.inverseSurface.themed(spec),
        inverseOnSurface = target.inverseOnSurface.themed(spec),
        error = target.error.themed(spec),
        onError = target.onError.themed(spec),
        errorContainer = target.errorContainer.themed(spec),
        onErrorContainer = target.onErrorContainer.themed(spec),
        outline = target.outline.themed(spec),
        outlineVariant = target.outlineVariant.themed(spec),
        scrim = target.scrim.themed(spec),
        surfaceBright = target.surfaceBright.themed(spec),
        surfaceDim = target.surfaceDim.themed(spec),
        surfaceContainerLowest = target.surfaceContainerLowest.themed(spec),
        surfaceContainerLow = target.surfaceContainerLow.themed(spec),
        surfaceContainer = target.surfaceContainer.themed(spec),
        surfaceContainerHigh = target.surfaceContainerHigh.themed(spec),
        surfaceContainerHighest = target.surfaceContainerHighest.themed(spec),
    )
}

@Composable
private fun Color.themed(spec: AnimationSpec<Color>): Color =
    animateColorAsState(targetValue = this, animationSpec = spec, label = "theme_color").value
