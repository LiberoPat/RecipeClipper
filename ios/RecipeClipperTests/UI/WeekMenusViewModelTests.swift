import XCTest
@testable import RecipeClipper

/// Reusable weekly menus on the Week tab (#52; Android's WeekMenusScreenTest, at the
/// ViewModel): save the week shown, apply a menu (adds, never overwrites), rename, delete.
@MainActor
final class WeekMenusViewModelTests: XCTestCase {
    private let plan = FakeMealPlanRepository()
    private let calendar = FakePlanCalendar()
    private let today = FakePlanCalendar.wednesday

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

    // MARK: Menus keep weekdays across rolling weeks (#232)

    func testAMenuSavedFromAThursdayWeekKeepsItsWeekdaysWhenAppliedToAnother() async {
        let thursday = FakePlanCalendar.thursday
        calendar.todayValue = thursday // the locale's week starts on Monday
        await plan.addNote("Thursday soup", day: thursday, mealTypeId: FakeMealPlanRepository.dinner)
        await plan.addNote("Monday pasta", day: thursday + 4, mealTypeId: FakeMealPlanRepository.dinner)
        let vm = await viewModel()
        vm.onSaveMenu("Usual")
        await settleMain()
        let id = plan.menus.value[0].id
        // Stored from the locale's Monday, as before rolling weeks.
        XCTAssertEqual(plan.menuMeals[id]?.map(\.dayOffset), [3, 0])

        // Opened on Saturday 10, the week runs Saturday to Friday: Monday 12 and Thursday 15.
        calendar.todayValue = thursday + 9
        let later = await viewModel()
        later.onApplyMenu(plan.menus.value[0])
        await settleMain()
        let applied = Array(plan.meals.value.dropFirst(2))
        XCTAssertEqual(applied.map(\.day), [thursday + 14, thursday + 11])
        XCTAssertEqual(applied.map(\.note), ["Thursday soup", "Monday pasta"])
        XCTAssertEqual(applied.map { PlanDays.dayOfWeek($0.day) }, [5, 2])
    }

    func testAMenuSavedBeforeRollingWeeksLandsOnItsWeekdays() async {
        // Saved by the code before #232 from a Monday-first week: offsets from Monday.
        await plan.addNote("Monday pasta", day: today - 2, mealTypeId: FakeMealPlanRepository.dinner)
        await plan.addNote("Friday fish", day: today + 2, mealTypeId: FakeMealPlanRepository.dinner)
        let old = plan.meals.value
        plan.meals.value = []
        plan.menuMeals[50] = [(dayOffset: 0, meal: old[0]), (dayOffset: 4, meal: old[1])]
        plan.menus.value = [WeekMenu(id: 50, name: "Old", mealCount: 2)]

        let vm = await viewModel() // today is a Wednesday: the block runs to Tuesday
        vm.onApplyMenu(plan.menus.value[0])
        await settleMain()
        XCTAssertEqual(plan.meals.value.map(\.day), [today + 5, today + 2])
        XCTAssertEqual(plan.meals.value.map(\.note), ["Monday pasta", "Friday fish"])
    }
}
