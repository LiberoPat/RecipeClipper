import XCTest

/// Recipes' views folded into the filter sheet (#454), end to end over design B: the Lists pill left of search
/// opening Lists (Back returns), the sheet's switches and choices narrowing the list with their pills and the
/// badge, Clear, and the flag off keeping design B's shelf. The twin of Android's `RecipesFiltersScreenTest`.
/// The standard seed: Chicken Adobo and Spaghetti Carbonara in lists, Banana Bread and Miso Soup in none, nothing
/// cooked, nothing typed.
final class RecipesFiltersUITests: RecipeUITestCase {

    private let on = ["libraryFirst", "recipesTopB", "recipesFilters", "recipeFilters", "recipesGrid", "multiSelect"]

    private var filter: XCUIElement { app.buttons["recipes.filter"] }
    private var listsPill: XCUIElement { app.buttons["recipes.listsPill"] }
    private var search: XCUIElement { app.textFields["Search titles and ingredients"] }

    private func openFilters() {
        require(filter, "the filter button at the end of search").tap()
        require(app.descendants(matching: .any)["recipes.filterSwitch.THIRTY_OR_LESS"], "the filter sheet")
    }

    private func toggle(_ raw: String) {
        let row = require(app.descendants(matching: .any)["recipes.filterSwitch.\(raw)"])
        let control = row.switches.firstMatch.exists ? row.switches.firstMatch : row
        control.tap()
    }

    private func choose(_ id: String) {
        require(app.buttons[id], id).tap()
    }

    /// Drags the sheet down by its header (a swipe in its middle may only scroll its content).
    private func closeSheet() {
        let sheet = require(app.descendants(matching: .any)["recipes.filterSheet"], "the sheet")
        sheet.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.02))
            .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 1.0)))
        requireGone(app.descendants(matching: .any)["recipes.filterSwitch.THIRTY_OR_LESS"], "the sheet, closed")
    }

    func testListsSitsLeftOfSearchOpensListsAndBackReturns() {
        launch(.standard, flags: on)
        assertAbsent(app.descendants(matching: .any)["recipes.viewsShelf"], "the shelf")
        let pill = require(listsPill, "the Lists pill")
        let field = require(search)
        XCTAssertLessThan(pill.frame.maxX, field.frame.minX + 1, "Lists is left of search")
        XCTAssertEqual(pill.frame.midY, field.frame.midY, accuracy: 12, "on the same row")
        XCTAssertTrue(pill.label.hasPrefix("Lists, "), pill.label)

        pill.tap()
        require(app.buttons["lists.newList"], "the Lists screen")
        back()
        require(listsPill, "Recipes again")
        require(row("Chicken Adobo"))
    }

    func testNotInAListNarrowsTheListAndShowsAPill() {
        launch(.standard, flags: on)
        XCTAssertEqual(require(filter).value as? String ?? "", "")
        openFilters()
        require(app.staticTexts["Where it is, where it came from"], "the second group")
        toggle("NOT_IN_A_LIST")
        closeSheet()

        require(app.buttons["recipes.filterPill.NOT_IN_A_LIST"], "its pill")
        XCTAssertEqual(filter.value as? String, "1 filter on")
        requireGone(row("Chicken Adobo"), "a listed recipe, filtered out")
        require(row("Banana Bread"), "a recipe in no list")
        require(row("Miso Soup"))

        app.buttons["recipes.filterPill.NOT_IN_A_LIST"].tap()
        requireGone(app.buttons["recipes.filterPill.NOT_IN_A_LIST"], "the pill, removed")
        require(row("Chicken Adobo"), "every recipe again")
    }

    func testCookedAndCameFromAreChoicesWithPillsAndClearUndoesThemAll() {
        launch(.standard, flags: on)
        openFilters()
        XCTAssertTrue(require(app.buttons["recipes.filterCooked.ANY"]).isSelected, "Any to start")
        choose("recipes.filterCooked.NOT_YET")
        XCTAssertTrue(app.buttons["recipes.filterCooked.NOT_YET"].isSelected)
        XCTAssertFalse(app.buttons["recipes.filterCooked.ANY"].isSelected)
        choose("recipes.filterOrigin.TYPED")
        closeSheet()

        require(app.buttons["recipes.filterPill.NOT_COOKED"], "Not cooked yet's pill")
        require(app.buttons["recipes.filterPill.TYPED"], "Typed by you's pill")
        XCTAssertEqual(filter.value as? String, "2 filters on")
        require(app.staticTexts["No recipes match these filters."], "nothing typed here")

        openFilters()
        choose("recipes.filterOrigin.ANYWHERE")
        choose("recipes.filterCooked.COOKED")
        closeSheet()
        require(app.buttons["recipes.filterPill.COOKED"], "Cooked's pill")
        assertAbsent(app.buttons["recipes.filterPill.TYPED"])
        require(app.staticTexts["No recipes match these filters."], "nothing cooked here")

        require(app.buttons["recipes.filterClear"]).tap()
        requireGone(app.buttons["recipes.filterPill.COOKED"], "every pill, cleared")
        require(row("Chicken Adobo"), "every recipe again")
        XCTAssertEqual(filter.value as? String ?? "", "")
    }

    func testWithTheFlagOffDesignBStays() {
        launch(.standard, flags: ["libraryFirst", "recipesTopB", "recipeFilters", "recipesGrid", "multiSelect"])
        require(app.descendants(matching: .any)["recipes.viewsShelf"], "design B's shelf")
        require(app.buttons["recipes.lists"], "its Lists cell")
        assertAbsent(listsPill, "the Lists pill")
        openFilters()
        require(app.descendants(matching: .any)["recipes.filterSwitch.NOT_COOKED"], "design B's switches")
        assertAbsent(app.buttons["recipes.filterCooked.ANY"], "the Cooked choice")
    }
}
