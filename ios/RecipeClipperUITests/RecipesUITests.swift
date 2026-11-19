import XCTest

/// Recipes (#102, it replaced History) end to end. Search goes through
/// the real SQL (instr, never LIKE); delete is a hard delete with an undo snackbar that
/// restores the row with its list membership.
final class RecipesUITests: RecipeUITestCase {

    private var search: XCUIElement { app.textFields["Search titles and ingredients"] }

    private func type(_ query: String) {
        search.tap()
        search.typeText(query)
    }

    /// Swipes a row away. A long swipe may complete the delete on its own; otherwise it reveals
    /// the Delete action, which is tapped.
    private func swipeDelete(_ title: String) {
        require(row(title)).swipeLeft()
        let action = app.buttons["Delete"]
        if action.waitForExistence(timeout: 2) { action.tap() }
        requireGone(row(title), "\(title) to be swiped away")
    }

    func testNewestFirst() {
        launch()
        openRecipes()

        let titles = ["Chicken Adobo", "Spaghetti Carbonara", "Banana Bread", "Miso Soup"]
        let ys = titles.map { require(row($0)).frame.minY }
        XCTAssertEqual(ys, ys.sorted(), "history should be newest first")
    }

    /// #286: a filled bookmark, not the word "Saved", and only on a recipe in a list.
    func testASavedRecipeCarriesAFilledBookmark() {
        launch()
        openRecipes()

        requireState(row("Chicken Adobo"), "label CONTAINS 'Saved to a list'")
        requireState(row("Banana Bread"), "NOT (label CONTAINS 'Saved')")
        assertAbsent(text("Saved"))
    }

    /// #286: the site under the name, as the reading view credits it; no "ago" any more.
    func testARowCreditsItsSite() {
        launch()
        openRecipes()

        requireState(row("Banana Bread"), "label CONTAINS 'example.com'")
        requireState(row("Banana Bread"), "NOT (label CONTAINS 'ago')")
    }

    /// #286: the count on the left of the line under search, the sort on the right, which
    /// names the order and changes it. The toolbar's ⋯ menu, which held only the sort, is gone.
    func testTheLineUnderSearchCountsAndSorts() {
        launch()
        openRecipes()

        require(text("4 recipes"))
        let sort = require(app.buttons["recipes.sort"], "the sort")
        XCTAssertEqual(sort.value as? String, "Recently viewed")
        assertAbsent(app.buttons["More options"])
        sort.tap()
        require(app.buttons["Name"], "Sort by name").tap()

        requireState(app.buttons["recipes.sort"], "value == 'Name'")
        XCTAssertLessThan(row("Banana Bread").frame.minY, row("Chicken Adobo").frame.minY)
    }

    /// #345: All / In a list / Not in a list under search, with the library's counts; a choice
    /// lists only its recipes, and the clean-up line shows under Not in a list alone.
    func testTheFilterListsRecipesInAListOrNotAndExplainsTheCleanUp() {
        launch()
        openRecipes()

        let all = require(app.buttons["recipes.filter.ALL"], "the All pill")
        let inList = require(app.buttons["recipes.filter.IN_A_LIST"], "the In a list pill")
        let notInList = require(app.buttons["recipes.filter.NOT_IN_A_LIST"], "the Not in a list pill")
        XCTAssertEqual(all.label, "All 4")
        XCTAssertEqual(inList.label, "In a list 2")
        XCTAssertEqual(notInList.label, "Not in a list 2")
        requireState(all, "isSelected == true")
        assertAbsent(app.descendants(matching: .any)["recipes.cullNote"])

        inList.tap()
        require(row("Chicken Adobo"))
        require(row("Spaghetti Carbonara"))
        requireGone(row("Banana Bread"))
        requireState(inList, "isSelected == true")
        assertAbsent(app.descendants(matching: .any)["recipes.cullNote"])

        notInList.tap()
        require(row("Banana Bread"))
        require(row("Miso Soup"))
        requireGone(row("Chicken Adobo"))
        require(app.descendants(matching: .any)["recipes.cullNote"], "the clean-up line")

        // The search still applies inside the filter.
        type("miso")
        require(row("Miso Soup"))
        requireGone(row("Banana Bread"))
        requireState(notInList, "isSelected == true")
    }

