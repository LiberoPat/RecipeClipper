import XCTest

/// Settings → Pantry → "Expiry reminders" (#52). UI tests answer the permission question
/// without the system prompt (the UI-test container grants it), so this checks the section's
/// visibility and that the switch turns on and stays on across a visit.
final class ExpiryRemindersUITests: RecipeUITestCase {

    private var reminders: XCUIElement {
        app.switches.matching(NSPredicate(format: "label BEGINSWITH 'Expiry reminders'")).firstMatch
    }

    /// Flips a SwiftUI Toggle at its trailing edge, where the switch is.
    private func flip(_ toggle: XCUIElement) {
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
    }

    /// To the foot of Settings, so the switch sits clear of the tab bar (the flag turns it on).
    private func scrollToReminders() {
        for _ in 0..<3 { app.swipeUp() }
    }

    func testHiddenWithoutTheMealPlanFlag() {
        launch()
        openSettings()
        app.swipeUp()
        XCTAssertFalse(reminders.exists, "no Pantry section while the pantry is hidden")
    }

    func testTurnsOnAndStaysOn() {
        launch(flags: ["mealPlan"])
        openSettings()
        scrollToReminders()
        requireState(require(reminders, "the Expiry reminders switch"), "value == '0'", "off by default")

        flip(reminders)
        requireState(reminders, "value == '1'", "on once notifications are allowed")

        back()
        openSettings()
        scrollToReminders()
        requireState(reminders, "value == '1'", "still on after revisiting")
    }
}
