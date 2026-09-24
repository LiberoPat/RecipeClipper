import Foundation

// One export file: every recipe, list and membership, as plain data. Ported from Android's
// data/backup/Backup.kt; both platforms test against the same fixture files in
// shared/fixtures/backup/, which is what makes an export from one readable on the other.
//
// Every record carries a stable `id`: the row's `uid`, which never changes on the phone that
// made it (a rename or a re-share keeps it). Memberships refer to those ids, never to database
// row ids.
//
// Deliberately not in the file: cached photos (only `imageUrl`) and cook progress (timers, the
// current step, the chosen servings), which live in memory today and, if #10 persists them,
// are still a moment in one kitchen rather than part of the recipe.

struct Backup: Equatable {
    /// The `format` marker: tells an export apart from any other JSON file.
    static let format = "recipe-clipper-backup"

    /// Bumped only when an older app would misread a newer file. Adding a field or a whole new
    /// top-level section (a meal plan, say) doesn't need a bump: readers ignore keys they don't
    /// know, and import only ever adds.
    static let formatVersion = 1

    var exportedAt: Int64
    var recipes: [BackupRecipe]
    var lists: [BackupList]
    var memberships: [BackupMembership]
}

struct BackupRecipe: Equatable {
    var id: String
    var sourceUrl: String
    /// "BLOG" or "REDDIT"; anything else is read as BLOG when imported.
    var sourceType: String
    var title: String
    var imageUrl: String?
    var ingredients: [String]
    var instructions: [String]
    var prepTime: String?
    var cookTime: String?
    var totalTime: String?
    var servings: String?
    var lastViewedAt: Int64
    var checkedIngredients: Set<Int>
    var notes: String?
}

struct BackupList: Equatable {
    var id: String
    var name: String
    var isFavorites: Bool
    var isBuiltIn: Bool
    var sortOrder: Int
    var createdAt: Int64
}

struct BackupMembership: Equatable {
    var recipeId: String
    var listId: String
    var addedAt: Int64
}

/// Why an export or an import failed, as a cause: the Settings screen picks the words. An import
/// that fails for any of these has written nothing.
enum BackupError: Error, Equatable {
    /// Not JSON, not an object, no `format` marker, or far too big to be an export.
    case notABackup
    /// Made by a newer app whose format this one can't read.
    case newerVersion(found: Int)
    /// An export, but damaged: the detail names the first bad field (diagnostic, not copy).
    case malformed(String)
    /// The picked file couldn't be opened or read.
    case readFailed
    /// The database refused the import. It ran in one transaction, so nothing was written.
    case saveFailed
    /// The recipes couldn't be read out, or the file couldn't be written.
    case exportFailed
}

/// What an import did, for the one-line summary.
struct ImportSummary: Equatable {
    /// New recipes written.
    var recipesAdded: Int
    /// New lists created (lists that combined with one already here don't count).
    var listsAdded: Int
    /// Recipes in the file that were already on this phone (same cleaned link).
    var recipesAlreadyHere: Int
    /// Recipes in no list left out because history was full (see BackupMerger).
    var recipesSkipped: Int
}

/// An export ready to hand to the share sheet.
struct ExportedBackup: Equatable {
    var json: String
    var exportedAt: Int64
    var recipeCount: Int
}
