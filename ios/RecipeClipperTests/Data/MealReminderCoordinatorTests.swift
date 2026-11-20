import XCTest
@testable import RecipeClipper

/// Which reminders are scheduled (#397; Android's MealReminderCoordinatorTest): everything is
/// replaced on every change to the plan, the settings, the flag, and coming back to the front.
@MainActor
final class MealReminderCoordinatorTests: XCTestCase {
    private final class RecordingScheduler: MealReminderScheduler {
        var calls: [[ScheduledMealReminder]] = []
        func replaceAll(_ reminders: [ScheduledMealReminder]) { calls.append(reminders) }
    }

    private struct FixedClock: Clock {
        let millis: Int64
        func now() -> Int64 { millis }
    }

    private final class MutableClock: Clock {
        var millis: Int64
        init(_ millis: Int64) { self.millis = millis }
        func now() -> Int64 { millis }
    }

    private let today: Int64 = 20_000
    private let plan = FakeMealPlanRepository()
    private let recipes = FakeRecipeRepository()
    private let preferences = FakeAppPreferences(eveningReminder: true, tonightReminder: false)
    private let store = MemoryFeatureFlagStore()
    private let scheduler = RecordingScheduler()
    private lazy var flags = FeatureFlags(store: store, isDebug: true)
    private lazy var clock = MutableClock(today * PlanDays.millisPerDay + 8 * 3_600_000)

    private func coordinator() -> MealReminderCoordinator {
        MealReminderCoordinator(
            mealPlan: plan, recipes: recipes, preferences: preferences, flags: flags, scheduler: scheduler,
            clock: clock, zone: TimeZone(identifier: "UTC")!
        )
    }

    private func settle() async {
        for _ in 0..<5 { await Task.yield() }
        try? await Task.sleep(for: .milliseconds(80))
    }

    private func planRecipe(_ id: Int64, _ title: String, day: Int64, type: Int64 = FakeMealPlanRepository.dinner, steps: [String] = [], language: String = "en") {
        plan.titles[id] = title
        recipes.recipes.value[id] = Recipe(
            name: title, image: nil, ingredients: ["1 thing"], instructions: steps, prepTime: nil, cookTime: nil,
            totalTime: nil, yield: nil, sourceUrl: "https://x/\(id)", id: id, language: language
        )
        plan.meals.value.append(PlannedMeal(
            id: id, day: day, mealTypeId: type, recipeId: id, title: title, imageUrl: nil, servings: nil, note: nil
        ))
    }

    func testPlansTheEveningRemindersOnStartAtTheCooksTime() async {
        planRecipe(1, "Lemon chicken", day: today + 1)
        let coordinator = coordinator()
        coordinator.start()
        await settle()
        let last = scheduler.calls.last
        XCTAssertEqual(last?.map(\.reminder.day), [today])
        XCTAssertEqual(last?.map(\.reminder.titles), [["Lemon chicken"]])
        XCTAssertEqual(last?.map(\.minute), [19 * 60])
    }

    func testAChangeToThePlanPlansAgain() async {
        let coordinator = coordinator()
        coordinator.start()
        await settle()
        XCTAssertEqual(scheduler.calls.last, [])

        planRecipe(1, "Lemon chicken", day: today + 1)
        await settle()
        XCTAssertEqual(scheduler.calls.last?.count, 1)

        // Moved away: the reminder goes with it.
        await plan.move(entryId: 1, day: today + 4, mealTypeId: FakeMealPlanRepository.dinner)
        await settle()
        XCTAssertEqual(scheduler.calls.last?.map(\.reminder.day), [today + 3])

        _ = await plan.delete(entryId: 1)
        await settle()
        XCTAssertEqual(scheduler.calls.last, [])
        withExtendedLifetime(coordinator) {}
    }

    func testSwitchingTheEveningReminderOffCancelsAndOnPlansAgain() async {
        planRecipe(1, "Lemon chicken", day: today + 1)
        let coordinator = coordinator()
        coordinator.start()
        await settle()
        preferences.eveningReminder = false
        await settle()
        XCTAssertEqual(scheduler.calls.last, [])

        preferences.eveningReminder = true
        await settle()
        XCTAssertEqual(scheduler.calls.last?.count, 1)
        withExtendedLifetime(coordinator) {}
    }

    func testChangingTheTimeMovesTheReminders() async {
        planRecipe(1, "Lemon chicken", day: today + 1)
        let coordinator = coordinator()
        coordinator.start()
        await settle()
        preferences.eveningReminderMinute = 20 * 60
        await settle()
        XCTAssertEqual(scheduler.calls.last?.map(\.minute), [20 * 60])
        withExtendedLifetime(coordinator) {}
    }

