import SwiftUI
import UIKit

/// The Week, Groceries and Pantry tabs until their features land (#49–#51): the tab's name, one
/// line on what it will hold, and "Coming soon". Deliberately quiet, with nothing to tap.
struct ComingSoonScreen: View {
    let title: String
    let description: String

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ScreenTitle(title).padding(.bottom, 12)
                Hairline()
                Text(description)
                    .textStyle(Typography.bodyLarge)
                    .foregroundStyle(Palette.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 24)
                Text(Strings.comingSoon)
                    .textStyle(Typography.labelMedium)
                    .foregroundStyle(Palette.accentText)
                    .padding(.top, 8)
            }
            .padding(.horizontal, 20)
            .padding(.top, 4)
            .padding(.bottom, 32)
        }
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
    }
}

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
