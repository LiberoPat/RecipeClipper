import XCTest

/// "Clip it yourself" end to end: the no-recipe error offers it, the page (a fixed local one,
/// UITestSeeding.clipFixtureHTML, never the network) is clipped by selection, a tag clears a
/// field and Undo brings it back, the Photo button picks an image, Review saves, and the saved
/// recipe replaces both the clip and the error screen. Mirrors Android's ClipScreenTest.
final class ClipUITests: RecipeUITestCase {

    private func importLink(_ link: String) {
        let field = app.textFields["Recipe URL"]
        field.tap()
        field.typeText(link)
        app.scrollViews.firstMatch.buttons["Go"].tap()
    }

    private var page: XCUIElement { app.webViews.firstMatch }

    /// The page's elements reach the accessibility tree from WebKit's own process, and on a busy
    /// runner the first query for them has taken 9 s and then 28 s while the page was already on
    /// screen (a nightly failed on a "Select title" that its own tree dump showed). Later queries
    /// are quick. So anything on the page gets a longer wait than the app's own views.
    private let pageTimeout: TimeInterval = 60

    @discardableResult
    private func onPage(_ element: XCUIElement, _ what: String) -> XCUIElement {
        require(element, what, within: pageTimeout)
    }

    private func fieldButton(_ field: String) -> XCUIElement { app.buttons["clip.field.\(field)"] }

    private func selectOnPage(_ button: String) {
        onPage(page.buttons[button], button).tap()
    }

    /// Each snackbar leaves by itself four seconds later, sliding down across the field buttons,
    /// and a tap on a button just as it passes lands on the snackbar instead (a nightly lost
    /// "Steps 1" that way). So after reading one, wait for it to go before the next tap, rather
    /// than hope the tap misses those few frames.
    private func requireSnackbar(_ message: String, _ what: String) {
        require(text(message), what)
        requireGone(text(message), what)
    }

    func testClipAPageFromTheErrorScreenToASavedRecipe() {
        launch(.empty)
        importLink("example.com/no-recipe")

        require(app.buttons["Try again"], "Try again")
        require(app.buttons["Report this site"], "Report this site")
        require(app.buttons["Clip it yourself"], "Clip it yourself").tap()

        selectOnPage("Select title")
        require(text("1 line selected · each line becomes one item"), "the selection preview")
        fieldButton("NAME").tap()
        requireSnackbar("Name added", "the Name snackbar")

        selectOnPage("Select ingredients")
        require(text("3 lines selected · each line becomes one item"), "the selection preview")
        require(app.buttons["Ingredients 3"], "the Ingredients count").tap()
        requireSnackbar("3 ingredients added", "the Ingredients snackbar")

        // The page tags the field; tapping the tag clears it, and Undo brings it back.
        onPage(page.buttons["Ingredients · 3"], "the Ingredients tag").tap()
        require(text("Ingredients cleared"), "the cleared snackbar")
        app.buttons["Undo"].tap()
        require(text("Name ✓ · 3 ingredients · 0 steps · no photo"), "the summary after Undo")

        // Assigning again replaces: two steps, then one.
        selectOnPage("Select steps")
        require(app.buttons["Steps 2"], "the Steps count").tap()
        requireSnackbar("2 steps added", "the Steps snackbar")
        selectOnPage("Select last step")
        require(app.buttons["Steps 1"], "the Steps count").tap()
        requireSnackbar("1 step added", "the replaced Steps snackbar")

        fieldButton("PHOTO").tap()
        require(text("Tap the picture to use as the photo."), "photo picking")
        onPage(page.images["Cookies photo"], "the photo on the page").tap()
        requireSnackbar("Photo added", "the Photo snackbar")
        require(text("Name ✓ · 3 ingredients · 1 step · photo"), "the summary")

        app.navigationBars.buttons["Done"].tap()
        require(text("Ingredients · 3"), "Review")
        let serves = app.textFields["Serves"]
        require(serves, "Serves").tap()
        serves.typeText("24 cookies")
        let save = app.buttons["Save recipe"]
        if !save.isHittable { app.swipeUp() }
        require(save, "Save recipe").tap()

        require(bookmark, "the saved recipe")
        require(text("Brown Butter Oat Cookies"), "its title")
        require(text("Bake at 350°F for 11 to 13 minutes."), "its one step")
        require(textContaining("Clipped by you · example.com"), "the clip credit under the title")

        // Back goes where the share came from, not to the clip or the error.
        back()
        require(app.textFields["Recipe URL"], "Home")

        openRecipes()
        require(
            app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@ AND label CONTAINS %@",
                                             "Brown Butter Oat Cookies", "Clipped by you")).firstMatch,
            "the History row saying it was clipped"
        )
    }
}
