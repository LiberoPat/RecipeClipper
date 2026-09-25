import XCTest
@testable import RecipeClipper

/// Android's WeekViewModelTest: the Week tab (#49).
@MainActor
final class WeekViewModelTests: XCTestCase {
    private let plan = FakeMealPlanRepository()
    private let recipes = FakeRecipeRepository()
    private let calendar = FakePlanCalendar()
    private let today = FakePlanCalendar.wednesday
    private var monday: Int64 { today - 2 }

    private func viewModel() -> WeekViewModel {
        WeekViewModel(plan: plan, recipes: recipes, calendar: calendar, sleep: immediateSleep)
    }

    func testOpensOnThisWeekFromTheLocalesFirstDay() async {
        let vm = viewModel()
        await settleMain()
        XCTAssertEqual(vm.uiState.weekStart, monday)
        XCTAssertTrue(vm.uiState.isThisWeek)
        XCTAssertEqual(vm.uiState.days.map(\.day), Array(monday...(monday + 6)))
    }

    func testASundayFirstLocaleStartsTheWeekOnSunday() async {
        calendar.firstDay = FakePlanCalendar.sundayFirst
        let vm = viewModel()
        await settleMain()
        XCTAssertEqual(vm.uiState.days.first?.day, today - 3)
    }

    func testMealsLandOnTheirDayInMealTypeOrder() async {
        await plan.addRecipe(recipeId: 7, day: today, mealTypeId: FakeMealPlanRepository.dinner, servings: 4)
        await plan.addNote("Leftovers", day: today, mealTypeId: FakeMealPlanRepository.lunch)
        await plan.addRecipe(recipeId: 8, day: today + 7, mealTypeId: FakeMealPlanRepository.dinner, servings: nil)
        let vm = viewModel()
        await settleMain()

        let wednesday = vm.uiState.days.first { $0.day == today }
        XCTAssertEqual(wednesday?.meals.map { $0.note ?? $0.title ?? "" }, ["Leftovers", "Recipe 7"])
        XCTAssertEqual(vm.uiState.days.reduce(0) { $0 + $1.meals.count }, 2)
    }

    func testArrowsChangeTheWeekAndThisWeekComesBack() async {
        await plan.addRecipe(recipeId: 8, day: today + 7, mealTypeId: FakeMealPlanRepository.dinner, servings: nil)
        let vm = viewModel()
        await settleMain()

        vm.onNextWeek()
        await settleMain()
        XCTAssertEqual(vm.uiState.weekStart, monday + 7)
        XCTAssertFalse(vm.uiState.isThisWeek)
        XCTAssertEqual(vm.uiState.days.reduce(0) { $0 + $1.meals.count }, 1)

        vm.onPreviousWeek()
        vm.onPreviousWeek()
        await settleMain()
        XCTAssertEqual(vm.uiState.weekStart, monday - 7)

        vm.onThisWeek()
        await settleMain()
        XCTAssertEqual(vm.uiState.weekStart, monday)
        XCTAssertTrue(vm.uiState.isThisWeek)
    }

    func testPlusOnADayAddsARecipeFromHistoryAsDinnerByDefault() async {
        recipes.history.send([testSummary(5, title: "Soup")])
        let vm = viewModel()
        await settleMain()

        vm.onAddToDay(today + 1)
        await settleMain()
        XCTAssertEqual(vm.uiState.adding?.mealTypeId, FakeMealPlanRepository.dinner)
        XCTAssertEqual(vm.uiState.adding?.results?.map(\.title), ["Soup"])

        vm.onAddRecipe(5)
        await settleMain()
        XCTAssertNil(vm.uiState.adding)
        let meal = plan.meals.value.first
        XCTAssertEqual(meal?.recipeId, 5)
        XCTAssertEqual(meal?.day, today + 1)
        XCTAssertEqual(meal?.mealTypeId, FakeMealPlanRepository.dinner)
        XCTAssertNil(meal?.servings)
    }

    func testWhatIsTypedCanBePlannedAsANoteInTheChosenMealType() async {
        let vm = viewModel()
        await settleMain()
        vm.onAddToDay(today)
        vm.onAddMealTypeSelected(FakeMealPlanRepository.breakfast)
        vm.onAddQueryChange("  Eat out  ")
        vm.onAddNote()
        await settleMain()

        XCTAssertEqual(plan.meals.value.first?.note, "Eat out")
        XCTAssertEqual(plan.meals.value.first?.mealTypeId, FakeMealPlanRepository.breakfast)
    }

    func testABlankNoteIsNotAdded() async {
        let vm = viewModel()
        await settleMain()
        vm.onAddToDay(today)
        vm.onAddQueryChange("   ")
        vm.onAddNote()
        await settleMain()
        XCTAssertTrue(plan.meals.value.isEmpty)
    }

    func testMovingOffersThisWeekAndNextAndWritesTheNewSlot() async {
        await plan.addRecipe(recipeId: 7, day: today, mealTypeId: FakeMealPlanRepository.dinner, servings: 2)
        let vm = viewModel()
        await settleMain()
        let meal = vm.uiState.days.first { $0.day == today }!.meals[0]

        vm.onMoveStart(meal)
        XCTAssertEqual(vm.uiState.moving?.days, Array(monday..<(monday + 14)))
        XCTAssertEqual(vm.uiState.moving?.day, today)

        vm.onMoveDaySelected(monday + 9)
        vm.onMoveMealTypeSelected(FakeMealPlanRepository.lunch)
        vm.onMoveConfirm()
        await settleMain()

        XCTAssertNil(vm.uiState.moving)
        let moved = plan.meals.value.first
        XCTAssertEqual(moved?.day, monday + 9)
        XCTAssertEqual(moved?.mealTypeId, FakeMealPlanRepository.lunch)
        XCTAssertEqual(moved?.servings, 2)
    }

    func testRemovingCanBeUndone() async {
        await plan.addRecipe(recipeId: 7, day: today, mealTypeId: FakeMealPlanRepository.dinner, servings: nil)
        let vm = viewModel()
        await settleMain()
        let meal = vm.uiState.days.first { $0.day == today }!.meals[0]

        vm.onRemove(meal)
        await settleMain()
        XCTAssertTrue(plan.meals.value.isEmpty)
        XCTAssertEqual(vm.uiState.removed?.label, "Recipe 7")

        vm.onUndoRemove()
        await settleMain()
        XCTAssertNil(vm.uiState.removed)
        XCTAssertEqual(plan.meals.value.map(\.id), [meal.id])
    }

    func testARemovalStandsOnceTheSnackbarGoes() async {
        await plan.addRecipe(recipeId: 7, day: today, mealTypeId: FakeMealPlanRepository.dinner, servings: nil)
        let vm = viewModel()
        await settleMain()
        vm.onRemove(vm.uiState.days.first { $0.day == today }!.meals[0])
        await settleMain()

        vm.onSnackbarDismissed()
        vm.onUndoRemove()
        await settleMain()
        XCTAssertTrue(plan.meals.value.isEmpty)
    }
}
