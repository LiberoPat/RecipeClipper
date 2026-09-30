import XCTest
@testable import RecipeClipper

/// The clip view opened for the cook to pass Cloudflare's check (#220; Android's
/// ClipHumanCheckTest): each page it settles on is read through the repository; a recipe opens,
/// the check keeps it waiting, and a page past it with no recipe offers the clip.
@MainActor
final class ClipHumanCheckTests: XCTestCase {
    private let url = "https://recipes.example.test/lemon-drizzle-cake/"
    private var repository: FakeRecipeRepository!

    override func setUp() async throws {
        repository = FakeRecipeRepository()
    }

    private func viewModel(check: Bool = true) -> ClipViewModel {
        ClipViewModel(url: url, repository: repository, drafts: ClipDraftStore(), check: check)
    }

    private func recipe(_ id: Int64) -> Recipe {
        var recipe = Recipe(
            name: "Lemon drizzle cake", image: nil, ingredients: ["4 eggs"], instructions: ["Bake."],
            prepTime: nil, cookTime: nil, totalTime: nil, yield: nil, sourceUrl: url
        )
        recipe.id = id
        return recipe
    }

    private func load(_ vm: ClipViewModel, _ html: String) async {
        vm.onPageLoaded(html)
        await vm.currentCheck?.value
    }

    func testOpenedForTheCheckItWaitsOnIt() {
        XCTAssertEqual(viewModel().uiState.check, .waiting)
        XCTAssertNil(viewModel(check: false).uiState.check)
    }

    func testTheCheckStillShowingKeepsItWaiting() async {
        let vm = viewModel()
        await load(vm, "<html>check</html>")

        XCTAssertEqual(repository.importPageCalls.map(\.url), [url])
        XCTAssertEqual(vm.uiState.check, .waiting)
        XCTAssertNil(vm.uiState.savedRecipeId)
    }

    func testAPageWithARecipeOpensIt() async {
        repository.importPageResults["<html>cake</html>"] = .success(recipe(7))
        let vm = viewModel()
        await load(vm, "<html>check</html>")
        await load(vm, "<html>cake</html>")

        XCTAssertEqual(vm.uiState.savedRecipeId, 7)
    }

    func testPastTheCheckWithNoRecipeItOffersTheClip() async {
        repository.importPageResults["<html>story</html>"] = .error(.noRecipeFound)
        let vm = viewModel()
        await load(vm, "<html>story</html>")

        XCTAssertEqual(vm.uiState.check, .noRecipe)
        // Once clipping, pages are no longer read.
        await load(vm, "<html>other</html>")
        XCTAssertEqual(repository.importPageCalls.count, 1)
    }

    func testAFullLibraryShowsThePromptAndNothingOpens() async {
        repository.importPageResults["<html>cake</html>"] = .notKept(recipe(0))
        let vm = viewModel()
        await load(vm, "<html>cake</html>")

        XCTAssertTrue(vm.uiState.libraryFull)
        XCTAssertNil(vm.uiState.savedRecipeId)
    }

    func testAnOrdinaryClipNeverReadsThePage() async {
        let vm = viewModel(check: false)
        await load(vm, "<html>cake</html>")

        XCTAssertTrue(repository.importPageCalls.isEmpty)
    }
}

/// Cloudflare's check wanting a person (#220; Android's RecipeHumanCheckTest): the import opens
/// the page for the cook to pass it instead of an error screen. Every other outcome keeps its own.
@MainActor
final class RecipeHumanCheckTests: XCTestCase {
    private let url = "https://recipes.example.test/lemon-drizzle-cake/"

    private func importing(_ error: ParseError) async -> RecipeUiState {
        let repository = FakeRecipeRepository()
        repository.importResult = .error(error)
        let clock = TestClock()
        let vm = RecipeViewModel(
            recipeId: nil, url: url, repository: repository, preferences: FakeAppPreferences(),
            clock: clock, sleep: clock.sleep
        )
        await settleMain()
        return vm.uiState
    }

    func testACheckThatWantsAPersonOpensThePageInsteadOfAnError() async {
        let state = await importing(.humanCheck)

        XCTAssertEqual(state.humanCheckPage, url)
        XCTAssertEqual(state.content, .loading)
        XCTAssertNil(state.clipUrl)
        XCTAssertNil(state.clipBlockedPost)
    }

    func testAnOrdinaryBlockOfflineAndATimeoutKeepTheirScreens() async {
        for error in [ParseError.blocked(httpStatus: 403), .offline, .fetchFailed("timeout", timedOut: true)] {
            let state = await importing(error)
            XCTAssertEqual(state.content, .error(error))
            XCTAssertNil(state.humanCheckPage)
        }
    }

    func testAPagePastTheCheckWithNoRecipeOffersTheClipAsBefore() async {
        let state = await importing(.noRecipeFound)

        XCTAssertEqual(state.clipUrl, url)
        XCTAssertNil(state.humanCheckPage)
    }
}
