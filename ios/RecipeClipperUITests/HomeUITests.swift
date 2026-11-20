import XCTest

/// Port of Android's HomeScreenTest, end to end: seeded database → repository → ViewModel →
/// SwiftUI. These exist because a view-only bug (a duplicate LazyColumn key) made Android's
/// Home unusable while every ViewModel test stayed green.
final class HomeUITests: RecipeUITestCase {

    /// Continue cooking (#286) is the card at the top, its photo and name a button that opens
    /// the recipe. There is no Recently viewed shelf (#371): the older recipes are Recipes' to show.
    func testTheNewestRecipeIsContinueCookingAndThereIsNoShelf() {
        launch(.standard)

        let newest = require(row("Chicken Adobo"))
        let open = require(app.buttons["home.resume"], "the card's button")
        XCTAssertEqual(open.label, "Open recipe", "nothing is being cooked")
        XCTAssertLessThan(newest.frame.minY, homePasteLink.frame.minY)
        assertAbsent(text("Recently viewed"))
        assertAbsent(row("Spaghetti Carbonara"))
        assertAbsent(app.buttons["home.seeAll"])
    }

    func testOpenRecipeOpensTheLatestRecipe() {
        launch(.standard)

        require(app.buttons["home.resume"]).tap()

        require(bookmark, "the recipe screen")
        require(text("Chicken Adobo"))
    }

    /// The regression guard for the bug that prompted Android's UI tests: a recipe on Home
    /// exactly once, even in three lists and the most recent.
    func testARecipeInSeveralListsIsStillOneRow() {
        launch(.standard)

        require(row("Chicken Adobo"))
        XCTAssertEqual(rowCount("Chicken Adobo"), 1)
    }

    /// Only Continue cooking's recipe is a row on Home (#371).
    func testOnlyTheNewestRecipeIsListed() {
        launch(.many)

        require(row("Recipe 1"))
        assertAbsent(row("Recipe 2"))
    }

    /// Removed deliberately: Lists answers "what have I kept?" better.
    func testThereIsNoSavedSection() {
        launch(.many) // nothing in any list, so no row carries a "Saved" tag either

        require(row("Recipe 1"))
        assertAbsent(text("Saved"))
    }

    func testTappingARecipeOpensIt() {
        launch(.standard)

        openRecipes()
        openRecipe("Banana Bread")

        require(text("Banana Bread"))
        require(text("Ingredients"))
    }

    func testTheSettingsGearOpensSettings() {
        launch(.standard)

        openSettings()
    }

    func testRecipesAndListsRowsOpenTheirScreens() {
        launch(.standard)

        openRecipes()
        back()
        openLists()
    }

    /// #371: with one cookbook Home keeps the Recipes row only; Recipes carries the lists.
    func testWithOneCookbookThereIsNoListsRow() {
        launch(.standard, flags: ["oneCookbook"])

        require(app.buttons["home.nav.recipes"])
        assertAbsent(app.buttons["home.nav.lists"])
    }

    /// Both nav rows and the gear are unconditional — they used to come and go with the database.
    func testTheNavEntriesAreThereWithAnEmptyDatabase() {
        launch(.empty)

        require(app.buttons["home.nav.recipes"])
        require(app.buttons["home.nav.lists"])
        require(app.buttons["Settings"])
    }

    func testTheEmptyHintShowsWhenThereIsNothingToResume() {
        launch(.empty)

        require(text("Recipes you open will show up here."))
        assertAbsent(app.buttons["home.resume"]) // no Continue cooking card
        assertAbsent(text("Recently viewed"))
    }

    func testTheEmptyHintGoesOnceThereIsARecipe() {
        launch(.standard)

        require(row("Chicken Adobo"))
        assertAbsent(text("Recipes you open will show up here."))
    }

    // "Add a recipe" (#286): three equal tiles in place of the link field.

    func testNothingTypedCannotBeOpened() {
        launch(.standard)

        require(homePasteLink).tap()
        let field = require(app.descendants(matching: .any)["pasteField"], "the Paste a link sheet")
        field.tap()

        XCTAssertFalse(app.buttons["pasteGo"].isEnabled)
        XCTAssertFalse(bookmark.exists, "no recipe screen should have opened")
    }

    func testTypeARecipeOpensTheEditor() {
        launch(.standard)

        require(app.buttons["home.typeRecipe"]).tap()

        require(app.textFields["Name"], "the editor")
    }

    func testALinkCanBeSharedFromAnyApp() {
        launch(.empty)

        require(text("Or share a link to Recipe Clipper from any app."))
    }

