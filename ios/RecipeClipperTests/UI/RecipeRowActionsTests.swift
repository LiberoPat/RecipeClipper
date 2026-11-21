import XCTest
@testable import RecipeClipper

/// A recipe row's long-press menu and selecting several recipes (#376, #377; Android's
/// `RecipesViewModelTest` and `ListDetailViewModelTest` additions): selection as ViewModel state,
/// one confirmation and one Undo for many, the free-tier rule for that Undo, leaving a list,
/// and the text a row shares.
@MainActor
final class RecipeRowActionsTests: XCTestCase {

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

    private func stocked(_ ids: [Int64]) -> FakeRecipeRepository {
        let repository = FakeRecipeRepository()
        repository.history.send(ids.map { testSummary($0, title: "Recipe \($0)") })
        for id in ids { repository.deleteResults[id] = deleted(id, "Recipe \(id)") }
        return repository
    }

    private func recipesVM(_ repository: FakeRecipeRepository, lists: ListRepository? = nil) -> RecipesViewModel {
        RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep, lists: lists)
    }

    // MARK: Selecting

    func testNotSelectingToStartWith() async {
        let vm = recipesVM(stocked([1, 2]))
        await settleMain()

        XCTAssertFalse(vm.uiState.selecting)
        XCTAssertEqual(vm.uiState.selectedIds, [])
        XCTAssertTrue(vm.uiState.canSelect)
    }

    func testTheTopBarsSelectStartsWithNothingTicked() async {
        let vm = recipesVM(stocked([1, 2]))
        await settleMain()

        vm.onStartSelecting()

        XCTAssertTrue(vm.uiState.selecting)
        XCTAssertEqual(vm.uiState.selectedIds, [])
    }

    func testARowsMenuStartsSelectingWithThatRowTicked() async {
        let vm = recipesVM(stocked([1, 2, 3]))
        await settleMain()

        vm.onStartSelecting(testSummary(2))

        XCTAssertTrue(vm.uiState.selecting)
        XCTAssertEqual(vm.uiState.selectedIds, [2])
    }

    func testATapTogglesARowAndDoneLeavesWithNoTicks() async {
        let vm = recipesVM(stocked([1, 2, 3]))
        await settleMain()
        vm.onStartSelecting()

        vm.onToggleSelected(1)
        vm.onToggleSelected(3)
        XCTAssertEqual(vm.uiState.selectedIds, [1, 3])
        vm.onToggleSelected(1)
        XCTAssertEqual(vm.uiState.selectedIds, [3])

        vm.onExitSelection()
        XCTAssertFalse(vm.uiState.selecting)
        XCTAssertEqual(vm.uiState.selectedIds, [])
        vm.onStartSelecting()
        XCTAssertEqual(vm.uiState.selectedIds, [], "Done dropped the ticks")
    }

    func testATapOutsideSelectingTicksNothing() async {
        let vm = recipesVM(stocked([1, 2]))
        await settleMain()

        vm.onToggleSelected(1)

        XCTAssertFalse(vm.uiState.selecting)
        XCTAssertEqual(vm.uiState.selectedIds, [])
    }

    func testASearchNeverLeavesAHiddenTickForAnActionToTake() async {
        let repository = stocked([1, 2, 3])
        let vm = recipesVM(repository)
        await settleMain()
        vm.onStartSelecting()
        vm.onToggleSelected(1)
        vm.onToggleSelected(2)

        // A search narrows the rows to recipe 2 only.
        repository.history.send([testSummary(2)])
        await settleMain()
        XCTAssertEqual(vm.uiState.selectedIds, [2], "the effective selection is what is listed")
        XCTAssertEqual(vm.selectedRecipes.map(\.id), [2])

        // The search cleared: the tick on 1 was kept, not lost.
        repository.history.send([testSummary(1), testSummary(2), testSummary(3)])
        await settleMain()
        XCTAssertEqual(vm.uiState.selectedIds, [1, 2])
    }

    // MARK: Deleting

    func testADeleteAsksBeforeDeletingAnything() async {
        let repository = stocked([1, 2])
        let vm = recipesVM(repository)
        await settleMain()

        vm.onRequestDelete([testSummary(1)])
        await settleMain()

        XCTAssertEqual(vm.uiState.confirmingDelete.map(\.id), [1])
        XCTAssertEqual(repository.deleteAllCalls, [])
        XCTAssertEqual(vm.uiState.pendingDeletes, [])
    }

    func testCancellingTheConfirmationDeletesNothing() async {
        let repository = stocked([1, 2])
        let vm = recipesVM(repository)
        await settleMain()
        vm.onRequestDelete([testSummary(1)])

        vm.onCancelDelete()
        await settleMain()

        XCTAssertEqual(vm.uiState.confirmingDelete, [])
        XCTAssertEqual(repository.deleteAllCalls, [])
    }

    func testConfirmingDeletesTheRowsRecipeWithAnUndoSnackbar() async {
        let repository = stocked([1, 2])
        let vm = recipesVM(repository)
        await settleMain()
        vm.onRequestDelete([testSummary(1, title: "Recipe 1")])

        vm.onConfirmDelete()
        await settleMain()

        XCTAssertEqual(repository.deleteAllCalls, [[1]])
        XCTAssertEqual(vm.uiState.pendingDeletes, ["Recipe 1"])
        XCTAssertNotNil(vm.uiState.deleteBatch)
        XCTAssertEqual(vm.uiState.confirmingDelete, [])
    }

    func testTheSelectionsDeleteIsOneConfirmationOneTransactionAndOneUndo() async {
        let repository = stocked([1, 2, 3])
        let vm = recipesVM(repository)
        await settleMain()
        vm.onStartSelecting()
        vm.onToggleSelected(1)
        vm.onToggleSelected(3)

        vm.onRequestDelete(vm.selectedRecipes)
        XCTAssertEqual(vm.uiState.confirmingDelete.map(\.id), [1, 3])
        vm.onConfirmDelete()
        await settleMain()

        XCTAssertEqual(repository.deleteAllCalls, [[1, 3]], "one transaction for the lot")
        XCTAssertEqual(vm.uiState.pendingDeletes.count, 2)
        XCTAssertEqual(vm.uiState.deleteBatch, 1, "one batch, so one snackbar")
        XCTAssertFalse(vm.uiState.selecting, "deleting ends selecting")

        vm.onUndoDelete()
        await settleMain()
        XCTAssertEqual(repository.restoreAllCalls.count, 1, "one all-or-nothing Undo")
        XCTAssertEqual(repository.restoreAllCalls[0].map(\.recipe.id), [1, 3])
        XCTAssertEqual(vm.uiState.pendingDeletes, [])
    }

    func testAnUndoThatTheFullFreeLibraryRefusesRestoresNoneOfTheSelection() async {
        let repository = stocked([1, 2, 3])
        repository.restoreOutcome = .libraryFull
        let vm = recipesVM(repository)
        await settleMain()
        vm.onRequestDelete([testSummary(1), testSummary(2)])
        vm.onConfirmDelete()
        await settleMain()

        vm.onUndoDelete()
        await settleMain()

        XCTAssertTrue(vm.uiState.restoreRefused, "the screen says so once")
        XCTAssertEqual(repository.restoreCalls, [], "none came back")
        XCTAssertEqual(repository.forgetCalls.map(\.recipe.id), [1, 2], "the deletes stand, photos forgotten")
        XCTAssertEqual(vm.uiState.pendingDeletes, [])
        vm.onRestoreRefusedShown()
        XCTAssertFalse(vm.uiState.restoreRefused)
    }

    func testTheSnackbarTimingOutLetsAllThePhotosGo() async {
        let repository = stocked([1, 2])
        let vm = recipesVM(repository)
        await settleMain()
        vm.onRequestDelete([testSummary(1), testSummary(2)])
        vm.onConfirmDelete()
        await settleMain()

        vm.onSnackbarDismissed()
        await settleMain()

        XCTAssertEqual(repository.forgetCalls.map(\.recipe.id), [1, 2])
        XCTAssertNil(vm.uiState.deleteBatch)
    }

    func testASwipeAndAMenuDeleteShareOneBatch() async {
        let repository = stocked([1, 2])
        let vm = recipesVM(repository)
        await settleMain()

        vm.onDelete(testSummary(1))
        await settleMain()
        vm.onRequestDelete([testSummary(2)])
        vm.onConfirmDelete()
        await settleMain()

        XCTAssertEqual(vm.uiState.deleteBatch, 1)
        XCTAssertEqual(vm.uiState.pendingDeletes, ["Recipe 1", "Recipe 2"])
    }

    // MARK: Share and Add to plan

    private func richRecipe() -> Recipe {
        Recipe(
            name: "Pancakes", image: nil, ingredients: ["2 cups flour", "1 cup milk"],
            instructions: ["Preheat the oven to 350°F.", "Mix."],
            prepTime: "10m", cookTime: "20m", totalTime: "30m", yield: "4 servings",
            sourceUrl: "https://example.com/pancakes", id: 7, servingsTarget: 8
        )
    }

    func testARowSharesWhatTheReadingViewWould() async {
        let repository = stocked([7])
        let recipe = richRecipe()
        repository.recipes.send([7: recipe])
        repository.openResult = recipe
        let preferences = FakeAppPreferences(unitSystem: .metric, temperatureUnit: .celsius)
        let vm = RecipesViewModel(repository: repository, preferences: preferences, sleep: immediateSleep)
        await settleMain()

        let shared = await vm.onShare(recipeId: 7)

        let reading = RecipeViewModel(
            recipeId: 7, url: nil, repository: repository, preferences: preferences, clock: TestClock()
        )
        await settleMain()
        XCTAssertEqual(shared?.title, "Pancakes")
        XCTAssertEqual(shared?.text, reading.shareText())
        XCTAssertTrue(shared?.text.contains("Serves 8") == true, "at the recipe's saved servings")
        XCTAssertTrue(shared?.text.contains("180") == true, "in the user's units: \(shared?.text ?? "")")
    }

    func testSharingARowIsNotAView() async {
        let repository = stocked([7])
        repository.recipes.send([7: richRecipe()])
        let vm = recipesVM(repository)
        await settleMain()

        _ = await vm.onShare(recipeId: 7)

        XCTAssertEqual(repository.byIdsQueries, [[7]], "read with byIds, never open(id:), which counts as a view")
    }

    func testSharingAGoneRecipeIsNil() async {
        let vm = recipesVM(stocked([7]))
        await settleMain()

        let shared = await vm.onShare(recipeId: 7)
        XCTAssertNil(shared)
    }

    func testAddToPlanStartsFromTheRecipesOwnYield() async {
        let repository = stocked([7])
        repository.recipes.send([7: richRecipe()])
        let vm = recipesVM(repository)
        await settleMain()

        let yield = await vm.yieldServings(recipeId: 7)
        XCTAssertEqual(yield, 4, "the base yield, as the recipe screen passes servings.base, not the saved 8")
    }

    // MARK: A list's detail

    private func listDetail(
        _ ids: [Int64], recipes: FakeRecipeRepository? = nil
    ) -> (ListDetailViewModel, FakeListRepository, FakeRecipeRepository) {
        let lists = FakeListRepository()
        lists.lists.send([RecipeList(id: 2, name: "Weeknights", isBuiltIn: false, isFavorites: false, recipeCount: 0)])
        lists.recipesIn.send(ids.map { testSummary($0, isSaved: true) })
        lists.stageMembership(ids.map { (recipeId: $0, listId: 2) })
        let repository = recipes ?? stocked(ids)
        let vm = ListDetailViewModel(
            listId: 2, repository: lists, recipes: repository, preferences: FakeAppPreferences()
        )
        return (vm, lists, repository)
    }

    func testAListsDetailSelectsLikeRecipes() async {
        let (vm, _, _) = listDetail([1, 2, 3])
        await settleMain()

        vm.onStartSelecting(testSummary(2))
        vm.onToggleSelected(3)

        XCTAssertTrue(vm.uiState.selecting)
        XCTAssertEqual(vm.uiState.selectedIds, [2, 3])
        vm.onExitSelection()
        XCTAssertFalse(vm.uiState.selecting)
    }

    func testRemovingFromThisListLeavesTheListNotTheLibrary() async {
        let (vm, lists, recipes) = listDetail([1, 2, 3])
        await settleMain()
        vm.onStartSelecting()
        vm.onToggleSelected(1)
        vm.onToggleSelected(2)

        vm.onRemoveFromList(ids: vm.selectedRecipes.map(\.id))
        await settleMain()

        XCTAssertEqual(lists.setMembershipAllCalls.count, 1, "one transaction")
        XCTAssertEqual(lists.setMembershipAllCalls[0].recipeIds, [1, 2])
        XCTAssertEqual(lists.setMembershipAllCalls[0].listId, 2)
        XCTAssertFalse(lists.setMembershipAllCalls[0].inList)
        XCTAssertEqual(lists.membership.value.map(\.recipeId), [3])
        XCTAssertEqual(recipes.deleteAllCalls, [], "nothing deleted")
        XCTAssertFalse(vm.uiState.selecting, "leaving the list ends selecting")
        XCTAssertEqual(vm.uiState.pendingDeletes, [], "no snackbar: it is not a deletion")
    }

    func testARowsRemoveFromThisListNeedsNoSelection() async {
        let (vm, lists, _) = listDetail([1, 2])
        await settleMain()

        vm.onRemoveFromList(ids: [2])
        await settleMain()

        XCTAssertEqual(lists.membership.value.map(\.recipeId), [1])
    }

    func testDeletingFromAListsDetailAsksOnceThenOffersOneUndo() async {
        let (vm, _, recipes) = listDetail([1, 2, 3])
        await settleMain()
        vm.onStartSelecting()
        vm.onToggleSelected(1)
        vm.onToggleSelected(2)

        vm.onRequestDelete(vm.selectedRecipes)
        XCTAssertEqual(vm.uiState.confirmingDelete.map(\.id), [1, 2])
        vm.onConfirmDelete()
        await settleMain()

        XCTAssertEqual(recipes.deleteAllCalls, [[1, 2]])
        XCTAssertEqual(vm.uiState.deleteBatch, 1)
        XCTAssertEqual(vm.uiState.pendingDeletes.count, 2)
        XCTAssertFalse(vm.uiState.selecting)

        vm.onUndoDelete()
        await settleMain()
        XCTAssertEqual(recipes.restoreAllCalls.count, 1)
        XCTAssertEqual(vm.uiState.pendingDeletes, [])
    }

    func testAListsDetailUndoIsRefusedOnAFullFreeLibrary() async {
        let (vm, _, recipes) = listDetail([1, 2])
        recipes.restoreOutcome = .libraryFull
        await settleMain()
        vm.onRequestDelete([testSummary(1), testSummary(2)])
        vm.onConfirmDelete()
        await settleMain()

        vm.onUndoDelete()
        await settleMain()

        XCTAssertTrue(vm.uiState.restoreRefused)
        XCTAssertEqual(recipes.restoreCalls, [])
    }

    func testAListsDetailWithoutARecipeRepositoryStillCompilesAndSelects() async {
        let lists = FakeListRepository()
        lists.recipesIn.send([testSummary(1, isSaved: true)])
        let vm = ListDetailViewModel(listId: 2, repository: lists)
        await settleMain()

        vm.onStartSelecting()
        vm.onToggleSelected(1)
        XCTAssertEqual(vm.uiState.selectedIds, [1])
        let shared = await vm.onShare(recipeId: 1)
        XCTAssertNil(shared)
    }

    // MARK: The registry

    func testBothFlagsAreOnByDefaultInDebugAndRelease() {
        for flag in ["rowActions", "multiSelect"] {
            let definition = FlagRegistry.definitions.first { $0.key == flag }
            XCTAssertEqual(definition?.defaults.debug, true, flag)
            XCTAssertEqual(definition?.defaults.release, true, flag)
        }
    }
}
