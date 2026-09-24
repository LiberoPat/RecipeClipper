import XCTest

/// Port of Android's SaveToListBottomSheetTest. The sheet has no Save button — a tap writes
/// immediately — so "did it stick?" is answered by what the tap did: the box, the count, the
/// bookmark, and the Lists screen after the sheet is gone.
///
/// Miso Soup is in no list; Chicken Adobo is in Favorites, Dinner and Weeknights.
final class SaveToListUITests: RecipeUITestCase {

    /// A list's row in the sheet. Its label is "Name, <count>".
    private func listRow(_ name: String) -> XCUIElement { row(name) }

    private func openSheet(for recipe: String) {
        openRecipe(recipe)
        bookmark.tap()
        require(text("Save to"), "the save-to-list sheet")
    }

    private func dismissSheet() {
        // Drag the grabber off the bottom of the screen; a swipe on the sheet's content only
        // scrolls it.
        let grabber = require(app.buttons["Sheet Grabber"], "the sheet grabber")
        grabber.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            .press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.99)))
        requireGone(text("Save to"), "the sheet to close")
    }

    private func assertTicked(_ name: String, _ ticked: Bool, file: StaticString = #filePath, line: UInt = #line) {
        requireState(listRow(name), "isSelected == \(ticked ? "true" : "false")",
                     "\(name) \(ticked ? "ticked" : "unticked")", file: file, line: line)
    }

    func testEveryListIsShown() {
        launch()
        openSheet(for: "Miso Soup")

        for name in ["Favorites", "Lunch", "Dinner", "Desserts", "Breakfast", "Snacks", "Weeknights"] {
            require(listRow(name), name)
        }
    }

    /// Built-ins first, in seeded order, then user lists.
    func testBuiltInsSortBeforeUserLists() {
        launch()
        openSheet(for: "Miso Soup")

        let favorites = require(listRow("Favorites")).frame.minY
        let snacks = require(listRow("Snacks")).frame.minY
        let weeknights = require(listRow("Weeknights")).frame.minY
        XCTAssertLessThan(favorites, snacks)
        XCTAssertLessThan(snacks, weeknights)
    }

    func testAnEmptyListSaysEmptyRatherThanZeroRecipes() {
        launch()
        openSheet(for: "Miso Soup")

        requireState(listRow("Lunch"), "label CONTAINS 'Empty'")
        requireState(listRow("Favorites"), "label CONTAINS '1 recipe'")
        requireState(listRow("Weeknights"), "label CONTAINS '2 recipes'")
    }

    func testNothingIsTickedForARecipeInNoList() {
        launch()
        openRecipe("Miso Soup")
        XCTAssertEqual(bookmark.label, "Save to a list", "an unsaved recipe's bookmark is the outline")
        bookmark.tap()
        require(text("Save to"))

        for name in ["Favorites", "Lunch", "Weeknights"] { assertTicked(name, false) }
    }

    func testARecipeAlreadyInAListOpensTicked() {
        launch()
        openRecipe("Chicken Adobo")
        XCTAssertEqual(bookmark.label, "Saved to a list", "a saved recipe's bookmark is filled")
        bookmark.tap()
        require(text("Save to"))

        assertTicked("Favorites", true)
        assertTicked("Dinner", true)
        assertTicked("Weeknights", true)
        assertTicked("Lunch", false)
    }

    /// The round trip: the tick writes, the sheet re-reads, the count moves, the bookmark
    /// fills — and it is still there on the Lists screen once the sheet is gone.
    func testTickingWritesStraightThroughAndFillsTheBookmark() {
        launch()
        openSheet(for: "Miso Soup")

        listRow("Lunch").tap()

        assertTicked("Lunch", true)
        assertTicked("Favorites", false)
        requireState(listRow("Lunch"), "label CONTAINS '1 recipe'", "the count to follow the tick")

        dismissSheet()
        requireState(bookmark, "label == 'Saved to a list'", "the bookmark to fill")

        back()
        openLists()
        requireState(row("Lunch"), "label CONTAINS '1 recipe'", "Lunch's count on the Lists screen")
    }

    func testTickingAgainUnticksAndRemovesTheMembership() {
        launch()
        openSheet(for: "Miso Soup")

        listRow("Lunch").tap()
        assertTicked("Lunch", true)
        listRow("Lunch").tap()

        assertTicked("Lunch", false)
        requireState(listRow("Lunch"), "label CONTAINS 'Empty'")
        dismissSheet()
        requireState(bookmark, "label == 'Save to a list'", "the bookmark to empty again")
    }

    /// Unticking a recipe's last list is a demotion, not a deletion: it stays in history.
    func testUntickingTheLastListKeepsTheRecipe() {
        launch()
        openSheet(for: "Chicken Adobo")

        for name in ["Favorites", "Dinner", "Weeknights"] {
            listRow(name).tap()
            assertTicked(name, false)
        }
        dismissSheet()
        requireState(bookmark, "label == 'Save to a list'")

        back()
        openHistory()
        require(row("Chicken Adobo"), "Adobo still in history")
    }

    func testNewListExpandsInlineRatherThanOpeningADialog() {
        launch()
        openSheet(for: "Miso Soup")

        app.buttons["+ New list"].tap()

        require(app.textFields["List name"])
        require(app.buttons["Create"])
        XCTAssertEqual(app.alerts.count, 0, "no dialog stacked on the sheet")
        XCTAssertTrue(text("Save to").exists, "still the same sheet")
    }

    func testCreateIsRefusedUntilSomethingIsTyped() {
        launch()
        openSheet(for: "Miso Soup")

        app.buttons["+ New list"].tap()

        XCTAssertFalse(require(app.buttons["Create"]).isEnabled)
        let field = app.textFields["List name"]
        field.tap()
        field.typeText("   ")
        XCTAssertFalse(app.buttons["Create"].isEnabled, "whitespace is not a name")
    }

    func testCreatingAListPutsTheCurrentRecipeInIt() {
        launch()
        openSheet(for: "Miso Soup")

        app.buttons["+ New list"].tap()
        let field = require(app.textFields["List name"])
        field.tap()
        field.typeText("Weekend")
        app.buttons["Create"].tap()

        assertTicked("Weekend", true)
        requireState(listRow("Weekend"), "label CONTAINS '1 recipe'")
        dismissSheet()
        requireState(bookmark, "label == 'Saved to a list'")
    }

    func testCreatingClosesTheFieldAgain() {
        launch()
        openSheet(for: "Miso Soup")

        app.buttons["+ New list"].tap()
        let field = require(app.textFields["List name"])
        field.tap()
        field.typeText("Weekend")
        app.buttons["Create"].tap()

        requireGone(app.textFields["List name"])
        require(app.buttons["+ New list"])
    }

    func testCancellingCreatesNothing() {
        launch()
        openSheet(for: "Miso Soup")

        app.buttons["+ New list"].tap()
        let field = require(app.textFields["List name"])
        field.tap()
        field.typeText("Weekend")
        app.buttons["Cancel"].tap()

        requireGone(app.textFields["List name"])
        assertAbsent(listRow("Weekend"))
        dismissSheet()
        XCTAssertEqual(bookmark.label, "Save to a list")
    }
}
