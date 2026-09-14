package com.whatsbird.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val BirdColorScheme = darkColorScheme(
    primary = BirdColors.Accent,
    onPrimary = BirdColors.Background,
    primaryContainer = BirdColors.AccentDim,
    onPrimaryContainer = BirdColors.OnBackground,
    background = BirdColors.Background,
    onBackground = BirdColors.OnBackground,
    surface = BirdColors.Surface,
    onSurface = BirdColors.OnBackground,
    surfaceVariant = BirdColors.SurfaceHigh,
    onSurfaceVariant = BirdColors.OnSurfaceMuted,
    outline = BirdColors.AccentDim,
    error = BirdColors.Danger,
)

private val BirdTypography = Typography(
    headlineSmall = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun WhatsBirdTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BirdColorScheme,
        typography = BirdTypography,
        content = content,
    )
}
