import XCTest

/// "Clip it yourself" end to end, guided (#251, field first since #237): the no-recipe error
/// offers it, and on the page (a fixed local one, UITestSeeding.clipFixtureHTML, never the
/// network) the clip opens on step 1 with Name armed; each step is a tap on its text and a tap on
/// the confirm, and the next step arms by itself, with no long press. A list comes whole, a tag
/// takes back its own add and Undo brings it back, a field button jumps back to a step, and text
/// selected first goes in with one tap on its field. The photo is a tap on it, Review saves, and
/// the saved recipe replaces both the clip and the error screen. Mirrors Android's ClipScreenTest.
final class ClipUITests: RecipeUITestCase {

    private func importLink(_ link: String) {
        pasteLink(link)
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
        require(text("Step 1 of 4 · Tap the recipe's name"), "guided on entry: step 1, Name armed")

        tapOnPage("Brown Butter Oat Cookies")
        confirm("Use as the name")
        require(text("Step 2 of 4 · Tap the ingredients"), "the next step, armed by itself")
        require(text("Name added."), "what was added")

        // A tap on one item takes the whole list.
        tapOnPage("1 cup packed brown sugar")
        confirm("Add 3 lines to Ingredients")
        require(text("Step 3 of 4 · Tap the steps"), "the steps step")

        // A tag takes back its own add; the snackbar's Undo brings it back.
        onPage(page.buttons["Ingredients · 3"], "the Ingredients tag").tap()
        require(text("3 ingredients removed"), "the removed snackbar")
        app.buttons["Undo"].tap()
        onPage(page.buttons["Ingredients · 3"], "the Ingredients tag, back")

        tapOnPage("Brown the butter until it smells nutty.")
        confirm("Add 2 lines to Steps")
        require(text("Step 4 of 4 · Tap the recipe's photo"), "the photo step")

        // Steps add up: Steps' button jumps back to the step; the paragraph after the list,
        // selected first, goes in with one tap on the field, no confirm.
        fieldButton("STEPS").tap()
        require(text("Step 3 of 4 · Tap the steps"), "back on the steps step")
        tapOnPage("Cool on the tray.")
        require(app.buttons["Add 1 line to Steps"], "the selection")
        fieldButton("STEPS").tap()
        require(text("1 step added."), "the step added after")
        require(text("Step 4 of 4 · Tap the recipe's photo"), "the photo step again")

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
        require(homePasteLink, "Home")

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
        require(text("Step 4 of 4 · Tap the recipe's photo"), "photo picking")
        onPage(page.staticTexts["Method"], "a heading on the page").tap()
        requireSnackbar("Couldn't read a picture there. The photo is optional.", "the no-picture snackbar")

        fieldButton("NAME").tap()
        tapOnPage("Brown Butter Oat Cookies")
        confirm("Use as the name")
        require(text("Step 2 of 4 · Tap the ingredients"), "the name added, the next step armed")

        // Skip moves on without touching the page; Skip on Photo ends the steps.
        require(app.buttons["Skip"], "Skip").tap()
        require(text("Step 3 of 4 · Tap the steps"), "the steps step")
        fieldButton("PHOTO").tap()
        require(app.buttons["Skip"], "Skip").tap()
        requireGone(app.buttons["Skip"], "the steps, over")

        fieldButton("INGREDIENTS").tap()
        tapOnPage("3 cups rolled oats")
        confirm("Add 3 lines to Ingredients")
        require(text("Step 3 of 4 · Tap the steps"), "the ingredients added")
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
            textContaining("selected ·").waitForExistence(timeout: 3),
            "a selection posted from a frame inside the page reached the app"
        )
        require(text("Step 1 of 4 · Tap the recipe's name"), "the first step, nothing selected")

        // The page itself is still heard.
        if !page.staticTexts["Brown Butter Oat Cookies"].isHittable { page.swipeDown() }
        tapOnPage("Brown Butter Oat Cookies")
        require(app.buttons["Use as the name"], "the page's tap, heard")
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
        tapOnPage("Brown Butter Oat Cookies")
        require(app.buttons["Use as the name"], "the page, ready to clip")

        app.navigationBars.buttons["Cancel"].tap()
        require(homePasteLink, "Home, not an error screen")
    }

    /// The Text view, line by line (#286): on a Reddit post, Text lists the post's lines (the
    /// fixture's hidden `shreddit-post`) as rows, each a tick. Selected first: step 1 disarmed,
    /// the title ticked ("1 line selected · tap the field they go in"), and Name's button adds it.
    /// Armed: the first ingredient, then the last, tick the run, and the step's confirm adds it,
    /// its tag on its first line. Mirrors Android's ClipScreenTest.theTextViewTicksLinesThenThePageWorks.
    func testTheTextViewTicksLinesSelectedFirstOrArmed() {
        launch(.empty, flags: ["reddit"])
        importLink("reddit.com/r/recipes/comments/abc123/blocked")
        onPage(page.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Brown Butter Oat Cookies")).firstMatch, "the page")
        require(app.navigationBars.buttons["Text"], "Text").tap()

        let title = require(app.buttons["clip.line.0"], "the post's title, a line to tick")
        XCTAssertFalse(title.isSelected)
        fieldButton("NAME").tap() // disarms step 1
        title.tap()
        require(textContaining("1 line selected · tap the field they go in"), "the count")
        XCTAssertTrue(app.buttons["clip.line.0"].isSelected)
        fieldButton("NAME").tap()
        require(text("Step 2 of 4 · Tap the ingredients"), "the next step, armed by itself")
        XCTAssertFalse(app.buttons["clip.line.0"].isSelected)

        app.buttons["clip.line.1"].tap()
        require(app.buttons["Add 1 line to Ingredients"], "one line ticked")
        app.buttons["clip.line.3"].tap()
        XCTAssertTrue(app.buttons["clip.line.2"].isSelected, "the run between")
        confirm("Add 3 lines to Ingredients")
        require(text("Step 3 of 4 · Tap the steps"), "step 3")
        XCTAssertEqual(require(app.buttons["clip.tag.m2"], "the add's tag").label, "Ingredients · 3")
        XCTAssertFalse(app.buttons["clip.line.1"].isSelected)
    }
}
