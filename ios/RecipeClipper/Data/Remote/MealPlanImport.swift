import Foundation

/// A week of recipes shared in from an AI chat (#352): the answer to the "Plan my week" prompt
/// (#353), one JSON array of schema.org Recipes, each with a `"day"` (`YYYY-MM-DD`). Ported line
/// for line from Android's `MealPlanImport`; its doc comment has the rules. Shared text that
/// holds one opens a review instead of the single-recipe editor. `read` gathers the recipes of
/// every whole JSON value in the text (one array, several code blocks, a wrapper object, or an
/// answer cut off mid-recipe, `Plan.cutOff`): at least `minRecipes`, and a day on one of them,
/// or all in one value, no `isBasedOn` (the Kotlin's header has the rules). At most `maxRecipes`
/// are kept; a recipe's url and image are never used.
///
/// Plan my week with choices (#392, #367) adds two properties. A recipe's `"meal"` is kept as written
/// (`Item.meal`); mapping it to a meal type is `MealType.idForMeal`'s, done where the cook's types
/// are known. And an object that names a saved recipe by its link (`isBasedOn`, or `url`) and has no
/// ingredients or steps of its own is an `Item` with `link`: the review matches it to the library
/// by `sourceUrl`, and a link that matches nothing is left out and counted, never made into a
/// recipe. A `meal` counts as a day does for telling a plan from a changed recipe.
enum MealPlanImport {

    /// Fewer than this is a single recipe, for the editor.
    static let minRecipes = 2

    /// Ten meals a day for a week: more is cut off, so a runaway answer can't flood the review.
    /// (Was a month's worth, 31, when the prompt asked only for dinners; #392.)
    static let maxRecipes = 70

    /// Longer text isn't read as a plan: room for `maxRecipes` recipes written out.
    static let maxLength = 100_000

    /// How many places in the text that open a JSON value are tried before giving up: room for
    /// `maxRecipes` separate blocks, the prose braces between them, and the pieces of a cut-off one.
    private static let maxCandidates = 200

    /// One recipe of the plan. `day` is the epoch day its `"day"` named, nil when it named none or
    /// something that isn't a real date ("Monday", "2026-02-30").
    struct Item: Equatable {
        let recipe: Recipe
        let day: Int64?
        /// The `"meal"` it named, as written (trimmed), or nil (#392).
        var meal: String? = nil
        /// The link it named a saved recipe by, when it carries no recipe of its own (#367).
        var link: String? = nil

        /// It names a saved recipe by `link` and carries none of its own: `recipe` is only a name.
        var namesSavedRecipe: Bool { link != nil }
    }

    /// `cutOff`: the text ended inside an unfinished JSON value, so recipes after the last whole
    /// one are missing.
    struct Plan: Equatable {
        let items: [Item]
        var cutOff = false
    }

    /// The plan in `text`, or nil when it holds none.
    static func read(_ text: String) -> Plan? {
        let normalized = SharedText.normalize(text)
        guard normalized.u16Count <= maxLength,
              normalized.range(of: "recipe", options: .caseInsensitive) != nil else { return nil }
        let units = Array(normalized.utf16)
        let ns = normalized as NSString
        var gathered: [JsonLdRecipeParser.Found] = []
        var oneValue = false // a single whole value held at least minRecipes recipes
        var basedOn = false // a value names the recipe it changed (#354): a changed recipe, not a week
        var cutAt = -1 // where the first unfinished JSON-looking value began
        var afterCut = 0 // recipes read from whole values that began inside it
        var tried = 0
        var i = 0
        while i < units.count && tried < maxCandidates && gathered.count < maxRecipes {
            if units[i] != UInt16(UInt8(ascii: "{")) && units[i] != UInt16(UInt8(ascii: "[")) { i += 1; continue }
            tried += 1
            let end = SharedText.jsonEnd(units, i)
            if end < 0 {
                // No closing bracket: a cut-off answer (or prose with a stray bracket). Step inside, so
                // each whole recipe in it is still found; the unfinished one is not a value at all.
                if cutAt < 0 && SharedText.looksLikeJson(units, i) { cutAt = i }
                i += 1
                continue
            }
            let value = ns.substring(with: NSRange(location: i, length: end - i))
            let found = JsonLdRecipeParser.parseEach(value, deep: true)
            if !found.isEmpty {
                gathered += found
                if found.count >= minRecipes { oneValue = true }
                if JsonLdRecipeParser.isBasedOn([value]) != nil { basedOn = true }
                if cutAt >= 0 { afterCut += found.count }
            }
            i = end
        }
        if gathered.count < minRecipes { return nil }
        // With no day or meal anywhere, only one value holding the recipes (an array, a wrapper, or
        // one that was cut off) is a plan, and never a changed recipe: separate blocks are not.
        if !gathered.contains(where: { $0.day != nil || $0.meal != nil }) && (basedOn || !(oneValue || afterCut >= minRecipes)) { return nil }
        return Plan(
            items: gathered.prefix(maxRecipes).map { Item(recipe: $0.recipe, day: parseDay($0.day), meal: $0.meal, link: $0.link) },
            cutOff: cutAt >= 0
        )
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
