import Foundation
@testable import RecipeClipper

/// Answers `result` for every read, recording the pictures asked for. With `held` set, a read
/// waits until `release()`, so a test can look at the state mid-read or leave the screen.
@MainActor
final class FakePhotoTextReader: PhotoTextReader, @unchecked Sendable {
    var result: PhotoTextResult
    private(set) var calls: [[String]] = []
    var held = false
    private var waiting: [CheckedContinuation<Void, Never>] = []

    init(_ result: PhotoTextResult = .read([])) {
        self.result = result
    }

    nonisolated func read(_ imageUrls: [String]) async -> PhotoTextResult {
        await record(imageUrls)
    }

    private func record(_ imageUrls: [String]) async -> PhotoTextResult {
        calls.append(imageUrls)
        if held { await withCheckedContinuation { waiting.append($0) } }
        return result
    }

    func release() {
        held = false
        waiting.forEach { $0.resume() }
        waiting = []
    }
}
