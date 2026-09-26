import XCTest

/// The walkthrough videos of the new features (#106): scripted, paced for a viewer, and
/// recorded by `scripts/record-walkthroughs-ios.sh` around each test (docs/testing.md,
/// "Walkthrough videos"). Skipped unless `RC_WALKTHROUGH=1` reaches the runner
/// (`TEST_RUNNER_RC_WALKTHROUGH=1 xcodebuild …`), so CI never runs them. They check little:
/// the feature suites do that. Each prints WALKTHROUGH-START/END with the time, which the script
/// trims the recording to.
final class WalkthroughUITests: RecipeUITestCase {

    override func setUpWithError() throws {
        try super.setUpWithError()
        try XCTSkipUnless(ProcessInfo.processInfo.environment["RC_WALKTHROUGH"] == "1", "walkthroughs only on demand")
    }

    override func tearDown() {
        mark("END")
        super.tearDown()
    }

    // MARK: - Pacing and helpers

    func mark(_ what: String) {
        print(String(format: "WALKTHROUGH-%@ %.3f", what, Date().timeIntervalSince1970))
    }

    /// A beat for the viewer to take in the screen.
    func pause(_ seconds: Double = 1.5) {
        Thread.sleep(forTimeInterval: seconds)
    }

    func start(flags: [String]) {
        launch(.walkthrough, flags: flags)
        mark("START")
        pause()
    }

    var tabBar: XCUIElement { app.tabBars.firstMatch }

    func tab(_ label: String) {
        require(tabBar.buttons[label], "the \(label) tab").tap()
        pause()
    }

    /// Opens a recipe from Home: Continue cooking (a plain title) or a Recently viewed row.
    func open(_ title: String) {
        let continueCooking = app.staticTexts[title].firstMatch
        (row(title).exists ? row(title) : require(continueCooking, title)).tap()
        require(bookmark, "the recipe screen for \(title)")
        pause()
    }

    func recipeMenu(_ item: String) {
        require(app.buttons["More options"], "the menu").tap()
        pause(0.8)
        require(app.buttons[item], item).tap()
        pause()
    }

    func type(_ text: String, into field: XCUIElement) {
        require(field).tap()
        field.typeText(text)
        pause(0.8)
    }

    func line(_ text: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    /// Flips a SwiftUI Toggle at its trailing edge, scrolling Settings until it can be tapped.
    func flipSwitch(_ prefix: String) {
        let toggle = app.switches.matching(NSPredicate(format: "label BEGINSWITH %@", prefix)).firstMatch
        for _ in 0..<4 where !(toggle.exists && toggle.isHittable) { app.swipeUp(); pause(0.6) }
        pause()
        require(toggle, prefix).coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        requireState(toggle, "value == '1'", "\(prefix) on")
        pause()
    }

    var today: Int64 {
        let now = Date()
        let offset = TimeInterval(TimeZone.current.secondsFromGMT(for: now))
        return Int64(((now.timeIntervalSince1970 + offset) / 86_400).rounded(.down))
    }

    /// Week's "+ Add" on today, then a recipe from the sheet's history.
    func planToday(_ title: String) {
        let add = app.buttons["addToDay-\(today)"]
        for _ in 0..<5 where !add.isHittable { app.swipeUp() }
        require(add, "+ Add on today").tap()
        pause()
        require(app.buttons[title], "\(title) in the sheet").tap()
        pause()
    }
}
