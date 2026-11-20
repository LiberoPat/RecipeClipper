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
        cookArg: Bool = false,
        countdowns: Bool = false,
        cooked: FakeCookedPhotoRepository? = nil,
        startCook: Bool = false
    ) async -> (RecipeViewModel, FakeRecipeRepository) {
        let repository = FakeRecipeRepository()
        repository.openResult = recipe
        let alarms = alarms ?? FakeTimerAlarmScheduler()
        let vm = RecipeViewModel(
            recipeId: recipe.id, url: nil, repository: repository, preferences: FakeAppPreferences(),
            clock: clock, sleep: clock.sleep, alarms: alarms, openInCookMode: cookArg,
            countdowns: countdowns ? TimerCountdownSync(repository: repository, alarms: alarms) : nil,
            cooked: cooked, startInCookMode: startCook
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

    // #333: a tap on the countdown after the last step used to restart the run and stop the timer.
    func testTheCookArgumentOnAFinishedRunKeepsItsTimerAndTheReadingView() async {
        let alarms = FakeTimerAlarmScheduler()
        let finished = CookProgress(
            active: false, currentStep: 2, doneSteps: [0, 1, 2],
            timers: [2: SavedTimer(totalSeconds: 300, remainingSeconds: 300, endsAt: 60_000)]
        )
        let (vm, _) = await open(recipe(cook: finished), alarms: alarms, cookArg: true)

        XCTAssertFalse(vm.uiState.cook.active)
        XCTAssertEqual(vm.uiState.cook.doneSteps, [0, 1, 2])
        XCTAssertEqual(vm.uiState.cook.timers[2]?.running, true)
        XCTAssertEqual(Set(alarms.pending.keys), [key(2)])
        XCTAssertTrue(alarms.cancelCalls.isEmpty)
    }

    func testDeletingTheRecipeSquaresTheCountdownsWithTheDatabase() async {
        let alarms = FakeTimerAlarmScheduler()
        let (vm, repository) = await open(recipe(), alarms: alarms, countdowns: true)
        let other = StepAlarm(recipeId: 9, recipeTitle: "Other", step: 0, endsAt: 5_000)
        repository.runningTimersResult = [other]

        vm.onDelete()
        await settleMain()

        XCTAssertEqual(alarms.reconcileCalls, [[other]])
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

    // #402: "Done — finish" ends the run's timers, their alerts and countdowns, and records the cooking.
    func testFinishingARunStopsItsTimersAndCancelsTheirAlerts() async {
        let alarms = FakeTimerAlarmScheduler()
        let (vm, repository) = await open(recipe(instructions: ["Simmer for 10 minutes.", "Serve."]), alarms: alarms)
        vm.onCookStart()
        vm.onTimerStart(0)
        XCTAssertEqual(alarms.pending.count, 1)

        vm.onStepDone()
        XCTAssertEqual(alarms.pending.count, 1, "not the last step: the timer runs on")
        vm.onStepDone() // the last step: Done, finish
        await vm.settleWrites()

        XCTAssertTrue(alarms.pending.isEmpty)
        XCTAssertEqual(alarms.cancelCalls.map(\.step), [0])
        XCTAssertTrue(vm.uiState.cook.timers.values.allSatisfy { !$0.running })
        XCTAssertNil(repository.setCookProgressCalls.last?.progress.timers[0]?.endsAt)
    }

    func testFinishingARunRecordsTodaysCookingOnce() async {
        let cooked = FakeCookedPhotoRepository()
        cooked.today = 0
        let (vm, _) = await open(recipe(instructions: ["Serve."]), cooked: cooked)

        vm.onCookStart()
        vm.onStepDone()
        await vm.settleWrites()
        XCTAssertEqual(cooked.photos.value.map(\.recipeId), [id])

        vm.onCookStart()
        vm.onStepDone()
        await vm.settleWrites()
        XCTAssertEqual(cooked.photos.value.count, 1, "one cooking a day")
    }

    func testARunAlreadyMarkedCookedTodayAddsNoSecondEntryAndAnUnfinishedOneAddsNone() async {
        let cooked = FakeCookedPhotoRepository()
        cooked.today = 0
        cooked.mark(recipeId: id)
        let (vm, _) = await open(recipe(instructions: ["Chop.", "Serve."]), cooked: cooked)

        vm.onCookStart()
        vm.onStepDone()
        await vm.settleWrites()
        XCTAssertEqual(cooked.photos.value.count, 1)
        vm.onStepDone()
        await vm.settleWrites()
        XCTAssertEqual(cooked.photos.value.count, 1)
    }

    // #402: Home's Today card opens the recipe in cook mode; a finished last run starts afresh.
    func testStartCookOpensInCookModeAFinishedRunStartingAfreshAndAnUnfinishedOneWhereItWas() async {
        let (fresh, _) = await open(
            recipe(cook: CookProgress(currentStep: 2, doneSteps: [0, 1, 2])), startCook: true
        )
        XCTAssertTrue(fresh.uiState.cook.active)
        XCTAssertEqual(fresh.uiState.cook.currentStep, 0)
        XCTAssertTrue(fresh.uiState.cook.doneSteps.isEmpty)

        let (resumed, _) = await open(recipe(cook: CookProgress(currentStep: 1, doneSteps: [0])), startCook: true)
        XCTAssertTrue(resumed.uiState.cook.active)
        XCTAssertEqual(resumed.uiState.cook.currentStep, 1)
        XCTAssertEqual(resumed.uiState.cook.doneSteps, [0])
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
