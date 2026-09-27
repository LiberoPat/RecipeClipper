import XCTest

/// Port of Android's HomeScreenTest, end to end: seeded database → repository → ViewModel →
/// SwiftUI. These exist because a view-only bug (a duplicate LazyColumn key) made Android's
/// Home unusable while every ViewModel test stayed green.
final class HomeUITests: RecipeUITestCase {

    /// The screen's Go button, not the keyboard's Go key (the field submits with .go).
    private var goButton: XCUIElement { app.scrollViews.firstMatch.buttons["Go"] }

    func testTheNewestRecipeIsContinueCookingAndTheRestAreRecentlyViewed() {
        launch(.standard)

        require(text("Continue cooking"))
        require(text("Recently viewed"))
        let newest = require(row("Chicken Adobo"))
        let older = require(row("Spaghetti Carbonara"))
        XCTAssertLessThan(text("Continue cooking").frame.minY, newest.frame.minY)
        XCTAssertLessThan(newest.frame.minY, text("Recently viewed").frame.minY)
        XCTAssertLessThan(text("Recently viewed").frame.minY, older.frame.minY)
    }

    /// The regression guard for the bug that prompted Android's UI tests: Home rendering a
    /// full set of rows at once, each exactly once.
    func testAFullHomeRendersEveryRecipeOnce() {
        launch(.many)

        require(row("Recipe 1")) // continue cooking
        require(row("Recipe 2")) // first recently viewed
        for n in 1...6 {
            XCTAssertEqual(rowCount("Recipe \(n)"), 1, "Recipe \(n) should appear exactly once")
        }
    }

    /// Chicken Adobo is in three lists and is also the most recent: still one row on Home.
    func testARecipeInSeveralListsIsStillOneRow() {
        launch(.standard)

        require(row("Chicken Adobo"))
        XCTAssertEqual(rowCount("Chicken Adobo"), 1)
        XCTAssertEqual(rowCount("Spaghetti Carbonara"), 1)
    }

    /// Six are fetched, one becomes "Continue cooking", so at most five are listed below it.
    func testRecentlyViewedStopsAtFive() {
        launch(.many)

        require(row("Recipe 6"))
        assertAbsent(row("Recipe 7"))
    }

    /// Removed deliberately: Lists answers "what have I kept?" better.
    func testThereIsNoSavedSection() {
        launch(.many) // nothing in any list, so no row carries a "Saved" tag either

        require(row("Recipe 1"))
        assertAbsent(text("Saved"))
    }

    func testTappingARecipeOpensIt() {
        launch(.standard)

        openRecipe("Banana Bread")

        require(text("Banana Bread"))
        require(text("Ingredients"))
    }

    func testTheSettingsGearOpensSettings() {
        launch(.standard)

        openSettings()
    }

    func testRecipesAndListsRowsOpenTheirScreens() {
        launch(.standard)

        openRecipes()
        back()
        openLists()
    }

    /// Both nav rows and the gear are unconditional — they used to come and go with the database.
    func testTheNavEntriesAreThereWithAnEmptyDatabase() {
        launch(.empty)

        require(app.buttons["home.nav.recipes"])
        require(app.buttons["home.nav.lists"])
        require(app.buttons["Settings"])
    }

    func testTheEmptyHintShowsWhenThereIsNothingToResume() {
        launch(.empty)

        require(text("Recipes you open will show up here."))
        assertAbsent(text("Continue cooking"))
        assertAbsent(text("Recently viewed"))
    }

    func testTheEmptyHintGoesOnceThereIsARecipe() {
        launch(.standard)

        require(row("Chicken Adobo"))
        assertAbsent(text("Recipes you open will show up here."))
    }

    func testSomethingThatIsNotALinkIsRejectedRatherThanOpened() {
        launch(.standard)

        let field = app.textFields["Recipe URL"]
        field.tap()
        field.typeText("not a link")
        goButton.tap()

        require(text("That doesn't look like a link."))
        XCTAssertFalse(bookmark.exists, "no recipe screen should have opened")
        XCTAssertTrue(app.textFields["Recipe URL"].exists)
    }

    /// Go opens the import route; the stub source resolves it offline, and the recipe it
    /// saves becomes "Continue cooking" when you come back.
    func testALinkIsOpenedAndLandsInContinueCooking() {
        launch(.standard)

        let field = app.textFields["Recipe URL"]
        field.tap()
        field.typeText("example.com/chicken")
        goButton.tap()

        require(bookmark, "the recipe screen")
        require(text("Stub Chicken Soup"))

        back()
        let stub = require(row("Stub Chicken Soup"))
        XCTAssertLessThan(stub.frame.minY, row("Chicken Adobo").frame.minY,
                          "the import is now the most recent recipe")
    }
}
