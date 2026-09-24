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
        checkedIngredients: Set<Int> = [],
        notes: String? = nil
    ) -> Recipe {
        Recipe(
            name: "Test Recipe", image: nil, ingredients: ingredients, instructions: instructions,
            prepTime: "10m", cookTime: "20m", totalTime: "30m", yield: yield,
            sourceUrl: "https://example.com/recipe", id: id, checkedIngredients: checkedIngredients,
            notes: notes
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

    func testTheSourceDomainIsCreditedFromTheSourceLink() async {
        var recipe = testRecipe()
        recipe.sourceUrl = "https://www.smittenkitchen.com/2024/01/soup/"
        let (vm, _) = await loaded(recipe)
        XCTAssertEqual(success(vm)?.sourceDomain, "smittenkitchen.com")
    }

    func testASourceLinkWithNoHostCreditsNoDomain() async {
        var recipe = testRecipe()
        recipe.sourceUrl = "not a link"
        let (vm, _) = await loaded(recipe)
        XCTAssertNotNil(success(vm))
        XCTAssertNil(success(vm)?.sourceDomain)
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

    // MARK: Notes

    func testTheNoteIsSeededFromTheLoadedRecipe() async {
        let (vm, _) = await loaded(testRecipe(notes: "Half the sugar"))
        XCTAssertEqual(vm.uiState.notes, "Half the sugar")
    }

    func testARecipeWithoutANoteShowsAnEmptyOne() async {
        let (vm, _) = await loaded(testRecipe(notes: nil))
        XCTAssertEqual(vm.uiState.notes, "")
    }

    func testTypingShowsAtOnceAndSavesOnceAfterThePause() async {
        let clock = TestClock()
        let (vm, repository) = await loaded(testRecipe(id: 5), clock: clock)

        vm.onNotesChange("N")
        vm.onNotesChange("Ne")
        await clock.advance(by: 499)
        vm.onNotesChange("Needs 10 more minutes")
        await settleMain()

        XCTAssertEqual(vm.uiState.notes, "Needs 10 more minutes")
        XCTAssertTrue(repository.setNotesCalls.isEmpty)

        await clock.advance(by: 501)
        XCTAssertEqual(repository.setNotesCalls.map(\.id), [5])
        XCTAssertEqual(repository.setNotesCalls.map(\.notes), ["Needs 10 more minutes"])
    }

    func testClearingTheNoteSavesTheEmptyText() async {
        let clock = TestClock()
        let (vm, repository) = await loaded(testRecipe(id: 5, notes: "Old"), clock: clock)

        vm.onNotesChange("")
        await clock.runUntilIdle()

        XCTAssertEqual(vm.uiState.notes, "")
        XCTAssertEqual(repository.setNotesCalls.map(\.notes), [""])
    }

    func testLeavingTheScreenMidPauseStillSavesTheNote() async {
        let repository = repository(testRecipe(id: 5))
        var vm: RecipeViewModel? = makeViewModel(id: 5, repository: repository)
        weak var weakVm = vm
        await settleMain()

        vm?.onNotesChange("Less salt")
        vm = nil // the screen is popped mid-pause
        await settleMain()

        XCTAssertNil(weakVm, "a pending note must not keep the ViewModel alive")
        XCTAssertEqual(repository.setNotesCalls.map(\.notes), ["Less salt"])
    }

    func testANoteSavedByThePauseIsNotWrittenAgainOnLeaving() async {
        let clock = TestClock()
        let repository = repository(testRecipe(id: 5))
        var vm: RecipeViewModel? = makeViewModel(id: 5, repository: repository, clock: clock)
        await settleMain()

        vm?.onNotesChange("Less salt")
        await clock.runUntilIdle()
        vm = nil
        await settleMain()

        XCTAssertEqual(repository.setNotesCalls.map(\.notes), ["Less salt"])
    }

    func testNoNoteIsWrittenBeforeTheRecipeHasLoaded() async {
        let clock = TestClock()
        let repository = repository(testRecipe(id: 5))
        let vm = makeViewModel(id: 5, repository: repository, clock: clock)

        vm.onNotesChange("Too early")
        await clock.runUntilIdle()

        XCTAssertTrue(repository.setNotesCalls.isEmpty)
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

        vm.onUnitSystemChange(.metric)

        let expectedIngredients = testRecipe().ingredients.map {
            UnitConverter.convert(IngredientScaler.scale($0, factor: 1.0), system: .metric, includeLiquids: false)
        }
        // Oven temperature is decoupled from the unit system (defaults to as written), so
        // changing UnitSystem alone must leave instructions untouched.
        let expectedInstructions = testRecipe().instructions.map {
            TemperatureConverter.convert($0, unit: .asWritten)
        }
        XCTAssertEqual(success(vm)?.ingredients, expectedIngredients)
        XCTAssertEqual(success(vm)?.instructions, expectedInstructions)
        XCTAssertEqual(preferences.unitSystem, .metric)
        XCTAssertEqual(vm.uiState.unitSystem, .metric)
    }

    func testUnitPreferencesAreSeededAtInit() async {
        let preferences = FakeAppPreferences(unitSystem: .metric, convertLiquids: true)
        let (vm, _) = await loaded(preferences: preferences)

        XCTAssertEqual(vm.uiState.unitSystem, .metric)
        XCTAssertTrue(vm.uiState.convertLiquids)
        let expected = testRecipe().ingredients.map {
            UnitConverter.convert(IngredientScaler.scale($0, factor: 1.0), system: .metric, includeLiquids: true)
        }
        XCTAssertEqual(success(vm)?.ingredients, expected)
    }

    func testDarkWhileCookingIsOffByDefault() async {
        let (vm, _) = await loaded()

        // Off by default: cook mode follows the system theme like every other screen.
        XCTAssertFalse(vm.uiState.darkWhileCooking)
    }

    func testDarkWhileCookingIsSeededFromPreferences() async {
        let (vm, _) = await loaded(preferences: FakeAppPreferences(darkWhileCooking: true))

        XCTAssertTrue(vm.uiState.darkWhileCooking)
    }

    func testTheScreenIsForcedDarkOnlyWhenCookingWithDarkWhileCookingOn() async {
        let preferences = FakeAppPreferences(darkWhileCooking: true)
        let (vm, _) = await loaded(preferences: preferences)
        XCTAssertFalse(vm.uiState.forceDark) // reading

        vm.onCookStart()
        XCTAssertTrue(vm.uiState.forceDark)

        preferences.darkWhileCooking = false // turned off in Settings mid-cook
        await settleMain()
        XCTAssertFalse(vm.uiState.forceDark)
    }

    // MARK: A settings change arriving while the recipe is open (#24)
    //
    // Writing to the fake directly is Settings changing a default while this screen sits
    // underneath it: the fake re-emits on `settings`, as the UserDefaults notification does.

    func testAUnitSystemChangeMadeInSettingsReRendersTheOpenRecipe() async {
        let preferences = FakeAppPreferences()
        let (vm, _) = await loaded(preferences: preferences)

        preferences.unitSystem = .metric
        await settleMain()

        XCTAssertEqual(vm.uiState.unitSystem, .metric)
        let expected = testRecipe().ingredients.map {
            UnitConverter.convert(IngredientScaler.scale($0, factor: 1.0), system: .metric, includeLiquids: false)
        }
        XCTAssertEqual(success(vm)?.ingredients, expected)
    }

    func testTurningOnConvertLiquidsInSettingsReRendersTheOpenRecipe() async {
        let preferences = FakeAppPreferences(unitSystem: .ounces)
        let (vm, _) = await loaded(preferences: preferences)

        preferences.convertLiquids = true
        await settleMain()

        XCTAssertTrue(vm.uiState.convertLiquids)
        let expected = testRecipe().ingredients.map {
            UnitConverter.convert(IngredientScaler.scale($0, factor: 1.0), system: .ounces, includeLiquids: true)
        }
        XCTAssertEqual(success(vm)?.ingredients, expected)
    }

    func testAnOvenTemperatureChangeMadeInSettingsConvertsTheOpenRecipesSteps() async {
        let preferences = FakeAppPreferences()
        let (vm, _) = await loaded(testRecipe(instructions: ["Bake at 350°F"]), preferences: preferences)

        preferences.temperatureUnit = .celsius
        await settleMain()

        XCTAssertEqual(vm.uiState.temperatureUnit, .celsius)
        XCTAssertEqual(success(vm)?.instructions, ["Bake at 180°C"])
    }

    func testDarkWhileCookingChangedInSettingsReachesTheScreenAndLeavesTheTextAlone() async {
        let preferences = FakeAppPreferences()
        let (vm, _) = await loaded(preferences: preferences)
        let before = vm.uiState.content

        preferences.darkWhileCooking = true
        await settleMain()

        XCTAssertTrue(vm.uiState.darkWhileCooking)
        // A display choice, so nothing about the rendered recipe may change.
        XCTAssertEqual(vm.uiState.content, before)
    }

    func testASettingsChangeKeepsTheChosenServingsTheTicksAndCookProgress() async {
        let preferences = FakeAppPreferences()
        let (vm, _) = await loaded(preferences: preferences)
        vm.onServingsChange(8) // base 4 -> 8, factor 2
        vm.onIngredientChecked(0, true)
        vm.onCookStart()
        vm.onStepDone()
        let cookBefore = vm.uiState.cook

        preferences.unitSystem = .metric
        await settleMain()

        XCTAssertEqual(success(vm)?.servings?.target, 8)
        let expected = testRecipe().ingredients.map {
            UnitConverter.convert(IngredientScaler.scale($0, factor: 2.0), system: .metric, includeLiquids: false)
        }
        XCTAssertEqual(success(vm)?.ingredients, expected)
        XCTAssertEqual(vm.uiState.checkedIngredients, [0])
        XCTAssertEqual(vm.uiState.cook, cookBefore)
    }

    func testADecimalCommaLineKeepsItsCommaWhenAUnitsChangeArrivesWhileItIsScaled() async {
        // Doubled, "2,5 lb" is "5 lb", which no longer shows a comma: the re-render must take
        // the separator from the unscaled line, as the first render does (#42).
        let preferences = FakeAppPreferences()
        let (vm, _) = await loaded(testRecipe(ingredients: ["2,5 lb potatoes"]), preferences: preferences)
        vm.onServingsChange(8) // base 4 -> 8, factor 2

        preferences.unitSystem = .metric
        await settleMain()

        XCTAssertEqual(success(vm)?.ingredients, ["2,27 kg potatoes"])
    }

    func testASettingsChangeMadeWhileTheRecipeIsStillLoadingIsUsedWhenItArrives() async {
        let preferences = FakeAppPreferences()
        let vm = makeViewModel(id: 1, repository: repository(testRecipe()), preferences: preferences)

        preferences.unitSystem = .metric
        await settleMain()

        let expected = testRecipe().ingredients.map {
            UnitConverter.convert(IngredientScaler.scale($0, factor: 1.0), system: .metric, includeLiquids: false)
        }
        XCTAssertEqual(success(vm)?.ingredients, expected)
    }

    func testTheUnitsDropdownsWriteComesBackThroughSettingsWithoutChangingAnything() async {
        let preferences = FakeAppPreferences()
        let (vm, _) = await loaded(preferences: preferences)

        vm.onUnitSystemChange(.ounces)
        let immediately = vm.uiState // before the echo is delivered
        await settleMain()

        XCTAssertEqual(immediately.unitSystem, .ounces)
        XCTAssertEqual(vm.uiState, immediately)
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

    /// The exact text Share hands the share sheet, pinned rather than rebuilt from the state:
    /// plain text, as shown on screen (scaled and converted), with no source link. Android's
    /// RecipeScreenTest pins the same shape.
    func testShareTextIsThePlainTextOfTheScaledConvertedRecipe() async {
        let (vm, _) = await loaded()

        vm.onServingsChange(8)
        vm.onUnitSystemChange(.metric)

        XCTAssertEqual(vm.shareText(), """
            Test Recipe

            Serves 8 (originally 4)
            Prep 10m · Cook 20m · Total 30m

            INGREDIENTS
            480 g flour
            480 ml milk

            INSTRUCTIONS
            1. Preheat the oven to 350°F.
            2. Mix for 5 minutes.
            3. Bake for 10 minutes.
            4. Cool completely.
            """)
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
