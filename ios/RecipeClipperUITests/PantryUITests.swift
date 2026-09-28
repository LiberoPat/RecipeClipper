import XCTest

/// The Pantry tab (#51), behind the tab flag: type an item, tap Ran out (#194) and it goes on the
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

        require(app.buttons["Ran out: milk"], "Ran out").tap()
        require(app.buttons["Restock: milk"], "Restock, once it has run out")
        require(text("Run out"), "the Run out section")
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

    private func row(_ name: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "\(name), ")).firstMatch
    }

    /// The row's menu offers only the state its button doesn't (#194): In stock → Running low,
    /// Running low → Restock, Run out → Running low.
    func testTheRowMenuNeverRepeatsTheButton() {
        launch(.empty, flags: ["mealPlan"])
        require(tabBar.buttons["Pantry"], "the Pantry tab").tap()
        let field = require(app.textFields["Add to the pantry"], "Add to the pantry")
        field.tap()
        field.typeText("garlic\n")

        require(row("garlic"), "garlic").press(forDuration: 1.2)
        let runningLow = require(app.buttons["Running low"], "In stock's menu: Running low")
        assertAbsent(app.buttons["Ran out"], "Ran out in the menu, beside the button")
        assertAbsent(app.buttons["Restock"], "Restock, while in stock")
        runningLow.tap()
        require(app.descendants(matching: .any)["low-1"].firstMatch, "the Low tag")

        require(row("garlic"), "garlic").press(forDuration: 1.2)
        require(app.buttons["Restock"], "Running low's menu: Restock").tap()
        requireGone(app.descendants(matching: .any)["low-1"].firstMatch, "the Low tag, once restocked")

        require(app.buttons["Ran out: garlic"], "Ran out").tap()
        require(app.buttons["Restock: garlic"], "Restock, once it has run out")
        require(row("garlic"), "garlic").press(forDuration: 1.2)
        require(app.buttons["Running low"], "Run out's menu: Running low")
        assertAbsent(app.buttons["Restock"], "Restock in the menu, beside the button")
        assertAbsent(app.buttons["Ran out"], "Ran out, once out")
    }
}
