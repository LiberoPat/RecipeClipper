import XCTest
@testable import RecipeClipper

@MainActor
final class ListDetailViewModelTests: XCTestCase {

    private let favorites = RecipeList(id: 1, name: "Favorites", isBuiltIn: true, isFavorites: true, recipeCount: 0)
    private let weeknights = RecipeList(id: 2, name: "Weeknights", isBuiltIn: false, isFavorites: false, recipeCount: 0)
    /// Seeded, but not Favorites — so deletable.
    private let lunch = RecipeList(id: 3, name: "Lunch", isBuiltIn: true, isFavorites: false, recipeCount: 0)

    private func repository() -> FakeListRepository {
        let repository = FakeListRepository()
        repository.lists.send([favorites, weeknights])
        return repository
    }

    func testResolvesTheListNamedByTheNavigationArgument() async {
        let vm = ListDetailViewModel(listId: 2, repository: repository())
        await settleMain()

        XCTAssertEqual(vm.uiState.list?.name, "Weeknights")
        XCTAssertTrue(vm.uiState.loaded)
    }

    func testStartsUnloaded() {
        let vm = ListDetailViewModel(listId: 2, repository: repository())
        XCTAssertFalse(vm.uiState.loaded)
    }

    func testAsksTheRepositoryForThatListsRecipes() async {
        let repository = repository()
        repository.recipesIn.send([testSummary(7, isSaved: true), testSummary(8, isSaved: true)])
        let vm = ListDetailViewModel(listId: 2, repository: repository)
        await settleMain()

        XCTAssertEqual(repository.recipesInQueries, [2])
        XCTAssertEqual(vm.uiState.recipes.map(\.id), [7, 8])
    }

    func testAnEmptyListIsLoadedNotMissing() async {
        let vm = ListDetailViewModel(listId: 2, repository: repository())
        await settleMain()

        XCTAssertTrue(vm.uiState.loaded)
        XCTAssertTrue(vm.uiState.recipes.isEmpty)
        XCTAssertEqual(vm.uiState.list?.name, "Weeknights")
    }

    func testAListIdThatMatchesNothingLeavesTheListNil() async {
        let vm = ListDetailViewModel(listId: 99, repository: repository())
        await settleMain()

        XCTAssertNil(vm.uiState.list)
    }

    func testRenamingSeedsTheFieldWithTheCurrentName() async {
        let vm = ListDetailViewModel(listId: 2, repository: repository())
        await settleMain()

        vm.onStartRenaming()

        XCTAssertTrue(vm.uiState.renaming)
        XCTAssertEqual(vm.uiState.renameValue, "Weeknights")
    }

    func testRenamingWritesThroughTrimmedAndClosesTheDialog() async {
        let repository = repository()
        let vm = ListDetailViewModel(listId: 2, repository: repository)
        await settleMain()

        vm.onStartRenaming()
        vm.onRenameValueChange("  Midweek  ")
        vm.onRenameConfirm()
        await settleMain()

        XCTAssertEqual(repository.renameCalls.map(\.listId), [2])
        XCTAssertEqual(repository.renameCalls.map(\.name), ["Midweek"])
        XCTAssertFalse(vm.uiState.renaming)
        XCTAssertEqual(vm.uiState.list?.name, "Midweek")
    }

    func testABlankRenameIsIgnoredAndTheDialogStaysOpen() async {
        let repository = repository()
        let vm = ListDetailViewModel(listId: 2, repository: repository)
        await settleMain()

        vm.onStartRenaming()
        vm.onRenameValueChange("   ")
        vm.onRenameConfirm()
        await settleMain()

        XCTAssertTrue(repository.renameCalls.isEmpty)
        XCTAssertTrue(vm.uiState.renaming)
    }

    func testABuiltInCanBeRenamed() async {
        let repository = repository()
        let vm = ListDetailViewModel(listId: 1, repository: repository)
        await settleMain()

        vm.onStartRenaming()
        vm.onRenameValueChange("Best of")
        vm.onRenameConfirm()
        await settleMain()

        XCTAssertEqual(repository.renameCalls.map(\.listId), [1])
        XCTAssertEqual(repository.renameCalls.map(\.name), ["Best of"])
        XCTAssertEqual(vm.uiState.list?.isFavorites, true)
    }

