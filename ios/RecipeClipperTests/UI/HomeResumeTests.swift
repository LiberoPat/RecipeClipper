import XCTest
@testable import RecipeClipper

/// Home's resume card (#286): the step stopped on and a running timer, only while it's true,
/// followed as the cook's progress is saved. Port of the resume tests in Android's
/// `HomeViewModelTest`.
@MainActor
final class HomeResumeTests: XCTestCase {
    private let clock = TestClock(now: 0)
    private let repository = FakeRecipeRepository()

    private func recipe(_ id: Int64, _ cook: CookProgress) -> Recipe {
        Recipe(
            name: "Recipe \(id)", image: nil, ingredients: ["1 egg"], instructions: (1 ... 4).map { "Step \($0)" },
            prepTime: nil, cookTime: nil, totalTime: nil, yield: nil, sourceUrl: "https://example.com/\(id)",
            id: id, cook: cook
        )
    }

    /// Recipe 1 is Continue cooking, saved with `cook`; the clock is the test's.
    private func cooking(_ cook: CookProgress) async -> HomeViewModel {
        repository.recent.send([testSummary(1), testSummary(2)])
        repository.recipes.send([1: recipe(1, cook)])
        let vm = HomeViewModel(repository: repository, clock: clock, sleep: clock.sleep)
        await settleMain()
        return vm
    }

    func testARecipeThatIsNotBeingCookedHasNoPlaceToResume() async {
        let vm = await cooking(CookProgress(currentStep: 2, doneSteps: [0, 1]))

        XCTAssertEqual(vm.uiState.continueCooking?.id, 1)
        XCTAssertNil(vm.uiState.resume)
    }

    func testCookModeLeftOnResumesAtItsStepWithNoTimer() async {
        let vm = await cooking(CookProgress(active: true, currentStep: 1, doneSteps: [0]))

        XCTAssertEqual(vm.uiState.resume, CookResume(recipeId: 1, step: 2, stepCount: 4, timerSecondsLeft: nil))
        XCTAssertFalse(vm.isTicking)
    }

    func testARunningTimerCountsDownFromItsDeadlineThenGoes() async {
        let timer = SavedTimer(totalSeconds: 600, remainingSeconds: 600, endsAt: 90_000)
        let vm = await cooking(CookProgress(active: true, currentStep: 1, timers: [1: timer]))
        XCTAssertEqual(vm.uiState.resume?.timerSecondsLeft, 90)

        await clock.advance(by: 30_000)
        XCTAssertEqual(vm.uiState.resume?.timerSecondsLeft, 60)

        await clock.advance(by: 61_000)
        XCTAssertEqual(vm.uiState.resume, CookResume(recipeId: 1, step: 2, stepCount: 4, timerSecondsLeft: nil))
        XCTAssertFalse(vm.isTicking, "the loop ends with the last running timer")
        XCTAssertTrue(repository.setCookProgressCalls.isEmpty, "nothing is written")
    }

    func testATimerStillRunningOutOfCookModeIsCookingToo() async {
        let timer = SavedTimer(totalSeconds: 600, remainingSeconds: 600, endsAt: 120_000)
        let vm = await cooking(CookProgress(active: false, currentStep: 3, timers: [3: timer]))

        XCTAssertEqual(vm.uiState.resume, CookResume(recipeId: 1, step: 4, stepCount: 4, timerSecondsLeft: 120))
    }

    func testTheCardFollowsTheCooksProgressAsItIsSaved() async {
        let vm = await cooking(CookProgress())
        XCTAssertNil(vm.uiState.resume)

        repository.recipes.send([1: recipe(1, CookProgress(active: true, currentStep: 2))])
        await settleMain()

        XCTAssertEqual(vm.uiState.resume?.step, 3)
    }

    func testANewerRecipeNeverShowsTheLastOnesPlace() async {
        let vm = await cooking(CookProgress(active: true))
        XCTAssertEqual(vm.uiState.resume?.recipeId, 1)

        repository.recent.send([testSummary(2), testSummary(1)])
        await settleMain()

        XCTAssertEqual(vm.uiState.continueCooking?.id, 2)
        XCTAssertNil(vm.uiState.resume)
    }
}
