import Combine
import XCTest
@testable import RecipeClipper

/// The repositories over a real in-memory AppDatabase: the import path (with a stub source and
/// a fixed clock), open, delete/undo, the list API, and the publishers re-emitting on writes.
final class DataRepositoryTests: XCTestCase {
    private var db: AppDatabase!
    private var clock: DataTestClock!
    private var source: DataStubSource!
    private var recipes: DefaultRecipeRepository!
    private var lists: DefaultListRepository!
    private var pauses: DataPauseRecorder!
    private var cancellables: Set<AnyCancellable> = []

    override func setUpWithError() throws {
        db = try AppDatabase(path: nil)
        clock = DataTestClock(1_000)
        source = DataStubSource()
        pauses = DataPauseRecorder()
        // The retry pause is recorded, not waited out.
        recipes = DefaultRecipeRepository(db: db, source: source, clock: clock, sleep: pauses.sleep)
        lists = DefaultListRepository(db: db, clock: clock)
    }

    override func tearDown() {
        cancellables = []
        recipes = nil
        lists = nil
        db = nil
    }

    private func importSuccess(_ url: String, title: String = "Soup", at time: Int64) async -> Recipe? {
        clock.time = time
        source.results[url] = .success(dataRecipe(url, title: title))
        guard case .success(let recipe) = await recipes.importFromUrl(url) else { return nil }
        return recipe
    }

    // MARK: - importFromUrl

    func testImportSavesAndReturnsThePersistedRecipe() async throws {
        let recipe = await importSuccess("https://a.com/soup", at: 5_000)

        XCTAssertNotNil(recipe)
        XCTAssertGreaterThan(recipe?.id ?? 0, 0)
        XCTAssertEqual(recipe?.lastViewedAt, 5_000)
        XCTAssertEqual(recipe?.name, "Soup")
        XCTAssertEqual(recipe?.yield, "4")
        let count = try await db.recipeCount()
        XCTAssertEqual(count, 1)
    }

    func testImportFetchesAndSavesUnderTheCleanedLink() async throws {
        let shared = "https://a.com/soup?utm_source=x"
        let cleaned = UrlCleaner.clean(shared)
        source.results[cleaned] = .success(dataRecipe(cleaned))

        let result = await recipes.importFromUrl(shared)

        XCTAssertEqual(source.fetched, [cleaned])
        guard case .success(let recipe) = result else { return XCTFail("\(result)") }
        XCTAssertEqual(recipe.sourceUrl, cleaned)
    }

    func testReImportKeepsTheIdAndTicksAndBumpsLastViewed() async throws {
        let first = await importSuccess("https://a.com/soup", at: 1_000)!
        await recipes.setChecked(id: first.id, checked: [0])

        let second = await importSuccess("https://a.com/soup", title: "Better Soup", at: 9_000)!

        XCTAssertEqual(second.id, first.id)
        XCTAssertEqual(second.name, "Better Soup")
        XCTAssertEqual(second.checkedIngredients, [0])
        XCTAssertEqual(second.lastViewedAt, 9_000)
    }

    func testOfflineFallbackReturnsTheSavedCopyAndTouchesIt() async throws {
        let saved = await importSuccess("https://a.com/soup", at: 1_000)!
        source.results = [:] // the network is gone
        clock.time = 7_777

        let result = await recipes.importFromUrl("https://a.com/soup")

        guard case .success(let recipe) = result else { return XCTFail("\(result)") }
        XCTAssertEqual(recipe.id, saved.id)
        XCTAssertEqual(recipe.name, "Soup")
        XCTAssertEqual(recipe.lastViewedAt, 7_777)
        let stored = try await db.get(saved.id)
        XCTAssertEqual(stored?.lastViewedAt, 7_777, "the touch is persisted")
    }

    func testAFailedFetchOfAnUnseenLinkReturnsTheSourceError() async throws {
        source.results["https://a.com/none"] = .error(.noRecipeFound)
        let result = await recipes.importFromUrl("https://a.com/none")
        XCTAssertEqual(result, .error(.noRecipeFound))
        let count = try await db.recipeCount()
        XCTAssertEqual(count, 0)
    }

