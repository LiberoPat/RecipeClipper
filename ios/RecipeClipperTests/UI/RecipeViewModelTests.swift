import XCTest
@testable import RecipeClipper

@MainActor
final class RecipeViewModelTests: XCTestCase {

    private nonisolated func testRecipe(
        id: Int64 = 1,
        yield: String? = "4 servings",
        ingredients: [String] = ["2 cups flour", "1 cup milk"],
        instructions: [String] = [
            "Preheat the oven to 350°F.",
            "Mix for 5 minutes.",
            "Bake for 10 minutes.",
            "Cool completely."
        ],
        checkedIngredients: Set<Int> = []
    ) -> Recipe {
        Recipe(
            name: "Test Recipe", image: nil, ingredients: ingredients, instructions: instructions,
            prepTime: "10m", cookTime: "20m", totalTime: "30m", yield: yield,
            sourceUrl: "https://example.com/recipe", id: id, checkedIngredients: checkedIngredients
        )
    }

    /// The Clock and the tick sleep share one virtual timeline, so wall-clock timer deadlines
    /// line up with the time the test advances.
    private func makeViewModel(
        id: Int64? = nil,
        url: String? = nil,
        repository: FakeRecipeRepository,
        preferences: FakeAppPreferences = FakeAppPreferences(),
        clock: TestClock = TestClock()
    ) -> RecipeViewModel {
        RecipeViewModel(
            recipeId: id, url: url, repository: repository, preferences: preferences,
            clock: clock, sleep: clock.sleep
        )
    }

    private func repository(_ recipe: Recipe?) -> FakeRecipeRepository {
        let repository = FakeRecipeRepository()
        repository.openResult = recipe
        return repository
    }

    private func success(_ vm: RecipeViewModel, file: StaticString = #filePath, line: UInt = #line) -> RecipeSuccess? {
        guard let content = vm.uiState.content.success else {
            XCTFail("Expected success, got \(vm.uiState.content)", file: file, line: line)
            return nil
        }
        return content
    }

    private func loaded(_ recipe: Recipe? = nil, preferences: FakeAppPreferences = FakeAppPreferences(), clock: TestClock = TestClock()) async -> (RecipeViewModel, FakeRecipeRepository) {
        let recipe = recipe ?? testRecipe()
        let repository = repository(recipe)
        let vm = makeViewModel(id: recipe.id, repository: repository, preferences: preferences, clock: clock)
        await settleMain()
        return (vm, repository)
    }

    // MARK: Loading

    func testStartsLoading() {
        let vm = makeViewModel(id: 1, repository: repository(testRecipe()))
        XCTAssertEqual(vm.uiState.content, .loading)
    }

    func testLoadsARecipeById() async {
        let (vm, _) = await loaded()
        XCTAssertEqual(success(vm)?.recipe.name, "Test Recipe")
    }

    func testLoadsARecipeByUrl() async {
        let repository = FakeRecipeRepository()
        repository.importResult = .success(testRecipe())
        let vm = makeViewModel(url: "https://example.com/recipe", repository: repository)
        await settleMain()

        XCTAssertNotNil(vm.uiState.content.success)
    }

    func testAnImportFailureShowsItsCause() async {
        let repository = FakeRecipeRepository()
        repository.importResult = .error(.fetchFailed("HTTP 403"))
        let vm = makeViewModel(url: "https://example.com/recipe", repository: repository)
        await settleMain()

        XCTAssertEqual(vm.uiState.content, .error(.fetchFailed("HTTP 403")))
    }

    func testErrorsWhenNeitherIdNorUrlIsPresent() async {
        let vm = makeViewModel(repository: FakeRecipeRepository())
        await settleMain()

        XCTAssertEqual(vm.uiState.content, .error(.nothingToShow))
    }

    func testErrorsWhenOpenReturnsNil() async {
        let vm = makeViewModel(id: 1, repository: repository(nil))
        await settleMain()

        XCTAssertEqual(vm.uiState.content, .error(.notSaved))
    }

    func testRetryLoadsAgain() async {
        let repository = repository(nil)
        let vm = makeViewModel(id: 1, repository: repository)
        await settleMain()
        XCTAssertEqual(vm.uiState.content, .error(.notSaved))

        repository.openResult = testRecipe()
        vm.onRetry()
        XCTAssertEqual(vm.uiState.content, .loading)
        await settleMain()

        XCTAssertNotNil(vm.uiState.content.success)
    }

