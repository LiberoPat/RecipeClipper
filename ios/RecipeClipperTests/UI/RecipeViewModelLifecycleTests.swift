import Combine
import XCTest
@testable import RecipeClipper

/// A repository whose `open` and `importFromUrl` don't answer until the test says so, to
/// put a load in flight and do something else meanwhile (retry, pop the screen). The async
/// methods are main-actor so `waiting` is only ever touched on the thread the test reads it
/// from; nonisolated, they would run on the global pool and append concurrently with it.
private final class GatedRecipeRepository: RecipeRepository {
    private var waiting: [CheckedContinuation<ParseResult, Never>] = []
    private(set) var cancelledImports = 0

    var pendingCount: Int { waiting.count }

    /// Answers the oldest load still waiting.
    func answerOldest(_ result: ParseResult) {
        guard !waiting.isEmpty else { return }
        waiting.removeFirst().resume(returning: result)
    }

    @MainActor func importFromUrl(_ sharedUrl: String) async -> ParseResult {
        let result = await withCheckedContinuation { waiting.append($0) }
        if Task.isCancelled { cancelledImports += 1 }
        return result
    }

    @MainActor func open(id: Int64) async -> Recipe? {
        if case .success(let recipe) = await importFromUrl("") { return recipe }
        return nil
    }

    @MainActor func setChecked(id: Int64, checked: Set<Int>) async {}
    @MainActor func setNotes(id: Int64, notes: String) async {}
    @MainActor func delete(id: Int64) async -> DeletedRecipe? { nil }
    @MainActor func restore(_ deleted: DeletedRecipe) async {}
    func observeHistory(query: String) -> AnyPublisher<[RecipeSummary], Never> { Just([]).eraseToAnyPublisher() }
    func observeRecent(limit: Int) -> AnyPublisher<[RecipeSummary], Never> { Just([]).eraseToAnyPublisher() }
}

/// Load cancellation, the tick loop's lifetime, the ViewModel going away with its screen,
/// and cook-mode progress surviving an exit — the parts of Android's viewModelScope and
/// onCleared that iOS has to do by hand.
@MainActor
final class RecipeViewModelLifecycleTests: XCTestCase {

    private func recipe(
        name: String = "Test Recipe",
        yield: String? = "4 servings",
        ingredients: [String] = ["2 cups flour", "1 cup milk"],
        instructions: [String] = ["Wait for 5 seconds.", "Wait for 10 seconds.", "Serve."]
    ) -> Recipe {
        Recipe(
            name: name, image: nil, ingredients: ingredients, instructions: instructions,
            prepTime: nil, cookTime: nil, totalTime: nil, yield: yield,
            sourceUrl: "https://example.com/recipe", id: 1, checkedIngredients: []
        )
    }

    private func loaded(_ recipe: Recipe, clock: TestClock, preferences: FakeAppPreferences = FakeAppPreferences()) async -> RecipeViewModel {
        let repository = FakeRecipeRepository()
        repository.openResult = recipe
        let vm = RecipeViewModel(
            recipeId: recipe.id, url: nil, repository: repository, preferences: preferences,
            clock: clock, sleep: clock.sleep
        )
        await settleMain()
        return vm
    }

    // MARK: Loading

    func testARetryWinsOverTheLoadItReplacedEvenWhenTheOldOneAnswersLast() async {
        let repository = GatedRecipeRepository()
        let vm = RecipeViewModel(
            recipeId: nil, url: "https://example.com/a", repository: repository,
            preferences: FakeAppPreferences(), clock: TestClock()
        )
        await settleMain()
        vm.onRetry()
        await settleMain()
        XCTAssertEqual(repository.pendingCount, 2)

        // The replaced load answers first, with something else; then the retry answers.
        repository.answerOldest(.success(recipe(name: "Stale")))
        await settleMain()
        XCTAssertEqual(vm.uiState.content, .loading, "a cancelled load must not show its result")
        repository.answerOldest(.success(recipe(name: "Fresh")))
        await settleMain()

        XCTAssertEqual(vm.uiState.content.success?.recipe.name, "Fresh")
    }

    func testPoppingTheScreenMidImportReleasesTheViewModelAndCancelsTheImport() async {
        let repository = GatedRecipeRepository()
        var vm: RecipeViewModel? = RecipeViewModel(
            recipeId: nil, url: "https://example.com/a", repository: repository,
            preferences: FakeAppPreferences(), clock: TestClock()
        )
        weak var weakVm = vm
        await settleMain()
        XCTAssertEqual(repository.pendingCount, 1)

        vm = nil // the screen is popped (ScreenHost's state goes)

        XCTAssertNil(weakVm, "an in-flight import must not keep the ViewModel alive")
        repository.answerOldest(.success(recipe()))
        await settleMain()
        XCTAssertEqual(repository.cancelledImports, 1, "Android's onCleared cancels the load")
    }

