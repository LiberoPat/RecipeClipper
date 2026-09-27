import Combine
import XCTest
@testable import RecipeClipper

/// Android's ExpiryRemindersTest (#52): when the morning reminders fall and what they list.
final class ExpiryRemindersTests: XCTestCase {
    private let today: Int64 = 20_000
    private let early = 8 * 60 + 59 // 8:59
    private let late = 9 * 60      // 9:00: today's reminder has gone
    private var nextId: Int64 = 1

    private func item(_ name: String, _ expires: Int64?, inStock: Bool = true, alwaysHave: Bool = false) -> PantryItem {
        defer { nextId += 1 }
        return PantryItem(
            id: nextId, name: name, quantity: nil, language: "en", aisle: .other, inStock: inStock,
            alwaysHave: alwaysHave, purchasedDay: nil, expiresDay: expires
        )
    }

    func testAnItemIsAnnouncedTheMorningBeforeAndTheMorningOfItsDate() {
        let plan = ExpiryReminders.plan([item("milk", today + 3)], today: today, minuteOfDay: early)
        XCTAssertEqual(plan, [
            ExpiryReminder(day: today + 2, today: [], tomorrow: ["milk"]),
            ExpiryReminder(day: today + 3, today: ["milk"], tomorrow: [])
        ])
        XCTAssertEqual(plan[0].text, .tomorrow)
        XCTAssertEqual(plan[1].text, .today)
    }

    func testItemsOnTheSameDayShareOneReminderAToZ() {
        let plan = ExpiryReminders.plan([item("yogurt", today + 2), item("Milk", today + 2)], today: today, minuteOfDay: early)
        XCTAssertEqual(plan.map(\.day), [today + 1, today + 2])
        XCTAssertEqual(plan[0].tomorrow, ["Milk", "yogurt"])
    }

    func testOneMorningCanSayWhatExpiresTodayAndTomorrow() throws {
        let reminder = try XCTUnwrap(ExpiryReminders.forDay([item("milk", today), item("eggs", today + 1)], day: today))
        XCTAssertEqual(reminder.today, ["milk"])
        XCTAssertEqual(reminder.tomorrow, ["eggs"])
        XCTAssertEqual(reminder.text, .todayAndTomorrow)
    }

    func testTodaysReminderIsPlannedOnlyBeforeNine() {
        let items = [item("milk", today + 1)]
        XCTAssertEqual(ExpiryReminders.plan(items, today: today, minuteOfDay: early).map(\.day), [today, today + 1])
        XCTAssertEqual(ExpiryReminders.plan(items, today: today, minuteOfDay: late).map(\.day), [today + 1])
    }

    func testOutOfStockStaplesUndatedAndExpiredItemsAreNeverAnnounced() {
        let items = [
            item("milk", today + 1, inStock: false),
            item("salt", today + 1, alwaysHave: true),
            item("flour", nil),
            item("cream", today - 1)
        ]
        XCTAssertEqual(ExpiryReminders.plan(items, today: today, minuteOfDay: early), [])
        XCTAssertNil(ExpiryReminders.forDay(items, day: today))
    }

    func testTheSameNameTwiceIsListedOnce() {
        XCTAssertEqual(ExpiryReminders.forDay([item("milk", today), item("milk", today)], day: today)?.today, ["milk"])
    }

    func testThePlanIsCapped() {
        let items = (1...100).map { item("item \($0)", today + Int64($0) * 3) }
        XCTAssertEqual(ExpiryReminders.plan(items, today: today, minuteOfDay: early).count, ExpiryReminders.maxReminders)
    }

    func testNamesJoinWithCommasAndTheTranslatedAnd() {
        let and = { (a: String, b: String) in "\(a) and \(b)" }
        XCTAssertEqual(ExpiryReminders.joinNames([], and: and), "")
        XCTAssertEqual(ExpiryReminders.joinNames(["milk"], and: and), "milk")
        XCTAssertEqual(ExpiryReminders.joinNames(["milk", "eggs"], and: and), "milk and eggs")
        XCTAssertEqual(ExpiryReminders.joinNames(["milk", "eggs", "yogurt"], and: and), "milk, eggs and yogurt")
    }

    func testASentenceOpensWithACapital() {
        XCTAssertEqual(ExpiryReminders.capitalized("milk expires today.", locale: Locale(identifier: "en")), "Milk expires today.")
        XCTAssertEqual(ExpiryReminders.capitalized("émincé", locale: Locale(identifier: "fr")), "Émincé")
    }

    func testTheNotificationSaysWhatExpires() {
        XCTAssertEqual(
            Strings.expiryNotificationBody(ExpiryReminder(day: today, today: [], tomorrow: ["milk", "yogurt"])),
            "Milk and yogurt expire tomorrow."
        )
        XCTAssertEqual(
            Strings.expiryNotificationBody(ExpiryReminder(day: today, today: ["milk"], tomorrow: ["eggs"])),
            "Milk expires today. Eggs expires tomorrow."
        )
    }
}

/// The coordinator (#52): replans on every change to the pantry, the setting or the flag.
@MainActor
final class ExpiryReminderCoordinatorTests: XCTestCase {
    private final class RecordingScheduler: ExpiryReminderScheduler {
        var calls: [[ExpiryReminder]] = []
        func replaceAll(_ reminders: [ExpiryReminder]) { calls.append(reminders) }
    }

    private struct FixedClock: Clock {
        let millis: Int64
        func now() -> Int64 { millis }
    }

    private let today: Int64 = 20_000

    private func settle() async {
        for _ in 0..<5 { await Task.yield() }
        try? await Task.sleep(for: .milliseconds(50))
    }

    func testPlansOnStartAndOnEveryChange() async {
        let pantry = FakePantryRepository([
            PantryItem(id: 1, name: "milk", quantity: nil, language: "en", aisle: .dairy, inStock: true,
                       alwaysHave: false, purchasedDay: nil, expiresDay: today + 1)
        ])
        let preferences = FakeAppPreferences(expiryReminders: true)
        let flags = FeatureFlags(store: MemoryFeatureFlagStore(), definitions: [
            FlagDefinition(key: "mealPlan", description: "The meal plan", defaults: .init(debug: false, release: false), issue: 47)
        ], isDebug: true)
        flags.set(.mealPlan, true)
        let scheduler = RecordingScheduler()
        let coordinator = ExpiryReminderCoordinator(
            pantry: pantry, preferences: preferences, flags: flags, scheduler: scheduler,
            clock: FixedClock(millis: today * PlanDays.millisPerDay + 8 * 3_600_000), zone: TimeZone(identifier: "UTC")!
        )
        coordinator.start()
        await settle()
        XCTAssertEqual(scheduler.calls.last?.map(\.day), [today, today + 1])

        preferences.expiryReminders = false
        await settle()
        XCTAssertEqual(scheduler.calls.last, [])

        preferences.expiryReminders = true
        await settle()
        XCTAssertEqual(scheduler.calls.last?.count, 2)

        flags.set(.mealPlan, false)
        await settle()
        XCTAssertEqual(scheduler.calls.last, [])

        flags.set(.mealPlan, true)
        pantry.items.value = pantry.items.value.map { var item = $0; item.inStock = false; return item }
        await settle()
        XCTAssertEqual(scheduler.calls.last, [])
    }
}
