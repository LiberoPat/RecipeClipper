import XCTest

/// Pro (#413) end to end. Pro adds nothing today (owner, 2026-11-23: the Week extras are free, Pro is
/// kept for future AI features), so with `proUnlock` on there is no Pro row in Settings, and Skip, the
/// one extra with a button of its own, carries no badge. The Pro screen itself is `ProTests`' while it
/// has no extra to list; the UI-test container has no store.
final class ProUITests: RecipeUITestCase {

    func testSettingsHasNoProRowWhileProAddsNothing() {
        launch(flags: ["proUnlock"])
        openSettings()
        assertAbsent(settingsRow("pro"), "the Pro row")
        assertAbsent(app.staticTexts["Pith Pro"], "the Pro page's title")
    }

    func testTodaysSkipHasNoBadgeAndOpensItsSheet() {
        launch(.weekPlan, flags: ["skipTonight", "proUnlock"])
        require(app.tabBars.firstMatch.buttons["Plan"], "the Plan tab").tap()
        require(app.staticTexts["weekRange"], "the Week")

        let skip = require(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'skip-'")).firstMatch, "today's Skip")
        XCTAssertFalse(skip.label.contains("Pro"), "no badge on Skip: \(skip.label)")
        skip.tap()
        require(app.staticTexts["Skip Miso Soup"], "the Skip sheet")
        assertAbsent(app.staticTexts["Pith Pro"], "the Pro screen")
        require(app.buttons["skipRemove"], "Remove from the plan")
    }
}
