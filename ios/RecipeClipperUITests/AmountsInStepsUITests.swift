import XCTest

/// Settings → Steps → "Amounts in steps" (#101), behind the `amountsInSteps` flag. The `cook`
/// scenario's "Weeknight Chili" lists "1 lb beef", and its first step is "Brown the beef in a
/// large pot.": with the switch on it reads "Brown 1 lb beef in a large pot." in the reading
/// view and in cook mode.
final class AmountsInStepsUITests: RecipeUITestCase {

    private var amountsSwitch: XCUIElement {
        app.switches.matching(NSPredicate(format: "label BEGINSWITH 'Amounts in steps'")).firstMatch
    }

    func testTheSwitchPutsTheAmountInsideTheStep() {
        launch(.cook, flags: ["amountsInSteps"])
        openSettings()
        app.swipeUp()
        requireState(require(amountsSwitch, "the Amounts in steps switch"), "value == '0'", "off by default")
        amountsSwitch.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        requireState(amountsSwitch, "value == '1'", "on")
        back()

        openRecipe("Weeknight Chili")
        require(textContaining("Brown 1 lb beef in a large pot."), "the amount inside the step")
        require(app.buttons["Start cooking"]).tap()
        require(textContaining("Brown 1 lb beef in a large pot."), "the amount in cook mode")
    }
}
