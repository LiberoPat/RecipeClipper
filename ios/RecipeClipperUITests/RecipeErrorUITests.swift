import XCTest

/// Every import error screen offers "Try again" — no-recipe included, since a captive portal's
/// login page parses as a page with no recipe. The stub
/// source (UITestSeeding) answers `/no-recipe` with "no recipe data" and `/blocked` with a
/// 403 every time, so the blocked case also sits through the repository's one real retry pause.
final class RecipeErrorUITests: RecipeUITestCase {

    private func importLink(_ link: String) {
        let field = app.textFields["Recipe URL"]
        field.tap()
        field.typeText(link)
        app.scrollViews.firstMatch.buttons["Go"].tap()
    }

    func testAPageWithNoRecipeStillOffersTryAgain() {
        launch(.empty)
        importLink("example.com/no-recipe")

        require(textContaining("Couldn't find recipe data"), "the no-recipe error")
        require(app.buttons["Try again"], "Try again")
    }

    func testABlockedSiteSaysSoAndOffersTryAgain() {
        launch(.empty)
        importLink("example.com/blocked")

        require(textContaining("didn't let the app in (HTTP 403)"), "the blocked error")
        require(app.buttons["Try again"], "Try again")
    }
}
