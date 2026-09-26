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
        // anchored to the button (iPad, and iPhone since iOS 26), or a bottom sheet (older
        // iPhones). Its targets come from the share service's own process after the frame, so
        // wait for them too: then the sheet is where it will stay.
        let sheet = require(app.otherElements["ActivityListView"], "the share sheet")
        require(sheet.cells.firstMatch, "the share sheet's targets")
        XCTAssertEqual(app.state, .runningForeground)

        // Dismiss by tapping outside it, in the larger part of the screen it leaves free: below
        // a popover, above a bottom sheet. Never at the very top: that is the status bar, and a
        // tap there goes to the system, not the popover's dismiss region (on iOS 27 the popover
        // starts right under it, and a tap at the top failed every night). Then the recipe
        // screen is still intact.
        let screen = app.frame
        let box = sheet.frame
        let y = screen.maxY - box.maxY > box.minY ? (box.maxY + screen.maxY) / 2 : box.minY / 2
        app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: screen.midX, dy: y)).tap()
        requireGone(sheet, "the share sheet")
        require(bookmark, "the recipe screen")
    }
}
