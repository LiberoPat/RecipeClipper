import XCTest
@testable import RecipeClipper

/// Issue #10 (Android's RecipeCookPersistenceTest): cook progress and servings are saved as they
/// change and restored on opening, and running timers are handed to the scheduler for their
/// background alert.
@MainActor
final class RecipeCookPersistenceTests: XCTestCase {
    private let id: Int64 = 3

    private func recipe(
        cook: CookProgress = CookProgress(),
        servingsTarget: Int? = nil,
        instructions: [String] = ["Chop.", "Simmer for 10 minutes.", "Rest for 5 minutes."]
    ) -> Recipe {
        Recipe(
            name: "Soup", image: nil, ingredients: ["2 cups stock"], instructions: instructions,
            prepTime: nil, cookTime: nil, totalTime: nil, yield: "4 servings",
            sourceUrl: "https://example.com/soup", id: id, cook: cook, servingsTarget: servingsTarget
        )
    }

    private func open(
        _ recipe: Recipe,
        clock: TestClock = TestClock(now: 0),
        alarms: FakeTimerAlarmScheduler? = nil,
        cookArg: Bool = false
    ) async -> (RecipeViewModel, FakeRecipeRepository) {
        let repository = FakeRecipeRepository()
        repository.openResult = recipe
        let vm = RecipeViewModel(
            recipeId: recipe.id, url: nil, repository: repository, preferences: FakeAppPreferences(),
            clock: clock, sleep: clock.sleep, alarms: alarms ?? FakeTimerAlarmScheduler(), openInCookMode: cookArg
        )
        await settleMain()
        return (vm, repository)
    }

    private func key(_ step: Int) -> FakeTimerAlarmScheduler.Key {
        FakeTimerAlarmScheduler.Key(recipeId: id, step: step)
    }

    // MARK: Restoring

    func testOpeningRestoresCookModeTheStepDoneStepsAndServings() async {
        let (vm, _) = await open(
            recipe(cook: CookProgress(active: true, currentStep: 2, doneSteps: [0, 1]), servingsTarget: 8)
        )

        XCTAssertTrue(vm.uiState.cook.active)
        XCTAssertEqual(vm.uiState.cook.currentStep, 2)
        XCTAssertEqual(vm.uiState.cook.doneSteps, [0, 1])
        XCTAssertEqual(vm.uiState.content.success?.servings?.target, 8)
    }

    func testIndexesPastTheLastStepAreDropped() async {
        let saved = CookProgress(
            active: true, currentStep: 7, doneSteps: [0, 9],
            timers: [9: SavedTimer(totalSeconds: 60, remainingSeconds: 60, endsAt: nil)]
        )
        let (vm, _) = await open(recipe(cook: saved))

        XCTAssertEqual(vm.uiState.cook.currentStep, 2)
        XCTAssertEqual(vm.uiState.cook.doneSteps, [0])
        XCTAssertTrue(vm.uiState.cook.timers.isEmpty)
    }

    func testATimerStillRunningResumesFromItsDeadlineAndIsRescheduled() async {
        let clock = TestClock(now: 0)
        let alarms = FakeTimerAlarmScheduler()
        let saved = CookProgress(
            active: true, timers: [1: SavedTimer(totalSeconds: 600, remainingSeconds: 600, endsAt: 90_000)]
        )
        let (vm, _) = await open(recipe(cook: saved), clock: clock, alarms: alarms)

        XCTAssertEqual(vm.uiState.cook.timers[1], StepTimer(totalSeconds: 600, remainingSeconds: 90, running: true))
        XCTAssertEqual(alarms.pending[key(1)], StepAlarm(recipeId: id, recipeTitle: "Soup", step: 1, endsAt: 90_000))

        await clock.advance(by: 30_000)
        XCTAssertEqual(vm.uiState.cook.timers[1]?.remainingSeconds, 60)
    }

    func testATimerThatEndedWhileClosedShowsFinishedAndAlreadyAlerted() async {
        let alarms = FakeTimerAlarmScheduler()
        let saved = CookProgress(
            active: true, timers: [1: SavedTimer(totalSeconds: 600, remainingSeconds: 600, endsAt: 50_000)]
        )
        let (vm, _) = await open(recipe(cook: saved), clock: TestClock(now: 100_000), alarms: alarms)

        let timer = vm.uiState.cook.timers[1]
        XCTAssertEqual(timer?.finished, true)
        XCTAssertEqual(timer?.alerted, true) // its notification announced it: no beep on reopening
        XCTAssertTrue(alarms.pending.isEmpty)
    }

    func testOpeningReplacesLeftoverAlertsWithTheRunningTimers() async {
        let alarms = FakeTimerAlarmScheduler()
        // Left over from before a re-share that changed the steps.
        alarms.schedule(StepAlarm(recipeId: id, recipeTitle: "Soup", step: 5, endsAt: 1_000))
        let saved = CookProgress(timers: [
            1: SavedTimer(totalSeconds: 600, remainingSeconds: 240, endsAt: nil),
            2: SavedTimer(totalSeconds: 300, remainingSeconds: 0, endsAt: nil),
        ])
        let (vm, _) = await open(recipe(cook: saved), alarms: alarms)

        XCTAssertTrue(alarms.pending.isEmpty)
        XCTAssertEqual(vm.uiState.cook.timers[1], StepTimer(totalSeconds: 600, remainingSeconds: 240, running: false))
        XCTAssertEqual(
            vm.uiState.cook.timers[2],
            StepTimer(totalSeconds: 300, remainingSeconds: 0, running: false, alerted: true)
        )
    }

