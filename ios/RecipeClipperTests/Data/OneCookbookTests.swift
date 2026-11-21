import XCTest
@testable import RecipeClipper

/// One cookbook (#345, Android's OneCookbookLimitTest, OneCookbookDaoTest and OneCookbookImportTest):
/// the free tier's limit of 50 holds on every way a recipe comes in, through the repositories over a
/// real in-memory database. At the limit a new recipe is shown but not saved, and nothing old is ever
/// removed to make room; a recipe already saved still opens and refreshes.
final class OneCookbookTests: XCTestCase {
    private var db: AppDatabase!
    private let capped = FixedLibraryLimit(limit: .capped(max: 50))
    private let now: Int64 = 1_800_000_000_000

    override func setUpWithError() throws { db = try AppDatabase(path: nil) }
    override func tearDown() { db = nil }

    private func repository(
        source: RecipeSource = DataStubSource(), library: LibraryLimitSource? = nil
    ) -> DefaultRecipeRepository {
        DefaultRecipeRepository(
            db: db, source: source, clock: DataTestClock(5_000), sleep: { _ in }, library: library ?? capped
        )
    }

    /// `n` parsed recipes nobody listed, the very kind the old clean-up would have removed.
    @discardableResult
    private func fill(_ n: Int, prefix: String = "old") async throws -> [Int64] {
        var ids: [Int64] = []
        for i in 0..<n {
            let row = dataRecipeRecord("https://\(prefix).com/\(i)", viewedAt: Int64(i))
            ids.append(try await db.write { try RecipeDao(db: $0).upsert(row, limit: .unlimited) })
        }
        return ids
    }

    private func count() async throws -> Int { try await db.read { try RecipeDao(db: $0).count() } }

    private func assertNothingWasRemoved(_ ids: [Int64], file: StaticString = #filePath, line: UInt = #line) async throws {
        let left = try await count()
        XCTAssertEqual(left, 50, file: file, line: line)
        for id in ids {
            let row = try await db.get(id)
            XCTAssertNotNil(row, "nothing is removed to make room", file: file, line: line)
        }
    }

    private func stub(_ url: String, name: String = "Soup") -> DataStubSource {
        let source = DataStubSource()
        source.results[url] = .success(dataRecipe(url, title: name))
        return source
    }

    // MARK: - Which limit applies

    func testWhichLimitApplies() {
        XCTAssertEqual(LibraryLimit.of(freeTier: false, unlocked: false), .history(keep: 50))
        XCTAssertEqual(LibraryLimit.of(freeTier: true, unlocked: false), .free(max: 20))
        XCTAssertEqual(LibraryLimit.of(freeTier: false, unlocked: false, oneCookbook: true), .unlimited)
        XCTAssertEqual(LibraryLimit.of(freeTier: true, unlocked: true, oneCookbook: true), .unlimited)
        XCTAssertEqual(LibraryLimit.of(freeTier: true, unlocked: false, oneCookbook: true), .capped(max: 50))
        XCTAssertEqual(LibraryLimit.freeRecipes(oneCookbook: true), 50)
        XCTAssertEqual(LibraryLimit.freeRecipes(oneCookbook: false), 20)
    }

    // MARK: - The DAO

    func testAtTheLimitANewRecipeIsNotKeptAndNothingIsRemoved() async throws {
        let old = try await fill(50)
        let id = try await db.write { try RecipeDao(db: $0).upsert(dataRecipeRecord("https://a.com/new", viewedAt: 10_000), limit: .capped(max: 50)) }
        XCTAssertEqual(id, RecipeDao.notKept)
        try await assertNothingWasRemoved(old)
    }

    func testTheFiftiethFitsAndARecipeAlreadyHereRefreshesAtTheLimit() async throws {
        let first = try await db.write { try RecipeDao(db: $0).upsert(dataRecipeRecord("https://a.com/soup", viewedAt: 1), limit: .unlimited) }
        try await fill(49)
        var better = dataRecipeRecord("https://a.com/soup", viewedAt: 99_000)
        better.title = "Soup, better"
        let again = try await db.write { try RecipeDao(db: $0).upsert(better, limit: .capped(max: 50)) }
        XCTAssertEqual(again, first)
        let row = try await db.get(first)
        XCTAssertEqual(row?.title, "Soup, better")
        let total = try await count()
        XCTAssertEqual(total, 50)
    }

