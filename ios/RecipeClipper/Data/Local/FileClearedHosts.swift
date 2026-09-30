import Foundation

/// `ClearedHosts` (#220) as a small JSON file, `cloudflare-clearances.json` in the app's Caches
/// folder: each host's expiry in epoch millis (Android's `SharedPrefsClearedHosts`). In Caches so
/// it is **not backed up**: the clearance it stands for is a cookie in this phone's web view,
/// which a restore doesn't bring. The system may purge it; that only costs one refused fetch.
/// Expired entries are dropped whenever one is written.
final class FileClearedHosts: ClearedHosts {
    private let file: URL
    private let clock: Clock
    private let lock = NSLock()

    init(clock: Clock, folder: URL? = nil) {
        let caches = folder ?? FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        file = caches.appendingPathComponent("cloudflare-clearances.json")
        self.clock = clock
    }

    func isCleared(_ host: String) -> Bool {
        lock.withLock { (read()[host.lowercased()] ?? 0) > clock.now() }
    }

    func record(_ host: String) {
        lock.withLock {
            let now = clock.now()
            var hosts = read().filter { $0.value > now }
            hosts[host.lowercased()] = now + clearedHostsTTL
            if let data = try? JSONEncoder().encode(hosts) { try? data.write(to: file, options: .atomic) }
        }
    }

    private func read() -> [String: Int64] {
        guard let data = try? Data(contentsOf: file) else { return [:] }
        return (try? JSONDecoder().decode([String: Int64].self, from: data)) ?? [:]
    }
}
