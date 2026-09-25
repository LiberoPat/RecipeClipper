import Foundation

/// A recipe already on this phone, as far as merging needs to know it.
struct ExistingRecipe: Equatable {
    var id: Int64
    var uid: String
    var sourceUrl: String
    var hasNotes: Bool
    /// In at least one list, or typed in by hand (#102): outside the history cap either way.
    var isListed: Bool
}

/// A list already on this phone. Pass them in display order: a name match takes the first.
struct ExistingList: Equatable {
    var id: Int64
    var uid: String
    var name: String
    var isFavorites: Bool
}

/// What a planned membership or note points at: a row already here, or one the import adds
/// (keyed by the uid it will be written with).
enum MergeTarget: Hashable {
    case existing(Int64)
    case new(String)
}

/// A pantry item already on this phone (#51).
struct ExistingPantryItem: Equatable {
    var uid: String
    var name: String
    var language: String?
}

/// A grocery item to write, with the recipe it came from, if that recipe is here after the import.
struct NewGrocery: Equatable {
    var item: BackupGroceryItem
    var recipe: MergeTarget?
}

/// A meal type already on this phone (#49). `builtInKey` names a seeded one.
struct ExistingMealType: Equatable {
    var id: Int64
    var uid: String
    var name: String
    var builtInKey: String?
}

/// A user meal type to create, after the ones already here.
struct NewMealType: Equatable {
    var uid: String
    var name: String
    var sortOrder: Int
    var updatedAt: Int64
}

/// A planned meal to write: its meal type, and its recipe (nil for a note). The DAO puts it at
/// the end of its day and meal type, after what's already planned there.
struct NewPlanEntry: Equatable {
    var entry: BackupPlanEntry
    var mealType: MergeTarget
    var recipe: MergeTarget?
}

/// A menu meal to write (#52): like `NewPlanEntry`, with its uid final.
struct NewMenuEntry: Equatable {
    var entry: BackupMenuEntry
    var mealType: MergeTarget
    var recipe: MergeTarget?
}

/// A menu to write (#52), with its meals.
struct NewMenu: Equatable {
    var menu: BackupMenu
    var entries: [NewMenuEntry]
}

struct NewList: Equatable {
    var uid: String
    var name: String
    var isBuiltIn: Bool
    var sortOrder: Int
    var createdAt: Int64
}

struct NoteUpdate: Equatable {
    var recipeId: Int64
    var notes: String
}

struct PlannedMembership: Equatable {
    var recipe: MergeTarget
    var list: MergeTarget
    var addedAt: Int64
}

/// Everything an import will write, worked out before anything is written. `newRecipes` are
/// ready to insert: link cleaned, uid final, note blank-to-nil, ticks within range, sourceType
/// one the app knows.
struct ImportPlan: Equatable {
    var newRecipes: [BackupRecipe]
    var noteUpdates: [NoteUpdate]
    var newLists: [NewList]
    var memberships: [PlannedMembership]
    var summary: ImportSummary
    var newPantry: [BackupPantryItem] = []
    var newGroceries: [NewGrocery] = []
    var newMealTypes: [NewMealType] = []
    var newPlanEntries: [NewPlanEntry] = []
    var newMenus: [NewMenu] = []
}

/// How an export file merges into a phone that already has recipes (issue #26; the owner's
/// decision: merge, never replace, never delete). A port of Android's BackupMerger; both are
/// tested against the same fixture and must agree. The rules are documented there in full:
/// recipes match by cleaned link (first copy in the file wins), an existing recipe keeps its
/// content and gains memberships and a note only if it has none; Favorites maps by
/// `isFavorites`, never by name; other lists join by uid, then by trimmed case-insensitive name
/// (never Favorites), then another list in the file, else are created after the lists here;
/// memberships are planned once per pair and written insert-or-ignore; importing never deletes
/// or culls, and new recipes in no list only fill free places under `historyLimit`. Pantry
/// items (#51) come in unless their uid, or their name in the same language, is here or earlier
/// in the file (what's here stands); grocery items (#50) unless their uid is here, after the
/// list's own, keeping a recipe only if it is here after the import. Meal types (#49): a seeded
/// one by `builtInKey`, never by name; a user's own by uid, then by name among the user types
/// here, then earlier in the file, else created after the types here. Planned meals come in
/// unless their uid is here, at the end of their day and meal type (Dinner if the file names
/// none): a note always, a recipe's only if the recipe is here after the import (else the meal
/// is dropped). A recipe the file plans for `today` or later comes in like a listed one.
/// Menus (#52): a menu comes in, with its meals, unless its uid is already here; one here is
/// left as it is. Its meals follow the plan's rules, its recipes come in like listed ones (the
/// cull keeps them too), and a menu left with no meals is dropped.
/// Typed-in recipes (#102, origin MANUAL) come in like listed ones, and one here counts as listed.
enum BackupMerger {

