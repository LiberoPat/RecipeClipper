import XCTest
@testable import RecipeClipper

/// Android's UseItUpViewModelTest (#305): the rows' counts, the sheet from a row or the
/// reminder, and one tap that plans a recipe once.
@MainActor
final class UseItUpViewModelTests: XCTestCase {
    private let calendar = FakePlanCalendar()
    private lazy var today = calendar.todayValue
    private let lists = FakeListRepository()
    private let plan = FakeMealPlanRepository()
    private var on = true

    private func item(_ id: Int64, _ name: String, _ expires: Int64?) -> PantryItem {
        PantryItem(id: id, name: name, quantity: nil, language: "en", aisle: .other, inStock: true, alwaysHave: false,
                   purchasedDay: nil, expiresDay: expires)
    }

    private func viewModel() -> UseItUpViewModel {
        let pantry = FakePantryRepository([item(1, "spinach", today + 1), item(2, "milk", today), item(3, "flour", today + 5)])
        lists.savedRecipes.value = [
            SavedRecipe(id: 10, title: "Saag", imageUrl: nil, language: "en", lines: ["500 g spinach"]),
            SavedRecipe(id: 11, title: "Pancakes", imageUrl: nil, language: "en", lines: ["1 cup milk", "2 cups flour"]),
            SavedRecipe(id: 12, title: "Spinach pie", imageUrl: nil, language: "en", lines: ["For the filling:", "300 g spinach"]),
        ]
        return UseItUpViewModel(pantry: pantry, lists: lists, plan: plan, calendar: calendar, isOn: { [unowned self] in on })
    }

    func testRowsExpiringSoonCountTheSavedRecipesThatUseThem() async {
        let vm = viewModel()
        await settleMain()
        // Flour expires in five days: outside the reminder's window, so no line.
        XCTAssertEqual(vm.uiState.counts, [1: 2, 2: 1])
        XCTAssertNil(vm.uiState.sheet)
    }

    func testARowOpensTheSheetOnThatItemOnly() async {
        let vm = viewModel()
        await settleMain()
        vm.onOpen(itemId: 1)
        XCTAssertEqual(vm.uiState.sheet?.map(\.item.name), ["spinach"])
        XCTAssertEqual(vm.uiState.sheet?.first?.recipes.map(\.id), [10, 12])
        vm.onDismiss()
        XCTAssertNil(vm.uiState.sheet)
    }

    func testTheReminderOpensEverythingDueSoonestFirst() async {
        let vm = viewModel()
        await settleMain()
        vm.onOpenAll()
        XCTAssertEqual(vm.uiState.sheet?.map(\.item.name), ["milk", "spinach"])
    }

    func testOneTapPlansTheRecipeAsDinnerOnce() async {
        let vm = viewModel()
        await settleMain()
        vm.onPlan(recipeId: 10, day: today + 1)
        vm.onPlan(recipeId: 10, day: today + 1)
        await settleMain()
        XCTAssertEqual(plan.meals.value.map(\.recipeId), [10])
        XCTAssertEqual(plan.meals.value.first?.day, today + 1)
        XCTAssertEqual(plan.meals.value.first?.mealTypeId, FakeMealPlanRepository.dinner)
        XCTAssertNil(plan.meals.value.first?.servings)
        XCTAssertEqual(vm.uiState.planned, [PlannedRecipeDay(recipeId: 10, day: today + 1)])
    }

    // #332: the tab's ViewModel outlives the day; the window and "Plan today" follow the real one.
    func testTodayFollowsTheRealDayWhenThePantryResumesOrTheReminderIsTapped() async {
        let vm = viewModel()
        await settleMain()
        calendar.todayValue = today + 1
        vm.onScreenResumed()
        await settleMain(until: { vm.uiState.today == self.today + 1 })
        // Milk expired yesterday; spinach is due today.
        XCTAssertEqual(vm.uiState.counts, [1: 2])

        calendar.todayValue = today + 2
        vm.onOpenAll()
        await settleMain(until: { vm.uiState.today == self.today + 2 })
        XCTAssertNil(vm.uiState.sheet)
        XCTAssertEqual(vm.uiState.counts, [:])
    }

    // #332: a reminder with nothing to show is forgotten at once, so a recipe saved later never
    // pops the sheet up by itself; one tapped before the first matching waits for it.
    func testARequestNothingAnswersIsDroppedAndNeverOpensTheSheetLater() async {
        let vm = viewModel()
        lists.savedRecipes.value = []
        vm.onOpenAll()
        await settleMain()
        XCTAssertNil(vm.uiState.sheet)
        lists.savedRecipes.value = [SavedRecipe(id: 10, title: "Saag", imageUrl: nil, language: "en", lines: ["500 g spinach"])]
        await settleMain(until: { vm.uiState.counts == [1: 1] })
        XCTAssertNil(vm.uiState.sheet)

        let cold = viewModel()
        cold.onOpenAll()
        await settleMain(until: { cold.uiState.sheet != nil })
        XCTAssertEqual(cold.uiState.sheet?.map(\.item.name), ["milk", "spinach"])
    }

    // #332: a write that failed frees the tap, so it can be tried again.
    func testAFailedPlanCanBeTriedAgain() async {
        let vm = viewModel()
        await settleMain()
        plan.failAdds = true
        vm.onPlan(recipeId: 10, day: today)
        await settleMain()
        XCTAssertEqual(plan.meals.value.count, 0)
        plan.failAdds = false
        vm.onPlan(recipeId: 10, day: today)
        await settleMain()
        XCTAssertEqual(plan.meals.value.map { PlannedRecipeDay(recipeId: $0.recipeId!, day: $0.day) }, [PlannedRecipeDay(recipeId: 10, day: today)])
    }

    // #332: the groups are matched once per change of what they're made from: a recipe written
    // again unchanged (a tick, a cook step) or a plan change reuses them.
    func testMatchingSkipsWhatHasNotChanged() async {
        let vm = viewModel()
        await settleMain()
        XCTAssertEqual(vm.matchings, 1)
        lists.savedRecipes.value = lists.savedRecipes.value
        vm.onPlan(recipeId: 10, day: today)
        await settleMain()
        XCTAssertEqual(vm.uiState.planned, [PlannedRecipeDay(recipeId: 10, day: today)])
        XCTAssertEqual(vm.matchings, 1)
        lists.savedRecipes.value = Array(lists.savedRecipes.value.dropLast())
        await settleMain(until: { vm.uiState.counts == [1: 1, 2: 1] })
        XCTAssertEqual(vm.matchings, 2)
    }

    func testOffNothingShows() async {
        on = false
        let vm = viewModel()
        await settleMain()
        vm.onOpen(itemId: 1)
        XCTAssertEqual(vm.uiState.counts, [:])
        XCTAssertNil(vm.uiState.sheet)
    }
}
