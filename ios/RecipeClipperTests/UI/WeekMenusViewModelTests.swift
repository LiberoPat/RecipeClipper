import XCTest
@testable import RecipeClipper

/// Reusable weekly menus on the Week tab (#52; Android's WeekMenusScreenTest, at the
/// ViewModel): save the week shown, apply a menu (adds, never overwrites), rename, delete.
@MainActor
final class WeekMenusViewModelTests: XCTestCase {
    private let plan = FakeMealPlanRepository()
    private let calendar = FakePlanCalendar()
    private let today = FakePlanCalendar.wednesday
    private var monday: Int64 { today - 2 }

    private func viewModel() async -> WeekViewModel {
        let vm = WeekViewModel(plan: plan, recipes: FakeRecipeRepository(), calendar: calendar, sleep: immediateSleep)
        await settleMain()
        return vm
    }

    private func mealCount(_ vm: WeekViewModel) -> Int { vm.uiState.days.reduce(0) { $0 + $1.meals.count } }

    func testTheWeekIsSavedUnderTheTrimmedName() async {
        await plan.addRecipe(recipeId: 7, day: today, mealTypeId: FakeMealPlanRepository.dinner, servings: 4)
        let vm = await viewModel()

        vm.onSaveMenuStart()
        XCTAssertTrue(vm.uiState.menus.saving)
        vm.onSaveMenu("  Busy week  ")
        await settleMain()

        XCTAssertFalse(vm.uiState.menus.saving)
        XCTAssertEqual(vm.uiState.menus.menus.map(\.name), ["Busy week"])
        XCTAssertEqual(vm.uiState.menus.menus.first?.mealCount, 1)
        XCTAssertEqual(vm.uiState.menus.message, .saved(name: "Busy week"))
        vm.onMenuMessageShown()
        XCTAssertNil(vm.uiState.menus.message)
    }

    func testABlankNameSavesNothingAndKeepsThePrompt() async {
        await plan.addRecipe(recipeId: 7, day: today, mealTypeId: FakeMealPlanRepository.dinner, servings: nil)
        let vm = await viewModel()
        vm.onSaveMenuStart()
        vm.onSaveMenu("   ")
        await settleMain()
        XCTAssertTrue(vm.uiState.menus.saving)
        XCTAssertTrue(vm.uiState.menus.menus.isEmpty)
    }

    func testAnEmptyWeekFailsToSave() async {
        let vm = await viewModel()
        vm.onSaveMenuStart()
        vm.onSaveMenu("Nothing")
        await settleMain()
        XCTAssertEqual(vm.uiState.menus.message, .saveFailed)
    }

    func testApplyingAMenuAddsToAnotherWeekWithoutOverwriting() async {
        await plan.addRecipe(recipeId: 7, day: today, mealTypeId: FakeMealPlanRepository.dinner, servings: 4)
        await plan.addNote("Pizza night", day: today + 2, mealTypeId: FakeMealPlanRepository.dinner)
        await plan.addNote("Already here", day: today + 7, mealTypeId: FakeMealPlanRepository.lunch)
        let vm = await viewModel()
        vm.onSaveMenu("Usual")
        await settleMain()

        vm.onNextWeek()
        await settleMain()
        vm.onPickMenuStart()
        XCTAssertTrue(vm.uiState.menus.picking)
        vm.onApplyMenu(vm.uiState.menus.menus[0])
        await settleMain()

        XCTAssertFalse(vm.uiState.menus.picking)
        XCTAssertEqual(mealCount(vm), 3)
        let wednesday = vm.uiState.days.first { $0.day == today + 7 }
        XCTAssertEqual(Set(wednesday?.meals.map { $0.note ?? $0.title ?? "" } ?? []), ["Already here", "Recipe 7"])
        XCTAssertEqual(vm.uiState.menus.message, .applied(name: "Usual", count: 2))
    }

    func testAMenuIsRenamedAndDeleted() async {
        await plan.addRecipe(recipeId: 7, day: today, mealTypeId: FakeMealPlanRepository.dinner, servings: nil)
        let vm = await viewModel()
        vm.onSaveMenu("Old")
        await settleMain()
        let menu = vm.uiState.menus.menus[0]

        vm.onRenameMenuStart(menu)
        XCTAssertEqual(vm.uiState.menus.renaming, menu)
        vm.onRenameMenu("New")
        await settleMain()
        XCTAssertNil(vm.uiState.menus.renaming)
        XCTAssertEqual(vm.uiState.menus.menus.map(\.name), ["New"])

        vm.onDeleteMenuStart(vm.uiState.menus.menus[0])
        vm.onDeleteMenuDismissed()
        XCTAssertNil(vm.uiState.menus.deleting)
        XCTAssertEqual(vm.uiState.menus.menus.count, 1)
        vm.onDeleteMenuStart(vm.uiState.menus.menus[0])
        vm.onDeleteMenuConfirm()
        await settleMain()
        XCTAssertTrue(vm.uiState.menus.menus.isEmpty)
        XCTAssertEqual(mealCount(vm), 1, "the plan is untouched")
    }
}
