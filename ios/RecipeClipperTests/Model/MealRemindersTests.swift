import XCTest
@testable import RecipeClipper

/// The evening-before and Tonight reminders (#397); Android's `MealRemindersTest` has the same cases.
final class MealRemindersTests: XCTestCase {

    private let types = [
        MealType(id: 3, name: "Dinner", builtInKey: "dinner", sortOrder: 2),
        MealType(id: 1, name: "Breakfast", builtInKey: "breakfast", sortOrder: 0),
        MealType(id: 2, name: "Lunch", builtInKey: "lunch", sortOrder: 1),
        MealType(id: 9, name: "Pudding", builtInKey: nil, sortOrder: 3),
    ]

    private func meal(_ id: Int64, _ day: Int64, _ type: Int64, _ title: String? = nil, note: String? = nil, recipe: Int64?? = nil) -> PlannedMeal {
        PlannedMeal(
            id: id, day: day, mealTypeId: type, recipeId: recipe ?? (title == nil ? nil : id), title: title,
            imageUrl: nil, servings: nil, note: note
        )
    }

    private let ahead = AheadStep(step: 2, quote: "Marinate at least 8 hours or overnight.")

    func testTheEveningReminderNamesTomorrowsMealsInMealTypeOrder() throws {
        let meals = [
            meal(1, 101, 3, "Lemon chicken"), meal(2, 101, 1, "Porridge"),
            meal(3, 100, 3, "Today's stew"), meal(4, 102, 3, "The day after"),
        ]
        let reminder = try XCTUnwrap(MealReminders.build(.evening, fireDay: 100, meals: meals, types: types))
        XCTAssertEqual(reminder.titles, ["Porridge", "Lemon chicken"])
        XCTAssertEqual(reminder.day, 100)
        XCTAssertEqual(reminder.recipeId, 2)
    }

    func testANoteShowsItsTextAndOpensNoRecipe() throws {
        let reminder = try XCTUnwrap(MealReminders.build(.evening, fireDay: 100, meals: [meal(1, 101, 3, note: " Leftovers ")], types: types))
        XCTAssertEqual(reminder.titles, ["Leftovers"])
        XCTAssertNil(reminder.recipeId)
    }

    func testTheFirstRecipeInOrderIsTheOneATapOpens() {
        let meals = [meal(1, 101, 9, note: "Ice cream"), meal(2, 101, 3, "Soup"), meal(3, 101, 2, "Salad")]
        XCTAssertEqual(MealReminders.build(.evening, fireDay: 100, meals: meals, types: types)?.recipeId, 3)
    }

    func testNothingPlannedTomorrowIsNoReminder() {
        XCTAssertNil(MealReminders.build(.evening, fireDay: 100, meals: [meal(1, 100, 3, "Today")], types: types))
        XCTAssertNil(MealReminders.build(.evening, fireDay: 100, meals: [], types: types))
        // A recipe that's gone and an empty note name nothing.
        let gone = [meal(1, 101, 3, nil, recipe: .some(1)), meal(2, 101, 3, note: "  ")]
        XCTAssertNil(MealReminders.build(.evening, fireDay: 100, meals: gone, types: types))
    }

    func testTheSameMealTwiceIsNamedOnce() throws {
        let meals = [meal(1, 101, 3, "Soup", recipe: .some(7)), meal(2, 101, 3, "Soup", recipe: .some(7))]
        let reminder = try XCTUnwrap(MealReminders.build(.evening, fireDay: 100, meals: meals, types: types, aheadFor: { _ in self.ahead }))
        XCTAssertEqual(reminder.titles, ["Soup"])
        XCTAssertEqual(reminder.ahead, [AheadNote(recipeId: 7, title: "Soup", quote: ahead.quote)])
    }

    func testAnAheadStepIsQuotedForEachRecipeThatHasOne() throws {
        let meals = [meal(1, 101, 1, "Porridge"), meal(2, 101, 3, "Lemon chicken")]
        let reminder = try XCTUnwrap(MealReminders.build(.evening, fireDay: 100, meals: meals, types: types) { $0 == 2 ? self.ahead : nil })
        XCTAssertEqual(reminder.ahead, [AheadNote(recipeId: 2, title: "Lemon chicken", quote: ahead.quote)])
        XCTAssertEqual(MealReminders.build(.evening, fireDay: 100, meals: meals, types: types)?.ahead, [])
    }

    func testTonightNamesTodaysMealsLeftOutBreakfastAndLunch() throws {
        let meals = [
            meal(1, 100, 1, "Porridge"), meal(2, 100, 2, "Sandwich"), meal(3, 100, 3, "Lemon chicken"),
            meal(4, 100, 9, note: "Ice cream"), meal(5, 101, 3, "Tomorrow"),
        ]
        let reminder = try XCTUnwrap(MealReminders.build(.tonight, fireDay: 100, meals: meals, types: types) { _ in self.ahead })
        XCTAssertEqual(reminder.titles, ["Lemon chicken", "Ice cream"])
        // Too late to start anything ahead.
        XCTAssertEqual(reminder.ahead, [])
        XCTAssertNil(MealReminders.build(.tonight, fireDay: 100, meals: [meal(1, 100, 1, "Porridge")], types: types))
    }

