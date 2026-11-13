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
        // Its target is the minimum touch size, so a near miss doesn't open the edit sheet (#332).
        XCTAssertGreaterThanOrEqual(line.frame.height, 44, "the line's tap target")

        line.tap()
        require(text("Chicken thighs · use by tomorrow"), "the item's heading")
        require(text("Chicken Adobo"), "the saved recipe")
        assertAbsent(text("Banana Bread"), "a recipe in no list")

        require(app.buttons["Plan tomorrow"], "Plan tomorrow").tap()
        requireState(require(app.buttons["Planned tomorrow"], "planned"), "isEnabled == false", "planned once")
    }

    /// #332: the line keeps its place while selecting, so rows never change height under a
    /// dragging finger (#258); it isn't a button then, and a tap where it sits toggles the row.
    func testTheLineKeepsItsPlaceWhileSelecting() {
        launch(.useItUp, flags: ["useItUp"])
        require(app.tabBars.firstMatch.buttons["Pantry"], "the Pantry tab").tap()
        require(lines.firstMatch, "the Use it up line")
        let row = require(pantryRow("chicken thighs"), "the row")
        let height = row.frame.height
        row.press(forDuration: 0.8)
        let selectedCount = app.staticTexts["selectedCount"]
        requireState(selectedCount, "label == '1 selected'", "1 selected")
        XCTAssertEqual(lines.count, 0, "the line isn't a button while selecting")
        XCTAssertEqual(pantryRow("chicken thighs").frame.height, height, accuracy: 0.5, "the row keeps its height")
        pantryRow("chicken thighs").coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.85)).tap()
        requireState(selectedCount, "label == '0 selected'", "a tap on the line toggles the row")
    }

    private func pantryRow(_ name: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "\(name), ")).firstMatch
    }
}