    func testCheckedIngredientsAreSeededFromTheLoadedRecipe() async {
        let (vm, _) = await loaded(testRecipe(checkedIngredients: [1]))
        XCTAssertEqual(vm.uiState.checkedIngredients, [1])
    }

    func testOnIngredientCheckedUpdatesStateAndPersistsThroughTheRepository() async {
        let (vm, repository) = await loaded(testRecipe(id: 5))

        vm.onIngredientChecked(0, true)
        await settleMain()

        XCTAssertEqual(vm.uiState.checkedIngredients, [0])
        XCTAssertEqual(repository.setCheckedCalls.map(\.id), [5])
        XCTAssertEqual(repository.setCheckedCalls.map(\.checked), [[0]])

        vm.onIngredientChecked(0, false)
        await settleMain()
        XCTAssertEqual(vm.uiState.checkedIngredients, [])
    }

    // MARK: Servings

    func testChangingServingsRescalesTheIngredientList() async {
        let (vm, _) = await loaded()

        vm.onServingsChange(8) // base 4 -> target 8, factor 2.0

        let expected = testRecipe().ingredients.map {
            UnitConverter.convert(IngredientScaler.scale($0, factor: 2.0), system: .asWritten, includeLiquids: false)
        }
        XCTAssertEqual(success(vm)?.ingredients, expected)
        XCTAssertEqual(success(vm)?.servings, ServingsScale(base: 4, target: 8))
    }

    func testServingsAreClampedToOneAndToServingsMax() async {
        let (vm, _) = await loaded()

        vm.onServingsChange(0)
        XCTAssertEqual(success(vm)?.servings?.target, 1)

        vm.onServingsChange(1000)
        XCTAssertEqual(success(vm)?.servings?.target, Servings.max)
    }

    func testChangingServingsIsANoOpWhenTheRecipeHasNoUsableYield() async {
        let (vm, _) = await loaded(testRecipe(yield: "a lot"))

        let before = vm.uiState
        vm.onServingsChange(10)

        XCTAssertEqual(vm.uiState, before)
        XCTAssertNil(success(vm)?.servings)
    }

    // MARK: Units

    func testChangingUnitsReRendersIngredientsAndInstructionsAndWritesThroughPreferences() async {
        let preferences = FakeAppPreferences()
        let (vm, _) = await loaded(preferences: preferences)

        vm.onUnitSystemChange(.grams)

        let expectedIngredients = testRecipe().ingredients.map {
            UnitConverter.convert(IngredientScaler.scale($0, factor: 1.0), system: .grams, includeLiquids: false)
        }
        // Oven temperature is decoupled from the unit system (defaults to as written), so
        // changing UnitSystem alone must leave instructions untouched.
        let expectedInstructions = testRecipe().instructions.map {
            TemperatureConverter.convert($0, unit: .asWritten)
        }
        XCTAssertEqual(success(vm)?.ingredients, expectedIngredients)
        XCTAssertEqual(success(vm)?.instructions, expectedInstructions)
        XCTAssertEqual(preferences.unitSystem, .grams)
        XCTAssertEqual(vm.uiState.unitSystem, .grams)
    }

    func testTogglingConvertLiquidsReRendersIngredientsAndWritesThroughPreferences() async {
        let preferences = FakeAppPreferences()
        let (vm, _) = await loaded(preferences: preferences)
        vm.onUnitSystemChange(.grams)

        vm.onConvertLiquidsChange(true)

        let expected = testRecipe().ingredients.map {
            UnitConverter.convert(IngredientScaler.scale($0, factor: 1.0), system: .grams, includeLiquids: true)
        }
        XCTAssertEqual(success(vm)?.ingredients, expected)
        XCTAssertTrue(preferences.convertLiquids)
    }

    func testUnitPreferencesAreReadOnceAtInit() async {
        let preferences = FakeAppPreferences(unitSystem: .metric, convertLiquids: true)
        let (vm, _) = await loaded(preferences: preferences)

        XCTAssertEqual(vm.uiState.unitSystem, .metric)
        XCTAssertTrue(vm.uiState.convertLiquids)
        let expected = testRecipe().ingredients.map {
            UnitConverter.convert(IngredientScaler.scale($0, factor: 1.0), system: .metric, includeLiquids: true)
        }
        XCTAssertEqual(success(vm)?.ingredients, expected)
    }

