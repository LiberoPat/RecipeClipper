import XCTest

/// "I made this" (#116; Android's RecipeCookedPhotosScreenTest): "Your cooks" sits at the foot
/// of the reading view only behind its flag, and offers the camera or the library. The
/// simulator has no camera, so choosing it says so instead of opening one; PhotosPicker and a
/// real camera are tried by hand on a phone (docs/testing.md).
final class CookedPhotosUITests: RecipeUITestCase {

    private var iMadeThis: XCUIElement { app.buttons["cooked.iMadeThis"] }

    private func scrollToYourCooks() {
        for _ in 0..<6 where !iMadeThis.isHittable {
            app.scrollViews.firstMatch.swipeUp()
        }
    }

    func testWithTheFlagOffThereIsNoGallery() {
        launch()
        openRecipe("Miso Soup")
        app.scrollViews.firstMatch.swipeUp()
        assertAbsent(text("Your cooks"), "Your cooks with the flag off")
    }

    func testIMadeThisOffersCameraAndLibraryAndSaysWhenThereIsNoCamera() {
        launch(flags: ["cookedPhotos"])
        openRecipe("Miso Soup")
        scrollToYourCooks()
        require(text("Your cooks"), "the Your cooks heading")
        require(iMadeThis, "I made this").tap()

        require(app.buttons["Choose from library"], "the library choice")
        require(app.buttons["Take a photo"], "the camera choice").tap()
        let alert = app.alerts["No camera app is available."]
        require(alert, "the no-camera alert")
        alert.buttons.firstMatch.tap()
        requireGone(alert, "the no-camera alert")
    }
}
