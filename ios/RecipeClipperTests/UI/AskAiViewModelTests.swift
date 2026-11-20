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
        let week = FakeMealPlanRepository()
        let choices = MemoryPlanChoices()
        let vm: AskAiViewModel

        @MainActor
        init(pantry: [PantryItem] = [], now: Int64, plus: Bool = false, remembered: [String]? = nil) {
            self.pantry = FakePantryRepository(pantry)
            choices.planMealKeys = remembered
            vm = AskAiViewModel(
                pantry: self.pantry, lists: lists, recipes: recipes, preferences: preferences, clock: TestClock(now: now),
                mealPlan: week, choices: choices, planPlus: { plus }
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

    // MARK: - Plan my week with choices (#392, #367)

    private func library(_ count: Int) -> [RecipeSummary] {
        (1...count).map {
            RecipeSummary(
                id: Int64($0), title: "Dish\($0)", imageUrl: nil, totalTime: nil, lastViewedAt: Int64($0), isSaved: true,
                sourceUrl: "https://example.com/\($0)"
            )
        }
    }

    private func plus(remembered: [String]? = nil, recipes: Int = 0) async -> Fixture {
        let f = Fixture(now: now, plus: true, remembered: remembered)
        if recipes > 0 { f.recipes.history.send(library(recipes)) }
        await settleMain()
        f.vm.onKind(.planMyWeek)
        return f
    }

    func testPlanStartsOnDinnerAndTodayWithNewRecipesAndCarriesTheMealOnEachObject() async {
        let f = await plus()
        let state = f.vm.uiState
        XCTAssertTrue(state.plan)
        XCTAssertEqual(state.chosenMeals, ["dinner"])
        XCTAssertEqual(state.mealTypes.map(\.name), ["Breakfast", "Lunch", "Dinner", "Snack"])
        XCTAssertEqual(state.startDay, today)
        XCTAssertEqual(state.days.count, 14)
        XCTAssertEqual(state.source, .new)
        XCTAssertTrue(state.canSend)
        let prompt = f.vm.prompt(words: words)!
        XCTAssertTrue(prompt.hasPrefix("Plan one recipe for each of these meals (Dinner) on each of these days:"))
        XCTAssertTrue(prompt.contains("\"meal\", holding the meal's name exactly as listed"))
        XCTAssertTrue(prompt.contains("- \(AskAiPrompt.isoDate(today + 6)) ("))
    }

    func testTheMealsAreChosenInTheCooksOrderAndTheLastChoiceIsRememberedByKeyNeverByName() async {
        let f = await plus()
        f.vm.onMeal("breakfast")
        f.vm.onMeal("dinner") // unticks Dinner
        await settleMain()
        XCTAssertEqual(f.vm.uiState.chosenMeals, ["breakfast"])
        XCTAssertEqual(f.choices.planMealKeys, ["breakfast"])
        f.vm.onMeal("snack")
        XCTAssertEqual(f.choices.planMealKeys, ["breakfast", "snack"])
        XCTAssertTrue(f.vm.prompt(words: words)!.hasPrefix("Plan one recipe for each of these meals (Breakfast, Snack) on"))

        // A user's own type is kept by its uid, and a renamed type keeps its tick.
        await f.week.addMealType(name: "Brunch")
        await settleMain()
        let brunch = f.vm.uiState.mealTypes.last!
        XCTAssertEqual(brunch.key, brunch.uid)
        f.vm.onMeal(brunch.key)
        XCTAssertEqual(f.choices.planMealKeys, ["breakfast", "snack", brunch.uid])
        await f.week.renameMealType(id: FakeMealPlanRepository.breakfast, name: "Morning")
        await settleMain()
        XCTAssertTrue(f.vm.prompt(words: words)!.hasPrefix("Plan one recipe for each of these meals (Morning, Snack, Brunch) on"))
    }

    func testTheLastChoiceComesBackWhenTheSheetOpensAgainAndATypeThatIsGoneFallsBackToDinner() async {
        let f = await plus(remembered: ["lunch", "snack"])
        XCTAssertEqual(f.vm.uiState.chosenMeals, ["lunch", "snack"])
        f.vm.onMeal("lunch")
        f.vm.onReset()
        await settleMain()
        XCTAssertEqual(f.vm.uiState.chosenMeals, ["snack"], "what was last chosen")

        let gone = await plus(remembered: ["gone"])
        XCTAssertEqual(gone.vm.uiState.chosenMeals, ["dinner"])
    }

    func testUntickingEveryMealLeavesNothingToSendAndTheSheetAsksForOne() async {
        let f = await plus()
        f.vm.onMeal("dinner")
        XCTAssertTrue(f.vm.uiState.chosenMeals.isEmpty)
        XCTAssertFalse(f.vm.uiState.canSend)
        XCTAssertNil(f.vm.prompt(words: words))
        f.vm.onMeal("lunch")
        XCTAssertTrue(f.vm.uiState.canSend)
    }

    func testTheStartDayIsOneOfTheStripsDaysNeverRememberedAndSetsTheFirstDayListed() async {
        let f = await plus()
        f.vm.onStartDay(today + 3)
        XCTAssertEqual(f.vm.uiState.startDay, today + 3)
        let prompt = f.vm.prompt(words: words)!
        XCTAssertTrue(prompt.contains("- \(AskAiPrompt.isoDate(today + 3)) ("))
        XCTAssertTrue(prompt.contains("- \(AskAiPrompt.isoDate(today + 9)) ("))
        XCTAssertFalse(prompt.contains(AskAiPrompt.isoDate(today + 2)))
        XCTAssertFalse(prompt.contains(AskAiPrompt.isoDate(today + 10)))
        f.vm.onReset()
        XCTAssertEqual(f.vm.uiState.startDay, today, "a plan never silently starts in the past")
    }

    func testFromMyRecipesNamesThemByLinkMostRecentlyViewedFirstCappedAndSaysHowManyWereSent() async {
        let f = await plus(recipes: 55)
        f.vm.onSource(.mine)
        XCTAssertEqual(f.vm.uiState.recipeCount, 55)
        XCTAssertEqual(f.vm.uiState.recipesSent, 40)
        let prompt = f.vm.prompt(words: words)!
        XCTAssertTrue(prompt.contains("- Dish55 | https://example.com/55\n- Dish54 | https://example.com/54"))
        XCTAssertTrue(prompt.contains("- Dish16 | https://example.com/16"))
        XCTAssertFalse(prompt.contains("Dish15 |"))
        XCTAssertTrue(prompt.contains("Use only my saved recipes listed above."))
    }

    func testFromMyRecipesNeedsRecipesAndOnlyTheChosenListsRecipesAreOfferedWhenOneIsChosen() async {
        let none = await plus()
        none.vm.onSource(.mine)
        XCTAssertFalse(none.vm.uiState.canSend)
        XCTAssertNil(none.vm.prompt(words: words))
        none.vm.onSource(.new)
        XCTAssertTrue(none.vm.uiState.canSend)

        let f = await plus(recipes: 5)
        f.lists.lists.send([RecipeList(id: 3, name: "Dinner", isBuiltIn: true, isFavorites: false, recipeCount: 2),
                            RecipeList(id: 4, name: "Empty", isBuiltIn: false, isFavorites: false, recipeCount: 0)])
        f.lists.stageMembership([(recipeId: 2, listId: 3), (recipeId: 3, listId: 3)])
        await settleMain()
        XCTAssertEqual(f.vm.uiState.planLists, [PlanListChoice(id: 3, name: "Dinner")], "a list with no recipe is not offered")
        f.vm.onSource(.mix)
        f.vm.onList(3)
        f.lists.recipesIn.send([
            RecipeSummary(id: 2, title: "Dish2", imageUrl: nil, totalTime: nil, lastViewedAt: 2, isSaved: true, sourceUrl: "https://example.com/2"),
            RecipeSummary(id: 3, title: "Dish3", imageUrl: nil, totalTime: nil, lastViewedAt: 3, isSaved: true, sourceUrl: "https://example.com/3"),
        ])
        await settleMain()
        XCTAssertEqual(f.vm.uiState.recipeCount, 2)
        let prompt = f.vm.prompt(words: words)!
        XCTAssertTrue(prompt.contains("- Dish3 | https://example.com/3\n- Dish2 | https://example.com/2"))
        XCTAssertFalse(prompt.contains("Dish5"))
    }

    func testAMixStepsBetweenOneAndTheWeeksMealsAndSaysSoInThePrompt() async {
        let f = await plus(recipes: 4)
        f.vm.onSource(.mix)
        XCTAssertEqual(f.vm.uiState.mix, 3)
        f.vm.onMix(0)
        XCTAssertEqual(f.vm.uiState.mix, 1)
        f.vm.onMix(50)
        XCTAssertEqual(f.vm.uiState.mix, 7, "one meal a day for a week")
        f.vm.onMix(2)
        XCTAssertTrue(f.vm.prompt(words: words)!.contains("Use up to 2 of my saved recipes"))
    }

    func testTheFavouritesAndCookedLatelyTogglesLeaveTheirLinesOutAndAreNotUsedFromMyRecipesOnly() async {
        let f = await plus(recipes: 3)
        f.lists.lists.send([RecipeList(id: 2, name: "Favorites", isBuiltIn: true, isFavorites: true, recipeCount: 1)])
        f.lists.recipesIn.send([summary(1, "Lasagne")])
        f.recipes.history.send([
            RecipeSummary(id: 9, title: "Chili", imageUrl: nil, totalTime: nil, lastViewedAt: 9, isSaved: true, lastCookedDay: today - 1, sourceUrl: "https://example.com/9"),
        ])
        await settleMain()
        var prompt = f.vm.prompt(words: words)!
        XCTAssertTrue(prompt.contains("Recipes I like, for ideas: Lasagne."))
        XCTAssertTrue(prompt.contains("don't repeat them: Chili."))
        f.vm.onUseFavourites(false)
        f.vm.onSkipCooked(false)
        prompt = f.vm.prompt(words: words)!
        XCTAssertFalse(prompt.contains("Recipes I like"))
        XCTAssertFalse(prompt.contains("don't repeat"))
        f.vm.onUseFavourites(true)
        f.vm.onSkipCooked(true)
        f.vm.onSource(.mine)
        prompt = f.vm.prompt(words: words)!
        XCTAssertFalse(prompt.contains("Recipes I like"))
        XCTAssertFalse(prompt.contains("don't repeat"))
    }

    func testManyMealsWarnOfALongAnswerButSavedRecipesDoNot() async {
        let f = await plus(recipes: 30)
        XCTAssertFalse(f.vm.uiState.longAnswer)
        f.vm.onMeal("breakfast")
        f.vm.onMeal("lunch")
        XCTAssertTrue(f.vm.uiState.longAnswer, "21 recipes to write out")
        f.vm.onSource(.mine)
        XCTAssertFalse(f.vm.uiState.longAnswer)
    }

    func testWithTheFlagOffPlanMyWeekIsTodaysWeekOfDinners() async {
        let f = Fixture(now: now)
        await settleMain()
        f.vm.onKind(.planMyWeek)
        XCTAssertFalse(f.vm.uiState.plan)
        XCTAssertTrue(f.vm.uiState.canSend)
        let prompt = f.vm.prompt(words: words)!
        XCTAssertTrue(prompt.hasPrefix("Plan one dinner recipe for each of these days:"))
        XCTAssertFalse(prompt.contains("\"meal\""))
    }
}
