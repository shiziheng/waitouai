package com.huyan.tvguard.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF12564A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFF0E6),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4A635C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE8DE),
    onSecondaryContainer = Color(0xFF06201A),
    background = Color(0xFFF5F9F7),
    onBackground = Color(0xFF171C1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171C1A),
    surfaceVariant = Color(0xFFE3ECE8),
    onSurfaceVariant = Color(0xFF3F4B47),
    outline = Color(0xFF6F7B76),
    outlineVariant = Color(0xFFC3CDC8),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD6C0),
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF0F4A3E),
    onPrimaryContainer = Color(0xFFADF2DB),
    secondary = Color(0xFFB1CCC3),
    onSecondary = Color(0xFF1C3530),
    secondaryContainer = Color(0xFF324B45),
    onSecondaryContainer = Color(0xFFCDE8DE),
    background = Color(0xFF0E1513),
    onBackground = Color(0xFFDFE4E1),
    surface = Color(0xFF141C1A),
    onSurface = Color(0xFFDFE4E1),
    surfaceVariant = Color(0xFF2A332F),
    onSurfaceVariant = Color(0xFFBEC9C4),
    outline = Color(0xFF889490),
    outlineVariant = Color(0xFF3F4B47),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/** 界面状态色。故意不走 colorScheme，避免被系统动态取色带偏。 */
object StatusPalette {
    val SafeLight = Color(0xFF1B7F5A)
    val SafeDark = Color(0xFF6FCFA6)
    val WarnLight = Color(0xFF9A6200)
    val WarnDark = Color(0xFFF2BE5C)
    val DangerLight = Color(0xFFB3261E)
    val DangerDark = Color(0xFFFFB4AB)
    val NeutralLight = Color(0xFF5C6B66)
    val NeutralDark = Color(0xFFB0BCB7)

    fun safe(dark: Boolean) = if (dark) SafeDark else SafeLight
    fun warn(dark: Boolean) = if (dark) WarnDark else WarnLight
    fun danger(dark: Boolean) = if (dark) DangerDark else DangerLight
    fun neutral(dark: Boolean) = if (dark) NeutralDark else NeutralLight
}

/**
 * 家长在几米外看屏幕，字号整体放大一档。
 */
private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 17.sp),
)

@Composable
fun TvEyeGuardianTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
