import XCTest
@testable import RecipeClipper

/// A recipe's own stored picture (#386; Android's RecipePhotoTest and MigrationTest) over real
/// SQLite: shown in place of its link wherever the recipe's picture shows, kept by a re-share and an
/// edit, restored by an Undo and deleted when the delete stands, swept only when nothing names it,
/// never a cooking, and carried by an export.
final class RecipePhotoTests: XCTestCase {
    private var db: AppDatabase!
    private let store = FakePhotoStore()
    private let clock = DataTestClock(1_790_000_000_000)

    override func setUpWithError() throws { db = try AppDatabase(path: nil) }
    override func tearDown() { db = nil }

    private func record(_ url: String, image: String? = "https://img.example/a.jpg", photo: String? = nil) -> RecipeRecord {
        var row = dataRecipeRecord(url, viewedAt: 1)
        row.imageUrl = image
        row.photoFile = photo
        return row
    }

    private func insert(_ row: RecipeRecord) async throws -> Int64 {
        try await db.write { try RecipeDao(db: $0).insert(row) }
    }

    private func repository() -> DefaultRecipeRepository {
        DefaultRecipeRepository(db: db, source: DataStubSource(), clock: clock, photos: store)
    }

    // MARK: StoredPhoto

    func testAFileNameIsAPhotoModelAndBack() {
        XCTAssertEqual(StoredPhoto.model("3f2a.jpg"), "photo://3f2a.jpg")
        XCTAssertEqual(StoredPhoto.fileName("photo://3f2a.jpg"), "3f2a.jpg")
    }

    func testAWebLinkOrAnythingThatIsntAPlainFileNameNamesNoFile() {
        for model in [
            "https://img.example/a.jpg", "file:///etc/passwd", "photo://", "photo://../secret", "photo://a/b.jpg",
            "photo://..", "photo://.", "photo://a\\b.jpg", "PHOTO://a.jpg", "", " photo://a.jpg",
        ] {
            XCTAssertNil(StoredPhoto.fileName(model), model)
        }
    }

    func testARecipeShowsItsStoredPhotoInPlaceOfItsLink() {
        let recipe = Recipe(
            name: "A", image: "https://img.example/a.jpg", ingredients: ["1 egg"], instructions: [],
            prepTime: nil, cookTime: nil, totalTime: nil, yield: nil, sourceUrl: "https://x/a"
        )
        XCTAssertEqual(recipe.displayImage, "https://img.example/a.jpg")
        var withPhoto = recipe
        withPhoto.photoFile = "mine.jpg"
        XCTAssertEqual(withPhoto.displayImage, "photo://mine.jpg")
        // The editor's link is untouched by it.
        XCTAssertEqual(withPhoto.image, "https://img.example/a.jpg")
        withPhoto.image = nil
        XCTAssertEqual(withPhoto.displayImage, "photo://mine.jpg")
        var none = recipe
        none.image = nil
        XCTAssertNil(none.displayImage)
    }

    // MARK: ImageLoader

