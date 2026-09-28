import XCTest

/// The tooltips (#190) as a fresh install has them (`-uiTestTooltips`; every other suite starts
/// with every tooltip seen). A visit shows one, at its control, once the screen has settled; "Got
/// it" dismisses it for good; the screen's next one shows on the next visit, never straight after.
final class TooltipsUITests: RecipeUITestCase {

    /// Dismisses a tooltip by its one button, and waits for it to go.
    private func gotIt(_ id: String, _ what: String) {
        let bubble = require(app.buttons["tooltip.\(id)"], what)
        bubble.tap()
        requireGone(bubble, "\(what), dismissed")
    }

    func testTheRecipeScreenShowsOneTooltipAVisitAtItsControl() {
        launch(.standard, extraArguments: ["-uiTestTooltips"])
        gotIt("home_link", "Home's first tooltip")

        openRecipe("Chicken Adobo")
        let servings = require(app.buttons["tooltip.recipe_servings"], "the recipe screen's first tooltip")
        XCTAssertTrue(servings.label.contains("Change the servings"), servings.label)
        // At its control: the bubble sits against the Serves stepper, above or below it.
        let stepper = require(app.buttons["Increase servings"], "the Serves stepper")
        let gap = min(abs(servings.frame.minY - stepper.frame.maxY), abs(stepper.frame.minY - servings.frame.maxY))
        XCTAssertLessThan(gap, 60, "bubble \(servings.frame), stepper \(stepper.frame)")
        gotIt("recipe_servings", "the servings tooltip")

        // Never chained: nothing else on this visit.
        XCTAssertFalse(app.buttons["tooltip.recipe_units"].waitForExistence(timeout: 3))

        back()
        gotIt("home_new_recipe", "Home's next tooltip, on its next visit")
        openRecipe("Chicken Adobo")
        gotIt("recipe_units", "the recipe screen's next tooltip, on its next visit")
    }
}
