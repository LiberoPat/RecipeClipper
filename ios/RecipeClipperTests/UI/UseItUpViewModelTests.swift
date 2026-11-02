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

    func testOffNothingShows() async {
        on = false
        let vm = viewModel()
        await settleMain()
        vm.onOpen(itemId: 1)
        XCTAssertEqual(vm.uiState.counts, [:])
        XCTAssertNil(vm.uiState.sheet)
    }
}