    func testARenamedBreakfastIsStillBreakfast() {
        let renamed = types.map { $0.builtInKey == "breakfast" ? MealType(id: $0.id, name: "Morning", builtInKey: $0.builtInKey, sortOrder: $0.sortOrder) : $0 }
        XCTAssertNil(MealReminders.build(.tonight, fireDay: 100, meals: [meal(1, 100, 1, "Porridge")], types: renamed))
    }

    func testTheFirstDayIsTodayUntilItsTimeHasPassed() {
        XCTAssertEqual(MealReminders.firstDay(today: 100, minuteOfDay: 18 * 60 + 59, atMinute: MealReminders.eveningMinute), 100)
        XCTAssertEqual(MealReminders.firstDay(today: 100, minuteOfDay: MealReminders.eveningMinute, atMinute: MealReminders.eveningMinute), 101)
        XCTAssertEqual(MealReminders.firstDay(today: 100, minuteOfDay: 23 * 60, atMinute: MealReminders.eveningMinute), 101)
    }

    func testTheDefaultTimes() {
        XCTAssertEqual(MealReminders.eveningMinute, 1140)
        XCTAssertEqual(MealReminders.tonightMinute, 960)
    }

    func testPlanIsEachDayWithSomethingToNameForAWeek() {
        let meals = [meal(1, 101, 3, "A"), meal(2, 103, 3, "B"), meal(3, 108, 3, "Beyond the week")]
        let planned = MealReminders.plan(.evening, atMinute: MealReminders.eveningMinute, today: 100, minuteOfDay: 20 * 60, meals: meals, types: types)
        XCTAssertEqual(planned.map(\.day), [102, 107])
        XCTAssertEqual(planned.flatMap(\.titles), ["B", "Beyond the week"])
        let early = MealReminders.plan(.evening, atMinute: MealReminders.eveningMinute, today: 100, minuteOfDay: 8 * 60, meals: meals, types: types)
        XCTAssertEqual(early.map(\.day), [100, 102])
        XCTAssertEqual(early.flatMap(\.titles), ["A", "B"])
    }

    func testThePlanEndsAtMaxDays() {
        let meals = (101...120).map { meal(Int64($0), Int64($0), 3, "Meal \($0)") }
        let planned = MealReminders.plan(.evening, atMinute: MealReminders.eveningMinute, today: 100, minuteOfDay: 8 * 60, meals: meals, types: types)
        XCTAssertEqual(planned.map(\.day), Array(100...106))
    }

    func testRecipeIdsAreThePlannedRecipesOnce() {
        let meals = [meal(1, 101, 3, "A", recipe: .some(5)), meal(2, 101, 3, "A", recipe: .some(5)), meal(3, 101, 1, note: "x")]
        XCTAssertEqual(MealReminders.recipeIds(meals), [5])
    }

    private func reminder(_ ahead: [AheadNote] = [], kind: MealReminderKind = .evening) -> MealReminder {
        MealReminder(kind: kind, day: 100, titles: ["Porridge", "Lemon chicken"], ahead: ahead, recipeId: 2)
    }

    func testTheTitleNamesTheMealsAfterTheWordForTomorrowOrTonight() {
        XCTAssertEqual(
            MealReminderText.title(reminder(), tomorrow: { "Tomorrow: \($0)" }, tonight: { "Tonight: \($0)" }),
            "Tomorrow: Porridge, Lemon chicken"
        )
        XCTAssertEqual(
            MealReminderText.title(reminder(kind: .tonight), tomorrow: { "Tomorrow: \($0)" }, tonight: { "Tonight: \($0)" }),
            "Tonight: Porridge, Lemon chicken"
        )
    }

    func testTheBodyQuotesTheAheadStep() {
        let one = AheadNote(recipeId: 2, title: "Lemon chicken", quote: "Marinate at least 8 hours or overnight.")
        let two = AheadNote(recipeId: 3, title: "Beans", quote: "Soak the beans for 8 hours.")
        func body(_ r: MealReminder) -> String? {
            MealReminderText.body(r, one: { "To do ahead: \u{201C}\($0)\u{201D}" }, header: "To do ahead:") { "\($0): \u{201C}\($1)\u{201D}" }
        }
        XCTAssertNil(body(reminder()))
        XCTAssertEqual(body(reminder([one])), "To do ahead: \u{201C}Marinate at least 8 hours or overnight.\u{201D}")
        XCTAssertEqual(
            body(reminder([one, two])),
            "To do ahead:\nLemon chicken: \u{201C}Marinate at least 8 hours or overnight.\u{201D}\nBeans: \u{201C}Soak the beans for 8 hours.\u{201D}"
        )
    }

    /// The words the app writes, in English (the test host runs in it): the issue's examples.
    func testTheStringsReadAsTheIssueSaysTheyDo() {
        let one = AheadNote(recipeId: 2, title: "Lemon chicken", quote: "Marinate at least 8 hours or overnight.")
        XCTAssertEqual(Strings.mealReminderTitle(reminder()), "Tomorrow: Porridge, Lemon chicken")
        XCTAssertEqual(Strings.mealReminderTitle(reminder(kind: .tonight)), "Tonight: Porridge, Lemon chicken")
        XCTAssertNil(Strings.mealReminderBody(reminder()))
        XCTAssertEqual(Strings.mealReminderBody(reminder([one])), "To do ahead: \u{201C}Marinate at least 8 hours or overnight.\u{201D}")
    }
}
