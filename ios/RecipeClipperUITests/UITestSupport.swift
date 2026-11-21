import XCTest

/// Shared plumbing for the end-to-end suites. The app is launched with `-uiTestSeed <scenario>`
/// (see RecipeClipper/App/UITestSeeding.swift): an in-memory database seeded per scenario, a
/// stub source so imports never touch the network, and a wiped preferences suite.
///
/// When a query can't find something, print `app.debugDescription` and look at what IS there
/// before changing production code (docs/testing.md, "Compose UI tests"). `require` does that for you.
class RecipeUITestCase: XCTestCase {
    var app: XCUIApplication!
    /// Library first (#375): the launch turned the flag on, so the Recipes tab opens the library, not Home.
    private(set) var libraryFirst = false

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
        /// "Sponge Cake" alone: a long step the stub model shortens (Chef mode, #100), then "Serve."
        case chef
        /// Twenty realistic recipes, each in a list, for the walkthrough videos (#106).
        case walkthrough
        /// The same, with a stocked pantry and the Adobo's lines on the grocery list.
        case walkthroughPantry
        /// The same, with the soy sauce (in the Adobo and the Beef Stir-Fry) expiring tomorrow (#305).
        case walkthroughUseItUp
        /// The twenty, and thirty typed ones after them: fifty, the free tier's limit (#107, #345).
        case walkthroughFull
        /// The standard recipes, with "chicken thighs" (Chicken Adobo's, saved) expiring
        /// tomorrow and "flour" (Banana Bread's, in no list) today: Use it up (#305).
        case useItUp
        /// The standard recipes, with Miso Soup planned tonight, Banana Bread tomorrow and Spaghetti
        /// Carbonara the day after, all Dinner: Skip tonight (#398).
        case weekPlan
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
        // Library first (#375) needs one cookbook (its views strip carries the Lists card), so asking for
        // one is asking for both.
        libraryFirst = flags.contains("libraryFirst")
        let turnedOn = libraryFirst && !flags.contains("oneCookbook") ? flags + ["oneCookbook"] : flags
        if !turnedOn.isEmpty { app.launchArguments += ["-uiTestFlags", turnedOn.joined(separator: ",")] }
        app.launchArguments += extraArguments
        app.launch()
        self.app = app
        require(atRoot, libraryFirst ? "the library to appear" : "Home to appear")
        return app
    }

    /// Home's "Paste a link" tile (#286): what tells Home is showing.
    var homePasteLink: XCUIElement { app.buttons["home.pasteLink"] }

    /// The library's + (#375): what tells the library is showing as the Recipes tab's first screen.
    var libraryAdd: XCUIElement { app.buttons["library.add"] }

    /// The Recipes tab's first screen: Home, or with library first the library.
    var atRoot: XCUIElement { libraryFirst ? libraryAdd : homePasteLink }

    /// The Recipes tab in the bar: choosing it again pops to the tab's first screen.
    var recipesTab: XCUIElement { app.tabBars.firstMatch.buttons["Recipes"] }

    /// Opens `link` from Home as the cook would (#286): the Paste a link tile, the link typed
    /// into its sheet, then Go. `beforeGo` runs between the two (a walkthrough's pause).
    func pasteLink(_ link: String, beforeGo: () -> Void = {}) {
        if libraryFirst {
            // The + 's sheet (#375): its first row.
            require(libraryAdd, "the library's +").tap()
            require(app.buttons["add.pasteLink"], "Paste a link in the sheet").tap()
        } else {
            require(homePasteLink, "Home").tap()
        }
        let field = require(app.descendants(matching: .any)["pasteField"], "the Paste a link sheet's field")
        field.tap()
        field.typeText(link)
        beforeGo()
        require(app.buttons["pasteGo"], "Go").tap()
    }

    // MARK: - Queries

    /// A recipe row. Its accessibility label is "Title, <details>", so match the title exactly
    /// up to the first comma ("Recipe 1" must not match "Recipe 10").
    func row(_ title: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label == %@ OR label BEGINSWITH %@", title, "\(title),")).firstMatch
    }

    /// A menu option. One with a second line under it (#260) reads "Title, line", so it's
    /// matched like a row.
    func menuItem(_ title: String) -> XCUIElement { row(title) }

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
        if libraryFirst {
            // The library is the tab's first screen (#375): the tab itself, when something is over it.
            if !app.textFields["Search titles and ingredients"].exists { recipesTab.tap() }
        } else {
            require(app.buttons["home.nav.recipes"]).tap()
        }
        require(app.textFields["Search titles and ingredients"], "Recipes")
    }

    func openLists() {
        // With library first the Lists card leads the views strip (#371).
        require(libraryFirst ? app.buttons["recipes.lists"] : app.buttons["home.nav.lists"]).tap()
        require(app.buttons["lists.newList"], "the Lists screen")
    }

    /// Settings' index (#286): a row per section, each a button named by its route.
    func openSettings() {
        require(app.buttons["Settings"]).tap()
        require(settingsRow("oven-temperature"), "Settings")
    }

    /// Settings' index row for a section: "units", "oven-temperature", "steps", "pantry",
    /// "your-recipes", "help"…
    func settingsRow(_ section: String) -> XCUIElement { app.buttons["settings.\(section)"] }

    /// Opens Settings, then a section's own page (#286); wait for something on it.
    func openSettings(_ section: String) {
        openSettings()
        settingsRow(section).tap()
    }

    /// Opens a recipe the way a cook would and waits for its screen. From Home that is Continue
    /// cooking when the recipe is the most recent (the only recipe Home lists, #371), else the
    /// Recipes screen and the recipe's row; from Recipes it is the row. Every test that needs
    /// "a recipe open" goes through this, so a change to Home's shape is made here once.
    func openRecipe(_ title: String) {
        let onRecipes = app.textFields["Search titles and ingredients"]
        if !onRecipes.exists && !row(title).exists && atRoot.exists && !libraryFirst {
            openRecipes()
        }
        require(row(title), "the row for \(title)").tap()
        require(bookmark, "the recipe screen for \(title)")
    }

    func back() {
        app.navigationBars.buttons.element(boundBy: 0).tap()
    }

    /// Backs out to Home, however deep: a recipe opened through Recipes (`openRecipe`) is one
    /// screen further from Home than Continue cooking's. With library first (#375) that is the
    /// library, the Recipes tab's first screen.
    func backToHome() {
        for _ in 0..<4 where !atRoot.exists { back() }
        require(atRoot, libraryFirst ? "the library" : "Home")
    }

    // MARK: - Waiting

    /// Waits for `element`, or fails with the whole tree printed so the miss can be diagnosed.
    /// `within` overrides `timeout` (web content, which is slow to reach the tree, needs longer).
    @discardableResult
    func require(
        _ element: XCUIElement, _ what: String = "", within: TimeInterval? = nil,
        file: StaticString = #filePath, line: UInt = #line
    ) -> XCUIElement {
        if !element.waitForExistence(timeout: within ?? timeout) {
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
