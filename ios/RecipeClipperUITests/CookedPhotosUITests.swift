import XCTest

/// "I made this" (#116; Android's RecipeCookedPhotosScreenTest): "Your cooks" sits at the foot
/// of the reading view only behind its flag, and offers the camera or the library. The iOS 27
/// simulator has a virtual camera, so the test takes a picture through the real
/// UIImagePickerController, writes a note, and deletes the photo with Undo. On a simulator
/// without a camera, choosing it says so instead. PhotosPicker is tried by hand
/// (docs/testing.md).
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

    func testAPhotoFromTheCameraGetsANoteAndADeleteCanBeUndone() throws {
        launch(flags: ["cookedPhotos"])
        openRecipe("Miso Soup")
        scrollToTheEnd()
        require(text("Your cooks"), "the Your cooks heading")
        require(iMadeThis, "I made this").tap()
        require(app.buttons["Choose from library"], "the library choice")
        require(app.buttons["Take a photo"], "the camera choice").tap()

        let noCamera = app.alerts["No camera app is available."]
        let shutter = app.buttons["PhotoCapture"]
        // The first use asks for camera access (a system alert, outside the app).
        let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.buttons["Allow"]
        if allow.waitForExistence(timeout: 3) { allow.tap() }
        guard shutter.waitForExistence(timeout: 10) else {
            require(noCamera, "the camera, or the no-camera alert")
            noCamera.buttons.firstMatch.tap()
            throw XCTSkip("This simulator has no camera")
        }
        shutter.tap()
        require(app.buttons["Use Photo"], "Use Photo").tap()

        // The new photo opens full screen, for its note.
        let note = require(app.textFields["cooked.note"], "the note field")
        note.tap()
        note.typeText("Less salt")
        require(app.buttons["Close"]).tap()
        require(thumbnail, "the photo in Your cooks").tap()
        XCTAssertEqual(require(app.textFields["cooked.note"]).value as? String, "Less salt")

        require(app.buttons["cooked.delete"], "Delete photo").tap()
        requireGone(thumbnail, "the deleted photo")
        require(app.buttons["Undo"], "Undo").tap()
        require(thumbnail, "the photo back after Undo")
    }
}