    func testImportCullsHistoryButNeverAListedRecipe() async throws {
        let first = await importSuccess("https://a.com/first", at: 1)!
        let favorites = try await db.favoritesId()
        await lists.setMembership(recipeId: first.id, listId: favorites, inList: true)
        let second = await importSuccess("https://a.com/second", at: 2)!
        for n in 0..<historyLimit {
            _ = await importSuccess("https://a.com/\(n)", at: 100 + Int64(n))
        }
        let firstRow = try await db.get(first.id)
        let secondRow = try await db.get(second.id)
        XCTAssertNotNil(firstRow, "listed: never culled")
        XCTAssertNil(secondRow, "the oldest unlisted one is gone")
    }

    // MARK: - importFromUrl: the single automatic retry

    private func scripted(_ results: ParseResult...) -> (DefaultRecipeRepository, DataScriptedSource) {
        let scripted = DataScriptedSource(results)
        return (DefaultRecipeRepository(db: db, source: scripted, clock: clock, sleep: pauses.sleep), scripted)
    }

    func testBlockedThenSuccessRetriesOnceAfterThePauseAndSaves() async throws {
        let url = "https://a.com/soup"
        let (repository, scripted) = scripted(.error(.blocked(httpStatus: 403)), .success(dataRecipe(url)))

        let result = await repository.importFromUrl(url)

        XCTAssertEqual(scripted.fetches, 2)
        XCTAssertEqual(pauses.durations, [DefaultRecipeRepository.retryPause])
        guard case .success(let recipe) = result else { return XCTFail("\(result)") }
        XCTAssertEqual(recipe.name, "Soup")
        let count = try await db.recipeCount()
        XCTAssertEqual(count, 1)
    }

    func testBlockedTwiceGivesBlockedAfterExactlyTwoFetches() async throws {
        let (repository, scripted) = scripted(.error(.blocked(httpStatus: 403)), .error(.blocked(httpStatus: 429)))

        let result = await repository.importFromUrl("https://a.com/soup")

        XCTAssertEqual(scripted.fetches, 2)
        XCTAssertEqual(result, .error(.blocked(httpStatus: 429)))
        let count = try await db.recipeCount()
        XCTAssertEqual(count, 0)
    }

    func testANetworkFailureThenSuccessSaves() async throws {
        let url = "https://a.com/soup"
        let (repository, scripted) = scripted(.error(.fetchFailed("offline")), .success(dataRecipe(url)))

        let result = await repository.importFromUrl(url)

        XCTAssertEqual(scripted.fetches, 2)
        guard case .success = result else { return XCTFail("\(result)") }
        let count = try await db.recipeCount()
        XCTAssertEqual(count, 1)
    }

    func testNoRecipeFoundIsFetchedOnceAndNeverRetried() async throws {
        let url = "https://a.com/story"
        let (repository, scripted) = scripted(.error(.noRecipeFound), .success(dataRecipe(url)))

        let result = await repository.importFromUrl(url)

        XCTAssertEqual(scripted.fetches, 1)
        XCTAssertEqual(pauses.durations, [], "no pause either")
        XCTAssertEqual(result, .error(.noRecipeFound))
    }

    func testOfflineFailsAtOnceWithOneFetchAndNoPause() async {
        let url = "https://a.com/soup"
        let (repository, scripted) = scripted(.error(.offline), .success(dataRecipe(url)))

        let result = await repository.importFromUrl(url)

        XCTAssertEqual(scripted.fetches, 1)
        XCTAssertEqual(pauses.durations, [])
        XCTAssertEqual(result, .error(.offline))
    }

    func testATimeoutIsNotRetriedSoADeadConnectionCostsOneTimeout() async {
        let url = "https://a.com/soup"
        let timeout = ParseError.fetchFailed("The request timed out.", timedOut: true)
        let (repository, scripted) = scripted(.error(timeout), .success(dataRecipe(url)))

        let result = await repository.importFromUrl(url)

        XCTAssertEqual(scripted.fetches, 1)
        XCTAssertEqual(pauses.durations, [])
        XCTAssertEqual(result, .error(timeout))
    }

