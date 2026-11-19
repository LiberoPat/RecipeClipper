import Foundation

/// A week of recipes shared in from an AI chat (#352): the answer to the "Plan my week" prompt
/// (#353), one JSON array of schema.org Recipes, each with a `"day"` (`YYYY-MM-DD`). Ported line
/// for line from Android's `MealPlanImport`; its doc comment has the rules. Shared text that
/// holds one opens a review instead of the single-recipe editor: the first JSON value in the text
/// that `JsonLdRecipeParser.parseEach` reads at least `minRecipes` recipes from, at least one of
/// them with a day. At most `maxRecipes` are kept; a recipe's url and image are never used.
enum MealPlanImport {

    /// Fewer than this is a single recipe, for the editor.
    static let minRecipes = 2

    /// A month's worth: more is cut off.
    static let maxRecipes = 31

    /// How many places in the text that open a JSON value are tried before giving up.
    private static let maxCandidates = 20

    /// One recipe of the plan. `day` is the epoch day its `"day"` named, nil when it named none or
    /// something that isn't a real date ("Monday", "2026-02-30").
    struct Item: Equatable {
        let recipe: Recipe
        let day: Int64?
    }

    struct Plan: Equatable {
        let items: [Item]
    }

    /// The plan in `text`, or nil when it holds none.
    static func read(_ text: String) -> Plan? {
        let normalized = SharedText.normalize(text)
        guard normalized.u16Count <= SharedText.maxLength,
              normalized.range(of: "recipe", options: .caseInsensitive) != nil else { return nil }
        let units = Array(normalized.utf16)
        let ns = normalized as NSString
        var tried = 0
        var i = 0
        while i < units.count && tried < maxCandidates {
            if units[i] != UInt16(UInt8(ascii: "{")) && units[i] != UInt16(UInt8(ascii: "[")) { i += 1; continue }
            tried += 1
            let end = SharedText.jsonEnd(units, i)
            if end < 0 { i += 1; continue }
            let found = JsonLdRecipeParser.parseEach(ns.substring(with: NSRange(location: i, length: end - i)))
            if found.count >= minRecipes && found.contains(where: { $0.day != nil }) {
                return Plan(items: found.prefix(maxRecipes).map { Item(recipe: $0.recipe, day: parseDay($0.day)) })
            }
            i = end
        }
        return nil
    }

    private static let dayPattern = JRegex(#"^(\d{4})-(\d{2})-(\d{2})(?:[T ].*)?$"#)

    /// `YYYY-MM-DD` (a time after it is ignored) as an epoch day; nil for anything else, and for a
    /// date the calendar doesn't have.
    static func parseDay(_ raw: String?) -> Int64? {
        guard let raw, let m = dayPattern.matchEntire(raw.kTrimmed),
              let year = Int(m[1]), let month = Int(m[2]), let day = Int(m[3]) else { return nil }
        guard (1...12).contains(month), (1...31).contains(day) else { return nil }
        let epoch = PlanDays.epochDay(year: year, month: month, dayOfMonth: day)
        let civil = PlanDays.civil(epoch)
        return civil.year == year && civil.month == month && civil.day == day ? epoch : nil
    }
}
