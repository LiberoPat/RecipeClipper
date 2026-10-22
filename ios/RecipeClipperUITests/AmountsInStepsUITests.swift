import XCTest

/// Settings → Steps → "Amounts in steps" (#101). The `cook`
/// scenario's "Weeknight Chili" lists "1 lb beef", and its first step is "Brown the beef in a
/// large pot.": with the switch on it reads "Brown 1 lb beef in a large pot." in the reading
/// view and in cook mode. Cook mode's ingredients bar then lists the beef under "In this step"
/// (#286), as it does with the switch off (`CookModeUITests`).
final class AmountsInStepsUITests: RecipeUITestCase {

    private var amountsSwitch: XCUIElement {
        app.switches.matching(NSPredicate(format: "label BEGINSWITH 'Amounts in steps'")).firstMatch
    }

    func testTheSwitchPutsTheAmountInsideTheStep() {
        launch(.cook)
        openSettings("steps") // Settings' index (#286): Steps' own page
        requireState(require(amountsSwitch, "the Amounts in steps switch"), "value == '0'", "off by default")
        amountsSwitch.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        requireState(amountsSwitch, "value == '1'", "on")
        back() // to the index
        back() // to Home

        openRecipe("Weeknight Chili")
        require(textContaining("Brown 1 lb beef in a large pot."), "the amount inside the step")
        require(app.buttons["Start cooking"]).tap()
        require(textContaining("Brown 1 lb beef in a large pot."), "the amount in cook mode")

        require(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Ingredients")).firstMatch, "the ingredients bar").tap()
        let tops = [text("In this step"), app.buttons["1 lb beef"], text("Later"), app.buttons["2 cups flour"]]
            .map { require($0).frame.minY }
        XCTAssertEqual(tops, tops.sorted(), "the beef under In this step, the flour under Later")
        require(textContaining("Brown 1 lb beef in a large pot."), "the step unchanged by the bar")
    }
}
