import Foundation
import Observation

/// Owns the navigation path. Views push through it; the share extension's deep link lands
/// here via `.onOpenURL`, and every share pushes a fresh import, even one repeated while the
/// app is running (Android's onNewIntent).
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
