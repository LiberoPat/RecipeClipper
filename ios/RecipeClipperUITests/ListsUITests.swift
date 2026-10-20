import XCTest

/// The Lists screen's covers (#286): a two-column grid of lists, each with its name and count
/// under its cover, and "New list" as the last tile. Which photos a cover shows is covered by
/// ListDaoTests (the SQL) and ListsViewModelTests; the seeded recipes here have none.
///
/// The standard seed: Weeknights (Adobo, Carbonara) is the cook's own list, so it comes after
/// the six seeded lists; Favorites and Dinner hold Adobo; the rest are empty.
final class ListsUITests: RecipeUITestCase {

    private var newListTile: XCUIElement { app.buttons["lists.newList"] }

    func testEveryListIsACoverWithItsNameAndCount() {
        launch()
        openLists()

        requireState(row("Favorites"), "label CONTAINS '1 recipe'")
        requireState(row("Weeknights"), "label CONTAINS '2 recipes'")
        requireState(row("Lunch"), "label CONTAINS 'Empty'")
    }

    /// Two to a row: Favorites and Lunch side by side, the seeded lists before the cook's own.
    func testTheListsSitTwoToARowSeededFirst() {
        launch()
        openLists()

        let favorites = require(row("Favorites")).frame
        let lunch = require(row("Lunch")).frame
        XCTAssertEqual(favorites.minY, lunch.minY, accuracy: 1)
        XCTAssertLessThan(favorites.minX, lunch.minX)
        XCTAssertLessThan(require(row("Snacks")).frame.minY, require(row("Weeknights")).frame.minY)
    }

    /// Seven lists: Weeknights alone in the fourth row, New list beside it.
    func testNewListIsTheLastTile() {
        launch()
        openLists()

        let last = require(row("Weeknights")).frame
        let tile = require(newListTile).frame
        XCTAssertEqual(tile.minY, last.minY, accuracy: 1)
        XCTAssertGreaterThan(tile.minX, last.minX)
    }

    func testTheNewListTileCreatesAnEmptyList() {
        launch()
        openLists()

        newListTile.tap()
        let field = require(app.textFields["List name"])
        field.tap()
        field.typeText("Soups")
        app.buttons["Create"].tap()

        requireState(row("Soups"), "label CONTAINS 'Empty'")
        require(newListTile, "the tile back once the field closes")
    }

    func testTappingACoverOpensTheList() {
        launch()
        openLists()

        require(row("Weeknights")).tap()

        require(app.buttons["More options"], "Weeknights' own screen")
        require(row("Spaghetti Carbonara"))
    }
}
