import Foundation
import Observation

/// The Pro screen's own state (#413; Android's `ProUiState`): `busy` while the store's sheet or a
/// restore is under way (so the buttons can't be doubled), and `notice` a purchase or restore that
/// didn't simply unlock, until the next one starts. Pro itself is `ProViewModel.pro`, read live.
struct ProUiState: Equatable {
    var busy = false
    var notice: PurchaseOutcome?
}

/// The one explainer every locked Week extra opens (#413), and Settings' Pro page (Android's
/// `ProViewModel`): what Pro adds, the unlock (the one-time purchase of #107, now Pro) and Restore
/// purchases. The price is the store's, shown only once the store has given it.
@MainActor
@Observable
final class ProViewModel {
    private(set) var uiState = ProUiState()
    @ObservationIgnored private let access: ProAccess

    init(pro: ProAccess) {
        access = pro
    }

    /// Pro as the store and Developer settings have it, now (observable through the access).
    var pro: ProState { access.state }

    /// "Unlock Pro": the store's purchase sheet. Returns the work so a test can await it.
    @discardableResult
    func onUnlock() -> Task<Void, Never>? { run { [access] in await access.purchase() } }

    /// "Restore purchases": asks the store for this account's purchase again.
    @discardableResult
    func onRestore() -> Task<Void, Never>? { run { [access] in await access.restore() } }

    private func run(_ action: @escaping @MainActor () async -> PurchaseOutcome) -> Task<Void, Never>? {
        guard !uiState.busy else { return nil }
        uiState.busy = true
        uiState.notice = nil
        return Task {
            let outcome = await action()
            uiState.busy = false
            uiState.notice = outcome.needsNotice ? outcome : nil
        }
    }
}
