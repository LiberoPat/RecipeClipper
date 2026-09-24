import XCTest
@testable import RecipeClipper

/// Export and import against real SQLite (an in-memory AppDatabase built by the production
/// migrations): the shared fixture lands on the right rows, insert-or-ignore keeps `addedAt`,
/// a failure writes nothing, and an export imports back into an empty phone intact.
final class BackupDaoTests: XCTestCase {
    private var db: AppDatabase!
    private var repo: DefaultBackupRepository!
    private let clock = DataTestClock(1_790_000_000_000)

    override func setUpWithError() throws {
        db = try AppDatabase(path: nil)
        repo = DefaultBackupRepository(db: db, clock: clock)
    }

    override func tearDown() {
        repo = nil
        db = nil
    }

    private func insert(_ row: RecipeRecord) async throws -> Int64 {
        try await db.write { try RecipeDao(db: $0).insert(row) }
    }

    private func summary(_ result: Result<ImportSummary, BackupError>) throws -> ImportSummary {
        switch result {
        case .success(let s): return s
        case .failure(let e): XCTFail("import failed: \(e)"); throw e
        }
    }

    func testImportingTheSharedFixtureMergesIntoWhatIsHere() async throws {
        var soup = dataRecipeRecord("https://example.com/soup", viewedAt: 5)
        soup.uid = "e-soup"
        var bread = dataRecipeRecord("https://example.com/bread", viewedAt: 4)
        bread.uid = "e-bread"
        bread.notes = "mine"
        let soupId = try await insert(soup)
        let breadId = try await insert(bread)
        let favorites = try await db.favoritesId()
        let lunch = try await db.listId(named: "Lunch")
        try await db.putInList(breadId, favorites, at: 1)

        let result = try summary(await repo.importBackup(try backupFixture("backup-v1")))

        // No "Weeknight" here, so the file's "Midweek" is new too (on Android's test phone it joins one by uid).
        XCTAssertEqual(result, ImportSummary(recipesAdded: 3, listsAdded: 3, recipesAlreadyHere: 2, recipesSkipped: 0))
        let pie = try await db.read { try RecipeDao(db: $0).findByUrl("https://example.com/pie") }
        XCTAssertEqual(pie?.uid, "r-pie")
        XCTAssertEqual(pie?.notes, "Use cold butter.")
        XCTAssertEqual(pie?.checkedIngredients, [0])
        let salad = try await db.read { try RecipeDao(db: $0).findByUrl("https://example.com/salad") }
        XCTAssertNotEqual(salad?.uid, "e-bread", "a colliding uid is replaced")
        let soupNote = try await db.get(soupId)?.notes
        XCTAssertEqual(soupNote, "Less salt.\nDouble the onion.")
        let breadNote = try await db.get(breadId)?.notes
        XCTAssertEqual(breadNote, "mine")

        let lists = try await db.allLists()
        XCTAssertEqual(lists.filter(\.isFavorites).count, 1)
        XCTAssertEqual(lists.filter { $0.name == "Favorites" }.count, 2, "the file's non-Favorites \"Favorites\" is its own list")
        XCTAssertEqual(lists.filter { $0.name == "Party food" }.count, 1)
        XCTAssertEqual(lists.count, 9)

        let pieRefs = try await db.crossRefs(pie!.id)
        XCTAssertEqual(Set(pieRefs.map(\.listId)).count, 3)
        let breadRefs = try await db.crossRefs(breadId)
        XCTAssertEqual(breadRefs.first { $0.listId == favorites }?.addedAt, 1, "IGNORE, never REPLACE")
        XCTAssertTrue(breadRefs.contains { $0.listId == lunch && $0.addedAt == 14 })
    }

    func testImportingTwiceAddsNothingTheSecondTime() async throws {
        let text = try backupFixture("backup-v1")
        _ = try summary(await repo.importBackup(text))
        let recipes = try await db.recipeCount()
        let lists = try await db.allLists().count

        let second = try summary(await repo.importBackup(text))

        XCTAssertEqual(second.recipesAdded, 0)
        XCTAssertEqual(second.listsAdded, 0)
        let recipesAfter = try await db.recipeCount()
        let listsAfter = try await db.allLists().count
        XCTAssertEqual(recipesAfter, recipes)
        XCTAssertEqual(listsAfter, lists)
    }

