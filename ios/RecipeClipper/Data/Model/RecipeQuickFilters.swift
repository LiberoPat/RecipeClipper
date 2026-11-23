import Foundation

/// The chips under Recipes' search (#378). They combine: a recipe must pass every one chosen, and
/// the search and the sort apply as before. Questions about what is stored, never a filing step,
/// like the views (#345). Not remembered: Recipes opens with none on. Android's `RecipeQuickFilter`.
enum RecipeQuickFilter: String, CaseIterable, Identifiable {
    /// Total time of `RecipeQuickFilters.quickMinutes` or less.
    case thirtyOrLess = "THIRTY_OR_LESS"
    /// No cooking entry yet: the Cooked view's complement.
    case notCooked = "NOT_COOKED"
    /// At least half of its nameable ingredients are in the pantry.
    case usesPantry = "USES_PANTRY"

    var id: String { rawValue }
}

/// How many of a recipe's nameable ingredient lines the pantry covers (`have` of `total`): what
/// "Uses what's in my pantry" decides on, and what each matching row says ("You have 6 of 8").
struct PantryCoverage: Equatable {
    let have: Int
    let total: Int

    /// At least half, and at least one line: a recipe with nothing nameable never matches.
    var matches: Bool { total > 0 && have * 2 >= total }
}

/// The quick filters of Recipes (#378), pure; Android's `RecipeQuickFilters`, pinned by the
/// corpus's `Mins`, `Quick` and `Cover` rows.
///
/// 30 min or less reads the recipe's total time as stored; with none, prep plus cook, and only when
/// both are present and readable (`Durations.minutes`). A time that can't be read ("Overnight",
/// "20 to 25 minutes", a zero) never matches: nothing is guessed. A total that is present but
/// unreadable doesn't fall back to prep plus cook. 30 min or less: 30 min itself counts.
/// Not cooked yet is `lastCookedDay == nil`: the Cooked view's own `EXISTS` turned round.
/// Uses what's in my pantry: of the recipe's nameable ingredient lines (a heading or a line with no
/// name aside), those the pantry has by `PantryMatch`, in stock or a staple, presence only:
/// `PantryCoverage.matches` when that is at least half. "rice flour" is never "flour".
enum RecipeQuickFilters {
    static let quickMinutes = 30

    /// The minutes `recipe` takes by its stored times, or nil when they can't be read.
    static func minutes(_ recipe: RecipeSummary) -> Int? {
        let words = LanguageWords.forTag(recipe.language) ?? (recipe.language == nil ? LanguageWords.english : nil)
        if let total = recipe.totalTime, !total.kIsBlank { return Durations.minutes(total, words: words) }
        guard let prep = recipe.prepTime.flatMap({ Durations.minutes($0, words: words) }),
              let cook = recipe.cookTime.flatMap({ Durations.minutes($0, words: words) }) else { return nil }
        return prep + cook
    }

    static func thirtyOrLess(_ recipe: RecipeSummary) -> Bool {
        minutes(recipe).map { $0 <= quickMinutes } ?? false
    }

    static func notCooked(_ recipe: RecipeSummary) -> Bool { recipe.lastCookedDay == nil }

    /// `recipe`'s coverage by `pantry`, or nil when its language has no tables (nothing can be named).
    static func coverage(_ recipe: SavedRecipe, pantry: [PantryItem]) -> PantryCoverage? {
        UseItUp.names(recipe).map { coverage($0, pantry: pantry) }
    }

    static func coverage(_ names: RecipeNames, pantry: [PantryItem]) -> PantryCoverage {
        PantryCoverage(
            have: names.names.filter {
                PantryMatch.status(PantryMatch.find($0, language: names.language, pantry: pantry)) != .buy
            }.count,
            total: names.names.count
        )
    }

    /// Every recipe's coverage by id; a recipe whose language has no tables has none.
    static func coverages(_ recipes: [SavedRecipe], pantry: [PantryItem]) -> [Int64: PantryCoverage] {
        var out: [Int64: PantryCoverage] = [:]
        for recipe in recipes {
            if let c = coverage(recipe, pantry: pantry) { out[recipe.id] = c }
        }
        return out
    }