    func testTheTourSampleTakesNoPlace() async throws {
        var sample = dataRecipeRecord(SampleRecipe.sourceUrl, viewedAt: 5)
        sample.contentOrigin = "MANUAL"
        _ = try await db.write { [sample] in try RecipeDao(db: $0).upsert(sample, limit: .unlimited) }
        try await fill(50)
        let total = try await count()
        XCTAssertEqual(total, 50)
        let id = try await db.write { try RecipeDao(db: $0).upsert(dataRecipeRecord("https://a.com/new", viewedAt: 9), limit: .capped(max: 50)) }
        XCTAssertEqual(id, RecipeDao.notKept)
    }

    func testNoLimitNeverCullsPastFifty() async throws {
        let ids = try await fill(60)
        _ = try await db.write {
            try RecipeDao(db: $0).upsert(dataRecipeRecord("https://a.com/new", viewedAt: 10_000), limit: LibraryLimit.of(freeTier: false, unlocked: false, oneCookbook: true))
        }
        let total = try await count()
        XCTAssertEqual(total, 61)
        for id in ids { let row = try await db.get(id); XCTAssertNotNil(row) }
    }

    // MARK: - Every way in, through the repository

    func testASharedLinkAtTheLimitIsShownNotSavedAndNothingIsRemoved() async throws {
        let old = try await fill(50)
        let result = await repository(source: stub("https://a.com/soup")).importFromUrl("https://a.com/soup")
        guard case .notKept(let shown) = result else { return XCTFail("expected notKept, got \(result)") }
        XCTAssertEqual(shown.name, "Soup")
        XCTAssertEqual(shown.id, 0)
        try await assertNothingWasRemoved(old)
    }

    func testARedditPostAtTheLimitIsShownNotSaved() async throws {
        let old = try await fill(50)
        let url = "https://www.reddit.com/r/Cooking/comments/abc"
        var post = dataRecipe(url, title: "Post")
        post.sourceType = .reddit
        let source = DataStubSource()
        source.results[url] = .success(post)
        guard case .notKept = await repository(source: source).importFromUrl(url) else { return XCTFail("expected notKept") }
        try await assertNothingWasRemoved(old)
    }

    func testAPagePassedThroughCloudflaresCheckAtTheLimitIsNotSaved() async throws {
        let old = try await fill(50)
        let html = """
        <html><script type="application/ld+json">
        {"@type":"Recipe","name":"Soup","recipeIngredient":["1 leek"],"recipeInstructions":["Simmer."]}
        </script></html>
        """
        guard case .notKept = await repository().importPage("https://a.com/soup", html: html) else {
            return XCTFail("expected notKept")
        }
        try await assertNothingWasRemoved(old)
    }

    func testAClipAtTheLimitIsNotSaved() async throws {
        let old = try await fill(50)
        guard case .notKept = await repository().saveClip(dataRecipe("https://a.com/clip", title: "Clip")) else {
            return XCTFail("expected notKept")
        }
        try await assertNothingWasRemoved(old)
    }

    func testATypedScannedPhotoReadOrSharedTextRecipeAtTheLimitComesBackUnsaved() async throws {
        let old = try await fill(50)
        let repo = repository()
        let typed = await repo.addManual(draft: RecipeDraft(name: "Toast", ingredientsText: "bread"))
        XCTAssertEqual(typed?.id, 0)
        let scanned = await repo.addManual(draft: RecipeDraft(name: "Gnocchi", ingredientsText: "500 g Kartoffeln"), language: "de")
        XCTAssertEqual(scanned?.id, 0)
        let text = await repo.addManual(
            draft: RecipeDraft(name: "Pancakes", ingredientsText: "2 eggs"), language: "en", notes: "Left over from the text"
        )
        XCTAssertEqual(text?.id, 0)
        try await assertNothingWasRemoved(old)
    }

    func testARecipeShownUnsavedIsKeptOnceTheCookUnlocks() async throws {
        try await fill(50)
        guard case .notKept(let shown) = await repository(source: stub("https://a.com/soup")).importFromUrl("https://a.com/soup") else {
            return XCTFail("expected notKept")
        }
        guard case .success(let kept) = await repository(library: FixedLibraryLimit(limit: .unlimited)).keep(shown) else {
            return XCTFail("keep failed")
        }
        XCTAssertGreaterThan(kept.id, 0)
        let total = try await count()
        XCTAssertEqual(total, 51)
    }

