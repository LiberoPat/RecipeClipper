import XCTest
@testable import RecipeClipper

/// Import from Mealie (#391; Android's DefaultMealieImportRepositoryTest) through the repository over a
/// real in-memory database (with the seeded lists the categories meet, #387), the real zip reader on the
/// shared fixtures, and a fake photo store (#386).
final class MealieImportRepositoryTests: XCTestCase {
    private var db: AppDatabase!
    private let files = FakeMealieFiles()
    private let store = FakePhotoStore()
    private let clock = DataTestClock(1_790_000_000_000)

    override func setUpWithError() throws {
        db = try AppDatabase(path: nil)
        files.photos = store
    }
    override func tearDown() { db = nil }

    private func fixture(_ name: String, _ ext: String = "zip") throws -> URL {
        let bundle = Bundle(for: Self.self)
        let url = try XCTUnwrap(bundle.url(forResource: name, withExtension: ext, subdirectory: "fixtures/mealie"))
        files.files[url] = try Data(contentsOf: url)
        return url
    }

    private func repository(_ limit: LibraryLimit = .unlimited) -> DefaultMealieImportRepository {
        DefaultMealieImportRepository(files: files, db: db, clock: clock, library: FixedLibraryLimit(limit: limit), photos: store)
    }

    private func summary(_ result: MealieResult, file: StaticString = #filePath, line: UInt = #line) throws -> MealieSummary {
        guard case .success(let summary) = result else {
            XCTFail("expected a success, got \(result)", file: file, line: line)
            throw CancellationError()
        }
        return summary
    }

    private func recipes() async throws -> [RecipeRecord] {
        try await db.read { try BackupDao(db: $0).snapshot().recipes }.sorted { $0.id < $1.id }
    }

    private func lists() async throws -> [BackupSnapshot.ListRow] {
        try await db.read { try BackupDao(db: $0).snapshot().lists }
    }

    /// The names of the lists the recipe titled `title` is in, sorted.
    private func listsOf(_ title: String) async throws -> [String] {
        let snapshot = try await db.read { try BackupDao(db: $0).snapshot() }
        let id = try XCTUnwrap(snapshot.recipes.first { $0.title == title }).id
        return snapshot.memberships.filter { $0.recipeId == id }
            .map { m in snapshot.lists.first { $0.id == m.listId }!.name }.sorted()
    }

    private func existing(_ n: Int, url: String? = nil, title: String = "Mine", photo: String? = nil) async throws {
        var row = dataRecipeRecord(url ?? "https://old.example/\(n)", viewedAt: Int64(n), title: title)
        row.photoFile = photo
        try await db.write { _ = try RecipeDao(db: $0).upsert(row, limit: .unlimited) }
    }

    func testABulkExportsRecipesJoinTheLibraryAsTheCooksOwn() async throws {
        let result = try summary(await repository().importMealie(fixture("mealie-bulk"), filing: false))

        // Pasta, the old soup and tea; the second pasta has the first's link; three files are no recipe; one is unreadable.
        XCTAssertEqual(result, MealieSummary(
            imported: 3, alreadyHere: 1, skippedForLimit: 0, notRecipes: 3, unreadable: 1, photosLeftOut: 0, freeLimit: nil
        ))
        let rows = try await recipes()
        XCTAssertEqual(rows.map(\.title), ["Pasta e Ceci", "Old Lentil Soup", "Mint Tea"])
        let pasta = try XCTUnwrap(rows.first { $0.title == "Pasta e Ceci" })
        XCTAssertEqual(pasta.sourceUrl, "https://www.example.com/pasta-e-ceci?id=9")
        XCTAssertEqual(pasta.contentOrigin, "EDITED")
        XCTAssertEqual(pasta.ingredients, ["The base:", "2 cups canned chickpeas, drained", "1 tbsp olive oil", "The pasta:", "200 g ditalini", "salt"])
        XCTAssertEqual(pasta.instructions, [
            "Start the soup: Heat the oil and soften the garlic.",
            "Add the chickpeas and 4 cups of water, then simmer for 20 minutes.",
            "Finish: Stir in the pasta and cook until tender.",
        ])
        XCTAssertEqual(pasta.servings, "4 servings")
        XCTAssertEqual(pasta.prepTime, "10m")
        XCTAssertEqual(pasta.cookTime, "35m")
        XCTAssertEqual(pasta.totalTime, "45m")
        XCTAssertEqual(pasta.notes, "Storage\nKeeps 3 days in the fridge.\n\nThickens as it stands.")
        XCTAssertNil(pasta.imageUrl)
        let soup = try XCTUnwrap(rows.first { $0.title == "Old Lentil Soup" })
        XCTAssertEqual(soup.sourceUrl, "manual:mealie-old-lentil-soup")
        XCTAssertEqual(soup.contentOrigin, "MANUAL")
        // Before Mealie 3.1: the unparsed lines were stored with quantity 1, and never show it.
        XCTAssertEqual(soup.ingredients, ["2 cups red lentils", "1 onion, chopped", "2 cups water"])
        XCTAssertEqual(soup.servings, "2.5")
        XCTAssertEqual(soup.prepTime, "10m")
        XCTAssertEqual(soup.totalTime, "1h 15m")
        // Without the switch nothing is filed and no list is made.
        let snapshot = try await db.read { try BackupDao(db: $0).snapshot() }
        XCTAssertTrue(snapshot.memberships.isEmpty)
        XCTAssertEqual(snapshot.lists.count, 6)
    }

