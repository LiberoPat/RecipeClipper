package com.example.recipeclipper.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.example.recipeclipper.R

// Design language from CLAUDE.md: Fraunces over Karla on a warm off-white ground, one
// paprika accent. Cook mode inverts to ink. Never fall back to the default Material purple.
private val Ground = Color(0xFFFBF9F6)
private val Ink = Color(0xFF1C1917)
private val Muted = Color(0xFF6B6259)
private val Hairline = Color(0xFFE7E1D9)
private val Paprika = Color(0xFFBF4A2B)

// Derived for the inverted cook view (the spec only names the light-side tokens): the
// spec's muted and paprika are too dim to read as small text on ink.
private val MutedOnInk = Color(0xFFA39A90)
private val HairlineOnInk = Color(0xFF3A342F)
private val PaprikaTextOnInk = Color(0xFFE2735A)

// The fonts are variable files; weight is picked per entry. Below API 26 Android ignores the
// variation and uses the file's default weight, which reads fine but not bold.
@OptIn(ExperimentalTextApi::class)
private fun variable(resId: Int, weight: FontWeight) = Font(
    resId = resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

val Fraunces = FontFamily(
    variable(R.font.fraunces, FontWeight.Normal),
    variable(R.font.fraunces, FontWeight.SemiBold),
    variable(R.font.fraunces, FontWeight.Bold)
)

val Karla = FontFamily(
    variable(R.font.karla, FontWeight.Normal),
    variable(R.font.karla, FontWeight.Medium),
    variable(R.font.karla, FontWeight.Bold)
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 26.sp),
    titleSmall = TextStyle(fontFamily = Karla, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Karla, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = Karla, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = Karla, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Karla, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Karla, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = Karla, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp)
)

// `tertiary` is this app's accent-for-text slot: paprika on the ground, a lighter paprika on ink.
// `primary` is the filled-button paprika in both.
private val LightScheme = lightColorScheme(
    primary = Paprika,
    onPrimary = Color.White,
    primaryContainer = Hairline,
    onPrimaryContainer = Ink,
    secondary = Muted,
    onSecondary = Ground,
    secondaryContainer = Hairline,
    onSecondaryContainer = Ink,
    tertiary = Paprika,
    onTertiary = Color.White,
    background = Ground,
    onBackground = Ink,
    surface = Ground,
    onSurface = Ink,
    surfaceVariant = Hairline,
    onSurfaceVariant = Muted,
    surfaceTint = Color.Transparent,
    outline = Color(0x806B6259),
    outlineVariant = Hairline,
    error = Paprika,
    onError = Color.White,
    // Snackbars use these three. Left undefined, Material fills them with its own baseline -
    // a near-white content colour on whatever container it is given (illegible) and the
    // default purple for the action. The snackbar is an ink card with the lighter paprika
    // for its action, the same pairing cook mode already uses.
    inverseSurface = Ink,
    inverseOnSurface = Ground,
    inversePrimary = PaprikaTextOnInk,
    surfaceContainerLowest = Ground,
    surfaceContainerLow = Ground,
    surfaceContainer = Color(0xFFF3EFE9),
    surfaceContainerHigh = Color(0xFFEEE9E2),
    surfaceContainerHighest = Hairline
)


// The ink scheme, used for system dark mode and for cook mode when the user has turned on
// "Dark while cooking". The spec only names the light-side tokens, so muted, hairline and
// the accent are the on-ink variants - the plain ones are too dim to read here.
private val DarkScheme = darkColorScheme(
    primary = Paprika,
    onPrimary = Color.White,
    primaryContainer = HairlineOnInk,
    onPrimaryContainer = Ground,
    secondary = MutedOnInk,
    onSecondary = Ink,
    secondaryContainer = HairlineOnInk,
    onSecondaryContainer = Ground,
    tertiary = PaprikaTextOnInk,
    onTertiary = Ink,
    background = Ink,
    onBackground = Ground,
    surface = Ink,
    onSurface = Ground,
    surfaceVariant = HairlineOnInk,
    onSurfaceVariant = MutedOnInk,
    surfaceTint = Color.Transparent,
    outline = MutedOnInk,
    outlineVariant = HairlineOnInk,
    error = PaprikaTextOnInk,
    onError = Ink,
    // See LightScheme: on an ink ground the snackbar inverts the other way, a light card
    // with the standard paprika for its action.
    inverseSurface = Ground,
    inverseOnSurface = Ink,
    inversePrimary = Paprika,
    surfaceContainerLowest = Ink,
    surfaceContainerLow = Ink,
    surfaceContainer = Color(0xFF26211E),
    surfaceContainerHigh = Color(0xFF2E2825),
    surfaceContainerHighest = HairlineOnInk
)

@Composable
fun RecipeClipperTheme(forceDark: Boolean = false, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = forceDark || systemDark
    val scheme = if (dark) DarkScheme else LightScheme
    // Status/nav bar icons are light-appearance (dark icons) only on the light scheme.
    val isLightScheme = !dark

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = scheme.background.toArgb()
            window.navigationBarColor = scheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = isLightScheme
                isAppearanceLightNavigationBars = isLightScheme
            }
        }
    }

    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
