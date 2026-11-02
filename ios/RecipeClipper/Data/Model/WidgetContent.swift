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
        sections.flatMap { section in
            section.rows.filter { row in !row.items.contains(where: \.checked) }.flatMap { row -> [String] in
                switch row {
                case .single(let item): return [item.text.trimmingCharacters(in: .whitespacesAndNewlines)]
                case .combined(_, let text, _): return [text]
                case .together: return GroceryCombiner.lineGroups(row).map(GroceryCombiner.lineText)
                }
            }
        }
    }
}
