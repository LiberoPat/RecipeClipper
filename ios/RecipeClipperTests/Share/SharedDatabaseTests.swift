import XCTest
@testable import RecipeClipper

/// The app and the share extension open one SQLite file from two processes, through the App
/// Group container.
final class SharedDatabaseTests: XCTestCase {

    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    /// The build is entitled to the group, so the database lands in its container. Simulator
    /// builds get this without a development team; a device build needs the group registered
    /// (docs/release.md).
    func testTheAppGroupContainerIsAvailable() throws {
        let container = try XCTUnwrap(AppGroup.containerURL, "App Group entitlement missing from the app")
        XCTAssertEqual(AppDatabase.sharedPath(), container.appendingPathComponent("recipe_clipper.sqlite").path)
        XCTAssertEqual(AppDatabase.defaultPath(), AppDatabase.sharedPath())
    }

    /// Two connections creating the same new file at once (the app and the extension opening
    /// it together): the second must not rerun version 1 and fail on "table already exists".
    func testTwoOpensOfANewFileAtOnceBothSucceedAndSeedOnce() async throws {
        let path = directory.appendingPathComponent("recipe_clipper.sqlite").path
        async let first = Task.detached { try AppDatabase(path: path) }.value
        async let second = Task.detached { try AppDatabase(path: path) }.value
        let (a, b) = try await (first, second)

        let listsA = try await a.read { try $0.queryOne("SELECT COUNT(*) FROM lists") { $0.int(0) } }
        let listsB = try await b.read { try $0.queryOne("SELECT COUNT(*) FROM lists") { $0.int(0) } }
        XCTAssertEqual(listsA, AppDatabase.builtInLists.count)
        XCTAssertEqual(listsB, AppDatabase.builtInLists.count)
    }

    /// What one connection (the extension) commits, another already open (the app) reads, and
    /// `refreshObservers` makes the app's open lists re-query to show it.
    func testAWriteFromAnotherConnectionReachesObserversOnRefresh() async throws {
        let path = directory.appendingPathComponent("recipe_clipper.sqlite").path
        let app = try AppDatabase(path: path)
        let appRepository = DefaultRecipeRepository(db: app, source: DataStubSource(), clock: DataTestClock())
        var latest: [RecipeSummary] = []
        let firstEmission = expectation(description: "first")
        let refreshed = expectation(description: "refreshed")
        refreshed.assertForOverFulfill = false
        let subscription = appRepository.observeRecent(limit: 5).sink { value in
            latest = value
            if value.isEmpty { firstEmission.fulfill() } else { refreshed.fulfill() }
        }
        await fulfillment(of: [firstEmission], timeout: 2)

        let extensionDb = try AppDatabase(path: path)
        let source = DataStubSource()
        source.results["https://example.com/soup"] = .success(dataRecipe("https://example.com/soup", title: "Shared Soup"))
        let result = await DefaultRecipeRepository(db: extensionDb, source: source, clock: DataTestClock())
            .importFromUrl("https://example.com/soup")
        guard case .success = result else { return XCTFail("import failed: \(result)") }

        app.refreshObservers()
        await fulfillment(of: [refreshed], timeout: 2)
        XCTAssertEqual(latest.map(\.title), ["Shared Soup"])
        subscription.cancel()
    }
}
