import XCTest

/// Port of Android's ListDetailScreenTest. The rule this screen enforces is "Favorites is the
/// only list you can't delete": the SQL guard is covered by the DAO tests, whether Delete is
/// even offered is covered here.
///
/// Weeknights holds Chicken Adobo (added first) and Spaghetti Carbonara (added later); Lunch
/// is empty; Favorites holds Adobo.
final class ListDetailUITests: RecipeUITestCase {

    private func openList(_ name: String) {
        openLists()
        require(row(name), "\(name) on the Lists screen").tap()
        require(app.buttons["More options"], "\(name)'s detail screen")
    }

    private func openMenu() {
        app.buttons["More options"].tap()
        require(app.buttons["Rename"], "the overflow menu")
    }

    private func renameAlert() -> XCUIElement { app.alerts["Rename list"] }

    private func replaceRenameText(with name: String) {
        let field = require(renameAlert().textFields.firstMatch, "the rename field")
        field.tap()
        let current = (field.value as? String) ?? ""
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count))
        field.typeText(name)
    }

    func testTheListNameIsTheHeading() {
        launch()
        openList("Weeknights")

        require(text("Weeknights"))
    }

    func testAnEmptyListSaysSoRatherThanLookingBroken() {
        launch()
        openList("Lunch")

        require(text("Nothing in this list yet."))
    }

    /// Ordered by when each was added, not when it was viewed: Adobo was viewed more recently
    /// but added to Weeknights earlier, so Carbonara comes first.
    func testTheRecipesAreShownMostRecentlyAddedFirst() {
        launch()
        openList("Weeknights")

        let carbonara = require(row("Spaghetti Carbonara"))
        let adobo = require(row("Chicken Adobo"))
        XCTAssertLessThan(carbonara.frame.minY, adobo.frame.minY)
        assertAbsent(text("Nothing in this list yet."))
        assertAbsent(row("Banana Bread"), "a recipe in no list")
    }

    func testTappingARecipeOpensIt() {
        launch()
        openList("Weeknights")

        row("Chicken Adobo").tap()

        require(bookmark, "the recipe screen")
        require(text("Chicken Adobo"))
    }

    // MARK: - What the overflow menu offers

    func testFavoritesOffersRenameButNotDelete() {
        launch()
        openList("Favorites")

        openMenu()

        require(app.buttons["Rename"])
        assertAbsent(app.buttons["Delete list"])
    }

    /// Lunch is seeded like Favorites, but only Favorites is protected.
    func testASeededListThatIsNotFavoritesOffersDelete() {
        launch()
        openList("Lunch")

        openMenu()

        require(app.buttons["Delete list"])
    }

    func testAUserListOffersDelete() {
        launch()
        openList("Weeknights")

        openMenu()

        require(app.buttons["Delete list"])
    }

    /// Membership is edited in the save-to-list sheet only: nothing on this screen removes a
    /// recipe from the list — not the rows, not the overflow menu.
    ///
    /// (A swipe isn't exercised: rows here are plain buttons in a ScrollView, and a horizontal
    /// swipe across one lands as a tap and opens the recipe.)
    func testNoRemoveFromListIsOffered() {
        launch()
        openList("Weeknights")

        require(row("Chicken Adobo"))
        let removes = NSPredicate(format: "label CONTAINS[c] 'remove'")
        XCTAssertEqual(app.buttons.matching(removes).count, 0)

        openMenu()
        XCTAssertEqual(app.buttons.matching(removes).count, 0)
        require(app.buttons["Rename"])
        require(app.buttons["Delete list"])
    }

    // MARK: - Deleting

    func testDeletingAsksFirstAndNamesTheList() {
        launch()
        openList("Weeknights")

        openMenu()
        app.buttons["Delete list"].tap()

        let alert = require(app.alerts["Delete \"Weeknights\"?"])
        // The dialog says what does NOT happen, which is the part worth being sure of.
        XCTAssertTrue(alert.staticTexts["The list is removed. The recipes in it stay in your history."].exists)
    }

    func testCancellingTheConfirmationDeletesNothing() {
        launch()
        openList("Weeknights")

        openMenu()
        app.buttons["Delete list"].tap()
        require(app.alerts.firstMatch).buttons["Cancel"].tap()

        requireGone(app.alerts.firstMatch)
        require(text("Weeknights"), "still on the list")
        back()
        require(row("Weeknights"), "Weeknights still on the Lists screen")
    }

    /// The list is gone the moment the delete lands, so the screen pops back to Lists; the
    /// recipes that were in it stay in history.
    func testConfirmingDeletesAndLeavesTheScreen() {
        launch()
        openList("Weeknights")

        openMenu()
        app.buttons["Delete list"].tap()
        require(app.alerts.firstMatch).buttons["Delete"].tap()

        require(app.buttons["+ New list"], "popped back to the Lists screen")
        requireGone(row("Weeknights"))
        back()
        openHistory()
        require(row("Spaghetti Carbonara"), "its recipes stay in history")
        require(row("Chicken Adobo"))
    }

    // MARK: - Renaming

    func testRenamingOpensPreFilledSoItIsAnEditNotARetype() {
        launch()
        openList("Weeknights")

        openMenu()
        app.buttons["Rename"].tap()

        let field = require(renameAlert().textFields.firstMatch)
        XCTAssertEqual(field.value as? String, "Weeknights")
    }

    func testRenamingWritesTheNewName() {
        launch()
        openList("Weeknights")

        openMenu()
        app.buttons["Rename"].tap()
        replaceRenameText(with: "Midweek")
        renameAlert().buttons["Save"].tap()

        require(text("Midweek"), "the new heading")
        back()
        require(row("Midweek"), "the new name on the Lists screen")
        assertAbsent(row("Weeknights"))
    }

    func testFavoritesCanBeRenamed() {
        launch()
        openList("Favorites")

        openMenu()
        app.buttons["Rename"].tap()
        replaceRenameText(with: "Best of")
        renameAlert().buttons["Save"].tap()

        require(text("Best of"))
        // Still Favorites underneath: Delete is still not offered.
        openMenu()
        assertAbsent(app.buttons["Delete list"])
    }

    func testCancellingARenameChangesNothing() {
        launch()
        openList("Weeknights")

        openMenu()
        app.buttons["Rename"].tap()
        replaceRenameText(with: "Midweek")
        renameAlert().buttons["Cancel"].tap()

        requireGone(renameAlert())
        require(text("Weeknights"))
        assertAbsent(text("Midweek"))
    }
}
