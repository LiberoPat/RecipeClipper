import Foundation
import Observation

/// Owns the navigation path. Views push through it; a `recipeclipper://import` deep link lands
/// here via `.onOpenURL`, and every one pushes a fresh import, even one repeated while the app
/// is running (Android's onNewIntent). The share extension no longer sends these: it imports
/// on its own (issue #19). The scheme stays as an entry point for Shortcuts and links.
@MainActor
@Observable
final class Router {
    var path: [Route] = []

    func push(_ route: Route) {
        path.append(route)
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
        push(.importUrl(shared))
    }
}
