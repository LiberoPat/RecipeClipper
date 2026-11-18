import XCTest

/// The text clip 37 shares in (#306): a relative's scones, with a tip and a link that the split
/// leaves to Notes; and a bulleted list for "Add this list".
enum UITestWalkthroughText {
    static let scones = """
    Hi love, here are Grandma's scones as promised.

    Ingredients:
    - 2 cups flour
    - 1 tbsp sugar
    - 1 tsp salt
    - 1/2 cup cold butter
    - 3/4 cup milk

    Method:
    1. Mix the flour, sugar and salt, then rub in the butter.
    2. Stir in the milk and pat into rounds.
    3. Bake at 425°F for 15 minutes.

    Tip: serve warm with jam.
    https://example.com/family-scones
    """
    static let list = "Saturday\n- 6 eggs\n- 1 cup plain yogurt\n- 2 lemons\n- a bunch of basil"
}

extension WalkthroughUITests {

    /// Text shared in (#306): the share extension's hand-off, seeded where it writes it
    /// (`RC_UITEST_SHARED_TEXT`, since the share sheet can't be driven). The recipe opens sorted
    /// in the editor, its leftovers under Notes, is named and saved; then a bulleted list goes to
    /// "Add this list" (through Paste a list: the extension's own sheet runs in another app).
    func test37_shareText() {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestSeed", Scenario.walkthrough.rawValue, "-uiTestFlags", "shareText",
                               "-uiTestPasteboard", UITestWalkthroughText.list.replacingOccurrences(of: "\n", with: "\\n")]
        app.launchEnvironment["RC_UITEST_SHARED_TEXT"] = UITestWalkthroughText.scones.replacingOccurrences(of: "\n", with: "\\n")
        app.launch()
        self.app = app
        require(text("Check the recipe"), "the review")
        mark("START")
        pause(3)
        require(textContaining("are under Notes"), "where the other lines went")
        pause(2)
        scrollTo(app.descendants(matching: .any)["edit.notes"], "the Notes field")
        pause(3)
        for _ in 0..<3 { app.swipeDown(); pause(0.4) }
        let name = require(app.textFields["Name"], "the name field")
        name.tap()
        name.typeText("Grandma's Scones")
        pause()
        require(app.buttons["edit.save"], "Save").tap()
        require(bookmark, "the saved recipe")
        pause(3)
        tab("Groceries")
        recipeMenu("Paste a list")
        require(text("Add this list"), "the sheet")
        pause(3)
        require(app.buttons["receiveToGroceries"], "Add to groceries").tap()
        pause(3)
    }

    /// Print or save as PDF (#318) from the recipe's menu: the system print dialog with its
    /// preview; then a list's "Make a cookbook": "Making the cookbook…", then the share sheet
    /// with the PDF.
    func test40_printAndCookbook() {
        start(flags: ["print"])
        open("Chicken Adobo")
        recipeMenu("Print or save as PDF")
        require(app.navigationBars.matching(NSPredicate(format: "identifier CONTAINS[c] 'Options'")).firstMatch, "the print dialog")
        pause(5)
        require(app.buttons["Cancel"].firstMatch, "the dialog's Cancel").tap()
        pause()
        back()
        openLists()
        require(row("Dinner"), "Dinner").tap()
        pause(1.5)
        recipeMenu("Make a cookbook")
        dismissShareSheet()
    }
}
