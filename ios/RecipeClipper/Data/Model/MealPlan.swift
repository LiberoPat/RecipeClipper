import Foundation

/// A meal type (#49): Breakfast, Lunch, Dinner and Snack are seeded; the user can add more,
/// rename and reorder any, and delete their own. `builtInKey` names a seeded one whatever it
/// has been renamed to; nil for the user's own, which are the only ones that can be deleted.
struct MealType: Equatable, Identifiable {
    let id: Int64
    let name: String
    let builtInKey: String?
    let sortOrder: Int
    /// The type's stable uid (#26); empty where a type was made without one (a test).
    var uid = ""

    var isBuiltIn: Bool { builtInKey != nil }

    /// What a setting stores to name this type (#392): `builtInKey` for a seeded one, which survives
    /// a rename, else the `uid`. Never the name, which the cook can change.
    var key: String { builtInKey ?? uid }

    /// New plan entries default to Dinner, and a deleted type's entries move to it.
    static let dinner = "dinner"

    /// The type a new plan entry defaults to: Dinner, else the first; nil with no types.
    static func defaultTypeId(_ types: [MealType]) -> Int64? {
        (types.first { $0.builtInKey == dinner } ?? types.first)?.id
    }

    /// The keys (`key`) of the types ticked in "Plan my week" (#392): `picked` (what the cook chose,
    /// or last time's choice) kept to the types that still exist; with none left, Dinner's (else
    /// the first type's). An empty `picked` (the cook unticked everything) stays empty; the fallback is
    /// for nothing picked yet (nil) or none of the picked types left. Empty also when there are no types.
    static func chosenKeys(_ types: [MealType], _ picked: [String]?) -> Set<String> {
        if let picked, picked.isEmpty { return [] }
        let kept = Set(types.map(\.key).filter { !$0.isEmpty && (picked?.contains($0) ?? false) })
        if !kept.isEmpty { return kept }
        let preferred = types.first { $0.builtInKey == dinner } ?? types.first
        guard let key = preferred?.key, !key.isEmpty else { return [] }
        return [key]
    }

    /// The type a plan's "meal" names (#392): the one with that name, ignoring case and surrounding
    /// spaces (the first in the cook's order when two share it); anything else, or no meal, is
    /// `defaultTypeId` (Dinner). Never guessed from the recipe.
    static func idForMeal(_ meal: String?, _ types: [MealType]) -> Int64? {
        let wanted = meal.map { $0.kTrimmed.lowercased() }.flatMap { $0.isEmpty ? nil : $0 }
        let named = wanted.flatMap { w in types.first { $0.name.kTrimmed.lowercased() == w } }
        return named?.id ?? defaultTypeId(types)
    }
}

/// One meal on the plan: a recipe with its planned servings, or a free-text note. `title` and
/// `imageUrl` are the recipe's, joined in; both nil for a note.
struct PlannedMeal: Equatable, Identifiable {
    let id: Int64
    let day: Int64
    let mealTypeId: Int64
    let recipeId: Int64?
    let title: String?
    let imageUrl: String?
    let servings: Int?
    let note: String?
    /// The entry's stable uid (#26): what a calendar export (#52) names the event by.
    var uid = ""
    /// The recipe's own uid and language, for its no-photo image (#403); nil for a note.
    var recipeUid: String? = nil
    var language: String? = nil
    /// The recipe's ingredient lines, for the label of its no-photo image on Home's Today card
    /// (#403, #402); empty for a note.
    var ingredients: [String] = []
}

/// A reusable weekly menu (#52; Android's `Menu`, renamed so it never shadows SwiftUI's): a named copy of a week's meals. `mealCount` is how many meals
/// applying it adds.
struct WeekMenu: Equatable, Identifiable {
    let id: Int64
    let name: String
    let mealCount: Int
}
