import XCTest

/// "Scan a recipe" (#226) end to end. The entries: "Scan a recipe" beside "+ New recipe" on Home
/// and in the Recipes + menu, each offering the camera or the library (the simulator has no
/// camera, so the camera says so; the library is the system's picker, which a UI test can't
/// drive reliably). The review: the fixture recipe card's two sides as local pages
/// (`RC_UITEST_SCAN_IMAGES`, as a scan stages them), read by the real Vision reader
/// (`RC_UITEST_PHOTO_VISION`), each page named for VoiceOver, then named and saved as a typed-in
/// recipe with no source credit.
final class ScanUITests: RecipeUITestCase {

    func testHomeAndTheRecipesMenuOfferTheCameraOrTheLibrary() {
        launch(.empty, flags: ["photoText"])

        require(app.buttons["Scan a recipe"], "Home's Scan a recipe").tap()
        require(app.buttons["Choose from library"], "the library choice")
        require(app.buttons["Take a photo"], "the camera choice").tap()
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
        } else {
            closeCamera.tap()
        }

        openRecipes()
        require(app.buttons["recipes.add"], "the + menu").tap()
        require(app.buttons["Scan a recipe"], "the scan submenu").tap()
        require(app.buttons["Take a photo"], "the camera choice")
        require(app.buttons["Choose from library"], "the library choice")
    }

    func testWithTheFlagOffThereIsNoScan() {
        launch(.empty)
        assertAbsent(app.buttons["Scan a recipe"], "Home's Scan a recipe with the flag off")
    }

    func testAScannedCardIsReadOnTheDeviceCheckedAndSavedAsYourOwn() throws {
        let bundle = Bundle(for: ScanUITests.self)
        func page(_ name: String) throws -> String {
            try XCTUnwrap(bundle.url(forResource: name, withExtension: "jpg", subdirectory: "reddit/photos")).absoluteString
        }
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestSeed", Scenario.empty.rawValue, "-uiTestFlags", "photoText"]
        app.launchEnvironment["RC_UITEST_PHOTO_VISION"] = "1"
        app.launchEnvironment["RC_UITEST_SCAN_IMAGES"] = "\(try page("card-front")) \(try page("card-back"))"
        app.launch()
        self.app = app

        require(text("Check the recipe"), "the review")
        require(app.descendants(matching: .any)["Page 1 of 2"], "the first page, named for VoiceOver")
        require(app.descendants(matching: .any)["Page 2 of 2"], "the second page")
        require(textContaining("Read from the photo"), "how the reading went", within: 30)

        // No title is guessed: the cook names it, then saves.
        let name = require(app.textFields["Name"], "the name field")
        name.tap()
        name.typeText("Aunt June's oatmeal cookies")
        require(app.buttons["edit.save"], "Save").tap()

        require(bookmark, "the saved recipe")
        require(text("Aunt June's oatmeal cookies"), "its name")
        assertAbsent(app.buttons["Open original"], "a source link, on a recipe of your own")
    }
}
