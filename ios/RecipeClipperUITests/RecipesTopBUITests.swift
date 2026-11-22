import XCTest

/// The top of Recipes, design B (#449), end to end: the ⋮ menu's Select and Settings, the views shelf
/// navigating, the filter button and its sheet, the pills with their × and Clear, Select all, Today's card
/// keeping its close (#448), and the flag off keeping the old top. The twin of Android's `RecipesTopBScreenTest`.
final class RecipesTopBUITests: RecipeUITestCase {

    private let on = ["libraryFirst", "recipesTopB", "recipeFilters", "recipesGrid", "multiSelect"]

    private var more: XCUIElement { app.buttons["library.more"] }
    private var filter: XCUIElement { app.buttons["recipes.filter"] }

    private func openFilters() {
        require(filter, "the filter button at the end of search").tap()
        require(app.descendants(matching: .any)["recipes.filterSwitch.THIRTY_OR_LESS"], "the filter sheet")
    }

    private func toggle(_ raw: String) {
        let row = require(app.descendants(matching: .any)["recipes.filterSwitch.\(raw)"])
        // The switch itself, not its labels.
        let control = row.switches.firstMatch.exists ? row.switches.firstMatch : row
        control.tap()
    }

    private func closeSheet() {
        app.swipeDown(velocity: .fast)
        requireGone(app.descendants(matching: .any)["recipes.filterSwitch.THIRTY_OR_LESS"], "the sheet, closed")
    }

    // MARK: The title row

    func testTheMoreMenuIsLastAndHoldsSelectAndSettings() {
        launch(.standard, flags: on)

        assertAbsent(app.buttons["recipes.select"], "the old Select button")
        let menu = require(more, "the ⋮")
        XCTAssertGreaterThan(menu.frame.minX, libraryAdd.frame.minX, "the ⋮ is right of the +")

        menu.tap()
        require(app.buttons["Settings"], "Settings in the menu").tap()
        require(settingsRow("oven-temperature"), "Settings")
        back()

        require(more).tap()
        require(app.buttons["Select"], "Select in the menu").tap()
        require(app.descendants(matching: .any)["recipes.selectedCount"], "selecting")
        require(app.buttons["recipes.selection.selectAll"], "Select all").tap()
        let count = app.descendants(matching: .any)["recipes.selectedCount"]
        XCTAssertTrue(count.label.hasSuffix("selected") && !count.label.hasPrefix("0"), "every row ticked: \(count.label)")
        require(app.buttons["recipes.selection.done"]).tap()
        require(more, "the title row again")
    }

    // MARK: The views shelf

    func testTheShelfSitsAboveSearchAndACellNavigates() {
        launch(.standard, flags: on)

        let shelf = require(app.descendants(matching: .any)["recipes.viewsShelf"], "the shelf")
        let search = require(app.textFields["Search titles and ingredients"])
        XCTAssertLessThan(shelf.frame.minY, search.frame.minY, "the shelf is above search")
        assertAbsent(app.descendants(matching: .any)["recipes.viewsStrip"], "the old strip")

        require(app.buttons["recipes.view.NOT_IN_A_LIST"], "Not in a list").tap()
        require(app.descendants(matching: .any)["recipes.viewNote"], "the view's own screen")
        back()

        require(app.buttons["recipes.lists"], "Lists").tap()
        require(app.buttons["lists.newList"], "the Lists screen")
    }

    // MARK: The filters

    func testAFilterFromTheSheetShowsAPillAndCountsOnTheBadge() {
        launch(.standard, flags: on)
        assertAbsent(app.buttons["recipes.quick.THIRTY_OR_LESS"], "the old chips")
        assertAbsent(app.buttons["recipes.filterClear"], "no pills with nothing on")
        XCTAssertEqual(require(filter).value as? String ?? "", "")

        openFilters()
        toggle("THIRTY_OR_LESS")
        toggle("NOT_COOKED")
        closeSheet()

        require(app.buttons["recipes.filterPill.THIRTY_OR_LESS"], "its pill")
        require(app.buttons["recipes.filterPill.NOT_COOKED"], "its pill")
        XCTAssertEqual(filter.value as? String, "2 filters on")

        // A pill's × removes its filter alone.
        app.buttons["recipes.filterPill.NOT_COOKED"].tap()
        requireGone(app.buttons["recipes.filterPill.NOT_COOKED"], "the pill, removed")
        require(app.buttons["recipes.filterPill.THIRTY_OR_LESS"])
        XCTAssertEqual(filter.value as? String, "1 filter on")

        // Clear removes all of them.
        require(app.buttons["recipes.filterClear"]).tap()
        requireGone(app.buttons["recipes.filterPill.THIRTY_OR_LESS"], "every pill, cleared")
        assertAbsent(app.buttons["recipes.filterClear"])
    }

    func testTheSheetsClearTurnsEverySwitchOff() {
        launch(.standard, flags: on)
        openFilters()
        toggle("THIRTY_OR_LESS")
        toggle("USES_PANTRY")

        require(app.buttons["recipes.filterSheet.clear"]).tap()
        closeSheet()

        assertAbsent(app.buttons["recipes.filterPill.THIRTY_OR_LESS"])
        assertAbsent(app.buttons["recipes.filterPill.USES_PANTRY"])
    }

    // MARK: Today's card keeps its close (#448)

    func testTodaysCardKeepsItsClose() {
        launch(.standard, flags: on + ["homeTonight", "todayCardClose"], extraArguments: ["-uiTestPlanToday", "dinner:Chicken Adobo"])

        require(app.buttons["library.card.start"], "Start cooking")
        require(app.buttons["today.close"], "the card's close").tap()
        assertAbsent(app.buttons["library.card.start"], "the card, closed")
    }

    // MARK: The flag off

    func testWithTheFlagOffTheOldTopStays() {
        launch(.standard, flags: ["libraryFirst", "recipeFilters", "multiSelect"])

        require(app.buttons["library.settings"], "the gear")
        require(app.buttons["recipes.select"], "the Select button")
        assertAbsent(more, "the ⋮")
        require(app.buttons["recipes.quick.THIRTY_OR_LESS"], "the chips")
        assertAbsent(filter, "the filter button")
    }
}
