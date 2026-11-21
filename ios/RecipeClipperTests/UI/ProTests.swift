import XCTest
@testable import RecipeClipper

/// Pro (#413) through `DefaultProAccess` and each ViewModel it gates, over a fake store (Android's
/// `ProAccessTest`, `ProScreenTest`, `SettingsProTest`, `WeekProTest`, `WhatINeedProTest`,
/// `PlanMyWeekProTest`, `MealPlanReviewProTest` and `MealReminderProTest`). Locked, every extra is
/// still there and nothing already saved or switched on is taken away; with Pro, as before.
@MainActor
final class ProTests: XCTestCase {
    private let flagStore = MemoryFeatureFlagStore()
    private lazy var flags = FeatureFlags(store: flagStore, isDebug: false)
    private let store = FakeEntitlements()
    private lazy var pro = DefaultProAccess(flags: flags, entitlements: store)
    private let today = FakePlanCalendar.wednesday

    // MARK: - Reading Pro

    func testOnByDefaultAndLockedWithNothingBought() {
        XCTAssertTrue(FeatureFlags(store: MemoryFeatureFlagStore(), isDebug: true).isOn(.proUnlock))
        XCTAssertTrue(flags.isOn(.proUnlock))
        XCTAssertEqual(pro.state, ProState(gated: true, isPro: false))
        XCTAssertTrue(pro.state.locked)
    }

    func testACookWhoUnlockedUnlimitedRecipesUnder107IsPro() {
        // The cached entitlement of #107, as StoreKitEntitlements reads it at launch.
        let old = DefaultProAccess(flags: flags, entitlements: FakeEntitlements(UnlockState(unlocked: true)))
        XCTAssertTrue(old.state.isPro)
        XCTAssertTrue(old.state.extras)
    }

    func testDeveloperSettingsOverrideCountsAsProAndResetTakesItAway() {
        flags.setUnlockedOverride(true)
        XCTAssertTrue(pro.state.isPro)
        flags.reset()
        XCTAssertFalse(pro.state.isPro)
    }

    func testWithTheFlagOffNothingIsGated() {
        flags.set(.proUnlock, false)
        XCTAssertFalse(pro.state.gated)
        XCTAssertTrue(pro.state.extras)
    }

    func testOnlyAReminderTheCookSwitchedOnRunsWithoutPro() {
        let locked = ProState(gated: true)
        XCTAssertFalse(ProReminders.runs(on: true, chosen: false, pro: locked))
        XCTAssertTrue(ProReminders.runs(on: true, chosen: true, pro: locked))
        XCTAssertTrue(ProReminders.runs(on: true, chosen: false, pro: ProState(gated: true, isPro: true)))
        XCTAssertTrue(ProReminders.runs(on: true, chosen: false, pro: ProState()))
    }

    // MARK: - The Pro screen

    func testThePriceIsTheStoresAndThereIsNoneUntilItAnswers() {
        let vm = ProViewModel(pro: pro)
        XCTAssertNil(vm.pro.price)
        store.state.price = "3,49 €"
        XCTAssertEqual(vm.pro.price, "3,49 €")
    }

    func testUnlockingBuysOnceAndMakesThePro() async {
        let vm = ProViewModel(pro: pro)
        await vm.onUnlock()?.value
        XCTAssertEqual(store.purchases, 1)
        XCTAssertTrue(vm.pro.isPro)
        XCTAssertNil(vm.uiState.notice)
        XCTAssertFalse(vm.uiState.busy)
    }

    func testARestoreWithNothingToRestoreSaysSoAndAPendingOneWaits() async {
        let vm = ProViewModel(pro: pro)
        await vm.onRestore()?.value
        XCTAssertEqual(store.restores, 1)
        XCTAssertEqual(vm.uiState.notice, .nothingToRestore)
        store.purchaseOutcome = .pending
        await vm.onUnlock()?.value
        XCTAssertEqual(vm.uiState.notice, .pending)
        XCTAssertTrue(vm.pro.pending)
    }

    // MARK: - Settings

    private func settings(_ preferences: FakeAppPreferences = FakeAppPreferences()) -> SettingsViewModel {
        SettingsViewModel(
            preferences: preferences, backups: FakeBackupRepository(), files: FakeBackupFiles(), flags: flags,
            notificationPermission: FixedNotificationPermission(granted: true), entitlements: store, pro: pro
        )
    }