    func testCancellingARenameClearsTheFieldAndChangesNothing() async {
        let repository = repository()
        let vm = ListDetailViewModel(listId: 2, repository: repository)
        await settleMain()

        vm.onStartRenaming()
        vm.onRenameValueChange("Midweek")
        vm.onCancelRenaming()
        await settleMain()

        XCTAssertFalse(vm.uiState.renaming)
        XCTAssertEqual(vm.uiState.renameValue, "")
        XCTAssertTrue(repository.renameCalls.isEmpty)
        XCTAssertEqual(vm.uiState.list?.name, "Weeknights")
    }

    func testDeletingAUserListMarksTheScreenDeleted() async {
        let repository = repository()
        let vm = ListDetailViewModel(listId: 2, repository: repository)
        await settleMain()

        vm.onDelete()
        await settleMain()

        XCTAssertEqual(repository.deleteCalls, [2])
        XCTAssertTrue(vm.uiState.deleted)
    }

    func testFavoritesIsNeverDeleted() async {
        let repository = repository()
        let vm = ListDetailViewModel(listId: 1, repository: repository)
        await settleMain()

        XCTAssertFalse(vm.canDelete)
        vm.onDelete()
        await settleMain()

        XCTAssertTrue(repository.deleteCalls.isEmpty)
        XCTAssertFalse(vm.uiState.deleted)
    }

    /// Lunch, Dinner, Desserts, Breakfast and Snacks are seeded but are starting suggestions,
    /// not fixtures. Only Favorites is protected, so `isBuiltIn` must not gate deleting.
    func testASeededListThatIsNotFavoritesCanBeDeleted() async {
        let repository = FakeListRepository()
        repository.lists.send([favorites, lunch])
        let vm = ListDetailViewModel(listId: lunch.id, repository: repository)
        await settleMain()

        XCTAssertTrue(vm.canDelete)
        vm.onDelete()
        await settleMain()

        XCTAssertEqual(repository.deleteCalls, [lunch.id])
        XCTAssertTrue(vm.uiState.deleted)
    }

    func testDeletingIsRefusedWhileTheListHasNotResolved() async {
        let repository = repository()
        let vm = ListDetailViewModel(listId: 99, repository: repository)
        await settleMain()

        vm.onDelete()
        await settleMain()

        XCTAssertTrue(repository.deleteCalls.isEmpty)
        XCTAssertFalse(vm.uiState.deleted)
    }

    /// The list is collected by the ViewModel itself, not by whichever view happens to be
    /// observing, so a delete goes through with no view at all.
    func testDeletingWorksWithNoViewObserving() async {
        let repository = repository()
        let vm = ListDetailViewModel(listId: 2, repository: repository)
        await settleMain()

        vm.onDelete()
        await settleMain()

        XCTAssertEqual(repository.deleteCalls, [2])
    }

    /// SwiftUI dismisses an alert on any button and may reset its binding before running the
    /// button's action. Save must still rename when the dismissal lands first.
    func testSaveStillRenamesWhenTheAlertDismissalLandsFirst() async {
        let repository = repository()
        let vm = ListDetailViewModel(listId: 2, repository: repository)
        await settleMain()

        vm.onStartRenaming()
        vm.onRenameValueChange("Midweek")
        vm.onRenameDismissed()   // the binding's setter
        vm.onRenameConfirm()     // then Save's action
        await settleMain()

        XCTAssertEqual(repository.renameCalls.map(\.name), ["Midweek"])
        XCTAssertFalse(vm.uiState.renaming)
    }

    func testDismissingTheAlertClosesItWithoutRenaming() async {
        let repository = repository()
        let vm = ListDetailViewModel(listId: 2, repository: repository)
        await settleMain()

        vm.onStartRenaming()
        vm.onRenameValueChange("Midweek")
        vm.onRenameDismissed()
        await settleMain()

        XCTAssertFalse(vm.uiState.renaming)
        XCTAssertTrue(repository.renameCalls.isEmpty)
        // Reopening reseeds from the list, not from the abandoned edit.
        vm.onStartRenaming()
        XCTAssertEqual(vm.uiState.renameValue, "Weeknights")
    }
}
