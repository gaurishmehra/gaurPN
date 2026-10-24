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

val Accent = Color(0xFFF5A524)
val Mint = Color(0xFF39D98A)
val Mist = Color(0xFF7FD1E0)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF221602),
    primaryContainer = Color(0xFF3B2D06),
    onPrimaryContainer = Color(0xFFFFD9A0),
    secondary = Mist,
    onSecondary = Color(0xFF06222A),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE7ECF3),
    surface = Color(0xFF111823),
    onSurface = Color(0xFFE7ECF3),
    surfaceVariant = Color(0xFF182030),
    onSurfaceVariant = Color(0xFF9AA8BC),
    outline = Color(0xFF26303F),
    error = Color(0xFFEF6C6C),
    onError = Color(0xFF2C0507),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFA96A05),
    onPrimary = Color(0xFFFFF6E8),
    secondary = Color(0xFF1F7C8B),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFAF6EE),
    onBackground = Color(0xFF1A1E25),
    surface = Color(0xFFFFFDF8),
    onSurface = Color(0xFF1A1E25),
    surfaceVariant = Color(0xFFECE7DC),
    onSurfaceVariant = Color(0xFF4D5563),
    outline = Color(0xFFDCD5C6),
    error = Color(0xFFB3261E),
)

@Composable
fun OutlineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
