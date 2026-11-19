import XCTest
@testable import RecipeClipper

/// The Week menu's shopping items (#384): one "Shop for this week" with the flag on, the old pair
/// with it off. Android's WeekScreenTest has the screen side.
final class WeekShoppingMenuTests: XCTestCase {

    private func items(shop: Bool, need: Bool = true, add: Bool = true, month: Bool = false) -> [WeekShoppingMenu.Item] {
        WeekShoppingMenu.items(shopForWeek: shop, canOpenWhatINeed: need, canAddToGroceries: add, inMonth: month)
    }

    func testTheFlagOnGivesTheOneItem() {
        XCTAssertEqual(items(shop: true), [.shopForWeek])
    }

    func testTheFlagOffGivesTheOldPair() {
        XCTAssertEqual(items(shop: false), [.whatINeed, .addWeekIngredients])
    }

    func testTheMonthViewHasNeither() {
        XCTAssertEqual(items(shop: true, month: true), [])
        XCTAssertEqual(items(shop: false, month: true), [])
    }

    func testAMissingEntryPointLeavesItOut() {
        XCTAssertEqual(items(shop: true, need: false), [])
        XCTAssertEqual(items(shop: false, need: false), [.addWeekIngredients])
        XCTAssertEqual(items(shop: false, add: false), [.whatINeed])
    }

    func testTheFlagIsDeclaredAndOnByDefault() throws {
        let flag = try XCTUnwrap(FlagRegistry.definitions.first { $0.key == Flag.shopForWeek.rawValue })
        XCTAssertTrue(flag.defaultValue(isDebug: true))
        XCTAssertTrue(flag.defaultValue(isDebug: false))
    }
}
