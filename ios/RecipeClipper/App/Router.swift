import Foundation
import Observation

/// The tabs behind `FeatureFlags.mealPlanTabs` (#47), in the owner's order.
enum AppTab: String, CaseIterable, Hashable {
    case recipes, week, groceries, pantry
}

/// Owns the navigation: the Recipes stack's path and, with the tab bar on, which tab is open.
/// Views push through it; the share extension's deep link lands here via `.onOpenURL`, and every
/// share pushes a fresh import, even one repeated while the app is running (Android's
/// onNewIntent). A share always lands in Recipes, whichever tab is open.
@MainActor
@Observable
final class Router {
    /// The Recipes stack. The placeholder tabs have no destinations yet, so no paths either.
    var path: [Route] = []
    var selectedTab: AppTab = .recipes

    func push(_ route: Route) {
        path.append(route)
    }

    /// A tab chosen in the bar. Choosing Recipes while it is open goes back to Home, as tab bars
    /// conventionally do (Android's `selectTab` does the same).
    func select(_ tab: AppTab) {
        if tab == selectedTab, tab == .recipes { path = [] }
        selectedTab = tab
    }

    func handle(_ url: URL) {
        guard let shared = DeepLink.sharedUrl(from: url) else { return }
        selectedTab = .recipes
        push(.importUrl(shared))
    }
}
