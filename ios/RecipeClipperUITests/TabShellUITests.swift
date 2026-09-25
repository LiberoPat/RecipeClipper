import XCTest

/// The tab shell (#47) in both states of `FeatureFlags.mealPlanTabs`. Off is the shipped state:
/// no tab bar, the single stack. On is forced with the debug-only `-mealPlanTabs` argument.
final class TabShellUITests: RecipeUITestCase {

    private var tabBar: XCUIElement { app.tabBars.firstMatch }
    private func tab(_ label: String) -> XCUIElement { tabBar.buttons[label] }

    private func launchWithTabs(_ scenario: Scenario = .standard) {
        launch(scenario, extraArguments: ["-mealPlanTabs"])
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
        openHistory()
        assertAbsent(tabBar, "a tab bar on History")
    }

    func testWithTheFlagOffAShareStillImports() {
        launch(.empty)

        share("https://example.com/no-recipe")

        require(textContaining("Couldn't find recipe data"), "the import")
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
        require(text("What you already have, so you only buy what you need."))
        XCTAssertTrue(tab("Pantry").isSelected)
    }

    func testTheBarShowsOnTheListScreensAndHidesOnARecipe() {
        launchWithTabs()

        openHistory()
        XCTAssertTrue(tabBar.isHittable, "bar on History")
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
        openHistory()

        tab("Recipes").tap()

        require(app.textFields["Recipe URL"], "Home")
    }

    func testAShareFromAnotherTabLandsInRecipes() {
        launchWithTabs(.empty)
        tab("Pantry").tap()
        require(text("Coming soon"))

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
        require(app.webViews.firstMatch.buttons["Select title"], "the page").tap()
        require(text("1 line selected · each line becomes one item"), "the selection, heard by the app")
        app.buttons["clip.field.NAME"].tap()
        require(text("Name added"), "the Name snackbar")
        app.buttons["clip.field.PHOTO"].tap()
        require(app.webViews.firstMatch.images["Cookies photo"], "the photo").tap()
        require(text("Photo added"), "the image tap, heard by the app")
    }
}