    func testTheCookArgumentOpensInCookMode() async {
        let (vm, _) = await open(recipe(), cookArg: true)
        XCTAssertTrue(vm.uiState.cook.active)
    }

    // MARK: Saving

    func testEveryCookActionSavesTheProgressInOrder() async {
        let (vm, repository) = await open(recipe())

        vm.onCookStart()
        vm.onStepDone()
        vm.onStepSelected(2)
        vm.onCookExit()
        await vm.settleWrites()

        XCTAssertEqual(repository.setCookProgressCalls.map(\.progress), [
            CookProgress(active: true),
            CookProgress(active: true, currentStep: 1, doneSteps: [0]),
            CookProgress(active: true, currentStep: 2, doneSteps: [0]),
            CookProgress(active: false, currentStep: 2, doneSteps: [0]),
        ])
        XCTAssertTrue(repository.setCookProgressCalls.allSatisfy { $0.id == id })
    }

    func testARunningTimerIsSavedByItsDeadlineAndItsAlertFollowsIt() async {
        let clock = TestClock(now: 0)
        let alarms = FakeTimerAlarmScheduler()
        let (vm, repository) = await open(recipe(), clock: clock, alarms: alarms)

        vm.onTimerStart(1) // 10 minutes, from t = 0
        await vm.settleWrites()
        XCTAssertEqual(alarms.pending[key(1)]?.endsAt, 600_000)
        XCTAssertEqual(
            repository.setCookProgressCalls.last?.progress.timers[1],
            SavedTimer(totalSeconds: 600, remainingSeconds: 600, endsAt: 600_000)
        )

        await clock.advance(by: 100_000)
        vm.onTimerToggle(1) // pause with 500 s left
        await vm.settleWrites()
        XCTAssertTrue(alarms.pending.isEmpty)
        XCTAssertEqual(
            repository.setCookProgressCalls.last?.progress.timers[1],
            SavedTimer(totalSeconds: 600, remainingSeconds: 500, endsAt: nil)
        )

        vm.onTimerToggle(1) // resume at t = 100 s
        XCTAssertEqual(alarms.pending[key(1)]?.endsAt, 600_000)

        vm.onTimerReset(1)
        await vm.settleWrites()
        XCTAssertTrue(alarms.pending.isEmpty)
        XCTAssertEqual(
            repository.setCookProgressCalls.last?.progress.timers[1],
            SavedTimer(totalSeconds: 600, remainingSeconds: 600, endsAt: nil)
        )
    }

    func testATimerReachingZeroKeepsItsAlertAndTicksDoNotWrite() async {
        let clock = TestClock(now: 0)
        let alarms = FakeTimerAlarmScheduler()
        let (vm, repository) = await open(recipe(), clock: clock, alarms: alarms)

        vm.onTimerStart(2)
        await clock.runUntilIdle()
        await vm.settleWrites()

        XCTAssertEqual(vm.uiState.cook.timers[2]?.finished, true)
        XCTAssertTrue(alarms.cancelCalls.isEmpty)
        XCTAssertEqual(repository.setCookProgressCalls.count, 1)
    }

    func testRestartingAFinishedRunCancelsTheAlertsOfTheTimersItDrops() async {
        let alarms = FakeTimerAlarmScheduler()
        let (vm, _) = await open(recipe(instructions: ["Simmer for 10 minutes."]), alarms: alarms)
        vm.onCookStart()
        vm.onTimerStart(0)
        vm.onStepDone() // the only step: finished, with its timer still running

        vm.onCookStart()

        XCTAssertTrue(alarms.pending.isEmpty)
        XCTAssertTrue(vm.uiState.cook.timers.isEmpty)
    }

    func testDeletingTheRecipeCancelsItsRunningTimers() async {
        let alarms = FakeTimerAlarmScheduler()
        let (vm, _) = await open(recipe(), alarms: alarms)
        vm.onTimerStart(1)
        vm.onTimerStart(2)

        vm.onDelete()

        XCTAssertTrue(alarms.pending.isEmpty)
    }

    func testTheChosenServingsAreSavedAndTheOwnYieldAsNone() async {
        let (vm, repository) = await open(recipe())

        vm.onServingsChange(6)
        vm.onServingsChange(4)
        await vm.settleWrites()

        XCTAssertEqual(repository.setServingsTargetCalls.map(\.target), [6, nil])
    }

    func testAWriteQueuedJustBeforeLeavingStillLands() async {
        let repository = FakeRecipeRepository()
        repository.openResult = recipe()
        var vm: RecipeViewModel? = RecipeViewModel(
            recipeId: id, url: nil, repository: repository, preferences: FakeAppPreferences(),
            clock: TestClock(now: 0), alarms: FakeTimerAlarmScheduler()
        )
        await settleMain()

        vm?.onCookStart()
        vm = nil // the screen is popped
        await settleMain()

        XCTAssertEqual(repository.setCookProgressCalls.last?.progress, CookProgress(active: true))
    }
}