    func testARecipesDownloadBringsItsPictureInAsTheRecipesOwnNeverACooking() async throws {
        let result = try summary(await repository().importMealie(fixture("mealie-recipe-download"), filing: false))
        XCTAssertEqual(result, MealieSummary(
            imported: 1, alreadyHere: 0, skippedForLimit: 0, notRecipes: 0, unreadable: 0, photosLeftOut: 0, freeLimit: nil
        ))
        let rows = try await recipes()
        let name = try XCTUnwrap(rows.first?.photoFile)
        XCTAssertTrue(store.exists(name))
        let cooked = try await db.read { try BackupDao(db: $0).snapshot().cookedPhotos }
        XCTAssertTrue(cooked.isEmpty)
        let stored = await store.files()
        XCTAssertEqual(stored.count, 1)
    }

    func testABareJsonIsOneRecipe() async throws {
        let result = try summary(await repository().importMealie(fixture("mealie-recipe", "json"), filing: false))
        XCTAssertEqual(result.imported, 1)
        let rows = try await recipes()
        let tea = try XCTUnwrap(rows.first)
        XCTAssertEqual(tea.title, "Mint Tea")
        XCTAssertEqual(tea.sourceUrl, "manual:mealie-mint-tea")
        XCTAssertEqual(tea.ingredients, ["a handful of fresh mint"])
        XCTAssertEqual(tea.servings, "1")
        XCTAssertEqual(tea.cookTime, "5m")
        XCTAssertEqual(tea.totalTime, "10m")
    }

    func testAPictureThatIsntOneOrIsTooBigIsLeftOutAndCountedAndTheRecipeStays() async throws {
        let result = try summary(await repository().importMealie(fixture("mealie-photos"), filing: false))

        XCTAssertEqual(result, MealieSummary(
            imported: 4, alreadyHere: 0, skippedForLimit: 0, notRecipes: 0, unreadable: 0, photosLeftOut: 2, freeLimit: nil
        ))
        let photos = Dictionary(uniqueKeysWithValues: try await recipes().map { ($0.title, $0.photoFile) })
        XCTAssertNotNil(photos["Toast"] ?? nil)
        for title in ["Jam", "Honey", "Salt"] { XCTAssertNil(photos[title] ?? nil, title) }
        let stored = await store.files()
        XCTAssertEqual(stored.count, 1)
    }

    func testPicturesNoRecipeEndsUpWithAreDeletedNoneIsLeftBehind() async throws {
        // The pasta is already here, with a picture of its own: the file's picture for it is not kept.
        let mine = try XCTUnwrap(store.importData(Data("mine".utf8)))
        try await existing(1, url: "https://www.example.com/pasta-e-ceci?id=9", photo: mine)
        _ = await repository().importMealie(try fixture("mealie-with-pictures"), filing: false)

        let rows = try await recipes()
        let named = Set(rows.compactMap(\.photoFile))
        let stored = Set(await store.files().keys)
        XCTAssertEqual(named, stored)
        XCTAssertEqual(store.text(try XCTUnwrap(rows.first { $0.id == 1 }?.photoFile)), "mine")
    }

