import Foundation

/// What the pantry hasn't got of one planned recipe (#456): every `missing` name not yet on the
/// grocery list, in the recipe's order, and how many more missing names `onList` already are. The
/// note shows `names` and how many `more` there are beyond them ("Not in your pantry: tahini,
/// sumac and 2 more"); opened, it lists `missing`, all of them. With nothing left but what is on
/// the list (`allOnList`) it says "On your grocery list". Android's `PantryGap`.
struct PantryGap: Equatable {
    let missing: [String]
    var onList: Int = 0

    /// All of them up to `PlanPantryNote.maxNames`; past that, one fewer, so "more" is never 1.
    var names: [String] {
        missing.count <= PlanPantryNote.maxNames ? missing : Array(missing.prefix(PlanPantryNote.maxNames - 1))
    }

    var more: Int { missing.count - names.count }

    /// Whether the note is capped ("and 3 more").
    var capped: Bool { more > 0 }

    /// Every missing name is on the grocery list already: the note says so, and offers nothing.
    var allOnList: Bool { missing.isEmpty }
}

/// The Plan's quiet pantry note (#456, `planPantryNote`): under a planned recipe, the ingredients
/// the pantry doesn't have, by name only. Pure; Android's `PlanPantryNote`, pinned by the corpus's
/// `Gap` rows.
///
/// The lines are the recipe's nameable ones (`UseItUp.names`, as #378's "Uses what's in my pantry"
/// reads them); missing is `PantryMatch` and nothing else (presence only, never "enough"; a staple
/// or an item in stock is had; "rice flour" is never "flour"; the model's "same" answers can only
/// turn missing into had). A missing name an unticked grocery line names too (`IngredientName.matches`,
/// same language) is counted in `onList`, not named. No note for a language with no tables, a
/// pantry with nothing in that language, or every name had. One name per ingredient
/// (`IngredientName.key`); up to `maxNames` named, past that one fewer and "and N more".
enum PlanPantryNote {

    static let maxNames = 3

    /// The note for a recipe titled `title` in `language` with `lines`, or nil when there is none.
    static func gap(
        title: String, language: String?, lines: [String], pantry: [PantryItem],
        decisions: Decisions = .none, groceries: [GroceryItem] = []
    ) -> PantryGap? {
        guard let names = UseItUp.names(SavedRecipe(id: 0, title: title, language: language, lines: lines)),
              pantry.contains(where: { $0.language == names.language }),
              let words = LanguageWords.forTag(names.language)
        else { return nil }
        var keys = Set<String>()
        var missing: [String] = []
        for name in names.names {
            let item = PantryMatch.find(name, language: names.language, pantry: pantry, decisions: decisions)
            guard PantryMatch.status(item) == .buy else { continue }
            if keys.insert(IngredientName.key(name, words: words)).inserted { missing.append(name) }
        }
        guard !missing.isEmpty else { return nil }
        let listed = groceries
            .filter { !$0.checked && LanguageWords.forTag($0.language)?.language == names.language }
            .compactMap { IngredientName.of($0.text, words: words) }
        let onList = missing.filter { name in listed.contains { IngredientName.matches(name, $0, words: words) } }
        let left = missing.filter { name in !listed.contains { IngredientName.matches(name, $0, words: words) } }
        return PantryGap(missing: left, onList: onList.count)
    }

    /// Every planned recipe's note in `meals` from `today` on, by `PlannedMeal.id`. A note (no
    /// recipe) and a meal in the past have none.
    static func gaps(
        _ meals: [PlannedMeal], pantry: [PantryItem], today: Int64,
        decisions: Decisions = .none, groceries: [GroceryItem] = []
    ) -> [Int64: PantryGap] {
        guard !pantry.isEmpty else { return [:] }
        var out: [Int64: PantryGap] = [:]
        for meal in meals where meal.recipeId != nil && meal.day >= today {
            if let gap = gap(
                title: meal.title ?? "", language: meal.language, lines: meal.ingredients, pantry: pantry,
                decisions: decisions, groceries: groceries
            ) {
                out[meal.id] = gap
            }
        }
        return out
    }

    /// The lines "Add to groceries" puts on the list for `gap`: of `rendered` (the recipe's lines as
    /// What I need renders them, in `language`), each one naming a name still missing, in order.
    static func linesToAdd(_ gap: PantryGap, language: String?, rendered: [String]) -> [String] {
        guard let words = LanguageWords.forTag(language) else { return [] }
        let keys = Set(gap.missing.map { IngredientName.key($0, words: words) })
        return rendered.filter { line in
            guard GrocerySources.buyable(line), let name = IngredientName.of(line, words: words) else { return false }
            return keys.contains(IngredientName.key(name, words: words))
        }
    }
}
