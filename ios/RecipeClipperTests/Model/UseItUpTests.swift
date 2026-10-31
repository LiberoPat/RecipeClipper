import XCTest
@testable import RecipeClipper

/// Android's UseItUpTest (#305): the window, matching by name only, and the groups' order. The
/// corpus's `UseIt` rows pin the matching against the Kotlin.
final class UseItUpTests: XCTestCase {
    private let today: Int64 = 20_000

    private func item(_ name: String, _ expires: Int64?, inStock: Bool = true, alwaysHave: Bool = false, id: Int64 = 1) -> PantryItem {
        PantryItem(id: id, name: name, quantity: nil, language: "en", aisle: .other, inStock: inStock,
                   alwaysHave: alwaysHave, purchasedDay: nil, expiresDay: expires)
    }

    private func recipe(_ id: Int64, _ title: String, _ lines: String...) -> SavedRecipe {
        SavedRecipe(id: id, title: title, imageUrl: nil, language: "en", lines: lines)
    }

    func testSoonIsTheRemindersWindow() {
        XCTAssertTrue(UseItUp.soon(item("spinach", today), today: today))
        XCTAssertTrue(UseItUp.soon(item("spinach", today + 1), today: today))
        XCTAssertFalse(UseItUp.soon(item("spinach", today + 2), today: today))
        XCTAssertFalse(UseItUp.soon(item("spinach", today - 1), today: today))
        XCTAssertFalse(UseItUp.soon(item("spinach", nil), today: today))
        XCTAssertFalse(UseItUp.soon(item("spinach", today, inStock: false), today: today))
        XCTAssertFalse(UseItUp.soon(item("spinach", today, alwaysHave: true), today: today))
    }

    func testGroupsListSoonItemsWithRecipesSoonestFirstRecipesAToZ() {
        let spinach = item("spinach", today + 1, id: 1)
        let milk = item("milk", today, id: 2)
        let eggs = item("eggs", today, id: 3)
        let saved = [recipe(1, "Saag", "500 g spinach"), recipe(2, "bake", "1 cup milk", "200 g spinach")]
        let groups = UseItUp.groups([spinach, milk, eggs], saved: saved, today: today)
        XCTAssertEqual(groups.map(\.item), [milk, spinach])
        XCTAssertEqual(groups[1].recipes.map(\.id), [2, 1])
    }

    func testPlanDaysAreTodayThenTheUseByDay() {
        XCTAssertEqual(UseItUp.planDays(item("milk", today), today: today), [today])
        XCTAssertEqual(UseItUp.planDays(item("milk", today + 1), today: today), [today, today + 1])
    }
}
