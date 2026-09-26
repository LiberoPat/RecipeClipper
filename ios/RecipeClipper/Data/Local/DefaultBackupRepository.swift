import Foundation

/// The real BackupRepository (Android's DefaultBackupRepository). A database failure is logged
/// and becomes `.exportFailed` or `.saveFailed`; the import is one transaction, so a failed one
/// wrote nothing.
final class DefaultBackupRepository: BackupRepository {
    private let db: AppDatabase
    private let clock: Clock

    private let library: LibraryLimitSource

    init(db: AppDatabase, clock: Clock, library: LibraryLimitSource = FixedLibraryLimit()) {
        self.library = library
        self.db = db
        self.clock = clock
    }

    func export() async -> Result<ExportedBackup, BackupError> {
        let snapshot: BackupSnapshot
        do {
            snapshot = try await db.read { conn in try BackupDao(db: conn).snapshot() }
        } catch {
            dataLog.error("export failed: \(String(describing: error), privacy: .public)")
            return .failure(.exportFailed)
        }
        let recipeUids = Dictionary(uniqueKeysWithValues: snapshot.recipes.map { ($0.id, $0.uid) })
        let listUids = Dictionary(uniqueKeysWithValues: snapshot.lists.map { ($0.id, $0.uid) })
        let mealTypeUids = Dictionary(uniqueKeysWithValues: snapshot.mealTypes.map { ($0.id, $0.uid) })
        let menuUids = Dictionary(uniqueKeysWithValues: snapshot.menus.map { ($0.id, $0.uid) })
        let now = clock.now()
        let backup = Backup(
            exportedAt: now,
            recipes: snapshot.recipes.map { r in
                BackupRecipe(
                    id: r.uid, sourceUrl: r.sourceUrl, sourceType: r.sourceType, title: r.title,
                    imageUrl: r.imageUrl, ingredients: r.ingredients, instructions: r.instructions,
                    prepTime: r.prepTime, cookTime: r.cookTime, totalTime: r.totalTime,
                    servings: r.servings, lastViewedAt: r.lastViewedAt,
                    checkedIngredients: r.checkedIngredients, notes: r.notes, language: r.language,
                    contentOrigin: r.contentOrigin, editedAt: r.editedAt
                )
            },
            lists: snapshot.lists.map { l in
                BackupList(
                    id: l.uid, name: l.name, isFavorites: l.isFavorites, isBuiltIn: l.isBuiltIn,
                    sortOrder: l.sortOrder, createdAt: l.createdAt
                )
            },
            memberships: snapshot.memberships.compactMap { m in
                guard let recipe = recipeUids[m.recipeId], let list = listUids[m.listId] else { return nil }
                return BackupMembership(recipeId: recipe, listId: list, addedAt: m.addedAt)
            },
            pantry: snapshot.pantry.map { p in
                BackupPantryItem(
                    id: p.uid, name: p.name, quantity: p.quantity, language: p.language, aisle: p.aisle,
                    inStock: p.inStock, alwaysHave: p.alwaysHave, purchasedDay: p.purchasedDay,
                    expiresDay: p.expiresDay, updatedAt: p.updatedAt
                )
            },
            groceries: snapshot.groceries.map { g in
                BackupGroceryItem(
                    id: g.uid, text: g.text, language: g.language, aisle: g.aisle, checked: g.checked,
                    recipeId: g.recipeId.flatMap { recipeUids[$0] }, plannedDay: g.plannedDay, updatedAt: g.updatedAt
                )
            },
            mealTypes: snapshot.mealTypes.map { t in
                BackupMealType(id: t.uid, name: t.name, builtInKey: t.builtInKey, sortOrder: t.sortOrder, updatedAt: t.updatedAt)
            },
            mealPlan: snapshot.mealPlan.map { e in
                BackupPlanEntry(
                    id: e.uid, day: e.day, mealTypeId: mealTypeUids[e.mealTypeId], recipeId: e.recipeId.flatMap { recipeUids[$0] },
                    servings: e.servings, note: e.note, sortOrder: e.sortOrder, updatedAt: e.updatedAt
                )
            },
            menus: snapshot.menus.map { BackupMenu(id: $0.uid, name: $0.name, updatedAt: $0.updatedAt) },
            menuEntries: snapshot.menuEntries.compactMap { e in
                guard let menuUid = menuUids[e.menuId] else { return nil }
                return BackupMenuEntry(
                    id: e.uid, menuId: menuUid, dayOffset: e.dayOffset, mealTypeId: mealTypeUids[e.mealTypeId],
                    recipeId: e.recipeId.flatMap { recipeUids[$0] }, servings: e.servings, note: e.note,
                    sortOrder: e.sortOrder, updatedAt: e.updatedAt
                )
            }
        )
        return .success(ExportedBackup(json: BackupJson.encode(backup), exportedAt: now, recipeCount: backup.recipes.count))
    }

    func importBackup(_ text: String) async -> Result<ImportSummary, BackupError> {
        let backup: Backup
        switch BackupJson.decode(text) {
        case .success(let decoded): backup = decoded
        case .failure(let error): return .failure(error)
        }
        let today = PlanDays.today(millis: clock.now())
        let limit = library.current()
        do {
            let summary = try await db.write { conn in
                try BackupDao(db: conn).importBackup(backup, limit: limit, today: today) {
                    UUID().uuidString.lowercased()
                }
            }
            return .success(summary)
        } catch {
            dataLog.error("import failed: \(String(describing: error), privacy: .public)")
            return .failure(.saveFailed)
        }
    }
}

/// Writes exports into the temporary directory and reads picked files, which from the file
/// importer are security-scoped: access is opened for the read and closed after it.
final class FileBackupFiles: BackupFiles {
    func writeExport(json: String, exportedAt: Int64) async -> URL? {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(backupFileName(exportedAt: exportedAt))
        do {
            try Data(json.utf8).write(to: url, options: .atomic)
            return url
        } catch {
            dataLog.error("writeExport failed: \(String(describing: error), privacy: .public)")
            return nil
        }
    }

    func readText(_ url: URL) async -> Result<String, BackupError> {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let handle = try FileHandle(forReadingFrom: url)
            defer { try? handle.close() }
            let data = try handle.read(upToCount: backupMaxBytes + 1) ?? Data()
            if data.count > backupMaxBytes { return .failure(.notABackup) }
            guard let text = String(data: data, encoding: .utf8) else { return .failure(.notABackup) }
            return .success(text)
        } catch {
            dataLog.error("readText failed: \(String(describing: error), privacy: .public)")
            return .failure(.readFailed)
        }
    }
}
