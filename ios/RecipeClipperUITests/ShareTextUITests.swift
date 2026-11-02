import XCTest

/// Text shared in (#306) end to end, from the app's side: the share extension's hand-off
/// (`PendingText`) stood in for by `RC_UITEST_SHARED_TEXT`, since the system share sheet can't be
/// driven. The text opens in the editor to check, split or as written, and is saved only on Save,
/// as a recipe of the cook's own.
final class ShareTextUITests: RecipeUITestCase {

    private func launch(sharedText: String) {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestSeed", Scenario.empty.rawValue, "-uiTestFlags", "shareText"]
        app.launchEnvironment["RC_UITEST_SHARED_TEXT"] = sharedText
        app.launch()
        self.app = app
    }

    func testARecipeInSharedTextIsSplitCheckedAndSavedAsYourOwn() {
        launch(sharedText: #"Ingredients:\n- 2 cups flour\n- 1 tsp salt\nMethod:\n1. Mix the flour and salt.\n2. Bake for 20 minutes."#)

        require(text("Check the recipe"), "the review")
        require(textContaining("Sorted from the text you shared"), "how the split went")

        // No title is guessed: the cook names it, then saves.
        let name = require(app.textFields["Name"], "the name field")
        name.tap()
        name.typeText("Grandma's scones")
        require(app.buttons["edit.save"], "Save").tap()

        require(bookmark, "the saved recipe")
        require(text("Grandma's scones"), "its name")
        require(textContaining("2 cups flour"), "its ingredient")
        assertAbsent(app.buttons["Open original"], "a source link, on a recipe of your own")
    }

    func testTextThatDoesNotSplitOpensAsWrittenInTheSteps() {
        launch(sharedText: #"Hi Mum,\nMix the flour with the butter and bake it."#)

        require(text("Check the recipe"), "the review")
        require(textContaining("Couldn't find ingredients and steps"), "the note to finish it by hand")
    }
}
