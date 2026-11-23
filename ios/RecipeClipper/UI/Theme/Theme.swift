import SwiftUI
import UIKit

// Design language from CLAUDE.md, ported from Android's ui/theme/Theme.kt ("Visual identity:
// Market" in docs/decisions.md, #455): Bricolage Grotesque over Instrument Sans on a crisp white
// ground, one basil accent, a faint herb-wash surface for cards and fields. Never the default
// blue/purple.
//
// Every token is a dynamic UIColor, so it follows the system appearance AND a SwiftUI
// `.environment(\.colorScheme, .dark)` override — which is how cook mode is forced onto ink
// when "Dark while cooking" is on.

/// The design tokens. Only `Palette`'s dynamic colours are used on screen; the light ones are
/// read by print too (`PrintStyle`, #318): paper is always light.
enum Tokens {
    static let ground = UIColor(rgb: 0xFFFFFF)
    static let surface = UIColor(rgb: 0xF2F6F2)
    static let surfaceHigh = UIColor(rgb: 0xE6EDE7)
    static let ink = UIColor(rgb: 0x14201A)
    static let muted = UIColor(rgb: 0x56645B)
    static let hairline = UIColor(rgb: 0xE1E7E2)
    /// Basil: the filled-button accent.
    static let accent = UIColor(rgb: 0x2C7A4A)
    static let onAccent = UIColor(rgb: 0xFFFFFF)
    /// Basil for text and icons on the ground: a shade deeper than the fill, for small type.
    static let accentText = UIColor(rgb: 0x25683E)
    /// Checkbox and control outlines.
    static let outline = UIColor(rgb: 0x9AA79E)
    /// Errors and the swipe-to-delete background: a tomato red (the design names no error colour).
    static let danger = UIColor(rgb: 0xB2371F)
    /// The accent tone (#449's "paprika tone", now basil): a soft fill and the deep basil on it.
    static let accentTone = UIColor(rgb: 0xE1F0E5)
    static let onAccentTone = UIColor(rgb: 0x1C5232)

    // The on-ink set, for system dark mode and cook mode's "Dark while cooking": a deep
    // green-black ground, and the muted, hairline and accent colours that read on it.
    static let inkGround = UIColor(rgb: 0x0E1310)
    static let sheetOnInk = UIColor(rgb: 0x151C17)
    static let surfaceOnInk = UIColor(rgb: 0x17201A)
    static let surfaceHighOnInk = UIColor(rgb: 0x202B24)
    static let textOnInk = UIColor(rgb: 0xEDF3EE)
    static let mutedOnInk = UIColor(rgb: 0x9BA89F)
    static let hairlineOnInk = UIColor(rgb: 0x28332C)
    static let accentOnInk = UIColor(rgb: 0x5DBE80)
    static let onAccentOnInk = UIColor(rgb: 0x06170C)
    static let accentTextOnInk = UIColor(rgb: 0x7ED39C)
    static let outlineOnInk = UIColor(rgb: 0x6B7A70)
    static let dangerOnInk = UIColor(rgb: 0xF4A28C)
    static let accentToneOnInk = UIColor(rgb: 0x1B3324)
    static let onAccentToneOnInk = UIColor(rgb: 0xA9E3BC)

