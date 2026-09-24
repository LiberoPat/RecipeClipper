import Foundation
import Observation

/// The tabs behind `FeatureFlags.mealPlanTabs` (#47), in the owner's order.
enum AppTab: String, CaseIterable, Hashable {
    case recipes, week, groceries, pantry
}

/// Owns the navigation: the Recipes stack's path and, with the tab bar on, which tab is open.
/// Views push through it; a `recipeclipper://import` deep link lands here via `.onOpenURL`, and
/// every one pushes a fresh import, even one repeated while the app is running (Android's
/// onNewIntent). The share extension no longer sends these: it imports on its own (issue #19).
/// The scheme stays as an entry point for Shortcuts and links, and, like a share, always lands
/// in Recipes, whichever tab is open.
@MainActor
@Observable
final class Router {
    /// The Recipes stack. The placeholder tabs have no destinations yet, so no paths either.
    var path: [Route] = []
    /// The Week tab's stack (#49).
    var weekPath: [Route] = []
    var selectedTab: AppTab = .recipes

    func push(_ route: Route) {
        path.append(route)
    }

    /// A tab chosen in the bar. Choosing Recipes while it is open goes back to Home, as tab bars
    /// conventionally do (Android's `selectTab` does the same).
    func select(_ tab: AppTab) {
        if tab == selectedTab, tab == .recipes { path = [] }
        if tab == selectedTab, tab == .week { weekPath = [] }
        selectedTab = tab
    }

    /// A route that must land in Recipes regardless of which tab is open (a share, a tapped
    /// timer notification): switch tab first, then push on top of whatever the Recipes stack
    /// already held (Android's `openRoute`).
    func openInRecipes(_ route: Route) {
        selectedTab = .recipes
        push(route)
    }

    /// Drops the top `count` entries and pushes `route` in their place: how a saved edit
    /// reopens the recipe afresh instead of returning to the copy loaded before it.
    func replace(last count: Int, with route: Route) {
        path.removeLast(min(count, path.count))
        path.append(route)
    }

    /// A saved clip replaces both the clip screen and the error screen under it, so Back from
    /// the recipe goes where the share came from.
    func openSavedClip(_ id: Int64) {
        if case .clip = path.last { path.removeLast() }
        if case .importUrl = path.last { path.removeLast() }
        path.append(.recipe(id: id))
    }

    func handle(_ url: URL) {
        guard let shared = DeepLink.sharedUrl(from: url) else { return }
        openInRecipes(.importUrl(shared))
    }
}
