import XCTest
@testable import RecipeClipper

/// A Cloudflare page from `shared/fixtures/cloudflare`, as the Android tests read it (#220).
func cloudflareFixture(_ name: String) throws -> String {
    final class Anchor {}
    let bundle = Bundle(for: Anchor.self)
    let url = try XCTUnwrap(bundle.url(forResource: name, withExtension: "html", subdirectory: "fixtures/cloudflare"))
    return try String(contentsOf: url, encoding: .utf8)
}

/// #220: Cloudflare's challenge pages, recognised by Cloudflare's own markers (Android's
/// CloudflareChallengeTest).
final class CloudflareChallengeTests: XCTestCase {
    func testTheManagedChallengeAsFetchedIsAChallenge() throws {
        XCTAssertTrue(CloudflareChallenge.isChallengePage(try cloudflareFixture("managed-challenge")))
    }

    func testTheVerifyYouAreHumanBoxIsAChallenge() throws {
        XCTAssertTrue(CloudflareChallenge.isChallengePage(try cloudflareFixture("interactive-turnstile")))
    }

    func testTheOlderJavaScriptChallengeIsAChallenge() throws {
        XCTAssertTrue(CloudflareChallenge.isChallengePage(try cloudflareFixture("legacy-js-challenge")))
    }

    func testAnOrdinaryPageWithCloudflaresDetectionScriptAndATurnstileFormIsNot() throws {
        XCTAssertFalse(CloudflareChallenge.isChallengePage(try cloudflareFixture("recipe-with-jsd")))
    }

    func testTheFirewallBlockIsNotAChallenge() throws {
        XCTAssertFalse(CloudflareChallenge.isChallengePage(try cloudflareFixture("blocked-1020")))
    }

    func testAJustAMomentTitleNeedsCloudflareOnThePage() {
        XCTAssertFalse(CloudflareChallenge.isChallengePage("<html><head><title>Just a moment...</title></head><body>Loading</body></html>"))
        XCTAssertTrue(CloudflareChallenge.isChallengePage("<html><head><title>Just a moment…</title></head><body>by Cloudflare</body></html>"))
    }

    func testTheCfMitigatedHeaderMarksAChallengeWhateverTheBody() {
        XCTAssertTrue(CloudflareChallenge.isChallengeResponse(status: 403, cfMitigated: "challenge", body: nil))
        XCTAssertTrue(CloudflareChallenge.isChallengeResponse(status: 403, cfMitigated: " Challenge ", body: ""))
        XCTAssertFalse(CloudflareChallenge.isChallengeResponse(status: 403, cfMitigated: "block", body: nil))
    }

    func testARefusalWhoseBodyIsTheChallengePageIsAChallenge() throws {
        XCTAssertTrue(CloudflareChallenge.isChallengeResponse(status: 403, cfMitigated: nil, body: try cloudflareFixture("managed-challenge")))
        XCTAssertTrue(CloudflareChallenge.isChallengeResponse(status: 503, cfMitigated: nil, body: try cloudflareFixture("legacy-js-challenge")))
    }

    func testOtherRefusalsAreNot() throws {
        XCTAssertFalse(CloudflareChallenge.isChallengeResponse(status: 403, cfMitigated: nil, body: try cloudflareFixture("blocked-1020")))
        XCTAssertFalse(CloudflareChallenge.isChallengeResponse(status: 403, cfMitigated: nil, body: "Forbidden"))
        XCTAssertFalse(CloudflareChallenge.isChallengeResponse(status: 404, cfMitigated: nil, body: try cloudflareFixture("managed-challenge")))
        XCTAssertFalse(CloudflareChallenge.isChallengeResponse(status: 403, cfMitigated: nil, body: nil))
    }
}

/// Cloudflare's check in `DefaultRecipeRepository` (#220; Android's
/// DefaultRecipeRepositoryCloudflareTest): a challenged plain fetch goes straight to the browser,
/// a check that passes by itself gets a longer cap, one that wants a person is `.humanCheck`, and
/// a host that passed is rendered first next time. Real time, with short caps.
final class RepositoryCloudflareTests: XCTestCase {
    private var db: AppDatabase!
    private var pauses: DataPauseRecorder!
    private let url = "https://recipes.example.test/lemon-drizzle-cake/"
    private let host = "recipes.example.test"
    private let storyPage = "<html><body><p>A long story, and no recipe.</p></body></html>"

    override func setUpWithError() throws {
        db = try AppDatabase(path: nil)
        pauses = DataPauseRecorder()
    }

    override func tearDown() { db = nil }

    /// Refuses every fetch with a 403, marked as Cloudflare's check when `challenge`.
    private final class Refusing: RecipeSource {
        let challenge: Bool
        private(set) var fetches = 0
        init(challenge: Bool) { self.challenge = challenge }
        func fetch(url: String) async -> ParseResult { await fetchPage(url: url).result }
        func fetchPage(url: String) async -> FetchedPage {
            fetches += 1
            return FetchedPage(result: .error(.blocked(httpStatus: 403)), challenge: challenge)
        }
    }

