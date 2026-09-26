import Combine
import XCTest
@testable import RecipeClipper

/// "I made this" (#116) against real SQLite and real files (Android's CookedPhotoDaoTest and
/// CookedPhotoBackupTest): newest cook first, a recipe with photos kept from the cull, the
/// cascade with Undo, the sweep, and the export zip round trip past a full history.
final class CookedPhotoTests: XCTestCase {
    private var db: AppDatabase!
    private var store: FilePhotoStore!
    private var photos: DefaultCookedPhotoRepository!
    private let clock = DataTestClock(1_790_000_000_000)

    override func setUpWithError() throws {
        db = try AppDatabase(path: nil)
        store = FilePhotoStore(directory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
        photos = DefaultCookedPhotoRepository(db: db, store: store, clock: clock)
    }

    private func insert(_ n: Int) async throws -> Int64 {
        try await db.write { try RecipeDao(db: $0).insert(dataRecipeRecord("https://example.com/\(n)", viewedAt: Int64(n))) }
    }

    /// A real (tiny) JPEG, so ImageIO has something to downscale.
    private func jpeg(width: Int = 40, height: Int = 20) -> Data {
        UIGraphicsImageRenderer(size: CGSize(width: width, height: height)).jpegData(withCompressionQuality: 0.9) { ctx in
            UIColor.orange.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        }
    }

    /// `XCTUnwrap` can't take an `await`.
    private func unwrap<T>(_ value: T?, file: StaticString = #filePath, line: UInt = #line) async throws -> T {
        try XCTUnwrap(value, file: file, line: line)
    }

    private func current(_ recipeId: Int64) async throws -> [CookedPhoto] {
        try await db.read { conn in try CookedPhotoDao(db: conn).photosFor(recipeId).map { $0.domain(self.store) } }
    }

    func testAddedPicturesAreDownscaledCookedTodayNewestFirstAndEditable() async throws {
        let id = try await insert(1)
        let first = try await unwrap(photos.add(recipeId: id, pictures: [jpeg(width: 4000, height: 3000)]).first)
        clock.time += 1_000
        let second = await photos.add(recipeId: id, pictures: [jpeg(), Data("not a picture".utf8)])
        XCTAssertEqual(second.count, 1)
        XCTAssertEqual(first.day, PlanDays.today(millis: clock.time))
        let image = try XCTUnwrap(UIImage(contentsOfFile: first.path))
        XCTAssertEqual(max(image.size.width * image.scale, image.size.height * image.scale), CGFloat(photoMaxEdge))
        let listed = try await current(id)
        XCTAssertEqual(listed.map(\.id), [second[0].id, first.id])

        await photos.edit(id: first.id, day: first.day + 1, note: "  Less sugar  ")
        let edited = try await current(id).first
        XCTAssertEqual(edited?.note, "Less sugar")
        XCTAssertEqual(edited?.updatedAt, clock.time)
    }

    /// A user_version 12 file, built by the real migrations, gains `cooked_photos` (Android's
    /// `MigrationTest.migration13To14…`), deleted with its recipe.
    func testAVersion12DatabaseMigratesToVersion13() async throws {
        let path = NSTemporaryDirectory() + "rc-\(UUID().uuidString).sqlite"
        defer {
            for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) }
        }
        do {
            let old = try SQLiteConnection(path: path)
            try AppDatabase.migrate(old, upTo: 12)
            try RecipeDao(db: old).insert(dataRecipeRecord("https://example.com/cake", viewedAt: 1))
        }
        let migrated = try AppDatabase(path: path)
        let version = try await migrated.read { try $0.queryOne("PRAGMA user_version") { $0.int(0) } }
        XCTAssertEqual(version, 13)
        let recipeId = try await migrated.read { try XCTUnwrap(RecipeDao(db: $0).findByUrl("https://example.com/cake")).id }
        try await migrated.write { conn in
            try CookedPhotoDao(db: conn).insert(CookedPhotoRecord(
                recipeId: recipeId, fileName: "a.jpg", day: 20_000, note: "Good", createdAt: 1, updatedAt: 1
            ))
        }
        let notes = try await migrated.read { try CookedPhotoDao(db: $0).photosFor(recipeId).map(\.note) }
        XCTAssertEqual(notes, ["Good"])
        try await migrated.write { try RecipeDao(db: $0).delete(recipeId) }
        let left = try await migrated.read { try CookedPhotoDao(db: $0).all() }
        XCTAssertTrue(left.isEmpty)
    }

    func testARecipeWithPhotosIsNeverCulledAndSortsAsCooked() async throws {
        let cooked = try await insert(1)
        _ = await photos.add(recipeId: cooked, pictures: [jpeg()])
        _ = try await insert(2)
        _ = try await insert(3)
        let oldest = try await db.read { try RecipeDao(db: $0).oldestCullable() }
        XCTAssertEqual(oldest, 2)
        try await db.write { try RecipeDao(db: $0).cullHistory(keep: 0) }
        let left = try await db.read { try RecipeDao(db: $0).history(query: "") }
        XCTAssertEqual(left.map(\.id), [cooked])
        XCTAssertEqual(left.first?.lastCookedDay, PlanDays.today(millis: clock.time))
    }

    func testDeletingARecipeTakesItsPhotosUndoBringsThemBackAndForgetRemovesTheFiles() async throws {
        let id = try await insert(1)
        let recipes = DefaultRecipeRepository(db: db, source: DataStubSource(), clock: clock, photos: store)
        let photo = try await unwrap(photos.add(recipeId: id, pictures: [jpeg()]).first)
        let deleted = try await unwrap(recipes.delete(id: id))
        let afterDelete = try await current(id)
        XCTAssertTrue(afterDelete.isEmpty)
        XCTAssertTrue(FileManager.default.fileExists(atPath: photo.path))

        await recipes.restore(deleted)
        let restored = try await current(id)
        XCTAssertEqual(restored.map(\.uid), [photo.uid])

        await recipes.forget(try await unwrap(recipes.delete(id: id)))
        XCTAssertFalse(FileManager.default.fileExists(atPath: photo.path))
    }

    func testTheSweepRemovesOnlyOldFilesNoPhotoNames() async throws {
        let id = try await insert(1)
        let kept = try await unwrap(photos.add(recipeId: id, pictures: [jpeg()]).first)
        let orphan = try await unwrap(store.importPicture(jpeg()))
        clock.time = Int64(Date().timeIntervalSince1970 * 1000) + photoSweepGraceMillis * 2
        await photos.sweep()
        let files = await store.files()
        XCTAssertEqual(Set(files.keys), [kept.fileName])
        XCTAssertNotEqual(orphan, kept.fileName)
    }

    func testPhotosRoundTripThroughTheZipPastAFullHistory() async throws {
        let id = try await insert(1)
        let photo = try await unwrap(photos.add(recipeId: id, pictures: [jpeg()]).first)
        await photos.edit(id: photo.id, day: 20_000, note: "Less sugar")
        let exported = try await exportOrFail(DefaultBackupRepository(db: db, clock: clock, photos: store))
        XCTAssertEqual(exported.photos.count, 1)
        let zip = BackupArchive.write(json: exported.json, photos: exported.photos.map { (path: $0.key, file: $0.value) })
        XCTAssertTrue(BackupArchive.isZip(zip))

        let unpacked = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        guard case .success(let package) = BackupArchive.read(zip, photoDirectory: unpacked, maxJsonBytes: 1_000_000) else {
            return XCTFail("zip didn't read")
        }
        let other = try AppDatabase(path: nil)
        let otherStore = FilePhotoStore(directory: unpacked.appendingPathComponent("store"))
        let into = DefaultBackupRepository(db: other, clock: clock, library: FixedLibraryLimit(limit: .history(keep: 0)), photos: otherStore)
        guard case .success(let summary) = await into.importBackup(package) else { return XCTFail("import failed") }
        XCTAssertEqual(summary.recipesAdded, 1)
        XCTAssertEqual(summary.photosAdded, 1)
        let copy = try await other.read { conn in try CookedPhotoDao(db: conn).all() }
        XCTAssertEqual(copy.map(\.uid), [photo.uid])
        XCTAssertEqual(copy.first?.note, "Less sugar")
        XCTAssertEqual(copy.first?.day, 20_000)
        XCTAssertEqual(
            try Data(contentsOf: URL(fileURLWithPath: otherStore.path(copy[0].fileName))),
            try Data(contentsOf: URL(fileURLWithPath: photo.path))
        )
        guard case .success(let again) = await into.importBackup(package) else { return XCTFail("import failed") }
        XCTAssertEqual(again.photosAdded, 0)
    }

    private func exportOrFail(_ repo: DefaultBackupRepository) async throws -> ExportedBackup {
        switch await repo.export() {
        case .success(let exported): return exported
        case .failure(let error): XCTFail("export failed: \(error)"); throw error
        }
    }
}