    func testTheProRowTakesTheFreeTiersPlaceEvenWithFreeTierOn() {
        flags.set(.freeTier, true)
        let vm = settings()
        XCTAssertTrue(vm.sections.contains(.pro))
        XCTAssertFalse(vm.sections.contains(.unlimited))
        XCTAssertNil(vm.unlockRow)
        XCTAssertFalse(vm.isPro)
        store.state.unlocked = true
        XCTAssertTrue(vm.isPro)
        flags.set(.proUnlock, false)
        XCTAssertFalse(vm.sections.contains(.pro))
        XCTAssertTrue(vm.sections.contains(.unlimited))
    }

    func testLockedTheDefaultEveningReminderShowsOffAndWontGoOn() async {
        let preferences = FakeAppPreferences()
        let vm = settings(preferences)
        XCTAssertTrue(vm.remindersLocked)
        XCTAssertFalse(vm.eveningReminderOn)
        XCTAssertNil(vm.onWeekReminderChange(.evening, true))
        XCTAssertNil(vm.onWeekReminderChange(.tonight, true))
        XCTAssertFalse(preferences.eveningReminderChosen)
        XCTAssertFalse(preferences.tonightReminder)
    }

    func testLockedAReminderTheCookTurnedOnStaysOnAndCanBeTurnedOff() async {
        let preferences = FakeAppPreferences(tonightReminder: true, eveningReminderChosen: true)
        let vm = settings(preferences)
        XCTAssertTrue(vm.eveningReminderOn)
        XCTAssertTrue(vm.tonightReminderOn)
        vm.onWeekReminderChange(.tonight, false)
        XCTAssertFalse(preferences.tonightReminder)
        XCTAssertNil(vm.onWeekReminderChange(.tonight, true), "off now, so on again needs Pro")
    }

    func testWithProTheDefaultIsOnAndTheSwitchesWork() async {
        store.state.unlocked = true
        let preferences = FakeAppPreferences()
        let vm = settings(preferences)
        XCTAssertFalse(vm.remindersLocked)
        XCTAssertTrue(vm.eveningReminderOn)
        await vm.onWeekReminderChange(.tonight, true)?.value
        XCTAssertTrue(preferences.tonightReminder)
    }

    // MARK: - Week: Skip and the fresh-food swap

    private func week(_ plan: FakeMealPlanRepository, _ groceries: FakeGroceryRepository) -> WeekViewModel {
        WeekViewModel(
            plan: plan, recipes: FakeRecipeRepository(), calendar: FakePlanCalendar(), sleep: immediateSleep,
            groceries: groceries, freshFood: true, pro: pro
        )
    }

    private func planWeek(_ plan: FakeMealPlanRepository, _ groceries: FakeGroceryRepository) async {
        await plan.addRecipe(recipeId: 7, day: today, mealTypeId: FakeMealPlanRepository.dinner, servings: nil)
        await plan.addRecipe(recipeId: 9, day: today + 5, mealTypeId: FakeMealPlanRepository.dinner, servings: nil)
        let (pasta, salmon) = (plan.meals.value[0], plan.meals.value[1])
        groceries.planned = [
            PlannedIngredients(entryId: pasta.id, day: today, servings: nil, recipeId: 7, title: "Pasta",
                               ingredients: ["1 lb spaghetti"], yield: nil, language: "en"),
            PlannedIngredients(entryId: salmon.id, day: today + 5, servings: nil, recipeId: 9, title: "Salmon bowls",
                               ingredients: ["2 salmon fillets"], yield: nil, language: "en")
        ]
    }

    func testLockedTheWeekNeitherSkipsNorSwapsButStillShowsBoth() async {
        let plan = FakeMealPlanRepository(), groceries = FakeGroceryRepository()
        await planWeek(plan, groceries)
        let vm = week(plan, groceries)
        await settleMain()
        let (pasta, salmon) = (plan.meals.value[0], plan.meals.value[1])
        XCTAssertTrue(vm.proLocked)
        XCTAssertTrue(vm.uiState.canSkip(pasta), "Skip is still offered, with the badge")
        XCTAssertNotNil(vm.uiState.freshNudges[salmon.id], "the note still shows")

        vm.onSkipStart(pasta)
        XCTAssertNil(vm.uiState.skipping)
        vm.onFreshSwap(salmon.id)
        await settleMain()
        XCTAssertEqual(plan.meals.value.map(\.day), [today, today + 5])
    }