    private func repository(
        _ source: RecipeSource, _ rendered: FakeRenderedPageSource, hosts: FakeClearedHosts = FakeClearedHosts(),
        renderTimeout: Duration = .milliseconds(300), challengeTimeout: Duration = .seconds(2)
    ) -> DefaultRecipeRepository {
        DefaultRecipeRepository(
            db: db, source: source, clock: DataTestClock(), sleep: pauses.sleep, renderedPages: rendered,
            renderTimeout: renderTimeout, challengeTimeout: challengeTimeout, clearedHosts: hosts
        )
    }

    /// Cloudflare's check for `duration`, then whatever the render answers.
    private func checkFor(_ duration: Duration) -> (@escaping () -> Void) async -> Void {
        { onChallenge in
            onChallenge()
            try? await Task.sleep(for: duration)
        }
    }

    private func recipePage() throws -> String { try cloudflareFixture("recipe-with-jsd") }

    func testAChallengedPlainFetchSkipsTheRetryAndRendersAtOnce() async throws {
        let page = try recipePage()
        let source = Refusing(challenge: true)
        let result = await repository(source, FakeRenderedPageSource { _ in page }).importFromUrl(url)

        XCTAssertEqual(source.fetches, 1)
        XCTAssertEqual(pauses.durations, [])
        guard case .success(let recipe) = result else { return XCTFail("\(result)") }
        XCTAssertEqual(recipe.name, "Lemon drizzle cake")
    }

    func testAnOrdinaryBlockIsStillRetriedOnceBeforeTheRender() async throws {
        let page = try recipePage()
        let source = Refusing(challenge: false)
        let result = await repository(source, FakeRenderedPageSource { _ in page }).importFromUrl(url)

        XCTAssertEqual(source.fetches, 2)
        guard case .success = result else { return XCTFail("\(result)") }
    }

    func testACheckThatPassesByItselfGetsPastTheRenderCapAndTheHostIsRemembered() async throws {
        let page = try recipePage()
        let hosts = FakeClearedHosts()
        let rendered = FakeRenderedPageSource(before: checkFor(.milliseconds(700))) { _ in page }

        let result = await repository(Refusing(challenge: true), rendered, hosts: hosts).importFromUrl(url)

        guard case .success(let recipe) = result else { return XCTFail("\(result)") }
        XCTAssertEqual(recipe.name, "Lemon drizzle cake")
        let stored = try await db.get(recipe.id)
        XCTAssertEqual(stored?.title, "Lemon drizzle cake")
        XCTAssertEqual(hosts.recorded, [host])
    }

    func testACheckStillShowingAtTheLongerCapWantsAPersonAndNothingIsSaved() async throws {
        let hosts = FakeClearedHosts()
        let rendered = FakeRenderedPageSource(before: checkFor(.seconds(60)))

        let started = ContinuousClock.now
        let result = await repository(Refusing(challenge: true), rendered, hosts: hosts, challengeTimeout: .milliseconds(600))
            .importFromUrl(url)

        XCTAssertEqual(result, .error(.humanCheck))
        XCTAssertGreaterThanOrEqual(ContinuousClock.now - started, .milliseconds(600))
        XCTAssertLessThan(ContinuousClock.now - started, .seconds(10))
        XCTAssertEqual(hosts.recorded, [])
        let rows = try await db.recipeCount()
        XCTAssertEqual(rows, 0)
    }

    func testARenderWithNoCheckIsStillCutOffAtTheRenderCapAndKeepsTheBlock() async {
        let rendered = FakeRenderedPageSource { _ in
            try? await Task.sleep(for: .seconds(60))
            return nil
        }
        let started = ContinuousClock.now
        let result = await repository(Refusing(challenge: true), rendered, challengeTimeout: .seconds(30)).importFromUrl(url)

        XCTAssertEqual(result, .error(.blocked(httpStatus: 403)))
        XCTAssertLessThan(ContinuousClock.now - started, .seconds(10))
    }

    func testABrowserThatHandsBackTheCheckItselfHasntPassedItEither() async throws {
        let check = try cloudflareFixture("interactive-turnstile")
        let result = await repository(Refusing(challenge: true), FakeRenderedPageSource { _ in check }).importFromUrl(url)
        XCTAssertEqual(result, .error(.humanCheck))
    }

    func testPastTheCheckWithNoRecipeDataIsThePagesOwnNoRecipeFoundNotTheBlock() async {
        let hosts = FakeClearedHosts()
        let story = storyPage
        let rendered = FakeRenderedPageSource(before: checkFor(.milliseconds(50))) { _ in story }

        let result = await repository(Refusing(challenge: true), rendered, hosts: hosts).importFromUrl(url)

        XCTAssertEqual(result, .error(.noRecipeFound))
        XCTAssertEqual(hosts.recorded, [host])
    }