    func testDarkWhileCookingIsOffByDefaultAndWritesThroughWhenTurnedOn() async {
        let preferences = FakeAppPreferences()
        let (vm, _) = await loaded(preferences: preferences)

        // Off by default: cook mode follows the system theme like every other screen.
        XCTAssertFalse(vm.uiState.darkWhileCooking)

        vm.onDarkWhileCookingChange(true)

        XCTAssertTrue(vm.uiState.darkWhileCooking)
        XCTAssertTrue(preferences.darkWhileCooking)
    }

    func testDarkWhileCookingIsSeededFromPreferencesAndLeavesTheRecipeTextAlone() async {
        let preferences = FakeAppPreferences(darkWhileCooking: true)
        let (vm, _) = await loaded(preferences: preferences)
        XCTAssertTrue(vm.uiState.darkWhileCooking)

        let before = success(vm)?.ingredients
        vm.onDarkWhileCookingChange(false)

        // A display choice, so nothing about the rendered recipe may change.
        XCTAssertEqual(success(vm)?.ingredients, before)
    }

    func testTheScreenIsForcedDarkOnlyWhenCookingWithDarkWhileCookingOn() async {
        let (vm, _) = await loaded(preferences: FakeAppPreferences(darkWhileCooking: true))
        XCTAssertFalse(vm.uiState.forceDark) // reading

        vm.onCookStart()
        XCTAssertTrue(vm.uiState.forceDark)

        vm.onDarkWhileCookingChange(false)
        XCTAssertFalse(vm.uiState.forceDark)
    }

    func testTemperatureUnitIsSeededFromPreferencesAndConvertsInstructions() async {
        let preferences = FakeAppPreferences(temperatureUnit: .celsius)
        let (vm, _) = await loaded(testRecipe(instructions: ["Bake at 350°F"]), preferences: preferences)

        XCTAssertEqual(vm.uiState.temperatureUnit, .celsius)
        XCTAssertEqual(success(vm)?.instructions, ["Bake at 180°C"])
    }

    func testDefaultTemperatureUnitIsAsWrittenAndLeavesInstructionsUntouched() async {
        let (vm, _) = await loaded(testRecipe(instructions: ["Bake at 350°F"]))

        XCTAssertEqual(vm.uiState.temperatureUnit, .asWritten)
        XCTAssertEqual(success(vm)?.instructions, ["Bake at 350°F"])
    }

    func testScalingAndConvertingTogetherMatchesTheScaleThenConvertOrder() async {
        let (vm, _) = await loaded()

        vm.onServingsChange(2) // base 4 -> target 2, factor 0.5
        vm.onUnitSystemChange(.metric)

        // Scale first, then convert. Computing "expected" the other way round would not match
        // if the ViewModel regressed.
        let expected = testRecipe().ingredients.map {
            UnitConverter.convert(IngredientScaler.scale($0, factor: 0.5), system: .metric, includeLiquids: false)
        }
        XCTAssertEqual(success(vm)?.ingredients, expected)
    }

    // MARK: Cook mode

    func testOnCookStartIsANoOpWithNoInstructions() async {
        let (vm, _) = await loaded(testRecipe(instructions: []))

        vm.onCookStart()

        XCTAssertEqual(vm.uiState.cook, CookState())
    }

    func testAFinishedCookRunStartsFresh() async {
        let (vm, _) = await loaded()

        vm.onCookStart()
        for _ in 0 ..< 4 { vm.onStepDone() }
        XCTAssertFalse(vm.uiState.cook.active)
        XCTAssertEqual(vm.uiState.cook.doneSteps, [0, 1, 2, 3])

        vm.onCookStart()

        XCTAssertEqual(vm.uiState.cook, CookState(active: true, currentStep: 0))
    }

    func testAnUnfinishedCookRunResumesWhereItLeftOff() async {
        let (vm, _) = await loaded()

        vm.onCookStart()
        vm.onStepDone() // done = {0}, currentStep -> 1
        vm.onCookExit()
        XCTAssertFalse(vm.uiState.cook.active)

        vm.onCookStart()

        XCTAssertTrue(vm.uiState.cook.active)
        XCTAssertEqual(vm.uiState.cook.currentStep, 1)
        XCTAssertEqual(vm.uiState.cook.doneSteps, [0])
    }

