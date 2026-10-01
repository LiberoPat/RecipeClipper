import XCTest

/// A file sent from another Recipe Clipper (#149, phase 2): the sheet it opens, and what Add puts
/// where. A UI test can't open a file from Messages, so `-uiTestReceiveFile` opens a canned one
/// at launch through the same `onOpenURL` path's ViewModel ("Shared Lemon Cake", "2 lemons" for
/// it, "baking paper", and "Basmati rice" for the pantry). The file "Send as file" writes is
/// ShareFileRepositoryTests'; the system share sheet isn't reachable from here.
final class ShareFileUITests: RecipeUITestCase {

    private var tabBar: XCUIElement { app.tabBars.firstMatch }

    private func line(_ text: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    func testAReceivedFileAddsWhatIsTickedAndShowsTheGroceries() {
        launch(.standard, extraArguments: ["-uiTestReceiveFile"])
        require(text("Add from this file"), "the sheet")
        require(app.buttons["receiveRow-r:ui-cake"], "the recipe")
        require(app.buttons["receiveRow-g:ui-lemons"], "the lemons, for the cake")
        require(app.buttons["receiveRow-g:ui-paper"], "the typed item").tap() // unticked: stays out
        require(app.buttons["receivePantryTo-GROCERIES"], "pantry items to groceries").tap()
        require(app.buttons["receiveFileAdd"], "Add").tap()
        requireGone(text("Add from this file"), "the sheet")

        // The grocery list shows, with the lemons and the pantry's rice on it.
        require(line("2 lemons"), "the lemons")
        require(line("Basmati rice"), "the rice, as a grocery")
        XCTAssertFalse(line("baking paper").exists, "an unticked item stays out")

        // And the recipe came in whole.
        tabBar.buttons["Recipes"].tap()
        require(app.staticTexts["Shared Lemon Cake"], "the recipe, the newest viewed")
    }
}
