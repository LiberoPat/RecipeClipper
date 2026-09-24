import SwiftUI
import UIKit

// Design language from CLAUDE.md, ported from Android's ui/theme/Theme.kt: Fraunces over
// Karla on a warm off-white ground, one paprika accent. Never the default blue/purple.
//
// Every token is a dynamic UIColor, so it follows the system appearance AND a SwiftUI
// `.environment(\.colorScheme, .dark)` override — which is how cook mode is forced onto ink
// when "Dark while cooking" is on.

private enum Tokens {
    static let ground = UIColor(rgb: 0xFBF9F6)
    static let ink = UIColor(rgb: 0x1C1917)
    static let muted = UIColor(rgb: 0x6B6259)
    static let hairline = UIColor(rgb: 0xE7E1D9)
    static let paprika = UIColor(rgb: 0xBF4A2B)

    // Derived for ink (the spec only names the light-side tokens): the spec's muted and
    // paprika are too dim to read as small text on ink.
    static let mutedOnInk = UIColor(rgb: 0xA39A90)
    static let hairlineOnInk = UIColor(rgb: 0x3A342F)
    static let paprikaTextOnInk = UIColor(rgb: 0xE2735A)
}

private extension UIColor {
    convenience init(rgb: UInt32, alpha: CGFloat = 1) {
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: alpha
        )
    }
}

private func dynamicUIColor(light: UIColor, dark: UIColor) -> UIColor {
    UIColor { $0.userInterfaceStyle == .dark ? dark : light }
}

private func dynamic(light: UIColor, dark: UIColor) -> Color {
    Color(uiColor: dynamicUIColor(light: light, dark: dark))
}

/// The colour slots, named after the Material roles the Android screens use.
enum Palette {
    /// Screen ground.
    static let background = dynamic(light: Tokens.ground, dark: Tokens.ink)
    /// Body text.
    static let onBackground = dynamic(light: Tokens.ink, dark: Tokens.ground)
    /// Secondary text (Material's onSurfaceVariant).
    static let muted = dynamic(light: Tokens.muted, dark: Tokens.mutedOnInk)
    /// Dividers (outlineVariant).
    static let hairline = dynamic(light: Tokens.hairline, dark: Tokens.hairlineOnInk)
    /// Button outlines (outline).
    static let outline = dynamic(light: Tokens.muted.withAlphaComponent(0.5), dark: Tokens.mutedOnInk)
    /// Accent for text (tertiary): paprika on the ground, a lighter paprika on ink.
    static let accentText = dynamic(light: Tokens.paprika, dark: Tokens.paprikaTextOnInk)
    /// Filled-button paprika (primary), the same in both schemes.
    static let primary = Color(uiColor: Tokens.paprika)
    static let onPrimary = Color.white
    /// Errors and the swipe-to-delete background.
    static let error = dynamic(light: Tokens.paprika, dark: Tokens.paprikaTextOnInk)
    /// Tonal fills (the servings stepper's buttons).
    static let surfaceContainer = dynamic(light: UIColor(rgb: 0xF3EFE9), dark: UIColor(rgb: 0x26211E))
    /// The snackbar inverts: an ink card on the ground, a light card on ink.
    static let inverseSurface = dynamic(light: Tokens.ink, dark: Tokens.ground)
    static let inverseOnSurface = dynamic(light: Tokens.ground, dark: Tokens.ink)
    static let inversePrimary = dynamic(light: Tokens.paprikaTextOnInk, dark: Tokens.paprika)

    /// `accentText` as a UIColor, for UIKit surfaces SwiftUI's `.tint` never reaches: the
    /// window tint that alerts, menus and text-field cursors inherit. Without it they fall back
    /// to system blue, since the app has no AccentColor asset.
    static let uiAccentText = dynamicUIColor(light: Tokens.paprika, dark: Tokens.paprikaTextOnInk)
}

// MARK: - Type