    static func plan(
        _ backup: Backup,
        existingRecipes: [ExistingRecipe],
        existingLists: [ExistingList],
        maxSortOrder: Int,
        historyLimit: Int,
        newUid: () -> String,
        existingPantry: [ExistingPantryItem] = [],
        existingGroceryUids: Set<String> = [],
        existingMealTypes: [ExistingMealType] = [],
        maxMealTypeSortOrder: Int = -1,
        existingPlanUids: Set<String> = [],
        today: Int64? = nil,
        existingMenuUids: Set<String> = [],
        existingMenuEntryUids: Set<String> = []
    ) -> ImportPlan {
        // --- Recipes: fold the file onto distinct cleaned links, then onto what's here.
        var existingByUrl: [String: ExistingRecipe] = [:]
        for recipe in existingRecipes where existingByUrl[UrlCleaner.clean(recipe.sourceUrl)] == nil {
            existingByUrl[UrlCleaner.clean(recipe.sourceUrl)] = recipe
        }
        var takenRecipeUids = Set(existingRecipes.map(\.uid))

        var recipeTargets: [String: MergeTarget] = [:]      // file recipe id -> target
        var newOrder: [String] = []                          // cleaned links, in file order
        var newByUrl: [String: BackupRecipe] = [:]
        var matchedOrder: [Int64] = []
        var matched: [Int64: ExistingRecipe] = [:]
        var importedNote: [Int64: String] = [:]              // existing id -> first non-blank note

        for recipe in backup.recipes {
            let url = UrlCleaner.clean(recipe.sourceUrl)
            let note = recipe.notes.flatMap { isBlank($0) ? nil : $0 }
            if let existing = existingByUrl[url] {
                if matched[existing.id] == nil { matchedOrder.append(existing.id) }
                matched[existing.id] = existing
                if let note, importedNote[existing.id] == nil { importedNote[existing.id] = note }
                recipeTargets[recipe.id] = .existing(existing.id)
                continue
            }
            if var first = newByUrl[url] {
                if first.notes == nil, let note {
                    first.notes = note
                    newByUrl[url] = first
                }
                recipeTargets[recipe.id] = .new(first.id)
                continue
            }
            let uid = takenRecipeUids.insert(recipe.id).inserted ? recipe.id : freshUid(&takenRecipeUids, newUid)
            var fresh = recipe
            fresh.id = uid
            fresh.sourceUrl = url
            fresh.sourceType = knownSourceTypes.contains(recipe.sourceType) ? recipe.sourceType : "BLOG"
            fresh.checkedIngredients = recipe.checkedIngredients.filter { recipe.ingredients.indices.contains($0) }
            fresh.notes = note
            newOrder.append(url)
            newByUrl[url] = fresh
            recipeTargets[recipe.id] = .new(uid)
        }

        // --- Lists.
        let favorites = existingLists.first { $0.isFavorites }
        let others = existingLists.filter { !$0.isFavorites }
        var takenListUids = Set(existingLists.map(\.uid))
        var listTargets: [String: MergeTarget] = [:]         // file list id -> target
        var newListOrder: [String] = []                      // name keys, in creation order
        var newListsByName: [String: NewList] = [:]
        var nextSortOrder = maxSortOrder + 1

        for list in backup.lists {
            let key = nameKey(list.name)
            let target: MergeTarget
            if list.isFavorites, let favorites {
                target = .existing(favorites.id)
            } else if let byUid = others.first(where: { $0.uid == list.id }) {
                target = .existing(byUid.id)
            } else if let byName = others.first(where: { nameKey($0.name) == key }) {
                target = .existing(byName.id)
            } else if let planned = newListsByName[key] {
                target = .new(planned.uid)
            } else {
                let uid = takenListUids.insert(list.id).inserted ? list.id : freshUid(&takenListUids, newUid)
                newListsByName[key] = NewList(
                    uid: uid,
                    name: list.name.trimmingCharacters(in: .whitespacesAndNewlines),
                    isBuiltIn: list.isBuiltIn,
                    sortOrder: nextSortOrder,
                    createdAt: list.createdAt
                )
                nextSortOrder += 1
                newListOrder.append(key)
                target = .new(uid)
            }
            listTargets[list.id] = target
        }

        // --- Memberships, once per recipe and list; the first in the file wins.
        struct Pair: Hashable { let recipe: MergeTarget; let list: MergeTarget }
        var seen = Set<Pair>()
        var memberships: [PlannedMembership] = []
        for m in backup.memberships {
            guard let recipe = recipeTargets[m.recipeId], let list = listTargets[m.listId] else { continue }
            if seen.insert(Pair(recipe: recipe, list: list)).inserted {
                memberships.append(PlannedMembership(recipe: recipe, list: list, addedAt: m.addedAt))
            }
        }

        // --- History: new recipes in no list only take free places; nothing here is pushed out.
        // A recipe the file plans for today or later is kept from the cull, so it counts as listed.
        let incomingPlan = backup.mealPlan.filter { !existingPlanUids.contains($0.id) }
        var listedTargets = Set(memberships.map(\.recipe))
        if let today {
            for entry in incomingPlan where entry.day >= today {
                if let target = entry.recipeId.flatMap({ recipeTargets[$0] }) { listedTargets.insert(target) }
            }
        }
        // A menu's recipes are kept from the cull as well.
        let incomingMenus = backup.menus.filter { !existingMenuUids.contains($0.id) }
        let incomingMenuIds = Set(incomingMenus.map(\.id))
        let incomingMenuEntries = backup.menuEntries.filter { incomingMenuIds.contains($0.menuId) }
        for entry in incomingMenuEntries {
            if let target = entry.recipeId.flatMap({ recipeTargets[$0] }) { listedTargets.insert(target) }
        }
        // So is a recipe typed in by hand (#102): it has no link to bring it back.
        for recipe in newByUrl.values where recipe.contentOrigin == "MANUAL" { listedTargets.insert(.new(recipe.id)) }
        let unlistedHere = existingRecipes.filter { !$0.isListed && !listedTargets.contains(.existing($0.id)) }.count
        let freePlaces = max(0, historyLimit - unlistedHere)
        let newRecipesInOrder = newOrder.compactMap { newByUrl[$0] }
        let unlistedNew = newRecipesInOrder.filter { !listedTargets.contains(.new($0.id)) }
        let kept = Set(
            unlistedNew.enumerated()
                .sorted { a, b in
                    a.element.lastViewedAt != b.element.lastViewedAt
                        ? a.element.lastViewedAt > b.element.lastViewedAt
                        : a.offset < b.offset
                }
                .prefix(freePlaces)
                .map(\.element.id)
        )
        let skipped = unlistedNew.filter { !kept.contains($0.id) }.count
        let newRecipes = newRecipesInOrder.filter { listedTargets.contains(.new($0.id)) || kept.contains($0.id) }

        let noteUpdates = matchedOrder.compactMap { id -> NoteUpdate? in
            guard let existing = matched[id], !existing.hasNotes, let note = importedNote[id] else { return nil }
            return NoteUpdate(recipeId: id, notes: note)
        }

        // --- Pantry: by uid, then by name in the same language; what's here stands.
        struct PantryKey: Hashable { let name: String; let language: String? }
        var takenPantryUids = Set(existingPantry.map(\.uid))
        var pantryNames = Set(existingPantry.map { PantryKey(name: nameKey($0.name), language: $0.language) })
        var newPantry: [BackupPantryItem] = []
        for item in backup.pantry {
            guard !takenPantryUids.contains(item.id),
                  pantryNames.insert(PantryKey(name: nameKey(item.name), language: item.language)).inserted
            else { continue }
            takenPantryUids.insert(item.id)
            var trimmed = item
            trimmed.name = item.name.trimmingCharacters(in: .whitespacesAndNewlines)
            newPantry.append(trimmed)
        }

        // --- Groceries: by uid only, after what's on the list, keeping a recipe that's here.
        let written = Set(newRecipes.map { MergeTarget.new($0.id) })
        let newGroceries = backup.groceries.filter { !existingGroceryUids.contains($0.id) }.map { item -> NewGrocery in
            var recipe = item.recipeId.flatMap { recipeTargets[$0] }
            if case .new = recipe, !written.contains(recipe!) { recipe = nil }
            return NewGrocery(item: item, recipe: recipe)
        }

        // --- Meal types: seeded by key, the user's own by uid, then name; the rest are created.
        var hereTypesByKey: [String: ExistingMealType] = [:]
        for type in existingMealTypes {
            if let key = type.builtInKey, hereTypesByKey[key] == nil { hereTypesByKey[key] = type }
        }
        let hereUserTypes = existingMealTypes.filter { $0.builtInKey == nil }
        var takenTypeUids = Set(existingMealTypes.map(\.uid))
        var typeTargets: [String: MergeTarget] = [:]          // file meal type id -> target
        var newTypeOrder: [String] = []                       // name keys, in creation order
        var newTypesByName: [String: NewMealType] = [:]
        var nextTypeOrder = maxMealTypeSortOrder + 1
        for type in backup.mealTypes {
            let key = nameKey(type.name)
            if let builtIn = type.builtInKey.flatMap({ hereTypesByKey[$0] }) {
                typeTargets[type.id] = .existing(builtIn.id)
            } else if let byUid = hereUserTypes.first(where: { $0.uid == type.id }) {
                typeTargets[type.id] = .existing(byUid.id)
            } else if let byName = hereUserTypes.first(where: { nameKey($0.name) == key }) {
                typeTargets[type.id] = .existing(byName.id)
            } else if let planned = newTypesByName[key] {
                typeTargets[type.id] = .new(planned.uid)
            } else {
                let uid = takenTypeUids.insert(type.id).inserted ? type.id : freshUid(&takenTypeUids, newUid)
                newTypesByName[key] = NewMealType(
                    uid: uid,
                    name: type.name.trimmingCharacters(in: .whitespacesAndNewlines),
                    sortOrder: nextTypeOrder,
                    updatedAt: type.updatedAt
                )
                nextTypeOrder += 1
                newTypeOrder.append(key)
                typeTargets[type.id] = .new(uid)
            }
        }
        let dinner = hereTypesByKey[MealType.dinner].map { MergeTarget.existing($0.id) }

        // --- The plan: by uid; a recipe entry needs its recipe here, a note always comes in.
        let newPlanEntries = incomingPlan.compactMap { entry -> NewPlanEntry? in
            guard let mealType = entry.mealTypeId.flatMap({ typeTargets[$0] }) ?? dinner else { return nil }
            var recipe = entry.recipeId.flatMap { recipeTargets[$0] }
            if case .new = recipe, !written.contains(recipe!) { recipe = nil }
            if let recipe {
                var withRecipe = entry
                withRecipe.note = nil
                return NewPlanEntry(entry: withRecipe, mealType: mealType, recipe: recipe)
            }
            guard entry.recipeId == nil, entry.note != nil else { return nil }
            var note = entry
            note.servings = nil
            return NewPlanEntry(entry: note, mealType: mealType, recipe: nil)
        }

        // --- Menus: by uid, whole; their meals follow the plan's rules.
        var takenMenuEntryUids = existingMenuEntryUids
        let newMenus = incomingMenus.compactMap { menu -> NewMenu? in
            let entries = incomingMenuEntries.filter { $0.menuId == menu.id }.compactMap { entry -> NewMenuEntry? in
                guard let mealType = entry.mealTypeId.flatMap({ typeTargets[$0] }) ?? dinner else { return nil }
                var recipe = entry.recipeId.flatMap { recipeTargets[$0] }
                if case .new = recipe, !written.contains(recipe!) { recipe = nil }
                guard recipe != nil || (entry.recipeId == nil && entry.note != nil) else { return nil }
                var row = entry
                row.id = takenMenuEntryUids.insert(entry.id).inserted ? entry.id : freshUid(&takenMenuEntryUids, newUid)
                if recipe != nil { row.note = nil } else { row.servings = nil }
                return NewMenuEntry(entry: row, mealType: mealType, recipe: recipe)
            }
            if entries.isEmpty { return nil }
            var trimmed = menu
            trimmed.name = menu.name.trimmingCharacters(in: .whitespacesAndNewlines)
            return NewMenu(menu: trimmed, entries: entries)
        }

        return ImportPlan(
            newRecipes: newRecipes,
            noteUpdates: noteUpdates,
            newLists: newListOrder.compactMap { newListsByName[$0] },
            memberships: memberships,
            summary: ImportSummary(
                recipesAdded: newRecipes.count,
                listsAdded: newListOrder.count,
                recipesAlreadyHere: matchedOrder.count,
                recipesSkipped: skipped,
                pantryAdded: newPantry.count,
                groceriesAdded: newGroceries.count,
                mealsAdded: newPlanEntries.count,
                mealTypesAdded: newTypeOrder.count,
                menusAdded: newMenus.count
            ),
            newPantry: newPantry,
            newGroceries: newGroceries,
            newMealTypes: newTypeOrder.compactMap { newTypesByName[$0] },
            newPlanEntries: newPlanEntries,
            newMenus: newMenus
        )
    }

    /// How list names are compared: trimmed and case-insensitive ("Desserts" = " desserts").
    static func nameKey(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    private static let knownSourceTypes: Set<String> = ["BLOG", "REDDIT"]

    private static func isBlank(_ s: String) -> Bool {
        s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private static func freshUid(_ taken: inout Set<String>, _ newUid: () -> String) -> String {
        while true {
            let uid = newUid()
            if taken.insert(uid).inserted { return uid }
        }
    }
}
