import XCTest

/// The Lists screen's covers (#286): a two-column grid of lists, each with its name and count
/// under its cover, and a "+" (the top bar, #371) to make a new list. Which photos a cover shows is covered by
/// ListDaoTests (the SQL) and ListsViewModelTests; the seeded recipes here have none.
///
/// The standard seed: Weeknights (Adobo, Carbonara) is the cook's own list, so it comes after
/// the six seeded lists; Favorites and Dinner hold Adobo; the rest are empty.
final class ListsUITests: RecipeUITestCase {

    private var newListPlus: XCUIElement { app.buttons["lists.newList"] }

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

    /// #371: the "+" is in the top bar, above the first cover, with no tile after the last one.
    func testTheNewListPlusIsInTheTopBarNotAtTheEndOfTheGrid() {
        launch()
        openLists()

        let plus = require(newListPlus).frame
        let first = require(row("Favorites")).frame
        XCTAssertLessThan(plus.maxY, first.minY)
        assertAbsent(app.staticTexts["New list"])
    }

    func testThePlusCreatesAnEmptyList() {
        launch()
        openLists()

        newListPlus.tap()
        let field = require(app.textFields["List name"])
        field.tap()
        field.typeText("Soups")
        app.buttons["Create"].tap()

        requireState(row("Soups"), "label CONTAINS 'Empty'")
        require(newListPlus, "the + still there once the field closes")
    }

    /// Reorder lists (#381): press and hold Lunch, drag it onto Favorites, let go. Lunch is first
    /// now, and stays first on coming back to the screen (the order was stored). The ViewModel
    /// tests carry the rules; this proves the system drag reaches them.
    func testAHeldCoverDraggedOntoAnotherTakesItsPlace() {
        launch(flags: ["reorderLists"])
        openLists()

        let lunch = require(row("Lunch"))
        lunch.press(forDuration: 1.5, thenDragTo: require(row("Favorites")), withVelocity: .slow, thenHoldForDuration: 1.0)

        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "lists-after-reorder"
        shot.lifetime = .keepAlways
        add(shot)
        let favorites = require(row("Favorites"))
        XCTAssertLessThan(lunch.frame.minX, favorites.frame.minX, "Lunch before Favorites")
        XCTAssertEqual(lunch.frame.minY, favorites.frame.minY, accuracy: 1, "still in the first row")

        back()
        openLists()
        XCTAssertLessThan(require(row("Lunch")).frame.minX, require(row("Favorites")).frame.minX)
    }

    func testTappingACoverOpensTheList() {
        launch()
        openLists()

        require(row("Weeknights")).tap()

        require(app.buttons["More options"], "Weeknights' own screen")
        require(row("Spaghetti Carbonara"))
    }
}
