import XCTest

/// The Groceries tab (#50), behind the tab flag: add a recipe's lines from its menu, see them
/// by aisle, type an item, and delete one with Undo.
final class GroceriesUITests: RecipeUITestCase {

    private var tabBar: XCUIElement { app.tabBars.firstMatch }

    /// A line on the list: a button whose label holds its text.
    private func line(_ text: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    func testARecipesLinesGoOnTheListByAisle() {
        launch(.standard, flags: ["mealPlan"])
        require(app.staticTexts["Chicken Adobo"], "Continue cooking").tap()
        require(app.buttons["More options"], "the recipe menu").tap()
        require(app.buttons["Add to groceries"], "Add to groceries").tap()
        require(app.buttons["addToGroceriesButton"], "the sheet's button").tap()
        requireGone(app.buttons["addToGroceriesButton"], "the sheet")

        back()
        require(tabBar.buttons["Groceries"], "the Groceries tab").tap()
        require(text("Meat"), "the meat aisle")
        require(line("2 lb chicken thighs"), "the chicken")
        require(text("Oils, sauces & condiments"), "the condiments aisle")
        require(line("1/2 cup soy sauce"), "the soy sauce")
    }

    func testATypedItemCanBeDeletedAndBroughtBack() {
        launch(.standard, flags: ["mealPlan"])
        require(tabBar.buttons["Groceries"], "the Groceries tab").tap()
        let field = require(app.textFields["Add an item"], "Add an item")
        field.tap()
        field.typeText("milk\n")
        require(line("milk"), "the typed item")
        require(text("Dairy & eggs"), "the dairy aisle")

        // The snackbar lasts four seconds of real time (SnackbarTimeout), so Undo is tapped as
        // soon as it shows. Waiting for the row to go first could outlast it when the machine
        // is busy (several UI test classes in one run), and then Undo was gone: the flake in #91.
        deleteMilk()
        require(app.buttons["Undo"], "the snackbar").tap()
        require(line("milk"), "the item, back")

        // Without Undo, the delete stands.
        deleteMilk()
        requireGone(line("milk"), "the deleted item")
    }

    private func deleteMilk() {
        require(line("milk"), "the item").press(forDuration: 1.0)
        require(app.buttons["Delete"], "the long-press menu").tap()
    }
}