    func testThePhotoSchemeIsAFileInTheStoreAndNothingElse() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let saved = ImageLoader.photoDirectory
        defer { ImageLoader.photoDirectory = saved }
        ImageLoader.photoDirectory = directory
        XCTAssertEqual(ImageLoader.storedFile(for: try XCTUnwrap(URL(string: "photo://abc.jpg"))), directory.appendingPathComponent("abc.jpg"))
        XCTAssertNil(ImageLoader.storedFile(for: try XCTUnwrap(URL(string: "https://img.example/a.jpg"))))
        XCTAssertNil(ImageLoader.storedFile(for: try XCTUnwrap(URL(string: "photo://a/b.jpg"))))
        ImageLoader.photoDirectory = nil
        XCTAssertNil(ImageLoader.storedFile(for: try XCTUnwrap(URL(string: "photo://abc.jpg"))))
    }

    func testTheLoaderReadsAStoredPhotosBytes() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data("PIXELS".utf8).write(to: directory.appendingPathComponent("abc.jpg"))
        let saved = ImageLoader.photoDirectory
        defer { ImageLoader.photoDirectory = saved }
        ImageLoader.photoDirectory = directory
        let url = try XCTUnwrap(URL(string: "photo://abc.jpg"))
        let loaded = try await ImageLoader(cache: URLCache(memoryCapacity: 0, diskCapacity: 0)).data(for: url)
        XCTAssertEqual(String(decoding: loaded, as: UTF8.self), "PIXELS")
        XCTAssertEqual(ImageLoader(cache: URLCache(memoryCapacity: 0, diskCapacity: 0)).cachedData(for: url), Data("PIXELS".utf8))
    }

    // MARK: Over SQLite

    func testEveryListOfRecipesShowsTheStoredPhotoInPlaceOfTheLink() async throws {
        let withPhoto = try await insert(record("https://x/a", photo: "mine.jpg"))
        let onlyPhoto = try await insert(record("https://x/b", image: nil, photo: "only.jpg"))
        let onlyLink = try await insert(record("https://x/c"))
        let none = try await insert(record("https://x/d", image: nil))

        let history = try await db.read { try RecipeDao(db: $0).history(query: "") }
        let images = Dictionary(uniqueKeysWithValues: history.map { ($0.id, $0.imageUrl) })
        XCTAssertEqual(images[withPhoto], "photo://mine.jpg")
        XCTAssertEqual(images[onlyPhoto], "photo://only.jpg")
        XCTAssertEqual(images[onlyLink], "https://img.example/a.jpg")
        XCTAssertNil(images[none] ?? nil)

        // A list's rows and its cover, and a planned meal's picture.
        let favorites = try await db.read { try BackupDao(db: $0).snapshot().lists.first { $0.isFavorites }!.id }
        try await db.write { conn in
            for (i, id) in [withPhoto, onlyPhoto, onlyLink, none].enumerated() {
                try ListDao(db: conn).addToList(ListMembership(recipeId: id, listId: favorites, addedAt: Int64(i)))
            }
        }
        let covers = try await db.read { try ListDao(db: $0).coverPhotos() }
        XCTAssertEqual(covers.map(\.imageUrl).filter { $0.hasPrefix("photo://") }.sorted(), ["photo://mine.jpg", "photo://only.jpg"])
        let rows = try await db.read { try ListDao(db: $0).recipesIn(listId: favorites) }
        XCTAssertEqual(rows.first { $0.id == onlyPhoto }?.imageUrl, "photo://only.jpg")
        let dinner = try await db.read { try MealPlanDao(db: $0).mealTypes().first!.id }
        try await db.write { conn in
            try MealPlanDao(db: conn).add(MealPlanEntryRecord(
                day: 20_000, mealTypeId: dinner, recipeId: withPhoto, servings: nil, note: nil, sortOrder: 0, updatedAt: 0
            ))
        }
        let meals = try await db.read { try MealPlanDao(db: $0).days(start: 20_000, end: 20_000) }
        XCTAssertEqual(meals.first?.imageUrl, "photo://mine.jpg")
    }

    func testAReShareAndAnEditKeepThePhoto() async throws {
        let id = try await insert(record("https://x/a", photo: "mine.jpg"))
        // A re-share refreshes the content with a fresh parse, which has no photo of the user's.
        var refreshed = record("https://x/a")
        refreshed.id = id
        refreshed.title = "Refreshed"
        try await db.write { try RecipeDao(db: $0).update(refreshed) }
        let after = try await db.get(id)
        XCTAssertEqual(after?.title, "Refreshed")
        XCTAssertEqual(after?.photoFile, "mine.jpg")

        // Through the repository: a domain recipe (which carries the photo file) saved as an edit.
        let recipes = repository()
        var draft = RecipeDraft.of(try XCTUnwrap(after).toDomain())
        draft.name = "Edited"
        _ = await recipes.saveEdit(id: id, draft: draft)
        let edited = try await db.get(id)
        XCTAssertEqual(edited?.title, "Edited")
        XCTAssertEqual(edited?.photoFile, "mine.jpg")
    }

    func testAnUndoBringsTheRowAndItsFileBackAndTheDeleteThatStandsRemovesTheFile() async throws {
        let name = try XCTUnwrap(store.importData(Data("a photo".utf8)))
        let id = try await insert(record("https://x/a", photo: name))
        let recipes = repository()

        let deletedRow = await recipes.delete(id: id)
        let deleted = try XCTUnwrap(deletedRow)
        let gone = try await db.get(id)
        XCTAssertNil(gone)
        // The file stays while the Undo may still come…
        XCTAssertTrue(store.exists(name))
        await recipes.restore(deleted)
        let back = try await db.get(id)
        XCTAssertEqual(back?.photoFile, name)
        XCTAssertTrue(store.exists(name))

        // …and goes once the delete stands.
        let again = await recipes.delete(id: id)
        await recipes.forget(try XCTUnwrap(again))
        XCTAssertFalse(store.exists(name))
    }

    func testTheSweepKeepsARecipesPhotoAndRemovesWhatNothingNames() async throws {
        let kept = try XCTUnwrap(store.importData(Data("kept".utf8)))
        let orphan = try XCTUnwrap(store.importData(Data("orphan".utf8)))
        _ = try await insert(record("https://x/a", photo: kept))
        let photos = DefaultCookedPhotoRepository(db: db, store: store, clock: DataTestClock(Int64(Date().timeIntervalSince1970 * 1000) + photoSweepGraceMillis * 2))

        await photos.sweep()

        XCTAssertTrue(store.exists(kept))
        XCTAssertFalse(store.exists(orphan))
    }

    func testARecipesOwnPhotoIsNeverACooking() async throws {
        let id = try await insert(record("https://x/a", photo: "mine.jpg"))
        let cooked = try await db.read { try CookedPhotoDao(db: $0).photosFor(id) }
        XCTAssertTrue(cooked.isEmpty)
        let cookedView = try await db.read { try RecipeDao(db: $0).history(query: "", cooked: 1) }
        XCTAssertTrue(cookedView.isEmpty)
        let row = try await db.read { try RecipeDao(db: $0).history(query: "") }
        XCTAssertNil(row.first?.lastCookedDay)
    }

    // MARK: Migration

    /// A user_version 16 file gains a nullable `photoFile` (Android's `MigrationTest.migration17To18…`):
    /// every recipe comes across as it was with no photo, and one can then be written.
    func testAVersion16DatabaseMigratesToVersion17() async throws {
        let path = NSTemporaryDirectory() + "rc-\(UUID().uuidString).sqlite"
        defer {
            for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) }
        }
        do {
            let old = try SQLiteConnection(path: path)
            try AppDatabase.migrate(old, upTo: 16)
            try old.run(
                """
                INSERT INTO recipes (id, sourceUrl, title, imageUrl, ingredients, instructions, sourceType, lastViewedAt,
                    checkedIngredients, uid, contentOrigin)
                VALUES (7, 'https://example.com/a', 'Adobo', 'https://img.example/a.jpg', '["1 cup soy sauce"]', '["Simmer."]',
                    'BLOG', 123, '[]', 'recipe-uid', 'PARSED')
                """
            )
        }
        let migrated = try AppDatabase(path: path)
        let version = try await migrated.read { try $0.queryOne("PRAGMA user_version") { $0.int(0) } }
        XCTAssertEqual(version, AppDatabase.schemaVersion)
        XCTAssertEqual(AppDatabase.schemaVersion, 17)
        let original = try await migrated.read { try XCTUnwrap(RecipeDao(db: $0).get(7)) }
        XCTAssertEqual(original.title, "Adobo")
        XCTAssertEqual(original.imageUrl, "https://img.example/a.jpg")
        XCTAssertNil(original.photoFile)
        try await migrated.write { try $0.run("UPDATE recipes SET photoFile = 'stored.jpg' WHERE id = 7") }
        let stored = try await migrated.read { try XCTUnwrap(RecipeDao(db: $0).get(7)) }
        XCTAssertEqual(stored.photoFile, "stored.jpg")
    }

    // MARK: Export and import

    private func exportOrFail(_ repo: DefaultBackupRepository) async throws -> ExportedBackup {
        switch await repo.export() {
        case .success(let exported): return exported
        case .failure(let error): XCTFail("export failed: \(error)"); throw error
        }
    }

    func testAnExportCarriesTheRecipesOwnPhotoAndAnImportBringsItBack() async throws {
        let name = try XCTUnwrap(store.importData(Data("PICTURE".utf8)))
        _ = try await insert(record("https://x/a", photo: name))
        _ = try await insert(record("https://x/b"))

        let exported = try await exportOrFail(DefaultBackupRepository(db: db, clock: clock, photos: store))
        XCTAssertEqual(exported.photos.count, 1)
        let zip = BackupArchive.write(json: exported.json, photos: exported.photos.map { (path: $0.key, file: $0.value) })
        let unpacked = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        guard case .success(let package) = BackupArchive.read(zip, photoDirectory: unpacked, maxJsonBytes: 1_000_000) else {
            return XCTFail("zip didn't read")
        }

        let other = try AppDatabase(path: nil)
        let otherStore = FakePhotoStore()
        let into = DefaultBackupRepository(db: other, clock: clock, library: FixedLibraryLimit(limit: .unlimited), photos: otherStore)
        guard case .success(let summary) = await into.importBackup(package) else { return XCTFail("import failed") }
        XCTAssertEqual(summary.recipesAdded, 2)

        let copy = try await other.read { try XCTUnwrap(RecipeDao(db: $0).findByUrl("https://x/a")) }
        XCTAssertEqual(otherStore.text(try XCTUnwrap(copy.photoFile)), "PICTURE")
        let plain = try await other.read { try XCTUnwrap(RecipeDao(db: $0).findByUrl("https://x/b")) }
        XCTAssertNil(plain.photoFile)
        // Never a cooking.
        let cooked = try await other.read { try CookedPhotoDao(db: $0).photosFor(copy.id) }
        XCTAssertTrue(cooked.isEmpty)
    }

    func testAPhotoWithoutItsPictureStaysOutAndTheRecipeComes() async throws {
        let name = try XCTUnwrap(store.importData(Data("PICTURE".utf8)))
        _ = try await insert(record("https://x/a", photo: name))
        let exported = try await exportOrFail(DefaultBackupRepository(db: db, clock: clock, photos: store))
        // Only the JSON, as if the pictures had been lost on the way.
        let other = try AppDatabase(path: nil)
        let otherStore = FakePhotoStore()
        let into = DefaultBackupRepository(db: other, clock: clock, library: FixedLibraryLimit(limit: .unlimited), photos: otherStore)
        guard case .success(let summary) = await into.importBackup(BackupPackage(json: exported.json)) else { return XCTFail("import failed") }
        XCTAssertEqual(summary.recipesAdded, 1)
        let copy = try await other.read { try XCTUnwrap(RecipeDao(db: $0).findByUrl("https://x/a")) }
        XCTAssertNil(copy.photoFile)
        let stored = await otherStore.files()
        XCTAssertTrue(stored.isEmpty)
    }

    func testARecipeHereWithNoPhotoGainsTheFilesAndOneWithAPhotoKeepsItsOwn() async throws {
        let fromFile = try XCTUnwrap(store.importData(Data("FROM THE FILE".utf8)))
        _ = try await insert(record("https://x/a", photo: fromFile))
        _ = try await insert(record("https://x/b", photo: try XCTUnwrap(store.importData(Data("OTHER".utf8)))))
        let exported = try await exportOrFail(DefaultBackupRepository(db: db, clock: clock, photos: store))
        let zip = BackupArchive.write(json: exported.json, photos: exported.photos.map { (path: $0.key, file: $0.value) })
        guard case .success(let package) = BackupArchive.read(
            zip, photoDirectory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString), maxJsonBytes: 1_000_000
        ) else { return XCTFail("zip didn't read") }

        let other = try AppDatabase(path: nil)
        let otherStore = FakePhotoStore()
        let mine = try XCTUnwrap(otherStore.importData(Data("MINE".utf8)))
        try await other.write { conn in
            _ = try RecipeDao(db: conn).insert(self.record("https://x/a"))
            _ = try RecipeDao(db: conn).insert(self.record("https://x/b", photo: mine))
        }
        let into = DefaultBackupRepository(db: other, clock: clock, library: FixedLibraryLimit(limit: .unlimited), photos: otherStore)
        guard case .success(let summary) = await into.importBackup(package) else { return XCTFail("import failed") }
        XCTAssertEqual(summary.recipesAlreadyHere, 2)

        let a = try await other.read { try XCTUnwrap(RecipeDao(db: $0).findByUrl("https://x/a")) }
        XCTAssertEqual(otherStore.text(try XCTUnwrap(a.photoFile)), "FROM THE FILE")
        let b = try await other.read { try XCTUnwrap(RecipeDao(db: $0).findByUrl("https://x/b")) }
        XCTAssertEqual(b.photoFile, mine)
    }
}
