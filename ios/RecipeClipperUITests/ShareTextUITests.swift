import XCTest

/// Text shared in (#306) end to end, from the app's side: the share extension's hand-off
/// (`PendingText`), written by `RC_UITEST_SHARED_TEXT` exactly where the extension writes it,
/// since the system share sheet can't be driven; the app then takes its real path, the shareText
/// flag included (#334). The text opens in the editor to check, split or as written, and is
/// saved only on Save, as a recipe of the cook's own.
final class ShareTextUITests: RecipeUITestCase {

    private func launch(sharedText: String, flags: String = "shareText") {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestSeed", Scenario.empty.rawValue, "-uiTestFlags", flags]
        app.launchEnvironment["RC_UITEST_SHARED_TEXT"] = sharedText
        app.launch()
        self.app = app
    }

    /// The note field (`identifier`: the review's, or the saved recipe's): a filled text field
    /// shows no label, and its value is the note's first line.
    private func assertNote(_ identifier: String, startsWith expected: String, _ what: String) {
        let field = require(app.descendants(matching: .any)[identifier], what)
        XCTAssertTrue((field.value as? String ?? "").hasPrefix(expected), "\(what): \(String(describing: field.value))")
    }

    func testARecipeInSharedTextIsSplitCheckedAndSavedAsYourOwn() {
        launch(sharedText: #"Grandma's scones\nIngredients:\n- 2 cups flour\n- 1 tsp salt\nMethod:\n1. Mix the flour and salt.\n2. Bake for 20 minutes.\nTip: serve warm."#)

        require(text("Check the recipe"), "the review")
        require(textContaining("Sorted from the text you shared"), "how the split went")
        // Nothing shared is lost (#334): the lines the split left out are the note, as written.
        require(textContaining("are under Notes"), "where the other lines went")
        assertNote("edit.notes", startsWith: "Grandma's scones", "the note, the title line first")

        // No title is guessed: the cook names it, then saves.
        let name = require(app.textFields["Name"], "the name field")
        name.tap()
        name.typeText("Grandma's scones")
        require(app.buttons["edit.save"], "Save").tap()

        require(bookmark, "the saved recipe")
        require(text("Grandma's scones"), "its name")
        require(textContaining("2 cups flour"), "its ingredient")
        assertNote("recipeNotes", startsWith: "Grandma's scones", "its note")
        assertAbsent(app.buttons["Open original"], "a source link, on a recipe of your own")
    }

    func testTextThatDoesNotSplitOpensAsWrittenInTheSteps() {
        launch(sharedText: #"Hi Mum,\nMix the flour with the butter and bake it."#)

        require(text("Check the recipe"), "the review")
        require(textContaining("Couldn't find ingredients and steps"), "the note to finish it by hand")
    }

    func testWithTheFlagOffSharedTextStaysWhereItIs() {
        launch(sharedText: #"Ingredients:\n- 2 cups flour\nMethod:\n1. Mix."#, flags: "none")

        require(app.buttons["home.typeRecipe"], "Home")
        assertAbsent(text("Check the recipe"), "the review, with shareText off")
    }
}
