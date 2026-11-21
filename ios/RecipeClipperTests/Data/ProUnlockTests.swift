import XCTest
@testable import RecipeClipper

/// Pro (#413; Android's `ProUnlockLimitTest`): with `proUnlock` on the free tier's limit is gone on every
/// way in, even with `freeTier` on and nothing bought; with it off the 50 of #345 comes back exactly.
/// Each way in reads the limit the way the app and the share extension do, from the App Group mirror the
/// real `LibraryPolicy` keeps (flags, a locked store), over a real in-memory database holding 50.
@MainActor
final class ProUnlockTests: XCTestCase {
    private var db: AppDatabase!
    private var defaults: UserDefaults!
    private var suite = ""
    private var flags: FeatureFlags!
    private var policy: LibraryPolicy!
    private var mirror: DefaultsLibraryLimit!

    override func setUpWithError() throws {
        db = try AppDatabase(path: nil)
        suite = "ProUnlockTests-\(UUID().uuidString)"
        defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        mirror = DefaultsLibraryLimit(defaults: defaults)
        // The free tier on and locked: before #413, the 51st recipe would not be saved.
        flags = FeatureFlags(store: MemoryFeatureFlagStore(["freeTier": true]), isDebug: false)
        policy = LibraryPolicy(flags: flags, entitlements: FakeEntitlements(), mirror: mirror)
        policy.startMirroring()
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        db = nil
    }

    /// Flips the flag and lets the policy write the mirror again, as it does in the app.
    private func proUnlock(_ on: Bool) async {
        flags.set(.proUnlock, on)
        await settleMain()
        XCTAssertEqual(mirror.current(), on ? .unlimited : .capped(max: 50))
    }

    private func repository(_ source: RecipeSource = DataStubSource()) -> DefaultRecipeRepository {
        DefaultRecipeRepository(db: db, source: source, clock: DataTestClock(5_000), sleep: { _ in }, library: mirror)
    }

    @discardableResult
    private func fill(_ n: Int = 50, in target: AppDatabase? = nil, prefix: String = "old") async throws -> [Int64] {
        var ids: [Int64] = []
        for i in 0..<n {
            let row = dataRecipeRecord("https://\(prefix).com/\(i)", viewedAt: Int64(i))
            ids.append(try await (target ?? db).write { try RecipeDao(db: $0).upsert(row, limit: .unlimited) })
        }
        return ids
    }

    private func count() async throws -> Int { try await db.read { try RecipeDao(db: $0).count() } }

    private func stub(_ urls: String...) -> DataStubSource {
        let source = DataStubSource()
        for url in urls { source.results[url] = .success(dataRecipe(url, title: "Soup")) }
        return source
    }

    func testWhichLimitApplies() {
        for freeTier in [false, true] {
            for unlocked in [false, true] {
                XCTAssertEqual(LibraryLimit.of(freeTier: freeTier, unlocked: unlocked, oneCookbook: true, proUnlock: true), .unlimited)
                // With oneCookbook off, its own old clean-up returns, as with the free tier off: never the free 20.
                XCTAssertEqual(LibraryLimit.of(freeTier: freeTier, unlocked: unlocked, oneCookbook: false, proUnlock: true), .history(keep: 50))
            }
        }
        XCTAssertEqual(LibraryLimit.of(freeTier: true, unlocked: false, oneCookbook: true, proUnlock: false), .capped(max: 50))
        XCTAssertEqual(LibraryLimit.of(freeTier: true, unlocked: false, oneCookbook: false, proUnlock: false), .free(max: 20))
    }

    func testTheFlagIsOnByDefaultAndTheShareExtensionsMirrorSaysUnlimited() async {
        XCTAssertTrue(flags.isOn(.proUnlock))
        XCTAssertEqual(policy.limit, .unlimited)
        XCTAssertEqual(mirror.current(), .unlimited)
        await proUnlock(false)
        XCTAssertEqual(policy.limit, .capped(max: 50))
    }

    func testASharedLinkPastFiftyIsSavedAndWithTheFlagOffShownUnsaved() async throws {
        try await fill()
        let repo = repository(stub("https://a.com/soup", "https://a.com/stew"))
        guard case .success = await repo.importFromUrl("https://a.com/soup") else { return XCTFail("expected saved") }
        let total = try await count()
        XCTAssertEqual(total, 51)
        await proUnlock(false)
        guard case .notKept = await repo.importFromUrl("https://a.com/stew") else { return XCTFail("expected notKept") }
    }