/// The bundled variable fonts. Each style asks for a weight on the `wght` axis and, for
/// Fraunces, an optical size matching the point size, rather than relying on named instances.
enum AppFont {
    private static let weightAxis = 0x7767_6874 // 'wght'
    private static let opticalSizeAxis = 0x6F70_737A // 'opsz'

    static func fraunces(_ size: CGFloat, weight: CGFloat) -> UIFont {
        variable("Fraunces-Regular", size: size, axes: [
            weightAxis: weight,
            opticalSizeAxis: min(max(size, 9), 144)
        ])
    }

    static func karla(_ size: CGFloat, weight: CGFloat) -> UIFont {
        variable("Karla-Regular", size: size, axes: [weightAxis: weight])
    }

    private static func variable(_ name: String, size: CGFloat, axes: [Int: CGFloat]) -> UIFont {
        let variation = Dictionary(uniqueKeysWithValues: axes.map { (NSNumber(value: $0.key), NSNumber(value: Double($0.value))) })
        let descriptor = UIFontDescriptor(fontAttributes: [
            .name: name,
            UIFontDescriptor.AttributeName(rawValue: kCTFontVariationAttribute as String): variation
        ])
        return UIFont(descriptor: descriptor, size: size)
    }
}

/// One typographic style: Theme.kt's size, weight, line height and tracking at the default
/// (Large) content size, plus the Dynamic Type text style it scales with — parity with
/// Android, where `sp` sizes follow the system font size.
///
/// Why not `Font.custom(name, size:, relativeTo:)`: it takes a font *name*, so it can't carry
/// the variable-font axes (the `wght` each role asks for, Fraunces' `opsz`). Instead
/// `.textStyle(_:)` reads the environment's `dynamicTypeSize` and builds the variable UIFont at
/// the size `UIFontMetrics` gives for it. That re-renders live when the setting changes (the
/// environment value changes), honours a `.dynamicTypeSize(...)` cap on a subtree, and lets
/// Fraunces' optical size follow the scaled point size.
struct TextStyle: Hashable {
    enum Family: Hashable { case fraunces, karla }

    let family: Family
    /// Point size at the default (Large) content size: Theme.kt's value.
    let baseSize: CGFloat
    let weight: CGFloat
    /// Line height at the default content size; scales with the font.
    let baseLineHeight: CGFloat
    var tracking: CGFloat = 0
    /// The Dynamic Type curve this role follows.
    let relativeTo: UIFont.TextStyle
    var tabularDigits = false

    /// The font at the given content size category. Cached: a style is built per render.
    func uiFont(for category: UIContentSizeCategory = .large) -> UIFont {
        let size = scaled(baseSize, category)
        return FontCache.font(for: self, size: size)
    }

    /// The line height at the given content size category.
    func lineHeight(for category: UIContentSizeCategory = .large) -> CGFloat {
        scaled(baseLineHeight, category)
    }

    /// Extra spacing between lines so a wrapped paragraph lands on the scaled line height.
    func lineSpacing(for category: UIContentSizeCategory = .large) -> CGFloat {
        max(0, lineHeight(for: category) - uiFont(for: category).lineHeight)
    }

    /// The default-size font, for callers outside a view (and the tests).
    var uiFont: UIFont { uiFont(for: .large) }

    private func scaled(_ value: CGFloat, _ category: UIContentSizeCategory) -> CGFloat {
        // At Large, UIFontMetrics returns the value unchanged, so the design at the default
        // size is exactly Theme.kt's.
        UIFontMetrics(forTextStyle: relativeTo)
            .scaledValue(for: value, compatibleWith: UITraitCollection(preferredContentSizeCategory: category))
    }

    fileprivate func makeFont(size: CGFloat) -> UIFont {
        let font = family == .fraunces ? AppFont.fraunces(size, weight: weight) : AppFont.karla(size, weight: weight)
        return tabularDigits ? font.withTabularDigits() : font
    }
}

private enum FontCache {
    private struct Key: Hashable { let style: TextStyle; let size: CGFloat }
    private static let lock = NSLock()
    nonisolated(unsafe) private static var fonts: [Key: UIFont] = [:]

