import Foundation

/// What the home-screen widgets show (#309), ported from the Kotlin `WidgetContent` (see there).
/// Pure; the widget extension only draws it.
enum WidgetContent {
    /// One of today's meals: its meal type's name ("Dinner") and the recipe's title or the note.
    struct Meal: Equatable {
        let mealType: String
        let title: String
    }

    /// "Tonight": `today`'s meals, in meal type order, then in the order the plan holds them. A
    /// meal with no title and no note is left out.
    static func tonight(_ meals: [PlannedMeal], types: [MealType], today: Int64) -> [Meal] {
        let sorted = types.sorted { ($0.sortOrder, $0.id) < ($1.sortOrder, $1.id) }
        let order = Dictionary(uniqueKeysWithValues: sorted.enumerated().map { ($0.element.id, $0.offset) })
        let names = Dictionary(types.map { ($0.id, $0.name) }, uniquingKeysWith: { first, _ in first })
        let todays = meals.enumerated().filter { $0.element.day == today }
        // A stable sort: meals of one type keep the plan's order.
        let byType = todays.sorted {
            let a = order[$0.element.mealTypeId] ?? Int.max, b = order[$1.element.mealTypeId] ?? Int.max
            return a != b ? a < b : $0.offset < $1.offset
        }
        return byType.compactMap { _, meal in
            guard let title = (meal.title ?? meal.note)?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !title.isEmpty else { return nil }
            return Meal(mealType: names[meal.mealTypeId] ?? "", title: title)
        }
    }

    /// The grocery list's unticked lines as the Groceries screen shows them, in aisle order: a
    /// combined row is its total, lines kept together are each listed. A row with any ticked
    /// line is in the basket.
    static func groceries(_ sections: [GroceryCombiner.Section]) -> [String] {
        sections.flatMap { section in GroceryCombiner.uncheckedLines(section).map(\.text) }
    }

    /// How a list is cut to a widget's height: `shown` of its items from the top, then "+`more`
    /// more", on a line of its own or, when `inline`, beside the one item there is room for.
    struct Fit: Equatable {
        let shown: Int
        let more: Int
        let inline: Bool
    }

    /// A list of `count` items in a widget with room for `rows` rows: all of them when they fit,
    /// else the last row is kept for "+N more". An item is always named: with room for a single
    /// row the count goes beside the first item, never in its place (#329).
    static func fit(count: Int, rows: Int) -> Fit {
        if count <= 0 { return Fit(shown: 0, more: 0, inline: false) }
        if count <= rows { return Fit(shown: count, more: 0, inline: false) }
        if rows >= 2 { return Fit(shown: rows - 1, more: count - (rows - 1), inline: false) }
        return Fit(shown: 1, more: count - 1, inline: count > 1)
    }
}
