package com.example.recipeclipper.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Market (#455), direction A of the visual revamp: the theme's key tokens hold A's values in light
 * and dark, so a stray revert to the old look (or a typo) fails here rather than on someone's phone.
 */
class ThemeTokensTest {

    private fun c(hex: Long) = Color(0xFF000000 or hex)

    @Test fun `the light scheme has Market's values`() {
        val s = appColorScheme(dark = false)
        assertEquals(c(0x2C7A4A), s.primary)
        assertEquals(c(0xFFFFFF), s.onPrimary)
        assertEquals(c(0x25683E), s.tertiary)
        assertEquals(c(0xFFFFFF), s.background)
        assertEquals(c(0x14201A), s.onBackground)
        assertEquals(c(0x56645B), s.onSurfaceVariant)
        assertEquals(c(0xE1E7E2), s.outlineVariant)
        assertEquals(c(0xF2F6F2), s.surfaceContainer)
        assertEquals(c(0xE6EDE7), s.surfaceContainerHigh)
        assertEquals(c(0xE1F0E5), s.secondaryContainer)
        assertEquals(c(0x1C5232), s.onSecondaryContainer)
    }

    @Test fun `the dark scheme is the on-ink set`() {
        val s = appColorScheme(dark = true)
        assertEquals(c(0x5DBE80), s.primary)
        assertEquals(c(0x06170C), s.onPrimary)
        assertEquals(c(0x7ED39C), s.tertiary)
        assertEquals(c(0x0E1310), s.background)
        assertEquals(c(0xEDF3EE), s.onBackground)
        assertEquals(c(0x9BA89F), s.onSurfaceVariant)
        assertEquals(c(0x28332C), s.outlineVariant)
        assertEquals(c(0x17201A), s.surfaceContainer)
        assertEquals(c(0x202B24), s.surfaceContainerHigh)
        assertEquals(c(0x1B3324), s.secondaryContainer)
        assertEquals(c(0xA9E3BC), s.onSecondaryContainer)
    }

    @Test fun `the accent tone pairs are A's`() {
        assertEquals(c(0xE1F0E5), AccentToneLight.fill)
        assertEquals(c(0x1C5232), AccentToneLight.onFill)
        assertEquals(c(0x1B3324), AccentToneOnInk.fill)
        assertEquals(c(0xA9E3BC), AccentToneOnInk.onFill)
    }

    @Test fun `the five no-photo tints are A's, light and on ink`() {
        assertEquals(
            listOf(0xDDEFE2 to 0x1C5232, 0xFBF0C2 to 0x5F4A00, 0xFADBD2 to 0x842913, 0xDCEAF5 to 0x1D4A6E, 0xEFE6D6 to 0x5A4729)
                .map { (g, i) -> c(g.toLong()) to c(i.toLong()) },
            NoPhotoTintsLight.map { it.ground to it.ink }
        )
        assertEquals(
            listOf(0x1B3324 to 0xA9E3BC, 0x3A3210 to 0xF6DE7A, 0x3D1D15 to 0xF7B8A6, 0x162A3A to 0xA9CDEA, 0x2F2A20 to 0xE3D3B4)
                .map { (g, i) -> c(g.toLong()) to c(i.toLong()) },
            NoPhotoTintsOnInk.map { it.ground to it.ink }
        )
    }

    @Test fun `display slots are Bricolage Grotesque and body slots Instrument Sans`() {
        val t = AppTypography
        listOf(t.headlineMedium, t.headlineSmall, t.titleLarge, t.titleMedium).forEach {
            assertEquals(Bricolage, it.fontFamily)
        }
        listOf(t.titleSmall, t.bodyLarge, t.bodyMedium, t.bodySmall, t.labelLarge, t.labelMedium, t.labelSmall).forEach {
            assertEquals(InstrumentSans, it.fontFamily)
        }
    }
}