    static func font(for style: TextStyle, size: CGFloat) -> UIFont {
        let key = Key(style: style, size: size)
        lock.lock(); defer { lock.unlock() }
        if let font = fonts[key] { return font }
        let font = style.makeFont(size: size)
        fonts[key] = font
        return font
    }
}

/// Theme.kt's AppTypography, slot for slot. Sizes are the default (Large) ones; each role
/// names the text style whose Dynamic Type curve it follows.
enum Typography {
    static let headlineMedium = TextStyle(family: .fraunces, baseSize: 30, weight: 600, baseLineHeight: 36, relativeTo: .title1)
    static let headlineSmall = TextStyle(family: .fraunces, baseSize: 26, weight: 600, baseLineHeight: 32, relativeTo: .title2)
    static let titleLarge = TextStyle(family: .fraunces, baseSize: 22, weight: 600, baseLineHeight: 28, relativeTo: .title3)
    static let titleMedium = TextStyle(family: .fraunces, baseSize: 19, weight: 600, baseLineHeight: 26, relativeTo: .headline)
    static let titleSmall = TextStyle(family: .karla, baseSize: 15, weight: 700, baseLineHeight: 20, relativeTo: .subheadline)
    static let bodyLarge = TextStyle(family: .karla, baseSize: 17, weight: 400, baseLineHeight: 26, relativeTo: .body)
    static let bodyLargeBold = TextStyle(family: .karla, baseSize: 17, weight: 700, baseLineHeight: 26, relativeTo: .body)
    static let bodyMedium = TextStyle(family: .karla, baseSize: 15, weight: 400, baseLineHeight: 22, relativeTo: .callout)
    static let bodySmall = TextStyle(family: .karla, baseSize: 13, weight: 400, baseLineHeight: 18, relativeTo: .footnote)
    static let labelLarge = TextStyle(family: .karla, baseSize: 16, weight: 700, baseLineHeight: 20, relativeTo: .subheadline)
    static let labelMedium = TextStyle(family: .karla, baseSize: 13, weight: 500, baseLineHeight: 16, tracking: 0.5, relativeTo: .caption1)
    static let labelSmall = TextStyle(family: .karla, baseSize: 12, weight: 500, baseLineHeight: 16, tracking: 0.8, relativeTo: .caption1)
    /// The current cook step: bodyLarge at ~21pt, per the UI decisions.
    static let cookStep = TextStyle(family: .karla, baseSize: 21, weight: 400, baseLineHeight: 30, relativeTo: .title3)
    /// A running countdown: headlineMedium's size with tabular digits.
    static let timerClock = TextStyle(family: .fraunces, baseSize: 30, weight: 600, baseLineHeight: 36, relativeTo: .title1, tabularDigits: true)

    static let all: [TextStyle] = [
        headlineMedium, headlineSmall, titleLarge, titleMedium, titleSmall, bodyLarge, bodyLargeBold,
        bodyMedium, bodySmall, labelLarge, labelMedium, labelSmall, cookStep, timerClock
    ]
}

private extension UIFont {
    func withTabularDigits() -> UIFont {
        let settings: [[UIFontDescriptor.FeatureKey: Int]] = [[
            .type: kNumberSpacingType,
            .selector: kMonospacedNumbersSelector
        ]]
        return UIFont(descriptor: fontDescriptor.addingAttributes([.featureSettings: settings]), size: pointSize)
    }
}

/// Applies a TextStyle at the environment's Dynamic Type size.
private struct ScaledTextStyle: ViewModifier {
    let style: TextStyle
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    func body(content: Content) -> some View {
        let category = UIContentSizeCategory(dynamicTypeSize)
        content
            .font(Font(style.uiFont(for: category)))
            .lineSpacing(style.lineSpacing(for: category))
            .tracking(style.tracking)
    }
}

extension View {
    func textStyle(_ style: TextStyle) -> some View {
        modifier(ScaledTextStyle(style: style))
    }
}
