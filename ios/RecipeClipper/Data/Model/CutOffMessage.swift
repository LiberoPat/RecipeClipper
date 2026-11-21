import Foundation

/// The message that asks an AI chat for the rest of an answer that was cut off (#352): copied by
/// the cook from the share card or the meal-plan review and pasted into the chat. Ported line for
/// line from Android's `CutOffMessage`; its doc comment has the rules. Pure: the sentences are
/// the UI language's (`Words`, from the String Catalog), the days are worked out here.
enum CutOffMessage {

    /// `plan` takes the missing days, written `YYYY-MM-DD` and comma-separated.
    struct Words {
        let plan: (_ days: String) -> String
        let planRest: String
        let recipe: String
        /// `planMeals`: the missing pairs, each `YYYY-MM-DD (Meal)`, comma-separated (#392).
        let planMeals: (_ pairs: String) -> String

        init(
            plan: @escaping (_ days: String) -> String, planRest: String, recipe: String,
            planMeals: ((_ pairs: String) -> String)? = nil
        ) {
            self.plan = plan
            self.planRest = planRest
            self.recipe = recipe
            self.planMeals = planMeals ?? plan
        }
    }

    /// The message for a cut-off week whose whole recipes are on `days` (nil: no readable day).
    static func forPlan(_ days: [Int64?], _ words: Words) -> String {
        guard let missing = missingDays(days) else { return words.planRest }
        return words.plan(missing.map(iso).joined(separator: ", "))
    }

    /// The message for a cut-off week whose whole recipes are on `entries` (day, meal, as the answer
    /// wrote them; nil where it had none): with no meal read anywhere it is `forPlan`; otherwise the
    /// missing day and meal pairs (`missingPairs`), or "the remaining days" when none can be told.
    static func forPlanMeals(_ entries: [(day: Int64?, meal: String?)], _ words: Words) -> String {
        if !entries.contains(where: { $0.meal != nil }) { return forPlan(entries.map(\.day), words) }
        guard let missing = missingPairs(entries) else { return words.planRest }
        return words.planMeals(missing.map { iso($0.day) + " (" + $0.meal + ")" }.joined(separator: ", "))
    }

    /// The (day, meal) pairs of the week's block that no whole recipe is on: the block counted from the
    /// earliest day read, the meals those the answer named (case and spaces ignored, written as first
    /// seen), in the order first seen. Nil when no day is readable or nothing is missing.
    static func missingPairs(_ entries: [(day: Int64?, meal: String?)]) -> [(day: Int64, meal: String)]? {
        guard let first = entries.compactMap(\.day).min() else { return nil }
        var meals: [String] = []
        for entry in entries {
            guard let name = entry.meal?.kTrimmed, !name.isEmpty else { continue }
            if !meals.contains(where: { $0.lowercased() == name.lowercased() }) { meals.append(name) }
        }
        if meals.isEmpty { return nil }
        var have = Set<String>()
        for entry in entries {
            if let day = entry.day, let meal = entry.meal { have.insert("\(day)|\(meal.kTrimmed.lowercased())") }
        }
        var missing: [(day: Int64, meal: String)] = []
        for day in first..<(first + Int64(PlanDays.blockDays)) {
            for meal in meals where !have.contains("\(day)|\(meal.lowercased())") { missing.append((day, meal)) }
        }
        return missing.isEmpty ? nil : missing
    }

    /// The message for a cut-off single recipe, or a week cut off before its first recipe ended.
    static func forRecipe(_ words: Words) -> String { words.recipe }

    /// The days of the week's block that no whole recipe is on, or nil when none can be told.
    static func missingDays(_ days: [Int64?]) -> [Int64]? {
        let known = days.compactMap { $0 }
        guard let first = known.min() else { return nil }
        let missing = (first..<(first + Int64(PlanDays.blockDays))).filter { !known.contains($0) }
        return missing.isEmpty ? nil : missing
    }

    /// `day` (an epoch day) as `YYYY-MM-DD`.
    static func iso(_ day: Int64) -> String {
        let civil = PlanDays.civil(day)
        return String(format: "%04d-%02d-%02d", civil.year, civil.month, civil.day)
    }
}
