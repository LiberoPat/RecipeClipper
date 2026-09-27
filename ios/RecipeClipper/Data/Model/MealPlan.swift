import Foundation

/// A meal type (#49): Breakfast, Lunch, Dinner and Snack are seeded; the user can add more,
/// rename and reorder any, and delete their own. `builtInKey` names a seeded one whatever it
/// has been renamed to; nil for the user's own, which are the only ones that can be deleted.
struct MealType: Equatable, Identifiable {
    let id: Int64
    let name: String
    let builtInKey: String?
    let sortOrder: Int

    var isBuiltIn: Bool { builtInKey != nil }

    /// New plan entries default to Dinner, and a deleted type's entries move to it.
    static let dinner = "dinner"
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
}

/// A reusable weekly menu (#52; Android's `Menu`, renamed so it never shadows SwiftUI's): a named copy of a week's meals. `mealCount` is how many meals
/// applying it adds.
struct WeekMenu: Equatable, Identifiable {
    let id: Int64
    let name: String
    let mealCount: Int
}
