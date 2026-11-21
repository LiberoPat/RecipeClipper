import XCTest

/// A recipe row's long-press menu and selecting several recipes (#376, #377; Android's
/// `RecipesScreenTest` additions). The standard scenario lists Chicken Adobo, Spaghetti
/// Carbonara, Banana Bread and Miso Soup, newest viewed first; Adobo and Carbonara are in
/// Weeknights.
final class RowActionsUITests: RecipeUITestCase {

    private let both = ["rowActions", "multiSelect"]

    /// Where the screenshots for the PR go.
    private func snap(_ name: String) {
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        let directory = URL(fileURLWithPath: "/private/tmp/claude-502/rc376-shots", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try? screenshot.pngRepresentation.write(to: directory.appendingPathComponent("\(name).png"))
    }

    private func longPress(_ title: String) {
        require(row(title), "the row for \(title)").press(forDuration: 1.2)
    }

    /// The menu's entry (a button), or the alert's when one is up.
    private func menuButton(_ title: String) -> XCUIElement { app.buttons[title].firstMatch }

    func testALongPressOpensTheRowMenuWithItsFiveEntries() {
        launch(flags: both)
        openRecipes()

        longPress("Banana Bread")

        require(menuButton("Add to list"), "Add to list")
        require(menuButton("Add to plan"), "Add to plan")
        require(menuButton("Share"), "Share")
        require(menuButton("Select"), "Select")
        require(menuButton("Delete"), "Delete")
        assertAbsent(menuButton("Remove from this list"), "Remove from this list outside a list")
        snap("ios-long-press-menu")
    }

    /// The grid (#380) and the menu and selection (#376, #377) landed together: a cell does what a row does.
    func testInTheGridALongPressOpensTheMenuAndSelectTicksCellsThenDeleteHasOneUndo() {
        launch(flags: both + ["recipesGrid"])
        openRecipes()
        require(app.buttons["recipes.layout"], "the layout toggle").tap()
        require(app.otherElements["recipes.grid"], "the grid")

        longPress("Banana Bread")
        require(menuButton("Add to list"), "Add to list")
        require(menuButton("Share"), "Share")
        require(menuButton("Delete"), "Delete")
        menuButton("Select").tap()

        require(text("1 selected"), "the count")
        requireState(row("Banana Bread"), "isSelected == true", "the cell ticked")
        row("Miso Soup").tap()
        require(text("2 selected"))
        requireState(row("Miso Soup"), "isSelected == true", "the second cell ticked")
        // Selecting never leaves the grid.
        require(app.otherElements["recipes.grid"], "still the grid")

        app.buttons["recipes.selection.delete"].tap()
        let alert = require(app.alerts.firstMatch, "one confirmation for both")
        XCTAssertTrue(alert.label.contains("2"), alert.label)
        alert.buttons["Delete"].tap()

        require(text("2 recipes deleted"))
        requireGone(row("Banana Bread"))
        requireGone(row("Miso Soup"))
        app.buttons["Undo"].tap()
        require(row("Banana Bread"), "restored")
        require(row("Miso Soup"), "restored")
    }

    func testAddToListFromTheMenuOpensTheSaveToListSheet() {
        launch(flags: both)
        openRecipes()

        longPress("Banana Bread")
        menuButton("Add to list").tap()

        require(text("Save to"), "the save-to-list sheet")
        require(app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Favorites'")).firstMatch, "the lists")
    }

    func testAddToPlanFromTheMenuOpensThePlanSheet() {
        launch(flags: both)
        openRecipes()

        longPress("Banana Bread")
        menuButton("Add to plan").tap()

        require(app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Add to'")).firstMatch, "the plan sheet's button")
    }

    func testDeleteFromTheMenuConfirmsThenOffersUndo() {
        launch(flags: both)
        openRecipes()

        longPress("Banana Bread")
        menuButton("Delete").tap()
        let alert = app.alerts.firstMatch
        require(alert, "the confirmation")
        XCTAssertTrue(alert.label.contains("Banana Bread"), alert.label)
        alert.buttons["Delete"].tap()

        require(text("Deleted \"Banana Bread\""))
        requireGone(row("Banana Bread"))
        app.buttons["Undo"].tap()
        require(row("Banana Bread"), "restored")
    }

    func testCancellingTheConfirmationDeletesNothing() {
        launch(flags: both)
        openRecipes()

        longPress("Miso Soup")
        menuButton("Delete").tap()
        require(app.alerts.firstMatch, "the confirmation").buttons["Cancel"].tap()

        requireGone(app.alerts.firstMatch)
        require(row("Miso Soup"))
        assertAbsent(textContaining("Deleted"))
    }

    func testSelectFromTheMenuTicksThatRow() {
        launch(flags: both)
        openRecipes()

        longPress("Banana Bread")
        menuButton("Select").tap()

        require(text("1 selected"), "the count")
        require(app.buttons["Done"], "Done")
        requireState(row("Banana Bread"), "isSelected == true", "the row ticked")
    }

    func testSelectTwoDeleteConfirmOnceThenOneUndoRestoresBoth() {
        launch(flags: both)
        openRecipes()

        require(app.buttons["recipes.select"], "the top bar's Select").tap()
        require(text("0 selected"))
        row("Banana Bread").tap()
        row("Miso Soup").tap()
        require(text("2 selected"))
        snap("ios-selection-mode")

        app.buttons["recipes.selection.delete"].tap()
        let alert = require(app.alerts.firstMatch, "one confirmation for both")
        XCTAssertTrue(alert.label.contains("2"), alert.label)
        alert.buttons["Delete"].tap()

        require(text("2 recipes deleted"))
        requireGone(row("Banana Bread"))
        requireGone(row("Miso Soup"))
        requireGone(app.buttons["Done"], "selecting ended")
        app.buttons["Undo"].tap()

        require(row("Banana Bread"), "restored")
        require(row("Miso Soup"), "restored")
    }

    func testSelectTwoAddToAListShowsTheSheetForBoth() {
        launch(flags: both)
        openRecipes()

        app.buttons["recipes.select"].tap()
        row("Banana Bread").tap()
        row("Miso Soup").tap()
        app.buttons["recipes.selection.addToList"].tap()

        require(text("Save to"))
        // Weeknights holds neither of the two.
        let weeknights = require(app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Weeknights'")).firstMatch)
        weeknights.tap()
        // Ticked for both now: the list's count reads two more.
        requireState(weeknights, "label CONTAINS '4 recipes'")
    }

    func testDoneLeavesSelectingAndTheTicksGo() {
        launch(flags: both)
        openRecipes()

        app.buttons["recipes.select"].tap()
        row("Banana Bread").tap()
        app.buttons["Done"].tap()

        requireGone(app.buttons["Done"])
        require(app.buttons["recipes.select"], "Select again")
        assertAbsent(text("1 selected"))
    }

    func testWithTheFlagsOffThereIsNoMenuAndNoSelect() {
        launch()
        openRecipes()

        assertAbsent(app.buttons["recipes.select"], "Select")
        row("Banana Bread").press(forDuration: 1.2)
        assertAbsent(menuButton("Add to plan"), "a row menu")
        // A held press is only a tap there, which opens the recipe: back to the list.
        if bookmark.waitForExistence(timeout: 2) { back() }
        // Opening and swiping are as before.
        row("Banana Bread").swipeLeft()
        require(app.buttons["Delete"], "the swipe's Delete")
    }

    func testInAListRemoveFromThisListLeavesTheListNotTheLibrary() {
        launch(flags: both)
        openLists()
        require(row("Weeknights")).tap()
        require(app.buttons["More options"])

        longPress("Spaghetti Carbonara")
        menuButton("Remove from this list").tap()

        requireGone(row("Spaghetti Carbonara"), "gone from the list")
        assertAbsent(textContaining("Deleted"), "no snackbar: not a deletion")
        back()
        back()
        openRecipes()
        require(row("Spaghetti Carbonara"), "still in Recipes")
    }

    func testInAListSelectAndRemoveSeveral() {
        launch(flags: both)
        openLists()
        require(row("Weeknights")).tap()
        require(app.buttons["More options"]).tap()
        require(app.buttons["list.select"], "Select in the overflow").tap()

        row("Chicken Adobo").tap()
        row("Spaghetti Carbonara").tap()
        require(text("2 selected"))
        snap("ios-selection-mode-list")
        app.buttons["recipes.selection.removeFromList"].tap()

        requireGone(row("Chicken Adobo"))
        requireGone(row("Spaghetti Carbonara"))
        require(text("Weeknights"))
    }
}
