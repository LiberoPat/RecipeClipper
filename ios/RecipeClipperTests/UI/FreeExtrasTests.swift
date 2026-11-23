import XCTest
@testable import RecipeClipper

/// The Week extras of #413 are free for everyone (owner, 2026-11-23: Pro is kept for future AI
/// features, nothing is gated today): Skip (#398) and the fresh-food swap (#396), Shop for this week's
/// Buy later, Plan my week's choices (#392, #367), the meal-plan review's swap and the evening and
/// Tonight reminders (#397). Android's `WeekFreeExtrasTest`, `WhatINeedFreeTest`, `PlanMyWeekFreeTest`,
/// `MealPlanReviewFreeTest` and `MealReminderFreeTest`.
@MainActor
final class FreeExtrasTests: XCTestCase {
    private let today = FakePlanCalendar.wednesday

    // MARK: - Week: Skip and the fresh-food swap

    private func week(_ plan: FakeMealPlanRepository, _ groceries: FakeGroceryRepository) -> WeekViewModel {
        WeekViewModel(
            plan: plan, recipes: FakeRecipeRepository(), calendar: FakePlanCalendar(), sleep: immediateSleep,
            groceries: groceries, freshFood: true
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

    func testSkipAndTheFreshSwapWorkForEveryone() async {
        let plan = FakeMealPlanRepository(), groceries = FakeGroceryRepository()
        await planWeek(plan, groceries)
        let vm = week(plan, groceries)
        await settleMain()
        let (pasta, salmon) = (plan.meals.value[0], plan.meals.value[1])
        XCTAssertTrue(vm.uiState.canSkip(pasta))
        XCTAssertNotNil(vm.uiState.freshNudges[salmon.id])

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
            calendar: FakePlanCalendar(), freshFood: true
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

    func testTheTwoTripsShowForEveryoneAndBuyNowIsAddedFirst() async {
        let groceries = shop()
        let vm = await whatINeed(groceries)
        XCTAssertNotNil(vm.shownTrips)
        XCTAssertEqual(vm.buyNow.map(\.name), ["spaghetti"])
        vm.onAddBuyToGroceries()
        await settleMain()
        XCTAssertEqual(groceries.items.value.map(\.text), ["1 lb spaghetti"])
        vm.onAddLaterToGroceries()
        await settleMain()
        XCTAssertEqual(groceries.items.value.map(\.text), ["1 lb spaghetti", "2 salmon fillets"])
    }

    // MARK: - Plan my week's choices

    private func askAi() -> AskAiViewModel {
        AskAiViewModel(
            pantry: FakePantryRepository(), lists: FakeListRepository(), recipes: FakeRecipeRepository(), preferences: FakeAppPreferences(),
            clock: TestClock(now: 1_800_000_000_000), mealPlan: FakeMealPlanRepository(), choices: MemoryPlanChoices(),
            planPlus: { true }
        )
    }

    func testPlanMyWeeksChoicesAreThereForEveryone() async {
        let vm = askAi()
        await settleMain()
        vm.onKind(.planMyWeek)
        XCTAssertTrue(vm.uiState.plan)
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
            freshFood: true
        )
    }

    func testTheReviewsSwapTradesTheDaysForEveryone() async {
        let vm = review()
        await settleMain()
        XCTAssertNotNil(vm.uiState.nudges[1])
        vm.onSwap(1)
        XCTAssertEqual(vm.uiState.items.map(\.day), [today + 5, today])
    }
}

/// The evening and Tonight reminders are free (#397; Android's `MealReminderFreeTest`): the evening
/// one's default "on" schedules for a cook who never touched the switch, one switched on runs, and one
/// switched off schedules nothing.
@MainActor
final class MealReminderFreeTests: XCTestCase {
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
    private lazy var flags = FeatureFlags(store: MemoryFeatureFlagStore(), isDebug: true)

    private func coordinator(_ preferences: FakeAppPreferences) -> MealReminderCoordinator {
        MealReminderCoordinator(
            mealPlan: plan, recipes: recipes, preferences: preferences, flags: flags, scheduler: scheduler,
            clock: FixedClock(millis: today * PlanDays.millisPerDay + 8 * 3_600_000), zone: TimeZone(identifier: "UTC")!
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

    func testTheDefaultEveningReminderSchedules() async {
        planTomorrow()
        let coordinator = coordinator(FakeAppPreferences(eveningReminder: true))
        coordinator.start()
        await settle()
        XCTAssertEqual(scheduler.calls.last?.isEmpty, false)
    }

    func testAReminderTheCookSwitchedOnRuns() async {
        planTomorrow()
        let coordinator = coordinator(FakeAppPreferences(eveningReminder: true, eveningReminderChosen: true))
        coordinator.start()
        await settle()
        XCTAssertEqual(scheduler.calls.last?.isEmpty, false)
    }

    func testAReminderSwitchedOffSchedulesNothing() async {
        planTomorrow()
        let coordinator = coordinator(FakeAppPreferences(eveningReminder: false, eveningReminderChosen: true))
        coordinator.start()
        await settle()
        XCTAssertEqual(scheduler.calls.last?.isEmpty, true)
    }
}
