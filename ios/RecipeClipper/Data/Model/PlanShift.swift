import Foundation

/// Skip tonight (#398): what skipping a planned meal does to the plan, as plain moves. Ported line
/// for line from the Kotlin `PlanShift` (see there); pure integer arithmetic over epoch days.
///
/// `meals` are the planned meals in plan order (by day, then meal type order, then the order they
/// were added to their slot), as `observeDays` lists them. Notes are meals like any other: they
/// move and count as occupying their meal type.
enum PlanShift {
    /// What the cook chose in the Skip sheet.
    enum Choice: CaseIterable {
        /// The skipped meal and every later planned meal go back a day, meal types kept.
        case pushWeek
        /// The skipped meal moves to the first later day with no meal of its meal type.
        case nextFreeDay
        /// The skipped meal leaves the plan (the recipe stays).
        case remove
    }

    /// `mealId` goes from `fromDay` to `toDay`, its meal type unchanged.
    struct Move: Equatable {
        let mealId: Int64
        let fromDay: Int64
        let toDay: Int64
    }

    /// What a skip does: the meals `moved` (the seam for #396, which looks at where each ended
    /// up), the meals `removed` from the plan, and the ids of the moved meals that now sit past
    /// the last day the plan shows (`pastEnd`, a subset of `moved`'s ids).
    struct Result: Equatable {
        let moved: [Move]
        let removed: [Int64]
        let pastEnd: [Int64]
    }

    /// A meal can be skipped from today on; one planned in the past is history, never offered.
    static func canSkip(_ meal: PlannedMeal, today: Int64) -> Bool { meal.day >= today }

    /// The moves for skipping the meal `skippedId` with `choice`. Nil when it isn't among `meals`
    /// or is in the past (before `today`). `lastDay` is the last day the plan shows: a meal moved
    /// beyond it is reported in `Result.pastEnd`. Nothing before the skipped meal ever moves;
    /// nothing else is changed.
    static func plan(_ meals: [PlannedMeal], skippedId: Int64, choice: Choice, today: Int64, lastDay: Int64) -> Result? {
        guard let index = meals.firstIndex(where: { $0.id == skippedId }) else { return nil }
        let skipped = meals[index]
        guard canSkip(skipped, today: today) else { return nil }

        let moved: [Move]
        switch choice {
        case .pushWeek:
            // Later days entirely, and on the skipped meal's own day only what comes at or
            // after it in plan order (the dinner follows a skipped lunch; a breakfast doesn't).
            moved = meals.enumerated().filter { i, meal in
                meal.day > skipped.day || (meal.day == skipped.day && i >= index)
            }.map { Move(mealId: $0.element.id, fromDay: $0.element.day, toDay: $0.element.day + 1) }
        case .nextFreeDay:
            let taken = Set(meals.filter { $0.mealTypeId == skipped.mealTypeId && $0.day > skipped.day }.map(\.day))
            var day = skipped.day + 1
            while taken.contains(day) { day += 1 }
            moved = [Move(mealId: skipped.id, fromDay: skipped.day, toDay: day)]
        case .remove:
            moved = []
        }
        return Result(
            moved: moved,
            removed: choice == .remove ? [skipped.id] : [],
            pastEnd: moved.filter { $0.toDay > lastDay }.map(\.mealId)
        )
    }
}