    func testWithProSkipAndTheSwapWorkAsBefore() async {
        store.state.unlocked = true
        let plan = FakeMealPlanRepository(), groceries = FakeGroceryRepository()
        await planWeek(plan, groceries)
        let vm = week(plan, groceries)
        await settleMain()
        let (pasta, salmon) = (plan.meals.value[0], plan.meals.value[1])
        XCTAssertFalse(vm.proLocked)
        vm.onSkipStart(pasta)
        XCTAssertNotNil(vm.uiState.skipping)
        vm.onSkipDismissed()
        vm.onFreshSwap(salmon.id)
        await settleMain()
        XCTAssertEqual(Set(plan.meals.value.map(\.day)), [today, today + 5])
        XCTAssertEqual(plan.meals.value.first { $0.id == salmon.id }?.day, today)
    }

    // MARK: - Shop for this week: Buy later

    private func whatINeed(_ groceries: FakeGroceryRepository) async -> WhatINeedViewModel {
        let vm = WhatINeedViewModel(
            weekStart: today, groceries: groceries, pantry: FakePantryRepository(), preferences: FakeAppPreferences(),
            calendar: FakePlanCalendar(), freshFood: true, pro: pro
        )
        await vm.loading?.value
        await settleMain()
        return vm
    }

    private func shop() -> FakeGroceryRepository {
        let groceries = FakeGroceryRepository()
        groceries.planned = [
            PlannedIngredients(entryId: 1, day: today + 1, servings: nil, recipeId: 7, title: "Pasta",
                               ingredients: ["1 lb spaghetti"], yield: nil, language: "en"),
            PlannedIngredients(entryId: 2, day: today + 5, servings: nil, recipeId: 9, title: "Salmon bowls",
                               ingredients: ["2 salmon fillets"], yield: nil, language: "en")
        ]
        return groceries
    }

    func testLockedToBuyIsOneListAddedWholeWithOneLockedLine() async {
        let groceries = shop()
        let vm = await whatINeed(groceries)
        XCTAssertTrue(vm.splitLocked)
        XCTAssertNil(vm.shownTrips)
        XCTAssertTrue(vm.showsSplitTeaser)
        XCTAssertNotNil(vm.uiState.trips, "worked out, not shown")
        vm.onAddBuyToGroceries()
        await settleMain()
        XCTAssertEqual(groceries.items.value.map(\.text), ["1 lb spaghetti", "2 salmon fillets"])
    }

    func testWithProTheTwoTripsAreBack() async {
        store.state.unlocked = true
        let vm = await whatINeed(shop())
        XCTAssertNotNil(vm.shownTrips)
        XCTAssertFalse(vm.showsSplitTeaser)
        XCTAssertEqual(vm.buyNow.map(\.name), ["spaghetti"])
    }

    // MARK: - Plan my week's choices

    private func askAi() -> AskAiViewModel {
        AskAiViewModel(
            pantry: FakePantryRepository(), lists: FakeListRepository(), recipes: FakeRecipeRepository(), preferences: FakeAppPreferences(),
            clock: TestClock(now: 1_800_000_000_000), mealPlan: FakeMealPlanRepository(), choices: MemoryPlanChoices(),
            planPlus: { true }, pro: pro
        )
    }

    func testLockedPlanMyWeekIsTheFreeWeekOfDinnersWithItsChoicesLocked() async {
        let vm = askAi()
        await settleMain()
        vm.onKind(.planMyWeek)
        XCTAssertFalse(vm.uiState.plan)
        XCTAssertTrue(vm.uiState.planLocked)
        XCTAssertTrue(vm.prompt(words: .english)!.hasPrefix("Plan one dinner recipe for each of these days:"))
    }

    func testWithProTheChoicesAreBack() async {
        store.state.unlocked = true
        let vm = askAi()
        await settleMain()
        vm.onKind(.planMyWeek)
        XCTAssertTrue(vm.uiState.plan)
        XCTAssertFalse(vm.uiState.planLocked)
    }

    // MARK: - The meal-plan review's note

