import Foundation

/// The real `ProAccess` (#413; Android's `DefaultProAccess`): the `proUnlock` flag, the store's
/// unlock (StoreKit's, #107) and Developer settings' override, all observable, so whatever reads
/// `state` inside a view or `withObservationTracking` follows a purchase, a refund or a flag change.
@MainActor
final class DefaultProAccess: ProAccess {
    private let flags: FeatureFlags
    private let entitlements: Entitlements

    init(flags: FeatureFlags, entitlements: Entitlements) {
        self.flags = flags
        self.entitlements = entitlements
    }

    var state: ProState {
        let store = entitlements.state
        return ProState(
            gated: flags.isOn(.proUnlock), isPro: store.unlocked || flags.unlockedOverride,
            pending: store.pending, price: store.price
        )
    }

    func purchase() async -> PurchaseOutcome { await entitlements.purchase() }

    func restore() async -> PurchaseOutcome { await entitlements.restore() }
}
