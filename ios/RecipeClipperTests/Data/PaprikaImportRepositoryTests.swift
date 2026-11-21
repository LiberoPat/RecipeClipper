import XCTest
@testable import RecipeClipper

/// Import from Paprika (#382; Android's DefaultPaprikaImportRepositoryTest) through the repository
/// over a real in-memory database (with the seeded lists the categories meet, #387), the real zip
/// reader on the shared fixtures, and a fake photo store (#386).
final class PaprikaImportRepositoryTests: XCTestCase {
    private var db: AppDatabase!
    private let files = FakePaprikaFiles()
    private let store = FakePhotoStore()
    private let clock = DataTestClock(1_790_000_000_000)

    override func setUpWithError() throws {
        db = try AppDatabase(path: nil)
        files.photos = store
    }
    override func tearDown() { db = nil }

    private func fixture(_ name: String) throws -> URL {
        let bundle = Bundle(for: Self.self)
        let url = try XCTUnwrap(bundle.url(forResource: name, withExtension: "paprikarecipes", subdirectory: "fixtures/paprika"))
        files.files[url] = try Data(contentsOf: url)
        return url
    }

    private func repository(_ limit: LibraryLimit = .unlimited) -> DefaultPaprikaImportRepository {
        DefaultPaprikaImportRepository(files: files, db: db, clock: clock, library: FixedLibraryLimit(limit: limit), photos: store)
    }