    /// #345, V4: with `oneCookbook` on the strip of views and lists replaces the pills; a view is a
    /// screen of its own, Back to Recipes, and the outlined bookmark on its rows opens the sheet.
    func testTheViewsStripReplacesThePillsAndAViewHasItsOwnScreen() {
        launch(flags: ["oneCookbook"])
        openRecipes()

        require(app.descendants(matching: .any)["recipes.viewsStrip"], "the views strip")
        assertAbsent(app.buttons["recipes.filter.ALL"])
        let notInList = require(app.buttons["recipes.view.NOT_IN_A_LIST"], "the Not in a list card")
        XCTAssertEqual(notInList.label, "Not in a list, 2")
        require(app.buttons["recipes.view.COOKED"], "the Cooked card")
        require(app.buttons["recipes.view.TYPED"], "the Typed by you card")

        notInList.tap()
        require(app.descendants(matching: .any)["recipes.viewNote"], "the view's note")
        require(row("Banana Bread"))
        require(row("Miso Soup"))
        requireGone(row("Chicken Adobo"))
        assertAbsent(app.descendants(matching: .any)["recipes.viewsStrip"])
        // A recipe in no list: an outlined bookmark, opening the sheet; a tick takes it off the view.
        require(app.buttons["Save to a list"].firstMatch, "an outlined bookmark").tap()
        require(text("Save to"), "the save-to-list sheet")
    }

    /// #371: with one cookbook, the fixed Lists header's "All lists" opens the Lists screen; Back returns here.
    func testAllListsOpensTheListsScreenAndBackReturns() {
        launch(flags: ["oneCookbook"])
        openRecipes()

        require(app.buttons["recipes.allLists"], "All lists").tap()
        require(app.buttons["lists.newList"], "the Lists screen")

        back()
        require(app.textFields["Search titles and ingredients"], "Recipes")
    }

    /// #371: the header's "+" makes an empty list, which then shows on the Lists strip.
    func testThePlusMakesAnEmptyList() {
        launch(flags: ["oneCookbook"])
        openRecipes()

        require(app.buttons["recipes.newList"], "the + in the Lists header").tap()
        let field = require(app.alerts.textFields.firstMatch, "the New list alert's field")
        field.typeText("Zucchini")
        app.alerts.buttons["Create"].tap()

        require(row("Zucchini"), "the new list's card")
    }

    func testSearchMatchesTitles() {
        launch()
        openRecipes()

        type("carbon")

        require(row("Spaghetti Carbonara"))
        requireGone(row("Chicken Adobo"))
        requireGone(row("Banana Bread"))
    }

    func testSearchMatchesIngredientsAndIgnoresCase() {
        launch()
        openRecipes()

        type("TOFU") // only in Miso Soup's ingredients

        require(row("Miso Soup"))
        requireGone(row("Chicken Adobo"))
        requireGone(row("Spaghetti Carbonara"))
    }

    /// "%" is a LIKE wildcard; a regression to LIKE would match everything here.
    func testAPercentSignIsLiteral() {
        launch()
        openRecipes()

        type("100%")

        require(text("No recipes match \"100%\"."))
        requireGone(row("Chicken Adobo"))
    }

    func testNoResultsIsNotTheSameAsEmpty() {
        launch()
        openRecipes()

        type("zzz")

        require(text("No recipes match \"zzz\"."))
        assertAbsent(text("Nothing yet. Recipes you open are kept here, and + adds one by hand or from a link."))
    }

    func testClearingTheSearchBringsEverythingBack() {
        launch()
        openRecipes()

        type("zzz")
        require(text("No recipes match \"zzz\"."))
        app.buttons["Clear search"].tap()

        require(row("Chicken Adobo"))
        require(row("Miso Soup"))
        requireGone(text("No recipes match \"zzz\"."))
    }

    func testAnEmptyLibrarySaysSo() {
        launch(.empty)
        openRecipes()

        require(text("Nothing yet. Recipes you open are kept here, and + adds one by hand or from a link."))
        assertAbsent(textContaining("No recipes match"))
    }

