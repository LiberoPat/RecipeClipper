import XCTest

/// Library first (#375), end to end: the Recipes tab opens the library with the compact card above
/// search, the + sheet of four ways in, the Settings gear beside the title, the first-run tip at the
/// +, and the empty library's tiles. The twin of Android's `LibraryFirstScreenTest` and
/// `AppShellTest`; the suites that go through Home run with the flag off.
final class LibraryFirstUITests: RecipeUITestCase {

    private let on = ["libraryFirst"]

    private var search: XCUIElement { app.textFields["Search titles and ingredients"] }
    private var tabBar: XCUIElement { app.tabBars.firstMatch }

    private func planToday(_ spec: String) -> [String] { ["-uiTestPlanToday", spec] }

    private func share(_ link: String) {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        let encoded = link.addingPercentEncoding(withAllowedCharacters: allowed)!
        app.open(URL(string: "recipeclipper://import?url=\(encoded)")!)
    }

    // MARK: The tab opens the library

    func testTheRecipesTabOpensTheLibraryAndHomeIsGone() {
        launch(.standard, flags: on)

        require(search, "search, on the first screen")
        require(row("Chicken Adobo"))
        require(row("Banana Bread"), "the whole library, not only Continue cooking's recipe")
        XCTAssertTrue(recipesTab.isSelected)
        assertAbsent(homePasteLink, "Home")
        assertAbsent(app.buttons["home.nav.recipes"], "Home's Recipes row")
        require(text("Recipes"))
        // The first screen of its tab: nothing to go back to.
        XCTAssertFalse(app.navigationBars.buttons.firstMatch.exists)
    }

    func testTheGearBesideTheTitleOpensSettings() {
        launch(.standard, flags: on)

        let gear = require(app.buttons["library.settings"], "the gear")
        XCTAssertLessThan(gear.frame.minX, libraryAdd.frame.minX, "the gear is left of the +")
        XCTAssertLessThan(abs(gear.frame.midY - libraryAdd.frame.midY), 12, "beside each other")
        openSettings()

        back()
        require(libraryAdd, "the library again")
    }

    func testChoosingRecipesAgainPopsToTheLibrary() {
        launch(.standard, flags: on)
        openLists()

        recipesTab.tap()

        require(libraryAdd, "the library")
        assertAbsent(app.buttons["lists.newList"], "Lists")
    }

    func testAShareFromAnotherTabLandsInRecipesOverTheLibrary() {
        launch(.empty, flags: on)
        tabBar.buttons["Pantry"].tap()
        require(app.textFields["Add to the pantry"], "the Pantry tab")

        share("https://example.com/no-recipe")

        require(textContaining("Couldn't find recipe data"), "the import, in Recipes")
        requireGone(tabBar, "the tab bar on the import")
        back()
        require(libraryAdd, "the library, under the import")
        XCTAssertTrue(recipesTab.isSelected)
    }

    // MARK: The + sheet