    // MARK: The tick loop

    func testTheTickLoopStopsOnceNoTimerIsRunning() async {
        let clock = TestClock()
        let vm = await loaded(recipe(), clock: clock)

        vm.onTimerStart(0)
        XCTAssertTrue(vm.isTicking)
        await clock.advance(by: 1_000)
        vm.onTimerToggle(0) // pause: nothing left running
        await clock.advance(by: 1_000)
        XCTAssertFalse(vm.isTicking)
        XCTAssertFalse(clock.hasSleepers)

        vm.onTimerToggle(0) // resume starts it again
        XCTAssertTrue(vm.isTicking)
        vm.onTimerReset(0)
        await clock.advance(by: 1_000)
        XCTAssertFalse(vm.isTicking)

        vm.onTimerStart(1)
        await clock.runUntilIdle() // finishing ends it too
        XCTAssertFalse(vm.isTicking)
        XCTAssertEqual(vm.uiState.cook.timers[1]?.finished, true)
    }

    func testPausingAndResumingInsideOneTickKeepsASingleLoop() async {
        let clock = TestClock()
        let vm = await loaded(recipe(), clock: clock)

        vm.onTimerStart(0)
        await clock.advance(by: 100)
        vm.onTimerToggle(0)
        vm.onTimerToggle(0) // resumed before the loop woke to notice the pause
        await clock.advance(by: 2_000)

        XCTAssertEqual(vm.uiState.cook.timers[0]?.remainingSeconds, 4) // 3.1s left at the last tick
        XCTAssertEqual(vm.uiState.cook.timers[0]?.running, true)
    }

    func testAScreenPoppedWithATimerRunningLetsItsViewModelGo() async {
        let clock = TestClock()
        var vm: RecipeViewModel? = await loaded(recipe(), clock: clock)
        weak var weakVm = vm
        vm?.onTimerStart(1)
        await clock.advance(by: 1_000)

        vm = nil

        XCTAssertNil(weakVm, "the tick loop must not hold the ViewModel")
        await clock.advance(by: 1_000)
        XCTAssertFalse(clock.hasSleepers, "and the loop ends with it")
    }

    // MARK: Leaving cook mode

    func testLeavingCookModeKeepsProgressAndRunningTimers() async {
        let clock = TestClock()
        let vm = await loaded(recipe(), clock: clock)
        vm.onCookStart()
        vm.onTimerStart(0)
        vm.onStepDone()

        vm.onCookExit()
        await clock.advance(by: 2_000)

        // The timer kept counting and still finishes (and so still alerts) out of cook mode.
        XCTAssertEqual(vm.uiState.cook.timers[0]?.remainingSeconds, 3)
        await clock.runUntilIdle()
        XCTAssertEqual(vm.uiState.cook.timers[0]?.finished, true)
        XCTAssertEqual(TimerAlarm.pending(vm.uiState.cook.timers), [0])
        XCTAssertFalse(vm.uiState.cook.active)

        vm.onCookStart()
        XCTAssertEqual(vm.uiState.cook.currentStep, 1)
        XCTAssertEqual(vm.uiState.cook.doneSteps, [0])
        XCTAssertNotNil(vm.uiState.cook.timers[0])
    }

    func testForceDarkEndsWithCookMode() async {
        let preferences = FakeAppPreferences()
        preferences.darkWhileCooking = true
        let vm = await loaded(recipe(), clock: TestClock(), preferences: preferences)

        vm.onCookStart()
        XCTAssertTrue(vm.uiState.forceDark)
        vm.onCookExit()
        XCTAssertFalse(vm.uiState.forceDark)
        vm.onCookStart()
        for _ in 0 ..< 3 { vm.onStepDone() } // finishing the last step leaves cook mode too
        XCTAssertFalse(vm.uiState.forceDark)
    }

    // MARK: Share

    func testShareTextCarriesTheConvertedOvenTemperature() async {
        let preferences = FakeAppPreferences()
        preferences.temperatureUnit = .celsius
        let vm = await loaded(recipe(instructions: ["Bake at 350°F for 20 minutes."]), clock: TestClock(), preferences: preferences)
        vm.onServingsChange(8)

        let text = vm.shareText() ?? ""
        XCTAssertTrue(text.contains("180°C"), text)
        XCTAssertFalse(text.contains("350"), text)
        XCTAssertTrue(text.contains("4 cups flour"), text)
    }
}

