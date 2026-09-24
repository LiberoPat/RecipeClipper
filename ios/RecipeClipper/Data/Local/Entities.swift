import Foundation

// Database rows, distinct from the domain types in Model/Recipe.swift (the repositories map
// between them in RecipeMapping.swift). Mirrors Android's RecipeEntity / ListRow /
// RecipeSummaryRow. Cross-ref rows use the domain `ListMembership` directly: its three fields
// are exactly the table's three columns.

/// One row of `recipes`.
struct RecipeRecord: Equatable {
    var id: Int64 = 0                 // 0 = not yet inserted; SQLite assigns one
    var sourceUrl: String             // unique: re-sharing a link upserts instead of duplicating
    var title: String
    var imageUrl: String?
    var ingredients: [String]         // JSON text
    var instructions: [String]        // JSON text
    var prepTime: String?
    var cookTime: String?
    var totalTime: String?
    var servings: String?             // the recipe's yield text as published
    var sourceType: String            // BLOG | REDDIT, for re-fetch
    var lastViewedAt: Int64
    var checkedIngredients: Set<Int> = []   // JSON text, sorted
    var notes: String? = nil                // the user's own note; kept across re-shares
    /// Stable across devices and exports (#26): what an export file calls this recipe. Never
    /// changes once written — `update` doesn't touch it, and an import keeps the file's.
    var uid: String = newUid()
    var language: String? = nil             // the recipe's language tag (#14); nil before user_version 4
    var cookState: String? = nil            // CookProgress as JSON (CookStateJSON); kept if steps unchanged
    var servingsTarget: Int? = nil          // the chosen servings; nil = the recipe's own yield

    /// The column list every `SELECT` of a full row uses, in `init(row:)`'s order.
    static let columns = """
        id, sourceUrl, title, imageUrl, ingredients, instructions, prepTime, cookTime, \
        totalTime, servings, sourceType, lastViewedAt, checkedIngredients, notes, uid, language, \
        cookState, servingsTarget
        """

    init(
        id: Int64 = 0, sourceUrl: String, title: String, imageUrl: String?,
        ingredients: [String], instructions: [String],
        prepTime: String?, cookTime: String?, totalTime: String?, servings: String?,
        sourceType: String, lastViewedAt: Int64, checkedIngredients: Set<Int> = [],
        notes: String? = nil, uid: String = newUid(), language: String? = nil,
        cookState: String? = nil, servingsTarget: Int? = nil
    ) {
        self.id = id
        self.sourceUrl = sourceUrl
        self.title = title
        self.imageUrl = imageUrl
        self.ingredients = ingredients
        self.instructions = instructions
        self.prepTime = prepTime
        self.cookTime = cookTime
        self.totalTime = totalTime
        self.servings = servings
        self.sourceType = sourceType
        self.lastViewedAt = lastViewedAt
        self.checkedIngredients = checkedIngredients
        self.notes = notes
        self.uid = uid
        self.language = language
        self.cookState = cookState
        self.servingsTarget = servingsTarget
    }

    init(row: SQLiteRow) {
        id = row.int64(0)
        sourceUrl = row.string(1)
        title = row.string(2)
        imageUrl = row.optionalString(3)
        ingredients = JSONColumns.decodeStrings(row.string(4))
        instructions = JSONColumns.decodeStrings(row.string(5))
        prepTime = row.optionalString(6)
        cookTime = row.optionalString(7)
        totalTime = row.optionalString(8)
        servings = row.optionalString(9)
        sourceType = row.string(10)
        lastViewedAt = row.int64(11)
        checkedIngredients = JSONColumns.decodeInts(row.string(12))
        notes = row.optionalString(13)
        uid = row.string(14)
        language = row.optionalString(15)
        cookState = row.optionalString(16)
        servingsTarget = row.isNull(17) ? nil : row.int(17)
    }
}

/// A fresh stable id for a new row: a lowercase UUID, the same form Android and the
/// migration's backfill use.
func newUid() -> String { UUID().uuidString.lowercased() }

/// A recipe's saved cook progress, with what a timer alert needs to name it (Android's
/// `CookStateRow`).
struct CookStateRecord: Equatable {
    let id: Int64
    let title: String
    let cookState: String
}

/// One row of a list of recipes: everything except the ingredients and steps.
struct RecipeSummaryRecord: Equatable {
    let id: Int64
    let title: String
    let imageUrl: String?
    let totalTime: String?
    let lastViewedAt: Int64
    let isSaved: Bool

    init(row: SQLiteRow) {
        id = row.int64(0)
        title = row.string(1)
        imageUrl = row.optionalString(2)
        totalTime = row.optionalString(3)
        lastViewedAt = row.int64(4)
        isSaved = row.bool(5)
    }
}

/// One list as a screen needs it. `containsRecipe` is always false when the query was asked
/// about `ListDao.noRecipe`.
struct ListRecord: Equatable {
    let id: Int64
    let name: String
    let isBuiltIn: Bool
    let isFavorites: Bool
    let recipeCount: Int
    let containsRecipe: Bool

    init(row: SQLiteRow) {
        id = row.int64(0)
        name = row.string(1)
        isBuiltIn = row.bool(2)
        isFavorites = row.bool(3)
        recipeCount = row.int(4)
        containsRecipe = row.bool(5)
    }
}

extension ListMembership {
    init(row: SQLiteRow) {
        self.init(recipeId: row.int64(0), listId: row.int64(1), addedAt: row.int64(2))
    }
}

/// The ingredient and step lists, and the ticked-ingredient set, are stored as JSON text in a
/// single column (Android's Converters). JSON rather than a join so a comma or quote in an
/// ingredient survives the round trip. Slashes are left unescaped so a search for "1/2" can
/// find "1/2 cup" in the stored ingredients column.
enum JSONColumns {
    static func encode(_ strings: [String]) -> String {
        encodeJSON(strings)
    }

    static func encode(_ ints: Set<Int>) -> String {
        encodeJSON(ints.sorted())
    }

    static func decodeStrings(_ json: String) -> [String] {
        (decodeJSON(json) as? [Any])?.compactMap { $0 as? String } ?? []
    }

    static func decodeInts(_ json: String) -> Set<Int> {
        Set((decodeJSON(json) as? [Any])?.compactMap { ($0 as? NSNumber)?.intValue } ?? [])
    }

    private static func encodeJSON(_ array: [Any]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: array, options: [.withoutEscapingSlashes]),
              let text = String(data: data, encoding: .utf8)
        else { return "[]" }
        return text
    }

    private static func decodeJSON(_ json: String) -> Any? {
        guard let data = json.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: data)
    }
}
