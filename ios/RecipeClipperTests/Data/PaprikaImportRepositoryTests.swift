import XCTest
@testable import RecipeClipper

/// Import from Paprika (#382; Android's DefaultPaprikaImportRepositoryTest) through the repository
/// over a real in-memory database and the real zip reader on the shared fixtures.
final class PaprikaImportRepositoryTests: XCTestCase {
    private var db: AppDatabase!
    private let files = FakePaprikaFiles()
    private let clock = DataTestClock(1_790_000_000_000)

    override func setUpWithError() throws { db = try AppDatabase(path: nil) }
    override func tearDown() { db = nil }

    private func fixture(_ name: String) throws -> URL {
        let bundle = Bundle(for: Self.self)
        let url = try XCTUnwrap(bundle.url(forResource: name, withExtension: "paprikarecipes", subdirectory: "fixtures/paprika"))
        files.files[url] = try Data(contentsOf: url)
        return url
    }

    private func repository(_ limit: LibraryLimit = .unlimited) -> DefaultPaprikaImportRepository {
        DefaultPaprikaImportRepository(files: files, db: db, clock: clock, library: FixedLibraryLimit(limit: limit))
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

    private func existing(_ n: Int, url: String? = nil, title: String = "Mine") async throws {
        let row = dataRecipeRecord(url ?? "https://old.example/\(n)", viewedAt: Int64(n), title: title)
        try await db.write { _ = try RecipeDao(db: $0).upsert(row, limit: .unlimited) }
    }

    func testAFilesRecipesJoinTheLibraryAsTheCooksOwn() async throws {
        let result = try summary(await repository().importPaprika(fixture("paprika-valid")))

        // Brownies, soup and tea; the second brownies has the first's link; two entries are no recipe.
        XCTAssertEqual(result, PaprikaSummary(
            imported: 3, alreadyHere: 1, skippedForLimit: 0, notRecipes: 2, unreadable: 0, photosNotImported: true, freeLimit: nil
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
        let lists = try await db.read { try BackupDao(db: $0).snapshot().memberships }
        XCTAssertTrue(lists.isEmpty)
    }

    func testImportingTheSameFileTwiceAddsNothingTheSecondTime() async throws {
        let url = try fixture("paprika-valid")
        _ = await repository().importPaprika(url)
        let again = try summary(await repository().importPaprika(url))

        XCTAssertEqual(again, PaprikaSummary(
            imported: 0, alreadyHere: 4, skippedForLimit: 0, notRecipes: 2, unreadable: 0, photosNotImported: true, freeLimit: nil
        ))
        let count = try await recipes().count
        XCTAssertEqual(count, 3)
    }

    func testARecipeAlreadyHereKeepsItsContentAndOnlyFillsAnEmptyNote() async throws {
        try await existing(1, url: "https://www.example.com/brownies?id=7", title: "My own brownies")
        let result = try summary(await repository().importPaprika(fixture("paprika-valid")))

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

    func testAtTheFreeLimitWhatFitsComesInTheRestIsCountedAndNothingOldIsRemoved() async throws {
        for n in 0..<49 { try await existing(n) }
        let result = try summary(await repository(.capped(max: 50)).importPaprika(fixture("paprika-valid")))

        XCTAssertEqual(result.imported, 1)
        XCTAssertEqual(result.skippedForLimit, 2)
        XCTAssertEqual(result.freeLimit, 50)
        let rows = try await recipes()
        XCTAssertEqual(rows.count, 50)
        XCTAssertEqual(rows.filter { $0.sourceUrl.hasPrefix("https://old.example/") }.count, 49)
    }

    func testAFullFreeLibraryTakesNoneAndSaysSo() async throws {
        for n in 0..<50 { try await existing(n) }
        let result = try summary(await repository(.capped(max: 50)).importPaprika(fixture("paprika-valid")))
        XCTAssertEqual(result.imported, 0)
        XCTAssertEqual(result.skippedForLimit, 3)
        let count = try await recipes().count
        XCTAssertEqual(count, 50)
    }

    func testEntriesThatCouldntBeReadAreCountedAndTheRestImport() async throws {
        let result = try summary(await repository().importPaprika(fixture("paprika-bad-entries")))
        XCTAssertEqual(result.imported, 1)
        XCTAssertEqual(result.unreadable, 4)
    }

    func testAFileWithNothingReadableWritesNothing() async throws {
        files.limits.maxEntryBytes = 10
        let result = try summary(await repository().importPaprika(fixture("paprika-bad-entries")))
        XCTAssertEqual(result, PaprikaSummary(
            imported: 0, alreadyHere: 0, skippedForLimit: 0, notRecipes: 0, unreadable: 5, photosNotImported: false, freeLimit: nil
        ))
        let count = try await recipes().count
        XCTAssertEqual(count, 0)
    }

    func testAFileThatIsntPaprikasADamagedOneAndAnUnreadableOneSayWhyAndWriteNothing() async throws {
        let damaged = await repository().importPaprika(try fixture("paprika-truncated"))
        XCTAssertEqual(damaged, .failure(.damaged))
        let text = URL(fileURLWithPath: "/tmp/a.txt")
        files.files[text] = Data("not a zip at all".utf8)
        let notPaprika = await repository().importPaprika(text)
        XCTAssertEqual(notPaprika, .failure(.notAPaprikaFile))
        let gone = await repository().importPaprika(URL(fileURLWithPath: "/tmp/gone"))
        XCTAssertEqual(gone, .failure(.readFailed))
        let count = try await recipes().count
        XCTAssertEqual(count, 0)
    }

    func testAFileThatUnpacksToFarMoreThanItIsIsDroppedEntryByEntry() async throws {
        let bomb = try summary(await repository().importPaprika(fixture("paprika-bomb")))
        XCTAssertEqual(bomb.imported, 1)
        XCTAssertEqual(bomb.unreadable, 1)
    }

    func testAnEntryThatClaimsToBe4GBIsDroppedUnread() async throws {
        let huge = try summary(await repository().importPaprika(fixture("paprika-huge-declared")))
        XCTAssertEqual(huge.imported, 1)
        XCTAssertEqual(huge.unreadable, 1)
    }
}
