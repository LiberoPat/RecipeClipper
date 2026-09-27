import XCTest

// Walkthroughs 14–16 and 19: the automatic backup copy and restoring on an empty Home (#150),
// sending and pasting a grocery list, a recipe sent as a file and a file received, and the
// Pantry's Send list (#149). The system's file picker and share sheet show but aren't driven.

/// A list someone sent, as "Send list" writes one (Android's SharingWalkthroughTest.FRIENDS_LIST).
enum UITestWalkthroughList {
    static let friends = "Groceries\n\nDairy & eggs\n- 6 eggs\n- 1 cup plain yogurt\n\nFruit & vegetables\n- 2 lemons"
}

extension WalkthroughUITests {

    func test14_automaticBackup() {
        start(flags: ["mealPlan"], scenario: .empty)
        let restore = require(app.buttons["home.restore"], "Restore from a backup file")
        pause()
        restore.tap()
        pause(3) // the system's file picker
        let cancel = app.buttons["Cancel"]
        if cancel.waitForExistence(timeout: 5) { cancel.tap() } else { app.swipeDown(velocity: .fast) }
        pause()
        require(app.buttons["Settings"]).tap()
        pause()
        scrollTo(app.buttons["settings.backUpNow"], "Back up now")
        pause(3.5)
    }

    func test15_sendAndPasteAList() {
        let pasteboard = UITestWalkthroughList.friends.replacingOccurrences(of: "\n", with: "\\n")
        start(flags: ["mealPlan"], scenario: .walkthroughPantry, extraArguments: ["-uiTestPasteboard", pasteboard])
        tab("Groceries")
        recipeMenu("Send list")
        dismissShareSheet()
        recipeMenu("Paste a list")
        require(text("Add this list"), "the sheet")
        pause(2)
        require(line("2 lemons"), "the lemons").tap() // unticked: stays out
        pause()
        require(app.buttons["receiveToGroceries"], "Add to groceries").tap()
        pause(2.5)
    }

    /// A file received first (`-uiTestReceiveFile` opens it at launch, as if from Messages), then
    /// a recipe sent as a file.
    func test16_sendAndReceiveAFile() {
        start(flags: ["mealPlan"], extraArguments: ["-uiTestReceiveFile"])
        require(text("Add from this file"), "the sheet")
        pause(2.5)
        require(app.buttons["receiveRow-g:ui-paper"], "the typed item").tap() // unticked: stays out
        pause()
        require(app.buttons["receiveFileAdd"], "Add").tap()
        pause(2.5)
        tab("Recipes")
        open("Chicken Adobo")
        recipeMenu("Send as file")
        dismissShareSheet()
    }

    func test19_pantrySendList() {
        start(flags: ["mealPlan"], scenario: .walkthroughPantry)
        tab("Pantry")
        pause()
        recipeMenu("Send list")
        dismissShareSheet()
        recipeMenu("Send as file")
        dismissShareSheet()
    }
}