    func testAPageSavedBeforeOpensFromTheSavedCopyInsteadOfTheCheck() async throws {
        let stub = DataStubSource()
        stub.results[url] = .success(dataRecipe(url, title: "Saved cake"))
        _ = await repository(stub, FakeRenderedPageSource()).importFromUrl(url)

        let rendered = FakeRenderedPageSource(before: checkFor(.seconds(60)))
        let result = await repository(Refusing(challenge: true), rendered, challengeTimeout: .milliseconds(400))
            .importFromUrl(url)

        guard case .success(let recipe) = result else { return XCTFail("\(result)") }
        XCTAssertEqual(recipe.name, "Saved cake")
    }

    func testAHostThatPassedLatelyIsRenderedFirstWithoutAPlainFetch() async throws {
        let page = try recipePage()
        let source = Refusing(challenge: true)
        let hosts = FakeClearedHosts(host)

        let result = await repository(source, FakeRenderedPageSource { _ in page }, hosts: hosts).importFromUrl(url)

        guard case .success = result else { return XCTFail("\(result)") }
        XCTAssertEqual(source.fetches, 0)
        XCTAssertEqual(hosts.recorded, [host])
    }

    func testAClearedHostWhoseRenderDoesntLoadFallsBackToThePlainFetchRenderingOnce() async {
        let source = Refusing(challenge: true)
        let rendered = FakeRenderedPageSource()

        let result = await repository(source, rendered, hosts: FakeClearedHosts(host)).importFromUrl(url)

        XCTAssertEqual(result, .error(.blocked(httpStatus: 403)))
        XCTAssertEqual(source.fetches, 1)
        XCTAssertEqual(rendered.requests.count, 1)
    }

    // MARK: importPage: the page the cook got past the check in the visible browser

    func testImportPageOnTheCheckItselfIsHumanCheckAndRemembersNothing() async throws {
        let hosts = FakeClearedHosts()
        let result = await repository(Refusing(challenge: true), FakeRenderedPageSource(), hosts: hosts)
            .importPage(url, html: try cloudflareFixture("interactive-turnstile"))

        XCTAssertEqual(result, .error(.humanCheck))
        XCTAssertEqual(hosts.recorded, [])
    }

    func testImportPagePastTheCheckSavesTheRecipeLikeAnImportAndRemembersTheHost() async throws {
        let hosts = FakeClearedHosts()
        let result = await repository(Refusing(challenge: true), FakeRenderedPageSource(), hosts: hosts)
            .importPage(url + "?utm_source=share", html: try recipePage())

        guard case .success(let recipe) = result else { return XCTFail("\(result)") }
        XCTAssertEqual(recipe.name, "Lemon drizzle cake")
        XCTAssertEqual(recipe.sourceUrl, url)
        let stored = try await db.get(recipe.id)
        XCTAssertEqual(stored?.sourceUrl, url)
        XCTAssertEqual(hosts.recorded, [host])
    }

    func testImportPagePastTheCheckWithNoRecipeDataIsNoRecipeFound() async {
        let hosts = FakeClearedHosts()
        let result = await repository(Refusing(challenge: true), FakeRenderedPageSource(), hosts: hosts)
            .importPage(url, html: storyPage)

        XCTAssertEqual(result, .error(.noRecipeFound))
        XCTAssertEqual(hosts.recorded, [host])
    }
}

/// The hosts that passed Cloudflare's check (#220), kept for a day in a file in Caches.
final class FileClearedHostsTests: XCTestCase {
    private var folder: URL!

    override func setUpWithError() throws {
        folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws { try FileManager.default.removeItem(at: folder) }

    func testARecordedHostIsClearedForADayThenNot() {
        let clock = DataTestClock(1_000)
        let hosts = FileClearedHosts(clock: clock, folder: folder)
        XCTAssertFalse(hosts.isCleared("recipes.example.test"))
        hosts.record("Recipes.Example.Test")
        XCTAssertTrue(hosts.isCleared("recipes.example.test"))

        clock.time += clearedHostsTTL - 1
        XCTAssertTrue(hosts.isCleared("recipes.example.test"))
        clock.time += 1
        XCTAssertFalse(hosts.isCleared("recipes.example.test"))
    }

    func testItSurvivesANewInstanceAndExpiredHostsAreDroppedOnTheNextRecord() throws {
        let clock = DataTestClock(1_000)
        FileClearedHosts(clock: clock, folder: folder).record("old.example.test")
        clock.time += clearedHostsTTL
        FileClearedHosts(clock: clock, folder: folder).record("new.example.test")

        let data = try Data(contentsOf: folder.appendingPathComponent("cloudflare-clearances.json"))
        let stored = try JSONDecoder().decode([String: Int64].self, from: data)
        XCTAssertEqual(Set(stored.keys), ["new.example.test"])
        XCTAssertTrue(FileClearedHosts(clock: clock, folder: folder).isCleared("new.example.test"))
    }
}
