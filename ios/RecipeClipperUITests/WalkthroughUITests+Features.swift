import XCTest

// Walkthroughs 24–27: tooltips (#190), the pantry's three states (#194), singular and plural
// names in What I need (#191), and a Reddit post imported (#11) from a fixture listing.

extension WalkthroughUITests {

    /// Taps a tooltip's "Got it" and waits for the bubble to go.
    private func gotIt(_ id: String, _ what: String) {
        let bubble = require(app.buttons["tooltip.\(id)"], what)
        pause(2.5)
        bubble.tap()
        requireGone(bubble, "\(what), dismissed")
        pause()
    }

    /// A fresh install's tooltips: one a visit, at its control; the next on a later visit; and
    /// Settings' "Show tips again".
    func test24_tooltips() {
        startFirstRun(flags: ["mealPlan"])
        gotIt("home_link", "Home's first tooltip")
        require(row("Tomato and White Bean Soup"), "the sample recipe").tap()
        gotIt("recipe_servings", "the recipe's first tooltip")
        back()
        gotIt("home_new_recipe", "Home's next tooltip, on a later visit")
        require(app.buttons["Settings"]).tap()
        pause()
        let settingsTip = app.buttons["tooltip.settings_units"]
        if settingsTip.waitForExistence(timeout: 4) { gotIt("settings_units", "Settings' first tooltip") }
        scrollTo(app.buttons["settings.showTips"], "Show tips again")
        pause()
        app.buttons["settings.showTips"].tap()
        pause(1.5)
        back()
        require(app.buttons["tooltip.home_link"], "Home's first tooltip, again")
        pause(3)
    }

    private func pantryRow(_ name: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "\(name), ")).firstMatch
    }

    /// Scrolls the Pantry (a lazy List: rows off screen aren't in the tree) until `element` can
    /// be tapped, down first, then back up.
    @discardableResult
    private func reveal(_ element: XCUIElement, _ what: String) -> XCUIElement {
        for _ in 0..<3 where !(element.exists && element.isHittable) { app.swipeUp(); pause(0.6) }
        for _ in 0..<4 where !(element.exists && element.isHittable) { app.swipeDown(); pause(0.6) }
        return require(element, what)
    }

    /// In stock, Running low, Run out (#194): the row's button, its long-press menu, and a swipe
    /// each way. Run out items sit in their own section at the foot of the Pantry.
    func test25_pantryStates() {
        start(flags: ["mealPlan"], scenario: .walkthroughPantry)
        tab("Pantry")
        pause()
        require(app.buttons["Ran out: soy sauce"], "soy sauce's Ran out").tap()
        pause()
        reveal(app.buttons["Restock: soy sauce"], "soy sauce, run out")
        pause(2.5)
        reveal(pantryRow("olive oil"), "olive oil").press(forDuration: 1.2)
        pause()
        require(app.buttons["Running low"], "the menu's Running low").tap()
        require(app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'low-'")).firstMatch, "the Low tag")
        pause(2.5)
        reveal(app.buttons["Restock: soy sauce"], "soy sauce's Restock").tap()
        pause()
        reveal(app.buttons["Ran out: soy sauce"], "soy sauce, back in stock")
        pause(2)
        reveal(pantryRow("basmati rice"), "basmati rice").swipeLeft()
        pause(1.5)
        require(app.buttons["Running low"], "the swipe's Running low").tap()
        pause(2)
        reveal(pantryRow("garlic"), "garlic").swipeRight()
        pause(1.5)
        require(app.buttons["Restock"], "the swipe's Restock").tap()
        pause()
        reveal(app.buttons["Ran out: garlic"], "garlic, back in stock")
        pause(2.5)
    }

    /// "onions" in the pantry covers the Adobo's "1 onion, sliced" (#191); the Guacamole's red
    /// onion is another thing, so it stays To buy.
    func test26_onionPlurals() {
        start(flags: ["mealPlan"])
        tab("Pantry")
        type("onions\n", into: app.textFields["Add to the pantry"])
        pause()
        tab("Week")
        planToday("Chicken Adobo")
        planToday("Guacamole")
        recipeMenu("What I need")
        require(textContaining("red onion"), "the red onion, To buy")
        pause(2.5)
        scrollTo(textContaining("1 onion, sliced"), "the Adobo's onion, in the pantry")
        pause(3)
    }

    /// A Reddit post (#11): an image post whose recipe is in the poster's comment. reddit.com
    /// refuses this machine, so the link goes through the real parser over a fixture listing
    /// (`RC_UITEST_REDDIT_LISTING`, read by the stub source).
    func test27_redditImport() throws {
        let fixture = try XCTUnwrap(Bundle(for: WalkthroughUITests.self)
            .url(forResource: "recipes-image-op-comment", withExtension: "json", subdirectory: "reddit"))
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestSeed", Scenario.walkthrough.rawValue, "-uiTestFlags", "mealPlan,reddit"]
        app.launchEnvironment["RC_UITEST_REDDIT_LISTING"] = try String(contentsOf: fixture, encoding: .utf8)
        app.launch()
        self.app = app
        let field = require(app.textFields["Recipe URL"], "Home")
        mark("START")
        pause()
        field.tap()
        field.typeText("https://www.reddit.com/r/recipes/comments/1f3k9xq/sticky_honey_garlic_chicken_thighs/")
        pause()
        app.scrollViews.firstMatch.buttons["Go"].tap()
        require(bookmark, "the recipe screen")
        pause(3)
        app.swipeUp()
        pause(2.5)
        app.swipeUp()
        pause(2.5)
    }
}
