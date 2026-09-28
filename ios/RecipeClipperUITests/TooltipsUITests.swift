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

        // A popover is modal: while it shows, what's under it is out of the accessibility tree,
        // so the recipe is opened by its row and the stepper found once the bubble has gone.
        require(row("Chicken Adobo")).tap()
        let servings = require(app.buttons["tooltip.recipe_servings"], "the recipe screen's first tooltip")
        XCTAssertTrue(servings.label.contains("Change the servings"), servings.label)
        let bubble = servings.frame
        gotIt("recipe_servings", "the servings tooltip")
        // At its control: the bubble sat against the Serves stepper, above or below it.
        let stepper = require(app.buttons["Increase servings"], "the Serves stepper").frame
        let gap = min(abs(bubble.minY - stepper.maxY), abs(stepper.minY - bubble.maxY))
        XCTAssertLessThan(gap, 60, "bubble \(bubble), stepper \(stepper)")

        // Never chained: nothing else on this visit.
        XCTAssertFalse(app.buttons["tooltip.recipe_units"].waitForExistence(timeout: 3))

        back()
        gotIt("home_new_recipe", "Home's next tooltip, on its next visit")
        require(row("Chicken Adobo")).tap()
        gotIt("recipe_units", "the recipe screen's next tooltip, on its next visit")
    }
}
