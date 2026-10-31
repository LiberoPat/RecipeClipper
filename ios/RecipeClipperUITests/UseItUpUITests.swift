import XCTest

/// Use it up (#305): a pantry item expiring soon says how many saved recipes use it; the line
/// opens them in a sheet, where one tap plans one. Banana Bread uses the flour expiring today,
/// but is in no list, so the flour has no line.
final class UseItUpUITests: RecipeUITestCase {

    private var lines: XCUIElementQuery {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'use-it-up-'"))
    }

    func testTheRowOpensItsSavedRecipesAndOneTapPlansOne() {
        // UI tests start with every flag off (UITestSeeding): turn this one on.
        launch(.useItUp, flags: ["useItUp"])
        require(app.tabBars.firstMatch.buttons["Pantry"], "the Pantry tab").tap()
        let line = require(lines.firstMatch, "the Use it up line")
        XCTAssertEqual(line.label, "1 saved recipe uses it")
        XCTAssertEqual(lines.count, 1, "only the saved recipe's item has a line")

        line.tap()
        require(text("Chicken thighs expires tomorrow."), "the item's heading")
        require(text("Chicken Adobo"), "the saved recipe")
        assertAbsent(text("Banana Bread"), "a recipe in no list")

        require(app.buttons["Plan tomorrow"], "Plan tomorrow").tap()
        requireState(require(app.buttons["Planned tomorrow"], "planned"), "isEnabled == false", "planned once")
    }
}
