import XCTest

/// The tab shell (#47) in both states of the `mealPlan` flag. Off is the shipped state:
/// no tab bar, the single stack. On is set through the flag store (`launch(flags:)`, #87).
final class TabShellUITests: RecipeUITestCase {

    private var tabBar: XCUIElement { app.tabBars.firstMatch }
    private func tab(_ label: String) -> XCUIElement { tabBar.buttons[label] }

    private func launchWithTabs(_ scenario: Scenario = .standard) {
        launch(scenario, flags: ["mealPlan"])
        require(tabBar, "the tab bar")
    }

    /// The share extension's deep link, as `.onOpenURL` receives it.
    private func share(_ link: String) {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        let encoded = link.addingPercentEncoding(withAllowedCharacters: allowed)!
        app.open(URL(string: "recipeclipper://import?url=\(encoded)")!)
    }

    // MARK: - Flag off

    func testWithTheFlagOffThereIsNoTabBar() {
        launch(.standard)

        assertAbsent(tabBar, "a tab bar")
        openRecipes()
        assertAbsent(tabBar, "a tab bar on Recipes")
    }

    func testWithTheFlagOffAShareStillImports() {
        launch(.empty)

        share("https://example.com/no-recipe")

        require(textContaining("Couldn't find recipe data"), "the import")
    }

    /// Developer settings (#87): seven taps on the version open it, and the flag's switch brings
    /// the tab bar and takes it away at once, with no relaunch. Reset puts the flag back to the
    /// build's default, and the shell follows that at once too.
    func testDeveloperSettingsTurnsTheTabsOnWithoutARestart() {
        launch(.standard)
        openSettings()
        let version = app.descendants(matching: .any)["settings.version"]
        for _ in 0..<7 { require(version, "the version").tap() }
        require(text("Developer settings"), "Developer settings")

        let mealPlan = require(app.switches.firstMatch, "the mealPlan switch")
        mealPlan.tap()
        require(tabBar, "the tab bar, once the flag is on")
        mealPlan.tap()
        requireGone(tabBar, "the tab bar, once the flag is off")

        // UI tests start with every flag off, whatever its default (#152 turned mealPlan's on),
        // so the build's default decides what Reset shows. Read it from the switch once Reset
        // has applied (the button is disabled when nothing differs from the defaults), rather
        // than assume it. When a flag is already at its default, Reset has nothing to do.
        let reset = require(app.buttons["developer.reset"], "Reset")
        if reset.isEnabled { reset.tap() }
        requireState(reset, "isEnabled == false", "Reset, once every flag is at its default")
        if mealPlan.value as? String == "1" {
            require(tabBar, "the tab bar, once reset to the default (on)")
        } else {
            requireGone(tabBar, "the tab bar, once reset to the default (off)")
        }
    }

    // MARK: - Flag on

    func testTheBarShowsFourTabsInOrderWithRecipesOpen() {
        launchWithTabs()

        let labels = ["Recipes", "Week", "Groceries", "Pantry"]
        let xs = labels.map { require(tab($0), $0).frame.minX }
        XCTAssertEqual(xs, xs.sorted())
        XCTAssertTrue(tab("Recipes").isSelected)
        require(app.textFields["Recipe URL"], "Home under Recipes")
    }

    func testTheOtherTabsShowTheirScreens() {
        launchWithTabs()

        tab("Week").tap()
        require(app.staticTexts["weekRange"], "the Week (#49)")
        tab("Groceries").tap()
        require(textContaining("Your list is empty"), "the empty Groceries list (#50)")
        tab("Pantry").tap()
        require(app.textFields["Add to the pantry"], "the Pantry tab")
        XCTAssertTrue(tab("Pantry").isSelected)
    }

    func testTheBarShowsOnTheListScreensAndHidesOnARecipe() {
        launchWithTabs()

        openRecipes()
        XCTAssertTrue(tabBar.isHittable, "bar on Recipes")
        back()
        openLists()
        XCTAssertTrue(tabBar.isHittable, "bar on Lists")
        back()
        openSettings()
        XCTAssertTrue(tabBar.isHittable, "bar on Settings")
        back()

        openRecipe("Chicken Adobo")
        requireGone(tabBar, "the tab bar on a recipe")
        back()
        require(tabBar, "the tab bar back on Home")
    }

    func testEachTabKeepsItsOwnPlace() {
        launchWithTabs()
        openLists()

        tab("Week").tap()
        require(app.staticTexts["weekRange"], "the Week")
        tab("Recipes").tap()

        require(app.buttons["+ New list"], "Lists, still open under Recipes")
    }

    func testChoosingRecipesAgainGoesBackToHome() {
        launchWithTabs()
        openRecipes()

        tab("Recipes").tap()

        require(app.textFields["Recipe URL"], "Home")
    }

    func testAShareFromAnotherTabLandsInRecipes() {
        launchWithTabs(.empty)
        tab("Pantry").tap()
        require(app.textFields["Add to the pantry"], "the Pantry tab")

        share("https://example.com/no-recipe")

        require(textContaining("Couldn't find recipe data"), "the import, in Recipes")
        requireGone(tabBar, "the tab bar on the import")
        back()
        require(app.textFields["Recipe URL"], "Home, under the import")
        XCTAssertTrue(tab("Recipes").isSelected)
    }

    /// Clip it yourself (#37) is full screen too, and its page still reaches the app: hiding the
    /// bar once broke the page's events (the modifier sat outside the screen's ScreenHost).
    func testClippingIsFullScreenAndThePageStillAnswers() {
        launchWithTabs(.empty)
        share("https://example.com/no-recipe")
        require(app.buttons["Clip it yourself"], "Clip it yourself").tap()

        requireGone(tabBar, "the tab bar on the clip page")
        app.buttons["clip.field.NAME"].tap()
        require(app.webViews.firstMatch.staticTexts["Brown Butter Oat Cookies"], "the page", within: 60).tap()
        require(app.buttons["Use as the name"], "the tap, heard by the app").tap()
        require(text("Name added. Next: tap Ingredients."), "the name added")
        app.buttons["clip.field.PHOTO"].tap()
        require(app.webViews.firstMatch.images["Cookies photo"], "the photo").tap()
        require(textContaining("Photo added."), "the image tap, heard by the app")
    }
}
