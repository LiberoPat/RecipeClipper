import XCTest
@testable import RecipeClipper

/// The top of Recipes, design B (#449), as the ViewModel serves it: the filters on (the sheet's switches,
/// the pills and the button's badge), Clear, and the selection's Select all. Android's `RecipesTopBViewModelTest`.
@MainActor
final class RecipesTopBViewModelTests: XCTestCase {

    private func summary(_ id: Int64, total: String? = nil, cooked: Int64? = nil) -> RecipeSummary {
        RecipeSummary(
            id: id, title: "Recipe \(id)", imageUrl: nil, totalTime: total, lastViewedAt: id,
            isSaved: false, lastCookedDay: cooked, language: "en"
        )
    }

    private func makeVM(view: RecipeFilter? = nil) async -> RecipesViewModel {
        let repository = FakeRecipeRepository()
        repository.history.send([summary(1, total: "20m"), summary(2, total: "2h"), summary(3, total: "10m", cooked: 20_000)])
        let vm = RecipesViewModel(
            repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep, view: view
        )
        await settleMain()
        return vm
    }

    func testTheFiltersOnAreCountedInTheOfferedOrder() async {
        let vm = await makeVM()
        XCTAssertEqual(vm.uiState.activeQuickFilters, [])

        vm.onQuickFilterToggle(.notCooked)
        vm.onQuickFilterToggle(.thirtyOrLess)
        XCTAssertEqual(vm.uiState.activeQuickFilters, [.thirtyOrLess, .notCooked], "the offered order, not the order turned on")
        XCTAssertEqual(vm.uiState.recipes?.map(\.id), [1])

        // A pill's ×: the same toggle.
        vm.onQuickFilterToggle(.notCooked)
        XCTAssertEqual(vm.uiState.activeQuickFilters, [.thirtyOrLess])
        XCTAssertEqual(vm.uiState.recipes?.map(\.id), [1, 3])
    }

    func testClearTurnsEveryFilterOffAtOnce() async {
        let vm = await makeVM()
        vm.onQuickFilterToggle(.thirtyOrLess)
        vm.onQuickFilterToggle(.notCooked)

        vm.onClearQuickFilters()

        XCTAssertTrue(vm.uiState.quick.isEmpty)
        XCTAssertEqual(vm.uiState.activeQuickFilters, [])
        XCTAssertEqual(vm.uiState.recipes?.count, 3)
    }

    func testInsideTheCookedViewNotCookedYetIsNeverCounted() async {
        let vm = await makeVM(view: .cooked)
        vm.onQuickFilterToggle(.notCooked)
        XCTAssertEqual(vm.uiState.activeQuickFilters, [])
    }

    func testSelectAllTicksEveryRowInSightAndNeverOneAFilterHides() async {
        let vm = await makeVM()
        vm.onQuickFilterToggle(.thirtyOrLess)
        vm.onStartSelecting()
        XCTAssertFalse(vm.uiState.allSelected)

        vm.onSelectAll()
        XCTAssertEqual(vm.uiState.selectedIds, [1, 3])
        XCTAssertTrue(vm.uiState.allSelected)

        vm.onQuickFilterToggle(.thirtyOrLess)
        XCTAssertEqual(vm.uiState.selectedIds, [1, 3], "the row the filter hid was never ticked")
        XCTAssertFalse(vm.uiState.allSelected)
    }

    func testSelectAllDoesNothingWhenNotSelecting() async {
        let vm = await makeVM()
        vm.onSelectAll()
        XCTAssertFalse(vm.uiState.selecting)
        XCTAssertTrue(vm.uiState.selectedIds.isEmpty)
    }
}
