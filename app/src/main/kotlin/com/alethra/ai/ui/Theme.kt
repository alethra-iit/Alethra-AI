package com.alethra.ai.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.alethra.ai.AlethraApp

/** Mode tampilan: 0 = ikuti sistem, 1 = terang, 2 = gelap. */
object ThemeMode {
    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
}

/** Satu palet warna lengkap dengan varian gelap dan terang. */
class AlethraPalette(val name: String, val dark: ColorScheme, val light: ColorScheme)

private fun c(v: Long) = Color(0xFF000000 or v)

private fun palette(
    name: String,
    dPrimary: Long, dContainer: Long, dSecondary: Long, dBg: Long, dPanel: Long, dVariant: Long,
    lPrimary: Long, lContainer: Long, lSecondary: Long, lBg: Long, lVariant: Long
): AlethraPalette {
    val dark = darkColorScheme(
        primary = c(dPrimary), onPrimary = c(0x101018),
        secondary = c(dSecondary), onSecondary = c(0x101018),
        background = c(dBg), onBackground = c(0xE8E9F5),
        surface = c(dPanel), onSurface = c(0xE8E9F5),
        surfaceVariant = c(dVariant), onSurfaceVariant = c(0xB4B7D3),
        primaryContainer = c(dContainer), onPrimaryContainer = c(0xF0F0FF),
        secondaryContainer = c(dContainer), onSecondaryContainer = c(0xF0F0FF),
        surfaceContainerLow = c(dPanel), surfaceContainer = c(dPanel), surfaceContainerHigh = c(dVariant),
        outline = c(0x8A8DA8), outlineVariant = c(dVariant),
        error = c(0xFF8A80)
    )
    val light = lightColorScheme(
        primary = c(lPrimary), onPrimary = Color.White,
        secondary = c(lSecondary), onSecondary = Color.White,
        background = c(lBg), onBackground = c(0x1B1B24),
        surface = Color.White, onSurface = c(0x1B1B24),
        surfaceVariant = c(lVariant), onSurfaceVariant = c(0x55586E),
        primaryContainer = c(lContainer), onPrimaryContainer = c(0x1B1B24),
        secondaryContainer = c(lContainer), onSecondaryContainer = c(0x1B1B24),
        surfaceContainerLow = Color.White, surfaceContainer = Color.White, surfaceContainerHigh = c(lVariant),
        outline = c(0x757892), outlineVariant = c(lVariant),
        error = c(0xB3261E)
    )
    return AlethraPalette(name, dark, light)
}

val Palettes = listOf(
    // Palet asli Alethra
    palette("Ungu", 0x8B7CFF, 0x3A3380, 0x4FD1C5, 0x0E1020, 0x171A2E, 0x22264A,
        0x5B4BDB, 0xE6E2FF, 0x0F8F85, 0xF6F5FF, 0xE9E7FA),
    palette("Samudra", 0x64B5FF, 0x1F4B7A, 0x5EE0C8, 0x0B1420, 0x121F30, 0x1C2E45,
        0x1565C0, 0xD6E8FF, 0x00897B, 0xF3F8FF, 0xE1ECF8),
    palette("Hutan", 0x6FD98B, 0x245A3A, 0xD9C16A, 0x0C1510, 0x131F18, 0x1D3126,
        0x2E7D32, 0xD4F0D8, 0x9A7B00, 0xF4F9F4, 0xE2EEE3),
    palette("Senja", 0xFFA45C, 0x7A3F10, 0xFF7A8A, 0x1A110C, 0x261A13, 0x3A281D,
        0xC25A00, 0xFFE0C8, 0xC2185B, 0xFFF8F3, 0xF8E8DC),
    palette("Mawar", 0xFF8AC0, 0x7A2552, 0xB79CFF, 0x1A0E15, 0x261520, 0x3B2132,
        0xC2185B, 0xFFD9E8, 0x6A4FD1, 0xFFF5F9, 0xF8E3EC),
    palette("Grafit", 0xC9CCD6, 0x3A3D45, 0x9AA3B5, 0x101113, 0x1A1B1E, 0x26282C,
        0x3F4450, 0xE1E3E8, 0x5F6B7A, 0xF7F7F8, 0xEAEBED)
)

/** Dipakai oleh Activity maupun floating assistant: membaca pilihan tema dari Prefs. */
@Composable
fun AlethraTheme(content: @Composable () -> Unit) {
    val prefs = (LocalContext.current.applicationContext as AlethraApp).prefs
    AlethraTheme(prefs.themeMode, prefs.paletteIndex, content)
}

@Composable
fun AlethraTheme(mode: Int, paletteIndex: Int, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }
    val p = Palettes[paletteIndex.coerceIn(0, Palettes.lastIndex)]
    val scheme = if (dark) p.dark else p.light

    // Samakan warna status bar & navigation bar dengan tema (hanya untuk Activity, bukan overlay).
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.statusBarColor = scheme.background.toArgb()
        window.navigationBarColor = scheme.background.toArgb()
        val ctl = WindowCompat.getInsetsController(window, view)
        ctl.isAppearanceLightStatusBars = !dark
        ctl.isAppearanceLightNavigationBars = !dark
    }

    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
