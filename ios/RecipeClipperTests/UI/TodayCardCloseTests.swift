import XCTest
@testable import RecipeClipper

/// The Today card's close button and "Show today's meal" (#448, `todayCardClose`), through
/// `HomeViewModel` and `SettingsViewModel`. Android: `TodayCardCloseViewModelTest`, `ShowTodayCardSettingsTest`.
@MainActor
final class TodayCardCloseTests: XCTestCase {
    private let clock = TestClock(now: 0)
    private let repository = FakeRecipeRepository()
    private let plan = FakeMealPlanRepository()
    private let cooked = FakeCookedPhotoRepository()
    private let preferences = FakeAppPreferences()
    private let store = MemoryFeatureFlagStore()
    private lazy var flags = FeatureFlags(store: store, definitions: FlagRegistry.definitions, isDebug: true)
    /// The test clock's 0 is midnight UTC, and the card follows UTC's day.
    private let utc = TimeZone(identifier: "UTC")!

    override func setUp() {
        super.setUp()
        cooked.today = 0
    }

    private func dinner(day: Int64 = 0) -> PlannedMeal {
        PlannedMeal(
            id: day + 1, day: day, mealTypeId: FakeMealPlanRepository.dinner, recipeId: 12, title: "Lemon chicken",
            imageUrl: nil, servings: nil, note: nil
        )
    }

    private func home(withChoices: Bool = true) async -> HomeViewModel {
        let vm = HomeViewModel(
            repository: repository, clock: clock, sleep: clock.sleep, mealPlan: plan, cooked: cooked, zone: utc,
            preferences: withChoices ? preferences : nil, flags: withChoices ? flags : nil
        )
        await settleMain()
        return vm
    }

    func testTheCardIsClosableWithTheFlagOn() async {
        plan.meals.send([dinner()])
        let vm = await home()

        XCTAssertTrue(vm.uiState.todayClosable)
        XCTAssertEqual(vm.uiState.today?.lead.title, "Lemon chicken")
    }

    func testClosingHidesTheCardForTheRestOfTodayAndStoresTodaysDate() async {
        plan.meals.send([dinner()])
        let vm = await home()

        vm.onCloseToday()
        await settleMain()

        XCTAssertNil(vm.uiState.today)
        XCTAssertEqual(preferences.todayCardDismissedDay, 0)

        // Later the same day, still gone.
        await clock.advance(by: 20 * 60 * 60 * 1000)
        await settleMain()
        XCTAssertNil(vm.uiState.today)
    }

    func testTheCardClosedYesterdayIsBackToday() async {
        plan.meals.send([dinner(), dinner(day: 1)])
        let vm = await home()
        vm.onCloseToday()
        await settleMain()
        XCTAssertNil(vm.uiState.today)

        await clock.advance(by: PlanDays.millisPerDay)
        await settleMain()
        XCTAssertEqual(vm.uiState.today?.lead.entryId, 2, "tomorrow's dinner")
    }

    func testACloseStoredOnAnEarlierDayDoesNotHideTodaysCard() async {
        preferences.todayCardDismissedDay = -1
        plan.meals.send([dinner()])
        let vm = await home()

        XCTAssertEqual(vm.uiState.today?.lead.title, "Lemon chicken")
    }

    func testTheSettingOffHidesTheCardAndOnAgainBringsItBack() async {
        plan.meals.send([dinner()])
        let vm = await home()

        preferences.showTodayCard = false
        await settleMain()
        XCTAssertNil(vm.uiState.today)

        preferences.showTodayCard = true
        await settleMain()
        XCTAssertEqual(vm.uiState.today?.lead.title, "Lemon chicken")
    }

    func testMidCookTheResumeIsUnaffectedByTheCloseAndTheSetting() async {
        plan.meals.send([dinner()])
        repository.recent.send([testSummary(1)])
        repository.recipes.send([1: Recipe(
            name: "Recipe 1", image: nil, ingredients: ["1 egg"], instructions: ["One", "Two"],
            prepTime: nil, cookTime: nil, totalTime: nil, yield: nil, sourceUrl: "https://example.com/1", id: 1,
            cook: CookProgress(active: true, currentStep: 1)
        )])
        preferences.showTodayCard = false
        let vm = await home()
        vm.onCloseToday()
        await settleMain()

        XCTAssertEqual(vm.uiState.resume?.step, 2)
        XCTAssertEqual(vm.uiState.continueCooking?.id, 1)
    }

    func testWithTheFlagOffThereIsNoCloseAndNeitherChoiceCounts() async {
        flags.set(.todayCardClose, false)
        preferences.showTodayCard = false
        preferences.todayCardDismissedDay = 0
        plan.meals.send([dinner()])
        let vm = await home()

        XCTAssertFalse(vm.uiState.todayClosable)
        XCTAssertEqual(vm.uiState.today?.lead.title, "Lemon chicken")
    }

    func testWithoutPreferencesOrFlagsTheCardIsAsBefore() async {
        plan.meals.send([dinner()])
        let vm = await home(withChoices: false)

        XCTAssertFalse(vm.uiState.todayClosable)
        vm.onCloseToday()
        await settleMain()
        XCTAssertEqual(vm.uiState.today?.lead.title, "Lemon chicken")
    }

    // MARK: Settings' switch

    private func settings() -> SettingsViewModel {
        SettingsViewModel(preferences: preferences, backups: FakeBackupRepository(), files: FakeBackupFiles(), flags: flags)
    }

    func testTheSwitchIsOnByDefaultAndTurnsOff() {
        let vm = settings()
        XCTAssertTrue(vm.showsTodayCardSetting)
        XCTAssertTrue(vm.uiState.showTodayCard)

        vm.onShowTodayCardChange(false)
        XCTAssertFalse(preferences.showTodayCard)
        XCTAssertFalse(vm.uiState.showTodayCard)
    }

    func testTheSwitchFollowsThePreferenceChangedElsewhere() async {
        let vm = settings()
        preferences.showTodayCard = false
        await settleMain()
        XCTAssertFalse(vm.uiState.showTodayCard)
    }

    func testThePlanSectionStaysForTheSwitchAloneAndGoesWithTheFlag() {
        let vm = settings()
        flags.set(.eveningReminders, false)
        XCTAssertTrue(vm.sections.contains(.week))
        XCTAssertTrue(flags.planSettingsLink)

        flags.set(.todayCardClose, false)
        XCTAssertFalse(vm.showsTodayCardSetting)
        XCTAssertFalse(vm.sections.contains(.week))
    }
}
