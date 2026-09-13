package dev.duofold.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 亮暗模式：跟随系统 / 强制浅色 / 强制深色 */
enum class ThemeMode { System, Light, Dark }

val LocalIsDark = staticCompositionLocalOf { false }
val LocalThemeMode = staticCompositionLocalOf { ThemeMode.System }

@Composable
fun DuoTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val controller = when (themeMode) {
        ThemeMode.System -> ThemeController(
            if (systemDark) ColorSchemeMode.Dark else ColorSchemeMode.Light
        )
        ThemeMode.Light -> ThemeController(ColorSchemeMode.Light)
        ThemeMode.Dark -> ThemeController(ColorSchemeMode.Dark)
    }
    MiuixTheme(controller = controller) {
        CompositionLocalProvider(
            LocalIsDark provides isDark,
            LocalThemeMode provides themeMode,
            content = content,
        )
    }
}