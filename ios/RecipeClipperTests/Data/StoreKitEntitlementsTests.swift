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

    private func isListed(_ id: UInt64) async -> Bool {
        for await result in Transaction.currentEntitlements where result.unsafePayloadValue.id == id {
            return true
        }
        return false
    }

    /// `buyProduct` returns before this app's `Transaction.currentEntitlements` has the purchase
    /// (it arrives as if from another device, up to ~300 ms later on a fresh simulator), so wait
    /// for StoreKit to list it before asking the store. The deadline is only for a hang.
    private func waitUntilListed(_ id: UInt64, file: StaticString = #filePath, line: UInt = #line) async throws {
        let deadline = Date().addingTimeInterval(30)
        while Date() < deadline {
            if await isListed(id) { return }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTFail("StoreKit never listed transaction \(id)", file: file, line: line)
    }

    /// Now and then `clearTransactions()` doesn't take: the purchase stays listed for seconds or
    /// for good (a few runs in a hundred), while a second clear drops it at once. So clear until
    /// StoreKit drops it. The deadline is only for a hang.
    private func clearUntilDropped(_ id: UInt64, file: StaticString = #filePath, line: UInt = #line) async throws {
        let deadline = Date().addingTimeInterval(30)
        while Date() < deadline {
            session.clearTransactions()
            let retry = Date().addingTimeInterval(1)
            while Date() < retry {
                if !(await isListed(id)) { return }
                try await Task.sleep(nanoseconds: 20_000_000)
            }
        }
        XCTFail("StoreKit never dropped transaction \(id)", file: file, line: line)
    }

    func testTheProductHasAPriceAndNothingIsOwnedAtFirst() async {
        let store = entitlements()
        await store.refresh()
        XCTAssertFalse(store.state.unlocked)
        XCTAssertNotNil(store.state.price, "the configured product answers with a price")
    }

    func testAPurchaseIsFoundAndCachedAndLosingItLocksAgain() async throws {
        let purchase = try await session.buyProduct(identifier: unlimitedRecipesProductId)
        try await waitUntilListed(purchase.id)
        let store = entitlements()
        await store.refresh()
        XCTAssertTrue(store.state.unlocked, "found in Transaction.currentEntitlements")
        XCTAssertTrue(UserDefaults(suiteName: suite)!.bool(forKey: StoreKitEntitlements.cacheKey))

        // The next launch starts from the cache, before StoreKit has answered.
        XCTAssertTrue(entitlements().state.unlocked)

        // Gone from the account (deleted in StoreKit's transaction manager): the cache follows.
        try await clearUntilDropped(purchase.id)
        await store.refresh()
        XCTAssertFalse(store.state.unlocked, "a purchase no longer owned no longer unlocks")
        XCTAssertFalse(UserDefaults(suiteName: suite)!.bool(forKey: StoreKitEntitlements.cacheKey))
    }
}
