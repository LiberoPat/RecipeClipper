import Foundation

/// The real ShareFileRepository (#149, phase 2; Android's DefaultShareFileRepository). Receiving
/// runs the merge and the history cap in one transaction, as a shared link's save does; a
/// database failure is logged and changes nothing.
final class DefaultShareFileRepository: ShareFileRepository {
    private let db: AppDatabase
    private let clock: Clock
    private let library: LibraryLimitSource

    init(db: AppDatabase, clock: Clock, library: LibraryLimitSource = FixedLibraryLimit()) {
        self.db = db
        self.clock = clock
        self.library = library
    }

    func recipeFile(recipeId: Int64) async -> String? {
        let now = clock.now()
        do {
            let recipes = try await db.read { conn in try BackupDao(db: conn).recipes(ids: [recipeId]) }
            guard let recipe = recipes.first else { return nil }
            return BackupJson.encode(ShareFile.make(now: now, recipes: [recipe.backup]))
        } catch {
            dataLog.error("recipeFile failed: \(String(describing: error), privacy: .public)")
            return nil
        }
    }

    func groceriesFile() async -> String? {
        let now = clock.now()
        do {
            let (items, recipes) = try await db.read { conn in
                let dao = BackupDao(db: conn)
                let items = try dao.uncheckedGroceries()
                return (items, try dao.recipes(ids: items.compactMap(\.recipeId)))
            }
            guard !items.isEmpty else { return nil }
            let uids = Dictionary(uniqueKeysWithValues: recipes.map { ($0.id, $0.uid) })
            let groceries = items.map { g in
                BackupGroceryItem(
                    id: g.uid, text: g.text, language: g.language, aisle: g.aisle, checked: g.checked,
                    recipeId: g.recipeId.flatMap { uids[$0] }, plannedDay: g.plannedDay, updatedAt: g.updatedAt
                )
            }
            return BackupJson.encode(ShareFile.make(now: now, recipes: recipes.map(\.backup), groceries: groceries))
        } catch {
            dataLog.error("groceriesFile failed: \(String(describing: error), privacy: .public)")
            return nil
        }
    }

    func pantryFile() async -> String? {
        let now = clock.now()
        do {
            let items = try await db.read { conn in try PantryDao(db: conn).items() }
            return ShareFile.pantry(now: now, items: items.map(\.backup)).map(BackupJson.encode)
        } catch {
            dataLog.error("pantryFile failed: \(String(describing: error), privacy: .public)")
            return nil
        }
    }

    func receive(_ file: Backup, choice: ShareChoice) async -> Result<ImportSummary, BackupError> {
        let now = clock.now()
        let limit = library.current()
        let chosen = ShareFile.chosen(file, choice, now: now)
        do {
            let summary = try await db.write { conn in
                try BackupDao(db: conn).importShare(chosen, limit: limit, now: now) { UUID().uuidString.lowercased() }
            }
            return .success(summary)
        } catch {
            dataLog.error("receiveShare failed: \(String(describing: error), privacy: .public)")
            return .failure(.saveFailed)
        }
    }
}

extension PantryItemRecord {
    /// The pantry item as an export file holds it (#26), and as the Pantry's shared file sends it (#149).
    var backup: BackupPantryItem {
        BackupPantryItem(
            id: uid, name: name, quantity: quantity, language: language, aisle: aisle,
            inStock: inStock, alwaysHave: alwaysHave, purchasedDay: purchasedDay,
            expiresDay: expiresDay, updatedAt: updatedAt
        )
    }
}

extension RecipeRecord {
    /// The recipe as an export file holds it (#26), and as a shared file sends it (#149).
    var backup: BackupRecipe {
        BackupRecipe(
            id: uid, sourceUrl: sourceUrl, sourceType: sourceType, title: title, imageUrl: imageUrl,
            ingredients: ingredients, instructions: instructions, prepTime: prepTime, cookTime: cookTime,
            totalTime: totalTime, servings: servings, lastViewedAt: lastViewedAt,
            checkedIngredients: checkedIngredients, notes: notes, language: language,
            contentOrigin: contentOrigin, editedAt: editedAt
        )
    }
}
