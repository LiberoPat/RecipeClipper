import Foundation

/// Why a Paprika file wasn't imported (#382; Android's `PaprikaError`): the cause, never the
/// words (the screen picks those). The causes are shared with the Recipe Keeper import (#390),
/// which reads through the same repository.
enum PaprikaError: Equatable {
    /// Not a zip, or a zip with no `.paprikarecipe` entry in it.
    case notAPaprikaFile
    /// Not a zip, or a zip with no `recipes.html` of Recipe Keeper's in it (#390).
    case notARecipeKeeperFile
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
    /// One recipe's picture once decoded from base64 (#386): bigger is left out, the recipe kept.
    var maxPhotoBytes = 8 * 1024 * 1024
}

/// What a Paprika file held: its readable recipes, and how many entries weren't.
struct PaprikaContents: Equatable {
    var recipes: [PaprikaRecipe]
    /// Entries that read as JSON objects but are no recipe (no name, or no ingredients and steps).
    var notRecipes: Int
    /// Entries that couldn't be unpacked or read as JSON.
    var unreadable: Int
    /// Pictures the file carried that were left out: too big, not an image, or not stored (#386). The recipe stays.
    var photosLeftOut = 0
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
    /// Pictures the file carried that were left out (#386): too big, or not an image. The recipe came in.
    var photosLeftOut: Int
    /// The free library's size when `skippedForLimit` ran into it, else nil.
    var freeLimit: Int?
    /// New lists made from the categories (#387); a list that was already here doesn't count.
    var listsMade = 0
}

/// Paprika's recipes as a `Backup` (#382; Android's `PaprikaImport`), so they merge like any
/// import: through `BackupMerger`, the one place that applies the library limit to a file. Pure. The Recipe Keeper import (#390)
/// maps its recipes to the same `PaprikaRecipe` and comes through here too; its `ImportSource` only
/// changes the prefix of a recipe's own key (`manual:recipekeeper-<recipeId>`, else
/// `manual:recipekeeper-h<hash>`).
///
/// - **Identity** (what makes a second import of the same file add nothing): the recipe's cleaned
///   source link when it has a real one, so it also meets the same recipe shared or clipped here;
///   else `manual:paprika-<uid>` from Paprika's own id; else `manual:paprika-h<hash>` of its name
///   and text, for a file with no ids. A recipe already here is never changed (the merger keeps
///   what is here, and only fills an empty note and a missing picture).
/// - **Origin:** `EDITED` for a recipe with a link (the cook's version: a re-share of its link
///   opens it as imported, never fetched over it; "Update from source" is the way back) and
///   `MANUAL` for one with none (Paprika's typed-in recipes, like the ones typed in here).
/// - **Text stays as written:** names, lines, notes and servings are Paprika's, trimmed. A time
///   that is a whole phrase ("1 hour 30 minutes") renders like ISO ("1h 30m") through
///   `Durations`; any other ("Overnight") stays.
/// - **Pictures** (#386): `PaprikaRecipe.photoFile`, already stored, becomes `BackupRecipe.photo`
///   under its own name (the repository maps each name to itself for the merger).
/// - **Lists** (#387), only when `filing` is on: each category puts the recipe in the list of that
///   name (the merger finds one here with the same name, trimmed and case-insensitive, else makes
///   one; categories that differ only in case are one list, the first spelling kept), and Paprika's
///   own favourite flag puts it in Favorites, which the merger finds by `isFavorites`, never by
///   name. A repeat of a recipe in the same file adds its categories to the first's. Nothing is
///   taken out of a list (memberships are insert-or-ignore).
/// - **Order:** the first recipe in the file is the most recently viewed, one millisecond apart,
///   so the file's order is kept in Recipes and decides which fit at the free limit.
enum PaprikaImport {

    /// A file's recipes ready for `BackupMerger`, and how many repeated an earlier one in the same file.
    struct Mapped: Equatable {
        var backup: Backup
        var repeated: Int
    }

    static func identity(_ recipe: PaprikaRecipe, source: ImportSource = .paprika) -> String {
        if let link = recipe.sourceUrl { return UrlCleaner.clean(link) }
        let prefix = source.keyPrefix
        if let uid = recipe.uid, isPlain(uid) { return ManualRecipe.newSourceUrl("\(prefix)-" + uid.lowercased()) }
        return ManualRecipe.newSourceUrl("\(prefix)-h" + contentHash(recipe))
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

    private final class Entry {
        let recipe: PaprikaRecipe
        let backup: BackupRecipe
        var categories: [(key: String, name: String)] = []
        var favorite: Bool

        init(recipe: PaprikaRecipe, backup: BackupRecipe) {
            self.recipe = recipe
            self.backup = backup
            favorite = recipe.favorite
        }

        func add(_ names: [String]) {
            for name in names where !name.kIsBlank {
                let key = BackupMerger.nameKey(name)
                if !categories.contains(where: { $0.key == key }) { categories.append((key, name)) }
            }
        }
    }

    static func toBackup(
        _ recipes: [PaprikaRecipe], now: Int64, filing: Bool = false, source: ImportSource = .paprika, newUid: () -> String
    ) -> Mapped {
        var order: [String] = []
        var entries: [String: Entry] = [:]
        var repeated = 0
        for recipe in recipes {
            let key = identity(recipe, source: source)
            if let seen = entries[key] {
                repeated += 1
                seen.add(recipe.categories)
                if recipe.favorite { seen.favorite = true }
                continue
            }
            let language = LanguageWords.resolve(declared: nil, page: nil) {
                LanguageWords.detectionText(name: recipe.name, ingredients: recipe.ingredients)
            }
            let words = LanguageWords.forTag(language)
            func time(_ text: String?) -> String? { text.flatMap { Durations.format($0, words: words) } }
            let hasLink = recipe.sourceUrl != nil
            let backup = BackupRecipe(
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
                lastViewedAt: now - Int64(order.count),
                checkedIngredients: [],
                notes: recipe.notes,
                language: language,
                contentOrigin: (hasLink ? ContentOrigin.edited : ContentOrigin.manual).rawValue,
                editedAt: now,
                photo: recipe.photoFile
            )
            let entry = Entry(recipe: recipe, backup: backup)
            entry.add(recipe.categories)
            entries[key] = entry
            order.append(key)
        }

        var lists: [BackupList] = []
        var memberships: [BackupMembership] = []
        if filing {
            var byName: [String: BackupList] = [:]
            var favorites: BackupList?
            for key in order {
                guard let entry = entries[key] else { continue }
                if entry.favorite {
                    let list = favorites ?? BackupList(
                        id: newUid(), name: "Favorites", isFavorites: true, isBuiltIn: true, sortOrder: 0, createdAt: now
                    )
                    if favorites == nil {
                        favorites = list
                        lists.append(list)
                    }
                    memberships.append(BackupMembership(recipeId: entry.backup.id, listId: list.id, addedAt: now))
                }
                for category in entry.categories {
                    let list = byName[category.key] ?? BackupList(
                        id: newUid(), name: category.name.kTrimmed, isFavorites: false, isBuiltIn: false, sortOrder: 0, createdAt: now
                    )
                    if byName[category.key] == nil {
                        byName[category.key] = list
                        lists.append(list)
                    }
                    memberships.append(BackupMembership(recipeId: entry.backup.id, listId: list.id, addedAt: now))
                }
            }
        }
        return Mapped(
            backup: Backup(exportedAt: now, recipes: order.compactMap { entries[$0]?.backup }, lists: lists, memberships: memberships),
            repeated: repeated
        )
    }
}