    func testOnStepDoneAdvancesWrapsToTheEarliestSkippedStepAndDeactivatesWhenAllDone() async {
        let (vm, _) = await loaded()
        vm.onCookStart()

        vm.onStepDone() // step 0 done -> currentStep = 1
        XCTAssertEqual(vm.uiState.cook.currentStep, 1)
        XCTAssertEqual(vm.uiState.cook.doneSteps, [0])

        vm.onStepSelected(3) // forgiving jump ahead; doesn't touch doneSteps
        XCTAssertEqual(vm.uiState.cook.currentStep, 3)
        XCTAssertEqual(vm.uiState.cook.doneSteps, [0])

        vm.onStepDone() // the last step done -> nothing after it -> wrap to 1
        XCTAssertEqual(vm.uiState.cook.currentStep, 1)
        XCTAssertEqual(vm.uiState.cook.doneSteps, [0, 3])
        XCTAssertTrue(vm.uiState.cook.active)

        vm.onStepDone() // step 1 done -> next unfinished is 2
        XCTAssertEqual(vm.uiState.cook.currentStep, 2)
        XCTAssertEqual(vm.uiState.cook.doneSteps, [0, 1, 3])

        vm.onStepDone() // everything done -> deactivate
        XCTAssertFalse(vm.uiState.cook.active)
        XCTAssertEqual(vm.uiState.cook.doneSteps, [0, 1, 2, 3])
    }

    func testIngredientsBarToggles() async {
        let (vm, _) = await loaded()
        vm.onCookStart()
        XCTAssertFalse(vm.uiState.cook.ingredientsExpanded)

        vm.onIngredientsToggle()
        XCTAssertTrue(vm.uiState.cook.ingredientsExpanded)

        vm.onIngredientsToggle()
        XCTAssertFalse(vm.uiState.cook.ingredientsExpanded)
    }

    // MARK: Timers

    private func timerRecipe(_ steps: [String] = ["Wait for 5 seconds."]) -> Recipe {
        testRecipe(instructions: steps)
    }

    func testStartingATimerSetsTotalAndRemainingSeconds() async {
        let (vm, _) = await loaded(timerRecipe())
        XCTAssertEqual(success(vm)?.stepTimerSeconds, [5])

        vm.onTimerStart(0)

        let timer = vm.uiState.cook.timers[0]
        XCTAssertEqual(timer?.totalSeconds, 5)
        XCTAssertEqual(timer?.remainingSeconds, 5)
        XCTAssertEqual(timer?.running, true)
    }

    func testAStepWithNoStatedDurationHasNoTimer() async {
        let (vm, _) = await loaded(timerRecipe(["Cool completely."]))

        vm.onTimerStart(0)

        XCTAssertNil(vm.uiState.cook.timers[0])
    }

    func testARunningTimerDecrementsAsTimePasses() async {
        let clock = TestClock()
        let (vm, _) = await loaded(timerRecipe(), clock: clock)

        vm.onTimerStart(0)
        await clock.advance(by: 1_000)

        XCTAssertEqual(vm.uiState.cook.timers[0]?.remainingSeconds, 4)
    }

    func testATimerReachingZeroIsMarkedFinished() async {
        let clock = TestClock()
        let (vm, _) = await loaded(timerRecipe(), clock: clock)

        vm.onTimerStart(0)
        await clock.runUntilIdle() // the tick loop ends once the timer empties

        let timer = vm.uiState.cook.timers[0]
        XCTAssertEqual(timer?.remainingSeconds, 0)
        XCTAssertEqual(timer?.running, false)
        XCTAssertEqual(timer?.finished, true)
        XCTAssertFalse(clock.hasSleepers)
    }

    func testRemainingTimeComesFromTheWallClockNotFromCountingTicks() async {
        let clock = TestClock()
        let (vm, _) = await loaded(timerRecipe(["Simmer for 10 minutes."]), clock: clock)

        vm.onTimerStart(0)
        await clock.advance(by: 1_000)
        XCTAssertEqual(vm.uiState.cook.timers[0]?.remainingSeconds, 599)

        // The app frozen for three minutes: no ticks at all, then one late one sees it all.
        clock.jump(by: 180_000)
        await clock.advance(by: 0)

        XCTAssertEqual(vm.uiState.cook.timers[0]?.remainingSeconds, 600 - 181)
    }

