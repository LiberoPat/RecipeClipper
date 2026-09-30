import Foundation
@testable import RecipeClipper

/// A RenderedPageSource that answers with `answer` and records every URL it was asked to render
/// (Android's FakeRenderedPageSource). `answer` may suspend, to stand in for a slow page or one
/// cancelled mid-load; it should honour cancellation, as the real one does. `before` runs first
/// with the render's `onChallenge`, to stand in for Cloudflare's check (#220): call it, then
/// suspend for as long as the check lasts.
final class FakeRenderedPageSource: RenderedPageSource {
    private let before: (@escaping () -> Void) async -> Void
    private let answer: (String) async -> String?
    private(set) var requests: [String] = []

    init(
        _ answer: @escaping (String) async -> String? = { _ in nil },
        before: @escaping (@escaping () -> Void) async -> Void = { _ in }
    ) {
        self.before = before
        self.answer = answer
    }

    func render(url: String, onChallenge: @escaping () -> Void) async -> String? {
        requests.append(url)
        await before(onChallenge)
        if Task.isCancelled { return nil }
        return await answer(url)
    }
}

/// In-memory `ClearedHosts` (#220): `cleared` start cleared; `recorded` lists every record.
final class FakeClearedHosts: ClearedHosts {
    private var hosts: Set<String>
    private(set) var recorded: [String] = []

    init(_ cleared: String...) { hosts = Set(cleared) }

    func isCleared(_ host: String) -> Bool { hosts.contains(host) }

    func record(_ host: String) {
        recorded.append(host)
        hosts.insert(host)
    }
}
