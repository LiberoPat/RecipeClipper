import Foundation
import Network

/// Connectivity over NWPathMonitor. Each stream gets its own monitor, started on subscribe and
/// cancelled when the stream ends, so nothing runs while no screen is waiting for a reconnect.
/// "Online" is `.satisfied`: a path the system believes can reach the internet.
final class PathConnectivity: Connectivity {
    private let queue = DispatchQueue(label: "RecipeClipper.PathConnectivity")

    func onlineUpdates() -> AsyncStream<Bool> {
        AsyncStream { continuation in
            let monitor = NWPathMonitor()
            var last: Bool?
            // The handler runs on `queue` only, so `last` needs no other synchronisation.
            monitor.pathUpdateHandler = { path in
                let online = path.status == .satisfied
                guard online != last else { return }
                last = online
                continuation.yield(online)
            }
            continuation.onTermination = { _ in monitor.cancel() }
            monitor.start(queue: queue) // the handler fires at once with the current path
        }
    }
}
