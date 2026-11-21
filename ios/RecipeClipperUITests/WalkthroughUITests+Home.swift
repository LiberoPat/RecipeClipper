import XCTest

// Walkthroughs 38 and 39 (#309): a timer counting down outside the app, and the home-screen
// widgets. Both leave the app for Springboard, which XCUITest drives as another app.

extension WalkthroughUITests {

    var springboard: XCUIApplication { XCUIApplication(bundleIdentifier: "com.apple.springboard") }

    /// The system's one-time question (Live Activities, notifications), if it is up.
    func allowIfAsked() {
        let allow = springboard.alerts.buttons["Allow"]
        if allow.waitForExistence(timeout: 4) { allow.tap(); pause() }
    }

    /// Locks the phone and wakes it to the lock screen, where a Live Activity shows.
    func showLockScreen() {
        XCUIDevice.shared.perform(NSSelectorFromString("pressLockButton"))
        pause(1.5)
        XCUIDevice.shared.press(.home)
        pause(2.5)
    }

    /// The Live Activity on the lock screen: Springboard's element naming the recipe.
    var liveActivity: XCUIElement {
        springboard.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@ OR label CONTAINS %@", "Chicken Adobo", "Timer")).firstMatch
    }

    /// A cook-mode timer, then the countdown on the lock screen (a Live Activity, #309); a tap on
    /// it brings the cook back with the timer still running, and again once the last step is done,
    /// when the reading view shows the timer under its step.
    func test38_timersOutsideTheApp() {
        start(flags: ["liveTimers"], extraArguments: ["-uiTestLiveTimers"])
        open("Chicken Adobo")
        require(app.buttons["Start cooking"]).tap()
        pause(2)
        require(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "⏱ Start")).firstMatch, "the step's timer").tap()
        allowIfAsked() // notifications
        allowIfAsked() // Live Activities
        pause(2.5)
        showLockScreen()
        require(liveActivity, "the Live Activity", within: 15)
        pause(4)
        liveActivity.tap()
        pause()
        allowIfAsked()
        require(app.buttons["Done — next step"], "cook mode, resumed")
        pause(3)
        for _ in 0..<3 {
            require(app.buttons["Done — next step"]).tap()
            pause(1.2)
        }
        require(app.buttons["Done — finish"]).tap()
        pause(3)
        showLockScreen()
        require(liveActivity, "the Live Activity, still counting", within: 15)
        pause(3)
        liveActivity.tap()
        require(bookmark, "the reading view")
        pause(1.5)
        scrollTo(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "⏱")).firstMatch, "the running timer under its step")
        pause(4)
    }

    /// A tooltip's Got it, when one is up (the real app's first run).
    func gotItIfShown() {
        let bubble = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'tooltip.'")).firstMatch
        if bubble.waitForExistence(timeout: 3) { bubble.tap(); pause(0.8) }
    }

    /// The home-screen widgets (#309), Tonight and Groceries, placed through the widget gallery.
    /// The widget extension reads the app's own database, so this clip runs the real app (a
    /// fresh install: the tour's sample recipe is tonight's dinner) rather than a seeded one.
    func test39_widgets() {
        let app = XCUIApplication()
        app.launch()
        self.app = app
        require(homePasteLink, "Home")
        mark("START")
        gotItIfShown()
        pause()
        tab("Plan")
        gotItIfShown()
        planToday("Tomato and White Bean Soup")
        tab("Groceries")
        gotItIfShown()
        let field = require(app.textFields["Add an item"], "Add an item")
        for item in ["2 lemons", "1 cup plain yogurt", "a bunch of basil"] {
            // A tooltip can take the focus just as the field is tapped: tap until the keyboard is up.
            for _ in 0..<3 where !app.keyboards.firstMatch.exists { field.tap(); pause(1); gotItIfShown() }
            field.typeText("\(item)\n")
            pause(0.8)
        }
        pause(2)
        XCUIDevice.shared.press(.home)
        pause(2)
        for name in ["Tonight", "Groceries"] { addWidget(name) }
        pause(5)
    }

    /// `require` on Springboard: its tree, not the app's, is printed on a miss.
    @discardableResult
    func requireHome(_ element: XCUIElement, _ what: String, file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        if !element.waitForExistence(timeout: timeout) {
            print(springboard.debugDescription)
            XCTFail("Not found: \(what)", file: file, line: line)
        }
        return element
    }

    /// Places the app's widget called `name` from the home screen's gallery (iOS 18+: Edit →
    /// Add Widget, the app searched for, then its widgets paged through to the one wanted).
    func addWidget(_ name: String) {
        let home = springboard
        home.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.8)).press(forDuration: 2)
        pause(1.5)
        let edit = home.buttons["Edit"].firstMatch
        if edit.waitForExistence(timeout: 3) { edit.tap(); pause() }
        requireHome(home.buttons["Add Widget"].firstMatch, "Add Widget").tap()
        pause(2)
        let search = home.searchFields.firstMatch
        requireHome(search, "the gallery's search").tap()
        search.typeText("Pith")
        pause(1.5)
        requireHome(home.cells.matching(NSPredicate(format: "label CONTAINS %@", "Pith")).firstMatch, "the app in the gallery").tap()
        pause(2)
        let title = home.staticTexts[name].firstMatch
        for _ in 0..<3 where !title.exists { home.swipeLeft(); pause(1) }
        requireHome(title, "the \(name) widget")
        pause(1.5)
        requireHome(home.buttons[" Add Widget"].exists ? home.buttons[" Add Widget"] : home.buttons["Add Widget"].firstMatch, "Add Widget").tap()
        pause(2)
        let done = home.buttons["Done"].firstMatch
        if done.waitForExistence(timeout: 3) { done.tap() }
        pause(2)
    }
}
