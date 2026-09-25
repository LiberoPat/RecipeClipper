import SwiftUI
import UIKit

/// The tab bar in the app's tokens: ground with a hairline above it, paprika for the open tab,
/// muted for the rest, Karla labels. UIKit draws the bar, so this goes through its appearance
/// proxy, once, before the first bar is made.
enum TabBarStyle {
    static let apply: Void = {
        let appearance = UITabBarAppearance()
        appearance.configureWithOpaqueBackground()
        appearance.backgroundColor = UIColor(Palette.background)
        appearance.shadowColor = UIColor(Palette.hairline)
        let font = AppFont.karla(11, weight: 500)
        for item in [appearance.stackedLayoutAppearance, appearance.inlineLayoutAppearance,
                     appearance.compactInlineLayoutAppearance] {
            item.normal.iconColor = UIColor(Palette.muted)
            item.normal.titleTextAttributes = [.foregroundColor: UIColor(Palette.muted), .font: font]
            item.selected.iconColor = Palette.uiAccentText
            item.selected.titleTextAttributes = [.foregroundColor: Palette.uiAccentText, .font: font]
        }
        UITabBar.appearance().standardAppearance = appearance
        UITabBar.appearance().scrollEdgeAppearance = appearance
    }()
}
