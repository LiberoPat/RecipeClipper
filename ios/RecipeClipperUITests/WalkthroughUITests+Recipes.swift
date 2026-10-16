import XCTest

// Walkthroughs 06–10: the Recipes screen (and a burst of deletes, #268), amounts in steps (#254), Chef mode (stub model), the free
// tier and a recipe picked from the page text (seeded as such: no model runs).

/// The seeded step the stub model shortens (UITestSeeding.chefStep, in the app target).
enum UITestWalkthroughStep {
    static let chef = "Preheat the oven to 350°F and butter a 9-inch round cake tin."
    /// Its short version (UITestSeeding.chefShortStep).
    static let chefShort = "Preheat oven to 350°F; butter a 9-inch tin."
}

extension WalkthroughUITests {

    /// The editor's multi-line fields are text views; the name is a text field.
    func field(_ prompt: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(
            format: "(elementType == %lu OR elementType == %lu) AND (placeholderValue == %@ OR label == %@)",
            XCUIElement.ElementType.textField.rawValue, XCUIElement.ElementType.textView.rawValue, prompt, prompt
        )).firstMatch
    }

    private func typeARecipe() {
        require(app.buttons["recipes.add"]).tap()
        pause()
        require(app.buttons["Type a recipe"]).tap()
        pause()
        type("Weeknight Stew", into: field("Name"))
        type("2 carrots\n1 lb stewing beef", into: field("Ingredients, one per line"))
        require(app.buttons["edit.save"]).tap()
        pause(2)
    }

    func test06_recipesScreen() {
        startAsShipped()
        openRecipes()
        pause()
        app.swipeUp()
        pause()
        require(app.buttons["More options"], "the sort menu").tap()
        pause()
        require(app.buttons["Name"], "Sort by name").tap()
        pause(2)
        typeARecipe()
        back()
        pause()
        require(app.buttons["recipes.add"]).tap()
        pause()
        require(app.buttons["Paste a link"]).tap()
        pause()
        app.alerts.firstMatch.textFields.firstMatch.typeText("https://example.com/chicken-soup")
        pause()
        app.alerts.firstMatch.buttons["Go"].tap()
        require(bookmark, "the imported recipe")
        pause(2.5)
        back()
        pause()
        // A burst of swipes shares one snackbar, counting in place, and one Undo brings them all
        // back, with no snackbar after it (#268).
        // Back to the top of the list, sorted by name.
        for _ in 0..<5 where !(row("Banana Bread").exists && row("Banana Bread").isHittable) {
            app.swipeDown()
            pause(0.6)
        }
        pause()
        // No waits between them: each must land while the snackbar still shows (RecipesUITests).
        for title in ["Banana Bread", "Beef Stir-Fry", "Buttermilk Pancakes"] {
            row(title).swipeLeft()
            app.buttons["Delete"].tap()
        }
        require(text("3 recipes deleted"), "the one snackbar")
        pause(2)
        require(app.buttons["Undo"], "Undo").tap()
        pause(3.5)
    }

    /// Amounts in steps (#101, #254): the switch, then the Lemon Garlic Salmon, whose butter is on
    /// two lines (`UITestWalkthroughSeed.salmon`): "the butter" is a link that shows both lines,
    /// with "Back to step 2"; in cook mode it opens the ingredients bar on them.
    func test07_amountsInSteps() {
        startAsShipped()
        require(app.buttons["Settings"]).tap()
        pause()
        flipSwitch("Amounts in steps")
        back()
        openRecipes()
        pause()
        scrollTo(row("Lemon Garlic Salmon"), "the salmon").tap()
        require(bookmark, "the salmon")
        pause()
        let butter = app.links["butter"].firstMatch
        scrollTo(butter, "the butter link")
        pause(2)
        butter.tap()
        let chip = require(app.buttons["recipe.backToStep"], "Back to step 2")
        pause(3)
        chip.tap()
        pause(2.5)
        require(app.buttons["Start cooking"]).tap()
        pause(2)
        require(app.buttons["Done — next step"]).tap()
        pause(2)
        require(app.links["butter"].firstMatch, "the current step's butter link").tap()
        let cookChip = require(app.buttons["recipe.backToStep"], "Back to step 2, in cook mode")
        pause(3)
        cookChip.tap()
        pause(2.5)
    }

    func test08_chefModeStubModel() {
        startAsShipped(flags: ["chefMode"])
        require(app.buttons["Settings"]).tap()
        pause()
        flipSwitch("Chef mode")
        back()
        open("Sponge Cake")
        app.swipeUp()
        pause(2.5)
        let short = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", UITestWalkthroughStep.chefShort)).firstMatch
        require(short, "the short step").tap() // as written…
        pause(2.5)
        let written = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", UITestWalkthroughStep.chef)).firstMatch
        require(written, "the step as written").tap() // …and short again
        pause(2)
        require(app.buttons["Start cooking"]).tap()
        pause(2)
        require(app.buttons["As written"], "As written").tap()
        pause(2.5)
    }

    func test09_freeTier() {
        startAsShipped(flags: ["freeTier"])
        openRecipes()
        require(app.staticTexts["recipes.count"], "the count")
        pause(2)
        typeARecipe()
        require(app.alerts.firstMatch.buttons["Unlock"], "the library-full prompt")
        pause(3)
        app.alerts.firstMatch.buttons["Cancel"].tap()
        pause()
    }

    func test10_pageExtractionLine() {
        startAsShipped(flags: ["llmExtraction"])
        open("Grandma's Lentil Soup")
        require(textContaining("Picked from the page text"), "the provenance line")
        pause(3)
        app.swipeUp()
        pause(2)
    }
}
