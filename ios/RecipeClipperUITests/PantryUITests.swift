import XCTest

/// The Pantry tab (#51), behind the tab flag: type an item, run out of it and send it to
/// groceries; tick a grocery off and add it to the pantry.
final class PantryUITests: RecipeUITestCase {

    private var tabBar: XCUIElement { app.tabBars.firstMatch }

    private func button(containing text: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    func testRunningOutSendsAnItemToGroceries() {
        launch(.empty, flags: ["mealPlan"])
        require(tabBar.buttons["Pantry"], "the Pantry tab").tap()
        let field = require(app.textFields["Add to the pantry"], "Add to the pantry")
        field.tap()
        field.typeText("milk\n")
        require(text("Dairy & eggs"), "the dairy aisle")

        require(app.switches["In stock: milk"], "the in-stock switch").tap()
        require(text("milk is out"), "the snackbar")
        require(app.buttons["Add to groceries"], "the snackbar's action").tap()

        require(tabBar.buttons["Groceries"], "the Groceries tab").tap()
        require(button(containing: "milk"), "milk on the list")
    }

    func testTickingAGroceryOffOffersThePantry() {
        launch(.empty, flags: ["mealPlan"])
        require(tabBar.buttons["Groceries"], "the Groceries tab").tap()
        let field = require(app.textFields["Add an item"], "Add an item")
        field.tap()
        field.typeText("2 cups flour\n")
        require(button(containing: "2 cups flour"), "the typed item").tap()

        require(textContaining("Add it to the pantry?"), "the offer")
        require(app.buttons["Add to pantry"], "the offer's action").tap()

        require(tabBar.buttons["Pantry"], "the Pantry tab").tap()
        require(button(containing: "flour"), "flour in the pantry")
    }
}