    func testSwipeDeleteShowsAnUndoSnackbarAndUndoRestores() {
        launch()
        openRecipes()

        swipeDelete("Banana Bread")

        require(text("Deleted \"Banana Bread\""))
        app.buttons["Undo"].tap()

        require(row("Banana Bread"), "the row restored")
        requireGone(text("Deleted \"Banana Bread\""))
        // In its old place, between Carbonara and Miso Soup.
        XCTAssertLessThan(row("Spaghetti Carbonara").frame.minY, row("Banana Bread").frame.minY)
        XCTAssertLessThan(row("Banana Bread").frame.minY, row("Miso Soup").frame.minY)
    }

    /// Undo brings back list membership too, under the same id.
    func testUndoRestoresListMembership() {
        launch()
        openRecipes()

        swipeDelete("Chicken Adobo")
        require(text("Deleted \"Chicken Adobo\""))
        app.buttons["Undo"].tap()

        requireState(row("Chicken Adobo"), "label CONTAINS 'Saved to a list'", "Adobo back with its bookmark")
        back()
        openLists()
        requireState(row("Weeknights"), "label CONTAINS '2 recipes'")
        requireState(row("Favorites"), "label CONTAINS '1 recipe'")
    }

    /// A second swipe inside the snackbar's window joins the batch; one Undo restores both.
    func testTwoDeletesShareOneSnackbarAndOneUndo() {
        launch()
        openRecipes()

        // The snackbar lasts four seconds, so the second swipe must land inside it: no waits
        // between the two beyond what the taps themselves need.
        row("Banana Bread").swipeLeft()
        app.buttons["Delete"].tap()
        row("Miso Soup").swipeLeft()
        app.buttons["Delete"].tap()

        require(text("2 recipes deleted"))
        app.buttons["Undo"].tap()

        require(row("Banana Bread"))
        require(row("Miso Soup"))
    }

    /// #268: a burst of three shares one snackbar, counting in place, and after Undo it is gone
    /// for good: the rows come back and stay, and no snackbar returns.
    func testABurstOfThreeSharesOneSnackbarAndUndoHidesItForGood() {
        launch()
        openRecipes()

        row("Banana Bread").swipeLeft()
        app.buttons["Delete"].tap()
        row("Miso Soup").swipeLeft()
        app.buttons["Delete"].tap()
        row("Chicken Adobo").swipeLeft()
        app.buttons["Delete"].tap()

        require(text("3 recipes deleted"))
        XCTAssertEqual(app.buttons.matching(identifier: "Undo").count, 1)
        app.buttons["Undo"].tap()

        require(row("Banana Bread"))
        require(row("Miso Soup"))
        require(row("Chicken Adobo"))
        // Give a stray snackbar time to come back, then check it didn't.
        _ = text("never shown").waitForExistence(timeout: 2)
        assertAbsent(app.buttons["Undo"])
        assertAbsent(text("3 recipes deleted"))
        require(row("Chicken Adobo"))
    }

    /// Without Undo the delete stands: the snackbar goes and so does the recipe, from Home too.
    func testADeleteWithoutUndoStands() {
        launch()
        openRecipes()

        swipeDelete("Banana Bread")
        require(text("Deleted \"Banana Bread\""))
        requireGone(text("Deleted \"Banana Bread\""), "the snackbar to time out")

        assertAbsent(row("Banana Bread"))
        back()
        require(row("Chicken Adobo"))
        assertAbsent(row("Banana Bread"))
    }

    /// A field by its prompt, single-line or the editor's multiline (a text view on iOS).
    private func field(_ prompt: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(
            format: "(elementType == %d OR elementType == %d) AND (placeholderValue == %@ OR label == %@)",
            XCUIElement.ElementType.textField.rawValue, XCUIElement.ElementType.textView.rawValue, prompt, prompt
        )).firstMatch
    }

    func testPlusTypeARecipeSavesItIntoRecipes() {
        launch()
        openRecipes()

        require(app.buttons["recipes.add"]).tap()
        require(app.buttons["Type a recipe"]).tap()
        let name = require(field("Name"))
        name.tap()
        name.typeText("Weeknight Stew")
        let ingredients = require(field("Ingredients, one per line"))
        ingredients.tap()
        ingredients.typeText("2 carrots")
        require(app.buttons["edit.save"]).tap()

        // Saving opens the new recipe in place of the editor; Back is Recipes, which lists it.
        require(text("Weeknight Stew"), "the saved recipe")
        back()
        require(row("Weeknight Stew"), "the typed-in recipe in Recipes")
    }
}
