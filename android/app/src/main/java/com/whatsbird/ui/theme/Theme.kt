/*
 * Copyright 2026 gongfpp (https://github.com/gongfpp/whatsBird)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
