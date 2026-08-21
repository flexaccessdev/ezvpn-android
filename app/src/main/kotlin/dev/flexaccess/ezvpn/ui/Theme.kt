package dev.flexaccess.ezvpn.ui

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

/** The app icon's teal, used as the seed where dynamic color is unavailable. */
private val Teal = Color(0xFF12A682)
private val TealDark = Color(0xFF05665C)

private val LightColors = lightColorScheme(
    primary = TealDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB6F0DE),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Teal,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6BD8BB),
    onPrimary = Color(0xFF00382E),
    primaryContainer = TealDark,
    onPrimaryContainer = Color(0xFFB6F0DE),
    secondary = Teal,
)

@Composable
fun EzvpnTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
