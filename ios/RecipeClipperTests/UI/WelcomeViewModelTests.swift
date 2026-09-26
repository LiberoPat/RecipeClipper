import XCTest
@testable import RecipeClipper

/// Port of Android's WelcomeViewModelTest and TipsTest: the welcome's cards and ways out, and
/// the one-time tips (#151).
@MainActor
final class WelcomeViewModelTests: XCTestCase {
    private var preferences: MemoryTourPreferences!
    private var recipes: FakeRecipeRepository!
    private var flags: FeatureFlags!

    override func setUp() {
        preferences = MemoryTourPreferences(welcome: .pending, sampleAdded: false, seen: [])
        recipes = FakeRecipeRepository()
        flags = FeatureFlags(store: MemoryFeatureFlagStore(), definitions: FlagRegistry.definitions, isDebug: false)
    }

    private func viewModel(again: Bool = false) -> WelcomeViewModel {
        WelcomeViewModel(
            tour: FirstRunTour(preferences: preferences, recipes: recipes), flags: flags, again: again,
            language: { "en" }
        )
    }

    func testFourCardsWithTheMealPlanOnAndChefModeNamedWithItsFlag() {
        flags.set(.mealPlan, true)
        flags.set(.chefMode, true)
        let state = viewModel().uiState
        XCTAssertEqual(state.cards, [.app, .clip, .daily, .weekly])
        XCTAssertTrue(state.chefMode)
    }

    func testAFlagThatIsOffHidesItsCardAndItsLine() {
        flags.set(.mealPlan, false)
        flags.set(.chefMode, false)
        let state = viewModel().uiState
        XCTAssertEqual(state.cards, [.app, .clip, .daily])
        XCTAssertFalse(state.chefMode)
    }

    func testNextAndBackMoveThroughTheCardsNeverPastEitherEnd() {
        flags.set(.mealPlan, false)
        let vm = viewModel()
        vm.onPrevious()
        XCTAssertEqual(vm.uiState.page, 0)
        for _ in 0..<5 { vm.onNext() }
        XCTAssertEqual(vm.uiState.page, 2)
        XCTAssertTrue(vm.uiState.isLast)
        vm.onPrevious()
        XCTAssertEqual(vm.uiState.card, .clip)
    }

    func testShowingItAddsTheSampleTheFirstTime() async {
        let vm = viewModel()
        await vm.seeding?.value
        XCTAssertEqual(recipes.addSampleCalls.count, 1)
        XCTAssertTrue(preferences.sampleAdded)
    }

    func testSkipAndStartMarkItSeenAndLeave() {
        let vm = viewModel()
        vm.onDone()
        XCTAssertEqual(preferences.welcome, .seen)
        XCTAssertEqual(vm.uiState.exit, .done)
    }

    func testTryItOpensTheSampleAndMarksTheWelcomeSeen() async {
        let vm = viewModel()
        await vm.onTrySample()?.value
        XCTAssertEqual(vm.uiState.exit, .openRecipe(99))
        XCTAssertEqual(preferences.welcome, .seen)
        XCTAssertEqual(recipes.addSampleCalls.count, 1, "the sample there is opened, not added again")
    }

    func testFromSettingsTheDeletedSampleComesBackWithTryItAndEveryTipShowsAgain() async {
        preferences.welcome = .seen
        preferences.sampleAdded = true
        for tip in Tip.allCases { preferences.setTipSeen(tip, true) }

        let vm = viewModel(again: true)
        await vm.seeding?.value
        XCTAssertTrue(recipes.addSampleCalls.isEmpty, "no sample added by itself")
        XCTAssertEqual(preferences.seenTips, [])

        await vm.onTrySample()?.value
        XCTAssertEqual(recipes.addSampleCalls.count, 1)
        XCTAssertEqual(vm.uiState.exit, .openRecipe(99))
    }

    func testIfTheSampleCantBeSavedTryItStillLeaves() async {
        recipes.addSampleResult = nil
        let vm = viewModel()
        await vm.onTrySample()?.value
        XCTAssertEqual(vm.uiState.exit, .done)
        XCTAssertEqual(preferences.welcome, .seen)
    }

    // MARK: Tips

    func testATipShowsUntilDismissedAndThenNeverAgain() {
        let tips = TipsViewModel(preferences: preferences, flags: flags)
        XCTAssertTrue(tips.shows(.recipe))
        tips.onDismiss(.recipe)
        XCTAssertFalse(tips.shows(.recipe))
        XCTAssertEqual(preferences.seenTips, [.recipe])
        XCTAssertFalse(TipsViewModel(preferences: preferences, flags: flags).shows(.recipe))
    }

    func testTheMealPlanTipsHideWhileItsFlagIsOff() {
        flags.set(.mealPlan, false)
        let tips = TipsViewModel(preferences: preferences, flags: flags)
        XCTAssertFalse(tips.shows(.week))
        XCTAssertFalse(tips.shows(.groceries))
        XCTAssertFalse(tips.shows(.pantry))
        XCTAssertTrue(tips.shows(.cookMode))

        flags.set(.mealPlan, true)
        XCTAssertTrue(tips.shows(.week))
    }

    func testShowingTheTourAgainReachesATipsViewModelAlreadyOpen() async {
        flags.set(.mealPlan, true)
        let tips = TipsViewModel(preferences: preferences, flags: flags)
        tips.onDismiss(.week)
        preferences.setTipSeen(.week, false)
        await settleMain { tips.shows(.week) }
        XCTAssertTrue(tips.shows(.week))
    }
}