    /// Go opens the import route; the stub source resolves it offline, and the recipe it
    /// saves becomes "Continue cooking" when you come back.
    func testALinkIsOpenedAndLandsInContinueCooking() {
        launch(.standard)

        pasteLink("example.com/chicken")

        require(bookmark, "the recipe screen")
        require(text("Stub Chicken Soup"))

        back()
        require(row("Stub Chicken Soup"))
        assertAbsent(row("Chicken Adobo")) // the previous newest is Recipes' to show now (#371)
    }

    // Today's card (#402): the earliest meal today not yet cooked, in Continue cooking's place.

    private func planToday(_ spec: String) -> [String] { ["-uiTestPlanToday", spec] }

    func testTodaysMealIsTheCardWhenNothingIsBeingCooked() {
        launch(.standard, flags: ["homeTonight"], extraArguments: planToday("dinner:Chicken Adobo"))

        let start = require(app.buttons["home.today.start"], "Start cooking on the Today card")
        XCTAssertEqual(start.label, "Start cooking")
        require(text("Today"))
        require(app.buttons["home.today.lead"])
        assertAbsent(app.buttons["home.resume"], "Continue cooking's card")
        XCTAssertLessThan(start.frame.minY, homePasteLink.frame.minY)
    }

    /// Opens on the Week's stack, in cook mode: Back from the recipe lands on the Week.
    func testStartCookingOpensCookModeOnTheWeeksStack() {
        launch(.standard, flags: ["homeTonight"], extraArguments: planToday("dinner:Chicken Adobo"))

        require(app.buttons["home.today.start"]).tap()
        require(text("Step 1 of 2"), "cook mode")

        require(app.buttons["✕ Exit"]).tap()
        require(bookmark, "the reading view")
        back()
        require(app.staticTexts["weekRange"], "the Week")
    }

    func testFinishingTheRunTakesTheMealOffTheCard() {
        launch(.standard, flags: ["homeTonight"], extraArguments: planToday("dinner:Chicken Adobo"))

        require(app.buttons["home.today.start"]).tap()
        require(text("Step 1 of 2"), "cook mode")
        require(app.buttons["Done — next step"]).tap()
        require(app.buttons["Done — finish"]).tap()
        // Cook mode ends on the reading view, and the run is over: Home offers nothing to resume.
        require(app.buttons["Start cooking"], "the reading view")
        back()
        app.tabBars.firstMatch.buttons["Recipes"].tap()

        require(homePasteLink, "Home")
        assertAbsent(app.buttons["home.today.start"], "a cooked meal")
        assertAbsent(app.buttons["home.resume"], "a finished run")
    }

    func testSeveralMealsLeadWithTheEarliestAndListTheRestInOrder() {
        // Planned dinner first: the meal types' order decides, not the order planned.
        launch(
            .standard, flags: ["homeTonight"],
            extraArguments: planToday("dinner:Chicken Adobo,breakfast:Banana Bread,lunch:#Leftovers")
        )

        let lead = require(app.buttons["home.today.lead"])
        XCTAssertTrue(lead.label.contains("Banana Bread"), lead.label)
        let others = app.buttons.matching(identifier: "home.today.other")
        XCTAssertEqual(others.count, 2)
        XCTAssertEqual(others.element(boundBy: 0).label, "Lunch: Leftovers")
        XCTAssertFalse(others.element(boundBy: 0).isEnabled, "a note opens nothing")
        XCTAssertEqual(others.element(boundBy: 1).label, "Dinner: Chicken Adobo")

        others.element(boundBy: 1).tap()
        require(bookmark, "the recipe, on the Week's stack")
        require(text("Chicken Adobo"))
        back()
        require(app.staticTexts["weekRange"], "the Week")
    }

    func testAPlannedNoteShowsItsTextWithNoButton() {
        launch(.standard, flags: ["homeTonight"], extraArguments: planToday("dinner:#Eat at the neighbours"))

        let lead = require(app.buttons["home.today.lead"])
        XCTAssertTrue(lead.label.contains("Eat at the neighbours"), lead.label)
        assertAbsent(app.buttons["home.today.start"], "a note's button")
    }

    func testNothingPlannedAndNothingMidCookIsNoCard() {
        launch(.standard, flags: ["homeTonight"])

        assertAbsent(app.buttons["home.today.start"])
        assertAbsent(app.buttons["home.resume"])
        assertAbsent(row("Chicken Adobo"), "the latest recipe as a card")
        require(homePasteLink, "the ways in")
    }

    /// With the flag off Home is as before: Continue cooking is always the most recent recipe.
    func testWithTheFlagOffTheCardIsTheLatestRecipeWhateverIsPlanned() {
        launch(.standard, extraArguments: planToday("dinner:Banana Bread"))

        require(row("Chicken Adobo"))
        XCTAssertEqual(require(app.buttons["home.resume"]).label, "Open recipe")
        assertAbsent(app.buttons["home.today.start"])
    }
}
