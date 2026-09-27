import StoreKitTest
import XCTest
@testable import RecipeClipper

/// `StoreKitEntitlements` against the local StoreKit configuration (`RecipeClipper.storekit`,
/// #107) through `SKTestSession`: no App Store, no dialogs. The purchase is made by the session
/// (`buyProduct`), as if on another device: `Product.purchase()` needs a window scene to show
/// its sheet, which a hosted unit test hasn't got, so it waits forever there. The sheet itself
/// is checked by hand in the simulator (docs/testing.md), and a real purchase needs the product
/// in App Store Connect (#18).
@MainActor
final class StoreKitEntitlementsTests: XCTestCase {
    private var session: SKTestSession!
    private let suite = "StoreKitEntitlementsTests"

    override func setUp() async throws {
        session = try SKTestSession(configurationFileNamed: "RecipeClipper")
        session.disableDialogs = true
        session.resetToDefaultState()
        session.clearTransactions()
        UserDefaults(suiteName: suite)!.removePersistentDomain(forName: suite)
    }

    override func tearDown() async throws {
        session.clearTransactions()
        session = nil
    }

    private func entitlements() -> StoreKitEntitlements {
        StoreKitEntitlements(defaults: UserDefaults(suiteName: suite)!)
    }

    func testTheProductHasAPriceAndNothingIsOwnedAtFirst() async {
        let store = entitlements()
        await store.refresh()
        XCTAssertFalse(store.state.unlocked)
        XCTAssertNotNil(store.state.price, "the configured product answers with a price")
    }

    func testAPurchaseIsFoundAndCachedAndLosingItLocksAgain() async throws {
        try await session.buyProduct(identifier: unlimitedRecipesProductId)
        let store = entitlements()
        await store.refresh()
        XCTAssertTrue(store.state.unlocked, "found in Transaction.currentEntitlements")
        XCTAssertTrue(UserDefaults(suiteName: suite)!.bool(forKey: StoreKitEntitlements.cacheKey))

        // The next launch starts from the cache, before StoreKit has answered.
        XCTAssertTrue(entitlements().state.unlocked)

        // Gone from the account (deleted in StoreKit's transaction manager): the cache follows.
        session.clearTransactions()
        await store.refresh()
        XCTAssertFalse(store.state.unlocked, "a purchase no longer owned no longer unlocks")
        XCTAssertFalse(UserDefaults(suiteName: suite)!.bool(forKey: StoreKitEntitlements.cacheKey))
    }
}
