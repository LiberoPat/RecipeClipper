import Foundation

/// Why a Paprika file wasn't imported (#382; Android's `PaprikaError`): the cause, never the
/// words (the screen picks those).
enum PaprikaError: Equatable {
    /// Not a zip, or a zip with no `.paprikarecipe` entry in it.
    case notAPaprikaFile
    /// A zip that is cut short or broken in its structure. Nothing was imported.
    case damaged
    /// More entries, or more unpacked text, than any real export holds (`PaprikaLimits`).
    case tooLarge
    /// The file couldn't be opened.
    case readFailed
    /// The database refused the write; the import is one transaction, so nothing was written.
    case saveFailed
}

/// How much of a file is believed (#382; Android's `PaprikaLimits`). A hostile file can name a
/// million entries or unpack a few kilobytes to gigabytes, so nothing is trusted: an entry's
/// declared size is checked, and its real size is counted as it unpacks. The defaults are far
/// beyond a real export. Tests pass small ones.
struct PaprikaLimits: Equatable {
    var maxEntries = 10_000
    /// One recipe's unpacked JSON, photo included.
    var maxEntryBytes = 32 * 1024 * 1024
    /// Every recipe's unpacked JSON together.
    var maxTotalBytes = 2 * 1024 * 1024 * 1024
    /// The file itself.
    var maxFileBytes = 2 * 1024 * 1024 * 1024
}

/// What a Paprika file held: its readable recipes, and how many entries weren't.
struct PaprikaContents: Equatable {
    var recipes: [PaprikaRecipe]
    /// Entries that read as JSON objects but are no recipe (no name, or no ingredients and steps).
    var notRecipes: Int
    /// Entries that couldn't be unpacked or read as JSON.
    var unreadable: Int
}

/// What an import from Paprika did (#382; Android's `PaprikaSummary`). Counts only; the screen
/// picks the words.
struct PaprikaSummary: Equatable {
    /// Recipes written.
    var imported: Int
    /// In the file, but already here (the same link, or the same Paprika recipe imported before), or twice in the file.
    var alreadyHere: Int
    /// Left out because the free library was full (nothing old is removed to make room).
    var skippedForLimit: Int
    /// Entries that read as JSON but had no name, or neither ingredients nor steps.
    var notRecipes: Int
    /// Entries that couldn't be unpacked or read.
    var unreadable: Int
    /// The file carried pictures of its own, which the app doesn't keep.
    var photosNotImported: Bool
    /// The free library's size when `skippedForLimit` ran into it, else nil.
    var freeLimit: Int?
}

/// Paprika's recipes as a `Backup` (#382; Android's `PaprikaImport`), so they merge like any
/// import: through `BackupMerger`, the one place that applies the library limit to a file. Pure.
///
/// - **Identity** (what makes a second import of the same file add nothing): the recipe's cleaned
///   source link when it has a real one, so it also meets the same recipe shared or clipped here;
///   else `manual:paprika-<uid>` from Paprika's own id; else `manual:paprika-h<hash>` of its name
///   and text, for a file with no ids. A recipe already here is never changed (the merger keeps
///   what is here, and only fills an empty note).
/// - **Origin:** `EDITED` for a recipe with a link (the cook's version: a re-share of its link
///   opens it as imported, never fetched over it; "Update from source" is the way back) and
///   `MANUAL` for one with none (Paprika's typed-in recipes, like the ones typed in here).
/// - **Text stays as written:** names, lines, notes and servings are Paprika's, trimmed. A time
///   that is a whole phrase ("1 hour 30 minutes") renders like ISO ("1h 30m") through
///   `Durations`; any other ("Overnight") stays. Categories are not filed into lists.
/// - **Order:** the first recipe in the file is the most recently viewed, one millisecond apart,
///   so the file's order is kept in Recipes and decides which fit at the free limit.
enum PaprikaImport {

    /// A file's recipes ready for `BackupMerger`, and how many repeated an earlier one in the same file.
    struct Mapped: Equatable {
        var backup: Backup
        var repeated: Int
    }

    static func identity(_ recipe: PaprikaRecipe) -> String {
        if let link = recipe.sourceUrl { return UrlCleaner.clean(link) }
        if let uid = recipe.uid, isPlain(uid) { return ManualRecipe.newSourceUrl("paprika-" + uid.lowercased()) }
        return ManualRecipe.newSourceUrl("paprika-h" + contentHash(recipe))
    }

    /// `[A-Za-z0-9-]{1,64}`: an id that can't smuggle a link or a path into the key.
    private static func isPlain(_ uid: String) -> Bool {
        let bytes = Array(uid.utf8)
        return !bytes.isEmpty && bytes.count <= 64 && bytes.allSatisfy {
            ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x41 && $0 <= 0x5A) || ($0 >= 0x61 && $0 <= 0x7A) || $0 == 0x2D
        }
    }

    /// FNV-1a (64 bits) over the name and text: the same on both platforms, and nothing secret.
    static func contentHash(_ recipe: PaprikaRecipe) -> String {
        let text = ([recipe.name] + recipe.ingredients + recipe.directions).joined(separator: "\n")
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in text.utf8 {
            hash = (hash ^ UInt64(byte)) &* 0x100_0000_01b3
        }
        let hex = String(hash, radix: 16)
        return String(repeating: "0", count: 16 - hex.count) + hex
    }

    static func toBackup(_ recipes: [PaprikaRecipe], now: Int64, newUid: () -> String) -> Mapped {
        var seen = Set<String>()
        var mapped: [BackupRecipe] = []
        var repeated = 0
        for recipe in recipes {
            let key = identity(recipe)
            if !seen.insert(key).inserted {
                repeated += 1
                continue
            }
            let language = LanguageWords.resolve(declared: nil, page: nil) {
                LanguageWords.detectionText(name: recipe.name, ingredients: recipe.ingredients)
            }
            let words = LanguageWords.forTag(language)
            func time(_ text: String?) -> String? { text.flatMap { Durations.format($0, words: words) } }
            let hasLink = recipe.sourceUrl != nil
            mapped.append(BackupRecipe(
                id: newUid(),
                sourceUrl: key,
                sourceType: "BLOG",
                title: recipe.name,
                imageUrl: recipe.imageUrl,
                ingredients: recipe.ingredients,
                instructions: recipe.directions,
                prepTime: time(recipe.prepTime),
                cookTime: time(recipe.cookTime),
                totalTime: time(recipe.totalTime),
                servings: recipe.servings,
                lastViewedAt: now - Int64(mapped.count),
                checkedIngredients: [],
                notes: recipe.notes,
                language: language,
                contentOrigin: (hasLink ? ContentOrigin.edited : ContentOrigin.manual).rawValue,
                editedAt: now
            ))
        }
        return Mapped(
            backup: Backup(exportedAt: now, recipes: mapped, lists: [], memberships: []),
            repeated: repeated
        )
    }
}
