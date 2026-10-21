import XCTest

/// The tab shell (#47): Recipes · Week · Groceries · Pantry, each with its own stack.
final class TabShellUITests: RecipeUITestCase {

    private var tabBar: XCUIElement { app.tabBars.firstMatch }
    private func tab(_ label: String) -> XCUIElement { tabBar.buttons[label] }

    private func launchWithTabs(_ scenario: Scenario = .standard) {
        launch(scenario)
        require(tabBar, "the tab bar")
    }

    /// The share extension's deep link, as `.onOpenURL` receives it.
    private func share(_ link: String) {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        let encoded = link.addingPercentEncoding(withAllowedCharacters: allowed)!
        app.open(URL(string: "recipeclipper://import?url=\(encoded)")!)
    }

    func testTheBarShowsFourTabsInOrderWithRecipesOpen() {
        launchWithTabs()

        let labels = ["Recipes", "Week", "Groceries", "Pantry"]
        let xs = labels.map { require(tab($0), $0).frame.minX }
        XCTAssertEqual(xs, xs.sorted())
        XCTAssertTrue(tab("Recipes").isSelected)
        require(homePasteLink, "Home under Recipes")
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
        openSettings("units")
        require(app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Metric'")).firstMatch, "Units' page")
        XCTAssertTrue(tabBar.isHittable, "bar on a Settings page (#286)")
        back()
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

        require(app.buttons["lists.newList"], "Lists, still open under Recipes")
    }

    func testChoosingRecipesAgainGoesBackToHome() {
        launchWithTabs()
        openRecipes()

        tab("Recipes").tap()

        require(homePasteLink, "Home")
    }

    func testAShareFromAnotherTabLandsInRecipes() {
        launchWithTabs(.empty)
        tab("Pantry").tap()
        require(app.textFields["Add to the pantry"], "the Pantry tab")

        share("https://example.com/no-recipe")

        require(textContaining("Couldn't find recipe data"), "the import, in Recipes")
        requireGone(tabBar, "the tab bar on the import")
        back()
        require(homePasteLink, "Home, under the import")
        XCTAssertTrue(tab("Recipes").isSelected)
    }

    /// Clip it yourself (#37) is full screen too, and its page still reaches the app: hiding the
    /// bar once broke the page's events (the modifier sat outside the screen's ScreenHost).
    func testClippingIsFullScreenAndThePageStillAnswers() {
        launchWithTabs(.empty)
        share("https://example.com/no-recipe")
        require(app.buttons["Clip it yourself"], "Clip it yourself").tap()

        requireGone(tabBar, "the tab bar on the clip page")
        // The guided steps (#251) arm Name on entry: a tap on its button would disarm it.
        require(text("Step 1 of 4 · Tap the recipe's name"), "guided on entry, Name armed")
        require(app.webViews.firstMatch.staticTexts["Brown Butter Oat Cookies"], "the page", within: 60).tap()
        require(app.buttons["Use as the name"], "the tap, heard by the app").tap()
        require(text("Step 2 of 4 · Tap the ingredients"), "the name added, the next step armed")
        app.buttons["clip.field.PHOTO"].tap()
        require(app.webViews.firstMatch.images["Cookies photo"], "the photo").tap()
        require(textContaining("Photo added."), "the image tap, heard by the app")
    }
}
