import Foundation
import Observation
import StoreKit

/// The real `Entitlements` on iOS: StoreKit 2, product `unlimited_recipes` (a non-consumable
/// in-app purchase; create it in App Store Connect, #18). Locally it runs against
/// `RecipeClipper.storekit`, the scheme's StoreKit configuration.
///
/// The last answer is cached in `defaults`, so the app judges a recipe by it at launch rather
/// than by "locked" while StoreKit is still being asked. `start` asks at every launch and then
/// listens to `Transaction.updates`, which also brings Ask to Buy approvals, refunds and
/// purchases made on another device.
@MainActor
@Observable
final class StoreKitEntitlements: Entitlements {
    static let cacheKey = "entitlements.unlocked"

    private(set) var state: UnlockState
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private var product: Product?
    @ObservationIgnored private var updates: Task<Void, Never>?

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        state = UnlockState(unlocked: defaults.bool(forKey: Self.cacheKey))
    }

    deinit { updates?.cancel() }

    func start() {
        updates = Task { [weak self] in
            for await result in Transaction.updates {
                if case .verified(let transaction) = result { await transaction.finish() }
                await self?.refresh()
            }
        }
        Task { await refresh() }
    }

    func purchase() async -> PurchaseOutcome {
        guard let product = await loadProduct() else { return .failed }
        do {
            switch try await product.purchase() {
            case .success(.verified(let transaction)):
                await transaction.finish()
                setUnlocked(true)
                return .unlocked
            case .success(.unverified):
                return .failed
            case .pending:
                state.pending = true
                return .pending
            case .userCancelled:
                return .cancelled
            @unknown default:
                return .failed
            }
        } catch {
            return .failed
        }
    }

    func restore() async -> PurchaseOutcome {
        do {
            try await AppStore.sync()
        } catch {
            return .failed
        }
        await refresh()
        return state.unlocked ? .unlocked : .nothingToRestore
    }

    /// The price, and whether this Apple Account owns the unlock (not refunded).
    func refresh() async {
        _ = await loadProduct()
        var owned = false
        for await result in Transaction.currentEntitlements {
            if case .verified(let transaction) = result,
               transaction.productID == unlimitedRecipesProductId, transaction.revocationDate == nil {
                owned = true
            }
        }
        setUnlocked(owned)
    }

    private func loadProduct() async -> Product? {
        if product == nil {
            product = try? await Product.products(for: [unlimitedRecipesProductId]).first
            state.price = product?.displayPrice
        }
        return product
    }

    private func setUnlocked(_ unlocked: Bool) {
        defaults.set(unlocked, forKey: Self.cacheKey)
        state.unlocked = unlocked
        if unlocked { state.pending = false }
    }
}