    func testAfterTheTimeTodaysReminderIsLeftOut() async {
        planRecipe(1, "Lemon chicken", day: today + 1)
        planRecipe(2, "Porridge", day: today + 2, type: FakeMealPlanRepository.breakfast)
        clock.millis = today * PlanDays.millisPerDay + 19 * 3_600_000
        let coordinator = coordinator()
        coordinator.start()
        await settle()
        XCTAssertEqual(scheduler.calls.last?.map(\.reminder.day), [today + 1])
        withExtendedLifetime(coordinator) {}
    }

    func testTonightIsPlannedOnlyWhenItsSwitchIsOnAtItsOwnTime() async {
        planRecipe(1, "Lemon chicken", day: today)
        planRecipe(2, "Porridge", day: today, type: FakeMealPlanRepository.breakfast)
        let coordinator = coordinator()
        coordinator.start()
        await settle()
        XCTAssertEqual(scheduler.calls.last, [])

        preferences.tonightReminder = true
        preferences.tonightReminderMinute = 17 * 60 + 30
        await settle()
        let tonight = scheduler.calls.last
        XCTAssertEqual(tonight?.map(\.reminder.kind), [.tonight])
        XCTAssertEqual(tonight?.map(\.reminder.titles), [["Lemon chicken"]])
        XCTAssertEqual(tonight?.map(\.minute), [17 * 60 + 30])
        withExtendedLifetime(coordinator) {}
    }

    func testAPlannedRecipesAheadStepIsQuotedInItsOwnLanguage() async {
        planRecipe(1, "Lemon chicken", day: today + 1, steps: ["Mix.", "Marinate at least 8 hours or overnight."])
        // An English phrase read as German is no ahead step, and the reverse.
        planRecipe(2, "Hähnchen", day: today + 1, steps: ["Marinate overnight."], language: "de")
        let coordinator = coordinator()
        coordinator.start()
        await settle()
        let reminder = scheduler.calls.last?.first?.reminder
        XCTAssertEqual(reminder?.ahead.map(\.title), ["Lemon chicken"])
        XCTAssertEqual(reminder?.ahead.map(\.quote), ["Marinate at least 8 hours or overnight."])
        XCTAssertEqual(reminder?.recipeId, 1)
        withExtendedLifetime(coordinator) {}
    }

    func testTheFlagOffSchedulesNothingAndOnPlansAgain() async {
        planRecipe(1, "Lemon chicken", day: today + 1)
        let coordinator = coordinator()
        coordinator.start()
        await settle()
        XCTAssertEqual(scheduler.calls.last?.count, 1)

        flags.set(.eveningReminders, false)
        await settle()
        XCTAssertEqual(scheduler.calls.last, [])

        flags.set(.eveningReminders, true)
        await settle()
        XCTAssertEqual(scheduler.calls.last?.count, 1)
        withExtendedLifetime(coordinator) {}
    }

    func testComingBackToTheForegroundPlansFromTheNewDay() async {
        planRecipe(1, "Lemon chicken", day: today + 1)
        planRecipe(2, "Soup", day: today + 2)
        let coordinator = coordinator()
        coordinator.start()
        await settle()
        XCTAssertEqual(scheduler.calls.last?.map(\.reminder.day), [today, today + 1])

        // Next morning: yesterday's reminder is gone, and the plan is read from the new today.
        clock.millis = (today + 1) * PlanDays.millisPerDay + 8 * 3_600_000
        coordinator.refresh()
        await settle()
        XCTAssertEqual(scheduler.calls.last?.map(\.reminder.day), [today + 1])
        XCTAssertEqual(plan.observedRanges.last?.lowerBound, today + 1)
    }

    func testATapOpensTheFirstRecipeOnTheWeekStackOrTheWeekTab() {
        let with = MealReminder(kind: .evening, day: today, titles: ["A"], ahead: [], recipeId: 7)
        let info = NotificationMealReminderScheduler.userInfo(with)
        XCTAssertEqual((info[NotificationRouter.openWeekRecipeKey] as? NSNumber)?.int64Value, 7)

        let notes = MealReminder(kind: .evening, day: today, titles: ["A"], ahead: [], recipeId: nil)
        XCTAssertEqual(NotificationMealReminderScheduler.userInfo(notes)[NotificationRouter.openTabKey] as? String, "week")
    }

    func testTheRouterLandsInTheWeekOnItsOwnStack() {
        let router = Router()
        router.selectedTab = .pantry
        router.openInWeek(recipe: 5)
        XCTAssertEqual(router.selectedTab, .week)
        XCTAssertEqual(router.weekPath, [.weekRecipe(id: 5, servings: nil)])
        // Already on top: not pushed twice.
        router.openInWeek(recipe: 5)
        XCTAssertEqual(router.weekPath.count, 1)
        router.openInWeek(recipe: 6)
        XCTAssertEqual(router.weekPath.count, 2)
    }
}
