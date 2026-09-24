import XCTest
@testable import RecipeClipper

@MainActor
final class ListsViewModelTests: XCTestCase {

    private func list(_ id: Int64, _ name: String) -> RecipeList {
        RecipeList(id: id, name: name, isBuiltIn: true, isFavorites: id == 1, recipeCount: 0)
    }

    private func repositoryWithBuiltIns() -> FakeListRepository {
        let repository = FakeListRepository()
        repository.lists.send([list(1, "Favorites"), list(2, "Lunch")])
        return repository
    }

    func testStartsUnloadedSoTheScreenDoesNotFlashAnEmptyList() {
        let vm = ListsViewModel(repository: repositoryWithBuiltIns())
        XCTAssertFalse(vm.uiState.loaded)
    }

    func testListsArriveWithTheirCounts() async {
        let repository = repositoryWithBuiltIns()
        repository.stageMembership([(7, 2), (8, 2)])
        let vm = ListsViewModel(repository: repository)
        await settleMain()

        XCTAssertTrue(vm.uiState.loaded)
        XCTAssertEqual(vm.uiState.lists.map(\.name), ["Favorites", "Lunch"])
        XCTAssertEqual(vm.uiState.lists.first { $0.id == 1 }?.recipeCount, 0)
        XCTAssertEqual(vm.uiState.lists.first { $0.id == 2 }?.recipeCount, 2)
    }

    func testNothingHereIsTickedSinceNoRecipeIsInHand() async {
        let repository = repositoryWithBuiltIns()
        repository.stageMembership([(7, 2)])
        let vm = ListsViewModel(repository: repository)
        await settleMain()

        XCTAssertFalse(vm.uiState.lists.contains { $0.containsRecipe })
    }

    func testCreatingAListFromHereLeavesItEmpty() async {
        let repository = repositoryWithBuiltIns()
        let vm = ListsViewModel(repository: repository)
        vm.onStartCreating()
        vm.onNewListNameChange("Weeknights")
        vm.onCreateList()
        await settleMain()

        XCTAssertEqual(repository.createCalls.map(\.name), ["Weeknights"])
        XCTAssertEqual(repository.createCalls.map(\.recipeId), [nil])
        XCTAssertEqual(vm.uiState.lists.first { $0.name == "Weeknights" }?.recipeCount, 0)
    }

    func testCreatingClosesTheFieldAndClearsWhatWasTyped() async {
        let vm = ListsViewModel(repository: repositoryWithBuiltIns())
        vm.onStartCreating()
        vm.onNewListNameChange("Weeknights")
        XCTAssertTrue(vm.uiState.creatingList)

        vm.onCreateList()
        await settleMain()

        XCTAssertFalse(vm.uiState.creatingList)
        XCTAssertEqual(vm.uiState.newListName, "")
    }

    func testABlankNameCreatesNothingAndKeepsTheFieldOpen() async {
        let repository = repositoryWithBuiltIns()
        let vm = ListsViewModel(repository: repository)
        vm.onStartCreating()
        vm.onNewListNameChange(" ")
        vm.onCreateList()
        await settleMain()

        XCTAssertTrue(repository.createCalls.isEmpty)
        XCTAssertTrue(vm.uiState.creatingList)
    }

    func testCancellingClearsTheField() async {
        let repository = repositoryWithBuiltIns()
        let vm = ListsViewModel(repository: repository)
        vm.onStartCreating()
        vm.onNewListNameChange("Weeknights")
        vm.onCancelCreating()
        await settleMain()

        XCTAssertFalse(vm.uiState.creatingList)
        XCTAssertEqual(vm.uiState.newListName, "")
        XCTAssertTrue(repository.createCalls.isEmpty)
    }

    func testCountLabelsSayEmptyRatherThanZero() {
        XCTAssertEqual(Strings.listCount(0), "Empty")
        XCTAssertEqual(Strings.listCount(1), "1 recipe")
        XCTAssertEqual(Strings.listCount(3), "3 recipes")
    }
}
