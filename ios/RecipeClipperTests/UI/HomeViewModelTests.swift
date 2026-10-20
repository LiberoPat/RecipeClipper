import XCTest
@testable import RecipeClipper

@MainActor
final class HomeViewModelTests: XCTestCase {

    func testContinueCookingIsTheMostRecentAndRecentIsTheFiveBeforeIt() async {
        let repository = FakeRecipeRepository()
        // Newest first, as the repository's real query would order it.
        let summaries = (1 ... 6).reversed().map { testSummary(Int64($0)) }
        repository.recent.send(summaries)
        let vm = HomeViewModel(repository: repository)
        await settleMain()

        XCTAssertEqual(vm.uiState.continueCooking, summaries[0])
        XCTAssertEqual(vm.uiState.recent, Array(summaries.dropFirst()))
    }

    func testAsksForSixSoHomeNeverShowsMoreThanFiveRecent() async {
        let repository = FakeRecipeRepository()
        repository.recent.send((1 ... 20).reversed().map { testSummary(Int64($0)) })
        let vm = HomeViewModel(repository: repository)
        await settleMain()

        XCTAssertEqual(vm.uiState.recent.count, HomeViewModel.recentCount)
    }

    func testLoadedStaysFalseUntilTheRepositoryAnswers() async {
        let vm = HomeViewModel(repository: FakeRecipeRepository())

        XCTAssertFalse(vm.uiState.loaded)

        await settleMain()

        XCTAssertTrue(vm.uiState.loaded)
    }

    // "Paste a link" (#286): the Recipes screen's alert, opened from Home's tile.

    func testThePasteTileOpensTheAlertEmptyAndGoReturnsTheNormalisedLink() async {
        let vm = HomeViewModel(repository: FakeRecipeRepository())
        await settleMain()
        XCTAssertFalse(vm.uiState.pastingLink)

        vm.onPasteLink()
        XCTAssertTrue(vm.uiState.pastingLink)
        XCTAssertEqual(vm.uiState.linkInput, "")
        XCTAssertFalse(vm.uiState.canOpenLink)

        vm.onLinkChange("example.com/recipe")
        XCTAssertTrue(vm.uiState.canOpenLink)
        XCTAssertEqual(vm.onOpenLink(), "https://example.com/recipe")
        XCTAssertFalse(vm.uiState.pastingLink, "the alert closes")
        XCTAssertEqual(vm.uiState.linkInput, "")
    }

    func testWhatIsNotALinkCannotBeOpenedAndTheAlertStays() async {
        let vm = HomeViewModel(repository: FakeRecipeRepository())
        await settleMain()

        vm.onPasteLink()
        vm.onLinkChange("not a link")

        XCTAssertFalse(vm.uiState.canOpenLink)
        XCTAssertNil(vm.onOpenLink())
        XCTAssertTrue(vm.uiState.pastingLink)
        XCTAssertEqual(vm.uiState.linkInput, "not a link")

        vm.onLinkDismissed()
        XCTAssertFalse(vm.uiState.pastingLink)
    }

    // The Recipes and Lists rows' counts (#286).

    func testTheCountsAreWhatRecipesListsAndHowManyListsThereAre() async {
        let repository = FakeRecipeRepository()
        repository.history.send((1 ... 3).map { testSummary(Int64($0)) })
        let lists = FakeListRepository()
        lists.lists.send((1 ... 6).map {
            RecipeList(id: Int64($0), name: "List \($0)", isBuiltIn: true, isFavorites: $0 == 1, recipeCount: 0)
        })
        let vm = HomeViewModel(repository: repository, lists: lists)
        await settleMain()

        XCTAssertEqual(vm.uiState.recipeCount, 3)
        XCTAssertEqual(vm.uiState.listCount, 6)
    }

    func testWithNoListRepositoryThereIsNoListCount() async {
        let vm = HomeViewModel(repository: FakeRecipeRepository())
        await settleMain()

        XCTAssertEqual(vm.uiState.recipeCount, 0)
        XCTAssertNil(vm.uiState.listCount)
    }
}
