package com.alethra.ai.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Violet = Color(0xFF8B7CFF)
val Teal = Color(0xFF4FD1C5)
val Ink = Color(0xFF0E1020)
val Panel = Color(0xFF171A2E)

private val scheme = darkColorScheme(
    primary = Violet, onPrimary = Color(0xFF14103A),
    secondary = Teal, onSecondary = Color(0xFF00201D),
    background = Ink, onBackground = Color(0xFFE8E9F5),
    surface = Panel, onSurface = Color(0xFFE8E9F5),
    surfaceVariant = Color(0xFF22264A), onSurfaceVariant = Color(0xFFB4B7D3),
    primaryContainer = Color(0xFF3A3380), onPrimaryContainer = Color(0xFFE6E2FF),
    error = Color(0xFFFF8A80)
)

@Composable
fun AlethraTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
