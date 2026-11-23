package com.example.recipeclipper.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.example.recipeclipper.R

// Design language (CLAUDE.md, "Visual identity: Market" in docs/decisions.md, #455): Bricolage
// Grotesque over Instrument Sans on a crisp white ground, one basil accent, a faint herb-wash
// surface for cards and fields. Never the default Material purple.
// The light tokens are read by print too (PrintStyle, #318): paper is always light.
internal val Ground = Color(0xFFFFFFFF)
internal val Surface = Color(0xFFF2F6F2)
internal val SurfaceHigh = Color(0xFFE6EDE7)
internal val Ink = Color(0xFF14201A)
internal val Muted = Color(0xFF56645B)
internal val Hairline = Color(0xFFE1E7E2)
/** Basil: the filled-button accent. */
internal val Accent = Color(0xFF2C7A4A)
internal val OnAccent = Color(0xFFFFFFFF)
/** Basil for text and icons on the ground: a shade deeper than the fill, for small type. */
internal val AccentText = Color(0xFF25683E)
/** Checkbox and control outlines. */
internal val Outline = Color(0xFF9AA79E)
/** Errors and the swipe-to-delete background: a tomato red (the design names no error colour). */
internal val Danger = Color(0xFFB2371F)

// The on-ink set, for system dark mode and cook mode's "Dark while cooking": a deep green-black
// ground, and the muted, hairline and accent colours that read on it.
internal val InkGround = Color(0xFF0E1310)
internal val SheetOnInk = Color(0xFF151C17)
internal val SurfaceOnInk = Color(0xFF17201A)
internal val SurfaceHighOnInk = Color(0xFF202B24)
internal val TextOnInk = Color(0xFFEDF3EE)
internal val MutedOnInk = Color(0xFF9BA89F)
internal val HairlineOnInk = Color(0xFF28332C)
internal val AccentOnInk = Color(0xFF5DBE80)
internal val OnAccentOnInk = Color(0xFF06170C)
internal val AccentTextOnInk = Color(0xFF7ED39C)
internal val OutlineOnInk = Color(0xFF6B7A70)
internal val DangerOnInk = Color(0xFFF4A28C)

/**
 * One of the five tints of a recipe with no photo (#403): the ground of its image and the
 * colour of the words on it.
 */
@Immutable
class NoPhotoTint(val ground: Color, val ink: Color)

// Basil, lemon, tomato, sky and oat (Market's five): NoPhoto.tintIndex picks one by the recipe's
// uid. The on-ink set is the dark variant: a dim ground with a light word, the same hue.
internal val NoPhotoTintsLight = listOf(
    NoPhotoTint(Color(0xFFDDEFE2), Color(0xFF1C5232)),
    NoPhotoTint(Color(0xFFFBF0C2), Color(0xFF5F4A00)),
    NoPhotoTint(Color(0xFFFADBD2), Color(0xFF842913)),
    NoPhotoTint(Color(0xFFDCEAF5), Color(0xFF1D4A6E)),
    NoPhotoTint(Color(0xFFEFE6D6), Color(0xFF5A4729))
)
internal val NoPhotoTintsOnInk = listOf(
    NoPhotoTint(Color(0xFF1B3324), Color(0xFFA9E3BC)),
    NoPhotoTint(Color(0xFF3A3210), Color(0xFFF6DE7A)),
    NoPhotoTint(Color(0xFF3D1D15), Color(0xFFF7B8A6)),
    NoPhotoTint(Color(0xFF162A3A), Color(0xFFA9CDEA)),
    NoPhotoTint(Color(0xFF2F2A20), Color(0xFFE3D3B4))
)

/** The tints for the scheme in use: light, or on ink (system dark, and cook mode's "Dark while cooking"). */
val LocalNoPhotoTints = staticCompositionLocalOf { NoPhotoTintsLight }

/**
 * The accent tone (#449's "paprika tone", now basil): a soft basil fill for what is on or leads
 * (the Lists cell, the filter button while a filter is on, the pills of the filters on, the open
 * tab), and the deep basil that reads on it. On ink, a dim basil with a light one. The same pair
 * is the scheme's secondaryContainer / onSecondaryContainer.
 */
@Immutable
class AccentTone(val fill: Color, val onFill: Color)

internal val AccentToneLight = AccentTone(Color(0xFFE1F0E5), Color(0xFF1C5232))
internal val AccentToneOnInk = AccentTone(Color(0xFF1B3324), Color(0xFFA9E3BC))

/** The accent tone for the scheme in use. */
val LocalAccentTone = staticCompositionLocalOf { AccentToneLight }

/**
 * Market's corners (#455): 18 for cards and panels, 14 for photos, thumbnails and fields, 20 for
 * the reading view's hero photo, 26 for a sheet's top, pills for buttons, chips and badges, and a
 * small 8 for tags and tiny tiles. Use these rather than a number in a screen.
 */
object Corners {
    val Card = RoundedCornerShape(18.dp)
    val Photo = RoundedCornerShape(14.dp)
    val Field = RoundedCornerShape(14.dp)
    val Hero = RoundedCornerShape(20.dp)
    val Sheet = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    val Small = RoundedCornerShape(8.dp)
    val Check = RoundedCornerShape(7.dp)
    val Pill = RoundedCornerShape(50)
}

