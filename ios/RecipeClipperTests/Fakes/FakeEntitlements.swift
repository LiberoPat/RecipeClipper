import Foundation
import Observation
@testable import RecipeClipper

/// `Entitlements` with no store: `purchaseOutcome` and `restoreOutcome` are what the next
/// purchase or restore answers, and `.unlocked` also unlocks `state`. Counts the calls.
@MainActor
@Observable
final class FakeEntitlements: Entitlements {
    var state: UnlockState
    var purchaseOutcome: PurchaseOutcome = .unlocked
    var restoreOutcome: PurchaseOutcome = .nothingToRestore
    private(set) var purchases = 0
    private(set) var restores = 0

    init(_ state: UnlockState = UnlockState()) { self.state = state }

    func purchase() async -> PurchaseOutcome {
        purchases += 1
        return answer(purchaseOutcome)
    }

    func restore() async -> PurchaseOutcome {
        restores += 1
        return answer(restoreOutcome)
    }

    private func answer(_ outcome: PurchaseOutcome) -> PurchaseOutcome {
        if outcome == .unlocked { state.unlocked = true; state.pending = false }
        if outcome == .pending { state.pending = true }
        return outcome
    }
}
