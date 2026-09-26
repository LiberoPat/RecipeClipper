import XCTest

/// The first-run tour end to end (#151): the welcome on a fresh install's first launch, the
/// sample recipe, a one-time tip, and Settings' "Show the tour again". Android's
/// WelcomeScreenTest, TipsTest and MainActivitySmokeTest.
final class WelcomeUITests: RecipeUITestCase {

    /// A fresh install (`-uiTestTour`), which `launch` can't be used for: it waits for Home,
    /// which the welcome covers.
    private func launchFresh(keepPrefs: Bool = false, flags: [String] = []) {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestSeed", "empty", "-uiTestTour"]
        if keepPrefs { app.launchArguments.append("-uiTestKeepPrefs") }
        if !flags.isEmpty { app.launchArguments += ["-uiTestFlags", flags.joined(separator: ",")] }
        app.launch()
        self.app = app
    }

    func testTheFirstLaunchWalksThroughTheCardsAndTryItOpensTheSample() {
        launchFresh(flags: ["mealPlan"])

        require(text("Just the recipe"), "the welcome")
        XCTAssertEqual(app.otherElements["welcome.page"].label, "Card 1 of 4")
        require(app.buttons["welcome.next"]).tap()
        require(text("Clip a recipe"))
        require(app.buttons["welcome.next"]).tap()
        require(text("Every day"))
        require(app.buttons["welcome.next"]).tap()
        require(text("Every week"))
        assertAbsent(app.buttons["welcome.skip"], "Skip on the last card")

        require(app.buttons["welcome.trySample"]).tap()
        require(bookmark, "the sample recipe")
        require(text("Tomato and White Bean Soup"))
        // The first recipe opened shows its tip once; a tap dismisses it.
        let tip = require(app.buttons["tip.RECIPE"], "the recipe tip")
        tip.tap()
        requireGone(app.buttons["tip.RECIPE"])
    }

    func testSkipLeavesForHomeWithTheSampleSavedAndTheWelcomeDoesNotReturn() {
        launchFresh()

        require(app.buttons["welcome.skip"]).tap()
        require(app.textFields["Recipe URL"], "Home")
        require(row("Tomato and White Bean Soup"), "the sample, saved like any recipe")

        app.terminate()
        launchFresh(keepPrefs: true)
        require(app.textFields["Recipe URL"], "Home, with no welcome")
        assertAbsent(text("Just the recipe"))
    }

    func testSettingsShowsTheTourAgain() {
        launch(.standard)
        openSettings()
        let row = app.buttons["settings.showTour"]
        for _ in 0..<6 where !row.isHittable { app.swipeUp() }
        require(row).tap()

        require(text("Just the recipe"), "the welcome again")
        require(app.buttons["welcome.skip"]).tap()
        require(text("Oven temperature"), "back in Settings")
    }
}