    func testAPagePassedThroughCloudflaresCheckAndAClipPastFiftyAreSaved() async throws {
        try await fill()
        let html = """
        <html><script type="application/ld+json">
        {"@type":"Recipe","name":"Soup","recipeIngredient":["1 leek"],"recipeInstructions":["Simmer."]}
        </script></html>
        """
        guard case .success = await repository().importPage("https://a.com/soup", html: html) else { return XCTFail("page") }
        guard case .success = await repository().saveClip(dataRecipe("https://a.com/clip", title: "Clip")) else { return XCTFail("clip") }
        await proUnlock(false)
        guard case .notKept = await repository().saveClip(dataRecipe("https://a.com/clip2", title: "Clip 2")) else {
            return XCTFail("expected notKept with the flag off")
        }
    }

    func testTypedScannedPhotoReadAndSharedTextRecipesPastFiftyAreSaved() async throws {
        try await fill()
        let repo = repository()
        let typed = await repo.addManual(draft: RecipeDraft(name: "Toast", ingredientsText: "bread"))
        XCTAssertGreaterThan(typed?.id ?? 0, 0)
        let scanned = await repo.addManual(draft: RecipeDraft(name: "Gnocchi", ingredientsText: "500 g Kartoffeln"), language: "de")
        XCTAssertGreaterThan(scanned?.id ?? 0, 0)
        await proUnlock(false)
        let text = await repo.addManual(draft: RecipeDraft(name: "Pancakes", ingredientsText: "2 eggs"), language: "en", notes: "x")
        XCTAssertEqual(text?.id, 0)
    }

    func testTheShareExtensionsRepositoryOverTheMirrorSavesPastFifty() async throws {
        try await fill()
        // The extension builds its own repository over the same suite, in its own process.
        let extensionRepository = DefaultRecipeRepository(
            db: db, source: stub("https://a.com/soup"), clock: DataTestClock(5_000), sleep: { _ in },
            library: DefaultsLibraryLimit(defaults: defaults)
        )
        guard case .success = await extensionRepository.importFromUrl("https://a.com/soup") else {
            return XCTFail("the extension should save it")
        }
    }

    func testABackupImportsEverythingPastFiftyAndStopsAtFiftyWithTheFlagOff() async throws {
        let from = try AppDatabase(path: nil)
        try await fill(10, in: from, prefix: "from")
        guard case .success(let exported) = await DefaultBackupRepository(db: from, clock: DataTestClock(9)).export() else {
            return XCTFail("export failed")
        }
        try await fill(47, prefix: "here")
        let importer = DefaultBackupRepository(db: db, clock: DataTestClock(9), library: mirror)

        await proUnlock(false)
        guard case .success(let capped) = await importer.importBackup(BackupPackage(json: exported.json)) else { return XCTFail("import") }
        XCTAssertEqual(capped.recipesAdded, 3)
        XCTAssertEqual(capped.recipesSkipped, 7)

        await proUnlock(true)
        guard case .success(let all) = await importer.importBackup(BackupPackage(json: exported.json)) else { return XCTFail("import") }
        XCTAssertEqual(all.recipesAdded, 7)
        XCTAssertEqual(all.recipesSkipped, 0)
        let total = try await count()
        XCTAssertEqual(total, 57)
    }

    func testASentFileComesInPastFiftyAndNotWithTheFlagOff() async throws {
        try await fill()
        let file = try decodeOrFail(shareFixture())
        let repo = DefaultShareFileRepository(db: db, clock: DataTestClock(9), library: mirror)
        let choice = ShareChoice(recipeIds: ["r-chicken"], groceryIds: [])

        await proUnlock(false)
        guard case .success(let refused) = await repo.receive(file, choice: choice) else { return XCTFail("receive") }
        XCTAssertEqual(refused.recipesSkipped, 1)

        await proUnlock(true)
        guard case .success(let added) = await repo.receive(file, choice: choice) else { return XCTFail("receive") }
        XCTAssertEqual(added.recipesAdded, 1)
    }

    func testUndoRestoresPastFiftyAndRefusesWithTheFlagOff() async throws {
        let ids = try await fill()
        let repo = repository()
        let gone = await repo.delete(id: ids[0])!
        _ = try await db.write { try RecipeDao(db: $0).upsert(dataRecipeRecord("https://a.com/new", viewedAt: 9_000), limit: .unlimited) }
        let restored = await repo.restore(gone)
        XCTAssertEqual(restored, .restored)
        var total = try await count()
        XCTAssertEqual(total, 51)

        await proUnlock(false)
        let again = await repo.delete(id: ids[1])!
        _ = try await db.write { try RecipeDao(db: $0).upsert(dataRecipeRecord("https://a.com/other", viewedAt: 9_001), limit: .unlimited) }
        let refused = await repo.restore(again)
        XCTAssertEqual(refused, .libraryFull)
        total = try await count()
        XCTAssertEqual(total, 51)
    }
}