    func testPausingAndResumingATimer() async {
        let clock = TestClock()
        let (vm, _) = await loaded(timerRecipe(), clock: clock)

        vm.onTimerStart(0)
        await clock.advance(by: 1_000)
        XCTAssertEqual(vm.uiState.cook.timers[0]?.remainingSeconds, 4)

        vm.onTimerToggle(0) // pause
        let paused = vm.uiState.cook.timers[0]
        XCTAssertEqual(paused?.running, false)
        XCTAssertEqual(paused?.remainingSeconds, 4)

        // Time passing while paused changes nothing.
        await clock.advance(by: 2_000)
        XCTAssertEqual(vm.uiState.cook.timers[0]?.remainingSeconds, 4)

        vm.onTimerToggle(0) // resume
        await clock.advance(by: 1_000)
        let resumed = vm.uiState.cook.timers[0]
        XCTAssertEqual(resumed?.remainingSeconds, 3)
        XCTAssertEqual(resumed?.running, true)
    }

    func testResettingATimerRestoresTheTotalAsRemainingAndStopsIt() async {
        let clock = TestClock()
        let (vm, _) = await loaded(timerRecipe(), clock: clock)

        vm.onTimerStart(0)
        await clock.advance(by: 2_000)
        vm.onTimerReset(0)
        await clock.advance(by: 2_000)

        let timer = vm.uiState.cook.timers[0]
        XCTAssertEqual(timer?.remainingSeconds, 5)
        XCTAssertEqual(timer?.running, false)
        XCTAssertEqual(timer?.finished, false)
    }

    func testOnTimerAlertedMarksTheTimerAlerted() async {
        let clock = TestClock()
        let (vm, _) = await loaded(timerRecipe(), clock: clock)

        vm.onTimerStart(0)
        await clock.runUntilIdle()
        XCTAssertEqual(vm.uiState.cook.timers[0]?.alerted, false)

        vm.onTimerAlerted(0)

        XCTAssertEqual(vm.uiState.cook.timers[0]?.alerted, true)
    }

    func testTwoTimersRunAtOnceIndependently() async {
        let clock = TestClock()
        let (vm, _) = await loaded(timerRecipe(["Wait for 5 seconds.", "Wait for 10 seconds."]), clock: clock)

        vm.onTimerStart(0)
        vm.onTimerStart(1)
        await clock.advance(by: 1_000)

        XCTAssertEqual(vm.uiState.cook.timers[0]?.remainingSeconds, 4)
        XCTAssertEqual(vm.uiState.cook.timers[1]?.remainingSeconds, 9)
        XCTAssertEqual(vm.uiState.cook.timers[0]?.running, true)
        XCTAssertEqual(vm.uiState.cook.timers[1]?.running, true)

        await clock.advance(by: 4_000) // 5s in: timer 0 finishes, timer 1 keeps going

        XCTAssertEqual(vm.uiState.cook.timers[0]?.finished, true)
        XCTAssertEqual(vm.uiState.cook.timers[1]?.remainingSeconds, 5)
        XCTAssertEqual(vm.uiState.cook.timers[1]?.running, true)
    }

    // MARK: Sharing and deleting

    func testShareTextReflectsTheCurrentScalingAndUnits() async {
        let (vm, _) = await loaded()

        vm.onServingsChange(8)
        vm.onUnitSystemChange(.metric)

        let content = success(vm)!
        let expected = RecipeShareText.format(
            recipe: content.recipe, servings: content.servings,
            ingredients: content.ingredients, instructions: content.instructions
        )
        XCTAssertEqual(vm.shareText(), expected)
    }

    func testShareTextIsNilUntilARecipeIsLoaded() {
        let vm = makeViewModel(id: 1, repository: repository(testRecipe()))
        XCTAssertNil(vm.shareText())
    }

    func testDeletingARecipeDeletesItAndMarksTheScreenDeleted() async {
        let (vm, repository) = await loaded(testRecipe(id: 9))

        vm.onDelete()
        await settleMain()

        XCTAssertEqual(repository.deleteCalls, [9])
        XCTAssertTrue(vm.uiState.deleted)
    }
}