    func testOfflineForASavedLinkOpensTheSavedCopy() async throws {
        let saved = await importSuccess("https://a.com/soup", at: 1_000)!
        let (repository, scripted) = scripted(.error(.offline))

        let result = await repository.importFromUrl("https://a.com/soup")

        XCTAssertEqual(scripted.fetches, 1)
        guard case .success(let recipe) = result else { return XCTFail("\(result)") }
        XCTAssertEqual(recipe.id, saved.id)
    }

    func testASuccessFirstTimeIsFetchedOnceWithNoPause() async {
        let url = "https://a.com/soup"
        let (repository, scripted) = scripted(.success(dataRecipe(url)))
        _ = await repository.importFromUrl(url)
        XCTAssertEqual(scripted.fetches, 1)
        XCTAssertEqual(pauses.durations, [])
    }

    func testBlockedTwiceForASavedLinkFallsBackToTheCachedCopy() async throws {
        let saved = await importSuccess("https://a.com/soup", at: 1_000)!
        let (repository, scripted) = scripted(.error(.blocked(httpStatus: 403)))
        clock.time = 7_777

        let result = await repository.importFromUrl("https://a.com/soup")

        XCTAssertEqual(scripted.fetches, 2)
        guard case .success(let recipe) = result else { return XCTFail("\(result)") }
        XCTAssertEqual(recipe.id, saved.id)
        XCTAssertEqual(recipe.lastViewedAt, 7_777)
    }

    func testCancellingDuringThePauseWritesNothing() async throws {
        let url = "https://a.com/soup"
        let saved = await importSuccess(url, at: 1_000)!
        clock.time = 9_000
        let scripted = DataScriptedSource([.error(.blocked(httpStatus: 503)), .success(dataRecipe(url, title: "New"))])
        let pauseStarted = expectation(description: "pause started")
        let repository = DefaultRecipeRepository(db: db, source: scripted, clock: clock, sleep: { _ in
            pauseStarted.fulfill()
            try await Task.sleep(for: .seconds(60)) // the real thing, so cancellation interrupts it
        })

        let task = Task { await repository.importFromUrl(url) }
        await fulfillment(of: [pauseStarted], timeout: 2)
        task.cancel()
        _ = await task.value

        XCTAssertEqual(scripted.fetches, 1)
        let stored = try await db.get(saved.id)
        XCTAssertEqual(stored?.title, "Soup")
        XCTAssertEqual(stored?.lastViewedAt, 1_000, "not even a touch")
        let count = try await db.recipeCount()
        XCTAssertEqual(count, 1)
    }

    // MARK: - importFromUrl: the off-screen browser fallback, after the retry

    /// A page as a browser would hand it back once its scripts have run.
    private let renderedRecipePage = """
        <html><head><script type="application/ld+json">
        {"@type":"Recipe","name":"Rendered Soup","recipeIngredient":["1 leek"],"recipeInstructions":["Simmer."]}
        </script></head><body></body></html>
        """
    private let renderedStoryPage = "<html><body><p>A long story, and no recipe.</p></body></html>"

    private func rendering(
        _ rendered: FakeRenderedPageSource,
        _ results: ParseResult...,
        renderTimeout: Duration = DefaultRecipeRepository.renderTimeout
    ) -> (DefaultRecipeRepository, DataScriptedSource) {
        let scripted = DataScriptedSource(results)
        let repository = DefaultRecipeRepository(
            db: db, source: scripted, clock: clock, sleep: pauses.sleep,
            renderedPages: rendered, renderTimeout: renderTimeout
        )
        return (repository, scripted)
    }

    func testBlockedTwiceRendersOnceAfterTheRetryAndSavesWhatItFinds() async throws {
        let url = "https://a.com/soup"
        var fetchesBeforeRender = -1
        var pausesBeforeRender = -1
        var scriptedRef: DataScriptedSource?
        let rendered = FakeRenderedPageSource { [unowned self] _ in
            fetchesBeforeRender = scriptedRef?.fetches ?? -1
            pausesBeforeRender = self.pauses.durations.count
            return self.renderedRecipePage
        }
        let (repository, scripted) = rendering(rendered, .error(.blocked(httpStatus: 403)))
        scriptedRef = scripted

        let result = await repository.importFromUrl(url)

        XCTAssertEqual(rendered.requests, [url])
        XCTAssertEqual(fetchesBeforeRender, 2)
        XCTAssertEqual(pausesBeforeRender, 1)
        guard case .success(let recipe) = result else { return XCTFail("\(result)") }
        XCTAssertEqual(recipe.name, "Rendered Soup")
        XCTAssertEqual(recipe.sourceUrl, url)
        let stored = try await db.get(recipe.id)
        XCTAssertEqual(stored?.title, "Rendered Soup")
    }

