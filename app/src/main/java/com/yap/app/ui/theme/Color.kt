package com.yap.app.ui.theme

import androidx.compose.ui.graphics.Color

val WarmPaper = Color(0xFFFAF8F4)
val WarmSurface = Color(0xFFFFFDF9)
val WarmSurfaceVariant = Color(0xFFF1ECE4)
val Ink = Color(0xFF1C1B18)
val InkMuted = Color(0xFF6F6B63)
val DarkCanvas = Color(0xFF111210)
val DarkSurface = Color(0xFF1A1B18)
val DarkSurfaceVariant = Color(0xFF24251F)
val DarkInk = Color(0xFFF1EDE5)
val DarkMuted = Color(0xFFA7A39A)
val DefaultCoral = Color(0xFFC76F4E)

fun Color.isLight(): Boolean = (0.299 * red + 0.587 * green + 0.114 * blue) > 0.62

fun Color.mixWith(other: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = red * (1 - f) + other.red * f,
        green = green * (1 - f) + other.green * f,
        blue = blue * (1 - f) + other.blue * f,
        alpha = alpha * (1 - f) + other.alpha * f
    )
}

fun Color.darken(fraction: Float): Color = mixWith(Color.Black, fraction)
fun Color.lighten(fraction: Float): Color = mixWith(Color.White, fraction)

fun avatarPalette(seed: Int): Pair<Color, Color> {
    val palettes = listOf(
        Color(0xFFC76F4E) to Color(0xFFE7B59D),
        Color(0xFF6E8B74) to Color(0xFFB9CFB4),
        Color(0xFF647A9B) to Color(0xFFB4C5DB),
        Color(0xFF9A6F86) to Color(0xFFD8B8CA),
        Color(0xFFA07842) to Color(0xFFDCC197),
        Color(0xFF527F82) to Color(0xFFA9CFD1),
        Color(0xFF806B9C) to Color(0xFFC9B9DD),
        Color(0xFFA05C5C) to Color(0xFFD8ABAB),
        Color(0xFF5C7B5E) to Color(0xFFAFC5A8),
        Color(0xFF7F6B55) to Color(0xFFCDBCA3),
        Color(0xFF4F7085) to Color(0xFFB1CBD8),
        Color(0xFF8A7350) to Color(0xFFD1BE98),
        Color(0xFF746C5A) to Color(0xFFC3BBA8)
    )
    return palettes[((seed % palettes.size) + palettes.size) % palettes.size]
}
