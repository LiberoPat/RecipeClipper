import SwiftUI
import UIKit

/// The tab bar in the app's tokens: ground with a hairline above it, the deep basil of the accent
/// tone for the open tab, muted for the rest, Instrument Sans labels (#455). UIKit draws the bar,
/// so this goes through its appearance proxy, once, before the first bar is made.
enum TabBarStyle {
    static let apply: Void = {
        let appearance = UITabBarAppearance()
        appearance.configureWithOpaqueBackground()
        appearance.backgroundColor = UIColor(Palette.background)
        appearance.shadowColor = UIColor(Palette.hairline)
        let font = AppFont.instrumentSans(11, weight: 600)
        for item in [appearance.stackedLayoutAppearance, appearance.inlineLayoutAppearance,
                     appearance.compactInlineLayoutAppearance] {
            item.normal.iconColor = UIColor(Palette.muted)
            item.normal.titleTextAttributes = [.foregroundColor: UIColor(Palette.muted), .font: font]
            item.selected.iconColor = UIColor(Palette.onAccentTone)
            item.selected.titleTextAttributes = [.foregroundColor: UIColor(Palette.onAccentTone), .font: font]
        }
        UITabBar.appearance().standardAppearance = appearance
        UITabBar.appearance().scrollEdgeAppearance = appearance
    }()
}
