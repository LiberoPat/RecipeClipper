import SwiftUI

/// The app's two typefaces for the widget extension (#309, #455), by PostScript name: a SwiftUI
/// `Font` made from a UIFont with extra descriptor attributes can't be encoded into the archive
/// WidgetKit sends to the system, and the extension crashes drawing it. The fonts are static
/// weights, so each weight is its own name (`AppFont.fontName`); only the ones listed in the
/// extension's `UIAppFonts` register.
enum WidgetFont {
    /// Bricolage Grotesque: Bold, or ExtraBold for `.heavy` and above.
    static func bricolage(_ size: CGFloat, _ weight: Font.Weight) -> Font {
        .custom(AppFont.fontName(display: true, weight: weight >= .heavy ? 800 : 700), size: size)
    }

    /// Instrument Sans: Regular, or SemiBold for `.semibold` and above.
    static func instrumentSans(_ size: CGFloat, _ weight: Font.Weight) -> Font {
        .custom(AppFont.fontName(display: false, weight: weight >= .semibold ? 600 : 400), size: size)
    }
}

private extension Font.Weight {
    private var rank: Int {
        switch self {
        case .ultraLight: 1
        case .thin: 2
        case .light: 3
        case .regular: 4
        case .medium: 5
        case .semibold: 6
        case .bold: 7
        case .heavy: 8
        case .black: 9
        default: 4
        }
    }

    static func >= (lhs: Font.Weight, rhs: Font.Weight) -> Bool { lhs.rank >= rhs.rank }
}