    func testDeletingOneOfTheFiftyFreesAPlace() async throws {
        let old = try await fill(50)
        _ = await repository().delete(id: old[0])
        guard case .success = await repository(source: stub("https://a.com/soup")).importFromUrl("https://a.com/soup") else {
            return XCTFail("expected a saved recipe")
        }
        let total = try await count()
        XCTAssertEqual(total, 50)
    }

    func testARecipeAlreadySavedStillOpensAndRefreshesAtTheLimit() async throws {
        let source = stub("https://a.com/soup")
        guard case .success = await repository(source: source).importFromUrl("https://a.com/soup") else {
            return XCTFail("first save failed")
        }
        try await fill(49)
        source.results["https://a.com/soup"] = .success(dataRecipe("https://a.com/soup", title: "Soup, improved"))
        guard case .success(let again) = await repository(source: source).importFromUrl("https://a.com/soup") else {
            return XCTFail("expected the saved recipe")
        }
        XCTAssertEqual(again.name, "Soup, improved")
        let total = try await count()
        XCTAssertEqual(total, 50)
    }

    // MARK: - The share extension saves in its own process

    /// The extension builds the same repository over the App Group suite's mirror of the limit, so
    /// "capped" there must stop a share at 50 just as the app does.
    func testTheMirroredCapIsWhatTheShareExtensionAppliesAndRoundTrips() async throws {
        let suite = "OneCookbookTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let mirror = DefaultsLibraryLimit(defaults: defaults)
        mirror.store(.capped(max: 50))
        XCTAssertEqual(mirror.current(), .capped(max: 50))
        mirror.store(.unlimited)
        XCTAssertEqual(mirror.current(), .unlimited)
        mirror.store(.capped(max: 50))

        let old = try await fill(50)
        let extensionRepository = DefaultRecipeRepository(
            db: db, source: stub("https://a.com/soup"), clock: DataTestClock(5_000), sleep: { _ in }, library: mirror
        )
        guard case .notKept(let shown) = await extensionRepository.importFromUrl("https://a.com/soup") else {
            return XCTFail("the extension should show it unsaved")
        }
        XCTAssertEqual(shown.name, "Soup")
        try await assertNothingWasRemoved(old)
    }

    @MainActor
    func testThePolicyMirrorsTheCapWhenTheFlagsSayFreeTierAndOneCookbook() throws {
        let suite = "OneCookbookTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let flags = FeatureFlags(store: MemoryFeatureFlagStore(["freeTier": true, "oneCookbook": true, "proUnlock": false]), isDebug: false)
        let mirror = DefaultsLibraryLimit(defaults: defaults)
        let policy = LibraryPolicy(flags: flags, entitlements: FakeEntitlements(), mirror: mirror)
        XCTAssertEqual(policy.limit, .capped(max: 50))
        policy.startMirroring()
        XCTAssertEqual(mirror.current(), .capped(max: 50))
    }

    // MARK: - Imports

    private func exported(_ from: AppDatabase) async throws -> String {
        switch await DefaultBackupRepository(db: from, clock: DataTestClock(9)).export() {
        case .success(let exported): return exported.json
        case .failure(let error): XCTFail("export failed: \(error)"); throw error
        }
    }

    private func importSummary(_ json: String, into target: AppDatabase) async throws -> ImportSummary {
        let into = DefaultBackupRepository(db: target, clock: DataTestClock(9), library: capped)
        switch await into.importBackup(BackupPackage(json: json)) {
        case .success(let summary): return summary
        case .failure(let error): XCTFail("import failed: \(error)"); throw error
        }
    }

    private func fill(_ n: Int, in target: AppDatabase, prefix: String) async throws {
        for i in 0..<n {
            let row = dataRecipeRecord("https://\(prefix).com/\(i)", viewedAt: Int64(i))
            _ = try await target.write { try RecipeDao(db: $0).upsert(row, limit: .unlimited) }
        }
    }

