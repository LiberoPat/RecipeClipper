import StoreKitTest
import XCTest
@testable import RecipeClipper

/// `StoreKitEntitlements` against the local StoreKit configuration (`RecipeClipper.storekit`,
/// #107) through `SKTestSession`: no App Store, no dialogs. A real purchase needs the product in
/// App Store Connect, which this can't test.
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

    func testAPurchaseUnlocksIsCachedAndIsSeenAgainAtTheNextLaunch() async {
        let store = entitlements()
        await store.refresh()
        XCTAssertFalse(store.state.unlocked)
        XCTAssertNotNil(store.state.price, "the configured product answers with a price")

        let outcome = await store.purchase()
        XCTAssertEqual(outcome, .unlocked)
        XCTAssertTrue(store.state.unlocked)
        XCTAssertTrue(UserDefaults(suiteName: suite)!.bool(forKey: StoreKitEntitlements.cacheKey))

        UserDefaults(suiteName: suite)!.removePersistentDomain(forName: suite)
        let relaunched = entitlements()
        await relaunched.refresh()
        XCTAssertTrue(relaunched.state.unlocked, "found again in Transaction.currentEntitlements")
    }

    func testAskToBuyIsPending() async {
        session.askToBuyEnabled = true
        let store = entitlements()
        let outcome = await store.purchase()
        XCTAssertEqual(outcome, .pending)
        XCTAssertTrue(store.state.pending)
        XCTAssertFalse(store.state.unlocked)
    }
}
