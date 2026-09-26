import XCTest

/// The Groceries tab (#50), behind the tab flag: add a recipe's lines from its menu, see them
/// by aisle, type an item, delete one with Undo, and put ticked items away (#146).
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

    /// #149: the menu offers "Send list" once there's something to buy, and "Paste a list" reads a
    /// sent list back into the "Add this list" sheet, whose lines go in the pantry. The text
    /// "Send list" writes is GroceriesViewModelTests' and SendListTextTests'. The system share
    /// sheet's buttons aren't reachable from a UI test, and the app reads nothing another app
    /// copied without a prompt, so the launch puts the sent text on the pasteboard as the app's
    /// own copy (`-uiTestPasteboard`).
    func testASentListPastesBackIntoThePantry() {
        let sent = "Groceries\n\nMeat\n- 2 lb chicken thighs (Chicken Adobo)\n\nProduce\n- 1 lime"
        let escaped = sent.replacingOccurrences(of: "\n", with: "\\n")
        launch(.standard, flags: ["mealPlan"], extraArguments: ["-uiTestPasteboard", escaped])
        require(tabBar.buttons["Groceries"], "the Groceries tab").tap()
        let field = require(app.textFields["Add an item"], "Add an item")
        field.tap()
        field.typeText("milk\n")
        require(line("milk"), "the typed item")

        require(app.buttons["More options"], "the Groceries menu").tap()
        require(app.buttons["Send list"], "Send list")
        require(app.buttons["Paste a list"], "Paste a list").tap()
        require(text("Add this list"), "the sheet")
        require(line("2 lb chicken thighs (Chicken Adobo)"), "the line, naming its recipe")
        require(line("1 lime"), "the lime").tap() // unticked: stays out
        require(app.buttons["receiveToPantry"], "Add to pantry").tap()

        // The ticked line went in the pantry, which opens.
        require(app.switches["In stock: chicken thighs"], "the chicken in the pantry")
        XCTAssertFalse(app.switches["In stock: lime"].exists, "an unticked line stays out")
    }

    private func deleteMilk() {
        require(line("milk"), "the item").press(forDuration: 1.0)
        require(app.buttons["Delete"], "the long-press menu").tap()
    }

    /// #146: a tick only ticks; "Done shopping" opens the put-away sheet, and one confirm puts
    /// the ticked ones in the pantry and clears the list, with one Undo for both.
    func testDoneShoppingPutsTickedItemsAwayWithOneUndo() {
        launch(.empty, flags: ["mealPlan"])
        require(tabBar.buttons["Groceries"], "the Groceries tab").tap()
        let field = require(app.textFields["Add an item"], "Add an item")
        field.tap()
        field.typeText("2 cups flour\n")
        assertAbsent(app.buttons["doneShopping"], "Done shopping, with nothing ticked")
        require(line("2 cups flour"), "the typed item").tap()
        assertAbsent(app.buttons["Undo"], "a snackbar for a tick")

        // Undo takes it all back: the line returns, and the pantry stays empty.
        putFlourAway()
        require(app.buttons["Undo"], "the snackbar").tap()
        require(line("2 cups flour"), "the item, back")

        putFlourAway()
        requireGone(line("2 cups flour"), "the cleared item")
        require(tabBar.buttons["Pantry"], "the Pantry tab").tap()
        require(app.switches["In stock: flour"], "flour in the pantry")
    }

    private func putFlourAway() {
        require(app.buttons["doneShopping"], "Done shopping").tap()
        require(app.buttons["putAway-new-en-flour"], "flour in the sheet, unticked").tap()
        require(app.buttons["putAwayButton"], "the sheet's button").tap()
    }
}