    /// `recipes` that pass every chip in `filters`, in their order. `coverage` is `coverages`'
    /// answer, needed only for `.usesPantry`; a recipe with none there never matches that chip.
    static func apply(
        _ recipes: [RecipeSummary], filters: Set<RecipeQuickFilter>, coverage: [Int64: PantryCoverage] = [:]
    ) -> [RecipeSummary] {
        if filters.isEmpty { return recipes }
        return recipes.filter { r in
            (!filters.contains(.thirtyOrLess) || thirtyOrLess(r))
                && (!filters.contains(.notCooked) || notCooked(r))
                && (!filters.contains(.usesPantry) || coverage[r.id]?.matches == true)
        }
    }
}

/// "Cooked" in the filter sheet (#454): one choice, folding the Cooked view and "Not cooked yet".
enum CookedChoice: String, CaseIterable, Identifiable {
    case any = "ANY", cooked = "COOKED", notYet = "NOT_YET"
    var id: String { rawValue }
}

/// "Came from" in the filter sheet (#454): a recipe is typed by you or from a chat, never both, so one choice.
enum OriginChoice: String, CaseIterable, Identifiable {
    case anywhere = "ANYWHERE", typed = "TYPED", chat = "CHAT"
    var id: String { rawValue }
}

/// One filter on, as a pill under search (#454), in the sheet's order; a tap on it turns that filter off.
enum RecipeFilterPill: String, CaseIterable, Identifiable {
    case thirtyOrLess = "THIRTY_OR_LESS", cooked = "COOKED", notCooked = "NOT_COOKED", usesPantry = "USES_PANTRY"
    case notInAList = "NOT_IN_A_LIST", typed = "TYPED", chat = "CHAT"
    var id: String { rawValue }
}

/// The filter sheet's five controls (#454, `recipesFilters`), with the views folded in: two switches and a
/// choice about the recipe (30 min or less, Cooked: Any / Cooked / Not yet, Uses what's in my pantry), a switch
/// and a choice about where it is and where it came from (Not in a list, Came from: Anywhere / Typed by you /
/// From a chat). They combine (AND) with each other, the search and the sort; questions about what is stored,
/// never filing; not remembered. Android's `RecipeFilterChoices`.
///
/// Nothing is re-derived: `views` are the view screens' own filters (#345), asked of the one SQL query that
/// answers those screens; `quick` are #378's rules, applied over its answer. Not yet is "Not cooked yet".
struct RecipeFilterChoices: Equatable {
    var thirtyOrLess = false
    var cooked: CookedChoice = .any
    var usesPantry = false
    var notInAList = false
    var origin: OriginChoice = .anywhere

    static let none = RecipeFilterChoices()

    /// The view screens' filters these ask for, answered by the database beside the search.
    var views: Set<RecipeFilter> {
        var views: Set<RecipeFilter> = []
        if notInAList { views.insert(.notInAList) }
        if cooked == .cooked { views.insert(.cooked) }
        switch origin {
        case .typed: views.insert(.typed)
        case .chat: views.insert(.chat)
        case .anywhere: break
        }
        return views
    }

    /// #378's filters these ask for, applied to the database's answer (`RecipeQuickFilters.apply`).
    var quick: Set<RecipeQuickFilter> {
        var quick: Set<RecipeQuickFilter> = []
        if thirtyOrLess { quick.insert(.thirtyOrLess) }
        if cooked == .notYet { quick.insert(.notCooked) }
        if usesPantry { quick.insert(.usesPantry) }
        return quick
    }

    /// The filters on, in the sheet's order: the pills under search; their number is the badge's.
    var pills: [RecipeFilterPill] {
        var pills: [RecipeFilterPill] = []
        if thirtyOrLess { pills.append(.thirtyOrLess) }
        switch cooked {
        case .cooked: pills.append(.cooked)
        case .notYet: pills.append(.notCooked)
        case .any: break
        }
        if usesPantry { pills.append(.usesPantry) }
        if notInAList { pills.append(.notInAList) }
        switch origin {
        case .typed: pills.append(.typed)
        case .chat: pills.append(.chat)
        case .anywhere: break
        }
        return pills
    }

    var isEmpty: Bool { self == .none }

    /// These with `pill`'s filter off: a switch off, a choice back to Any / Anywhere.
    func without(_ pill: RecipeFilterPill) -> RecipeFilterChoices {
        var next = self
        switch pill {
        case .thirtyOrLess: next.thirtyOrLess = false
        case .cooked, .notCooked: next.cooked = .any
        case .usesPantry: next.usesPantry = false
        case .notInAList: next.notInAList = false
        case .typed, .chat: next.origin = .anywhere
        }
        return next
    }
}
