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
    }

    /// The message for a cut-off week whose whole recipes are on `days` (nil: no readable day).
    static func forPlan(_ days: [Int64?], _ words: Words) -> String {
        guard let missing = missingDays(days) else { return words.planRest }
        return words.plan(missing.map(iso).joined(separator: ", "))
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
