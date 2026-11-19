import XCTest
@testable import RecipeClipper

/// Import from Recipe Keeper (#390; Android's DefaultRecipeKeeperImportRepositoryTest) through the
/// repository the Paprika import uses, over a real in-memory database (with the seeded lists the
/// categories meet, #387), the real zip reader on the shared fixtures, and a fake photo store (#386).
final class RecipeKeeperImportRepositoryTests: XCTestCase {
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
        let url = try XCTUnwrap(bundle.url(forResource: name, withExtension: "zip", subdirectory: "fixtures/recipekeeper"))
        files.files[url] = try Data(contentsOf: url)
        return url
    }

    private func repository(_ limit: LibraryLimit = .unlimited) -> DefaultPaprikaImportRepository {
        DefaultPaprikaImportRepository(files: files, db: db, clock: clock, library: FixedLibraryLimit(limit: limit), photos: store)
    }

    private func importing(_ name: String, filing: Bool = false, limit: LibraryLimit = .unlimited) async throws -> PaprikaResult {
        await repository(limit).importPaprika(try fixture(name), filing: filing, source: .recipeKeeper)
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

    private func existing(_ n: Int, url: String? = nil, title: String = "Mine") async throws {
        let row = dataRecipeRecord(url ?? "https://old.example/\(n)", viewedAt: Int64(n), title: title)
        try await db.write { _ = try RecipeDao(db: $0).upsert(row, limit: .unlimited) }
    }

    func testAFilesRecipesJoinTheLibraryAsTheCooksOwnAsTheFileWroteThem() async throws {
        let result = try summary(await importing("recipekeeper-valid"))

        // Six recipes; the second cheesecake has the first's id; two blocks are no recipe; pasta's picture is missing.
        XCTAssertEqual(result, PaprikaSummary(
            imported: 6, alreadyHere: 1, skippedForLimit: 0, notRecipes: 2, unreadable: 0, photosLeftOut: 1, freeLimit: nil
        ))
        let rows = try await recipes()
        XCTAssertEqual(rows.map(\.title), [
            "Blueberry Cheesecake", "Chocolate Chip Cookies", "Tiramisù", "Pasta al Pomodoro", "Mint Tea", "Mac & Cheese – Quick",
        ])
        let cheesecake = try XCTUnwrap(rows.first { $0.title == "Blueberry Cheesecake" })
        XCTAssertEqual(cheesecake.sourceUrl, "manual:recipekeeper-a2489b23-050f-5c8a-8e5d-12fdb9fdeea3")
        XCTAssertEqual(cheesecake.contentOrigin, "MANUAL")
        XCTAssertEqual(cheesecake.servings, "Serves 6-8")
        XCTAssertEqual(cheesecake.prepTime, "10m")
        XCTAssertEqual(cheesecake.cookTime, "1h")
        XCTAssertEqual(cheesecake.notes, "Better the next day.\nFreezes well.")
        XCTAssertEqual(cheesecake.ingredients.first, "For the base:")
        XCTAssertEqual(cheesecake.ingredients.count, 6)
        XCTAssertEqual(cheesecake.instructions.count, 3)
        let cookies = try XCTUnwrap(rows.first { $0.title == "Chocolate Chip Cookies" })
        XCTAssertEqual(cookies.sourceUrl, "https://www.example.com/cookies?id=3")
        XCTAssertEqual(cookies.contentOrigin, "EDITED")
        XCTAssertEqual(cookies.prepTime, "5m")
        XCTAssertNil(cookies.cookTime, "PT0S is none")
        XCTAssertEqual(rows.first { $0.title == "Mint Tea" }?.sourceUrl, "manual:recipekeeper-h258018455a849b2a")
        // Without the switch nothing is filed and no list is made.
        let snapshot = try await db.read { try BackupDao(db: $0).snapshot() }
        XCTAssertTrue(snapshot.memberships.isEmpty)
        XCTAssertEqual(snapshot.lists.count, 6)
    }

    func testEachRecipesFirstReadablePictureBecomesItsOwnUnderANameOfTheStores() async throws {
        _ = try await importing("recipekeeper-valid")
        let rows = try await recipes()
        let photos = Dictionary(uniqueKeysWithValues: rows.map { ($0.title, $0.photoFile) })
        XCTAssertNotNil(photos["Blueberry Cheesecake"] ?? nil)
        XCTAssertNotNil(photos["Chocolate Chip Cookies"] ?? nil)
        XCTAssertNotNil(photos["Tiramisù"] ?? nil, "the second picture, the first is no image")
        XCTAssertNotNil(photos["Mac & Cheese – Quick"] ?? nil, "a name with a space in the zip")
        XCTAssertNil(photos["Pasta al Pomodoro"] ?? nil)
        XCTAssertNil(photos["Mint Tea"] ?? nil)
        let stored = await store.files()
        XCTAssertEqual(stored.count, 4)
        XCTAssertEqual(Set(rows.compactMap(\.photoFile)), Set(stored.keys))
        let cooked = try await db.read { try BackupDao(db: $0).snapshot().cookedPhotos }
        XCTAssertTrue(cooked.isEmpty)
    }

    func testImportingTheSameFileTwiceAddsNothingTheSecondTimePicturesIncluded() async throws {
        _ = try await importing("recipekeeper-valid")
        let again = try summary(await importing("recipekeeper-valid"))

        XCTAssertEqual(again, PaprikaSummary(
            imported: 0, alreadyHere: 7, skippedForLimit: 0, notRecipes: 2, unreadable: 0, photosLeftOut: 1, freeLimit: nil
        ))
        let count = try await recipes().count
        XCTAssertEqual(count, 6)
        let stored = await store.files()
        XCTAssertEqual(stored.count, 4)
    }

    func testARecipeAlreadyHereByItsLinkKeepsItsContentAndGainsTheFilesPicture() async throws {
        try await existing(1, url: "https://www.example.com/cookies?id=3", title: "My own cookies")
        let result = try summary(await importing("recipekeeper-valid"))

        XCTAssertEqual(result.imported, 5)
        XCTAssertEqual(result.alreadyHere, 2)
        let all = try await recipes()
        let here = try XCTUnwrap(all.first { $0.id == 1 })
        XCTAssertEqual(here.title, "My own cookies")
        XCTAssertNotNil(here.photoFile)
        XCTAssertEqual(all.count, 6)
    }

    func testAPaprikaRecipeAndARecipeKeeperOneWithTheSameTextAreTwoRecipes() async throws {
        try await existing(1, url: "manual:paprika-h258018455a849b2a", title: "Mint Tea")
        let result = try summary(await importing("recipekeeper-valid"))
        XCTAssertEqual(result.imported, 6)
        let count = try await recipes().count
        XCTAssertEqual(count, 7)
    }

    // MARK: #387: courses, categories and collections become lists

    func testWithTheSwitchOnCoursesCategoriesAndCollectionsPutRecipesInListsByName() async throws {
        let result = try summary(await importing("recipekeeper-valid", filing: true))

        // Dessert, Cake, Holiday Baking, Party, Snack, Baking, Cookie, Dolce, Dolci, Dolci della nonna, Drinks:
        // the seeded lists are Favorites, Lunch, Dinner, Desserts, Breakfast and Snacks.
        XCTAssertEqual(result.listsMade, 11)
        let cheesecake = try await listsOf("Blueberry Cheesecake")
        XCTAssertEqual(cheesecake, ["Cake", "Dessert", "Holiday Baking", "Party"])
        // Favourite: the Favorites list, found by its flag.
        let cookies = try await listsOf("Chocolate Chip Cookies")
        XCTAssertEqual(cookies, ["Baking", "Cookie", "Dessert", "Favorites", "Snack"])
        let tiramisu = try await listsOf("Tiramisù")
        XCTAssertEqual(tiramisu, ["Dolce", "Dolci", "Dolci della nonna"])
        let tea = try await listsOf("Mint Tea")
        XCTAssertEqual(tea, ["Drinks"])
        let pasta = try await listsOf("Pasta al Pomodoro")
        XCTAssertEqual(pasta, [])
        let all = try await lists()
        XCTAssertEqual(all.count, 6 + 11)
        XCTAssertEqual(all.filter(\.isFavorites).count, 1)
    }

    func testAListThatIsAlreadyHereIsFoundByItsNameWhateverTheCaseAndNotMadeAgain() async throws {
        let html = """
        <div class=recipe-details><meta content=k1 itemprop=recipeId><h2 itemprop=name>Stew</h2>
        <meta content=dinner itemprop=recipeCategory><meta content=DESSERTS itemprop=recipeCategory>
        <div itemprop=recipeIngredients><p>1 onion</p></div></div>
        """
        let url = URL(fileURLWithPath: "/tmp/one.zip")
        files.files[url] = PaprikaZipTestSupport.storedZip(["recipes.html": Data(html.utf8)])
        let result = try summary(await repository().importPaprika(url, filing: true, source: .recipeKeeper))

        XCTAssertEqual(result.listsMade, 0)
        let stew = try await listsOf("Stew")
        XCTAssertEqual(stew, ["Dinner", "Desserts"].sorted())
        let all = try await lists()
        XCTAssertEqual(all.count, 6)
    }

    func testWithTheSwitchOffNothingIsFiledFavouritesIncluded() async throws {
        let result = try summary(await importing("recipekeeper-valid", filing: false))
        XCTAssertEqual(result.listsMade, 0)
        let snapshot = try await db.read { try BackupDao(db: $0).snapshot() }
        XCTAssertTrue(snapshot.memberships.isEmpty)
    }

    // MARK: the free limit

    func testAtTheFreeLimitWhatFitsComesInTheRestIsCountedAndNothingOldIsRemoved() async throws {
        for n in 0..<49 { try await existing(n) }
        let result = try summary(await importing("recipekeeper-valid", filing: true, limit: .capped(max: 50)))

        XCTAssertEqual(result.imported, 1)
        XCTAssertEqual(result.skippedForLimit, 5)
        XCTAssertEqual(result.freeLimit, 50)
        let rows = try await recipes()
        XCTAssertEqual(rows.count, 50)
        XCTAssertEqual(rows.filter { $0.sourceUrl.hasPrefix("https://old.example/") }.count, 49)
        // The file's first recipe is the one that fits; the others' pictures and lists are not kept.
        XCTAssertEqual(rows.last?.title, "Blueberry Cheesecake")
        let stored = await store.files()
        XCTAssertEqual(stored.count, 1)
        let snapshot = try await db.read { try BackupDao(db: $0).snapshot() }
        XCTAssertEqual(result.listsMade, snapshot.lists.count - 6)
        XCTAssertEqual(Set(snapshot.memberships.map(\.recipeId)), [try XCTUnwrap(rows.last).id])
    }

    func testAFullFreeLibraryTakesNoneAndKeepsNoPictureOrList() async throws {
        for n in 0..<50 { try await existing(n) }
        let result = try summary(await importing("recipekeeper-valid", filing: true, limit: .capped(max: 50)))
        XCTAssertEqual(result.imported, 0)
        XCTAssertEqual(result.skippedForLimit, 6)
        let count = try await recipes().count
        XCTAssertEqual(count, 50)
        let stored = await store.files()
        XCTAssertTrue(stored.isEmpty)
        let all = try await lists()
        XCTAssertEqual(all.count, 6)
    }

    // MARK: other layouts and refused files

    func testAWrapperFolderABOMAndAddressesThatLeadOutsideTheZip() async throws {
        let result = try summary(await importing("recipekeeper-wrapper"))
        XCTAssertEqual(result, PaprikaSummary(
            imported: 2, alreadyHere: 0, skippedForLimit: 0, notRecipes: 0, unreadable: 0, photosLeftOut: 1, freeLimit: nil
        ))
        let rows = try await recipes()
        XCTAssertEqual(rows.map(\.title), ["Gran's Soup", "Flapjacks"])
        XCTAssertNotNil(rows.first { $0.title == "Gran's Soup" }?.photoFile)
        XCTAssertNil(rows.first { $0.title == "Flapjacks" }?.photoFile)
    }

    func testAPictureThatIsTooBigOrOnlyClaimsToBeIsLeftOutAndTheRecipeIsKept() async throws {
        let result = try summary(await importing("recipekeeper-hostile-photos"))
        XCTAssertEqual(result, PaprikaSummary(
            imported: 4, alreadyHere: 0, skippedForLimit: 0, notRecipes: 0, unreadable: 0, photosLeftOut: 2, freeLimit: nil
        ))
        let stored = await store.files()
        XCTAssertEqual(stored.count, 1)
    }

    func testAFileThatIsntRecipeKeepersADamagedOneAHugeOneAndAnUnreadableOneSayWhyAndWriteNothing() async throws {
        let notIt = try await importing("recipekeeper-not")
        XCTAssertEqual(notIt, .failure(.notARecipeKeeperFile))
        let noPage = try await importing("recipekeeper-no-page")
        XCTAssertEqual(noPage, .failure(.notARecipeKeeperFile))
        let truncated = try await importing("recipekeeper-truncated")
        XCTAssertEqual(truncated, .failure(.damaged))
        let bomb = try await importing("recipekeeper-bomb")
        XCTAssertEqual(bomb, .failure(.tooLarge))
        let huge = try await importing("recipekeeper-huge-declared")
        XCTAssertEqual(huge, .failure(.tooLarge))
        let gone = await repository().importPaprika(URL(fileURLWithPath: "/tmp/gone"), filing: false, source: .recipeKeeper)
        XCTAssertEqual(gone, .failure(.readFailed))
        let count = try await recipes().count
        XCTAssertEqual(count, 0)
        let stored = await store.files()
        XCTAssertTrue(stored.isEmpty)
    }

    func testAPaprikaFileGivenToTheRecipeKeeperImportIsNotRecipeKeepers() async throws {
        let bundle = Bundle(for: Self.self)
        let url = try XCTUnwrap(bundle.url(forResource: "paprika-valid", withExtension: "paprikarecipes", subdirectory: "fixtures/paprika"))
        files.files[url] = try Data(contentsOf: url)
        let result = await repository().importPaprika(url, filing: false, source: .recipeKeeper)
        XCTAssertEqual(result, .failure(.notARecipeKeeperFile))
    }

    func testDeepNestingStillImportsTheRecipe() async throws {
        let result = try summary(await importing("recipekeeper-deep"))
        XCTAssertEqual(result.imported, 1)
    }
}
