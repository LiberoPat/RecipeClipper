import XCTest

/// #457: the Add to groceries sheet says why a line started unticked. Chicken Adobo's chicken
/// thighs are in the `useItUp` scenario's pantry; its soy sauce is not. Android:
/// `AddToGroceriesPantryLabelTest`.
final class AddToGroceriesPantryLabelUITests: RecipeUITestCase {

    private func openSheet(flags: [String]) {
        launch(.useItUp, flags: flags)
        require(app.staticTexts["Chicken Adobo"], "Continue cooking").tap()
        require(app.buttons["More options"], "the recipe menu").tap()
        require(app.buttons["Add to groceries"], "Add to groceries").tap()
        require(app.buttons["addToGroceriesButton"], "the sheet's button")
    }

    private func sheetLine(_ text: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'sheetLine-' AND label CONTAINS %@", text)).firstMatch
    }

    func testThePantryLineSaysSoAndTheOthersDoNot() {
        openSheet(flags: ["groceryPantryLabel"])

        let chicken = require(sheetLine("2 lb chicken thighs"), "the chicken line")
        let soy = require(sheetLine("1/2 cup soy sauce"), "the soy sauce line")
        XCTAssertFalse(chicken.isSelected, "the pantry line starts unticked")
        XCTAssertTrue(soy.isSelected, "the other line starts ticked")
        XCTAssertTrue(chicken.label.contains("In your pantry"), "the pantry line says why: \(chicken.label)")
        XCTAssertFalse(soy.label.contains("In your pantry"), "the other line does not: \(soy.label)")

        // Ticking the marked line anyway keeps the mark: it is about the pantry, not the tick.
        chicken.tap()
        XCTAssertTrue(sheetLine("2 lb chicken thighs").isSelected)
        XCTAssertTrue(sheetLine("2 lb chicken thighs").label.contains("In your pantry"))
    }

    func testWithTheFlagOffThereIsNoLabel() {
        openSheet(flags: [])
        let chicken = require(sheetLine("2 lb chicken thighs"), "the chicken line")
        XCTAssertFalse(chicken.isSelected)
        XCTAssertFalse(chicken.label.contains("In your pantry"))
    }
}
