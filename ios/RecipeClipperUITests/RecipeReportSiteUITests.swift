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
        // The link is handed to the system, which brings the browser forward. Asserted as the
        // app leaving the foreground: Safari's own first launch on a fresh, busy simulator can
        // take longer than any sensible wait to report itself foreground.
        XCTAssertTrue(app.wait(for: .runningBackground, timeout: 30), "the browser to open the issue")
        XCUIApplication(bundleIdentifier: "com.apple.mobilesafari").terminate()
    }

    func testABlockedSiteOffersNoReport() {
        launch(.empty)
        importLink("example.com/blocked")

        require(textContaining("didn't let the app in (HTTP 403)"), "the blocked error")
        require(app.buttons["Try again"], "Try again")
        XCTAssertFalse(app.buttons["Report this site"].exists)
    }
}
