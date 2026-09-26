import XCTest

// Walkthroughs 06–10: the Recipes screen, amounts in steps, Chef mode (stub model), the free
// tier and a recipe picked from the page text (seeded as such: no model runs).

/// The seeded step the stub model shortens (UITestSeeding.chefStep, in the app target).
enum UITestWalkthroughStep {
    static let chef = "Preheat the oven to 350°F and butter a 9-inch round cake tin."
}

extension WalkthroughUITests {

    /// The editor's multi-line fields are text views; the name is a text field.
    private func field(_ prompt: String) -> XCUIElement {
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
        start(flags: [])
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
    }

    func test07_amountsInSteps() {
        start(flags: ["amountsInSteps"])
        require(app.buttons["Settings"]).tap()
        pause()
        flipSwitch("Amounts in steps")
        back()
        open("Weeknight Chili")
        app.swipeUp()
        pause(2.5)
        require(app.buttons["Start cooking"]).tap()
        pause(2.5)
    }

    func test08_chefModeStubModel() {
        start(flags: ["chefMode"])
        require(app.buttons["Settings"]).tap()
        pause()
        flipSwitch("Chef mode")
        back()
        open("Sponge Cake")
        app.swipeUp()
        pause(2.5)
        let short = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Oven to 350°F; butter a 9-inch tin.")).firstMatch
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
        start(flags: ["freeTier"])
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
        start(flags: ["llmExtraction"])
        open("Grandma's Lentil Soup")
        require(textContaining("Picked from the page text"), "the provenance line")
        pause(3)
        app.swipeUp()
        pause(2)
    }
}
