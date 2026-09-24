import XCTest
@testable import RecipeClipper

@MainActor
final class SaveToListViewModelTests: XCTestCase {

    private func list(_ id: Int64, _ name: String) -> RecipeList {
        RecipeList(id: id, name: name, isBuiltIn: true, isFavorites: id == 1, recipeCount: 0)
    }

    private func repositoryWithBuiltIns() -> FakeListRepository {
        let repository = FakeListRepository()
        repository.lists.send([list(1, "Favorites"), list(2, "Lunch"), list(3, "Dinner")])
        return repository
    }

    private func membership(_ repository: FakeListRepository) -> Set<FakeListRepository.Membership> {
        repository.membership.value
    }

    func testNoRecipeSetMeansNoListsToShow() async {
        let vm = SaveToListViewModel(repository: repositoryWithBuiltIns())
        await settleMain()

        // The bookmark icon must not claim "saved" before it knows which recipe it means.
        XCTAssertEqual(vm.uiState.lists, [])
        XCTAssertFalse(vm.uiState.isSaved)
    }

    func testSettingTheRecipeShowsEveryListNoneTicked() async {
        let vm = SaveToListViewModel(repository: repositoryWithBuiltIns())
        vm.setRecipe(7)
        await settleMain()

        XCTAssertEqual(vm.uiState.lists.map(\.name), ["Favorites", "Lunch", "Dinner"])
        XCTAssertFalse(vm.uiState.lists.contains { $0.containsRecipe })
        XCTAssertFalse(vm.uiState.isSaved)
    }

    func testTickingAListWritesItThroughAndTheTickComesBack() async {
        let repository = repositoryWithBuiltIns()
        let vm = SaveToListViewModel(repository: repository)
        vm.setRecipe(7)
        await settleMain()

        vm.onListToggled(listId: 2, inList: true)
        await settleMain()

        XCTAssertEqual(membership(repository), [.init(recipeId: 7, listId: 2)])
        let lunch = vm.uiState.lists.first { $0.id == 2 }
        XCTAssertEqual(lunch?.containsRecipe, true)
        // The count is derived from membership, as it is in SQL.
        XCTAssertEqual(lunch?.recipeCount, 1)
    }

    func testUntickingRemovesTheMembership() async {
        let repository = repositoryWithBuiltIns()
        repository.stageMembership([(7, 2)])
        let vm = SaveToListViewModel(repository: repository)
        vm.setRecipe(7)
        await settleMain()

        vm.onListToggled(listId: 2, inList: false)
        await settleMain()

        XCTAssertTrue(membership(repository).isEmpty)
        XCTAssertEqual(vm.uiState.lists.first { $0.id == 2 }?.containsRecipe, false)
    }

    func testSavedIsTrueWhileTheRecipeIsInAnyListAtAll() async {
        let vm = SaveToListViewModel(repository: repositoryWithBuiltIns())
        vm.setRecipe(7)
        await settleMain()
        XCTAssertFalse(vm.uiState.isSaved)

        vm.onListToggled(listId: 3, inList: true)
        await settleMain()
        XCTAssertTrue(vm.uiState.isSaved)

        vm.onListToggled(listId: 3, inList: false)
        await settleMain()
        XCTAssertFalse(vm.uiState.isSaved)
    }

    func testAnotherRecipesMembershipDoesNotTickThisOne() async {
        let repository = repositoryWithBuiltIns()
        repository.stageMembership([(99, 2)])
        let vm = SaveToListViewModel(repository: repository)
        vm.setRecipe(7)
        await settleMain()

        XCTAssertFalse(vm.uiState.isSaved)
        // The list still shows the other recipe in its count; it just isn't ticked here.
        XCTAssertEqual(vm.uiState.lists.first { $0.id == 2 }?.recipeCount, 1)
    }

    func testSwitchingRecipeFollowsTheLatestOne() async {
        let repository = repositoryWithBuiltIns()
        repository.stageMembership([(8, 2)])
        let vm = SaveToListViewModel(repository: repository)
        vm.setRecipe(7)
        await settleMain()
        XCTAssertFalse(vm.uiState.isSaved)

        vm.setRecipe(8)
        await settleMain()

        XCTAssertTrue(vm.uiState.isSaved)
    }

    func testTogglingBeforeARecipeIsSetIsIgnoredRatherThanCrashing() async {
        let repository = repositoryWithBuiltIns()
        let vm = SaveToListViewModel(repository: repository)
        await settleMain()

        vm.onListToggled(listId: 2, inList: true)
        await settleMain()

        XCTAssertTrue(membership(repository).isEmpty)
    }

    func testCreatingAListFromTheSheetPutsTheCurrentRecipeInIt() async {
        let repository = repositoryWithBuiltIns()
        let vm = SaveToListViewModel(repository: repository)
        vm.setRecipe(7)
        await settleMain()

        vm.onStartCreating()
        vm.onNewListNameChange("Weeknights")
        vm.onCreateList()
        await settleMain()

        XCTAssertEqual(repository.createCalls.map(\.name), ["Weeknights"])
        XCTAssertEqual(repository.createCalls.map(\.recipeId), [7])
        XCTAssertEqual(vm.uiState.lists.first { $0.name == "Weeknights" }?.containsRecipe, true)
        XCTAssertTrue(vm.uiState.isSaved)
    }

    func testCreatingClosesTheFieldAndClearsWhatWasTyped() async {
        let vm = SaveToListViewModel(repository: repositoryWithBuiltIns())
        vm.setRecipe(7)
        vm.onStartCreating()
        vm.onNewListNameChange("Weeknights")
        XCTAssertTrue(vm.uiState.creatingList)

        vm.onCreateList()
        await settleMain()

        XCTAssertFalse(vm.uiState.creatingList)
        XCTAssertEqual(vm.uiState.newListName, "")
    }

    func testABlankNameCreatesNothing() async {
        let repository = repositoryWithBuiltIns()
        let vm = SaveToListViewModel(repository: repository)
        vm.setRecipe(7)
        vm.onStartCreating()
        vm.onNewListNameChange("   ")
        vm.onCreateList()
        await settleMain()

        XCTAssertTrue(repository.createCalls.isEmpty)
        // The field stays open, so what was typed isn't silently thrown away.
        XCTAssertTrue(vm.uiState.creatingList)
    }

    func testTheCreatedNameIsTrimmed() async {
        let repository = repositoryWithBuiltIns()
        let vm = SaveToListViewModel(repository: repository)
        vm.setRecipe(7)
        vm.onStartCreating()
        vm.onNewListNameChange("  Weeknights  ")
        vm.onCreateList()
        await settleMain()

        XCTAssertEqual(repository.createCalls.map(\.name), ["Weeknights"])
    }

    func testCancellingCreationClearsTheField() async {
        let repository = repositoryWithBuiltIns()
        let vm = SaveToListViewModel(repository: repository)
        vm.onStartCreating()
        vm.onNewListNameChange("Weeknights")

        vm.onCancelCreating()
        await settleMain()

        XCTAssertFalse(vm.uiState.creatingList)
        XCTAssertEqual(vm.uiState.newListName, "")
        XCTAssertTrue(repository.createCalls.isEmpty)
    }
}
