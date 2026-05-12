package com.curiokid.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = LunaPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7DFFF),
    onPrimaryContainer = Color(0xFF20124B),
    secondary = LunaSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD8E7),
    onSecondaryContainer = Color(0xFF430F2A),
    tertiary = LunaTertiary,
    onTertiary = Color(0xFF422C00),
    tertiaryContainer = Color(0xFFFFE8B0),
    onTertiaryContainer = Color(0xFF2A1C00),
    background = LunaBgLight,
    onBackground = LunaOnSurfaceLight,
    surface = LunaSurfaceLight,
    onSurface = LunaOnSurfaceLight,
    surfaceVariant = Color(0xFFF1ECF7),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFFCCC4D6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB8A8FF),
    onPrimary = Color(0xFF1F1259),
    primaryContainer = LunaPrimaryDark,
    onPrimaryContainer = Color(0xFFE7DFFF),
    secondary = Color(0xFFFFB1D2),
    onSecondary = Color(0xFF5A1F3D),
    secondaryContainer = Color(0xFF7A3559),
    onSecondaryContainer = Color(0xFFFFD8E7),
    tertiary = Color(0xFFFFE08C),
    onTertiary = Color(0xFF402C00),
    background = LunaBgDark,
    onBackground = LunaOnSurfaceDark,
    surface = LunaSurfaceDark,
    onSurface = LunaOnSurfaceDark,
    surfaceVariant = Color(0xFF2C2839),
    onSurfaceVariant = Color(0xFFCDC4DC),
    outline = Color(0xFF564E64),
)

@Composable
fun CurioKidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = colorScheme.background.luminance() > 0.5f
                isAppearanceLightNavigationBars = colorScheme.background.luminance() > 0.5f
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CurioTypography,
        shapes = CurioShapes,
        content = content,
    )
}
