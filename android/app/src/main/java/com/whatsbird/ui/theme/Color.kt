package com.whatsbird.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * A dark chrome palette. This is a camera app: labels sit directly on top of a live image, and a
 * light UI competes with the frame for attention and washes out over bright skies.
 */
object BirdColors {
    val Background = Color(0xFF0A0E0C)
    val Surface = Color(0xFF131A17)
    val SurfaceHigh = Color(0xFF1D2622)
    val Accent = Color(0xFF4CC38A)
    val AccentDim = Color(0xFF2A6A4C)
    val OnBackground = Color(0xFFE9F1EC)
    val OnSurfaceMuted = Color(0xFF9DB0A6)
    val Warning = Color(0xFFE8B339)
    val Danger = Color(0xFFE06B5E)

    /** Label chip colours, keyed by how much the app actually knows. */
    val ChipConfirmed = Color(0xE60E3D2A)
    val ChipUnknown = Color(0xE61E2421)
    val ChipIdentifying = Color(0xCC2A2F2C)
}