    func testAFailedImportWritesNothing() async throws {
        let before = try await db.recipeCount()
        // Make the database refuse the import's first list insert, after its recipes are in.
        try await db.write { try $0.execute("CREATE TRIGGER no_lists BEFORE INSERT ON lists BEGIN SELECT RAISE(ABORT, 'no'); END") }

        let result = await repo.importBackup(try backupFixture("backup-v1"))

        XCTAssertEqual(result, .failure(.saveFailed))
        let after = try await db.recipeCount()
        XCTAssertEqual(after, before, "recipes written before the failure were rolled back")
    }

    func testAnUnreadableFileWritesNothing() async throws {
        let result = await repo.importBackup("{\"format\":\"recipe-clipper-backup\",\"formatVersion\":3}")
        XCTAssertEqual(result, .failure(.newerVersion(found: 3)))
        let count = try await db.recipeCount()
        XCTAssertEqual(count, 0)
    }

    func testAnExportImportsIntoAnEmptyPhoneIntact() async throws {
        var soup = dataRecipeRecord("https://example.com/soup", viewedAt: 5, checked: [1])
        soup.notes = "salt"
        soup.imageUrl = "https://example.com/i.jpg"
        let soupId = try await insert(soup)
        let listId = try await db.write { try ListDao(db: $0).create(name: "Weeknight", recipeId: soupId, now: 42) }
        try await db.putInList(soupId, try await db.favoritesId(), at: 7)

        guard case .success(let exported) = await repo.export() else { return XCTFail("export failed") }
        XCTAssertEqual(exported.exportedAt, 1_790_000_000_000)
        XCTAssertEqual(exported.recipeCount, 1)

        let other = try AppDatabase(path: nil)
        let otherRepo = DefaultBackupRepository(db: other, clock: clock)
        let result = try summary(await otherRepo.importBackup(exported.json))
        XCTAssertEqual(result, ImportSummary(recipesAdded: 1, listsAdded: 1, recipesAlreadyHere: 0, recipesSkipped: 0))

        let original = try await db.get(soupId)!
        let copy = try await other.read { try RecipeDao(db: $0).findByUrl("https://example.com/soup") }!
        XCTAssertEqual(copy.uid, original.uid)
        XCTAssertEqual(copy.title, original.title)
        XCTAssertEqual(copy.ingredients, original.ingredients)
        XCTAssertEqual(copy.instructions, original.instructions)
        XCTAssertEqual(copy.checkedIngredients, [1])
        XCTAssertEqual(copy.notes, "salt")
        XCTAssertEqual(copy.imageUrl, "https://example.com/i.jpg")
        XCTAssertEqual(copy.lastViewedAt, 5)
        let otherFavorites = try await other.favoritesId()
        let weeknight = try await other.allLists().first { $0.name == "Weeknight" }
        let refs = try await other.crossRefs(copy.id)
        XCTAssertEqual(Set(refs.map(\.listId)), [otherFavorites, try XCTUnwrap(weeknight).id])
        XCTAssertEqual(refs.first { $0.listId == otherFavorites }?.addedAt, 7)
        XCTAssertEqual(refs.first { $0.listId == weeknight?.id }?.addedAt, 42)
        _ = listId
    }

    func testExportedListsAndRecipesCarryTheirStableUids() async throws {
        let id = try await insert(dataRecipeRecord("https://example.com/a", viewedAt: 1))
        let uid = try await db.get(id)!.uid
        guard case .success(let first) = await repo.export(),
              case .success(let second) = await repo.export(),
              case .success(let a) = BackupJson.decode(first.json),
              case .success(let b) = BackupJson.decode(second.json)
        else { return XCTFail("export failed") }
        XCTAssertEqual(a.recipes.map(\.id), [uid])
        XCTAssertEqual(a.recipes.map(\.id), b.recipes.map(\.id))
        XCTAssertEqual(a.lists.map(\.id), b.lists.map(\.id))
        XCTAssertEqual(Set(a.lists.map(\.id)).count, 6)
    }
}