    func testABackupStopsAtTheLimitAndSaysHowManyWereLeftOut() async throws {
        let from = try AppDatabase(path: nil)
        try await fill(10, in: from, prefix: "from")
        try await fill(47, prefix: "here")
        let json = try await exported(from)

        let summary = try await importSummary(json, into: db)

        XCTAssertEqual(summary.recipesAdded, 3)
        XCTAssertEqual(summary.recipesSkipped, 7)
        XCTAssertEqual(summary.freeLimit, 50)
        let total = try await count()
        XCTAssertEqual(total, 50)
        let again = try await importSummary(json, into: db)
        XCTAssertEqual(again.recipesAdded, 0)
        let after = try await count()
        XCTAssertEqual(after, 50)
    }

    func testAListedRecipeIsLimitedTooAndItsListStaysOutWithIt() async throws {
        let from = try AppDatabase(path: nil)
        let id = try await from.write { try RecipeDao(db: $0).upsert(dataRecipeRecord("https://from.com/listed", viewedAt: 1), limit: .unlimited) }
        try await from.putInList(id, try await from.favoritesId())
        try await fill(50)
        let json = try await exported(from)

        let summary = try await importSummary(json, into: db)

        XCTAssertEqual(summary.recipesAdded, 0)
        XCTAssertEqual(summary.recipesSkipped, 1)
        let missing = try await db.read { try RecipeDao(db: $0).findByUrl("https://from.com/listed") }
        XCTAssertNil(missing)
        let total = try await count()
        XCTAssertEqual(total, 50)
    }

    func testTheListedOnesTakeTheFreePlacesFirst() {
        let here = (1...46).map {
            ExistingRecipe(id: Int64($0), uid: "u\($0)", sourceUrl: "https://h.example/\($0)", hasNotes: false, isListed: false)
        }
        func recipe(_ id: String, viewed: Int64) -> BackupRecipe {
            BackupRecipe(
                id: id, sourceUrl: "https://f.example/\(id)", sourceType: "BLOG", title: id, imageUrl: nil,
                ingredients: [], instructions: [], prepTime: nil, cookTime: nil, totalTime: nil, servings: nil,
                lastViewedAt: viewed, checkedIngredients: [], notes: nil
            )
        }
        let incoming = (1...6).map { recipe("f\($0)", viewed: Int64($0) * 10) }
        let lists = [BackupList(id: "l1", name: "Soups", isFavorites: false, isBuiltIn: false, sortOrder: 9, createdAt: 0)]
        let memberships = [
            BackupMembership(recipeId: "f1", listId: "l1", addedAt: 1),
            BackupMembership(recipeId: "f6", listId: "l1", addedAt: 1),
        ]
        var n = 0
        let plan = BackupMerger.plan(
            Backup(exportedAt: 0, recipes: incoming, lists: lists, memberships: memberships),
            existingRecipes: here, existingLists: [], maxSortOrder: 0, historyLimit: 50,
            newUid: { n += 1; return "gen-\(n)" }, capsEveryRecipe: true
        )
        // f1 is the oldest viewed but listed: it takes a place before the newer ones.
        XCTAssertEqual(plan.newRecipes.map(\.id).sorted(), ["f1", "f4", "f5", "f6"])
        XCTAssertEqual(plan.summary.recipesSkipped, 2)
        XCTAssertEqual(plan.summary.freeLimit, 50)
        XCTAssertEqual(plan.memberships.count, 2)
    }

    func testASkippedListedRecipeTakesItsMembershipWithIt() {
        let here = (1...50).map {
            ExistingRecipe(id: Int64($0), uid: "u\($0)", sourceUrl: "https://h.example/\($0)", hasNotes: false, isListed: false)
        }
        let one = BackupRecipe(
            id: "f1", sourceUrl: "https://f.example/1", sourceType: "BLOG", title: "F1", imageUrl: nil,
            ingredients: [], instructions: [], prepTime: nil, cookTime: nil, totalTime: nil, servings: nil,
            lastViewedAt: 0, checkedIngredients: [], notes: nil
        )
        let lists = [BackupList(id: "l1", name: "Soups", isFavorites: false, isBuiltIn: false, sortOrder: 9, createdAt: 0)]
        var n = 0
        let plan = BackupMerger.plan(
            Backup(exportedAt: 0, recipes: [one], lists: lists, memberships: [BackupMembership(recipeId: "f1", listId: "l1", addedAt: 1)]),
            existingRecipes: here, existingLists: [], maxSortOrder: 0, historyLimit: 50,
            newUid: { n += 1; return "gen-\(n)" }, capsEveryRecipe: true
        )
        XCTAssertTrue(plan.newRecipes.isEmpty)
        XCTAssertTrue(plan.memberships.isEmpty)
        XCTAssertEqual(plan.summary.recipesSkipped, 1)
    }

