import XCTest

/// Shared plumbing for the end-to-end suites. The app is launched with `-uiTestSeed <scenario>`
/// (see RecipeClipper/App/UITestSeeding.swift): an in-memory database seeded per scenario, a
/// stub source so imports never touch the network, and a wiped preferences suite.
///
/// When a query can't find something, print `app.debugDescription` and look at what IS there
/// before changing production code (docs/testing.md, "Compose UI tests"). `require` does that for you.
class RecipeUITestCase: XCTestCase {
    var app: XCUIApplication!

    /// Generous: the first launch on a cold simulator is slow.
    let timeout: TimeInterval = 10

    enum Scenario: String {
        /// No recipes; only the six seeded lists.
        case empty
        /// "Recipe 1" (newest) … "Recipe 10", in no list.
        case many
        /// Chicken Adobo (Favorites, Dinner, Weeknights), Spaghetti Carbonara (Weeknights,
        /// added after Adobo), Banana Bread, Miso Soup — viewed in that order, newest first.
        case standard
        /// "Weeknight Chili" alone: steps "Brown the beef in a large pot.", "Simmer for 20
        /// minutes.", "Rest off the heat for 3 seconds.", "Serve with rice."; its lines include
        /// "1 lb beef" (amounts inside steps, #101).
        case cook
    }

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    @discardableResult
    /// [flags] are feature-flag keys from shared/flags.json (#87) to turn on, through the app's
    /// flag store (UITestSeeding), as Developer settings would.
    func launch(
        _ scenario: Scenario = .standard, keepPrefs: Bool = false, flags: [String] = [], extraArguments: [String] = []
    ) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestSeed", scenario.rawValue]
        if keepPrefs { app.launchArguments.append("-uiTestKeepPrefs") }
        if !flags.isEmpty { app.launchArguments += ["-uiTestFlags", flags.joined(separator: ",")] }
        app.launchArguments += extraArguments
        app.launch()
        self.app = app
        require(app.textFields["Recipe URL"], "Home to appear")
        return app
    }

    // MARK: - Queries

    /// A recipe row. Its accessibility label is "Title, <details>", so match the title exactly
    /// up to the first comma ("Recipe 1" must not match "Recipe 10").
    func row(_ title: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label == %@ OR label BEGINSWITH %@", title, "\(title),")).firstMatch
    }

    func rowCount(_ title: String) -> Int {
        app.buttons.matching(NSPredicate(format: "label == %@ OR label BEGINSWITH %@", title, "\(title),")).count
    }

    func text(_ label: String) -> XCUIElement {
        app.staticTexts[label].firstMatch
    }

    func textContaining(_ fragment: String) -> XCUIElement {
        app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", fragment)).firstMatch
    }

    var bookmark: XCUIElement { app.buttons["recipe.bookmark"] }

    // MARK: - Navigation

    func openRecipes() {
        require(app.buttons["home.nav.recipes"]).tap()
        require(app.textFields["Search titles and ingredients"], "Recipes")
    }

    func openLists() {
        require(app.buttons["home.nav.lists"]).tap()
        require(app.buttons["+ New list"], "the Lists screen")
    }

    func openSettings() {
        require(app.buttons["Settings"]).tap()
        require(text("Oven temperature"), "Settings")
    }

    /// Opens a recipe from Home and waits for its title.
    func openRecipe(_ title: String) {
        require(row(title)).tap()
        require(bookmark, "the recipe screen for \(title)")
    }

    func back() {
        app.navigationBars.buttons.element(boundBy: 0).tap()
    }

    // MARK: - Waiting

    /// Waits for `element`, or fails with the whole tree printed so the miss can be diagnosed.
    @discardableResult
    func require(_ element: XCUIElement, _ what: String = "", file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        if !element.waitForExistence(timeout: timeout) {
            print(app.debugDescription)
            XCTFail("Not found: \(what.isEmpty ? element.description : what)", file: file, line: line)
        }
        return element
    }

    /// Waits for `element` to go away. `waitForNonExistence` rather than an NSPredicate
    /// expectation, which only re-checks about once a second.
    func requireGone(_ element: XCUIElement, _ what: String = "", file: StaticString = #filePath, line: UInt = #line) {
        if !element.waitForNonExistence(timeout: timeout) {
            print(app.debugDescription)
            XCTFail("Still present: \(what.isEmpty ? element.description : what)", file: file, line: line)
        }
    }

    /// Waits for a predicate over an element's properties (e.g. "isSelected == true").
    func requireState(_ element: XCUIElement, _ format: String, _ what: String = "", file: StaticString = #filePath, line: UInt = #line) {
        require(element, what, file: file, line: line)
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: format), object: element)
        if XCTWaiter.wait(for: [expectation], timeout: timeout) != .completed {
            print(app.debugDescription)
            XCTFail("\(what.isEmpty ? element.description : what): expected \(format)", file: file, line: line)
        }
    }

    /// Asserts something is absent right now, after a short settle, printing the tree if not.
    func assertAbsent(_ element: XCUIElement, _ what: String = "", file: StaticString = #filePath, line: UInt = #line) {
        if element.waitForExistence(timeout: 1) {
            print(app.debugDescription)
            XCTFail("Unexpectedly present: \(what.isEmpty ? element.description : what)", file: file, line: line)
        }
    }
}
