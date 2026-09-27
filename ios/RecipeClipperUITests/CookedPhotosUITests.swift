import XCTest

/// "I made this" (#116; Android's RecipeCookedPhotosScreenTest): "Your cooks" sits at the foot
/// of the reading view only behind its flag, and offers the camera or the library. The iOS 27
/// simulator opens the real UIImagePickerController, but its virtual camera shows a grey
/// preview and never captures, so the test only checks that the camera (or, on a simulator
/// without one, the no-camera alert) opens and closes without adding a photo. The note, the
/// date and Delete with Undo are covered by CookedPhotosViewModelTests and tried by hand, with
/// PhotosPicker (docs/testing.md). "Mark as cooked" (#173) needs no camera, so it is tried end to end.
final class CookedPhotosUITests: RecipeUITestCase {

    private var iMadeThis: XCUIElement { app.buttons["cooked.iMadeThis"] }
    private var thumbnail: XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Your photo, ")).firstMatch
    }

    private func scrollToTheEnd() {
        for _ in 0..<3 { app.scrollViews.firstMatch.swipeUp() }
    }

    func testWithTheFlagOffThereIsNoGallery() {
        launch()
        openRecipe("Miso Soup")
        scrollToTheEnd()
        assertAbsent(text("Your cooks"), "Your cooks with the flag off")
    }

    func testIMadeThisOpensTheCameraAndClosingItAddsNothing() {
        launch(flags: ["cookedPhotos"])
        openRecipe("Miso Soup")
        scrollToTheEnd()
        require(text("Your cooks"), "the Your cooks heading")
        require(iMadeThis, "I made this").tap()
        require(app.buttons["Choose from library"], "the library choice")
        require(app.buttons["Take a photo"], "the camera choice").tap()

        // The first use asks for camera access (a system alert, outside the app).
        let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.buttons["Allow"]
        if allow.waitForExistence(timeout: 3) { allow.tap() }

        let noCamera = app.alerts["No camera app is available."]
        let closeCamera = app.buttons["DismissButton"]
        let deadline = Date().addingTimeInterval(timeout)
        while !closeCamera.exists && !noCamera.exists && Date() < deadline {
            _ = closeCamera.waitForExistence(timeout: 0.5)
        }
        if noCamera.exists {
            noCamera.buttons.firstMatch.tap()
            requireGone(noCamera, "the no-camera alert")
        } else {
            require(app.buttons["PhotoCapture"], "the camera, or the no-camera alert")
            closeCamera.tap()
            requireGone(closeCamera, "the camera")
        }

        XCTAssertTrue(require(iMadeThis, "back on the recipe").isHittable)
        assertAbsent(thumbnail, "a photo after closing the camera")
    }

    /// "Mark as cooked" (#173): one tap records today's cooking with no photo. It opens like a
    /// new photo, "Cooked" in place of the picture and no Share, for its note; then the row shows
    /// it as a dated entry VoiceOver reads as cooked with no photo.
    func testMarkAsCookedAddsADatedEntryWithoutAPicture() {
        launch(flags: ["cookedPhotos"])
        openRecipe("Miso Soup")
        scrollToTheEnd()
        require(iMadeThis, "I made this").tap()
        require(app.buttons["Mark as cooked"], "the Mark as cooked choice").tap()

        require(text("Cooked"), "Cooked in place of a picture")
        assertAbsent(app.buttons["Share photo"], "Share for a cooking with no photo")
        let note = require(app.descendants(matching: .any).matching(identifier: "cooked.note").firstMatch, "the note field")
        note.tap()
        note.typeText("Doubled the garlic")
        require(app.buttons["Close"], "Close").tap()

        let entry = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Cooked, no photo, ")).firstMatch
        require(entry, "the dated entry in Your cooks")
        assertAbsent(thumbnail, "a photo")
    }
}
