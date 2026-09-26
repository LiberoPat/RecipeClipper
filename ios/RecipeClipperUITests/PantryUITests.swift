import XCTest

/// The Pantry tab (#51), behind the tab flag: type an item, run out of it and it goes on the
/// grocery list by itself, with an "On list" tag that takes it off again (#146).
final class PantryUITests: RecipeUITestCase {

    private var tabBar: XCUIElement { app.tabBars.firstMatch }

    private func button(containing text: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    private var onListTag: XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ OR label == %@", "onList-", "On list")).firstMatch
    }

    func testRunningOutPutsAnItemOnGroceriesAndTheTagTakesItOff() {
        launch(.empty, flags: ["mealPlan"])
        require(tabBar.buttons["Pantry"], "the Pantry tab").tap()
        let field = require(app.textFields["Add to the pantry"], "Add to the pantry")
        field.tap()
        field.typeText("milk\n")
        require(text("Dairy & eggs"), "the dairy aisle")

        require(app.switches["In stock: milk"], "the in-stock switch").tap()
        require(onListTag, "the On list tag")
        assertAbsent(app.buttons["Undo"], "a snackbar")

        require(tabBar.buttons["Groceries"], "the Groceries tab").tap()
        require(button(containing: "milk"), "milk on the list")

        require(tabBar.buttons["Pantry"], "the Pantry tab").tap()
        require(onListTag, "the On list tag").tap()
        requireGone(onListTag, "the tag, once off the list")
        require(tabBar.buttons["Groceries"], "the Groceries tab").tap()
        requireGone(button(containing: "milk"), "milk, off the list")
    }
}