    func testASentFileStopsAtTheLimitToo() async throws {
        try await fill(50)
        let file = try decodeOrFail(shareFixture())
        let repo = DefaultShareFileRepository(db: db, clock: DataTestClock(now), library: capped)

        let result = await repo.receive(file, choice: ShareChoice(recipeIds: ["r-chicken"], groceryIds: []))
        guard case .success(let summary) = result else { return XCTFail("receive failed: \(result)") }

        XCTAssertEqual(summary.recipesAdded, 0)
        XCTAssertEqual(summary.recipesSkipped, 1)
        XCTAssertEqual(summary.freeLimit, 50)
        let missing = try await db.read { try RecipeDao(db: $0).findByUrl("https://example.com/sheet-pan-chicken") }
        XCTAssertNil(missing)
        let total = try await count()
        XCTAssertEqual(total, 50)
    }

    // MARK: - The views

    func testTheViewsAreQuestionsAboutWhatIsStored() async throws {
        let inList = try await db.write { try RecipeDao(db: $0).upsert(dataRecipeRecord("https://a.com/listed", viewedAt: 1), limit: .unlimited) }
        let cooked = try await db.write { try RecipeDao(db: $0).upsert(dataRecipeRecord("https://a.com/cooked", viewedAt: 2), limit: .unlimited) }
        var typedRow = dataRecipeRecord("manual:typed", viewedAt: 3)
        typedRow.contentOrigin = "MANUAL"
        let typed = try await db.write { [typedRow] in try RecipeDao(db: $0).upsert(typedRow, limit: .unlimited) }
        var sampleRow = dataRecipeRecord(SampleRecipe.sourceUrl, viewedAt: 4)
        sampleRow.contentOrigin = "MANUAL"
        let sample = try await db.write { [sampleRow] in try RecipeDao(db: $0).upsert(sampleRow, limit: .unlimited) }
        var chatRow = dataRecipeRecord("manual:chat", viewedAt: 5)
        chatRow.contentOrigin = "CHAT"
        let chat = try await db.write { [chatRow] in try RecipeDao(db: $0).upsert(chatRow, limit: .unlimited) }
        try await db.putInList(inList, try await db.favoritesId())
        // "Mark as cooked" (#173) with no photo counts as cooked.
        try await db.write {
            try $0.run(
                "INSERT INTO cooked_photos (uid, recipeId, fileName, day, note, createdAt, updatedAt) VALUES ('c1', ?, NULL, 20000, NULL, 1, 1)",
                cooked
            )
        }

        func ids(_ listed: Int, _ cooked: Int, _ typed: Int, _ query: String = "", chat: Int = 0) async throws -> Set<Int64> {
            Set(try await db.read { try RecipeDao(db: $0).history(query: query, listed: listed, cooked: cooked, typed: typed, chat: chat) }.map(\.id))
        }
        let any = try await ids(RecipeDao.listedAny, 0, 0)
        XCTAssertEqual(any, [inList, cooked, typed, sample, chat])
        let notInList = try await ids(RecipeDao.listedNo, 0, 0)
        XCTAssertEqual(notInList, [cooked, typed, sample, chat])
        let cookedIds = try await ids(RecipeDao.listedAny, 1, 0)
        XCTAssertEqual(cookedIds, [cooked])
        let typedIds = try await ids(RecipeDao.listedAny, 0, 1)
        XCTAssertEqual(typedIds, [typed], "never the tour's sample")
        let searched = try await ids(RecipeDao.listedAny, 0, 1, "zzz")
        XCTAssertEqual(searched, [], "the search still applies inside a view")

        let chatIds = try await ids(RecipeDao.listedAny, 0, 0, chat: 1)
        XCTAssertEqual(chatIds, [chat], "a chat's recipe is its own view, not Typed by you (#355)")
        let searchedChat = try await ids(RecipeDao.listedAny, 0, 0, "zzz", chat: 1)
        XCTAssertEqual(searchedChat, [])

        let counts = try await db.libraryCounts()
        XCTAssertEqual(counts, RecipeCounts(all: 5, inList: 1, cooked: 1, typed: 1, chat: 1))
    }
}
