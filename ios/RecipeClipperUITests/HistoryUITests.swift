import XCTest

/// History end to end — untested on Android (docs/testing.md). Search goes through
/// the real SQL (instr, never LIKE); delete is a hard delete with an undo snackbar that
/// restores the row with its list membership.
final class HistoryUITests: RecipeUITestCase {

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
        openHistory()

        let titles = ["Chicken Adobo", "Spaghetti Carbonara", "Banana Bread", "Miso Soup"]
        let ys = titles.map { require(row($0)).frame.minY }
        XCTAssertEqual(ys, ys.sorted(), "history should be newest first")
    }

    func testASavedRecipeCarriesTheSavedTag() {
        launch()
        openHistory()

        requireState(row("Chicken Adobo"), "label CONTAINS 'Saved'")
        requireState(row("Banana Bread"), "NOT (label CONTAINS 'Saved')")
    }

    func testSearchMatchesTitles() {
        launch()
        openHistory()

        type("carbon")

        require(row("Spaghetti Carbonara"))
        requireGone(row("Chicken Adobo"))
        requireGone(row("Banana Bread"))
    }

    func testSearchMatchesIngredientsAndIgnoresCase() {
        launch()
        openHistory()

        type("TOFU") // only in Miso Soup's ingredients

        require(row("Miso Soup"))
        requireGone(row("Chicken Adobo"))
        requireGone(row("Spaghetti Carbonara"))
    }

    /// "%" is a LIKE wildcard; a regression to LIKE would match everything here.
    func testAPercentSignIsLiteral() {
        launch()
        openHistory()

        type("100%")

        require(text("No recipes match \"100%\"."))
        requireGone(row("Chicken Adobo"))
    }

    func testNoResultsIsNotTheSameAsEmpty() {
        launch()
        openHistory()

        type("zzz")

        require(text("No recipes match \"zzz\"."))
        assertAbsent(text("Nothing yet. Recipes you open are kept here automatically."))
    }

    func testClearingTheSearchBringsEverythingBack() {
        launch()
        openHistory()

        type("zzz")
        require(text("No recipes match \"zzz\"."))
        app.buttons["Clear search"].tap()

        require(row("Chicken Adobo"))
        require(row("Miso Soup"))
        requireGone(text("No recipes match \"zzz\"."))
    }

    func testAnEmptyHistorySaysSo() {
        launch(.empty)
        openHistory()

        require(text("Nothing yet. Recipes you open are kept here automatically."))
        assertAbsent(textContaining("No recipes match"))
    }

    func testSwipeDeleteShowsAnUndoSnackbarAndUndoRestores() {
        launch()
        openHistory()

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
        openHistory()

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
        openHistory()

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
        openHistory()

        swipeDelete("Banana Bread")
        require(text("Deleted \"Banana Bread\""))
        requireGone(text("Deleted \"Banana Bread\""), "the snackbar to time out")

        assertAbsent(row("Banana Bread"))
        back()
        require(row("Chicken Adobo"))
        assertAbsent(row("Banana Bread"))
    }
}
