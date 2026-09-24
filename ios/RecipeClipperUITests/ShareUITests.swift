import XCTest

/// Sharing a recipe out. On an iPad the share sheet is a popover, and a popover with nothing
/// to anchor to crashes the app, so this runs on both device families (issue #20). Confirmed
/// on an iPad simulator: it anchors under the toolbar's share button rather than failing to
/// present or covering the whole screen.
final class ShareUITests: RecipeUITestCase {

    func testShareOpensTheShareSheetAndTheAppSurvives() {
        launch()
        openRecipe("Miso Soup")

        require(app.buttons["Share recipe"]).tap()
        // UIActivityViewController's own collection view, however it is presented: a popover
        // anchored to the button on iPad, a bottom sheet on iPhone.
        let sheet = require(app.otherElements["ActivityListView"], "the share sheet")
        XCTAssertEqual(app.state, .runningForeground)

        // Dismiss by tapping outside it: the popover's full-screen dismiss region on iPad, the
        // dimmed background above the sheet on iPhone. Then the recipe screen is still intact.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.03)).tap()
        requireGone(sheet, "the share sheet")
        require(bookmark, "the recipe screen")
    }
}
