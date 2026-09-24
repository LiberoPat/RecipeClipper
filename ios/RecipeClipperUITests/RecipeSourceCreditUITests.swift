import XCTest

/// Port of Android's RecipeSourceCreditTest. The reading view credits the site a recipe came
/// from, under the title, with a way back to the original page. The seeded recipes all come
/// from `https://example.com/<slug>`. Tapping "Open original" isn't exercised: it leaves the
/// app for Safari.
final class RecipeSourceCreditUITests: RecipeUITestCase {

    private var domain: XCUIElement { app.staticTexts["recipe.sourceDomain"] }
    private var openOriginal: XCUIElement { app.buttons["recipe.openOriginal"] }

    func testTheDomainShowsUnderTheTitle() {
        launch()
        openRecipe("Miso Soup")

        require(domain, "the source domain")
        XCTAssertEqual(domain.label, "example.com")
        XCTAssertGreaterThan(domain.frame.minY, require(text("Miso Soup")).frame.minY)
    }

    func testOpenOriginalIsOfferedAsAButton() {
        launch()
        openRecipe("Miso Soup")

        require(openOriginal, "Open original")
        XCTAssertEqual(openOriginal.label, "Open original")
        XCTAssertTrue(openOriginal.isHittable)
    }

    func testCookModeLeavesTheCreditOut() {
        launch()
        openRecipe("Miso Soup")
        require(domain, "the source domain")

        require(app.buttons["Start cooking"]).tap()
        require(app.buttons["✕ Exit"], "cook mode")
        assertAbsent(domain, "the source domain in cook mode")
        assertAbsent(openOriginal, "Open original in cook mode")
    }
}
