import XCTest
@testable import RecipeClipper

@MainActor
final class RecipesViewModelTests: XCTestCase {

    private func deleted(_ id: Int64, _ title: String) -> DeletedRecipe {
        DeletedRecipe(
            recipe: Recipe(
                name: title, image: nil, ingredients: ["1 egg"], instructions: ["Cook."],
                prepTime: nil, cookTime: nil, totalTime: nil, yield: nil,
                sourceUrl: "https://example.com/\(id)", id: id
            ),
            memberships: []
        )
    }

    func testRapidQueryChangesAreDebouncedIntoOneRepositoryQuery() async {
        let repository = FakeRecipeRepository()
        let vm = RecipesViewModel(repository: repository, sleep: immediateSleep)

        vm.onQueryChange("a")
        vm.onQueryChange("ab")
        vm.onQueryChange("abc")
        await settleMain()

        XCTAssertEqual(repository.historyQueries, ["abc"])
        XCTAssertEqual(vm.uiState.query, "abc")
    }

    func testTheDebounceReallyWaits250ms() async {
        let repository = FakeRecipeRepository()
        let clock = TestClock()
        let vm = RecipesViewModel(repository: repository, sleep: clock.sleep)
        await clock.advance(by: 250)
        XCTAssertEqual(repository.historyQueries, [""])

        vm.onQueryChange("soup")
        await clock.advance(by: 249)
        XCTAssertEqual(repository.historyQueries, [""])

        await clock.advance(by: 1)
        XCTAssertEqual(repository.historyQueries, ["", "soup"])
    }

    func testTheSameQueryAgainDoesNotResubscribe() async {
        let repository = FakeRecipeRepository()
        let vm = RecipesViewModel(repository: repository, sleep: immediateSleep)
        await settleMain()

        vm.onQueryChange("soup")
        await settleMain()
        vm.onQueryChange("soup")
        await settleMain()

        XCTAssertEqual(repository.historyQueries, ["", "soup"])
    }

    func testRecipesStaysNilUntilTheRepositoryAnswers() async {
        let repository = FakeRecipeRepository()
        repository.history.send([testSummary(1)])
        let vm = RecipesViewModel(repository: repository, sleep: immediateSleep)

        // Still "loading", not "nothing matched".
        XCTAssertNil(vm.uiState.recipes)

        await settleMain()

        XCTAssertEqual(vm.uiState.recipes, [testSummary(1)])
    }

    func testAnEmptyAnswerIsEmptyNotNil() async {
        let vm = RecipesViewModel(repository: FakeRecipeRepository(), sleep: immediateSleep)
        await settleMain()

        XCTAssertEqual(vm.uiState.recipes, [])
    }

    func testDeletingARecipeCapturesItAndSetsPendingDeletes() async {
        let repository = FakeRecipeRepository()
        repository.deleteResults[1] = deleted(1, "Chicken Adobo")
        let vm = RecipesViewModel(repository: repository, sleep: immediateSleep)
        await settleMain()

        vm.onDelete(testSummary(1, title: "Chicken Adobo"))
        await settleMain()

        XCTAssertEqual(vm.uiState.pendingDeletes, ["Chicken Adobo"])
        XCTAssertEqual(repository.deleteCalls, [1])
    }

    func testARecipeAlreadyGoneIsNotPending() async {
        let repository = FakeRecipeRepository()
        let vm = RecipesViewModel(repository: repository, sleep: immediateSleep)
        await settleMain()

        vm.onDelete(testSummary(1))
        await settleMain()

        XCTAssertEqual(vm.uiState.pendingDeletes, [])
    }

    func testUndoRestoresThroughTheRepositoryAndClearsPendingDeletes() async {
        let repository = FakeRecipeRepository()
        let removed = deleted(1, "Chicken Adobo")
        repository.deleteResults[1] = removed
        let vm = RecipesViewModel(repository: repository, sleep: immediateSleep)
        await settleMain()
        vm.onDelete(testSummary(1, title: "Chicken Adobo"))
        await settleMain()

        vm.onUndoDelete()
        await settleMain()

        XCTAssertEqual(vm.uiState.pendingDeletes, [])
        XCTAssertEqual(repository.restoreCalls, [removed])
    }