    /// The five tints of a recipe with no photo (#403): basil, lemon, tomato, sky and oat, each a
    /// ground and the colour of the words on it. `NoPhoto.tintIndex` picks one by the recipe's uid.
    /// The on-ink set is the dark variant: a dim ground with a light word, the same hue.
    static let noPhotoLight: [(ground: UIColor, ink: UIColor)] = [
        (UIColor(rgb: 0xDDEFE2), UIColor(rgb: 0x1C5232)),
        (UIColor(rgb: 0xFBF0C2), UIColor(rgb: 0x5F4A00)),
        (UIColor(rgb: 0xFADBD2), UIColor(rgb: 0x842913)),
        (UIColor(rgb: 0xDCEAF5), UIColor(rgb: 0x1D4A6E)),
        (UIColor(rgb: 0xEFE6D6), UIColor(rgb: 0x5A4729)),
    ]
    static let noPhotoOnInk: [(ground: UIColor, ink: UIColor)] = [
        (UIColor(rgb: 0x1B3324), UIColor(rgb: 0xA9E3BC)),
        (UIColor(rgb: 0x3A3210), UIColor(rgb: 0xF6DE7A)),
        (UIColor(rgb: 0x3D1D15), UIColor(rgb: 0xF7B8A6)),
        (UIColor(rgb: 0x162A3A), UIColor(rgb: 0xA9CDEA)),
        (UIColor(rgb: 0x2F2A20), UIColor(rgb: 0xE3D3B4)),
    ]
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
    static let background = dynamic(light: Tokens.ground, dark: Tokens.inkGround)
    /// Body text.
    static let onBackground = dynamic(light: Tokens.ink, dark: Tokens.textOnInk)
    /// Secondary text (Material's onSurfaceVariant).
    static let muted = dynamic(light: Tokens.muted, dark: Tokens.mutedOnInk)
    /// Dividers (outlineVariant).
    static let hairline = dynamic(light: Tokens.hairline, dark: Tokens.hairlineOnInk)
    /// Checkbox and button outlines (outline).
    static let outline = dynamic(light: Tokens.outline, dark: Tokens.outlineOnInk)
    /// Accent for text (tertiary): the deeper basil on the ground, a light basil on ink.
    static let accentText = dynamic(light: Tokens.accentText, dark: Tokens.accentTextOnInk)
    /// The filled-button basil (primary): basil on the ground, a light basil on ink.
    static let primary = dynamic(light: Tokens.accent, dark: Tokens.accentOnInk)
    /// Words and icons on `primary`: white on basil, a deep green on the light basil.
    static let onPrimary = dynamic(light: Tokens.onAccent, dark: Tokens.onAccentOnInk)
    /// Errors and the swipe-to-delete background.
    static let error = dynamic(light: Tokens.danger, dark: Tokens.dangerOnInk)
    /// Words and icons on `error`.
    static let onError = dynamic(light: Tokens.onAccent, dark: Tokens.inkGround)
    /// Sheets (Android's surfaceContainerLow): the ground, a shade lifted on ink.
    static let surfaceContainerLow = dynamic(light: Tokens.ground, dark: Tokens.sheetOnInk)
    /// The herb-wash surface: cards, the search field, tonal fills (surfaceContainer).
    static let surfaceContainer = dynamic(light: Tokens.surface, dark: Tokens.surfaceOnInk)
    /// Raised tonal fills: the servings stepper's buttons, a card's close button (surfaceContainerHigh).
    static let surfaceContainerHigh = dynamic(light: Tokens.surfaceHigh, dark: Tokens.surfaceHighOnInk)
    /// The accent tone (#449's "paprika tone", now basil): a soft basil fill for what is on or
    /// leads (the Lists cell, the filter button with a filter on, the pills of the filters on, the
    /// open tab), and the deep basil that reads on it. On ink, a dim basil with a light one.
    /// Android's `AccentTone`.
    static let accentTone = dynamic(light: Tokens.accentTone, dark: Tokens.accentToneOnInk)
    static let onAccentTone = dynamic(light: Tokens.onAccentTone, dark: Tokens.onAccentToneOnInk)
    /// The snackbar inverts: an ink card on the ground, a light card on ink.
    static let inverseSurface = dynamic(light: Tokens.ink, dark: Tokens.textOnInk)
    static let inverseOnSurface = dynamic(light: Tokens.ground, dark: Tokens.inkGround)
    static let inversePrimary = dynamic(light: Tokens.accentTextOnInk, dark: Tokens.accentText)

    /// One of the five tints of a recipe's no-photo image (#403): its ground and the words on it,
    /// following the appearance (and cook mode's forced ink).
    static func noPhotoTint(_ index: Int) -> (ground: Color, ink: Color) {
        let i = ((index % Tokens.noPhotoLight.count) + Tokens.noPhotoLight.count) % Tokens.noPhotoLight.count
        return (
            dynamic(light: Tokens.noPhotoLight[i].ground, dark: Tokens.noPhotoOnInk[i].ground),
            dynamic(light: Tokens.noPhotoLight[i].ink, dark: Tokens.noPhotoOnInk[i].ink)
        )
    }

    /// `accentText` as a UIColor, for UIKit surfaces SwiftUI's `.tint` never reaches: the
    /// window tint that alerts, menus and text-field cursors inherit. Without it they fall back
    /// to system blue, since the app has no AccentColor asset.
    static let uiAccentText = dynamicUIColor(light: Tokens.accentText, dark: Tokens.accentTextOnInk)
}

/// Market's corners (#455): 18 for cards and panels, 14 for photos, thumbnails and fields, 20 for
/// the reading view's hero photo, 26 for a sheet's top, pills (`Capsule`) for buttons, chips and
/// badges, and a small 8 for tags and tiny tiles. Use these rather than a number in a view.
/// Android's `Corners`.
enum Radius {
    static let card: CGFloat = 18
    static let photo: CGFloat = 14
    static let field: CGFloat = 14
    static let hero: CGFloat = 20
    static let sheet: CGFloat = 26
    static let small: CGFloat = 8
    static let check: CGFloat = 7
}

// MARK: - Type

/// The bundled fonts (#455): static instances of Bricolage Grotesque (optical size 24, weights
/// 600-800) and Instrument Sans (400-700), registered through `UIAppFonts`. A style asks for a
/// weight; the nearest bundled one is used.
enum AppFont {
    private static let display: [(weight: CGFloat, name: String)] = [
        (600, "BricolageGrotesque-24ptSemiBold"),
        (700, "BricolageGrotesque-24ptBold"),
        (800, "BricolageGrotesque-24ptExtraBold"),
    ]
    private static let body: [(weight: CGFloat, name: String)] = [
        (400, "InstrumentSans-Regular"),
        (500, "InstrumentSans-Medium"),
        (600, "InstrumentSans-SemiBold"),
        (700, "InstrumentSans-Bold"),
    ]

