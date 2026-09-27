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

    func testASavedRecipeCarriesTheSavedTag() {
        launch()
        openRecipes()

        requireState(row("Chicken Adobo"), "label CONTAINS 'Saved'")
        requireState(row("Banana Bread"), "NOT (label CONTAINS 'Saved')")
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

        requireState(row("Chicken Adobo"), "label CONTAINS 'Saved'", "Adobo back with its Saved tag")
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
