import XCTest

/// "I made this" (#116; Android's RecipeCookedPhotosScreenTest): "Your cooks" sits at the foot
/// of the reading view, and offers the camera or the library. The iOS 27
/// simulator opens the real UIImagePickerController, but its virtual camera shows a grey
/// preview and never captures, so the test only checks that the camera (or, on a simulator
/// without one, the no-camera alert) opens and closes without adding a photo. The library is
/// tried end to end with one of the simulator's own sample photos, and so is "Mark as cooked"
/// (#173). The date and Delete with Undo are covered by CookedPhotosViewModelTests and tried by
/// hand (docs/testing.md). The note is typed on the software keyboard, which a simulator shows
/// unless a hardware keyboard is connected (Simulator's I/O menu).
final class CookedPhotosUITests: RecipeUITestCase {

    private var iMadeThis: XCUIElement { app.buttons["cooked.iMadeThis"] }
    private var thumbnail: XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Your photo, ")).firstMatch
    }

    private func scrollToTheEnd() {
        for _ in 0..<3 { app.scrollViews.firstMatch.swipeUp() }
    }

    func testIMadeThisOpensTheCameraAndClosingItAddsNothing() {
        launch()
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
        launch()
        openRecipe("Miso Soup")
        scrollToTheEnd()
        require(iMadeThis, "I made this").tap()
        require(app.buttons["Mark as cooked"], "the Mark as cooked choice").tap()

        require(text("Cooked"), "Cooked in place of a picture")
        assertAbsent(app.buttons["Share photo"], "Share for a cooking with no photo")
        let note = require(app.descendants(matching: .any).matching(identifier: "cooked.note").firstMatch, "the note field")
        note.tap()
        note.typeText("Doubled the garlic")
        // The note takes a return as a new line, so the keyboard's Done puts it away (#180).
        require(app.buttons["cooked.noteDone"], "Done above the keyboard").tap()
        requireGone(app.keyboards.firstMatch, "the keyboard")
        require(app.buttons["Close"], "Close").tap()

        let entry = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Cooked, no photo, ")).firstMatch
        require(entry, "the dated entry in Your cooks")
        assertAbsent(thumbnail, "a photo")
    }

    /// A photo from the library opens full screen for its note (#180): only once the picker has
    /// gone, so it keeps its safe area. With the keyboard up for the note, the note shows above
    /// it and × stays in reach, below the status bar, and closes the viewer.
    func testAPhotoFromTheLibraryKeepsItsNoteAndCloseInReach() {
        launch()
        openRecipe("Miso Soup")
        scrollToTheEnd()
        require(iMadeThis, "I made this").tap()
        require(app.buttons["Choose from library"], "the library choice").tap()
        // The system's picker, out of process: any of the simulator's sample photos will do.
        require(app.images.matching(identifier: "PXGGridLayout-Info").firstMatch, "a photo in the picker", within: 20).tap()
        require(app.navigationBars["Photos"].buttons["Done"], "the picker's Done").tap()

        let note = require(app.descendants(matching: .any).matching(identifier: "cooked.note").firstMatch, "the note field")
        let close = require(app.buttons["Close"], "Close")
        XCTAssertTrue(close.isHittable, "× below the status bar")
        note.tap()
        require(app.keyboards.firstMatch, "the software keyboard")
        note.typeText("Less salt next time")
        XCTAssertTrue(note.isHittable, "the note above the keyboard")
        XCTAssertTrue(close.isHittable, "× with the keyboard up")
        close.tap()
        requireGone(note, "the viewer")
        require(thumbnail, "the photo in Your cooks")
    }
}