    func testDismissingTheSnackbarClearsPendingDeletesWithoutRestoring() async {
        let repository = FakeRecipeRepository()
        repository.deleteResults[1] = deleted(1, "Chicken Adobo")
        let vm = RecipesViewModel(repository: repository, sleep: immediateSleep)
        await settleMain()
        vm.onDelete(testSummary(1, title: "Chicken Adobo"))
        await settleMain()

        vm.onSnackbarDismissed()
        vm.onUndoDelete() // too late: nothing left to restore
        await settleMain()

        XCTAssertEqual(vm.uiState.pendingDeletes, [])
        XCTAssertTrue(repository.restoreCalls.isEmpty)
    }

    func testTwoDeletesInARowDoNotClobberEachOtherAndBothAreRestoredOldestFirstByOneUndo() async {
        let repository = FakeRecipeRepository()
        let a = deleted(1, "A")
        let b = deleted(2, "B")
        repository.deleteResults[1] = a
        repository.deleteResults[2] = b
        let vm = RecipesViewModel(repository: repository, sleep: immediateSleep)
        await settleMain()

        vm.onDelete(testSummary(1, title: "A"))
        await settleMain()
        vm.onDelete(testSummary(2, title: "B"))
        await settleMain()

        // The second swipe, within the same snackbar window, must not strand the first.
        XCTAssertEqual(vm.uiState.pendingDeletes, ["A", "B"])

        vm.onUndoDelete()
        await settleMain()

        XCTAssertEqual(vm.uiState.pendingDeletes, [])
        XCTAssertEqual(repository.restoreCalls, [a, b])
    }

    // MARK: - Sort and Paste a link (#102)

    private let unsorted = [testSummary(2, title: "Bread"), testSummary(3, title: "apple pie"), testSummary(1, title: "Cake")]

    func testRecentlyViewedIsTheDefaultOrderAsTheRepositoryGivesIt() async {
        let repository = FakeRecipeRepository()
        repository.history.send(unsorted)
        let vm = RecipesViewModel(repository: repository, sleep: immediateSleep)
        await settleMain()

        XCTAssertEqual(vm.uiState.sort, .recentlyViewed)
        XCTAssertEqual(vm.uiState.recipes?.map(\.id), [2, 3, 1])
    }

    func testNameSortsByTitleIgnoringCaseAndDateAddedIsNewestIdFirst() async {
        let repository = FakeRecipeRepository()
        repository.history.send(unsorted)
        let vm = RecipesViewModel(repository: repository, sleep: immediateSleep)
        await settleMain()

        vm.onSortChange(.name)
        XCTAssertEqual(vm.uiState.recipes?.map(\.title), ["apple pie", "Bread", "Cake"])
        vm.onSortChange(.dateAdded)
        XCTAssertEqual(vm.uiState.recipes?.map(\.id), [3, 2, 1])
    }

    func testPasteALinkOpensOnlyARealLinkAndClosesTheAlert() {
        let vm = RecipesViewModel(repository: FakeRecipeRepository(), sleep: immediateSleep)

        vm.onPasteLink()
        XCTAssertTrue(vm.uiState.pastingLink)
        vm.onLinkChange("not a link")
        XCTAssertFalse(vm.uiState.canOpenLink)
        XCTAssertNil(vm.onOpenLink())
        XCTAssertTrue(vm.uiState.pastingLink)

        vm.onLinkChange("  seriouseats.com/bread ")
        XCTAssertTrue(vm.uiState.canOpenLink)
        XCTAssertEqual(vm.onOpenLink(), "https://seriouseats.com/bread")
        XCTAssertFalse(vm.uiState.pastingLink)
    }

    func testTheAlertGoingAwayKeepsTheTextForAGoThatRunsSecond() {
        let vm = RecipesViewModel(repository: FakeRecipeRepository(), sleep: immediateSleep)
        vm.onPasteLink()
        vm.onLinkChange("https://example.com/soup")

        vm.onLinkDismissed()

        XCTAssertFalse(vm.uiState.pastingLink)
        XCTAssertEqual(vm.onOpenLink(), "https://example.com/soup")
    }

    func testSnackbarMessageNamesOneAndCountsMany() {
        XCTAssertNil(Strings.deletedMessage([]))
        XCTAssertEqual(Strings.deletedMessage(["Soup"]), "Deleted \"Soup\"")
        XCTAssertEqual(Strings.deletedMessage(["A", "B"]), "2 recipes deleted")
    }
}
