import XCTest
@testable import RecipeClipper

/// The share extension's card, over fakes and over the real repository on in-memory SQLite.
@MainActor
final class ShareImportViewModelTests: XCTestCase {

    private func recipe(_ name: String = "Guacamole") -> Recipe {
        Recipe(
            name: name, image: nil, ingredients: ["3 avocados"], instructions: ["Mash."],
            prepTime: nil, cookTime: nil, totalTime: nil, yield: "4",
            sourceUrl: "https://example.com/guacamole", id: 7
        )
    }

    private let input = SharedInput(url: "https://example.com/guacamole")

    func testStartsLoadingThenShowsTheSavedTitle() async {
        let repository = ReconnectCountingRepository(.success(recipe()))
        let vm = ShareImportViewModel(repository: repository)
        XCTAssertEqual(vm.uiState, .loading)

        vm.start(with: input)
        await vm.currentLoad?.value

        XCTAssertEqual(vm.uiState, .saved(title: "Guacamole"))
        XCTAssertEqual(repository.importCalls, 1)
    }

    /// Safari's rendered page (#35) rides along with the URL, straight to the repository.
    func testARenderedPageIsPassedThrough() async {
        let repository = ReconnectCountingRepository(.success(recipe()))
        let vm = ShareImportViewModel(repository: repository)

        vm.start(with: SharedInput(url: "https://example.com/guacamole", page: "<html>rendered</html>"))
        await vm.currentLoad?.value

        XCTAssertEqual(vm.uiState, .saved(title: "Guacamole"))
        XCTAssertEqual(repository.importedRenderedPages, ["<html>rendered</html>"])
    }

    func testNothingSharedWithALinkSaysSoWithoutImporting() async {
        let repository = ReconnectCountingRepository(.success(recipe()))
        let vm = ShareImportViewModel(repository: repository)

        vm.start(with: nil)
        await settleMain()

        XCTAssertEqual(vm.uiState, .noLink)
        XCTAssertEqual(repository.importCalls, 0)
    }

    func testNoDatabaseIsASaveFailure() async {
        let vm = ShareImportViewModel(repository: nil)
        vm.start(with: input)
        XCTAssertEqual(vm.uiState, .failed(.saveFailed))
    }

    func testEachCauseReachesTheCardAndTryAgainImportsAgain() async {
        for cause in [ParseError.noRecipeFound, .blocked(httpStatus: 403), .offline, .fetchFailed("DNS")] {
            let repository = ReconnectCountingRepository(.error(cause))
            let vm = ShareImportViewModel(repository: repository)
            vm.start(with: input)
            await vm.currentLoad?.value
            XCTAssertEqual(vm.uiState, .failed(cause))

            repository.importResult = .success(recipe())
            vm.onRetry()
            XCTAssertEqual(vm.uiState, .loading)
            await vm.currentLoad?.value
            XCTAssertEqual(vm.uiState, .saved(title: "Guacamole"), "\(cause)")
            XCTAssertEqual(repository.importCalls, 2)
        }
    }

    // MARK: Reddit's block (#213)

    private let post = "https://www.reddit.com/r/recipes/comments/1abc01/lemon_orzo/"

    private func pendingClips() -> PendingClip {
        let suite = "ShareImportPendingClip-\(UUID().uuidString)"
        return PendingClip(defaults: UserDefaults(suiteName: suite)!)
    }

    func testABlockedRedditPostIsLeftForTheAppToClip() async {
        let pending = pendingClips()
        let clock = DataTestClock(5_000)
        let vm = ShareImportViewModel(
            repository: ReconnectCountingRepository(.error(.blocked(httpStatus: 403))), pendingClip: pending, clock: clock
        )
        vm.start(with: SharedInput(url: post))
        await vm.currentLoad?.value

        XCTAssertEqual(vm.uiState, .clipInApp)
        XCTAssertEqual(pending.take(now: 5_000 + PendingClip.window), post)
    }

    func testOtherBlocksAndCausesKeepTheirCardAndLeaveNothing() async {
        let cases: [(String, ParseError, Bool)] = [
            ("https://example.com/pie", .blocked(httpStatus: 403), true),
            (post, .blocked(httpStatus: 403), false), // the reddit flag off
            (post, .offline, true),
            (post, .fetchFailed("timeout", timedOut: true), true),
            (post, .noTranscription(title: "Pie", imageUrl: nil), true),
        ]
        for (url, cause, redditOn) in cases {
            let pending = pendingClips()
            let vm = ShareImportViewModel(
                repository: ReconnectCountingRepository(.error(cause)), redditOn: { redditOn }, pendingClip: pending
            )
            vm.start(with: SharedInput(url: url))
            await vm.currentLoad?.value

            XCTAssertEqual(vm.uiState, .failed(cause), "\(url) \(cause)")
            XCTAssertNil(pending.take(now: DataTestClock().now()), "\(url) \(cause)")
        }
    }

