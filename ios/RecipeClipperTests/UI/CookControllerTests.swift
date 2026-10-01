import XCTest
@testable import RecipeClipper

/// `CookController` on its own (#234; Android's CookControllerTest): cook mode's alerts, tick loop
/// and saved progress around `CookSession`, over state the test holds as the ViewModel would.
@MainActor
final class CookControllerTests: XCTestCase {
    private let id: Int64 = 3
    private let clock = TestClock(now: 0)
    private let alarms = FakeTimerAlarmScheduler()
    private let repository = FakeRecipeRepository()
    private let writes = OrderedWrites()
    private var cook = CookState()
    private var content: RecipeSuccess?

    private func recipe(cook: CookProgress = CookProgress()) -> Recipe {
        Recipe(
            name: "Soup", image: nil, ingredients: ["2 cups stock", "1 onion"],
            instructions: ["Chop.", "Simmer for 10 minutes.", "Serve."],
            prepTime: nil, cookTime: nil, totalTime: nil, yield: "4 servings",
            sourceUrl: "https://example.com/soup", id: id, cook: cook
        )
    }

    private func controller(loaded: Recipe?) -> CookController {
        content = loaded.map { RecipeRenderer.content($0, settings: RecipeRenderer.Settings()) }
        let controller = CookController(clock: clock, alarms: alarms, sleep: clock.sleep, writes: writes, repository: repository)
        controller.content = { [unowned self] in self.content }
        controller.cook = { [unowned self] in self.cook }
        controller.onCook = { [unowned self] in self.cook = $0 }
        return controller
    }

    private func controller() -> CookController { controller(loaded: recipe()) }

    private func key(_ step: Int) -> FakeTimerAlarmScheduler.Key {
        FakeTimerAlarmScheduler.Key(recipeId: id, step: step)
    }

    func testEveryActionSavesTheCooksPlaceInOrder() async {
        let controller = controller()

        controller.start()
        controller.select(2)
        controller.exit()
        await writes.settle()

        XCTAssertEqual(repository.setCookProgressCalls.map(\.id), [id, id, id])
        XCTAssertEqual(repository.setCookProgressCalls.map(\.progress), [
            CookProgress(active: true),
            CookProgress(active: true, currentStep: 2),
            CookProgress(active: false, currentStep: 2)
        ])
    }

    func testOpeningTheIngredientsBarIsNotSaved() async {
        let controller = controller()

        controller.toggleIngredients()
        await writes.settle()

        XCTAssertTrue(cook.ingredientsExpanded)
        XCTAssertTrue(repository.setCookProgressCalls.isEmpty)
    }

    func testATimerSchedulesItsAlertAndCountsDownFromTheClockWithoutWriting() async {
        let controller = controller()

        controller.startTimer(1)
        await writes.settle()
        let saved = repository.setCookProgressCalls.count
        await clock.advance(by: 3_000)

        XCTAssertEqual(Set(alarms.pending.keys), [key(1)])
        XCTAssertEqual(cook.timers[1]?.remainingSeconds, 597)
        XCTAssertTrue(controller.isTicking)
        XCTAssertEqual(repository.setCookProgressCalls.count, saved)
    }

    func testPausingATimerCancelsItsAlertAndTheLoopEnds() async {
        let controller = controller()
        controller.startTimer(1)

        controller.toggleTimer(1)
        await clock.advance(by: 250)

        XCTAssertTrue(alarms.pending.isEmpty)
        XCTAssertEqual(cook.timers[1]?.running, false)
        XCTAssertFalse(controller.isTicking)
    }

    func testResettingATimerStopsItAtItsFullTime() async {
        let controller = controller()
        controller.startTimer(1)

        controller.resetTimer(1)

        XCTAssertTrue(alarms.pending.isEmpty)
        XCTAssertEqual(cook.timers[1]?.remainingSeconds, 600)
        XCTAssertEqual(cook.timers[1]?.running, false)
    }

    func testAStepWithNoTimeStartsNoTimer() async {
        let controller = controller()

        controller.startTimer(0)
        await writes.settle()

        XCTAssertTrue(cook.timers.isEmpty)
        XCTAssertTrue(alarms.scheduleCalls.isEmpty)
        XCTAssertTrue(repository.setCookProgressCalls.isEmpty)
    }

    func testRestoringReschedulesARunningTimerAndStoppingTheTimersCancelsIt() async {
        let saved = CookProgress(
            active: true, timers: [1: SavedTimer(totalSeconds: 600, remainingSeconds: 600, endsAt: 60_000)]
        )
        let controller = controller()

        controller.restore(recipe(cook: saved))
        XCTAssertEqual(Set(alarms.pending.keys), [key(1)])
        XCTAssertTrue(cook.active)
        XCTAssertTrue(controller.isTicking)

        controller.stopTimers(recipeId: id)
        XCTAssertTrue(alarms.pending.isEmpty)
    }

    func testTheLastStepDoneFinishesTheCookWithWhatWasTicked() throws {
        let controller = controller()
        let loaded = try XCTUnwrap(content)
        let onLast = CookState(active: true, currentStep: 2, doneSteps: [0, 1])

        let done = controller.done(onLast, content: loaded, checked: [1])

        XCTAssertTrue(done.finished)
        XCTAssertFalse(done.cook.active)
        XCTAssertEqual(done.finishedCook?.lines, ["1 onion"])
    }

    func testAStepDoneBeforeTheLastOneFinishesNothing() throws {
        let controller = controller()

        let done = controller.done(CookState(active: true), content: try XCTUnwrap(content), checked: [0, 1])

        XCTAssertFalse(done.finished)
        XCTAssertEqual(done.cook.currentStep, 1)
        XCTAssertNil(done.finishedCook)
    }

    func testWithNothingLoadedCookModeDoesNothing() async {
        let controller = controller(loaded: nil)

        controller.start()
        controller.startTimer(1)
        await writes.settle()

        XCTAssertFalse(cook.active)
        XCTAssertTrue(repository.setCookProgressCalls.isEmpty)
    }
}
