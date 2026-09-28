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
        let field = require(app.textFields["Recipe URL"], "Home")
        field.tap()
        field.typeText(postLink)
        app.scrollViews.firstMatch.buttons["Go"].tap()
        require(textContaining("No recipe text found"), "the post's no-transcription note")
        require(app.buttons["Try again"], "Try again")
        require(app.buttons["recipe.readPhoto"], "Read the photo").tap()
    }

    func testAPhotoReadOnTheDeviceIsCheckedThenSavedAsTheRecipe() throws {
        try launch(lines: "Ingredients\n1 cup butter\n?1 1/2 cups flour\nDirections\n1. Cream the butter.\n2. Stir in the flour.")

        require(text("Check the recipe"), "the review")
        require(textContaining("Read from the photo"), "how the reading went")
        require(app.descendants(matching: .any)["edit.photoCheck"], "the lines to check")
        require(textContaining("1 1/2 cups flour"), "the unsure line")

        require(app.buttons["edit.save"], "Save").tap()
        require(bookmark, "the saved recipe")
        require(text(title), "the post's title")
        require(text("Cream the butter."), "a step read from the photo")

        // The editor and the error screen are gone: Back is Home.
        back()
        require(app.textFields["Recipe URL"], "Home, straight back")
    }

    func testNothingReadOpensTheEditorToFinishByHand() throws {
        try launch(lines: "")

        require(text("Couldn't read a recipe from the photo: finish it by hand."), "the fallback note")
        require(app.buttons["edit.save"], "Save").tap()
        require(text("A recipe needs a name, and ingredients or steps."), "nothing saved without a recipe")
    }
}
