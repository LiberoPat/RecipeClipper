import Foundation

/// A saved recipe (in at least one list) as "Use it up" (#305) reads it: its ingredient `lines`
/// as stored (only the names are read), in `language`. Android's `SavedRecipe`.
struct SavedRecipe: Equatable, Identifiable {
    let id: Int64
    let title: String
    let imageUrl: String?
    let language: String?
    let lines: [String]
}

/// One pantry `item` about to expire, and the saved `recipes` that use it, A–Z.
struct UseItUpGroup: Equatable {
    let item: PantryItem
    let recipes: [SavedRecipe]
}

/// "Use it up" (#305): the saved recipes that use a pantry item about to expire. Pure; Android's
/// `UseItUp`, pinned by the corpus's `UseIt` rows.
///
/// Soon is the expiry reminder's window (`ExpiryReminders.due`). Only saved recipes: the caller
/// passes the recipes in at least one list. A recipe uses an item when one of its lines names it
/// as Have/Buy matches (`PantryMatch.find`, by name only: "rice flour" is never "flour"), in the
/// same language. Presence only.
enum UseItUp {
    static func soon(_ item: PantryItem, today: Int64) -> Bool { ExpiryReminders.due(item, day: today) }

    /// Whether one of `recipe`'s lines (a heading aside) names `item`.
    static func uses(_ recipe: SavedRecipe, _ item: PantryItem) -> Bool {
        // A recipe stored with no language (before #14) is detected, as What I need's are.
        let tag = recipe.language ?? LanguageWords.resolve(declared: nil, page: nil) {
            LanguageWords.detectionText(name: recipe.title, ingredients: recipe.lines)
        }
        guard let words = LanguageWords.forTag(tag) else { return false }
        return recipe.lines.contains { line in
            guard GrocerySources.buyable(line), let name = IngredientName.of(line, words: words) else { return false }
            return PantryMatch.find(name, language: words.language, pantry: [item]) != nil
        }
    }

    /// The recipes in `saved` that use `item`, by title A–Z.
    static func recipes(_ item: PantryItem, saved: [SavedRecipe]) -> [SavedRecipe] {
        saved.filter { uses($0, item) }.sorted { a, b in
            let la = utf16(a.title.lowercased()), lb = utf16(b.title.lowercased())
            if la != lb { return la.lexicographicallyPrecedes(lb) }
            if a.title != b.title { return utf16(a.title).lexicographicallyPrecedes(utf16(b.title)) }
            return a.id < b.id
        }
    }

    /// Each item of `pantry` expiring soon with at least one saved recipe that uses it: soonest
    /// first, then by name A–Z.
    static func groups(_ pantry: [PantryItem], saved: [SavedRecipe], today: Int64) -> [UseItUpGroup] {
        pantry.filter { soon($0, today: today) }
            .sorted { a, b in
                if a.expiresDay != b.expiresDay { return (a.expiresDay ?? 0) < (b.expiresDay ?? 0) }
                let la = utf16(a.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())
                let lb = utf16(b.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())
                if la != lb { return la.lexicographicallyPrecedes(lb) }
                return a.id < b.id
            }
            .map { UseItUpGroup(item: $0, recipes: recipes($0, saved: saved)) }
            .filter { !$0.recipes.isEmpty }
    }

    /// The days a recipe for `item` can be planned on with one tap: today, then its use-by day.
    static func planDays(_ item: PantryItem, today: Int64) -> [Int64] {
        guard let expires = item.expiresDay, expires > today else { return [today] }
        return [today, expires]
    }

    private static func utf16(_ s: String) -> [UInt16] { Array(s.utf16) }
}
