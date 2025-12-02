package com.zigent.ui.theme

import android.app.Activity
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// 颜色定义：暖色调 + 深色背景，避免蓝紫色与渐变
private val Primary = Color(0xFFE28C3C)           // 琥珀橙
private val PrimaryVariant = Color(0xFFBB6B1C)
private val Secondary = Color(0xFF52B788)         // 竹青
private val SecondaryVariant = Color(0xFF3A8E65)

private val Background = Color(0xFF0D0E11)
private val Surface = Color(0xFF161922)
private val SurfaceVariant = Color(0xFF1F232D)

private val OnPrimary = Color(0xFFFFFFFF)
private val OnSecondary = Color(0xFF0F131A)
private val OnBackground = Color(0xFFE8EDF5)
private val OnSurface = Color(0xFFD8DEE9)

private val Error = Color(0xFFEF5350)

/**
 * Zigent暗色主题配色
 */
private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryVariant,
    onPrimaryContainer = OnPrimary,
    
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryVariant,
    onSecondaryContainer = OnSecondary,
    
    background = Background,
    onBackground = OnBackground,
    
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurface,
    
    error = Error,
    onError = OnPrimary
)

/**
 * Zigent主题
 * 使用深色科技风格
 */
@Composable
fun ZigentTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current
    
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Background.toArgb()
            window.navigationBarColor = Background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

