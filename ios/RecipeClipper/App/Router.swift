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

    func handle(_ url: URL) {
        guard let shared = DeepLink.sharedUrl(from: url) else { return }
        push(.importUrl(shared))
    }
}