/// The alarm's pieces that can be checked without a speaker.
@MainActor
final class TimerAlarmTests: XCTestCase {
    func testOnlyFinishedUnannouncedTimersArePending() {
        let timers: [Int: StepTimer] = [
            0: StepTimer(totalSeconds: 5, remainingSeconds: 0, running: false),
            1: StepTimer(totalSeconds: 5, remainingSeconds: 0, running: false, alerted: true),
            2: StepTimer(totalSeconds: 5, remainingSeconds: 3, running: true),
            3: StepTimer(totalSeconds: 5, remainingSeconds: 3, running: false) // paused
        ]
        XCTAssertEqual(TimerAlarm.pending(timers), [0])
    }

    func testTheSynthesisedBeepIsAPlayableWav() throws {
        let wav = TimerAlarm.beepWav
        XCTAssertEqual(String(decoding: wav.prefix(4), as: UTF8.self), "RIFF")
        XCTAssertEqual(wav.count, 44 + 44_100 * 300 / 1000 * 2)
        XCTAssertNoThrow(try AVAudioPlayerProbe.duration(of: wav))
        XCTAssertEqual(try AVAudioPlayerProbe.duration(of: wav), 0.3, accuracy: 0.01)
    }

    // Counted, not timed. These used to time a cancelled `play()` against 0.5s, which mostly
    // measured the audio session switching on and off: 0.57s with builds running alongside,
    // 16s across a Mac sleep.

    func testAnUncancelledAlarmPlaysEveryBeep() async {
        let beeps = BeepCounter()
        await TimerAlarm.beeps(sleep: { _ in }) { beeps.count += 1 }
        XCTAssertEqual(beeps.count, TimerAlarm.beepCount)
    }

    func testACancelledAlarmStopsWithoutPlayingTheRest() async {
        let beeps = BeepCounter()
        let task = Task {
            await TimerAlarm.beeps(sleep: { _ in try await Task.sleep(for: .seconds(60)) }) { beeps.count += 1 }
        }
        await settleMain()
        XCTAssertEqual(beeps.count, 1, "the first beep, then waiting")

        task.cancel()
        await task.value

        XCTAssertEqual(beeps.count, 1)
    }

    func testAnAlarmCancelledBeforeItStartsPlaysNothing() async {
        let beeps = BeepCounter()
        let task = Task { await TimerAlarm.beeps(sleep: { _ in }) { beeps.count += 1 } }
        task.cancel() // before it runs: the test holds the main actor until the await
        await task.value
        XCTAssertEqual(beeps.count, 0)
    }
}

@MainActor
private final class BeepCounter {
    var count = 0
}

import AVFoundation
private enum AVAudioPlayerProbe {
    static func duration(of data: Data) throws -> TimeInterval {
        try AVAudioPlayer(data: data).duration
    }
}

/// The screen stays awake exactly while some cook view is showing and the app is active.
@MainActor
final class KeepScreenOnTests: XCTestCase {
    override func tearDown() async throws {
        KeepScreenOn.reset()
    }

    func testOverlappingAppearAndDisappearLeaveTheScreenAwake() {
        KeepScreenOn.reset()
        KeepScreenOn.acquire()          // upper recipe screen, cooking
        KeepScreenOn.acquire()          // pop: the lower cook view appears first...
        KeepScreenOn.release()          // ...then the upper one disappears
        XCTAssertTrue(KeepScreenOn.wanted)
        XCTAssertTrue(UIApplication.shared.isIdleTimerDisabled)

        KeepScreenOn.release()          // leaving cook mode
        XCTAssertFalse(KeepScreenOn.wanted)
        XCTAssertFalse(UIApplication.shared.isIdleTimerDisabled)
    }

    func testBackgroundingReleasesTheIdleTimerAndReturningTakesItBack() {
        KeepScreenOn.reset()
        KeepScreenOn.acquire()
        KeepScreenOn.setAppActive(false)
        XCTAssertFalse(UIApplication.shared.isIdleTimerDisabled)
        KeepScreenOn.setAppActive(true)
        XCTAssertTrue(UIApplication.shared.isIdleTimerDisabled)
    }

    func testAnUnbalancedReleaseCannotGoNegative() {
        KeepScreenOn.reset()
        KeepScreenOn.release()
        KeepScreenOn.acquire()
        XCTAssertTrue(KeepScreenOn.wanted)
    }
}
