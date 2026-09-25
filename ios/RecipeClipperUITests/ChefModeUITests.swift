import XCTest

/// Chef mode (#100) end to end, over the stub model the UI-test container injects: turn it on in
/// Settings → Steps, then the recipe shows the short step and a tap shows it as written; in cook
/// mode the current step's "As written" button does it.
final class ChefModeUITests: RecipeUITestCase {
    private let step = "Preheat the oven to 350°F and butter a 9-inch round cake tin."
    private let short = "Oven to 350°F; butter a 9-inch tin."

    /// A step's text, whatever it is exposed as (a tappable step reads as a button).
    private func labelled(_ label: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label)).firstMatch
    }

    private var chefMode: XCUIElement {
        app.switches.matching(NSPredicate(format: "label BEGINSWITH 'Chef mode'")).firstMatch
    }

    func testShortStepsInTheReadingViewAndCookMode() {
        launch(.chef, flags: ["chefMode"])
        openSettings()
        for _ in 0..<2 where !chefMode.isHittable { app.swipeUp() }
        require(textContaining("For recipes in English"), "the languages line")
        chefMode.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        requireState(chefMode, "value == '1'", "Chef mode on")
        back()

        openRecipe("Sponge Cake")
        app.swipeUp()
        require(labelled(short), "the short step").tap()
        require(labelled(step), "the step as written").tap()
        require(labelled(short), "short again")

        require(app.buttons["Start cooking"], "Start cooking").tap()
        require(labelled(short), "the short step in cook mode")
        require(app.buttons["As written"], "the As written button").tap()
        require(labelled(step), "the step as written in cook mode")
    }
}
