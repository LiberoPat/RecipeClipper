import XCTest
@testable import RecipeClipper

/// Android's AddToPlanViewModelTest: the "Add to plan" sheet (#49).
@MainActor
final class AddToPlanViewModelTests: XCTestCase {
    private let plan = FakeMealPlanRepository()
    private let today = FakePlanCalendar.wednesday
    private var monday: Int64 { today - 2 }

    private func viewModel() async -> AddToPlanViewModel {
        let vm = AddToPlanViewModel(repository: plan, calendar: FakePlanCalendar())
        await settleMain()
        return vm
    }

    func testStartsOnTodayDinnerAndTheYieldOverThisWeekAndNext() async {
        let vm = await viewModel()
        vm.setRecipe(7, yieldServings: 4)

        XCTAssertEqual(vm.uiState.days, Array(monday..<(monday + 14)))
        XCTAssertEqual(vm.uiState.selectedDay, today)
        XCTAssertEqual(vm.uiState.selectedMealTypeId, FakeMealPlanRepository.dinner)
        XCTAssertEqual(vm.uiState.servings, 4)
        XCTAssertTrue(vm.uiState.canAdd)
    }

    func testAddsTheChosenDayMealTypeAndServings() async {
        let vm = await viewModel()
        vm.setRecipe(7, yieldServings: 4)
        vm.onDaySelected(monday + 8)
        vm.onMealTypeSelected(FakeMealPlanRepository.snack)
        vm.onServingsChange(6)
        vm.onAdd()
        await settleMain()

        let meal = plan.meals.value.first
        XCTAssertEqual(meal?.recipeId, 7)
        XCTAssertEqual(meal?.day, monday + 8)
        XCTAssertEqual(meal?.mealTypeId, FakeMealPlanRepository.snack)
        XCTAssertEqual(meal?.servings, 6)
        XCTAssertTrue(vm.uiState.added)
        XCTAssertFalse(vm.uiState.canAdd)
    }

    func testAYieldWithNoNumberPlansNoServings() async {
        let vm = await viewModel()
        vm.setRecipe(7, yieldServings: nil)
        vm.onServingsChange(3)
        XCTAssertNil(vm.uiState.servings)
        vm.onAdd()
        await settleMain()
        XCTAssertNil(plan.meals.value.first?.servings)
    }

    func testServingsStayBetween1AndTheMaximum() async {
        let vm = await viewModel()
        vm.setRecipe(7, yieldServings: 1)
        vm.onServingsChange(0)
        XCTAssertEqual(vm.uiState.servings, 1)
    }

    func testAddingTwiceWritesOneMeal() async {
        let vm = await viewModel()
        vm.setRecipe(7, yieldServings: 2)
        vm.onAdd()
        vm.onAdd()
        await settleMain()
        XCTAssertEqual(plan.meals.value.count, 1)
    }

    func testOpeningTheSheetAgainStartsAfresh() async {
        let vm = await viewModel()
        vm.setRecipe(7, yieldServings: 2)
        vm.onMealTypeSelected(FakeMealPlanRepository.snack)
        vm.onAdd()
        await settleMain()

        vm.setRecipe(8, yieldServings: 3)
        XCTAssertFalse(vm.uiState.added)
        XCTAssertEqual(vm.uiState.selectedMealTypeId, FakeMealPlanRepository.dinner)
        XCTAssertEqual(vm.uiState.servings, 3)
    }
}

/// Android's MealTypesViewModelTest: the Week menu's meal types screen (#49).
@MainActor
final class MealTypesViewModelTests: XCTestCase {
    private let plan = FakeMealPlanRepository()

    private func viewModel() async -> MealTypesViewModel {
        let vm = MealTypesViewModel(repository: plan)
        await settleMain()
        return vm
    }

    func testAddsATypeLast() async {
        let vm = await viewModel()
        vm.onStartCreating()
        vm.onNewNameChange(" Brunch ")
        vm.onCreate()
        await vm.settleWrites()
        await settleMain()
        XCTAssertEqual(vm.uiState.types.map(\.name), ["Breakfast", "Lunch", "Dinner", "Snack", "Brunch"])
        XCTAssertFalse(vm.uiState.creating)
    }

    func testRenamesAnyTypeASeededOneIncluded() async {
        let vm = await viewModel()
        vm.onRenameStart(vm.uiState.types.first { $0.id == FakeMealPlanRepository.dinner }!)
        vm.onRenameTextChange("Supper")
        vm.onRenameConfirm()
        await vm.settleWrites()
        await settleMain()
        XCTAssertEqual(vm.uiState.types.first { $0.id == FakeMealPlanRepository.dinner }?.name, "Supper")
        XCTAssertNil(vm.uiState.renaming)
    }

    func testMovesATypeUpAndDownAndNotPastEitherEnd() async {
        let vm = await viewModel()
        vm.onMoveUp(vm.uiState.types.first { $0.id == FakeMealPlanRepository.snack }!)
        await vm.settleWrites()
        XCTAssertEqual(plan.types.value.map(\.id), [1, 2, 4, 3])

        vm.onMoveUp(vm.uiState.types.first!)
        vm.onMoveDown(vm.uiState.types.last!)
        await vm.settleWrites()
        XCTAssertEqual(plan.types.value.map(\.id), [1, 2, 4, 3])
    }

    func testDeletingAUserTypeMovesItsMealsToDinner() async {
        await plan.addMealType(name: "Brunch")
        let brunch = plan.types.value.first { $0.name == "Brunch" }!.id
        await plan.addNote("Pancakes", day: 20_000, mealTypeId: brunch)
        let vm = await viewModel()

        vm.onDeleteStart(vm.uiState.types.first { $0.id == brunch }!)
        vm.onDeleteConfirm()
        await vm.settleWrites()
        await settleMain()

        XCTAssertEqual(vm.uiState.types.map(\.name), ["Breakfast", "Lunch", "Dinner", "Snack"])
        XCTAssertEqual(plan.meals.value.first?.mealTypeId, FakeMealPlanRepository.dinner)
    }

    func testASeededTypeOffersNoDelete() async {
        let vm = await viewModel()
        vm.onDeleteStart(vm.uiState.types.first { $0.id == FakeMealPlanRepository.lunch }!)
        XCTAssertNil(vm.uiState.deleting)
    }
}
