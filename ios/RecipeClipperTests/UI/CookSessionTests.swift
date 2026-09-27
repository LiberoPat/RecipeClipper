import XCTest
@testable import RecipeClipper

/// `CookSession` on its own (#169; Android's `CookSessionTest`): cook mode's steps and timers
/// over a clock the test moves.
final class CookSessionTests: XCTestCase {
    private final class MovableClock: Clock {
        var current: Int64 = 1_000_000
        func now() -> Int64 { current }
    }

    private let clock = MovableClock()
    private lazy var session = CookSession(clock: clock)
    private var now: Int64 { clock.current }

    private func recipe(_ cook: CookProgress = CookProgress()) -> Recipe {
        Recipe(
            name: "Stew", image: nil, ingredients: ["2 carrots", "1 onion", "2 cups stock"],
            instructions: ["Chop.", "Simmer 20 minutes.", "Serve."], prepTime: nil, cookTime: nil, totalTime: nil,
            yield: "4", sourceUrl: "https://example.com/stew", id: 7, cook: cook
        )
    }

    func testRestoringResumesARunningTimerFromItsDeadlineAndFinishesOneThatRanOut() {
        let saved = CookProgress(
            active: true, currentStep: 9, doneSteps: [0, 5],
            timers: [
                1: SavedTimer(totalSeconds: 1200, remainingSeconds: 1200, endsAt: now + 90_500),
                0: SavedTimer(totalSeconds: 60, remainingSeconds: 60, endsAt: now - 1),
                2: SavedTimer(totalSeconds: 300, remainingSeconds: 120, endsAt: nil),
                4: SavedTimer(totalSeconds: 60, remainingSeconds: 60, endsAt: now + 5_000),
            ]
        )

        let restored = session.restore(recipe(saved))

        XCTAssertEqual(restored.cook.currentStep, 2)
        XCTAssertEqual(restored.cook.doneSteps, [0])
        XCTAssertTrue(restored.cook.active)
        XCTAssertEqual(restored.cook.timers[1], StepTimer(totalSeconds: 1200, remainingSeconds: 91, running: true))
        XCTAssertEqual(restored.cook.timers[0], StepTimer(totalSeconds: 60, remainingSeconds: 0, running: false, alerted: true))
        XCTAssertEqual(restored.cook.timers[2], StepTimer(totalSeconds: 300, remainingSeconds: 120, running: false))
        XCTAssertNil(restored.cook.timers[4], "past the last step")
        XCTAssertEqual(restored.alarms, [StepAlarm(recipeId: 7, recipeTitle: "Stew", step: 1, endsAt: now + 90_500)])
        XCTAssertTrue(session.hasRunningTimers)
    }

    func testAFinishedRunStartsFreshAndStopsItsTimersAnythingElseResumes() {
        var finished = session.startTimer(CookState(), step: 1, totalSeconds: 1200).cook
        finished.doneSteps = [0, 1, 2]
        finished.currentStep = 2

        let fresh = session.start(finished, stepCount: 3)
        XCTAssertEqual(fresh.cook, CookState(active: true))
        XCTAssertEqual(fresh.stopped, [1])
        XCTAssertFalse(session.hasRunningTimers)

        let resumed = session.start(CookState(currentStep: 7, doneSteps: [0]), stepCount: 3)
        XCTAssertEqual(resumed.cook, CookState(active: true, currentStep: 2, doneSteps: [0]))
        XCTAssertEqual(resumed.stopped, [])

        XCTAssertNil(session.start(CookState(), stepCount: 0).cook, "no steps, nothing to cook")
    }

    func testDoneMovesToTheNextUnfinishedStepThenTheEarliestSkippedThenFinishes() {
        let first = session.done(CookState(active: true, currentStep: 0), stepCount: 3)
        XCTAssertEqual(first.cook, CookState(active: true, currentStep: 1, doneSteps: [0]))
        XCTAssertFalse(first.finished)

        // Step 1 was skipped by tapping step 2.
        let skipped = session.done(session.select(first.cook, step: 2), stepCount: 3)
        XCTAssertEqual(skipped.cook.currentStep, 1)

        let last = session.done(skipped.cook, stepCount: 3)
        XCTAssertTrue(last.finished)
        XCTAssertEqual(last.cook.doneSteps, [0, 1, 2])
        XCTAssertFalse(last.cook.active)
    }

