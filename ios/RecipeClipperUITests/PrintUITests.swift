import XCTest

/// Print or save as PDF, and a list as a cookbook (#318; Android's PrintScreenTest). The menus
/// offer them, and each opens its system sheet with the app still running: the print dialog for
/// a recipe, the share sheet for a cookbook. What the PDF holds is PrintViewModelTests'.
final class PrintUITests: RecipeUITestCase {

    func testTheRecipeMenuOpensThePrintDialog() {
        launch(flags: ["print"])
        openRecipe("Miso Soup")

        app.buttons["More options"].tap()
        let print = require(app.buttons["recipe.print"], "Print or save as PDF")
        XCTAssertTrue(print.label.contains("Print or save as PDF"))
        print.tap()

        // The print options are the system's, drawn in the app's process.
        require(app.navigationBars.matching(NSPredicate(format: "identifier CONTAINS[c] 'Options'")).firstMatch, "the print dialog")
        XCTAssertEqual(app.state, .runningForeground)
    }

    func testAListMakesACookbookForTheShareSheet() {
        launch(flags: ["print"])
        openLists()
        require(row("Weeknights"), "Weeknights").tap()

        app.buttons["More options"].tap()
        let cookbook = require(app.buttons["list.cookbook"], "Make a cookbook")
        XCTAssertTrue(cookbook.label.contains("Make a cookbook"))
        cookbook.tap()

        let sheet = require(app.otherElements["ActivityListView"], "the share sheet")
        require(sheet.cells.firstMatch, "the share sheet's targets")
        XCTAssertEqual(app.state, .runningForeground)
    }

    func testAnEmptyListOffersNoCookbook() {
        launch(flags: ["print"])
        openLists()
        require(row("Lunch"), "Lunch").tap()

        app.buttons["More options"].tap()
        require(app.buttons["Rename"], "the overflow menu")
        assertAbsent(app.buttons["list.cookbook"])
    }
}
