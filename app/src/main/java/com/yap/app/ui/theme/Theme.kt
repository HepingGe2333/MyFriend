package com.yap.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yap.app.data.prefs.AppSettings
import com.yap.app.data.prefs.ThemeMode

val LocalAccentColor = staticCompositionLocalOf { DefaultCoral }
val LocalAnimationsEnabled = staticCompositionLocalOf { true }
val LocalAppDark = staticCompositionLocalOf { false }

@Composable
fun YapTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val accent = Color(settings.accentColor)
    val colorScheme = if (dark) darkScheme(accent) else lightScheme(accent)
    val density = LocalDensity.current
    val scaledDensity = Density(
        density = density.density,
        fontScale = density.fontScale * settings.fontScale
    )
    CompositionLocalProvider(
        LocalDensity provides scaledDensity,
        LocalAccentColor provides if (dark) accent.lighten(0.12f) else accent,
        LocalAnimationsEnabled provides settings.animationsEnabled,
        LocalAppDark provides dark
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = myTypography,
            shapes = myShapes,
            content = content
        )
    }
}

private fun lightScheme(accent: Color): ColorScheme {
    val primary = if (accent.isLight()) accent.darken(0.22f) else accent
    val onPrimary = if (primary.isLight()) Color(0xFF1F1B18) else Color.White
    return lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = accent.mixWith(Color.White, 0.72f),
        onPrimaryContainer = accent.darken(0.55f),
        secondary = WarmInkSecondary,
        onSecondary = Color.White,
        secondaryContainer = WarmSurfaceVariant,
        onSecondaryContainer = Ink,
        tertiary = Color(0xFF6F7F6A),
        onTertiary = Color.White,
        background = WarmPaper,
        onBackground = Ink,
        surface = WarmSurface,
        onSurface = Ink,
        surfaceVariant = WarmSurfaceVariant,
        onSurfaceVariant = InkMuted,
        surfaceContainer = Color(0xFFF7F3ED),
        surfaceContainerLow = Color(0xFFFBF8F3),
        surfaceContainerHigh = Color(0xFFF2EDE6),
        surfaceContainerHighest = Color(0xFFECE6DE),
        outline = Color(0xFFD8D1C6),
        outlineVariant = Color(0xFFE8E1D7),
        error = Color(0xFFA9423A),
        onError = Color.White,
        errorContainer = Color(0xFFF8E0DD),
        onErrorContainer = Color(0xFF611A16),
        inverseSurface = Color(0xFF2C2B27),
        inverseOnSurface = Color(0xFFF4F0E9),
        scrim = Color.Black,
        surfaceTint = primary
    )
}

private val WarmInkSecondary = Color(0xFF6E6459)

private fun darkScheme(accent: Color): ColorScheme {
    val primary = if (accent.isLight()) accent else accent.lighten(0.2f)
    val onPrimary = if (primary.isLight()) Color(0xFF211A16) else Color.White
    return darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = accent.darken(0.45f),
        onPrimaryContainer = accent.lighten(0.65f),
        secondary = Color(0xFFC7C0B4),
        onSecondary = Color(0xFF2E2D28),
        secondaryContainer = DarkSurfaceVariant,
        onSecondaryContainer = DarkInk,
        tertiary = Color(0xFFA7B8A2),
        onTertiary = Color(0xFF1E2A1E),
        background = DarkCanvas,
        onBackground = DarkInk,
        surface = DarkSurface,
        onSurface = DarkInk,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = DarkMuted,
        surfaceContainer = Color(0xFF1E1F1B),
        surfaceContainerLow = Color(0xFF181915),
        surfaceContainerHigh = Color(0xFF242520),
        surfaceContainerHighest = Color(0xFF2B2C26),
        outline = Color(0xFF3D3E37),
        outlineVariant = Color(0xFF2E2F29),
        error = Color(0xFFE8A09A),
        onError = Color(0xFF4A1210),
        errorContainer = Color(0xFF5C2521),
        onErrorContainer = Color(0xFFFFDAD6),
        inverseSurface = Color(0xFFEDE9E1),
        inverseOnSurface = Color(0xFF2B2B27),
        scrim = Color.Black,
        surfaceTint = primary
    )
}

private val baseTypography = Typography()
private val myTypography = Typography(
    displayLarge = baseTypography.displayLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
    displayMedium = baseTypography.displayMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
    displaySmall = baseTypography.displaySmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
    headlineLarge = baseTypography.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineMedium = baseTypography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    headlineSmall = baseTypography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = baseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    titleMedium = baseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    titleSmall = baseTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = baseTypography.bodyLarge.copy(lineHeight = 25.sp, letterSpacing = 0.05.sp),
    bodyMedium = baseTypography.bodyMedium.copy(lineHeight = 22.sp, letterSpacing = 0.05.sp),
    bodySmall = baseTypography.bodySmall.copy(lineHeight = 18.sp),
    labelLarge = baseTypography.labelLarge.copy(fontWeight = FontWeight.Medium),
    labelMedium = baseTypography.labelMedium.copy(letterSpacing = 0.2.sp),
    labelSmall = baseTypography.labelSmall.copy(letterSpacing = 0.2.sp)
)

private val myShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(32.dp)
)

fun brandTextStyle(base: TextStyle): TextStyle = base.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
