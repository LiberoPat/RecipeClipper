import SwiftUI

/// The app's two typefaces for the widget extension (#309), by name. Not `AppFont`'s UIFonts:
/// a SwiftUI `Font` made from a variable-axis UIFont can't be encoded into the archive WidgetKit
/// sends to the system, and the extension crashes drawing it.
enum WidgetFont {
    static func fraunces(_ size: CGFloat, _ weight: Font.Weight) -> Font {
        .custom("Fraunces-Regular", size: size).weight(weight)
    }

    static func karla(_ size: CGFloat, _ weight: Font.Weight) -> Font {
        .custom("Karla-Regular", size: size).weight(weight)
    }
}
