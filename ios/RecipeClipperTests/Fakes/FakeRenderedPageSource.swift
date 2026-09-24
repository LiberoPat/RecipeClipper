import Foundation
@testable import RecipeClipper

/// A RenderedPageSource that answers with `answer` and records every URL it was asked to render
/// (Android's FakeRenderedPageSource). `answer` may suspend, to stand in for a slow page or one
/// cancelled mid-load; it should honour cancellation, as the real one does.
final class FakeRenderedPageSource: RenderedPageSource {
    private let answer: (String) async -> String?
    private(set) var requests: [String] = []

    init(_ answer: @escaping (String) async -> String? = { _ in nil }) {
        self.answer = answer
    }

    func render(url: String) async -> String? {
        requests.append(url)
        return await answer(url)
    }
}
