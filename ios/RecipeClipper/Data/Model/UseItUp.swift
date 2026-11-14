import Foundation

/// A saved recipe (in at least one list) as "Use it up" (#305) reads it: its ingredient `lines`
/// as stored (only the names are read), in `language`. Android's `SavedRecipe`.
struct SavedRecipe: Equatable, Identifiable {
    let id: Int64
    let title: String
    let language: String?
    let lines: [String]
}

/// One pantry `item` about to expire, and the saved `recipes` that use it, A–Z.
struct UseItUpGroup: Equatable {
    let item: PantryItem
    let recipes: [SavedRecipe]
}

/// A saved `recipe`'s ingredient `names` (a heading or an unbuyable line aside), parsed once and
/// then checked against every item expiring, in its `language`. Android's `RecipeNames`.
struct RecipeNames: Equatable {
    let recipe: SavedRecipe
    let language: String
    let names: [String]
}

/// "Use it up" (#305): the saved recipes that use a pantry item about to expire. Pure; Android's
/// `UseItUp`, pinned by the corpus's `UseIt` rows.
///
/// Soon is the expiry reminder's window (`ExpiryReminders.due`). Only saved recipes: the caller
/// passes the recipes in at least one list. A recipe uses an item when one of its lines names it
/// as Have/Buy matches (`PantryMatch.find`, by name only: "rice flour" is never "flour"), in the
/// same language. Presence only. Each recipe's lines are parsed once (`names`), not once per
/// expiring item, and not at all while nothing expires.
enum UseItUp {
    static func soon(_ item: PantryItem, today: Int64) -> Bool { ExpiryReminders.due(item, day: today) }

    /// `recipe`'s ingredient names, or nil when its language has no tables.
    static func names(_ recipe: SavedRecipe) -> RecipeNames? {
        // A recipe stored with no language (before #14) is detected, as What I need's are.
        guard let words = LanguageWords.forRecipe(language: recipe.language, title: recipe.title, lines: recipe.lines) else {
            return nil
        }
        let names = recipe.lines.filter { GrocerySources.buyable($0) }.compactMap { IngredientName.of($0, words: words) }
        return RecipeNames(recipe: recipe, language: words.language, names: names)
    }

    /// Whether one of the recipe's `names` is `item`'s.
    static func uses(_ names: RecipeNames, _ item: PantryItem) -> Bool {
        names.names.contains { PantryMatch.find($0, language: names.language, pantry: [item]) != nil }
    }

    /// Whether one of `recipe`'s lines (a heading aside) names `item`.
    static func uses(_ recipe: SavedRecipe, _ item: PantryItem) -> Bool {
        names(recipe).map { uses($0, item) } ?? false
    }

    /// The recipes in `saved` that use `item`, by title A–Z.
    static func recipes(_ item: PantryItem, saved: [RecipeNames]) -> [SavedRecipe] {
        saved.filter { uses($0, item) }.map(\.recipe).sorted { a, b in
            let la = utf16(a.title.lowercased()), lb = utf16(b.title.lowercased())
            if la != lb { return la.lexicographicallyPrecedes(lb) }
            if a.title != b.title { return utf16(a.title).lexicographicallyPrecedes(utf16(b.title)) }
            return a.id < b.id
        }
    }

    /// Each item of `pantry` expiring soon with at least one saved recipe that uses it: soonest
    /// first, then by name A–Z.
    static func groups(_ pantry: [PantryItem], saved: [SavedRecipe], today: Int64) -> [UseItUpGroup] {
        let soon = pantry.filter { soon($0, today: today) }
            .sorted { a, b in
                if a.expiresDay != b.expiresDay { return (a.expiresDay ?? 0) < (b.expiresDay ?? 0) }
                let la = utf16(a.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())
                let lb = utf16(b.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())
                if la != lb { return la.lexicographicallyPrecedes(lb) }
                return a.id < b.id
            }
        if soon.isEmpty { return [] }
        let parsed = saved.compactMap(names)
        return soon.map { UseItUpGroup(item: $0, recipes: recipes($0, saved: parsed)) }.filter { !$0.recipes.isEmpty }
    }

    /// The days a recipe for `item` can be planned on with one tap: today, then its use-by day.
    static func planDays(_ item: PantryItem, today: Int64) -> [Int64] {
        guard let expires = item.expiresDay, expires > today else { return [today] }
        return [today, expires]
    }

    private static func utf16(_ s: String) -> [UInt16] { Array(s.utf16) }
}
