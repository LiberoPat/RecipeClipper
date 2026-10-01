import XCTest

/// "Clip it yourself" end to end, field first (#237): the no-recipe error offers it, and on the
/// page (a fixed local one, UITestSeeding.clipFixtureHTML, never the network) each field is a
/// tap on its button, a tap on its text and a tap on the confirm, with no long press. A list
/// comes whole, steps add up, a tag takes back its own add and Undo brings it back, the Photo
/// button picks an image, Review saves, and the saved recipe replaces both the clip and the error
/// screen. Mirrors Android's ClipScreenTest.
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

    /// A finger's tap on the page's text `label` (a list item's may carry its marker, so the text
    /// is matched as part of the label).
    private func tapOnPage(_ label: String) {
        let element = page.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", label)).firstMatch
        onPage(element, label).tap()
    }

    /// The hint bar's confirm, reading `label`.
    private func confirm(_ label: String) {
        require(app.buttons[label], label).tap()
    }

    /// Each snackbar leaves by itself four seconds later, sliding down across the field buttons,
    /// and a tap on a button just as it passes lands on the snackbar instead (a nightly lost
    /// "Steps 1" that way). So after reading one, wait for it to go before the next tap, rather
    /// than hope the tap misses those few frames.
    private func requireSnackbar(_ message: String, _ what: String) {
        require(text(message), what)
        requireGone(text(message), what)
    }

    func testClipAPageFieldFirstFromTheErrorScreenToASavedRecipe() {
        launch(.empty)
        importLink("example.com/no-recipe")

        require(app.buttons["Try again"], "Try again")
        require(app.buttons["Report this site"], "Report this site")
        require(app.buttons["Clip it yourself"], "Clip it yourself").tap()
        require(text("Tap Name below, then tap the recipe's name on the page."), "the first hint")

        fieldButton("NAME").tap()
        require(text("Tap the recipe's name on the page."), "Name armed")
        tapOnPage("Brown Butter Oat Cookies")
        confirm("Use as the name")
        require(text("Name added. Next: tap Ingredients."), "the next hint")

        // A tap on one item takes the whole list.
        fieldButton("INGREDIENTS").tap()
        tapOnPage("1 cup packed brown sugar")
        confirm("Add 3 lines to Ingredients")
        require(text("3 ingredients added. Next: tap Steps."), "the ingredients added")

        // A tag takes back its own add; the snackbar's Undo brings it back.
        onPage(page.buttons["Ingredients · 3"], "the Ingredients tag").tap()
        require(text("3 ingredients removed"), "the removed snackbar")
        app.buttons["Undo"].tap()
        onPage(page.buttons["Ingredients · 3"], "the Ingredients tag, back")

        // Steps add up: the list, then a paragraph after it.
        fieldButton("STEPS").tap()
        tapOnPage("Brown the butter until it smells nutty.")
        confirm("Add 2 lines to Steps")
        require(text("2 steps added. Next: tap Photo, or Done."), "the steps added")
        tapOnPage("Cool on the tray.")
        confirm("Add 1 line to Steps")
        require(text("1 step added. Next: tap Photo, or Done."), "the step added after")

        fieldButton("PHOTO").tap()
        require(text("Tap the picture to use as the photo."), "photo picking")
        onPage(page.images["Cookies photo"], "the photo on the page").tap()
        require(text("Photo added. Tap Done to review and save."), "the photo added")

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
        require(text("Cool on the tray."), "its added step")
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

    /// The owner's "stuck in the photo section": a tap while picking that found no readable
    /// picture left picking on, every tap on the page swallowed and the other fields waiting.
    /// Now one tap on anything that isn't a readable picture (here a heading) ends the step and
    /// says so, and Skip leaves the step without touching the page; either way the next field
    /// takes its tap. Mirrors Android's ClipScreenTest.
    func testTheOtherFieldsGoOnAfterATapOnSomethingElseOrSkip() {
        launch(.empty)
        importLink("example.com/no-recipe")
        require(app.buttons["Clip it yourself"], "Clip it yourself").tap()

        fieldButton("PHOTO").tap()
        require(text("Tap the picture to use as the photo."), "photo picking")
        onPage(page.staticTexts["Method"], "a heading on the page").tap()
        requireSnackbar("Couldn't read a picture there. The photo is optional.", "the no-picture snackbar")

        fieldButton("NAME").tap()
        tapOnPage("Brown Butter Oat Cookies")
        confirm("Use as the name")
        require(text("Name added. Next: tap Ingredients."), "the name added")

        fieldButton("PHOTO").tap()
        require(app.buttons["Skip"], "Skip").tap()
        requireGone(app.buttons["Skip"], "photo picking, over")

        fieldButton("INGREDIENTS").tap()
        tapOnPage("3 cups rolled oats")
        confirm("Add 3 lines to Ingredients")
        require(text("3 ingredients added. Next: tap Steps."), "the ingredients added")
    }

    /// #235: a frame inside the page (an ad, another site's widget) can reach the `rc` handler,
    /// but the app hears the main frame only, so a selection posted from the frame never shows.
    /// Mirrors Android's ClipScreenTest.
    func testAFrameInsideThePageCantPostIntoTheClip() {
        launch(.empty)
        importLink("example.com/no-recipe")
        require(app.buttons["Clip it yourself"], "Clip it yourself").tap()

        let ad = onPage(page.buttons["Ad: post a selection"], "the button inside the frame")
        if !ad.isHittable { page.swipeUp() } // the frame is at the foot of the page
        ad.tap()
        XCTAssertFalse(
            text("1 line selected · each line becomes one item").waitForExistence(timeout: 3),
            "a selection posted from a frame inside the page reached the app"
        )
        require(text("No name · 0 ingredients · 0 steps · no photo"), "the summary, nothing selected")

        // The page itself is still heard.
        selectOnPage("Select title")
        require(text("1 line selected · each line becomes one item"), "the selection preview")
    }

    /// Reddit's block (#213): a Reddit post the app can't read opens here by itself, in the
    /// import's place, with a note saying why and no Try again (the block doesn't lift). Cancel
    /// goes back to Home, never to an error screen. The stub source answers 403 for a path ending
    /// `/blocked`. Mirrors Android's ClipBlockedNoteScreenTest.
    func testABlockedRedditPostOpensHereWithANote() {
        launch(.empty, flags: ["reddit"])
        importLink("reddit.com/r/recipes/comments/abc123/blocked")

        require(text("Reddit didn't let the app read this post, so it's open here: select the recipe."), "the note")
        XCTAssertFalse(app.buttons["Try again"].exists)
        fieldButton("NAME").tap()
        tapOnPage("Brown Butter Oat Cookies")
        require(app.buttons["Use as the name"], "the page, ready to clip")

        app.navigationBars.buttons["Cancel"].tap()
        require(app.textFields["Recipe URL"], "Home, not an error screen")
    }
}