private val AppShapes = Shapes(
    extraSmall = Corners.Field,
    small = Corners.Pill,
    medium = Corners.Card,
    large = Corners.Card,
    extraLarge = RoundedCornerShape(26.dp)
)

// Static instances of the published variable fonts (OFL, assets/licenses): Bricolage Grotesque at
// optical size 24 (the display sizes, 19-32), weights 600-800; Instrument Sans 400-700.
val Bricolage = FontFamily(
    Font(R.font.bricolage_semibold, FontWeight.SemiBold),
    Font(R.font.bricolage_bold, FontWeight.Bold),
    Font(R.font.bricolage_extrabold, FontWeight.ExtraBold)
)

val InstrumentSans = FontFamily(
    Font(R.font.instrument_regular, FontWeight.Normal),
    Font(R.font.instrument_medium, FontWeight.Medium),
    Font(R.font.instrument_semibold, FontWeight.SemiBold),
    Font(R.font.instrument_bold, FontWeight.Bold)
)

// Display: Bricolage Bold, tracked in a little (-0.025em, the design's); body: Instrument Sans.
internal val AppTypography = Typography(
    headlineMedium = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.8).sp),
    headlineSmall = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 32.sp, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 27.sp, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.3).sp),
    titleSmall = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp)
)

// `tertiary` is this app's accent-for-text slot: the deeper basil on the ground, a light basil on
// ink. `primary` is the filled-button basil; the tone is the secondary container.
private val LightScheme = lightColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = AccentToneLight.fill,
    onPrimaryContainer = AccentToneLight.onFill,
    secondary = Muted,
    onSecondary = Ground,
    secondaryContainer = AccentToneLight.fill,
    onSecondaryContainer = AccentToneLight.onFill,
    tertiary = AccentText,
    onTertiary = OnAccent,
    background = Ground,
    onBackground = Ink,
    surface = Ground,
    onSurface = Ink,
    surfaceVariant = Hairline,
    onSurfaceVariant = Muted,
    surfaceTint = Color.Transparent,
    outline = Outline,
    outlineVariant = Hairline,
    error = Danger,
    onError = OnAccent,
    // Snackbars use these three. Left undefined, Material fills them with its own baseline -
    // a near-white content colour on whatever container it is given (illegible) and the
    // default purple for the action. The snackbar is an ink card with the light basil for its
    // action, the same pairing dark mode uses.
    inverseSurface = Ink,
    inverseOnSurface = Ground,
    inversePrimary = AccentTextOnInk,
    surfaceContainerLowest = Ground,
    surfaceContainerLow = Ground,
    surfaceContainer = Surface,
    surfaceContainerHigh = SurfaceHigh,
    surfaceContainerHighest = Hairline
)

// The ink scheme, used for system dark mode and for cook mode when the user has turned on
// "Dark while cooking": the on-ink set throughout, a light basil fill with dark words on it.
private val DarkScheme = darkColorScheme(
    primary = AccentOnInk,
    onPrimary = OnAccentOnInk,
    primaryContainer = AccentToneOnInk.fill,
    onPrimaryContainer = AccentToneOnInk.onFill,
    secondary = MutedOnInk,
    onSecondary = InkGround,
    secondaryContainer = AccentToneOnInk.fill,
    onSecondaryContainer = AccentToneOnInk.onFill,
    tertiary = AccentTextOnInk,
    onTertiary = InkGround,
    background = InkGround,
    onBackground = TextOnInk,
    surface = InkGround,
    onSurface = TextOnInk,
    surfaceVariant = HairlineOnInk,
    onSurfaceVariant = MutedOnInk,
    surfaceTint = Color.Transparent,
    outline = OutlineOnInk,
    outlineVariant = HairlineOnInk,
    error = DangerOnInk,
    onError = InkGround,
    // See LightScheme: on an ink ground the snackbar inverts the other way, a light card with
    // the deeper basil for its action.
    inverseSurface = TextOnInk,
    inverseOnSurface = InkGround,
    inversePrimary = AccentText,
    surfaceContainerLowest = InkGround,
    surfaceContainerLow = SheetOnInk,
    surfaceContainer = SurfaceOnInk,
    surfaceContainerHigh = SurfaceHighOnInk,
    surfaceContainerHighest = HairlineOnInk
)

/** The scheme for [dark]: exposed for the theme's tests. */
internal fun appColorScheme(dark: Boolean) = if (dark) DarkScheme else LightScheme

@Composable
fun RecipeClipperTheme(forceDark: Boolean = false, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = forceDark || systemDark
    val scheme = appColorScheme(dark)
    // The bars are transparent (edge-to-edge), so each screen's background shows through;
    // only the icon colour has to follow the scheme, which matters when "Dark while cooking"
    // forces dark on a light system. Dark icons only on the light scheme.
    val isLightScheme = !dark

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = isLightScheme
                isAppearanceLightNavigationBars = isLightScheme
            }
        }
    }

    CompositionLocalProvider(
        LocalNoPhotoTints provides if (dark) NoPhotoTintsOnInk else NoPhotoTintsLight,
        LocalAccentTone provides if (dark) AccentToneOnInk else AccentToneLight
    ) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
