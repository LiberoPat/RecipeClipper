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

    func handle(_ url: URL) {
        guard let shared = DeepLink.sharedUrl(from: url) else { return }
        push(.importUrl(shared))
    }
}
