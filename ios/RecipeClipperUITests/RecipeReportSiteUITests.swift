import XCTest

/// "Report this site" sits beside "Try again" on the no-recipe error only, and opens the
/// prefilled GitHub issue in the browser. The stub source (UITestSeeding) answers `/no-recipe`
/// with "no recipe data" and `/blocked` with a 403 every time.
final class RecipeReportSiteUITests: RecipeUITestCase {

    private func importLink(_ link: String) {
        let field = app.textFields["Recipe URL"]
        field.tap()
        field.typeText(link)
        app.scrollViews.firstMatch.buttons["Go"].tap()
    }

    func testAPageWithNoRecipeOffersReportThisSiteWhichOpensTheBrowser() {
        launch(.empty)
        importLink("example.com/no-recipe")

        require(app.buttons["Try again"], "Try again")
        let report = app.buttons["Report this site"]
        require(report, "Report this site")

        report.tap()
        let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
        XCTAssertTrue(safari.wait(for: .runningForeground, timeout: timeout), "the browser to open the issue")
        safari.terminate()
    }

    func testABlockedSiteOffersNoReport() {
        launch(.empty)
        importLink("example.com/blocked")

        require(textContaining("didn't let the app in (HTTP 403)"), "the blocked error")
        require(app.buttons["Try again"], "Try again")
        XCTAssertFalse(app.buttons["Report this site"].exists)
    }
}