    func testARecipeAlreadyHereWithNoPictureGainsTheFilesAndNothingElseOfItChanges() async throws {
        try await existing(1, url: "https://www.example.com/pasta-e-ceci?id=9", title: "My own pasta")
        _ = await repository().importMealie(try fixture("mealie-recipe-download"), filing: false)

        let all = try await recipes()
        let here = try XCTUnwrap(all.first { $0.id == 1 })
        XCTAssertEqual(here.title, "My own pasta")
        XCTAssertEqual(here.ingredients, ["1 cup flour", "2 eggs"])
        XCTAssertTrue(store.exists(try XCTUnwrap(here.photoFile)))
        XCTAssertEqual(all.count, 1)
    }

    func testAFullFreeLibraryTakesNoneAndKeepsNoPicture() async throws {
        for n in 0..<50 { try await existing(n) }
        _ = await repository(.capped(max: 50)).importMealie(try fixture("mealie-recipe-download"), filing: false)
        let count = try await recipes().count
        XCTAssertEqual(count, 50)
        let stored = await store.files()
        XCTAssertTrue(stored.isEmpty)
    }

    func testImportingTheSameFileTwiceAddsNothingTheSecondTimePicturesIncluded() async throws {
        let url = try fixture("mealie-with-pictures")
        _ = await repository().importMealie(url, filing: false)
        let again = try summary(await repository().importMealie(url, filing: false))

        XCTAssertEqual(again, MealieSummary(
            imported: 0, alreadyHere: 2, skippedForLimit: 0, notRecipes: 0, unreadable: 0, photosLeftOut: 0, freeLimit: nil
        ))
        let count = try await recipes().count
        XCTAssertEqual(count, 2)
        let stored = await store.files()
        XCTAssertEqual(stored.count, 1)
    }

    func testARecipeAlreadyHereKeepsItsContentAndOnlyFillsAnEmptyNote() async throws {
        try await existing(1, url: "https://www.example.com/pasta-e-ceci?id=9", title: "My own pasta")
        let result = try summary(await repository().importMealie(fixture("mealie-bulk"), filing: false))

        XCTAssertEqual(result.imported, 2)
        // The one here, and the second pasta in the file that has the same link.
        XCTAssertEqual(result.alreadyHere, 2)
        let all = try await recipes()
        let here = try XCTUnwrap(all.first { $0.id == 1 })
        XCTAssertEqual(here.title, "My own pasta")
        XCTAssertEqual(here.ingredients, ["1 cup flour", "2 eggs"])
        XCTAssertEqual(here.contentOrigin, "PARSED")
        XCTAssertEqual(here.notes, "Storage\nKeeps 3 days in the fridge.\n\nThickens as it stands.")
        XCTAssertEqual(all.count, 3)
    }

    // MARK: #387: categories and tags become lists

    func testWithTheSwitchOnCategoriesAndTagsPutRecipesInListsByNameAndNewOnesAreMade() async throws {
        let result = try summary(await repository().importMealie(fixture("mealie-bulk"), filing: true))

        // Soup, Vegetarian, Weeknight (from the repeat), Drinks and Quick are new; "dinner" is the Dinner list here.
        XCTAssertEqual(result.listsMade, 5)
        let pasta = try await listsOf("Pasta e Ceci")
        XCTAssertEqual(pasta, ["Dinner", "Soup", "Vegetarian", "Weeknight"])
        let soup = try await listsOf("Old Lentil Soup")
        XCTAssertEqual(soup, [])
        let tea = try await listsOf("Mint Tea")
        XCTAssertEqual(tea, ["Drinks", "Quick"])
        let all = try await lists()
        XCTAssertEqual(all.count, 6 + 5)
        // Mealie keeps favourites per user, outside the recipe: nothing goes to Favorites.
        let snapshot = try await db.read { try BackupDao(db: $0).snapshot() }
        let favorites = try XCTUnwrap(snapshot.lists.first(where: \.isFavorites))
        XCTAssertTrue(snapshot.memberships.allSatisfy { $0.listId != favorites.id })
    }

    func testWithTheSwitchOffNothingIsFiled() async throws {
        let result = try summary(await repository().importMealie(fixture("mealie-bulk"), filing: false))
        XCTAssertEqual(result.listsMade, 0)
        let snapshot = try await db.read { try BackupDao(db: $0).snapshot() }
        XCTAssertTrue(snapshot.memberships.isEmpty)
        XCTAssertEqual(snapshot.lists.count, 6)
    }