    func testNoRecipeFoundRendersOnceWithNoRetryAndNoPause() async {
        let rendered = FakeRenderedPageSource { [unowned self] _ in self.renderedRecipePage }
        let (repository, scripted) = rendering(rendered, .error(.noRecipeFound))

        let result = await repository.importFromUrl("https://a.com/soup")

        XCTAssertEqual(scripted.fetches, 1)
        XCTAssertEqual(pauses.durations, [])
        XCTAssertEqual(rendered.requests.count, 1)
        guard case .success = result else { return XCTFail("\(result)") }
    }

    func testTheTrackingFreeLinkIsWhatGetsRendered() async {
        let rendered = FakeRenderedPageSource()
        let (repository, _) = rendering(rendered, .error(.noRecipeFound))

        _ = await repository.importFromUrl("https://a.com/soup?utm_source=share#jump")

        XCTAssertEqual(rendered.requests, ["https://a.com/soup"])
    }

    func testNeverRenderedForOfflineATimeoutANetworkFailureOrASuccess() async {
        let url = "https://a.com/soup"
        let timeout = ParseError.fetchFailed("The request timed out.", timedOut: true)
        let scripts: [[ParseResult]] = [
            [.error(.offline)],
            [.error(timeout)],
            [.error(.fetchFailed("reset"))],
            [.error(.blocked(httpStatus: 403)), .error(timeout)], // the retry itself timed out
            [.success(dataRecipe(url))],
        ]
        for script in scripts {
            let rendered = FakeRenderedPageSource { [unowned self] _ in self.renderedRecipePage }
            let repository = DefaultRecipeRepository(
                db: db, source: DataScriptedSource(script), clock: clock, sleep: pauses.sleep,
                renderedPages: rendered
            )
            _ = await repository.importFromUrl(url)
            XCTAssertEqual(rendered.requests, [], "\(script)")
        }
    }

    func testARenderedPageWithNoRecipeKeepsTheDirectFetchsCause() async throws {
        let rendered = FakeRenderedPageSource { [unowned self] _ in self.renderedStoryPage }
        let (repository, _) = rendering(rendered, .error(.blocked(httpStatus: 403)), .error(.blocked(httpStatus: 429)))

        let result = await repository.importFromUrl("https://a.com/soup")

        XCTAssertEqual(rendered.requests.count, 1)
        XCTAssertEqual(result, .error(.blocked(httpStatus: 429)))
        let count = try await db.recipeCount()
        XCTAssertEqual(count, 0)
    }

    func testAPageThatFailsToRenderKeepsNoRecipeFound() async {
        let rendered = FakeRenderedPageSource()
        let (repository, _) = rendering(rendered, .error(.noRecipeFound))

        let result = await repository.importFromUrl("https://a.com/soup")

        XCTAssertEqual(rendered.requests.count, 1)
        XCTAssertEqual(result, .error(.noRecipeFound))
    }

    func testARenderThatNeverSettlesIsCutOffAtTheCapKeepingTheCause() async {
        let rendered = FakeRenderedPageSource { _ in
            try? await Task.sleep(for: .seconds(60)) // interrupted by the cap's cancellation
            return Task.isCancelled ? nil : "<html></html>"
        }
        let (repository, _) = rendering(rendered, .error(.noRecipeFound), renderTimeout: .milliseconds(50))

        let started = ContinuousClock.now
        let result = await repository.importFromUrl("https://a.com/soup")

        XCTAssertEqual(result, .error(.noRecipeFound))
        XCTAssertLessThan(ContinuousClock.now - started, .seconds(10))
    }

