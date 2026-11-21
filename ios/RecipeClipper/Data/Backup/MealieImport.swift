import Foundation

/// What an import from Mealie did (#391; Android's `MealieSummary`). Counts only; the screen picks the words.
struct MealieSummary: Equatable {
    /// Recipes written.
    var imported: Int
    /// In the file, but already here (the same link, or the same Mealie recipe imported before), or twice in the file.
    var alreadyHere: Int
    /// Left out because the free library was full (nothing old is removed to make room).
    var skippedForLimit: Int
    /// JSON files that read, but weren't a Mealie recipe, or had no name, or neither ingredients nor steps.
    var notRecipes: Int
    /// Files that couldn't be unpacked or read.
    var unreadable: Int
    /// Pictures that came with a recipe but were left out (#386): too big, or not an image. The recipe came in.
    var photosLeftOut: Int
    /// The free library's size when `skippedForLimit` ran into it, else nil.
    var freeLimit: Int?
    /// New lists made from the categories and tags (#387); a list that was already here doesn't count.
    var listsMade = 0
}

/// Mealie's recipes as a `Backup` (#391; Android's `MealieImport`), so they merge like any import: through
/// `BackupMerger`, the one place that applies the library limit to a file. The same rules as `PaprikaImport`,
/// with Mealie's own names. Pure.
///
/// - **Identity** (what makes a second import of the same file add nothing): the recipe's cleaned `orgURL`
///   when it has a real one, so it also meets the same recipe shared or clipped here; else
///   `manual:mealie-<slug>` (a plain slug), else `manual:mealie-<id>` (a plain id), else
///   `manual:mealie-h<hash>` of its name and text. A recipe already here is never changed (the merger keeps
///   what is here, and only fills an empty note and a missing picture).
/// - **Origin:** `EDITED` for a recipe with a link (the cook's version: a re-share of its link opens it as
///   imported, never fetched over it) and `MANUAL` for one with none.
/// - **Times** go through `Durations` with the words of the recipe's language (detected from its own name and
///   ingredients): "1 hour 30 minutes" renders "1h 30m", a zero total is absent, "Overnight" stays as written.
/// - **Lists** (#387), only when `filing` is on: each category and tag puts the recipe in the list of that
///   name (found here by the trimmed, case-insensitive name, else made). Mealie's favourites belong to a user
///   and are not in a recipe's file, so nothing goes to Favorites.
/// - **Order:** the first recipe in the file is the most recently viewed, one millisecond apart.
enum MealieImport {

    /// A file's recipes ready for `BackupMerger`, and how many repeated an earlier one in the same file.
    struct Mapped: Equatable {
        var backup: Backup
        var repeated: Int
    }

    static func identity(_ recipe: MealieRecipe) -> String {
        if let link = recipe.sourceUrl { return UrlCleaner.clean(link) }
        if isPlain(recipe.slug) { return ManualRecipe.newSourceUrl("mealie-" + recipe.slug.lowercased()) }
        if let id = recipe.id, isPlain(id) { return ManualRecipe.newSourceUrl("mealie-" + id.lowercased()) }
        return ManualRecipe.newSourceUrl("mealie-h" + contentHash(recipe))
    }

    /// `[A-Za-z0-9_-]{1,100}`: a name that can't smuggle a link or a path into the key.
    private static func isPlain(_ name: String) -> Bool {
        let bytes = Array(name.utf8)
        return !bytes.isEmpty && bytes.count <= 100 && bytes.allSatisfy {
            ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x41 && $0 <= 0x5A) || ($0 >= 0x61 && $0 <= 0x7A) || $0 == 0x2D || $0 == 0x5F
        }
    }

    /// FNV-1a (64 bits) over the name and text: the same on both platforms, and nothing secret.
    static func contentHash(_ recipe: MealieRecipe) -> String {
        let text = ([recipe.name] + recipe.ingredients + recipe.instructions).joined(separator: "\n")
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in text.utf8 {
            hash = (hash ^ UInt64(byte)) &* 0x100_0000_01b3
        }
        let hex = String(hash, radix: 16)
        return String(repeating: "0", count: 16 - hex.count) + hex
    }

    private final class Entry {
        let backup: BackupRecipe
        var categories: [(key: String, name: String)] = []

        init(backup: BackupRecipe) { self.backup = backup }

        func add(_ names: [String]) {
            for name in names where !name.kIsBlank {
                let key = BackupMerger.nameKey(name)
                if !categories.contains(where: { $0.key == key }) { categories.append((key, name)) }
            }
        }
    }

    static func toBackup(_ recipes: [MealieRecipe], now: Int64, filing: Bool = false, newUid: () -> String) -> Mapped {
        var order: [String] = []
        var entries: [String: Entry] = [:]
        var repeated = 0
        for recipe in recipes {
            let key = identity(recipe)
            if let seen = entries[key] {
                repeated += 1
                seen.add(recipe.categories)
                continue
            }
            let language = LanguageWords.resolve(declared: nil, page: nil) {
                LanguageWords.detectionText(name: recipe.name, ingredients: recipe.ingredients)
            }
            let words = LanguageWords.forTag(language)
            func time(_ text: String?) -> String? { text.flatMap { Durations.format($0, words: words) } }
            let backup = BackupRecipe(
                id: newUid(),
                sourceUrl: key,
                sourceType: "BLOG",
                title: recipe.name,
                imageUrl: nil,
                ingredients: recipe.ingredients,
                instructions: recipe.instructions,
                prepTime: time(recipe.prepTime),
                cookTime: time(recipe.cookTime),
                totalTime: time(recipe.totalTime),
                servings: recipe.servings,
                lastViewedAt: now - Int64(order.count),
                checkedIngredients: [],
                notes: recipe.notes,
                language: language,
                contentOrigin: (recipe.sourceUrl != nil ? ContentOrigin.edited : ContentOrigin.manual).rawValue,
                editedAt: now,
                photo: recipe.photoFile
            )
            let entry = Entry(backup: backup)
            entry.add(recipe.categories)
            entries[key] = entry
            order.append(key)
        }

        var lists: [BackupList] = []
        var memberships: [BackupMembership] = []
        if filing {
            var byName: [String: BackupList] = [:]
            for key in order {
                guard let entry = entries[key] else { continue }
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