    private func review() -> MealPlanReviewViewModel {
        func date(_ offset: Int64) -> String {
            let c = PlanDays.civil(today + offset)
            return String(format: "%04d-%02d-%02d", c.year, c.month, c.day)
        }
        let text = "[{\"@type\": \"Recipe\", \"name\": \"Omelette\", \"day\": \"\(date(0))\", \"recipeIngredient\": [\"4 eggs\"], "
            + "\"recipeInstructions\": [\"Cook.\"]}, {\"@type\": \"Recipe\", \"name\": \"Salmon bowls\", \"day\": \"\(date(5))\", "
            + "\"recipeIngredient\": [\"2 salmon fillets\"], \"recipeInstructions\": [\"Cook.\"]}]"
        return MealPlanReviewViewModel(
            text: text, recipes: FakeRecipeRepository(), plan: FakeMealPlanRepository(), calendar: FakePlanCalendar(),
            freshFood: true, pro: pro
        )
    }

    func testLockedTheReviewsNoteStaysAndItsSwapMovesNothing() async {
        let vm = review()
        await settleMain()
        XCTAssertTrue(vm.proLocked)
        XCTAssertNotNil(vm.uiState.nudges[1])
        vm.onSwap(1)
        XCTAssertEqual(vm.uiState.items.map(\.day), [today, today + 5])
        store.state.unlocked = true
        vm.onSwap(1)
        XCTAssertEqual(vm.uiState.items.map(\.day), [today + 5, today])
    }
}

/// The evening and Tonight reminders under Pro (#397, #413; Android's `MealReminderProTest`).
@MainActor
final class MealReminderProTests: XCTestCase {
    private final class RecordingScheduler: MealReminderScheduler {
        var calls: [[ScheduledMealReminder]] = []
        func replaceAll(_ reminders: [ScheduledMealReminder]) { calls.append(reminders) }
    }

    private struct FixedClock: Clock {
        let millis: Int64
        func now() -> Int64 { millis }
    }

    private let today: Int64 = 20_000
    private let plan = FakeMealPlanRepository()
    private let recipes = FakeRecipeRepository()
    private let scheduler = RecordingScheduler()
    private let store = FakeEntitlements()
    private lazy var flags = FeatureFlags(store: MemoryFeatureFlagStore(), isDebug: true)

    private func coordinator(_ preferences: FakeAppPreferences) -> MealReminderCoordinator {
        MealReminderCoordinator(
            mealPlan: plan, recipes: recipes, preferences: preferences, flags: flags, scheduler: scheduler,
            clock: FixedClock(millis: today * PlanDays.millisPerDay + 8 * 3_600_000), zone: TimeZone(identifier: "UTC")!,
            pro: DefaultProAccess(flags: flags, entitlements: store)
        )
    }

    private func settle() async {
        for _ in 0..<5 { await Task.yield() }
        try? await Task.sleep(for: .milliseconds(80))
    }

    private func planTomorrow() {
        plan.titles[1] = "Lemon chicken"
        recipes.recipes.value[1] = Recipe(
            name: "Lemon chicken", image: nil, ingredients: ["1 lemon"], instructions: [], prepTime: nil, cookTime: nil,
            totalTime: nil, yield: nil, sourceUrl: "https://x/1", id: 1
        )
        plan.meals.value.append(PlannedMeal(
            id: 1, day: today + 1, mealTypeId: FakeMealPlanRepository.dinner, recipeId: 1, title: "Lemon chicken",
            imageUrl: nil, servings: nil, note: nil
        ))
    }

    func testWithoutProTheDefaultEveningReminderSchedulesNothing() async {
        planTomorrow()
        let coordinator = coordinator(FakeAppPreferences(eveningReminder: true))
        coordinator.start()
        await settle()
        XCTAssertEqual(scheduler.calls.last?.isEmpty, true)
    }

    func testWithoutProAReminderTheCookSwitchedOnKeepsRunning() async {
        planTomorrow()
        let coordinator = coordinator(FakeAppPreferences(eveningReminder: true, eveningReminderChosen: true))
        coordinator.start()
        await settle()
        XCTAssertEqual(scheduler.calls.last?.isEmpty, false)
    }

    func testBuyingProSchedulesTheDefaultReminderAtOnce() async {
        planTomorrow()
        let coordinator = coordinator(FakeAppPreferences(eveningReminder: true))
        coordinator.start()
        await settle()
        XCTAssertEqual(scheduler.calls.last?.isEmpty, true)
        store.state.unlocked = true
        await settle()
        XCTAssertEqual(scheduler.calls.last?.isEmpty, false)
    }
}
