import Foundation
@testable import RecipeClipper

/// Lets queued main-actor work, main-queue deliveries (the ViewModels' `receive(on:)`) and
/// the fakes' async calls finish: the counterpart of `advanceUntilIdle` for work that doesn't
/// involve the clock.
@MainActor
func settleMain() async {
    for _ in 0 ..< 4 {
        for _ in 0 ..< 20 { await Task.yield() }
        try? await Task.sleep(nanoseconds: 3_000_000)
    }
}

/// A debounce that doesn't really wait: it yields once, then honours cancellation, so rapid
/// changes that replace each other before running still collapse to the last one.
let immediateSleep: Sleep = { _ in
    await Task.yield()
    try Task.checkCancellation()
}

func testSummary(_ id: Int64, title: String? = nil, isSaved: Bool = false) -> RecipeSummary {
    RecipeSummary(id: id, title: title ?? "Recipe \(id)", imageUrl: nil, totalTime: nil, lastViewedAt: id, isSaved: isSaved)
}