    func testTheEndOfCookingHandsOnTheTickedLinesAsShownInOrder() {
        let content = RecipeSuccess(
            recipe: recipe(), servings: nil, ingredients: ["4 carrots", "2 onions", "480 ml stock"],
            instructions: recipe().instructions, stepTimerSeconds: [nil, 1200, nil], sourceDomain: nil,
            words: LanguageWords.english
        )

        XCTAssertEqual(
            session.finishedCook(content, ticked: [2, 0, 9]), FinishedCook(language: "en", lines: ["4 carrots", "480 ml stock"])
        )
        XCTAssertNil(session.finishedCook(content, ticked: []))
    }

    func testExitKeepsTheProgressAndTheIngredientsBarToggles() {
        let cook = CookState(active: true, currentStep: 1, doneSteps: [0])
        XCTAssertEqual(session.exit(cook), CookState(active: false, currentStep: 1, doneSteps: [0]))
        XCTAssertTrue(session.toggleIngredients(cook).ingredientsExpanded)
        XCTAssertFalse(session.toggleIngredients(session.toggleIngredients(cook)).ingredientsExpanded)
    }

    func testATimerCountsDownFromItsDeadlinePausesResumesAndResets() throws {
        let started = session.startTimer(CookState(), step: 1, totalSeconds: 60)
        XCTAssertEqual(started.endsAt, now + 60_000)
        XCTAssertEqual(started.cook.timers[1], StepTimer(totalSeconds: 60, remainingSeconds: 60, running: true))

        clock.current += 20_000
        let ticked = try XCTUnwrap(session.tick(started.cook))
        XCTAssertEqual(ticked.timers[1]?.remainingSeconds, 40)
        XCTAssertNil(session.tick(ticked), "nothing changed within the same second")

        let paused = try XCTUnwrap(session.toggleTimer(ticked, step: 1))
        XCTAssertNil(paused.endsAt, "pausing cancels the alert")
        XCTAssertEqual(paused.cook.timers[1]?.running, false)
        XCTAssertFalse(session.hasRunningTimers)
        clock.current += 60_000
        XCTAssertNil(session.tick(paused.cook), "a paused timer doesn't tick")

        let resumed = try XCTUnwrap(session.toggleTimer(paused.cook, step: 1))
        XCTAssertEqual(resumed.endsAt, now + 40_000)

        let reset = try XCTUnwrap(session.resetTimer(resumed.cook, step: 1))
        XCTAssertEqual(reset.timers[1], StepTimer(totalSeconds: 60, remainingSeconds: 60, running: false))
        XCTAssertFalse(session.hasRunningTimers)
        XCTAssertNil(session.resetTimer(reset, step: 2))
        XCTAssertNil(session.toggleTimer(reset, step: 2))
    }

    func testATimerThatReachesZeroStopsRunningAndAFinishedOneCantBeResumed() throws {
        let started = session.startTimer(CookState(), step: 0, totalSeconds: 5).cook
        clock.current += 5_000

        let done = try XCTUnwrap(session.tick(started))
        XCTAssertEqual(done.timers[0], StepTimer(totalSeconds: 5, remainingSeconds: 0, running: false))
        XCTAssertFalse(session.hasRunningTimers)
        XCTAssertNil(session.toggleTimer(done, step: 0))

        let alerted = try XCTUnwrap(session.alerted(done, step: 0))
        XCTAssertEqual(alerted.timers[0]?.alerted, true)
        XCTAssertNil(session.alerted(done, step: 1))
    }

    func testProgressSavesARunningTimerByItsDeadlineAndAPausedOneByWhatItHadLeft() throws {
        let running = session.startTimer(CookState(active: true, currentStep: 1), step: 1, totalSeconds: 1200).cook
        var paused = try XCTUnwrap(session.toggleTimer(session.startTimer(running, step: 2, totalSeconds: 60).cook, step: 2)).cook
        paused.ingredientsExpanded = true

        XCTAssertEqual(
            session.progress(paused),
            CookProgress(
                active: true, currentStep: 1, doneSteps: [],
                timers: [
                    1: SavedTimer(totalSeconds: 1200, remainingSeconds: 1200, endsAt: now + 1_200_000),
                    2: SavedTimer(totalSeconds: 60, remainingSeconds: 60, endsAt: nil),
                ]
            )
        )
    }

    func testSecondsLeftRoundUpAndNeverGoBelowZero() {
        XCTAssertEqual(CookSession.secondsUntil(1_001, 1_000), 1)
        XCTAssertEqual(CookSession.secondsUntil(2_000, 1_000), 1)
        XCTAssertEqual(CookSession.secondsUntil(1_000, 1_000), 0)
        XCTAssertEqual(CookSession.secondsUntil(0, 1_000), 0)
    }
}
