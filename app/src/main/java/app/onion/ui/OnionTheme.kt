package app.onion.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.onion.AppThemeMode

private val OnionLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF5BAE31),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEAF7D8),
    onPrimaryContainer = Color(0xFF101510),
    secondary = Color(0xFF50624A),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF207A41),
    background = Color(0xFFF7F8F1),
    onBackground = Color(0xFF101510),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101510),
    onSurfaceVariant = Color(0xFF5D6559),
    outline = Color(0xFF9AA392),
    outlineVariant = Color(0xFFE1E7D8),
)

private val OnionDarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF98E86E),
    onPrimary = Color(0xFF10220B),
    primaryContainer = Color(0xFF1F3417),
    onPrimaryContainer = Color(0xFFE9F7DA),
    secondary = Color(0xFFC1D4B8),
    onSecondary = Color(0xFF263522),
    tertiary = Color(0xFF85D99D),
    background = Color(0xFF101510),
    onBackground = Color(0xFFF7F8F1),
    surface = Color(0xFF171D17),
    onSurface = Color(0xFFF7F8F1),
    onSurfaceVariant = Color(0xFFC2CABD),
    outline = Color(0xFF778171),
    outlineVariant = Color(0xFF30382E),
)

@Composable
fun OnionTheme(
    themeMode: AppThemeMode,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.System -> isSystemInDarkTheme()
        AppThemeMode.Light -> false
        AppThemeMode.Dark -> true
    }

    MaterialTheme(
        colorScheme = if (darkTheme) OnionDarkColors else OnionLightColors,
        typography = Typography(),
        content = content,
    )
}
