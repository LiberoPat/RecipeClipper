import Foundation

/// The two reminders about the plan (#397): the evening before, and the afternoon of.
enum MealReminderKind: Equatable {
    case evening, tonight
}

/// A planned recipe's step to start ahead of time, quoted: what the evening reminder adds.
struct AheadNote: Equatable {
    let recipeId: Int64
    let title: String
    let quote: String
}

/// One reminder as it is worded: `day` is the day it fires (an epoch day), `titles` the meals it
/// names (recipe titles, or a note's text) in meal type order, `ahead` the planned recipes with a
/// step to start ahead of time (the evening reminder only), and `recipeId` the first planned
/// recipe, which a tap opens (nil when every meal is a note). Android's `MealReminder`.
struct MealReminder: Equatable {
    let kind: MealReminderKind
    let day: Int64
    let titles: [String]
    let ahead: [AheadNote]
    let recipeId: Int64?
}

/// The evening-before and "Tonight" reminders (#397). Pure: ported from the Kotlin
/// `MealReminders` (see there). iOS schedules every reminder `plan` returns, each carrying its
/// words, since nothing runs when one fires.
enum MealReminders {

    /// 19:00, in minutes since local midnight.
    static let eveningMinute = 19 * 60

    /// 16:00.
    static let tonightMinute = 16 * 60

    /// The most days ahead one kind is planned for: iOS keeps only 64 pending notifications per app.
    static let maxDays = 7

    /// The day whose meals a reminder firing on `fireDay` names: tomorrow's for the evening, today's for Tonight.
    static func mealDay(_ kind: MealReminderKind, fireDay: Int64) -> Int64 {
        kind == .evening ? fireDay + 1 : fireDay
    }

    /// The first day a reminder at `atMinute` can still fire: today while it is earlier than that, else tomorrow.
    static func firstDay(today: Int64, minuteOfDay: Int, atMinute: Int) -> Int64 {
        minuteOfDay < atMinute ? today : today + 1
    }

    private static func label(_ meal: PlannedMeal) -> String? {
        (meal.title ?? meal.note)?.kTrimmed
    }

    /// What a reminder firing on `fireDay` names, in meal type order, then in the order the plan
    /// holds them. A meal with neither a title nor a note is left out. "Tonight" leaves out
    /// Breakfast and Lunch (by their `builtInKey`, so a rename doesn't matter).
    static func meals(_ kind: MealReminderKind, fireDay: Int64, meals: [PlannedMeal], types: [MealType]) -> [PlannedMeal] {
        let day = mealDay(kind, fireDay: fireDay)
        let sorted = types.sorted { ($0.sortOrder, $0.id) < ($1.sortOrder, $1.id) }
        let order = Dictionary(uniqueKeysWithValues: sorted.enumerated().map { ($0.element.id, $0.offset) })
        let over = Set(types.filter { $0.builtInKey == "breakfast" || $0.builtInKey == "lunch" }.map(\.id))
        let named = meals.enumerated().filter { _, meal in
            meal.day == day
                && (kind == .evening || !over.contains(meal.mealTypeId))
                && !(label(meal) ?? "").isEmpty
        }
        // A stable sort: meals of one type keep the plan's order.
        return named.sorted {
            let a = order[$0.element.mealTypeId] ?? Int.max, b = order[$1.element.mealTypeId] ?? Int.max
            return a != b ? a < b : $0.offset < $1.offset
        }.map(\.element)
    }

    /// The recipes `meals` is about, once each: the ones whose steps the evening reminder reads.
    static func recipeIds(_ meals: [PlannedMeal]) -> [Int64] {
        var seen = Set<Int64>()
        return meals.compactMap(\.recipeId).filter { seen.insert($0).inserted }
    }

    private static func distinct<T: Hashable>(_ values: [T]) -> [T] {
        var seen = Set<T>()
        return values.filter { seen.insert($0).inserted }
    }

    /// The reminder firing on `fireDay`, or nil when it has nothing to name. `aheadFor` answers a
    /// planned recipe's step to start ahead of time (the evening reminder only; "Tonight" is too
    /// late for one).
    static func build(
        _ kind: MealReminderKind, fireDay: Int64, meals: [PlannedMeal], types: [MealType],
        aheadFor: (Int64) -> AheadStep? = { _ in nil }
    ) -> MealReminder? {
        let named = Self.meals(kind, fireDay: fireDay, meals: meals, types: types)
        if named.isEmpty { return nil }
        var ahead: [AheadNote] = []
        if kind == .evening {
            var seen = Set<Int64>()
            for meal in named {
                guard let id = meal.recipeId, seen.insert(id).inserted else { continue }
                if let step = aheadFor(id) { ahead.append(AheadNote(recipeId: id, title: label(meal)!, quote: step.quote)) }
            }
        }
        return MealReminder(
            kind: kind, day: fireDay,
            titles: distinct(named.map { label($0)! }),
            ahead: ahead,
            recipeId: named.compactMap(\.recipeId).first
        )
    }

    /// Every reminder of `kind` still to come, soonest first: the next `maxDays` days from the
    /// first one at `atMinute` can fire (see `firstDay`) that have something to name. `meals`
    /// holds the plan over those days' meals.
    static func plan(
        _ kind: MealReminderKind, atMinute: Int, today: Int64, minuteOfDay: Int, meals: [PlannedMeal],
        types: [MealType], aheadFor: (Int64) -> AheadStep? = { _ in nil }
    ) -> [MealReminder] {
        let first = firstDay(today: today, minuteOfDay: minuteOfDay, atMinute: atMinute)
        return (first..<(first + Int64(maxDays))).compactMap {
            build(kind, fireDay: $0, meals: meals, types: types, aheadFor: aheadFor)
        }
    }
}

/// The words of a reminder, from the platform's strings (the screen picks the words, never the
/// model). Each takes the string as a function of its parts, so the model stays free of resources.
enum MealReminderText {

    /// "Tomorrow: Porridge, Lemon chicken" / "Tonight: Lemon chicken".
    static func title(_ reminder: MealReminder, tomorrow: (String) -> String, tonight: (String) -> String) -> String {
        let names = reminder.titles.joined(separator: ", ")
        return reminder.kind == .evening ? tomorrow(names) : tonight(names)
    }

    /// What to do ahead, or nil when nothing: one recipe's step as `one(quote)`; several as a
    /// header line, then a line per recipe, `item(recipe, quote)`.
    static func body(
        _ reminder: MealReminder, one: (String) -> String, header: String, item: (String, String) -> String
    ) -> String? {
        switch reminder.ahead.count {
        case 0: return nil
        case 1: return one(reminder.ahead[0].quote)
        default: return ([header] + reminder.ahead.map { item($0.title, $0.quote) }).joined(separator: "\n")
        }
    }
}
