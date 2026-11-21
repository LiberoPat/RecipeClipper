import XCTest

/// Pro (#413) end to end: a locked Week extra is still there, with its quiet badge, and opens the Pro
/// screen on the Week's own stack; Settings' Pro row opens the same screen. The UI-test container has
/// no store, so Pro is never bought here (the purchase itself is `ProTests`' and the StoreKit tests').
final class ProUITests: RecipeUITestCase {

    private func requireProScreen() {
        require(app.staticTexts["Pith Pro"], "the Pro screen's title")
        require(app.staticTexts["One-time purchase, never a subscription."], "one-time, never a subscription")
        require(app.buttons["pro.unlock"], "the unlock button")
        require(app.buttons["pro.restore"], "Restore purchases")
        let free = app.staticTexts["pro.freeLine"]
        for _ in 0 ..< 4 where !free.isHittable { app.swipeUp() }
        require(free, "Free. No ads. No account. No subscription.")
        XCTAssertEqual(free.label, "Free. No ads. No account. No subscription.")
    }

    func testTodaysSkipIsLockedWithTheBadgeAndOpensTheProScreenOnTheWeek() {
        launch(.weekPlan, flags: ["skipTonight", "proUnlock"])
        require(app.tabBars.firstMatch.buttons["Plan"], "the Plan tab").tap()
        require(app.staticTexts["weekRange"], "the Week")

        let skip = require(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'skip-'")).firstMatch, "today's Skip")
        XCTAssertTrue(skip.label.contains("Pro"), "the badge is part of the button: \(skip.label)")
        skip.tap()
        assertAbsent(app.staticTexts["Skip Miso Soup"], "the Skip sheet")
        requireProScreen()

        // Back returns to the week, with nothing moved.
        back()
        require(app.staticTexts["weekRange"], "the Week again")
    }

    func testSettingsProRowOpensTheProScreen() {
        launch(flags: ["proUnlock"])
        openSettings()
        let row = settingsRow("pro")
        for _ in 0 ..< 4 where !row.isHittable { app.swipeUp() }
        require(row, "the Pro row").tap()
        requireProScreen()
    }
}