    func testStillBlockedAfterRenderingASavedLinkOpensTheSavedCopy() async {
        let saved = await importSuccess("https://a.com/soup", at: 1_000)!
        let rendered = FakeRenderedPageSource { [unowned self] _ in self.renderedStoryPage }
        let (repository, _) = rendering(rendered, .error(.blocked(httpStatus: 403)))

        let result = await repository.importFromUrl("https://a.com/soup")

        XCTAssertEqual(rendered.requests.count, 1)
        guard case .success(let recipe) = result else { return XCTFail("\(result)") }
        XCTAssertEqual(recipe.id, saved.id)
        XCTAssertEqual(recipe.name, "Soup")
    }

    func testCancellingDuringTheRenderWritesNothing() async throws {
        let url = "https://a.com/soup"
        let saved = await importSuccess(url, at: 1_000)!
        clock.time = 9_000
        let renderStarted = expectation(description: "render started")
        let rendered = FakeRenderedPageSource { [unowned self] _ in
            renderStarted.fulfill()
            try? await Task.sleep(for: .seconds(60)) // the real thing, so cancellation interrupts it
            return self.renderedRecipePage // as if the page had finished anyway
        }
        let (repository, _) = rendering(rendered, .error(.noRecipeFound))

        let task = Task { await repository.importFromUrl(url) }
        await fulfillment(of: [renderStarted], timeout: 2)
        task.cancel()
        _ = await task.value

        let stored = try await db.get(saved.id)
        XCTAssertEqual(stored?.title, "Soup")
        XCTAssertEqual(stored?.lastViewedAt, 1_000, "not even a touch")
        let count = try await db.recipeCount()
        XCTAssertEqual(count, 1)
    }

    // MARK: - open / delete / restore

    func testOpenTouchesAndReturns() async throws {
        let saved = await importSuccess("https://a.com/soup", at: 1_000)!
        clock.time = 4_242

        let opened = await recipes.open(id: saved.id)

        XCTAssertEqual(opened?.id, saved.id)
        XCTAssertEqual(opened?.lastViewedAt, 4_242)
        let stored = try await db.get(saved.id)
        XCTAssertEqual(stored?.lastViewedAt, 4_242)
    }

    func testOpenOfAMissingRecipeIsNil() async {
        let opened = await recipes.open(id: 999)
        XCTAssertNil(opened)
    }

    func testDeleteThenRestoreBringsBackTheSameIdAndMembership() async throws {
        let saved = await importSuccess("https://a.com/soup", at: 1_000)!
        await recipes.setChecked(id: saved.id, checked: [0])
        let favorites = try await db.favoritesId()
        let lunch = try await db.listId(named: "Lunch")
        clock.time = 2_000
        await lists.setMembership(recipeId: saved.id, listId: favorites, inList: true)
        clock.time = 3_000
        await lists.setMembership(recipeId: saved.id, listId: lunch, inList: true)

        let deleted = await recipes.delete(id: saved.id)
        XCTAssertNotNil(deleted)
        XCTAssertEqual(Set(deleted?.memberships.map(\.listId) ?? []), [favorites, lunch])
        let gone = try await db.get(saved.id)
        XCTAssertNil(gone)
        let refsAfterDelete = try await db.crossRefs(saved.id)
        XCTAssertTrue(refsAfterDelete.isEmpty)

        await recipes.restore(deleted!)

        let restored = try await db.get(saved.id)
        XCTAssertEqual(restored?.title, "Soup")
        XCTAssertEqual(restored?.checkedIngredients, [0])
        XCTAssertEqual(restored?.lastViewedAt, 1_000)
        let refs = try await db.crossRefs(saved.id)
        XCTAssertEqual(Set(refs.map(\.addedAt)), [2_000, 3_000])
    }

    func testDeletingAMissingRecipeIsNil() async {
        let deleted = await recipes.delete(id: 12345)
        XCTAssertNil(deleted)
    }

    // MARK: - ListRepository

    func testCreateListTrimsTheNameAndAddsTheRecipe() async throws {
        let saved = await importSuccess("https://a.com/soup", at: 1_000)!
        clock.time = 5_000

        let listId = await lists.createList(name: "  Weeknights \n", addRecipeId: saved.id)

        let all = await firstValue(lists.observeListsFor(recipeId: saved.id))
        let created = all?.first { $0.id == listId }
        XCTAssertEqual(created?.name, "Weeknights")
        XCTAssertEqual(created?.containsRecipe, true)
        XCTAssertEqual(created?.recipeCount, 1)
        let refs = try await db.crossRefs(saved.id)
        XCTAssertEqual(refs.first?.addedAt, 5_000)
    }

