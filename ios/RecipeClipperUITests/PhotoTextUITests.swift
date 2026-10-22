import XCTest

/// "Read the photo" (#198) end to end: an untranscribed Reddit gallery (a `shared/fixtures/reddit`
/// listing through the real parser, as walkthrough 27 serves one, since reddit.com refuses this
/// machine), the button beside Try again, the editor filled from canned recognised lines
/// (`RC_UITEST_PHOTO_LINES`, UITestSeeding's stub reader: no picture is fetched, Vision never
/// runs), the unsure line marked, and Save replacing the editor and the error screen.
final class PhotoTextUITests: RecipeUITestCase {

    private let postLink = "https://www.reddit.com/r/Old_Recipes/comments/1f6d4ef/aunt_junes_oatmeal_cookies_front_and_back_of_the_card/"
    private let title = "Aunt June's oatmeal cookies, front and back of the card"

    private func launch(lines: String) throws {
        let fixture = try XCTUnwrap(Bundle(for: PhotoTextUITests.self)
            .url(forResource: "old-recipes-card-untranscribed", withExtension: "json", subdirectory: "reddit"))
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestSeed", Scenario.empty.rawValue, "-uiTestFlags", "reddit,photoText"]
        app.launchEnvironment["RC_UITEST_REDDIT_LISTING"] = try String(contentsOf: fixture, encoding: .utf8)
        app.launchEnvironment["RC_UITEST_PHOTO_LINES"] = lines
        app.launch()
        self.app = app
        pasteLink(postLink)
        require(textContaining("No recipe text found"), "the post's no-transcription note")
        require(app.buttons["Try again"], "Try again")
        require(app.buttons["recipe.readPhoto"], "Read the photo").tap()
    }

    func testAPhotoReadOnTheDeviceIsCheckedThenSavedAsTheRecipe() throws {
        try launch(lines: "Ingredients\n1 cup butter\n?1 1/2 cups flour\nDirections\n1. Cream the butter.\n2. Stir in the flour.")

        require(text("Check the recipe"), "the review")
        require(textContaining("Read from the photo"), "how the reading went")
        require(app.descendants(matching: .any)["edit.photoCheck"], "the lines to check")
        // #286: the pictures in a row with the count, and the unsure line as a card: the strip of
        // the photo it was read from (the stub reader places each line), then the line as a field.
        require(textContaining("· 1 line to check"), "the count over the cards")
        require(app.descendants(matching: .any)["edit.strip.0"], "the line's strip of the photo")
        requireState(app.textFields["edit.checkLine.0"], "value == '1 1/2 cups flour'", "the unsure line, as a field")

        require(app.buttons["edit.save"], "Save").tap()
        require(bookmark, "the saved recipe")
        require(text(title), "the post's title")
        require(text("Cream the butter."), "a step read from the photo")

        // The editor and the error screen are gone: Back is Home.
        back()
        require(homePasteLink, "Home, straight back")
    }

    /// Vision's "34 tsp salt" for ¾ (#249): read plainly, just too much, so it has its own reason
    /// and the photo's heading doesn't show (#281).
    func testALineFlaggedOnlyForItsSizeSaysSo() throws {
        try launch(lines: "Ingredients\n34 tsp salt\n1 cup flour\nDirections\n1. Mix.")

        require(app.descendants(matching: .any)["edit.photoCheck"], "the lines to check")
        requireState(app.textFields["edit.checkLine.0"], "value == '34 tsp salt'", "the large line")
        require(text("This amount looks large: check it"), "its reason")
        XCTAssertFalse(textContaining("Check these lines").exists, "no photo heading for a size alone")
    }

    /// A fraction glyph read as "%" (#245, ML Kit's reading of card-fractions-hand.jpg): the line
    /// is listed with the fractions to tap, and the one tapped replaces it in place.
    func testAFractionReadAsALookAlikeIsFixedWithOneTap() throws {
        try launch(lines: "Ingredients\n2 % cups oats\n1 cup 2% milk\nDirections\n1. Mix.")

        require(textContaining("A fraction? Tap the one in the photo"), "the fraction prompt")
        XCTAssertFalse(app.buttons["edit.fraction.1.⅔"].exists, "only the look-alike's line has chips")
        require(app.descendants(matching: .any)["edit.strip.0"], "the strip beside the line")
        require(app.buttons["edit.fraction.0.⅔"], "the ⅔ chip").tap()
        requireState(app.textFields["edit.checkLine.0"], "value == '2 ⅔ cups oats'", "the line, fixed")
        XCTAssertFalse(app.buttons["edit.fraction.0.⅔"].exists, "the chips go once it's fixed")

        require(app.buttons["edit.save"], "Save").tap()
        require(bookmark, "the saved recipe")
        require(textContaining("2 ⅔ cups oats"), "the fixed line, saved")
    }

    func testNothingReadOpensTheEditorToFinishByHand() throws {
        try launch(lines: "")

        require(text("Couldn't read a recipe from the photo: finish it by hand."), "the fallback note")
        require(app.buttons["edit.save"], "Save").tap()
        require(text("A recipe needs a name, and ingredients or steps."), "nothing saved without a recipe")
    }
}
