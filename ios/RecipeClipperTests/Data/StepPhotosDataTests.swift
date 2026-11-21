import XCTest
@testable import RecipeClipper

/// Step photos (#433) over real SQLite (Android's MigrationTest 19 to 20 and the repository
/// tests): stored with the recipe, refreshed by a re-share with its content, kept through an edit
/// and by the user's version, carried by an export, and added by the migration.
final class StepPhotosDataTests: XCTestCase {
    private let url = "https://www.cookist.it/fagottini/"
    private let one = "https://img.example/1.jpg"
    private let two = "https://img.example/2.jpg"
    private var db: AppDatabase!
    private var clock: DataTestClock!
    private var source: DataStubSource!
    private var recipes: DefaultRecipeRepository!

    override func setUpWithError() throws {
        db = try AppDatabase(path: nil)
        clock = DataTestClock(1_000)
        source = DataStubSource()
        source.results[url] = .success(parsed(steps: ["Stendi (1).", "Farcisci (2)."], photos: [[one], [two]]))
        recipes = DefaultRecipeRepository(db: db, source: source, clock: clock, sleep: { _ in })
    }

    override func tearDown() {
        recipes = nil
        db = nil
    }

    private func parsed(steps: [String], photos: [[String]]) -> Recipe {
        var recipe = Recipe(
            name: "Fagottini", image: nil, ingredients: ["8 fette"], instructions: steps,
            prepTime: nil, cookTime: nil, totalTime: nil, yield: "8", sourceUrl: url
        )
        recipe.stepPhotos = photos
        return recipe
    }

    private func imported() async throws -> Recipe {
        guard case .success(let recipe) = await recipes.importFromUrl(url) else {
            XCTFail("import failed")
            throw CancellationError()
        }
        return recipe
    }

    private func stored(_ id: Int64) async throws -> Recipe {
        try await db.read { try XCTUnwrap(RecipeDao(db: $0).get(id)).toDomain() }
    }

    func testAParsedRecipesStepPhotosAreStoredWithIt() async throws {
        let id = try await imported().id
        let recipe = try await stored(id)
        XCTAssertEqual(recipe.stepPhotos, [[one], [two]])
        let raw = try await db.read { try XCTUnwrap(RecipeDao(db: $0).get(id)).stepPhotos }
        XCTAssertNotNil(raw)
    }

    func testARecipeWithoutStepPhotosStoresNone() async throws {
        source.results[url] = .success(parsed(steps: ["Mix."], photos: []))
        let id = try await imported().id
        let raw = try await db.read { try XCTUnwrap(RecipeDao(db: $0).get(id)).stepPhotos }
        XCTAssertNil(raw)
        let recipe = try await stored(id)
        XCTAssertEqual(recipe.stepPhotos, [])
    }

    func testAReShareRefreshesTheStepPhotosWithTheContent() async throws {
        let id = try await imported().id
        source.results[url] = .success(parsed(steps: ["Stendi (1).", "Farcisci (2).", "Servi."], photos: [[two], [one], []]))
        _ = await recipes.importFromUrl(url)
        let recipe = try await stored(id)
        XCTAssertEqual(recipe.stepPhotos, [[two], [one], []])
        // A source that stops giving them takes them away.
        source.results[url] = .success(parsed(steps: ["Stendi (1)."], photos: []))
        _ = await recipes.importFromUrl(url)
        let again = try await stored(id)
        XCTAssertEqual(again.stepPhotos, [])
    }

    func testAnEditKeepsThePhotosWithTheirStepsAndTheUsersVersionKeepsThemOnAReShare() async throws {
        let id = try await imported().id
        var draft = RecipeDraft.of(try await stored(id))
        draft.instructionsText = "Farcisci (2).\nStendi bene le fette (1)."
        let saved = await recipes.saveEdit(id: id, draft: draft)
        XCTAssertEqual(saved?.stepPhotos, [[two], [one]])

        source.results[url] = .success(parsed(steps: ["Other."], photos: [["https://img.example/other.jpg"]]))
        _ = await recipes.importFromUrl(url)
        let recipe = try await stored(id)
        XCTAssertEqual(recipe.origin, .edited)
        XCTAssertEqual(recipe.stepPhotos, [[two], [one]])
    }

    func testAnExportCarriesTheStepPhotosAndAnImportBringsThemBack() async throws {
        _ = try await imported()
        guard case .success(let exported) = await DefaultBackupRepository(db: db, clock: clock).export() else {
            return XCTFail("export failed")
        }
        let other = try AppDatabase(path: nil)
        let into = DefaultBackupRepository(db: other, clock: clock, library: FixedLibraryLimit(limit: .unlimited))
        guard case .success(let summary) = await into.importBackup(BackupPackage(json: exported.json)) else {
            return XCTFail("import failed")
        }
        XCTAssertEqual(summary.recipesAdded, 1)
        let copy = try await other.read { try XCTUnwrap(RecipeDao(db: $0).findByUrl(self.url)).toDomain() }
        XCTAssertEqual(copy.stepPhotos, [[one], [two]])
    }

    // MARK: Migration

    /// A user_version 18 file (Android's Room 19) gains a nullable `stepPhotos` (Android's
    /// `MigrationTest.migration19To20…`): every recipe comes across as it was with none, and they can
    /// then be written. A user_version 17 file, which first gains `photoFile` (#386), ends the same.
    /// (#432, recipe tips, also claims user_version 19 on its branch: whichever lands second renumbers.)
    func testAVersion18DatabaseMigratesToVersion19() async throws {
        try await assertStepPhotosMigration(from: 18)
    }

    func testAVersion17DatabaseEndsWithBothChanges() async throws {
        try await assertStepPhotosMigration(from: 17)
    }

    private func assertStepPhotosMigration(from oldVersion: Int) async throws {
        let path = NSTemporaryDirectory() + "rc-\(UUID().uuidString).sqlite"
        defer {
            for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) }
        }
        do {
            let old = try SQLiteConnection(path: path)
            try AppDatabase.migrate(old, upTo: oldVersion)
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
        XCTAssertEqual(AppDatabase.schemaVersion, 19)
        let original = try await migrated.read { try XCTUnwrap(RecipeDao(db: $0).get(7)) }
        XCTAssertEqual(original.title, "Adobo")
        XCTAssertEqual(original.instructions, ["Simmer."])
        XCTAssertNil(original.stepPhotos)
        XCTAssertNil(original.photoFile)
        try await migrated.write { try $0.run("UPDATE recipes SET stepPhotos = ? WHERE id = 7", "[[\"https://img.example/s.jpg\"]]") }
        let stored = try await migrated.read { try XCTUnwrap(RecipeDao(db: $0).get(7)).toDomain() }
        XCTAssertEqual(stored.stepPhotos, [["https://img.example/s.jpg"]])
    }
}