    func testThePlusOpensASheetOfFourWaysEachWithItsLine() {
        launch(.standard, flags: on + ["photoText", "askAi"])

        libraryAdd.tap()

        require(app.buttons["add.pasteLink"], "Paste a link")
        require(app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Scan a recipe'")).firstMatch, "Scan a recipe")
        require(app.buttons["add.typeRecipe"], "Type a recipe")
        require(app.buttons["add.askAi"], "Ask an AI")
        for hint in [
            "A link or a recipe's text", "A card or a cookbook page", "Write it in yourself", "A prompt for any chat app",
            "Or share a link to Pith from any app.",
        ] {
            require(textContaining(hint), hint)
        }
    }

    func testTheFlagsLeaveOutScanAndAskAnAi() {
        launch(.standard, flags: on)

        libraryAdd.tap()

        require(app.buttons["add.pasteLink"])
        require(app.buttons["add.typeRecipe"])
        assertAbsent(app.buttons["add.askAi"])
        assertAbsent(app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Scan a recipe'")).firstMatch)
    }

    func testTypeARecipeFromTheSheetOpensTheEditor() {
        launch(.standard, flags: on)

        libraryAdd.tap()
        require(app.buttons["add.typeRecipe"]).tap()

        require(app.textFields["Name"], "the editor")
    }

    func testPasteALinkFromTheSheetImportsItAndBackReturnsToTheLibrary() {
        launch(.standard, flags: on)

        pasteLink("example.com/chicken")

        require(bookmark, "the recipe screen")
        require(text("Stub Chicken Soup"))
        back()
        require(libraryAdd, "the library")
        require(row("Stub Chicken Soup"), "at the top of Recently viewed")
    }

    func testAskAnAiFromTheSheetOpensItsOwnSheet() {
        launch(.standard, flags: on + ["askAi"])

        libraryAdd.tap()
        require(app.buttons["add.askAi"]).tap()

        require(textContaining("What do you want to cook?"), "the Ask an AI sheet")
    }

    // MARK: The card

    func testTodaysMealIsACompactCardAboveSearchAndStartCookingOpensItOnTheRecipesStack() {
        launch(.standard, flags: on + ["homeTonight"], extraArguments: planToday("dinner:Chicken Adobo"))

        let start = require(app.buttons["library.card.start"], "Start cooking on the card")
        XCTAssertEqual(start.label, "Start cooking")
        require(text("Today · Dinner"))
        XCTAssertLessThan(start.frame.minY, search.frame.minY, "above search")
        XCTAssertLessThan(libraryAdd.frame.maxY, require(app.buttons["library.card.open"]).frame.minY, "under the title row")

        start.tap()
        require(text("Step 1 of 2"), "cook mode")
        require(app.buttons["✕ Exit"]).tap()
        require(bookmark, "the reading view")
        back()
        require(libraryAdd, "the library: Back from the recipe returns to it, not to the Week")
        XCTAssertTrue(recipesTab.isSelected)
    }

    func testTheCardScrollsAwayWithTheList() {
        launch(.walkthrough, flags: on + ["homeTonight"], extraArguments: planToday("dinner:Chicken Adobo"))
        let start = require(app.buttons["library.card.start"], "the card")

        app.swipeUp()
        app.swipeUp()

        XCTAssertFalse(start.isHittable, "the card has gone with the list")
    }

    func testTheNextMealIsOneLineUnderTheNameAndOpensItsRecipe() {
        launch(
            .standard, flags: on + ["homeTonight"],
            extraArguments: planToday("snack:Banana Bread,dinner:Chicken Adobo,snack:#Fruit")
        )

        require(app.buttons["library.card.start"])
        let next = require(app.buttons["library.card.other"], "the next meal")
        XCTAssertEqual(next.label, "Snack: Banana Bread")
        next.tap()
        require(bookmark, "the recipe, on the Recipes stack")
        require(text("Banana Bread"))
        back()
        require(libraryAdd, "the library")
    }

    func testAPlannedNoteHasNoButton() {
        launch(.standard, flags: on + ["homeTonight"], extraArguments: planToday("dinner:#Eat at the neighbours"))

        let card = require(app.buttons["library.card.open"])
        XCTAssertTrue(card.label.contains("Eat at the neighbours"), card.label)
        assertAbsent(app.buttons["library.card.start"], "a note's button")
    }

    func testNothingPlannedAndNothingMidCookIsNoCardAndSearchSitsUnderTheTitle() {
        launch(.standard, flags: on + ["homeTonight"])

        require(search)
        assertAbsent(app.buttons["library.card.start"])
        assertAbsent(app.buttons["library.card.open"])
        // Never an "Open recipe" card for a recipe nobody is cooking.
        assertAbsent(app.buttons["Open recipe"])
    }

    /// The Today card's close (#448, `todayCardClose`): gone until tomorrow, and still gone on a relaunch
    /// the same day. The twin of Android's `theTodayCardsCloseHidesItUntilTomorrow`.
    func testTheTodayCardsCloseHidesItForTheRestOfTheDay() {
        let flags = on + ["homeTonight", "todayCardClose"]
        launch(.standard, flags: flags, extraArguments: planToday("dinner:Chicken Adobo"))

        require(app.buttons["library.card.start"], "the card")
        let close = require(app.buttons["today.close"], "the card's close")
        XCTAssertEqual(close.label, "Hide today's meal until tomorrow")
        close.tap()

        assertAbsent(app.buttons["library.card.start"], "the card, closed")
        require(search, "search under the title")

        app.terminate()
        launch(.standard, keepPrefs: true, flags: flags, extraArguments: planToday("dinner:Chicken Adobo"))
        require(search)
        assertAbsent(app.buttons["library.card.start"], "still closed today")
    }

    func testWithTheTodayFlagOffOnlyACookingRecipeGetsACard() {
        launch(.standard, flags: on, extraArguments: planToday("dinner:Banana Bread"))

        require(search)
        assertAbsent(app.buttons["library.card.start"])
    }

    /// A running timer keeps the recipe "being cooked": the card is Continue cooking, with the countdown.
    func testMidCookTheCardIsContinueCookingWithTheStepAndTheCountdown() {
        launch(.cook, flags: on + ["homeTonight"])
        openRecipe("Weeknight Chili")
        require(app.buttons["Start cooking"]).tap()
        require(app.buttons["Done — next step"]).tap()
        require(app.buttons["⏱ Start 20 min timer"]).tap()
        require(app.buttons["✕ Exit"]).tap()
        require(bookmark, "the reading view")
        back()

        let resume = require(app.buttons["library.card.start"], "Resume cooking")
        XCTAssertEqual(resume.label, "Resume cooking")
        require(text("Continue cooking"))
        require(textContaining("Step 2 of 4"))
        require(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'left'")).firstMatch, "the countdown")

        resume.tap()
        require(text("Step 2 of 4"), "cook mode, where it was left")
    }

    // MARK: The empty library

    func testAnEmptyLibraryShowsTheWaysInAsTilesAndRestore() {
        launch(.empty, flags: on + ["photoText", "askAi"])

        require(text("Your recipes go here"))
        for id in ["library.empty.pasteLink", "library.empty.typeRecipe", "library.empty.askAi", "library.restore"] {
            require(app.buttons[id], id)
        }
        require(app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Scan a recipe'")).firstMatch, "the scan tile")
        require(text("Or share a link to Pith from any app."))
        assertAbsent(search, "nothing to search yet")
        require(libraryAdd, "the + is still the way in")

        app.buttons["library.empty.typeRecipe"].tap()
        require(app.textFields["Name"], "the editor")
    }

    func testTheEmptyLibrarysPasteTileOpensTheDialogThatImports() {
        launch(.empty, flags: on)

        require(app.buttons["library.empty.pasteLink"]).tap()
        let field = require(app.descendants(matching: .any)["pasteField"], "the Paste a link sheet's field")
        field.tap()
        field.typeText("example.com/chicken")
        require(app.buttons["pasteGo"]).tap()

        require(bookmark, "the recipe screen")
        require(text("Stub Chicken Soup"))
    }

    func testALibraryWithRecipesShowsNoTilesAndNoRestore() {
        launch(.standard, flags: on)

        require(row("Chicken Adobo"))
        assertAbsent(app.buttons["library.empty.pasteLink"])
        assertAbsent(app.buttons["library.restore"])
    }

    // MARK: The tour

    /// The first tip, as a fresh install has it (`-uiTestTooltips`), points at the + with its own words;
    /// the sample recipe (#151) sits under the tiles.
    func testTheFirstTipPointsAtThePlusAndTheSampleSitsUnderTheTiles() {
        launch(.empty, flags: on, extraArguments: ["-uiTestTooltips"])

        let bubble = require(app.buttons["tooltip.home_link"], "the first tooltip")
        XCTAssertTrue(bubble.label.contains("tap + to add one"), bubble.label)
        XCTAssertGreaterThan(bubble.frame.maxX, libraryAdd.frame.minX - 40, "bubble \(bubble.frame), + \(libraryAdd.frame)")
        XCTAssertTrue(
            bubble.frame.maxX <= libraryAdd.frame.minX + 8 || bubble.frame.minY >= libraryAdd.frame.maxY - 8,
            "beside or under the +, never over it"
        )
        bubble.tap()
        requireGone(bubble, "the tooltip, dismissed")

        require(text("Your recipes go here"))
        require(row("Tomato and White Bean Soup"), "the sample under the tiles")
    }
}
