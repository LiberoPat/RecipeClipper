import XCTest

/// The chips under Recipes' search (#378) and the photo grid (#380) end to end. UI tests start
/// with every flag off (UITestSeeding), so each turns on the one it is about.
final class RecipesFiltersGridUITests: RecipeUITestCase {

    private var search: XCUIElement { app.textFields["Search titles and ingredients"] }

    private func chip(_ key: String) -> XCUIElement { app.buttons["recipes.quick.\(key)"] }

    /// "Uses what's in my pantry": the pantry holds chicken thighs and flour (the Use it up
    /// scenario), so the Adobo (thighs, soy sauce) and the Banana Bread (bananas, flour) have one
    /// of two ingredients, which is half. Each says so; the others are gone; the search still applies.
    func testThePantryChipKeepsTheRecipesItCoversAndSaysHowMany() {
        launch(.useItUp, flags: ["recipeFilters"])
        openRecipes()
        require(row("Miso Soup"), "every recipe before the chip")

        let pantry = require(chip("USES_PANTRY"), "the pantry chip")
        pantry.tap()
        requireState(pantry, "isSelected == true", "the chip on")
        require(text("at least half of the ingredients", exactly: false), "the rule, said once")
        requireGone(row("Miso Soup"), "a recipe the pantry doesn't cover")
        requireGone(row("Spaghetti Carbonara"), "a recipe the pantry doesn't cover")
        requireState(row("Chicken Adobo"), "label CONTAINS 'You have 1 of 2'")
        requireState(row("Banana Bread"), "label CONTAINS 'You have 1 of 2'")
        require(text("2 recipes"), "the count is of what is shown")

        // The chips combine with the search.
        search.tap()
        search.typeText("flour")
        requireGone(row("Chicken Adobo"), "the search narrows the chip's recipes")
        require(row("Banana Bread"))

        // A chip that leaves nothing says so, and turns off again.
        search.clearText()
        search.typeText("zzz")
        require(text("No recipes match these filters."))
        pantry.tap()
        search.clearText()
        require(row("Miso Soup"), "every recipe once the chip is off")
        assertAbsent(text("You have 1 of 2"), "no pantry line with the chip off")
    }

    /// "Not cooked yet" keeps every recipe here (nothing is cooked); "30 min or less" never
    /// matches a recipe with no time, so the two together are an empty list with its own line.
    func testChipsCombineAndNeverGuessATime() {
        launch(.standard, flags: ["recipeFilters"])
        openRecipes()

        chip("NOT_COOKED").tap()
        requireState(chip("NOT_COOKED"), "isSelected == true")
        require(row("Banana Bread"), "nothing is cooked yet")
        chip("THIRTY_OR_LESS").tap()
        require(text("No recipes match these filters."), "no recipe states a time")
        chip("THIRTY_OR_LESS").tap()
        chip("NOT_COOKED").tap()
        require(row("Miso Soup"))
    }

    /// #380: the toggle beside the sort turns the rows into a grid; each cell is a photo tile with
    /// its title and opens its recipe; the choice is kept when the app opens again.
    func testTheGridTogglesKeepsTheTitlesAndIsRemembered() {
        launch(.standard, flags: ["recipesGrid"])
        openRecipes()
        let toggle = require(app.buttons["recipes.layout"], "the layout toggle")
        XCTAssertEqual(toggle.label, "Show as a grid")

        toggle.tap()
        require(app.otherElements["recipes.grid"], "the grid")
        XCTAssertEqual(app.buttons["recipes.layout"].label, "Show as a list")
        // Two columns: the first two cells sit side by side, the third below the first.
        let first = require(row("Chicken Adobo"), "a cell with its title").frame
        let second = require(row("Spaghetti Carbonara")).frame
        let third = require(row("Banana Bread")).frame
        XCTAssertEqual(first.minY, second.minY, accuracy: 1, "two columns")
        XCTAssertLessThan(first.minX, second.minX)
        XCTAssertGreaterThan(third.minY, first.minY)
        // A grid has the same search and sort above it.
        require(app.buttons["recipes.sort"], "the sort")

        // Remembered: the app opens on the grid again.
        app.terminate()
        launch(.standard, keepPrefs: true, flags: ["recipesGrid"])
        openRecipes()
        requireState(app.buttons["recipes.layout"], "label == 'Show as a list'", "the stored grid")
        openRecipe("Chicken Adobo")
        back()
        app.buttons["recipes.layout"].tap()
        requireState(app.buttons["recipes.layout"], "label == 'Show as a grid'", "back to rows")
    }
}

private extension XCUIElement {
    func clearText() {
        guard let current = value as? String, !current.isEmpty else { return }
        tap()
        typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count))
    }
}

private extension RecipeUITestCase {
    /// A static text containing `fragment` (`exactly: false`), for a sentence whose tail is long.
    func text(_ fragment: String, exactly: Bool) -> XCUIElement {
        exactly ? text(fragment) : textContaining(fragment)
    }
}
