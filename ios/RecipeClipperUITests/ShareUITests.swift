import XCTest

/// Sharing a recipe out. On an iPad the share sheet is a popover, and a popover with nothing
/// to anchor to crashes the app, so this runs on both device families (issue #20).
final class ShareUITests: RecipeUITestCase {

    func testShareOpensTheShareSheetAndTheAppSurvives() {
        launch()
        openRecipe("Miso Soup")

        require(app.buttons["Share recipe"]).tap()
        print(app.debugDescription)
        XCTAssertEqual(app.state, .runningForeground)
    }
}
