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

    func testOnGoReturnsTheNormalisedUrlAndClearsTheInput() async {
        let vm = HomeViewModel(repository: FakeRecipeRepository())
        await settleMain()

        vm.onUrlChange("example.com/recipe")
        let result = vm.onGo()

        XCTAssertEqual(result, "https://example.com/recipe")
        XCTAssertEqual(vm.uiState.urlInput, "")
        XCTAssertFalse(vm.uiState.urlError)
    }

    func testOnGoReturnsNilAndSetsUrlErrorOnRubbishInput() async {
        let vm = HomeViewModel(repository: FakeRecipeRepository())
        await settleMain()

        vm.onUrlChange("not a url")
        let result = vm.onGo()

        XCTAssertNil(result)
        XCTAssertTrue(vm.uiState.urlError)
        XCTAssertEqual(vm.uiState.urlInput, "not a url")
    }

    func testOnUrlChangeClearsAPreviousError() async {
        let vm = HomeViewModel(repository: FakeRecipeRepository())
        await settleMain()

        vm.onUrlChange("not a url")
        _ = vm.onGo()
        XCTAssertTrue(vm.uiState.urlError)

        vm.onUrlChange("something else")

        XCTAssertFalse(vm.uiState.urlError)
    }
}
