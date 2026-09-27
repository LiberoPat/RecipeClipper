import XCTest

/// End to end through the real share sheet: Safari opens a page, shares it to Recipe Clipper,
/// and the extension imports it into the App Group database the app then shows.
///
/// Skipped unless `RC_SHARE_E2E_BASE` names an HTTPS server the simulator trusts, serving
/// `small.html` (a page with a JSON-LD recipe titled "E2E Guacamole Small") and answering 403
/// at `/blocked`. Set it for xcodebuild as `TEST_RUNNER_RC_SHARE_E2E_BASE=https://localhost:8443`.
/// The recipe for the server and certificate is in docs/testing.md. CI never sets it, so
/// these never touch the network there. They use the app's real (App Group) database, so run
/// them on a simulator of your own.
final class ShareExtensionUITests: XCTestCase {
    private let timeout: TimeInterval = 30
    private var base: String!
    private let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")

    override func setUpWithError() throws {
        continueAfterFailure = false
        guard let base = ProcessInfo.processInfo.environment["RC_SHARE_E2E_BASE"], !base.isEmpty else {
            throw XCTSkip("RC_SHARE_E2E_BASE not set: the end-to-end share tests need a local HTTPS server")
        }
        self.base = base
    }

    func testSharingFromSafariSavesTheRecipeAndTheAppShowsIt() throws {
        shareFromSafari("\(base!)/small.html")

        require(safari.staticTexts["E2E Guacamole Small"], in: safari, "the saved card with the title")
        require(safari.staticTexts["Open Recipe Clipper to cook."], in: safari, "the saved card")
        // The card dismisses itself.
        XCTAssertTrue(safari.staticTexts["Open Recipe Clipper to cook."].waitForNonExistence(timeout: timeout))

        let app = XCUIApplication()
        app.launch()
        require(app.staticTexts["E2E Guacamole Small"], in: app, "the shared recipe on Home")
    }

    func testABlockedPageShowsTheCauseAndTryAgain() throws {
        shareFromSafari("\(base!)/blocked")

        let blocked = safari.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "HTTP 403")).firstMatch
        require(blocked, in: safari, "the blocked error")
        require(safari.buttons["Try again"], in: safari, "Try again")
        safari.buttons["Close"].tap()
    }

    // MARK: - Driving Safari

    private func shareFromSafari(_ url: String) {
        safari.launch()
        let address = safari.textFields.matching(NSPredicate(format: "identifier == 'TabBarItemTitle' OR identifier == 'URL'")).firstMatch
        if address.waitForExistence(timeout: 5) {
            address.tap()
        } else {
            safari.buttons.matching(NSPredicate(format: "identifier == 'TabBarItemTitle' OR label CONTAINS[c] 'Address'")).firstMatch.tap()
        }
        let field = safari.textFields.firstMatch
        require(field, in: safari, "Safari's address field")
        field.typeText(url + "\n")
        sleep(2) // let the page settle before sharing

        tapShare()
        let target = safari.descendants(matching: .any).matching(NSPredicate(format: "label == 'Recipe Clipper'")).firstMatch
        if !target.waitForExistence(timeout: 10) {
            // Not in the first row of apps: More, then pick it from the list.
            safari.buttons.matching(NSPredicate(format: "label == 'More'")).firstMatch.tap()
        }
        require(target, in: safari, "Recipe Clipper in the share sheet")
        target.tap()
    }

    private func tapShare() {
        let share = safari.buttons.matching(NSPredicate(format: "identifier == 'ShareButton' OR label == 'Share'")).firstMatch
        if share.waitForExistence(timeout: 5) {
            share.tap()
            return
        }
        // Newer Safari tucks Share into the page menu.
        let menu = safari.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'More' OR label CONTAINS[c] 'Page Menu' OR identifier == 'PageFormatMenuButton'")).firstMatch
        require(menu, in: safari, "Safari's share or page menu button")
        menu.tap()
        let item = safari.descendants(matching: .any).matching(NSPredicate(format: "label == 'Share'")).firstMatch
        require(item, in: safari, "Share in the page menu")
        item.tap()
    }

    private func require(_ element: XCUIElement, in app: XCUIApplication, _ what: String,
                         file: StaticString = #filePath, line: UInt = #line) {
        if !element.waitForExistence(timeout: timeout) {
            XCTFail("\(what) not found. What is there:\n\(app.debugDescription)", file: file, line: line)
        }
    }
}