    private func summary(_ result: PaprikaResult, file: StaticString = #filePath, line: UInt = #line) throws -> PaprikaSummary {
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

    func testAFilesRecipesJoinTheLibraryAsTheCooksOwn() async throws {
        let result = try summary(await repository().importPaprika(fixture("paprika-valid"), filing: false))

        // Brownies, soup and tea; the second brownies has the first's link; two entries are no recipe.
        XCTAssertEqual(result, PaprikaSummary(
            imported: 3, alreadyHere: 1, skippedForLimit: 0, notRecipes: 2, unreadable: 0, photosLeftOut: 0, freeLimit: nil
        ))
        let rows = try await recipes()
        XCTAssertEqual(rows.map(\.title), ["Fudgy Brownies", "Gran's Lentil Soup", "Mint Tea"])
        let brownies = try XCTUnwrap(rows.first { $0.title == "Fudgy Brownies" })
        XCTAssertEqual(brownies.sourceUrl, "https://www.example.com/brownies?id=7")
        XCTAssertEqual(brownies.contentOrigin, "EDITED")
        XCTAssertEqual(brownies.imageUrl, "https://img.example.com/brownies.jpg")
        XCTAssertEqual(brownies.cookTime, "1h 30m")
        XCTAssertEqual(brownies.notes, "Dad's favourite. Use less sugar next time.")
        let soup = try XCTUnwrap(rows.first { $0.title == "Gran's Lentil Soup" })
        XCTAssertEqual(soup.sourceUrl, "manual:paprika-0a9b8c7d-6e5f-4a3b-8c2d-1e0f9a8b7c6d")
        XCTAssertEqual(soup.contentOrigin, "MANUAL")
        XCTAssertEqual(soup.prepTime, "10m")
        // Without the switch nothing is filed and no list is made.
        let snapshot = try await db.read { try BackupDao(db: $0).snapshot() }
        XCTAssertTrue(snapshot.memberships.isEmpty)
        XCTAssertEqual(snapshot.lists.count, 6)
    }

    func testEachPictureInTheFileBecomesTheRecipesOwnUnderANameOfTheStores() async throws {
        let result = try summary(await repository().importPaprika(fixture("paprika-valid"), filing: false))
        XCTAssertEqual(result.photosLeftOut, 0)
        let rows = try await recipes()
        let brownies = try XCTUnwrap(rows.first { $0.title == "Fudgy Brownies" }?.photoFile)
        let soup = try XCTUnwrap(rows.first { $0.title == "Gran's Lentil Soup" }?.photoFile)
        XCTAssertNil(rows.first { $0.title == "Mint Tea" }?.photoFile)
        XCTAssertTrue([brownies, soup].allSatisfy { !$0.contains("Brownies") && !$0.contains("Soup") })
        XCTAssertTrue(store.exists(brownies) && store.exists(soup))
        // The picture is a recipe's own, never a cooking.
        let cooked = try await db.read { try BackupDao(db: $0).snapshot().cookedPhotos }
        XCTAssertTrue(cooked.isEmpty)
    }

    func testAPictureThatIsntOneOrIsTooBigIsLeftOutAndCountedAndTheRecipeStays() async throws {
        let result = try summary(await repository().importPaprika(fixture("paprika-photos"), filing: false))

        XCTAssertEqual(result, PaprikaSummary(
            imported: 5, alreadyHere: 0, skippedForLimit: 0, notRecipes: 0, unreadable: 0, photosLeftOut: 3, freeLimit: nil
        ))
        let photos = Dictionary(uniqueKeysWithValues: try await recipes().map { ($0.title, $0.photoFile) })
        XCTAssertNotNil(photos["Toast"] ?? nil)
        for title in ["Jam", "Butter", "Honey", "Salt"] { XCTAssertNil(photos[title] ?? nil, title) }
        let stored = await store.files()
        XCTAssertEqual(stored.count, 1)
    }

    func testPicturesNoRecipeEndsUpWithAreDeletedNoneIsLeftBehind() async throws {
        // Brownies is already here, with a picture of its own: the file's picture for it is not kept, nor is it stored.
        let mine = try XCTUnwrap(store.importData(Data("mine".utf8)))
        try await existing(1, url: "https://www.example.com/brownies?id=7", photo: mine)
        _ = await repository().importPaprika(try fixture("paprika-valid"), filing: false)

        let rows = try await recipes()
        let named = Set(rows.compactMap(\.photoFile))
        let stored = Set(await store.files().keys)
        XCTAssertEqual(named, stored)
        XCTAssertEqual(store.text(try XCTUnwrap(rows.first { $0.id == 1 }?.photoFile)), "mine")
        XCTAssertEqual(named.count, 2)
    }

    func testARecipeAlreadyHereWithNoPictureGainsTheFilesAndNothingElseOfItChanges() async throws {
        try await existing(1, url: "https://www.example.com/brownies?id=7", title: "My own brownies")
        _ = await repository().importPaprika(try fixture("paprika-valid"), filing: false)

        let all = try await recipes()
        let here = try XCTUnwrap(all.first { $0.id == 1 })
        XCTAssertEqual(here.title, "My own brownies")
        let name = try XCTUnwrap(here.photoFile)
        XCTAssertTrue(store.exists(name))
    }

    func testAFullFreeLibraryTakesNoneAndKeepsNoPicture() async throws {
        for n in 0..<50 { try await existing(n) }
        _ = await repository(.capped(max: 50)).importPaprika(try fixture("paprika-valid"), filing: false)
        let count = try await recipes().count
        XCTAssertEqual(count, 50)
        let stored = await store.files()
        XCTAssertTrue(stored.isEmpty)
    }

    func testImportingTheSameFileTwiceAddsNothingTheSecondTimePicturesIncluded() async throws {
        let url = try fixture("paprika-valid")
        _ = await repository().importPaprika(url, filing: false)
        let again = try summary(await repository().importPaprika(url, filing: false))

        XCTAssertEqual(again, PaprikaSummary(
            imported: 0, alreadyHere: 4, skippedForLimit: 0, notRecipes: 2, unreadable: 0, photosLeftOut: 0, freeLimit: nil
        ))
        let count = try await recipes().count
        XCTAssertEqual(count, 3)
        let stored = await store.files()
        XCTAssertEqual(stored.count, 2)
    }

    func testARecipeAlreadyHereKeepsItsContentAndOnlyFillsAnEmptyNote() async throws {
        try await existing(1, url: "https://www.example.com/brownies?id=7", title: "My own brownies")
        let result = try summary(await repository().importPaprika(fixture("paprika-valid"), filing: false))

        XCTAssertEqual(result.imported, 2)
        // The one here, and the second brownies in the file that has the same link.
        XCTAssertEqual(result.alreadyHere, 2)
        let all = try await recipes()
        let here = try XCTUnwrap(all.first { $0.sourceUrl == "https://www.example.com/brownies?id=7" })
        XCTAssertEqual(here.title, "My own brownies")
        XCTAssertEqual(here.ingredients, ["1 cup flour", "2 eggs"])
        XCTAssertEqual(here.contentOrigin, "PARSED")
        XCTAssertEqual(here.notes, "Dad's favourite. Use less sugar next time.")
        let count = try await recipes().count
        XCTAssertEqual(count, 3)
    }

    // MARK: #387: categories become lists

    func testWithTheSwitchOnCategoriesPutRecipesInListsByNameAndNewOnesAreMade() async throws {
        let result = try summary(await repository().importPaprika(fixture("paprika-valid"), filing: true))

        // Dessert, Baking, Soup, Drinks and holiday are new; "dinner" is the Dinner list here, whatever the case.
        XCTAssertEqual(result.listsMade, 5)
        let brownies = try await listsOf("Fudgy Brownies")
        XCTAssertEqual(brownies, ["Baking", "Dessert", "holiday"])
        let soup = try await listsOf("Gran's Lentil Soup")
        XCTAssertEqual(soup, ["Dinner", "Soup"])
        // Tea: its categories, and Favorites only because Paprika flags it a favourite.
        let tea = try await listsOf("Mint Tea")
        XCTAssertEqual(tea, ["Baking", "Drinks", "Favorites"])
        let all = try await lists()
        XCTAssertEqual(all.count, 6 + 5)
        // The Dinner and Favorites lists here were found, not made again, and "dinner" didn't rename it.
        XCTAssertEqual(all.filter { $0.name.lowercased() == "dinner" }.map(\.name), ["Dinner"])
        XCTAssertEqual(all.filter(\.isFavorites).count, 1)
    }

    func testACategoryNamedFavoritesIsAListOfItsOwnNeverTheFavoritesList() async throws {
        let json = #"{"name": "Soup", "ingredients": "1 onion", "categories": ["favorites"], "on_favorites": 0}"#
        let url = URL(fileURLWithPath: "/tmp/fav.paprikarecipes")
        files.files[url] = Self.zip(of: json)
        _ = await repository().importPaprika(url, filing: true)

        let soup = try await listsOf("Soup")
        XCTAssertEqual(soup, ["favorites"])
        let snapshot = try await db.read { try BackupDao(db: $0).snapshot() }
        let favorites = try XCTUnwrap(snapshot.lists.first(where: \.isFavorites))
        XCTAssertTrue(snapshot.memberships.allSatisfy { $0.listId != favorites.id })
    }

    func testWithTheSwitchOffNothingIsFiledFavouritesIncluded() async throws {
        let result = try summary(await repository().importPaprika(fixture("paprika-valid"), filing: false))
        XCTAssertEqual(result.listsMade, 0)
        let snapshot = try await db.read { try BackupDao(db: $0).snapshot() }
        XCTAssertTrue(snapshot.memberships.isEmpty)
        XCTAssertEqual(snapshot.lists.count, 6)
    }

    func testARecipeAlreadyHereGainsTheListsAndOneItWasAlreadyInKeepsItsPlace() async throws {
        try await existing(1, url: "https://www.example.com/brownies?id=7", title: "My own brownies")
        let seeded = try await lists()
        let dinner = try XCTUnwrap(seeded.first { $0.name == "Dinner" })
        try await db.write { conn in
            try conn.run("INSERT INTO recipe_list_cross_ref (recipeId, listId, addedAt) VALUES (1, ?, 99)", dinner.id)
        }
        _ = await repository().importPaprika(try fixture("paprika-valid"), filing: true)

        let mine = try await listsOf("My own brownies")
        XCTAssertEqual(mine, ["Baking", "Dessert", "Dinner", "holiday"])
        // Insert-or-ignore: Dinner's addedAt was not rewritten.
        let snapshot = try await db.read { try BackupDao(db: $0).snapshot() }
        XCTAssertEqual(snapshot.memberships.first { $0.recipeId == 1 && $0.listId == dinner.id }?.addedAt, 99)
    }

    func testRecipesTheFreeLimitLeavesOutAreFiledNowhereAndMakeNoEmptyList() async throws {
        for n in 0..<49 { try await existing(n) }
        let result = try summary(await repository(.capped(max: 50)).importPaprika(fixture("paprika-valid"), filing: true))

        // One recipe fits (a typed-in one counts first); the other two are left out with their lists.
        XCTAssertEqual(result.imported, 1)
        XCTAssertEqual(result.skippedForLimit, 2)
        let snapshot = try await db.read { try BackupDao(db: $0).snapshot() }
        let came = try XCTUnwrap(snapshot.recipes.first { !$0.sourceUrl.hasPrefix("https://old.example/") })
        XCTAssertEqual(Set(snapshot.memberships.map(\.recipeId)), [came.id])
        XCTAssertEqual(result.listsMade, snapshot.lists.count - 6)
        XCTAssertTrue(snapshot.lists.allSatisfy { list in
            snapshot.memberships.contains { $0.listId == list.id } || list.createdAt != 1_790_000_000_000
        })
    }

    func testAFullFreeLibraryMakesNoListAtAll() async throws {
        for n in 0..<50 { try await existing(n) }
        let result = try summary(await repository(.capped(max: 50)).importPaprika(fixture("paprika-valid"), filing: true))
        XCTAssertEqual(result.listsMade, 0)
        let all = try await lists()
        XCTAssertEqual(all.count, 6)
    }

    // MARK: the rest of #382

    func testAtTheFreeLimitWhatFitsComesInTheRestIsCountedAndNothingOldIsRemoved() async throws {
        for n in 0..<49 { try await existing(n) }
        let result = try summary(await repository(.capped(max: 50)).importPaprika(fixture("paprika-valid"), filing: false))

        XCTAssertEqual(result.imported, 1)
        XCTAssertEqual(result.skippedForLimit, 2)
        XCTAssertEqual(result.freeLimit, 50)
        let rows = try await recipes()
        XCTAssertEqual(rows.count, 50)
        XCTAssertEqual(rows.filter { $0.sourceUrl.hasPrefix("https://old.example/") }.count, 49)
    }

    func testEntriesThatCouldntBeReadAreCountedAndTheRestImport() async throws {
        let result = try summary(await repository().importPaprika(fixture("paprika-bad-entries"), filing: false))
        XCTAssertEqual(result.imported, 1)
        XCTAssertEqual(result.unreadable, 4)
    }

    func testAFileWithNothingReadableWritesNothing() async throws {
        files.limits.maxEntryBytes = 10
        let result = try summary(await repository().importPaprika(fixture("paprika-bad-entries"), filing: false))
        XCTAssertEqual(result, PaprikaSummary(
            imported: 0, alreadyHere: 0, skippedForLimit: 0, notRecipes: 0, unreadable: 5, photosLeftOut: 0, freeLimit: nil
        ))
        let count = try await recipes().count
        XCTAssertEqual(count, 0)
    }

    func testAFileThatIsntPaprikasADamagedOneAndAnUnreadableOneSayWhyAndWriteNothing() async throws {
        let damaged = await repository().importPaprika(try fixture("paprika-truncated"), filing: false)
        XCTAssertEqual(damaged, .failure(.damaged))
        let text = URL(fileURLWithPath: "/tmp/a.txt")
        files.files[text] = Data("not a zip at all".utf8)
        let notPaprika = await repository().importPaprika(text, filing: false)
        XCTAssertEqual(notPaprika, .failure(.notAPaprikaFile))
        let gone = await repository().importPaprika(URL(fileURLWithPath: "/tmp/gone"), filing: false)
        XCTAssertEqual(gone, .failure(.readFailed))
        let count = try await recipes().count
        XCTAssertEqual(count, 0)
    }

    func testAFileThatUnpacksToFarMoreThanItIsIsDroppedEntryByEntry() async throws {
        let bomb = try summary(await repository().importPaprika(fixture("paprika-bomb"), filing: false))
        XCTAssertEqual(bomb.imported, 1)
        XCTAssertEqual(bomb.unreadable, 1)
    }

    func testAnEntryThatClaimsToBe4GBIsDroppedUnread() async throws {
        let huge = try summary(await repository().importPaprika(fixture("paprika-huge-declared"), filing: false))
        XCTAssertEqual(huge.imported, 1)
        XCTAssertEqual(huge.unreadable, 1)
    }

    /// A zip of one `.paprikarecipe` entry holding `json`, "gzipped" with a stored block.
    private static func zip(of json: String) -> Data {
        let gz = PaprikaZipTestSupport.gzip(Data(json.utf8))
        return PaprikaZipTestSupport.storedZip(["One.paprikarecipe": gz])
    }
}