    func testSetMembershipAddsAndRemoves() async throws {
        let saved = await importSuccess("https://a.com/soup", at: 1_000)!
        let favorites = try await db.favoritesId()

        await lists.setMembership(recipeId: saved.id, listId: favorites, inList: true)
        let inList = await firstValue(lists.observeRecipesIn(listId: favorites))
        XCTAssertEqual(inList?.map(\.id), [saved.id])

        await lists.setMembership(recipeId: saved.id, listId: favorites, inList: false)
        let emptied = await firstValue(lists.observeRecipesIn(listId: favorites))
        XCTAssertEqual(emptied, [])
        let history = await firstValue(recipes.observeHistory(query: ""))
        XCTAssertEqual(history?.map(\.id), [saved.id], "out of its last list, still in history")
        XCTAssertEqual(history?.first?.isSaved, false)
    }

    func testRenameAndDeleteThroughTheRepository() async throws {
        let favorites = try await db.favoritesId()
        let listId = await lists.createList(name: "Temp", addRecipeId: nil)
        await lists.rename(listId: listId, name: " Keep ")
        await lists.rename(listId: favorites, name: "Best")
        await lists.deleteList(listId: favorites) // refused

        var all = await firstValue(lists.observeLists()) ?? []
        XCTAssertEqual(all.first { $0.id == listId }?.name, "Keep")
        XCTAssertEqual(all.first { $0.isFavorites }?.name, "Best")

        await lists.deleteList(listId: listId)
        all = await firstValue(lists.observeLists()) ?? []
        XCTAssertFalse(all.contains { $0.id == listId })
        XCTAssertEqual(all.count, 6)
    }

    // MARK: - Observation

    func testObserveEmitsImmediatelyOnMainAndReEmitsAfterAWrite() async throws {
        var emissions: [[RecipeSummary]] = []
        var allOnMain = true
        let initial = expectation(description: "initial")
        let afterWrite = expectation(description: "after write")
        recipes.observeHistory(query: "").sink { value in
            allOnMain = allOnMain && Thread.isMainThread
            emissions.append(value)
            if emissions.count == 1 { initial.fulfill() }
            if emissions.count == 2 { afterWrite.fulfill() }
        }.store(in: &cancellables)

        await fulfillment(of: [initial], timeout: 2)
        XCTAssertEqual(emissions.first, [])

        _ = await importSuccess("https://a.com/soup", at: 1_000)
        await fulfillment(of: [afterWrite], timeout: 2)

        XCTAssertEqual(emissions.last?.map(\.title), ["Soup"])
        XCTAssertTrue(allOnMain)
    }

    func testListObserversReEmitWhenMembershipChanges() async throws {
        let saved = await importSuccess("https://a.com/soup", at: 1_000)!
        let favorites = try await db.favoritesId()
        var latest: [RecipeList] = []
        let checked = expectation(description: "favorites ticked")
        lists.observeListsFor(recipeId: saved.id).sink { value in
            latest = value
            if value.first(where: { $0.id == favorites })?.containsRecipe == true { checked.fulfill() }
        }.store(in: &cancellables)

        await lists.setMembership(recipeId: saved.id, listId: favorites, inList: true)

        await fulfillment(of: [checked], timeout: 2)
        XCTAssertEqual(latest.first { $0.id == favorites }?.recipeCount, 1)
    }

    func testRecentObserverRespectsItsLimitAndSearchFilters() async throws {
        _ = await importSuccess("https://a.com/1", title: "Chicken Adobo", at: 1)
        _ = await importSuccess("https://a.com/2", title: "Beef Stew", at: 2)
        _ = await importSuccess("https://a.com/3", title: "Pancakes", at: 3)

        let recent = await firstValue(recipes.observeRecent(limit: 2))
        XCTAssertEqual(recent?.map(\.title), ["Pancakes", "Beef Stew"])
        let found = await firstValue(recipes.observeHistory(query: "STEW"))
        XCTAssertEqual(found?.map(\.title), ["Beef Stew"])
    }
}
