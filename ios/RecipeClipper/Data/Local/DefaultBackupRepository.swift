import Foundation

/// The real BackupRepository (Android's DefaultBackupRepository). A database failure is logged
/// and becomes `.exportFailed` or `.saveFailed`; the import is one transaction, so a failed one
/// wrote nothing.
final class DefaultBackupRepository: BackupRepository {
    private let db: AppDatabase
    private let clock: Clock

    private let library: LibraryLimitSource
    private let photos: PhotoStore?

    init(db: AppDatabase, clock: Clock, library: LibraryLimitSource = FixedLibraryLimit(), photos: PhotoStore? = nil) {
        self.photos = photos
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
        // The pictures travel beside the JSON (#116), each named after its row.
        var withPhotos = backup
        var pictures: [String: URL] = [:]
        if let photos {
            withPhotos.cookedPhotos = snapshot.cookedPhotos.compactMap { p in
                guard let recipe = recipeUids[p.recipeId] else { return nil }
                let file = "photos/photo-\(p.id).jpg"
                pictures[file] = URL(fileURLWithPath: photos.path(p.fileName))
                return BackupCookedPhoto(
                    id: p.uid, recipeId: recipe, day: p.day, note: p.note, createdAt: p.createdAt,
                    updatedAt: p.updatedAt, file: file
                )
            }
        }
        return .success(ExportedBackup(
            json: BackupJson.encode(withPhotos), exportedAt: now, recipeCount: backup.recipes.count, photos: pictures
        ))
    }

    func importBackup(_ package: BackupPackage) async -> Result<ImportSummary, BackupError> {
        let backup: Backup
        switch BackupJson.decode(package.json) {
        case .success(let decoded): backup = decoded
        case .failure(let error): return .failure(error)
        }
        // Copy in the pictures the file's photos name first; a photo whose picture didn't come
        // stays out. Copies the import didn't use are swept later (the store's grace period).
        var stored: [String: String] = [:]
        if let photos {
            for file in Set(backup.cookedPhotos.map(\.file)) {
                guard let local = package.photos[file], let name = await photos.adopt(local) else { continue }
                stored[file] = name
            }
        }
        let now = clock.now()
        let today = PlanDays.today(millis: now)
        let limit = library.current()
        do {
            let summary = try await db.write { [stored] conn in
                try BackupDao(db: conn).importBackup(backup, limit: limit, today: today, storedPhotos: stored, now: now) {
                    UUID().uuidString.lowercased()
                }
            }
            return .success(summary)
        } catch {
            dataLog.error("import failed: \(String(describing: error), privacy: .public)")
            await photos?.delete(Array(stored.values))
            return .failure(.saveFailed)
        }
    }
}

/// Writes exports into the temporary directory and reads picked files, which from the file
/// importer are security-scoped: access is opened for the read and closed after it.
final class FileBackupFiles: BackupFiles {
    func writeExport(json: String, exportedAt: Int64, photos: [String: URL]) async -> URL? {
        let name = backupFileName(exportedAt: exportedAt, zip: !photos.isEmpty)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        do {
            // With photos (#116), a zip of the JSON and the pictures.
            let data = photos.isEmpty
                ? Data(json.utf8)
                : BackupArchive.write(json: json, photos: photos.sorted { $0.key < $1.key }.map { (path: $0.key, file: $0.value) })
            try data.write(to: url, options: .atomic)
            return url
        } catch {
            dataLog.error("writeExport failed: \(String(describing: error), privacy: .public)")
            return nil
        }
    }

    func read(_ url: URL) async -> Result<BackupPackage, BackupError> {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let handle = try FileHandle(forReadingFrom: url)
            defer { try? handle.close() }
            // A zip (#116) carries its pictures; anything else is read as a plain JSON export.
            if BackupArchive.isZip(try handle.read(upToCount: 4) ?? Data()) {
                let zip = try Data(contentsOf: url, options: .mappedIfSafe)
                let unpacked = FileManager.default.temporaryDirectory.appendingPathComponent("import-photos")
                return BackupArchive.read(zip, photoDirectory: unpacked, maxJsonBytes: backupMaxBytes)
            }
            try handle.seek(toOffset: 0)
            let data = try handle.read(upToCount: backupMaxBytes + 1) ?? Data()
            if data.count > backupMaxBytes { return .failure(.notABackup) }
            guard let text = String(data: data, encoding: .utf8) else { return .failure(.notABackup) }
            return .success(BackupPackage(json: text))
        } catch {
            dataLog.error("read backup failed: \(String(describing: error), privacy: .public)")
            return .failure(.readFailed)
        }
    }
}