    func testRecipesTheFreeLimitLeavesOutAreFiledNowhereAndMakeNoEmptyList() async throws {
        for n in 0..<49 { try await existing(n) }
        let result = try summary(await repository(.capped(max: 50)).importMealie(fixture("mealie-bulk"), filing: true))

        // One recipe fits (the file's first); the other two are left out with their lists.
        XCTAssertEqual(result.imported, 1)
        XCTAssertEqual(result.skippedForLimit, 2)
        let snapshot = try await db.read { try BackupDao(db: $0).snapshot() }
        let came = try XCTUnwrap(snapshot.recipes.first { !$0.sourceUrl.hasPrefix("https://old.example/") })
        XCTAssertEqual(came.title, "Pasta e Ceci")
        XCTAssertEqual(Set(snapshot.memberships.map(\.recipeId)), [came.id])
        XCTAssertEqual(result.listsMade, snapshot.lists.count - 6)
        XCTAssertTrue(snapshot.lists.allSatisfy { $0.name != "Drinks" && $0.name != "Quick" })
    }

    // MARK: the rest of #391

    func testAtTheFreeLimitWhatFitsComesInTheRestIsCountedAndNothingOldIsRemoved() async throws {
        for n in 0..<49 { try await existing(n) }
        let result = try summary(await repository(.capped(max: 50)).importMealie(fixture("mealie-bulk"), filing: false))

        XCTAssertEqual(result.imported, 1)
        XCTAssertEqual(result.skippedForLimit, 2)
        XCTAssertEqual(result.freeLimit, 50)
        let rows = try await recipes()
        XCTAssertEqual(rows.count, 50)
        XCTAssertEqual(rows.filter { $0.sourceUrl.hasPrefix("https://old.example/") }.count, 49)
    }

    func testFilesThatCouldntBeReadAreCountedAndTheRestImport() async throws {
        let result = try summary(await repository().importMealie(fixture("mealie-bad-entries"), filing: false))
        XCTAssertEqual(result.imported, 1)
        XCTAssertEqual(result.unreadable, 3)
    }

    func testAFileWithNothingReadableWritesNothing() async throws {
        files.limits.maxEntryBytes = 10
        let result = try summary(await repository().importMealie(fixture("mealie-bad-entries"), filing: false))
        XCTAssertEqual(result, MealieSummary(
            imported: 0, alreadyHere: 0, skippedForLimit: 0, notRecipes: 0, unreadable: 4, photosLeftOut: 0, freeLimit: nil
        ))
        let count = try await recipes().count
        XCTAssertEqual(count, 0)
    }

    func testAFileThatIsntMealiesADamagedOneAndAnUnreadableOneSayWhyAndWriteNothing() async throws {
        let damaged = await repository().importMealie(try fixture("mealie-truncated"), filing: false)
        XCTAssertEqual(damaged, .failure(.damaged))
        let notMealie = await repository().importMealie(try fixture("mealie-not-mealie"), filing: false)
        XCTAssertEqual(notMealie, .failure(.notAMealieFile))
        // A recipe page's schema.org data is not a Mealie file: it goes in through the link.
        let schema = await repository().importMealie(try fixture("mealie-schema-org", "json"), filing: false)
        XCTAssertEqual(schema, .failure(.notAMealieFile))
        let onlySchema = await repository().importMealie(try fixture("mealie-only-schema"), filing: false)
        XCTAssertEqual(onlySchema, .failure(.notAMealieFile))
        let text = URL(fileURLWithPath: "/tmp/a.txt")
        files.files[text] = Data("not a zip at all".utf8)
        let notAZip = await repository().importMealie(text, filing: false)
        XCTAssertEqual(notAZip, .failure(.notAMealieFile))
        let gone = await repository().importMealie(URL(fileURLWithPath: "/tmp/gone"), filing: false)
        XCTAssertEqual(gone, .failure(.readFailed))
        let count = try await recipes().count
        XCTAssertEqual(count, 0)
    }

    func testAFileThatUnpacksToFarMoreThanItSaysIsDroppedEntryByEntry() async throws {
        let bomb = try summary(await repository().importMealie(fixture("mealie-bomb"), filing: false))
        XCTAssertEqual(bomb.imported, 1)
        XCTAssertEqual(bomb.unreadable, 1)
        let huge = try summary(await repository().importMealie(fixture("mealie-huge-declared"), filing: false))
        XCTAssertEqual(huge.imported, 0)
        XCTAssertEqual(huge.alreadyHere, 1)
        XCTAssertEqual(huge.unreadable, 1)
    }
}
