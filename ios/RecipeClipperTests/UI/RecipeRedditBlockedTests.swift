import XCTest
@testable import RecipeClipper

/// Reddit's block (#213; Android's RecipeRedditBlockedTest): an import of a Reddit post that ends
/// blocked, after the repository's one retry and with no saved copy, opens "Clip it yourself" on
/// the post instead of an error screen. Every other outcome keeps its own screen.
@MainActor
final class RecipeRedditBlockedTests: XCTestCase {

    private let post = "https://www.reddit.com/r/recipes/comments/1abc01/lemon_orzo/"
    private let shareLink = "https://www.reddit.com/r/recipes/s/AbCd123"

    private func viewModel(_ url: String, repository: RecipeRepository, flags: FeatureFlags? = nil) -> RecipeViewModel {
        let clock = TestClock()
        return RecipeViewModel(
            recipeId: nil, url: url, repository: repository, preferences: FakeAppPreferences(),
            clock: clock, sleep: clock.sleep, flags: flags
        )
    }

    private func importing(_ url: String, _ error: ParseError, flags: FeatureFlags? = nil) async -> RecipeUiState {
        let repository = FakeRecipeRepository()
        repository.importResult = .error(error)
        let vm = viewModel(url, repository: repository, flags: flags)
        await settleMain()
        return vm.uiState
    }

    func testABlockedRedditPostOpensTheClipInsteadOfAnError() async {
        let state = await importing(post, .blocked(httpStatus: 403))

        XCTAssertEqual(state.clipBlockedPost, post)
        // No error screen shows meanwhile, and nothing on it is offered.
        XCTAssertEqual(state.content, .loading)
        XCTAssertNil(state.clipUrl)
        XCTAssertNil(state.reportSiteUrl)
    }

    func testAShareLinkIsHandedToTheClipAsItIs() async {
        let state = await importing(shareLink, .blocked(httpStatus: 429))
        XCTAssertEqual(state.clipBlockedPost, shareLink)
    }

    /// The real repository: its one retry, then its fallback to the saved copy.
    func testAPostSavedBeforeOpensFromTheSavedCopy() async throws {
        let db = try AppDatabase(path: nil)
        let source = DataStubSource()
        source.results[post] = .success(Recipe(
            name: "Lemon orzo", image: nil, ingredients: ["1 cup orzo"], instructions: ["Cook."],
            prepTime: nil, cookTime: nil, totalTime: nil, yield: "2", sourceUrl: post
        ))
        let repository = DefaultRecipeRepository(db: db, source: source, clock: DataTestClock(), sleep: { _ in })
        guard case .success(let saved) = await repository.importFromUrl(post) else {
            return XCTFail("the first import should save the post")
        }

        source.results[post] = .error(.blocked(httpStatus: 403))
        let vm = viewModel(post, repository: repository)
        await settleMain { vm.uiState.content != .loading }

        XCTAssertEqual(vm.uiState.content.success?.recipe.id, saved.id)
        XCTAssertNil(vm.uiState.clipBlockedPost)
    }

    func testOfflineStillShowsTheOfflineScreen() async {
        let state = await importing(post, .offline)
        XCTAssertEqual(state.content, .error(.offline))
        XCTAssertNil(state.clipBlockedPost)
    }

    func testAFailedOrTimedOutFetchKeepsItsErrorScreen() async {
        for error in [ParseError.fetchFailed("Connection reset"), .fetchFailed("timeout", timedOut: true)] {
            let state = await importing(post, error)
            XCTAssertEqual(state.content, .error(error))
            XCTAssertNil(state.clipBlockedPost)
        }
    }

    func testAPostWithNoRecipeTextStillOffersToReadItsPhoto() async {
        let error = ParseError.noTranscription(title: "Aunt June's cookies", imageUrl: "https://preview.redd.it/front.jpg")
        let state = await importing(post, error)

        XCTAssertEqual(state.content, .error(error))
        XCTAssertEqual(state.photoPost?.url, post)
        XCTAssertNil(state.clipBlockedPost)
    }

    func testAnotherSitesBlockIsStillAnErrorScreen() async {
        let state = await importing("https://example.com/pie", .blocked(httpStatus: 403))
        XCTAssertEqual(state.content, .error(.blocked(httpStatus: 403)))
        XCTAssertNil(state.clipBlockedPost)
    }

    func testWithTheRedditFlagOffABlockedPostIsAnErrorScreen() async {
        let flags = FeatureFlags(store: MemoryFeatureFlagStore(["reddit": false]), isDebug: false)
        let state = await importing(post, .blocked(httpStatus: 403), flags: flags)

        XCTAssertEqual(state.content, .error(.blocked(httpStatus: 403)))
        XCTAssertNil(state.clipBlockedPost)
    }

    func testTheClipOpenedThisWaySaysWhy() {
        let make = { (blocked: Bool) in
            ClipViewModel(url: self.post, repository: FakeRecipeRepository(), drafts: ClipDraftStore(), readBlocked: blocked)
        }
        XCTAssertTrue(make(true).uiState.readBlocked)
        XCTAssertFalse(make(false).uiState.readBlocked)
        XCTAssertEqual(
            Strings.clipRedditBlockedNote,
            "Reddit didn't let the app read this post, so it's open here: select the recipe."
        )
    }
}
