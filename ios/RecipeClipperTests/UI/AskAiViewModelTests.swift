import XCTest
@testable import RecipeClipper

/// Android's AskAiViewModelTest (#351, #353): which prompt is sent, and from what.
@MainActor
final class AskAiViewModelTests: XCTestCase {
    private let now: Int64 = 1_800_000_000_000
    private var today: Int64 { PlanDays.today(millis: now) }
    private let words = AskAiPrompt.Words.english

    private struct Fixture {
        let pantry: FakePantryRepository
        let lists = FakeListRepository()
        let recipes = FakeRecipeRepository()
        let preferences = FakeAppPreferences()
        let vm: AskAiViewModel

        @MainActor
        init(pantry: [PantryItem] = [], now: Int64) {
            self.pantry = FakePantryRepository(pantry)
            vm = AskAiViewModel(
                pantry: self.pantry, lists: lists, recipes: recipes, preferences: preferences, clock: TestClock(now: now)
            )
        }
    }

    private func item(_ id: Int64, _ name: String, expires: Int64? = nil, inStock: Bool = true) -> PantryItem {
        PantryItem(
            id: id, name: name, quantity: nil, language: "en", aisle: .other, inStock: inStock, alwaysHave: false,
            purchasedDay: nil, expiresDay: expires
        )
    }

    private func summary(_ id: Int64, _ title: String, cooked: Int64? = nil) -> RecipeSummary {
        RecipeSummary(
            id: id, title: title, imageUrl: nil, totalTime: nil, lastViewedAt: id, isSaved: true, lastCookedDay: cooked
        )
    }

    func testTheCooksOwnWordsNeedWords() async {
        let f = Fixture(now: now)
        await settleMain()
        XCTAssertFalse(f.vm.uiState.canSend)
        XCTAssertNil(f.vm.prompt(words: words))

        f.vm.onRequestChange("  ")
        XCTAssertFalse(f.vm.uiState.canSend)

        f.vm.onRequestChange("a lentil soup")
        XCTAssertTrue(f.vm.uiState.canSend)
        XCTAssertTrue(f.vm.prompt(words: words)!.hasPrefix("I want to cook: a lentil soup"))
    }

    func testThePromptFollowsTheUnitsAndTemperatureChosenInSettings() async {
        let f = Fixture(now: now)
        await settleMain()
        f.vm.onRequestChange("soup")
        XCTAssertTrue(f.vm.prompt(words: words)!.contains("Use the units that suit the recipe."))

        f.preferences.unitSystem = .metric
        f.preferences.temperatureUnit = .celsius
        await settleMain()
        let prompt = f.vm.prompt(words: words)!
        XCTAssertTrue(prompt.contains("metric units"))
        XCTAssertTrue(prompt.contains("°C"))
    }

    func testWhatCanIMakeCarriesThePantryTheItemsToUseUpFirst() async {
        let f = Fixture(
            pantry: [item(1, "rice"), item(2, "spinach", expires: today + 1), item(3, "flour", inStock: false)], now: now
        )
        await settleMain()
        XCTAssertEqual(f.vm.uiState.pantryCount, 2)
        XCTAssertEqual(f.vm.uiState.useUpCount, 1)

        f.vm.onKind(.whatCanIMake)
        f.vm.onRequestChange("quick")
        let prompt = f.vm.prompt(words: words)!
        XCTAssertTrue(prompt.contains("Use these up first, they expire soon: spinach."))
        XCTAssertTrue(prompt.contains("Also in my pantry: rice."))
        XCTAssertTrue(prompt.contains("Also: quick"))
    }

    func testWhatCanIMakeIsOffWhileThePantryIsEmpty() async {
        let f = Fixture(now: now)
        await settleMain()
        XCTAssertFalse(f.vm.uiState.canAskWhatCanIMake)
        f.vm.onKind(.whatCanIMake)
        XCTAssertFalse(f.vm.uiState.canSend)
        XCTAssertNil(f.vm.prompt(words: words))
    }

    func testPlanMyWeekDrawsOnTheFavouritesAndWhatWasCookedLatelyForSevenDays() async {
        let f = Fixture(now: now)
        f.lists.lists.send([
            RecipeList(id: 1, name: "Lunch", isBuiltIn: true, isFavorites: false, recipeCount: 0),
            RecipeList(id: 2, name: "Favorites", isBuiltIn: true, isFavorites: true, recipeCount: 0),
        ])
        f.lists.recipesIn.send([summary(1, "Lasagne"), summary(2, "Pad Thai")])
        f.recipes.history.send([
            summary(1, "Lasagne"), summary(3, "Chili", cooked: today - 1), summary(4, "Stew", cooked: today - 5),
            summary(5, "Never cooked"),
        ])
        await settleMain()
        XCTAssertEqual(f.vm.uiState.favouriteCount, 2)
        XCTAssertEqual(f.vm.uiState.cookedCount, 2)

        f.vm.onKind(.planMyWeek)
        XCTAssertTrue(f.vm.uiState.canSend)
        let prompt = f.vm.prompt(words: words)!
        XCTAssertTrue(prompt.contains("- \(AskAiPrompt.isoDate(today)) ("))
        XCTAssertTrue(prompt.contains("- \(AskAiPrompt.isoDate(today + 6)) ("))
        XCTAssertFalse(prompt.contains(AskAiPrompt.isoDate(today + 7)))
        XCTAssertTrue(prompt.contains("Recipes I like, for ideas: Lasagne, Pad Thai."))
        // The latest cooked first.
        XCTAssertTrue(prompt.contains("so please don't repeat them: Chili, Stew."))
    }

    func testChangeThisRecipeNeedsARequestAndCarriesTheRecipe() async {
        let recipe = Recipe(
            name: "Adobo", image: nil, ingredients: ["2 lb chicken"], instructions: ["Simmer."], prepTime: nil,
            cookTime: nil, totalTime: nil, yield: "4 servings", sourceUrl: "manual:abc"
        )
        let f = Fixture(now: now)
        await settleMain()
        XCTAssertFalse(f.vm.uiState.canSendChange)
        XCTAssertNil(f.vm.changePrompt(recipe: recipe, words: words))

        f.vm.onRequestChange("make it vegetarian")
        let prompt = f.vm.changePrompt(recipe: recipe, words: words)
        XCTAssertNotNil(prompt)
        XCTAssertTrue(prompt!.hasPrefix("Please change this recipe: make it vegetarian"))
        XCTAssertTrue(prompt!.contains("\"isBasedOn\": \"manual:abc\""))
    }

    func testCopiedLastsUntilSomethingChangesAndClosingTheSheetStartsOver() async {
        let f = Fixture(now: now)
        await settleMain()
        f.vm.onRequestChange("soup")
        f.vm.onCopied()
        XCTAssertTrue(f.vm.uiState.copied)

        f.vm.onRequestChange("soup!")
        XCTAssertFalse(f.vm.uiState.copied)

        f.vm.onKind(.planMyWeek)
        f.vm.onReset()
        XCTAssertEqual(f.vm.uiState.request, "")
        XCTAssertEqual(f.vm.uiState.kind, .describe)
    }
}
