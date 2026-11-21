import XCTest
@testable import RecipeClipper

/// The save-to-list sheet over several recipes (#377): a list holds all, some or none of them,
/// a tick adds all, an untick removes all.
@MainActor
final class SaveToListSeveralTests: XCTestCase {

    private func repository() -> FakeListRepository {
        let repository = FakeListRepository()
        repository.lists.send([
            RecipeList(id: 1, name: "Favorites", isBuiltIn: true, isFavorites: true, recipeCount: 0),
            RecipeList(id: 2, name: "Lunch", isBuiltIn: true, isFavorites: false, recipeCount: 0),
            RecipeList(id: 3, name: "Dinner", isBuiltIn: true, isFavorites: false, recipeCount: 0),
        ])
        return repository
    }

    private func list(_ vm: SaveToListViewModel, _ id: Int64) -> RecipeList? {
        vm.uiState.lists.first { $0.id == id }
    }

    func testAListHoldingAllSomeOrNoneOfTheSelectionShowsEachWay() async {
        let repository = repository()
        // Favorites holds both, Lunch one of them, Dinner neither.
        repository.stageMembership([(10, 1), (11, 1), (10, 2)])
        let vm = SaveToListViewModel(repository: repository)

        vm.setRecipes([10, 11])
        await settleMain()

        XCTAssertEqual(vm.uiState.selectionCount, 2)
        XCTAssertEqual(list(vm, 1)?.containsRecipe, true)
        XCTAssertFalse(vm.uiState.isPartial(list(vm, 1)!))
        XCTAssertEqual(list(vm, 2)?.containsRecipe, false)
        XCTAssertTrue(vm.uiState.isPartial(list(vm, 2)!), "some, not all: indeterminate")
        XCTAssertEqual(list(vm, 3)?.containsRecipe, false)
        XCTAssertFalse(vm.uiState.isPartial(list(vm, 3)!))
        XCTAssertEqual(vm.uiState.partialListIds, [2])
    }

    func testTappingAnEmptyListAddsAllOfThemInOneWrite() async {
        let repository = repository()
        let vm = SaveToListViewModel(repository: repository)
        vm.setRecipes([10, 11, 12])
        await settleMain()

        vm.onListTapped(list(vm, 3)!)
        await settleMain()

        XCTAssertEqual(repository.setMembershipAllCalls.count, 1)
        XCTAssertEqual(repository.membership.value.filter { $0.listId == 3 }.map(\.recipeId).sorted(), [10, 11, 12])
        XCTAssertEqual(list(vm, 3)?.containsRecipe, true)
    }

    func testTappingAPartialListAddsTheRestSoItBecomesTicked() async {
        let repository = repository()
        repository.stageMembership([(10, 2)])
        let vm = SaveToListViewModel(repository: repository)
        vm.setRecipes([10, 11])
        await settleMain()
        XCTAssertTrue(vm.uiState.isPartial(list(vm, 2)!))

        vm.onListTapped(list(vm, 2)!)
        await settleMain()

        XCTAssertEqual(repository.membership.value.filter { $0.listId == 2 }.map(\.recipeId).sorted(), [10, 11])
        XCTAssertEqual(list(vm, 2)?.containsRecipe, true)
        XCTAssertFalse(vm.uiState.isPartial(list(vm, 2)!))
    }

    func testTappingAFullyTickedListRemovesAllOfThem() async {
        let repository = repository()
        repository.stageMembership([(10, 1), (11, 1), (99, 1)])
        let vm = SaveToListViewModel(repository: repository)
        vm.setRecipes([10, 11])
        await settleMain()

        vm.onListTapped(list(vm, 1)!)
        await settleMain()

        XCTAssertEqual(repository.membership.value.map(\.recipeId), [99], "only the selection left; another recipe stays")
        XCTAssertEqual(list(vm, 1)?.containsRecipe, false)
    }

    func testANewListTakesAllTheSelectedRecipesAndSaysSo() async {
        let repository = repository()
        let vm = SaveToListViewModel(repository: repository)
        vm.setRecipes([10, 11])
        await settleMain()

        vm.onStartCreating()
        vm.onNewListNameChange("Picnic")
        vm.onCreateList()
        await settleMain()

        XCTAssertEqual(repository.createManyCalls.count, 1)
        XCTAssertEqual(repository.createManyCalls[0].recipeIds, [10, 11])
        XCTAssertEqual(repository.createCalls.count, 0)
        XCTAssertEqual(repository.membership.value.map(\.recipeId).sorted(), [10, 11])
        XCTAssertFalse(vm.uiState.creatingList)
    }

    func testOneRecipeThroughSetRecipesIsTheSingleCaseExactly() async {
        let repository = repository()
        repository.stageMembership([(10, 2)])
        let vm = SaveToListViewModel(repository: repository)

        vm.setRecipes([10], titles: [10: "Soup"])
        await settleMain()

        XCTAssertEqual(vm.uiState.selectionCount, 1)
        XCTAssertEqual(vm.uiState.recipeTitle, "Soup")
        XCTAssertEqual(list(vm, 2)?.containsRecipe, true)
        vm.onListTapped(list(vm, 3)!)
        await settleMain()
        XCTAssertEqual(repository.setMembershipAllCalls.count, 0, "one recipe writes one membership as before")
        XCTAssertTrue(repository.membership.value.contains(.init(recipeId: 10, listId: 3)))
    }

    func testGoingBackToOneRecipeDropsThePartialStates() async {
        let repository = repository()
        repository.stageMembership([(10, 2)])
        let vm = SaveToListViewModel(repository: repository)
        vm.setRecipes([10, 11])
        await settleMain()
        XCTAssertEqual(vm.uiState.partialListIds, [2])

        vm.setRecipe(11, title: "Stew")
        await settleMain()

        XCTAssertEqual(vm.uiState.partialListIds, [])
        XCTAssertEqual(vm.uiState.selectionCount, 1)
        XCTAssertEqual(list(vm, 2)?.containsRecipe, false)
    }
}
