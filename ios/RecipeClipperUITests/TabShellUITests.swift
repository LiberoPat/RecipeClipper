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

    func testTheOtherTabsShowTheirPlaceholders() {
        launchWithTabs()

        tab("Week").tap()
        require(text("Plan what you're cooking on each day of the week."))
        require(text("Coming soon"))
        tab("Groceries").tap()
        require(text("A shopping list built from the recipes in your week."))
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
        require(text("Coming soon"))
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
}