    /// Bricolage Grotesque, the display face.
    static func bricolage(_ size: CGFloat, weight: CGFloat) -> UIFont {
        named(fontName(display: true, weight: weight), size: size, weight: weight)
    }

    /// Instrument Sans, the body face.
    static func instrumentSans(_ size: CGFloat, weight: CGFloat) -> UIFont {
        named(fontName(display: false, weight: weight), size: size, weight: weight)
    }

    /// The PostScript name of the bundled display or body font nearest `weight`.
    static func fontName(display isDisplay: Bool, weight: CGFloat) -> String {
        let fonts = isDisplay ? display : body
        return fonts.min { abs($0.weight - weight) < abs($1.weight - weight) }?.name ?? fonts[0].name
    }

    private static func named(_ name: String, size: CGFloat, weight: CGFloat) -> UIFont {
        if let font = UIFont(name: name, size: size) { return font }
        let system: UIFont.Weight = weight >= 700 ? .bold : weight >= 600 ? .semibold : weight >= 500 ? .medium : .regular
        return .systemFont(ofSize: size, weight: system)
    }
}

/// One typographic style: Theme.kt's size, weight, line height and tracking at the default
/// (Large) content size, plus the Dynamic Type text style it scales with — parity with
/// Android, where `sp` sizes follow the system font size.
///
/// `.textStyle(_:)` reads the environment's `dynamicTypeSize` and builds the UIFont at the size
/// `UIFontMetrics` gives for it. That re-renders live when the setting changes (the environment
/// value changes) and honours a `.dynamicTypeSize(...)` cap on a subtree; the UIFont also carries
/// the tabular-digits feature, which `Font.custom` can't.
struct TextStyle: Hashable {
    enum Family: Hashable { case bricolage, instrumentSans }

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
        let font = family == .bricolage ? AppFont.bricolage(size, weight: weight) : AppFont.instrumentSans(size, weight: weight)
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
/// names the text style whose Dynamic Type curve it follows. Display: Bricolage Bold, tracked in
/// a little (-0.025em, the design's); body: Instrument Sans.
enum Typography {
    static let headlineMedium = TextStyle(family: .bricolage, baseSize: 32, weight: 700, baseLineHeight: 38, tracking: -0.8, relativeTo: .title1)
    static let headlineSmall = TextStyle(family: .bricolage, baseSize: 27, weight: 700, baseLineHeight: 32, tracking: -0.6, relativeTo: .title2)
    static let titleLarge = TextStyle(family: .bricolage, baseSize: 22, weight: 700, baseLineHeight: 27, tracking: -0.4, relativeTo: .title3)
    static let titleMedium = TextStyle(family: .bricolage, baseSize: 19, weight: 700, baseLineHeight: 24, tracking: -0.3, relativeTo: .headline)
    static let titleSmall = TextStyle(family: .instrumentSans, baseSize: 16, weight: 600, baseLineHeight: 21, relativeTo: .subheadline)
    static let bodyLarge = TextStyle(family: .instrumentSans, baseSize: 17, weight: 400, baseLineHeight: 25, relativeTo: .body)
    static let bodyLargeBold = TextStyle(family: .instrumentSans, baseSize: 17, weight: 700, baseLineHeight: 25, relativeTo: .body)
    static let bodyMedium = TextStyle(family: .instrumentSans, baseSize: 15, weight: 400, baseLineHeight: 21, relativeTo: .callout)
    static let bodySmall = TextStyle(family: .instrumentSans, baseSize: 13, weight: 400, baseLineHeight: 18, relativeTo: .footnote)
    static let labelLarge = TextStyle(family: .instrumentSans, baseSize: 15, weight: 700, baseLineHeight: 20, relativeTo: .subheadline)
    static let labelMedium = TextStyle(family: .instrumentSans, baseSize: 13, weight: 600, baseLineHeight: 16, tracking: 0.3, relativeTo: .caption1)
    static let labelSmall = TextStyle(family: .instrumentSans, baseSize: 12, weight: 600, baseLineHeight: 16, tracking: 0.8, relativeTo: .caption1)
    /// The current cook step: bodyLarge at ~21pt, per the UI decisions.
    static let cookStep = TextStyle(family: .instrumentSans, baseSize: 21, weight: 400, baseLineHeight: 29, relativeTo: .title3)
    /// A running countdown: Bricolage ExtraBold at about headlineMedium's size, tabular digits.
    static let timerClock = TextStyle(family: .bricolage, baseSize: 30, weight: 800, baseLineHeight: 36, relativeTo: .title1, tabularDigits: true)

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