    func testANewShareClearsAPostLeftBefore() async {
        let pending = pendingClips()
        pending.put(post, at: 1_000)
        let vm = ShareImportViewModel(
            repository: ReconnectCountingRepository(.success(recipe())), pendingClip: pending, clock: DataTestClock(1_000)
        )
        vm.start(with: input)
        await vm.currentLoad?.value

        XCTAssertEqual(vm.uiState, .saved(title: "Guacamole"))
        XCTAssertNil(pending.take(now: 1_000))
    }

    func testThePostLeftOpensOnceAndOnlyWithinTheWindow() {
        let pending = pendingClips()
        pending.put(post, at: 1_000)
        XCTAssertEqual(pending.take(now: 1_000 + PendingClip.window), post)
        XCTAssertNil(pending.take(now: 1_000 + PendingClip.window), "taken once")

        pending.put(post, at: 1_000)
        XCTAssertNil(pending.take(now: 1_001 + PendingClip.window), "too old")
        XCTAssertNil(pending.take(now: 1_000), "an old one is dropped, not kept")
    }

    func testTheCardSaysToOpenTheApp() {
        XCTAssertEqual(
            Strings.shareRedditBlocked,
            "Reddit didn't let the app read this post. Open Recipe Clipper: the post will be open there, to select the recipe."
        )
    }

    func testOfflineReloadsOnceTheConnectionReturns() async {
        let connectivity = ReconnectFakeConnectivity(online: false)
        let repository = ReconnectCountingRepository(.error(.offline))
        let vm = ShareImportViewModel(repository: repository, connectivity: connectivity)
        vm.start(with: input)
        await vm.currentLoad?.value
        await settleMain()

        repository.importResult = .success(recipe())
        connectivity.set(true)
        await settleMain()
        await vm.currentLoad?.value

        XCTAssertEqual(repository.importCalls, 2)
        XCTAssertEqual(vm.uiState, .saved(title: "Guacamole"))
    }

    func testAFailureWhileOnlineDoesNotReloadByItself() async {
        let connectivity = ReconnectFakeConnectivity(online: true)
        let repository = ReconnectCountingRepository(.error(.fetchFailed("reset")))
        let vm = ShareImportViewModel(repository: repository, connectivity: connectivity)
        vm.start(with: input)
        await vm.currentLoad?.value
        await settleMain()

        XCTAssertEqual(repository.importCalls, 1)
        XCTAssertEqual(vm.uiState, .failed(.fetchFailed("reset")))
    }

    /// Cancel (or the card going away) mid-import: the real repository writes nothing.
    func testCancellingMidImportWritesNothing() async throws {
        let db = try AppDatabase(path: nil)
        let url = "https://example.com/guacamole"
        let source = DataScriptedSource([.error(.blocked(httpStatus: 503)), .success(dataRecipe(url, title: "Guacamole"))])
        let pauseStarted = expectation(description: "retry pause started")
        let repository = DefaultRecipeRepository(db: db, source: source, clock: DataTestClock(), sleep: { _ in
            pauseStarted.fulfill()
            try await Task.sleep(for: .seconds(60))
        })
        let vm = ShareImportViewModel(repository: repository)

        vm.start(with: SharedInput(url: url))
        await fulfillment(of: [pauseStarted], timeout: 2)
        vm.onCancel()
        await vm.currentLoad?.value

        XCTAssertEqual(source.fetches, 1)
        XCTAssertEqual(vm.uiState, .loading, "a cancelled import never reports")
        let count = try await db.recipeCount()
        XCTAssertEqual(count, 0)
    }

    /// End to end over the real repository: the link is cleaned before it's saved.
    func testSavesUnderTheCleanedLink() async throws {
        let db = try AppDatabase(path: nil)
        let source = DataStubSource()
        let cleaned = "https://example.com/guacamole"
        source.results[cleaned] = .success(dataRecipe(cleaned, title: "Guacamole"))
        let vm = ShareImportViewModel(repository: DefaultRecipeRepository(db: db, source: source, clock: DataTestClock()))

        vm.start(with: SharedInput(url: "http://Example.com/guacamole?utm_source=share#top"))
        await vm.currentLoad?.value

        XCTAssertEqual(vm.uiState, .saved(title: "Guacamole"))
        XCTAssertEqual(source.fetched, [cleaned])
        let saved = try await db.read { try RecipeDao(db: $0).findByUrl(cleaned) }
        XCTAssertNotNil(saved)
    }
}
